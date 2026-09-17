package com.pawwork.android.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.pawwork.android.docs.DocGenerator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * PawWork tool-calling support (like desktop PawWork's tool/skill system).
 * The model can invoke any of these mid-conversation; results feed back into
 * the agentic loop.
 *
 * Mapped from the pawwork-linux / DeepSeek Harness toolset:
 *   office skills  → generate_document / read_document
 *   tool-fs        → read_file / write_file / list_files / search_files / delete_file
 *   web-search     → web_search / web_fetch
 *   session/file   → share_text / clipboard_copy / speak / download
 *   automations    → automation_status
 */
object ToolRegistry {

    // ------------------------------------------------------------------ schemas
    val tools: JSONArray = run {
        val str = { desc: String -> JSONObject().put("type", "string").put("description", desc) }
        val obj = JSONObject().put("type", "object")
        val empty = JSONObject().put("type", "object").put("properties", JSONObject())

        fun docFormat(extra: JSONObject = JSONObject()): JSONObject {
            val props = JSONObject()
                .put("format", JSONObject().put("type", "string")
                    .put("enum", JSONArray().put("docx").put("xlsx").put("pptx").put("pdf")))
                .put("title", str("Short document title"))
            val keys = extra.keys()
            while (keys.hasNext()) { val k = keys.next(); props.put(k, extra.get(k)) }
            return props
        }

        JSONArray().apply {
            put(tool("generate_document",
                "Generate an Office document (docx/xlsx/pptx/pdf) into PawWork's document store.",
                obj.put("type", "object").put("properties", docFormat())
                    .put("required", JSONArray().put("format"))))
            put(tool("read_document",
                "Extract the text content of a stored document (docx, xlsx, pptx, pdf) by filename.",
                obj.put("type", "object").put("properties",
                    JSONObject().put("name", str("Filename from list_documents")))
                    .put("required", JSONArray().put("name"))))
            put(tool("list_documents",
                "List the Office documents stored in PawWork's document store.",
                empty))
            put(tool("web_search",
                "Search the web with DuckDuckGo and return the top result titles, URLs and snippets.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("query", str("Search query")))
                    .put("required", JSONArray().put("query"))))
            put(tool("web_fetch",
                "Fetch the visible text of a web page (HTML stripped). Use for reading articles/pages.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("url", str("Absolute http(s) URL")))
                    .put("required", JSONArray().put("url"))))
            put(tool("read_file",
                "Read a text file from PawWork's private storage (path relative to files dir).",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("path", str("Relative path, e.g. notes/idea.txt")))
                    .put("required", JSONArray().put("path"))))
            put(tool("write_file",
                "Write a text file into PawWork's private storage (notes, drafts, code).",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("path", str("Relative path, e.g. notes/idea.txt"))
                        .put("content", str("Full text content")))
                    .put("required", JSONArray().put("path").put("content"))))
            put(tool("list_files",
                "Recursively list files under a folder of PawWork's private storage.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("path", str("Folder (default: everything)")))
                    .put("required", JSONArray())))
            put(tool("search_files",
                "Find files whose name contains the given text.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("query", str("File-name fragment")))
                    .put("required", JSONArray().put("query"))))
            put(tool("delete_file",
                "Delete a file from PawWork's private storage (safety: only inside app storage).",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("path", str("Relative path of the file to delete")))
                    .put("required", JSONArray().put("path"))))
            put(tool("calculate",
                "Evaluate a math expression safely (numbers, + - * / ^ %, parentheses).",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("expression", str("e.g. (12.5*4+2)^2/3")))
                    .put("required", JSONArray().put("expression"))))
            put(tool("share_text",
                "Open the Android share sheet with the given text (send to any app).",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("text", str("Text to share")))
                    .put("required", JSONArray().put("text"))))
            put(tool("clipboard_copy",
                "Copy text to the Android clipboard.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("text", str("Text to copy")))
                    .put("required", JSONArray().put("text"))))
            put(tool("speak",
                "Speak text aloud with Android text-to-speech.",
                JSONObject().put("type", "object").put("properties",
                    JSONObject().put("text", str("Text to speak")))
                    .put("required", JSONArray().put("text"))))
            put(tool("device_info",
                "Return basic device info (model, Android version, SDK level).",
                empty))
            put(tool("automation_status",
                "Report PawWork automation state: run count and scheduled interval.",
                empty))
            // ---- Code & APK Lab ----
            put(tool("download_apk",
                "Download an APK from F-Droid (by package) or a direct URL; verifies SHA-256 when given.",
                obj.put("type", "object").put("properties",
                    JSONObject().put("fdroidPackage", str("F-Droid package id, e.g. org.fdroid.fdroid"))
                        .put("url", str("Direct APK URL (alternative to fdroidPackage)"))
                        .put("sha256", str("Optional expected SHA-256")))
                    .put("required", JSONArray())))
            put(tool("install_apk",
                "Install an APK file already on the device using Android's installer consent dialog.",
                obj.put("type", "object").put("properties",
                    JSONObject().put("path", str("Absolute path to the APK")))
                    .put("required", JSONArray().put("path"))))
            put(tool("build_apk",
                "Build a real installable APK on-device from PawWork's template, sign it (v1) and return its path.",
                obj.put("type", "object").put("properties",
                    JSONObject().put("label", str("App name shown on screen"))
                        .put("message", str("Text the app displays"))
                        .put("code", str("Optional code/text baked into the app")))
                    .put("required", JSONArray())))
            put(tool("run_code",
                "Run code on-device. lang=python (real CPython via Pyodide/WASM) or lang=javascript.",
                obj.put("type", "object").put("properties",
                    JSONObject().put("lang", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("python").put("javascript")))
                        .put("code", str("Program source")))
                    .put("required", JSONArray().put("lang").put("code"))))
            put(tool("call_library",
                "Call an Android system library: crypto (sha256/sha1/md5/uuid), zlib (deflate/inflate), " +
                    "sqlite (exec/test), math (sin/cos/sqrt/log/pow/random), json (parse/array).",
                obj.put("type", "object").put("properties",
                    JSONObject().put("lib", str("crypto|zlib|sqlite|math|json"))
                        .put("fn", str("Function name"))
                        .put("args", JSONObject().put("type", "array").put("items", str("argument"))))
                    .put("required", JSONArray().put("lib").put("fn"))))
            put(tool("invoke_app",
                "Call into another app: launch by package name, or send an intent action (with optional data URI).",
                obj.put("type", "object").put("properties",
                    JSONObject().put("package", str("Package name, e.g. com.android.settings"))
                        .put("action", str("Intent action, e.g. android.intent.action.VIEW"))
                        .put("data", str("Optional URI data")))
                    .put("required", JSONArray())))
            put(tool("list_apps",
                "List third-party apps installed on the device (package, label, version).",
                empty))
        }
    }

    // ------------------------------------------------------------------ execute
    fun execute(context: Context, name: String, arguments: String): String {
        val args = try { JSONObject(arguments) } catch (_: Exception) { JSONObject() }
        return try {
            when (name) {
                "generate_document" -> generateDocument(context, args)
                "read_document" -> readDocument(context, args)
                "list_documents" -> listDocuments(context)
                "web_search" -> webSearch(args.optString("query"))
                "web_fetch" -> webFetch(args.optString("url"))
                "read_file" -> readFile(context, args.optString("path"))
                "write_file" -> writeFile(context, args)
                "list_files" -> listFiles(context, args.optString("path"))
                "search_files" -> searchFiles(context, args.optString("query"))
                "delete_file" -> deleteFile(context, args.optString("path"))
                "calculate" -> JSONObject().put("result", Calculator.eval(args.optString("expression"))).toString()
                "share_text" -> onMain { shareText(context, args.optString("text")) }
                "clipboard_copy" -> onMain { clipboardCopy(context, args.optString("text")) }
                "speak" -> onMain { speak(context, args.optString("text")) }
                "device_info" -> deviceInfo(context)
                "automation_status" -> automationStatus(context)
                // ---- Code & APK Lab ----
                "download_apk" -> com.pawwork.android.lab.AndroidLab.downloadApk(context, args)
                "install_apk" -> com.pawwork.android.lab.AndroidLab.installApk(context, args.optString("path"))
                "build_apk" -> com.pawwork.android.lab.AndroidLab.buildApk(context, args)
                "run_code" -> when (args.optString("lang")) {
                    "python" -> com.pawwork.android.lab.AndroidLab.runPython(context, args.optString("code"))
                    else -> com.pawwork.android.lab.AndroidLab.runJavaScript(context, args.optString("code"))
                }
                "call_library" -> com.pawwork.android.lab.AndroidLab.callLibrary(context, args)
                "invoke_app" -> com.pawwork.android.lab.AndroidLab.invokeApp(context, args)
                "list_apps" -> com.pawwork.android.lab.AndroidLab.listInstalledApps(context)
                else -> JSONObject().put("error", "unknown tool: $name").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    // ------------------------------------------------------------- main thread
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try { result = block() } finally { latch.countDown() }
        }
        latch.await(15, TimeUnit.SECONDS)
        return result ?: throw IllegalStateException("main-thread op timed out")
    }

    // ------------------------------------------------------------- document
    private fun generateDocument(context: Context, args: JSONObject): String {
        val format = args.optString("format", "docx").lowercase()
        val title = args.optString("title", "PawWork Report")
        val dir = File(context.filesDir, "documents").apply { mkdirs() }
        val file: File = when (format) {
            "xlsx" -> DocGenerator.generateXlsx(dir)
            "pptx" -> DocGenerator.generatePptx(dir)
            "pdf" -> DocGenerator.generatePdf(dir)
            else -> DocGenerator.generateDocx(dir)
        }
        return JSONObject().put("ok", true).put("format", format).put("title", title)
            .put("file", file.name).put("bytes", file.length()).toString()
    }

    private fun listDocuments(context: Context): String {
        val dir = File(context.filesDir, "documents")
        val arr = JSONArray()
        (dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()).forEach {
            arr.put(fileEntry(it))
        }
        return JSONObject().put("documents", arr).toString()
    }

    private fun readDocument(context: Context, args: JSONObject): String {
        val name = args.optString("name")
        val dir = File(context.filesDir, "documents")
        val file = File(dir, name)
        if (!file.exists()) return JSONObject().put("error", "not found: $name").toString()
        val text = when (file.extension.lowercase()) {
            "docx" -> extractDocx(file)
            "xlsx" -> extractXlsx(file)
            "pptx" -> extractPptx(file)
            "pdf" -> extractPdf(file)
            else -> "Unsupported type"
        }
        return JSONObject().put("name", name).put("content", text.take(8000)).toString()
    }

    private fun extractDocx(file: File): String {
        val zip = ZipFile(file)
        val doc = zip.getEntry("word/document.xml") ?: return "Cannot read docx"
        val xml = zip.getInputStream(doc).bufferedReader().readText()
        zip.close()
        return xml.replace(Regex("<[^>]+>"), "").replace("&amp;", "&")
            .replace(Regex("\\s+\\n"), "\n").replace(Regex("\\n\\s*\\n"), "\n\n").trim()
    }

    private fun extractXlsx(file: File): String {
        val zip = ZipFile(file)
        val shared = zip.getEntry("xl/sharedStrings.xml")
        val sharedStrings = if (shared != null) {
            val xml = zip.getInputStream(shared).bufferedReader().readText()
            Regex("<t[^>]*>([^<]+)</t>").findAll(xml).map { it.groupValues[1] }.toList()
        } else emptyList()
        val sheet = zip.getEntry("xl/worksheets/sheet1.xml") ?: return "No sheet"
        val xml = zip.getInputStream(sheet).bufferedReader().readText()
        zip.close()
        val sb = StringBuilder("📊 Spreadsheet\n")
        val rows = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        var rn = 0
        for (row in rows) {
            rn++
            val cells = Regex("<c[^>]*>(.*?)</c>", RegexOption.DOT_MATCHES_ALL).findAll(row.value)
            val vals = mutableListOf<String>()
            for (c in cells) {
                val inner = c.groupValues[1]
                val isStr = c.value.contains("t=\"s\"")
                val v = Regex("<v>([^<]+)</v>").find(inner)?.groupValues?.get(1) ?: ""
                vals.add(if (isStr && v.isNotEmpty()) {
                    val idx = v.toIntOrNull() ?: -1
                    if (idx in sharedStrings.indices) sharedStrings[idx] else v
                } else v)
            }
            if (vals.any { it.isNotEmpty() }) sb.append("row $rn: ").append(vals.joinToString(" | ")).append("\n")
        }
        return sb.toString()
    }

    private fun extractPptx(file: File): String {
        val zip = ZipFile(file)
        val slides = zip.entries().toList().filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.name }
        val sb = StringBuilder("📑 PPTX (${slides.size} slides)\n")
        for ((i, e) in slides.withIndex()) {
            val xml = zip.getInputStream(e).bufferedReader().readText()
            val texts = Regex("<a:t>([^<]*)</a:t>").findAll(xml).map { it.groupValues[1] }
            sb.append("— Slide ${i + 1} —\n").append(texts.joinToString(" ")).append("\n")
        }
        zip.close()
        return sb.toString()
    }

    private fun extractPdf(file: File): String {
        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        // crude but works for PawWork's generated PDFs (BT ... Tj / TJ text)
        val sb = StringBuilder("📕 PDF text\n")
        val tj = Regex("\\(([^)]*)\\)\\s*Tj").findAll(raw)
        for (m in tj) sb.append(m.groupValues[1]).append(" ")
        return sb.toString().trim().ifEmpty { "(no extractable text — ${file.length()} bytes)" }
    }

    // ------------------------------------------------------------- web
    private fun webSearch(query: String): String {
        val url = URL("https://html.duckduckgo.com/html/?q=" +
            URLEncoder.encode(query, "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) PawWork/1.0")
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        val html = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val titles = Regex("class=\"result__a\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { it.groupValues[1] }.take(5).toList()
        val urls = Regex("result__a\"[^>]*href=\"([^\"]+)\"").findAll(html)
            .map { it.groupValues[1] }.take(5).toList()
        val snips = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { it.groupValues[1] }.take(5).toList()
        val arr = JSONArray()
        for (i in titles.indices) {
            arr.put(JSONObject()
                .put("title", titles[i].replace(Regex("<[^>]+>"), "").trim())
                .put("url", urls.getOrElse(i) { "" })
                .put("snippet", snips.getOrElse(i) { "" }.replace(Regex("<[^>]+>"), "").trim()))
        }
        return JSONObject().put("query", query).put("results", arr).toString()
    }

    private fun webFetch(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) PawWork/1.0")
        conn.connectTimeout = 15000; conn.readTimeout = 25000
        val html = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val text = html.replace(Regex("<(script|style)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&").replace("&nbsp;", " ").replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ").trim()
        return JSONObject().put("url", urlStr).put("text", text.take(8000)).toString()
    }

    // ------------------------------------------------------------- files
    private fun fileEntry(f: File): JSONObject = JSONObject()
        .put("name", f.name)
        .put("path", f.absolutePath.removePrefix(File(f.absolutePath).parentFile?.parentFile?.absolutePath ?: ""))
        .put("bytes", f.length())
        .put("modified", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(f.lastModified())))

    private fun safeFile(context: Context, rel: String): File {
        val base = context.filesDir
        val f = File(base, rel.trimStart('/'))
        if (!f.canonicalPath.startsWith(base.canonicalPath)) throw SecurityException("outside app storage")
        return f
    }

    private fun readFile(context: Context, rel: String): String {
        val f = safeFile(context, rel)
        if (!f.exists()) return JSONObject().put("error", "not found: $rel").toString()
        return JSONObject().put("path", rel)
            .put("content", f.readText().take(8000)).toString()
    }

    private fun writeFile(context: Context, args: JSONObject): String {
        val f = safeFile(context, args.optString("path"))
        f.parentFile?.mkdirs()
        f.writeText(args.optString("content"))
        return JSONObject().put("ok", true).put("path", args.optString("path"))
            .put("bytes", f.length()).toString()
    }

    private fun listFiles(context: Context, rel: String): String {
        val start = if (rel.isBlank()) context.filesDir else safeFile(context, rel)
        val arr = JSONArray()
        start.walkTopDown().filter { it.isFile }.forEach { arr.put(fileEntry(it)) }
        return JSONObject().put("folder", rel.ifBlank { "(all)" }).put("files", arr).toString()
    }

    private fun searchFiles(context: Context, query: String): String {
        val q = query.lowercase()
        val arr = JSONArray()
        context.filesDir.walkTopDown().filter { it.isFile && it.name.lowercase().contains(q) }
            .forEach { arr.put(fileEntry(it)) }
        return JSONObject().put("query", q).put("matches", arr).toString()
    }

    private fun deleteFile(context: Context, rel: String): String {
        val f = safeFile(context, rel)
        if (!f.exists()) return JSONObject().put("error", "not found: $rel").toString()
        val deleted = f.delete()
        return JSONObject().put("ok", deleted).put("path", rel).toString()
    }

    // ------------------------------------------------------------- device/misc
    private fun deviceInfo(context: Context): String = JSONObject()
        .put("model", android.os.Build.MODEL)
        .put("manufacturer", android.os.Build.MANUFACTURER)
        .put("android", android.os.Build.VERSION.RELEASE)
        .put("sdk", android.os.Build.VERSION.SDK_INT)
        .toString()

    private fun automationStatus(context: Context): String {
        val prefs = context.getSharedPreferences("pawwork_automations", Context.MODE_PRIVATE)
        return JSONObject()
            .put("run_count", prefs.getInt("run_count", 0))
            .put("interval_minutes", 15)
            .put("worker", "PawWorkTaskWorker (WorkManager)")
            .toString()
    }

    private fun shareText(context: Context, text: String): String {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(intent, "Share via PawWork").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return "share sheet opened (${text.take(40)}…)"
    }

    private fun clipboardCopy(context: Context, text: String): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("pawwork", text))
        return "copied ${text.length} chars to clipboard"
    }

    private fun speak(context: Context, text: String): String {
        val latch = CountDownLatch(1)
        val ttsRef = arrayOfNulls<TextToSpeech>(1)
        ttsRef[0] = TextToSpeech(context.applicationContext) { status ->
            val tts = ttsRef[0] ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.getDefault()
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pawwork-tts")
                Handler(Looper.getMainLooper()).postDelayed({
                    tts.stop(); tts.shutdown(); latch.countDown()
                }, 4000)
            } else latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return "spoken: ${text.take(60)}"
    }

    private fun tool(name: String, description: String, params: JSONObject): JSONObject =
        JSONObject().put("type", "function")
            .put("function", JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", params))

    fun describe(): String = buildString {
        for (i in 0 until tools.length()) {
            val fn = tools.getJSONObject(i).getJSONObject("function")
            append("• ").append(fn.getString("name")).append(" — ").append(fn.getString("description")).append("\n")
        }
    }
}

