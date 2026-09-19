package com.pawwork.android.lab

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.pawwork.android.chat.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * PawWork "Code & APK Lab" — Android-flavoured analogues of the desktop toolset:
 *  - download_apk  : F-Droid API or direct URL + SHA-256 check
 *  - install_apk   : Android PackageInstaller (user consent dialog)
 *  - build_apk     : create an APK on-device (template + v1 JAR signing) and install it
 *  - run_code      : Python (Pyodide = real CPython in WASM) and JavaScript, on-device
 *  - call_library  : system libraries — crypto, zlib, sqlite, math, JSON
 *  - invoke_app    : call into other installed apps via intents
 *
 * Robustness (2026-09-19): tool calls run on an app-scoped background executor
 * (chat.ToolExecutor) so the LLM can wait minutes for a heavy Python/JS job. The
 * runners no longer die at 30 s (JS) / 240 s (Python): the wait budget comes from the
 * call's `timeout_seconds` (default 25 min) and latch waits are interruptible so the
 * loop can cancel a stuck call. WebView runners are serialized on [codeLock] so two
 * concurrent run_code calls can no longer fight over the single WebView.
 *
 * Storage bridge: Python code gets `paw_read(path)` / `paw_write(path, data)` /
 * `paw_list(path)` and JavaScript gets `window.PawFS` — both talk to REAL PawWork app
 * storage (filesDir), so `write_file` results and images survive across tool calls and
 * are visible to read_file / list_files afterwards.
 */
object AndroidLab {

    /** Default run budget for one run_code call (incl. Pyodide boot). 25 minutes. */
    const val DEFAULT_RUN_TIMEOUT_SECONDS = 1500L

    /** Serialize WebView-backed runners: one shared WebView must not host two evals at once. */
    private val codeLock = Any()

    // ---------------------------------------------------------------- WebView
    @Volatile private var webView: WebView? = null
    @Volatile private var pyodideLoaded = false
    @Volatile private var pageReady = false
    @Volatile private var pageReadyLatch = CountDownLatch(1)
    private val bridge = Bridge()

    class Bridge {
        private var readyLatch: CountDownLatch? = null
        private var resultLatch: CountDownLatch? = null
        @Volatile var pendingResult = ""
        @Volatile var appContext: Context? = null

        @JavascriptInterface fun ready(msg: String) { pyodideLoaded = msg == "ok"; readyLatch?.countDown() }
        @JavascriptInterface fun result(msg: String) { pendingResult = msg; resultLatch?.countDown() }

        fun setReadyLatch(l: CountDownLatch) { readyLatch = l }
        fun setResultLatch(l: CountDownLatch) { resultLatch = l }

