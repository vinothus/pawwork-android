package com.pawwork.android.lab

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * PawWork "Code & APK Lab" — Android-flavoured analogues of the desktop toolset:
 *  - download_apk  : F-Droid API or direct URL + SHA-256 check
 *  - install_apk   : Android PackageInstaller (user consent dialog)
 *  - build_apk     : create an APK on-device (template + v1 JAR signing) and install it
 *  - run_code      : Python (Pyodide = real CPython in WASM) and JavaScript, on-device
 *  - call_library  : system libraries — crypto, zlib, sqlite, math, JSON
 *  - invoke_app    : call into other installed apps via intents
 */
object AndroidLab {

    // ---------------------------------------------------------------- WebView
    @Volatile private var webView: WebView? = null
    @Volatile private var pyodideLoaded = false
    private val bridge = Bridge()

    class Bridge {
        private var readyLatch: CountDownLatch? = null
        private var resultLatch: CountDownLatch? = null
        @Volatile var pendingResult = ""

        @JavascriptInterface fun ready(msg: String) { pyodideLoaded = msg == "ok"; readyLatch?.countDown() }
        @JavascriptInterface fun result(msg: String) { pendingResult = msg; resultLatch?.countDown() }

        fun setReadyLatch(l: CountDownLatch) { readyLatch = l }
        fun setResultLatch(l: CountDownLatch) { resultLatch = l }
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
                override fun shouldInterceptRequest(view: WebView, url: String) = loader.shouldInterceptRequest(Uri.parse(url))
            }
            wv.addJavascriptInterface(bridge, "AndroidBridge")
            wv.loadUrl("about:blank")
            webView = wv
        }
        // wait until the main thread has actually created it
        var waited = 0
        while (webView == null && waited < 5000) { Thread.sleep(50); waited += 50 }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post { block() }
    }

    fun runJavaScript(context: Context, code: String): String {
        ensureWebView(context)
        val latch = CountDownLatch(1)
        bridge.pendingResult = ""
        bridge.setResultLatch(latch)   // set BEFORE evaluating to avoid a race
        onMain {
            webView?.evaluateJavascript(
                "try { AndroidBridge.result('ok|' + JSON.stringify(eval(${JSONObject.quote(code)}))); } catch(e) { AndroidBridge.result('err|' + e); }"
            ) { }
        }
        latch.await(25, TimeUnit.SECONDS)
        val result = bridge.pendingResult
        return if (result.isEmpty()) "error: no result from WebView (page not ready)" else result.take(2000)
    }

    @Volatile private var pyodidePageLoaded = false

    fun runPython(context: Context, code: String): String {
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
            if (!ready.await(240, TimeUnit.SECONDS) || !pyodideLoaded)
                return "python runtime not ready (pyodide assets load failed or timed out)"
        }
        val latch = CountDownLatch(1)
        bridge.pendingResult = ""
        bridge.setResultLatch(latch)
        onMain { webView?.evaluateJavascript("runPy(${JSONObject.quote(code)});", null) }
        if (!latch.await(120, TimeUnit.SECONDS)) return "python timed out"
        return bridge.pendingResult.ifEmpty { "error: no result from python" }.take(4000)
    }

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

    fun buildApk(context: Context, args: JSONObject): String {
        val dir = File(context.filesDir, "apk").apply { mkdirs() }
        val templateBytes = context.assets.open("template.apk").readBytes()
        // rewrite assets/config.json inside a copy of the template
        val config = JSONObject()
            .put("label", args.optString("label", "PawCode App"))
            .put("message", args.optString("message", "Built by PawWork build_apk"))
            .put("code", args.optString("code", ""))
        val patched = patchZipEntry(templateBytes, "assets/config.json", config.toString().toByteArray())
        val unsigned = File(dir, "unsigned.apk").apply { writeBytes(patched) }
        val signed = File(dir, "pawwork_build.apk")
        val kp = loadOrCreateKey(context)
        V1Signer.signApk(unsigned, signed, kp)
        return JSONObject().put("ok", true).put("apk", signed.absolutePath)
            .put("size", signed.length()).put("package", "com.pawwork.template").toString()
    }

    private fun patchZipEntry(zipBytes: ByteArray, entryName: String, newBytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zos ->
            java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (e.name.endsWith("/")) { e = zin.nextEntry; continue }
                    if (e.name.startsWith("META-INF/")) { e = zin.nextEntry; continue }
                    zos.putNextEntry(java.util.zip.ZipEntry(e.name))
                    if (e.name == entryName) zos.write(newBytes)
                    else zin.copyTo(zos)
                    zos.closeEntry()
                    e = zin.nextEntry
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
                    out.put("base64", Base64.getEncoder().encodeToString(buf.copyOf(n)))
                }
                "inflate" -> {
                    val i = Inflater(); i.setInput(Base64.getDecoder().decode(s(0)))
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