// ------------------------------------------------------------------ calculator
object Calculator {
    private var i = 0
    private lateinit var s: String
    private fun peek(): Char = if (i < s.length) s[i] else '\u0000'
    private fun next(): Char = s[i++]

    private fun atom(): Double {
        if (peek() == '(') { next(); val v = expr(); next(); return v }
        if (peek() == '-') { next(); return -atom() }
        if (peek() == '+') { next(); return atom() }
        val sb = StringBuilder()
        while (peek().isDigit() || peek() == '.') sb.append(next())
        if (peek() == 'e' || peek() == 'E') { sb.append(next()); while (peek().isDigit() || peek() == '+' || peek() == '-') sb.append(next()) }
        return sb.toString().toDouble()
    }
    private fun power(): Double {
        var v = atom()
        while (peek() == '^') { next(); v = Math.pow(v, atom()) }
        return v
    }
    private fun term(): Double {
        var v = power()
        while (true) {
            when (peek()) {
                '*' -> { next(); v *= power() }
                '/' -> { next(); v /= power() }
                '%' -> { next(); v %= power() }
                else -> return v
            }
        }
    }
    private fun expr(): Double {
        var v = term()
        while (true) {
            when (peek()) {
                '+' -> { next(); v += term() }
                '-' -> { next(); v -= term() }
                else -> return v
            }
        }
    }

    fun eval(expression: String): Double {
        check(expression.isNotEmpty()) { "empty expression" }
        i = 0; s = expression.replace(" ", "")
        val result = expr()
        check(i >= s.length) { "trailing chars at ${s.substring(i)}" }
        return result
    }
}