        // ------------------------------------------------------- storage bridge
        // Runs on WebView's Java-bridge thread (not the UI thread), so file I/O is safe.
        private fun fsErr(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

        @JavascriptInterface
        fun fsRead(path: String): String {
            val ctx = appContext ?: return fsErr("no app context (call later)")
            return try {
                val base = ctx.filesDir
                val f = File(base, path.trimStart('/'))
                if (!f.canonicalPath.startsWith(base.canonicalPath)) return fsErr("outside app storage: $path")
                if (!f.exists()) return fsErr("not found: $path")
                val bytes = f.readBytes()
                val text = isProbablyText(bytes)
                JSONObject().put("ok", true)
                    .put("path", f.absolutePath)
                    .put("bytes", bytes.size)
                    .put("encoding", if (text) "utf8" else "base64")
                    .put("data", if (text) String(bytes, Charsets.UTF_8)
                        else Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .toString()
            } catch (e: Exception) { fsErr(e.message ?: "fsRead failed") }
        }

        @JavascriptInterface
        fun fsWrite(path: String, data: String): String {
            val ctx = appContext ?: return fsErr("no app context (call later)")
            return try {
                val base = ctx.filesDir
                val f = File(base, path.trimStart('/'))
                if (!f.canonicalPath.startsWith(base.canonicalPath)) return fsErr("outside app storage: $path")
                val bytes = if (data.startsWith("b64:"))
                    Base64.decode(data.removePrefix("b64:"), Base64.NO_WRAP)
                else data.toByteArray(Charsets.UTF_8)
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
                JSONObject().put("ok", true).put("path", f.absolutePath).put("bytes", bytes.size).toString()
            } catch (e: Exception) { fsErr(e.message ?: "fsWrite failed") }
        }

        @JavascriptInterface
        fun fsList(path: String): String {
            val ctx = appContext ?: return fsErr("no app context (call later)")
            return try {
                val base = ctx.filesDir
                val start = if (path.isBlank()) base else File(base, path.trimStart('/'))
                if (!start.canonicalPath.startsWith(base.canonicalPath)) return fsErr("outside app storage: $path")
                val arr = JSONArray()
                if (start.exists()) {
                    start.walkTopDown().filter { it.isFile }.forEach {
                        arr.put(JSONObject().put("path", it.absolutePath.removePrefix(base.absolutePath))
                            .put("bytes", it.length()))
                    }
                }
                JSONObject().put("ok", true).put("path", path.ifBlank { "/" }).put("files", arr).toString()
            } catch (e: Exception) { fsErr(e.message ?: "fsList failed") }
        }

        private fun isProbablyText(bytes: ByteArray): Boolean {
            val n = minOf(bytes.size, 4096)
            for (i in 0 until n) if (bytes[i] == 0.toByte()) return false
            return true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(context: Context) {
        if (webView != null) return
        // WebView MUST be constructed on a Looper-backed (main) thread
        onMain {
            if (webView != null) return@onMain
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            val wv = WebView(context.applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.allowFileAccess = true
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, url: String): android.webkit.WebResourceResponse? {
                    val resp = loader.shouldInterceptRequest(Uri.parse(url))
                    android.util.Log.i("PawWorkLab", "intercept $url -> ${resp?.mimeType ?: "MISS"}")
                    return resp
                }
                @Deprecated("Deprecated in Java")
                override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                    android.util.Log.w("PawWorkLab", "page error $errorCode $description $failingUrl")
                }
                override fun onPageFinished(view: WebView, url: String) {
                    android.util.Log.i("PawWorkLab", "page finished $url")
                    pageReady = true
                    pageReadyLatch.countDown()
                }
                // Renderer crashes (common on slow emulators) must NOT kill the whole app.
                // Marking it handled keeps PawWork alive; the next run_code call recreates the WebView.
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    resetWebView("renderer gone")
                    return true
                }
            }
            wv.addJavascriptInterface(bridge, "AndroidBridge")
            bridge.appContext = context.applicationContext
            webView = wv
        }
        // wait until the main thread has actually created it
        var waited = 0
        while (webView == null && waited < 5000) { Thread.sleep(50); waited += 50 }
    }

    /**
     * Load a real page so the JS interface gets injected (about:blank never injects it on
     * modern WebView). Safe to call repeatedly — only the first call per page navigates.
     */
    private fun loadPage(page: String): Boolean {
        if (pageReady || pyodidePageLoaded) return true
        pageReady = false
        pageReadyLatch = CountDownLatch(1)
        onMain { webView?.loadUrl("https://appassets.androidplatform.net/assets/$page") }
        val ok = pageReadyLatch.await(180, TimeUnit.SECONDS)
        if (!ok) android.util.Log.w("PawWorkLab", "page not ready after 180s: $page")
        return ok && pageReady
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post { block() }
    }

    fun runJavaScript(context: Context, code: String, timeoutSeconds: Long = DEFAULT_RUN_TIMEOUT_SECONDS): String =
        synchronized(codeLock) {
            ensureWebView(context)
            if (!loadPage("blank.html"))
                return@synchronized pythonResultJson(false, "WebView page did not finish loading", "", "")
            val latch = CountDownLatch(1)
            bridge.pendingResult = ""
            bridge.setResultLatch(latch)   // set BEFORE evaluating to avoid a race
            val wvSnapshot = webView
            // expose PawFS (real PawWork storage) to the evaluated code
            val pawfs = "window.PawFS={" +
                "read:function(p){try{return JSON.parse(AndroidBridge.fsRead(p));}catch(e){return {ok:false,error:String(e)}}}," +
                "write:function(p,d,m){try{return JSON.parse(AndroidBridge.fsWrite(p,(m==='b64'?'b64:':'')+d));}catch(e){return {ok:false,error:String(e)}}}," +
                "list:function(p){try{return JSON.parse(AndroidBridge.fsList(p||''));}catch(e){return {ok:false,error:String(e)}}}};"
            onMain {
                wvSnapshot?.evaluateJavascript(
                    "try { (function(){ $pawfs " +
                        "var r = eval(${JSONObject.quote(code)}); " +
                        "AndroidBridge.result('ok|' + JSON.stringify(r)); })(); } catch(e) { AndroidBridge.result('err|' + e); }"
                ) { }
            }
            try {
                if (latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                    val r = bridge.pendingResult
                    if (r.isNotEmpty()) return@synchronized parseRunResult(r, code)
                }
            } catch (_: InterruptedException) {
                return@synchronized pythonResultJson(false, "javascript cancelled", "", "")
            }
            pythonResultJson(false, "javascript timed out after ${timeoutSeconds}s (run_code timeout_seconds)", "", "")
        }

    /** Drop the current WebView so the next call builds a fresh one (post-crash recovery). */
    private fun resetWebView(reason: String) {
        val wv = webView
        webView = null
        pageReady = false
        pyodideLoaded = false
        pyodidePageLoaded = false
        onMain { wv?.destroy() }
        android.util.Log.w("PawWorkLab", "WebView reset: $reason")
    }

    @Volatile private var pyodidePageLoaded = false

    fun runPython(context: Context, code: String, timeoutSeconds: Long = DEFAULT_RUN_TIMEOUT_SECONDS): String =
        synchronized(codeLock) {
            ensureWebView(context)
            if (!pyodideLoaded) {
                val ready = CountDownLatch(1)
                bridge.setReadyLatch(ready)
                onMain {
                    if (!pyodidePageLoaded) {
                        pyodidePageLoaded = true
                        webView?.loadUrl("https://appassets.androidplatform.net/assets/jsrunner.html")
                    } else {
                        webView?.evaluateJavascript("initPyodide();", null)
                    }
                }
                // Pyodide is ~14 MB of WASM; under emulation this can take minutes
                try {
                    if (!ready.await(420, TimeUnit.SECONDS) || !pyodideLoaded)
                        return@synchronized "python runtime not ready (pyodide assets load failed or timed out)"
                } catch (_: InterruptedException) {
                    return@synchronized pythonResultJson(false, "python boot cancelled", "", "")
                }
            }
            val latch = CountDownLatch(1)
            bridge.pendingResult = ""
            bridge.setResultLatch(latch)
            onMain { webView?.evaluateJavascript("runPy(${JSONObject.quote(code)});", null) }
            try {
                if (!latch.await(timeoutSeconds, TimeUnit.SECONDS))
                    return@synchronized pythonResultJson(false,
                        "python timed out after ${timeoutSeconds}s (run_code timeout_seconds) — " +
                            "long jobs run in the background while the model waits", "", "")
            } catch (_: InterruptedException) {
                return@synchronized pythonResultJson(false, "python cancelled", "", "")
            }
            parseRunResult(bridge.pendingResult, code)
        }

    /**
     * Convert the JS bridge's "ok|<stdout>\n(result: X)" / "err|<stderr>" payload into a
     * structured JSON result so the model can actually see and evaluate the program output.
     */
    private fun parseRunResult(raw: String, code: String): String {
        if (raw.isEmpty()) return pythonResultJson(false, "no result from code runner", "", "")
        val sep = raw.indexOf('|')
        if (sep < 0) return pythonResultJson(false, raw.take(400), "", "")
        val ok = raw.startsWith("ok")
        val body = raw.substring(sep + 1)
        // split trailing "(result: ...)" line if present
        var stdout = body
        var result = ""
        val m = Regex("(?s)^(.*)\\n\\(result: (.+)\\)$").find(body.trimEnd())
        if (m != null) {
            stdout = m.groupValues[1]
            result = m.groupValues[2]
        }
        return pythonResultJson(ok, "", stdout, result)
    }

    private fun pythonResultJson(ok: Boolean, err: String, stdout: String, result: String): String =
        JSONObject()
            .put("ok", ok)
            .put("stdout", stdout.take(3000))
            .put("result", result.take(500))
            .put("error", err)
            .toString()

    // ---------------------------------------------------------------- APKs
    fun downloadApk(context: Context, args: JSONObject): String {
        val dir = File(context.filesDir, "apk").apply { mkdirs() }
        val urlStr = args.optString("url")
        val pkg = args.optString("fdroidPackage")
        val url: String = if (urlStr.isNotEmpty()) urlStr else {
            fetch("https://f-droid.org/api/v1/packages/$pkg").trim().let { meta ->
                val j = JSONObject(meta)
                val vc = j.optInt("suggestedVersionCode")
                if (vc == 0) return JSONObject().put("error", "no suggested version for $pkg").toString()
                "https://f-droid.org/repo/$pkg" + "_" + vc + ".apk"
            }
        }
        val name = (args.optString("name").ifEmpty {
            URL(url).path.substringAfterLast('/').ifEmpty { "download_${System.currentTimeMillis()}.apk" }
        })
        val out = File(dir, name)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", "PawWork Android/1.0")
        conn.inputStream.use { input -> out.outputStream().use { output -> input.copyTo(output) } }
        val sha = sha256(out.readBytes())
        val want = args.optString("sha256")
        if (want.isNotEmpty() && !want.equals(sha, ignoreCase = true))
            return JSONObject().put("error", "SHA-256 mismatch").toString()
        return JSONObject().put("ok", true).put("path", out.absolutePath)
            .put("size", out.length()).put("sha256", sha).toString()
    }

    fun installApk(context: Context, apkPath: String): String {
        if (!context.packageManager.canRequestPackageInstalls())
            return JSONObject().put("error",
                "REQUEST_INSTALL_PACKAGES not granted — enable 'Install unknown apps' for PawWork").toString()
        val file = File(apkPath)
        if (!file.exists()) return JSONObject().put("error", "not found: $apkPath").toString()
        val installer = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = try { installer.createSession(params) } catch (e: Exception) {
            return JSONObject().put("error", "session: ${e.message}").toString()
        }
        val session = installer.openSession(sessionId)
        file.inputStream().use { input ->
            session.openWrite("pkg", 0, file.length()).use { out -> input.copyTo(out); session.fsync(out) }
        }
        // Deliver the install result to a BroadcastReceiver (no Activity/window needed —
        // SystemUI ANRs on slow emulators block the Activity result path).
        val pi = android.app.PendingIntent.getBroadcast(
            context, sessionId,
            Intent(context, InstallReceiver::class.java).putExtra(
                android.content.pm.PackageInstaller.EXTRA_SESSION_ID, sessionId),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
        session.commit(pi.intentSender)
        // Poll for the outcome: the package appearing, or the status the receiver got.
        val pm = context.packageManager
        val deadline = System.currentTimeMillis() + 90_000
        var landed = false
        var receiverStatus = -1
        while (System.currentTimeMillis() < deadline) {
            try {
                pm.getPackageInfo("com.pawwork.template", 0)
                landed = true
                break
            } catch (_: Exception) {}
            receiverStatus = InstallReceiver.lastStatus
            if (receiverStatus >= 0 && receiverStatus != android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION
                && receiverStatus != -1) {
                // a terminal status arrived and the package still missing → failure
                try { pm.getPackageInfo("com.pawwork.template", 0); landed = true; break } catch (_: Exception) {}
                break
            }
            Thread.sleep(3000)
        }
        return JSONObject().put("ok", true).put("session", sessionId)
            .put("installed", landed)
            .put("receiver_status", receiverStatus)
            .put("status", when {
                landed -> "✅ installed via PackageInstaller"
                receiverStatus >= 0 -> "install status=$receiverStatus"
                else -> "committed; no result within 90s — install pending/blocked"
            }).toString()
    }

    /** Pyodide runtime files injected into a built APK when lang=python (self-contained app). */
    private val PYODIDE_ASSETS = listOf(
        "pyodide.js", "pyodide.asm.js", "pyodide.asm.wasm", "python_stdlib.zip", "pyodide-lock.json")

    /**
     * Build an installable, signed APK on-device.
     *
     * Arguments (extended 2026-09-19 — built apps now EXECUTE real functionality):
     *   label            app name shown in the built app
     *   message          subtitle text
     *   lang             "js" (default) | "python" — functionality language
     *   code             JavaScript (or Python) source the built app runs on launch
     *   files            optional { "path": "text" } map embedded as assets/files/<path>
     * When lang=python the Pyodide WASM runtime is embedded, so the built APK runs
     * offline CPython (self-contained, ~15 MB larger). JS apps stay tiny (~50 KB).
     */
    fun buildApk(context: Context, args: JSONObject): String {
        val dir = File(context.filesDir, "apk").apply { mkdirs() }
        val templateBytes = context.assets.open("template.apk").readBytes()
        val label = args.optString("label", "PawCode App")
        val message = args.optString("message", "Built by PawWork build_apk")
        val lang = when (args.optString("lang", "js").lowercase()) { "python", "py" -> "python"; else -> "js" }
        val code = args.optString("code", "")
        val config = JSONObject()
            .put("label", label)
            .put("message", message)
            .put("lang", lang)
            .put("code", code)

        val extraAssets = LinkedHashMap<String, ByteArray>()
        // extra functionality/data files → assets/files/<path>
        val files = args.optJSONObject("files") ?: JSONObject()
        val fkeys = files.keys()
        var fileCount = 0
        while (fkeys.hasNext()) {
            val k = fkeys.next()
            extraAssets["assets/files/${k.trimStart('/')}"] = files.getString(k).toByteArray(Charsets.UTF_8)
            fileCount++
        }
        // injected media (audio/video) → assets/media/<name> so the built APK's WebView runner
        // can both list them (Tpl.mediaList) and play them (Tpl.assetRead → blob/stream).
        val media = args.optJSONObject("media") ?: JSONObject()
        val mkeys = media.keys()
        var mediaCount = 0
        while (mkeys.hasNext()) {
            val k = mkeys.next()
            extraAssets["assets/media/$k"] = Base64.decode(media.getString(k).removePrefix("b64:"), Base64.NO_WRAP)
            mediaCount++
        }
        var pyodideBytes = 0L
        if (lang == "python") {
            for (name in PYODIDE_ASSETS) {
                val b = context.assets.open("pyodide/$name").readBytes()
                extraAssets["assets/pyodide/$name"] = b
                pyodideBytes += b.size
            }
        }

        val patched = buildApkZip(templateBytes, "assets/config.json", config.toString().toByteArray(), extraAssets)
        val unsigned = File(dir, "unsigned.apk").apply { writeBytes(patched) }
        val signed = File(dir, "pawwork_build.apk")
        val kp = loadOrCreateKey(context)
        V1Signer.signApk(unsigned, signed, kp)
        return JSONObject().put("ok", true).put("apk", signed.absolutePath)
            .put("size", signed.length()).put("package", "com.pawwork.template")
            .put("lang", lang)
            .put("code_bytes", code.toByteArray(Charsets.UTF_8).size)
            .put("embedded_files", fileCount)
            .put("python_runtime_bytes", pyodideBytes)
            .toString()
    }

    /**
     * Zip surgery: copy template entries (no META-INF, no dirs), atomically replace one
     * entry, and inject extra assets (pyodide runtime, functionality files).
     */
    private fun buildApkZip(
        zipBytes: ByteArray,
        replaceEntry: String,
        newBytes: ByteArray,
        extra: Map<String, ByteArray>,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val written = HashSet<String>()
        ZipOutputStream(out).use { zos ->
            ZipInputStream(zipBytes.inputStream()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (e.isDirectory || e.name.startsWith("META-INF/") || !written.add(e.name)) {
                        e = zin.nextEntry
                        continue
                    }
                    zos.putNextEntry(ZipEntry(e.name))
                    if (e.name == replaceEntry) zos.write(newBytes)
                    else zin.copyTo(zos)
                    zos.closeEntry()
                    e = zin.nextEntry
                }
            }
            extra.forEach { (name, bytes) ->
                if (written.add(name)) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    private fun loadOrCreateKey(context: Context): KeyPair {
        val dir = File(context.filesDir, "signkey")
        val privF = File(dir, "pawwork_pkcs8.der"); val certF = File(dir, "pawwork_cert.der")
        if (privF.exists() && certF.exists()) {
            try {
                val spec = PKCS8EncodedKeySpec(privF.readBytes())
                val priv = KeyFactory.getInstance("RSA").generatePrivate(spec)
                val cert = java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(certF.inputStream()) as X509Certificate
                val pub = cert.publicKey
                return KeyPair(pub, priv)
            } catch (e: Exception) { privF.delete(); certF.delete() }
        }
        val kpg = KeyPairGenerator.getInstance("RSA"); kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val cert = V1Signer.makeCert(kp, "PawWork")
        dir.mkdirs(); privF.writeBytes(kp.private.encoded); certF.writeBytes(cert.encoded)
        return kp
    }

    // ---------------------------------------------------------------- libraries
    fun callLibrary(context: Context, args: JSONObject): String {
        val lib = args.optString("lib")
        val fn = args.optString("fn")
        val a = args.optJSONArray("args") ?: JSONArray()
        fun s(i: Int) = a.optString(i)
        val out = JSONObject()
        when (lib) {
            "crypto" -> when (fn) {
                "sha256" -> out.put("digest", sha256(s(0).toByteArray()))
                "sha1" -> out.put("digest", hex(MessageDigest.getInstance("SHA-1").digest(s(0).toByteArray())))
                "md5" -> out.put("digest", hex(MessageDigest.getInstance("MD5").digest(s(0).toByteArray())))
                "uuid" -> out.put("uuid", java.util.UUID.randomUUID().toString())
                else -> out.put("error", "unknown fn $fn")
            }
            "zlib" -> when (fn) {
                "deflate" -> {
                    val d = Deflater(); d.setInput(s(0).toByteArray()); d.finish()
                    val buf = ByteArray(65536); val n = d.deflate(buf)
                    out.put("base64", Base64.encodeToString(buf.copyOf(n), Base64.NO_WRAP))
                }
                "inflate" -> {
                    val i = Inflater(); i.setInput(Base64.decode(s(0), Base64.NO_WRAP))
                    val bytes = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (!i.finished()) { val n = i.inflate(buf); bytes.write(buf, 0, n) }
                    out.put("text", String(bytes.toByteArray(), Charsets.UTF_8))
                }
                else -> out.put("error", "unknown fn $fn")
            }
            "sqlite" -> when (fn) {
                "exec" -> {
                    val db = android.database.sqlite.SQLiteDatabase.create(null)
                    db.execSQL(s(0)); val cur = db.rawQuery("SELECT sqlite_version() AS v", null)
                    cur.moveToFirst(); out.put("sqlite_version", cur.getString(0)); cur.close(); db.close()
                }
                "test" -> {
                    val db = android.database.sqlite.SQLiteDatabase.create(null)
                    db.execSQL("CREATE TABLE t (x INTEGER, y TEXT)")
                    db.execSQL("INSERT INTO t VALUES (1, 'paw')"); db.execSQL("INSERT INTO t VALUES (2, 'work')")
                    val cur = db.rawQuery("SELECT SUM(x), y FROM t GROUP BY y ORDER BY x", null)
                    cur.moveToFirst(); out.put("rows", cur.count); out.put("sum", cur.getInt(0)); cur.close(); db.close()
                }
                else -> out.put("error", "unknown fn $fn")
            }
            "math" -> when (fn) {
                "sin" -> out.put("result", Math.sin(s(0).toDouble()))
                "cos" -> out.put("result", Math.cos(s(0).toDouble()))
                "sqrt" -> out.put("result", Math.sqrt(s(0).toDouble()))
                "log" -> out.put("result", Math.log(s(0).toDouble()))
                "pow" -> out.put("result", Math.pow(s(0).toDouble(), s(1).toDouble()))
                "random" -> out.put("result", Math.random() * s(0).toDouble())
                else -> out.put("error", "unknown fn $fn")
            }
            "json" -> when (fn) {
                "parse" -> out.put("parsed", JSONObject(s(0)).toString())
                "array" -> out.put("parsed", JSONArray(s(0)).toString())
                else -> out.put("error", "unknown fn $fn")
            }
            "sqlite_raw" -> out.put("x", "use lib=sqlite")
            else -> out.put("error", "unknown lib $lib")
        }
        return out.toString()
    }

    fun invokeApp(context: Context, args: JSONObject): String {
        val pkg = args.optString("package")
        val action = args.optString("action")
        val data = args.optString("data")
        val target: Intent? = when {
            pkg.isNotEmpty() -> context.packageManager.getLaunchIntentForPackage(pkg)
            action.isNotEmpty() -> Intent(action).apply { if (data.isNotEmpty()) setData(Uri.parse(data)) }
            else -> null
        } ?: return JSONObject().put("error", "no launchable app (action=$action pkg=$pkg)").toString()
        target!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(target)
            JSONObject().put("ok", true).put("launched", pkg.ifEmpty { action }).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "cannot launch").toString()
        }
    }

    fun listInstalledApps(context: Context): String {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val out = JSONArray()
        apps.sortedBy { it.loadLabel(pm).toString() }.forEach { ai ->
            val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (!isSystem) {
                out.put(JSONObject().put("package", ai.packageName)
                    .put("label", ai.loadLabel(pm).toString())
                    .put("version", try { pm.getPackageInfo(ai.packageName, 0).versionName } catch (e: Exception) { "" }))
            }
        }
        return JSONObject().put("third_party_apps", out).toString()
    }

    // ---------------------------------------------------------------- helpers
    private fun fetch(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "PawWork Android/1.0")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
    }

    private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    // quoted JS string helper — also used by ChatFragment if needed
    fun quoteJs(s: String): String = JSONObject.quote(s)
}