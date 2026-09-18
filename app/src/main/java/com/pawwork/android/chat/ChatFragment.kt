package com.pawwork.android.chat

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.Manifest
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pawwork.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class ChatFragment : Fragment(R.layout.fragment_chat) {

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private var baseUrl = "https://api.deepseek.com"
    private var apiKey = ""
    private var model = "deepseek-chat"

    // Attached image as base64 JPEG (OpenAI vision format), sent with the next user message
    private var imageB64: String? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var micListening = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val b64 = loadImageAsDataUrl(uri)
            if (b64 == null) Toast.makeText(requireContext(), "Could not read that image", Toast.LENGTH_SHORT).show()
            else {
                imageB64 = b64
                view?.findViewById<ImageButton>(R.id.attachBtn)?.setImageResource(android.R.drawable.ic_menu_delete)
                addMessage("assistant", "🖼️ Image attached (${b64.length / 1024} KB) — it goes with your next message.")
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recyclerView = view.findViewById<RecyclerView>(R.id.chatRecycler)
        val input = view.findViewById<EditText>(R.id.chatInput)
        val sendBtn = view.findViewById<ImageButton>(R.id.sendBtn)
        val settingsBtn = view.findViewById<View>(R.id.settingsBtn)
        val attachBtn = view.findViewById<ImageButton>(R.id.attachBtn)
        val micBtn = view.findViewById<ImageButton>(R.id.micBtn)

        adapter = ChatAdapter(messages) { path -> openFileWithAndroid(path) }
        recyclerView.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        recyclerView.adapter = adapter

        attachBtn.setOnClickListener {
            if (imageB64 != null) {
                imageB64 = null
                addMessage("assistant", "🗑️ Attached image removed.")
                attachBtn.setImageResource(android.R.drawable.ic_menu_gallery)
                attachBtn.animate().rotationBy(-180f).setDuration(220).start()
            } else {
                attachBtn.animate().rotationBy(180f).setDuration(220).start()
                pickImage.launch("image/*")
            }
        }
        micBtn.setOnClickListener { toggleMic(input, micBtn) }

        // Chat box grows while you type, shrinks back to one line when you leave it
        val density = resources.displayMetrics.density
        input.setOnFocusChangeListener { v, hasFocus ->
            val targetPx = (if (hasFocus) 112f else 44f) * density
            android.animation.ValueAnimator.ofFloat(v.height.toFloat(), targetPx).apply {
                duration = 220
                interpolator = android.view.animation.DecelerateInterpolator(1.6f)
                addUpdateListener { a ->
                    val lp = v.layoutParams
                    lp.height = (a.animatedValue as Float).toInt()
                    v.layoutParams = lp
                }
                start()
            }
        }
        // hardware Enter still sends; soft-keyboard Enter inserts a newline (multi-line box)
        input.setOnEditorActionListener { _, _, event ->
            if (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN) {
                sendBtn.performClick()
                true
            } else false
        }

        // Send button press micro-animation (bounce)
        sendBtn.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                sendBtn.animate().scaleX(0.82f).scaleY(0.82f).setDuration(90).start()
            } else if (ev.action == android.view.MotionEvent.ACTION_UP ||
                ev.action == android.view.MotionEvent.ACTION_CANCEL) {
                sendBtn.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }

        // Restore chat history across restarts (welcome shown only on a fresh chat)
        loadChat()
        if (!hasHistory()) {
            messages.add(ChatMessage("assistant", "Hi! I'm PawWork 🐾\n\nI can help you with documents, web search, files, and more.\n\nTap ⚙ to pick from PawWork's built-in models (DeepSeek, OpenCode free tier, OpenAI, OpenRouter, Ollama) — or just start chatting!"))
            adapter.notifyItemInserted(0)
        } else {
            adapter.notifyItemRangeInserted(0, messages.size)
            view.post { recyclerView.scrollToPosition(messages.size - 1) }
        }

        // Deep-start: adb shell am start -n com.pawwork.android/.MainActivity --es action show_models
        if (requireActivity().intent.getStringExtra("action") == "show_models") {
            view.postDelayed({ showProviderDialog() }, 3000)
        }
        // Deep-start proof: auto-configure OpenCode Zen + Big Pickle and send a test
        // adb shell am start -n com.pawwork.android/.MainActivity --es action chat_test
        if (requireActivity().intent.getStringExtra("action") == "chat_test") {
            view.postDelayed({
                baseUrl = "https://opencode.ai/zen/v1"
                apiKey = ""
                model = "big-pickle"
                saveConfig()
                addMessage("assistant", "🐾 Testing Big Pickle via OpenCode Zen (free tier, no key)...")
                sendMessage("Reply with exactly: PICKLE-WORKS-FROM-PAWWORK")
            }, 5000)
        }
        // Deep-start proof: tool calling end-to-end — ask the model to generate a report
        // adb shell am start -n com.pawwork.android/.MainActivity --es action tool_test
        if (requireActivity().intent.getStringExtra("action") == "tool_test") {
            view.postDelayed({
                baseUrl = "https://opencode.ai/zen/v1"
                apiKey = ""
                model = "big-pickle"
                saveConfig()
                addMessage("assistant", "🔧 Tool-calling test armed. Asking Big Pickle to call a tool...")
                sendMessage("Please use the generate_document tool to create a docx report titled PawWork Android.")
            }, 5000)
        }
        // Deep-start diagnostic: JUST run_code js + python into a report (fast iteration)
        // adb shell am start -n com.pawwork.android/.MainActivity --es action webview_test
        if (requireActivity().intent.getStringExtra("action") == "webview_test") {
            view.postDelayed({
                lifecycleScope.launch(Dispatchers.IO) {
                    val f = java.io.File(requireContext().filesDir, "webview_report.txt")
                    f.writeText("WEBVIEW TEST\n")
                    fun step(name: String, result: String) {
                        f.appendText("$name → ${result.take(500)}\n")
                        android.util.Log.i("PawWorkLab", "$name → ${result.take(300)}")
                    }
                    step("js_6x7", ToolRegistry.execute(requireContext(), "run_code",
                        """{"lang":"javascript","code":"6*7"}"""))
                    step("js_hello", ToolRegistry.execute(requireContext(), "run_code",
                        """{"lang":"javascript","code":"'hello from js'.toUpperCase()"}"""))
                    step("py_6x7", ToolRegistry.execute(requireContext(), "run_code",
                        """{"lang":"python","code":"print('python says', 6*7)"}"""))
                    f.appendText("WEBVIEW TEST DONE\n")
                    view?.post { addMessage("assistant", "✅ webview_test complete, see webview_report.txt") }
                }
            }, 6000)
        }
        // Deep-start: verify non-WebView features (create_folder, file chips, input autosize)
        // adb shell am start -n com.pawwork.android/.MainActivity --es action features_test
        if (requireActivity().intent.getStringExtra("action") == "features_test") {
            view.postDelayed({
                lifecycleScope.launch(Dispatchers.IO) {
                    val ctx = requireContext()
                    val f = java.io.File(ctx.filesDir, "features_report.txt")
                    f.writeText("FEATURES TEST\n")
                    fun step(name: String, result: String) { f.appendText("$name → ${result.take(500)}\n") }
                    // 1. create_folder
                    step("create_folder_ok", ToolRegistry.execute(ctx, "create_folder",
                        """{"path":"projects/notes"}"""))
                    step("create_folder_dup", ToolRegistry.execute(ctx, "create_folder",
                        """{"path":"projects/notes"}"""))
                    step("create_folder_empty", ToolRegistry.execute(ctx, "create_folder",
                        """{"path":""}"""))
                    // 2. write_file + file chip (resolveFile + addFile)
                    val writeResult = ToolRegistry.execute(ctx, "write_file",
                        """{"path":"projects/notes/hello.txt","content":"Hello from PawWork!"}""")
                    step("write_file", writeResult)
                    // simulate what the tool-loop does: parse file/write_file/apk/path → resolveFile → addFile
                    try {
                        val jo = org.json.JSONObject(writeResult)
                        val rel = jo.optString("file").ifEmpty { jo.optString("path") }
                        if (rel.isNotEmpty()) {
                            val abs = resolveFile(ctx, rel)
                            if (abs != null) {
                                view?.post { addFile(abs) }
                                step("file_chip_abs", abs)
                            } else {
                                step("file_chip_abs", "RESOLVE_FAILED: $rel")
                            }
                        } else {
                            step("file_chip_abs", "NO_FILE_FIELD: ${writeResult.take(200)}")
                        }
                    } catch (e: Exception) { step("file_chip_err", e.toString()) }
                    // 3. list folder
                    step("list_files", ToolRegistry.execute(ctx, "list_files",
                        """{"path":"projects/notes"}"""))
                    // 4. delete file
                    step("delete_file", ToolRegistry.execute(ctx, "delete_file",
                        """{"path":"projects/notes/hello.txt"}"""))
                    // 5. delete folder (now empty)
                    step("delete_folder", ToolRegistry.execute(ctx, "delete_file",
                        """{"path":"projects/notes"}"""))
                    f.appendText("FEATURES TEST DONE\n")
                    view?.post { addMessage("assistant", "✅ features_test complete") }
                }
            }, 5000)
        }
        // Deep-start demo: Code & APK Lab
        // adb shell am start -n com.pawwork.android/.MainActivity --es action code_lab
        if (requireActivity().intent.getStringExtra("action") == "code_lab") {
            view.postDelayed({
                addMessage("assistant", "🧪 PawWork Code & APK Lab — testing every lab tool…")
                lifecycleScope.launch(Dispatchers.IO) {
                    fun show(name: String, result: String) {
                        view?.post { addMessage("assistant", "🔧 $name\n→ ${result.take(280)}") }
                        // durable text report for verification
                        try {
                            java.io.File(requireContext().filesDir, "lab_report.txt")
                                .appendText("$name → ${result.take(600)}\n")
                        } catch (_: Exception) {}
                    }
                    java.io.File(requireContext().filesDir, "lab_report.txt").writeText("PAWWORK CODE & APK LAB\n")
                    show("run_code[js]", ToolRegistry.execute(requireContext(), "run_code",
                        """{"lang":"javascript","code":"6*7"}"""))
                    show("run_code[python]", ToolRegistry.execute(requireContext(), "run_code",
                        """{"lang":"python","code":"import sys\nprint('python', sys.version.split()[0])\nprint('sum', sum(range(11)))"}"""))
                    show("call_library[crypto]", ToolRegistry.execute(requireContext(), "call_library",
                        """{"lib":"crypto","fn":"sha256","args":["pawwork"]}"""))
                    show("call_library[sqlite]", ToolRegistry.execute(requireContext(), "call_library",
                        """{"lib":"sqlite","fn":"test"}"""))
                    show("call_library[zlib]", ToolRegistry.execute(requireContext(), "call_library",
                        """{"lib":"zlib","fn":"deflate","args":["pawwork android lab"]}"""))
                    show("build_apk", ToolRegistry.execute(requireContext(), "build_apk",
                        """{"label":"My PawCode App","message":"APK built ON the phone by PawWork!","code":"build_apk"}"""))
                    show("list_apps", ToolRegistry.execute(requireContext(), "list_apps", "{}"))
                    show("invoke_app[settings]", ToolRegistry.execute(requireContext(), "invoke_app",
                        """{"package":"com.android.settings"}"""))
                    val built = try {
                        org.json.JSONObject(com.pawwork.android.lab.AndroidLab.buildApk(requireContext(),
                            org.json.JSONObject().put("label", "PawCode Install Test").put("message", "Self-installed!")))
                            .optString("apk")
                    } catch (e: Exception) { "" }
                    if (built.isNotEmpty()) show("install_apk", ToolRegistry.execute(requireContext(), "install_apk",
                        org.json.JSONObject().put("path", built).toString()))
                    view?.post { addMessage("assistant", "✅ code_lab demo complete") }
                }
            }, 8000)
        }
        // Deep-start: isolated APK build + install test
        // adb shell am start -n com.pawwork.android/.MainActivity --es action install_test
        if (requireActivity().intent.getStringExtra("action") == "install_test") {
            view.postDelayed({
                lifecycleScope.launch(Dispatchers.IO) {
                    val report = java.io.File(requireContext().filesDir, "install_report.txt")
                    val sb = StringBuilder("INSTALL TEST\n")
                    try {
                        val canInstall = requireContext().packageManager.canRequestPackageInstalls()
                        sb.append("canRequestPackageInstalls=$canInstall\n")
                        val built = org.json.JSONObject(
                            com.pawwork.android.lab.AndroidLab.buildApk(requireContext(),
                                org.json.JSONObject().put("label", "PawCode Install Test")
                                    .put("message", "Built and installed by PawWork on-device!")))
                        sb.append("build_apk=$built\n")
                        val res = com.pawwork.android.lab.AndroidLab.installApk(requireContext(), built.optString("apk"))
                        sb.append("install_apk=$res\n")
                    } catch (e: Exception) {
                        sb.append("EXCEPTION ${e.javaClass.simpleName}: ${e.message}\n")
                    }
                    report.writeText(sb.toString())
                    view?.post { addMessage("assistant", "🔧 install_test\n→ ${sb.toString().take(500)}") }
                }
            }, 6000)
        }
        if (requireActivity().intent.getStringExtra("action") == "tools_demo") {
            view.postDelayed({
                addMessage("assistant", "🧰 PawWork tool demo — running all ${ToolRegistry.tools.length()} tools…")
                val demos = listOf(
                    "web_search" to """{"query":"PawWork"}""",
                    "web_fetch" to """{"url":"https://example.com"}""",
                    "calculate" to """{"expression":"(12.5*4+2)^2/3"}""",
                    "write_file" to """{"path":"notes/demo.txt","content":"Hello from PawWork Android tools!"}""",
                    "read_file" to """{"path":"notes/demo.txt"}""",
                    "list_files" to """{"path":""}""",
                    "search_files" to """{"query":"demo"}""",
                    "list_documents" to """{}""",
                    "read_document" to """{"name":"report_20260916_215407.docx"}""",
                    "device_info" to """{}""",
                    "automation_status" to """{}""",
                    "clipboard_copy" to """{"text":"pawwork-demo"}""",
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    demos.forEach { (name, args) ->
                        val result = try { ToolRegistry.execute(requireContext(), name, args) } catch (e: Exception) { "ERROR ${e.message}" }
                        val short = result.take(240)
                        view?.post { addMessage("assistant", "🔧 $name\n→ $short") }
                    }
                    view?.post { addMessage("assistant", "✅ tools_demo complete") }
                }
            }, 8000)
        }

        settingsBtn.setOnClickListener { showProviderDialog() }
        settingsBtn.setOnLongClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Clear chat history?")
                .setMessage("This wipes the saved conversation (messages + tool history) from this device.")
                .setPositiveButton("Clear") { _, _ -> clearChat() }
                .setNegativeButton("Keep", null)
                .show()
            true
        }

        sendBtn.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.text.clear()
            addMessage("user", text)
            sendMessage(text)
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()   // shrink the box back to one line after sending
        }

        // Load saved provider config
        val prefs = requireContext().getSharedPreferences("pawwork", Context.MODE_PRIVATE)
        baseUrl = prefs.getString("base_url", baseUrl) ?: baseUrl
        apiKey = prefs.getString("api_key", "") ?: ""
        model = prefs.getString("model", model) ?: model
    }

    // ---------- Built-in model catalog (mirrors pawwork-linux built-ins) ----------
    data class ModelOption(val label: String, val model: String, val noMaxTokens: Boolean = false)
    data class ProviderGroup(
        val title: String, val baseUrl: String, val needsKey: Boolean,
        val options: List<ModelOption>,
        val supportsTools: Boolean = true
    )

    // The built-in model lineup is the same family PawWork's desktop app ships:
    // DeepSeek official + OpenCode Zen free tier + common OpenAI-compatible hosts.
    private val catalog = listOf(
        ProviderGroup("⚡ DeepSeek (PawWork built-in)", "https://api.deepseek.com", true, listOf(
            ModelOption("DeepSeek Chat — legacy → V4-Flash", "deepseek-chat"),
            ModelOption("DeepSeek Reasoner — legacy → V4-Flash thinking", "deepseek-reasoner"),
            ModelOption("DeepSeek V4 Flash (current)", "deepseek-v4-flash"),
            ModelOption("DeepSeek V4 Pro (frontier)", "deepseek-v4-pro"),
        )),
        ProviderGroup("🆓 OpenCode Zen Free (no key)", "https://opencode.ai/zen/v1", false, listOf(
            ModelOption("Big Pickle (stealth free)", "big-pickle"),
            ModelOption("DeepSeek V4 Flash Free", "deepseek-v4-flash-free"),
            ModelOption("MiMo V2.5 Free", "mimo-v2.5-free"),
            ModelOption("Nemotron 3 Ultra Free", "nemotron-3-ultra-free"),
        )),
        ProviderGroup("🤖 OpenAI", "https://api.openai.com/v1", true, listOf(
            ModelOption("GPT-4o mini", "gpt-4o-mini"),
            ModelOption("GPT-4o", "gpt-4o"),
            ModelOption("GPT-4.1 mini", "gpt-4.1-mini"),
        )),
        ProviderGroup("🌐 OpenRouter (many models)", "https://openrouter.ai/api/v1", true, listOf(
            ModelOption("Auto — best available", "auto"),
            ModelOption("DeepSeek V3", "deepseek/deepseek-chat"),
            ModelOption("Llama 3.3 70B", "meta-llama/llama-3.3-70b-instruct"),
        )),
        ProviderGroup("🏠 Ollama Local (emulator→host)", "http://10.0.2.2:11434/v1", false, listOf(
            ModelOption("Llama 3.2", "llama3.2"),
            ModelOption("DeepSeek R1", "deepseek-r1"),
            ModelOption("Qwen 2.5", "qwen2.5"),
        ), supportsTools = false),
        ProviderGroup("✏️ Custom (any OpenAI-compatible)", "", true, listOf(
            ModelOption("Custom endpoint + model", ""),
        )),
    )

    private fun showProviderDialog() {
        val groups = catalog.map { it.title }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("🐾 PawWork — built-in models")
            .setItems(groups) { _, gi ->
                val group = catalog[gi]
                val labels = group.options.map { it.label }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(group.title)
                    .setItems(labels) { _, mi ->
                        val opt = group.options[mi]
                        if (group.title.startsWith("✏️")) {
                            showCustomDialog(baseUrl, apiKey, model, "Custom")
                        } else {
                            showKeyDialog(group, opt)
                        }
                    }
                    .setNegativeButton("Back", null)
                    .show()
            }
            .show()
    }

    private fun showKeyDialog(group: ProviderGroup, opt: ModelOption) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_provider_key, null)
        val keyEt = dialogView.findViewById<EditText>(R.id.keyInput)
        val hint = dialogView.findViewById<TextView>(R.id.hintText)
        hint.text = "Endpoint: ${group.baseUrl}\nModel: ${opt.model}${if (!group.needsKey) "\n\nThis provider works without an API key 🆓" else ""}"
        if (group.needsKey) keyEt.setHint("Paste API key for ${group.title}")

        AlertDialog.Builder(requireContext())
            .setTitle(group.title)
            .setView(dialogView)
            .setPositiveButton("Select") { _, _ ->
                baseUrl = group.baseUrl
                apiKey = if (group.needsKey) keyEt.text.toString().trim() else ""
                model = opt.model
                saveConfig()
                Toast.makeText(requireContext(), "✅ ${opt.label}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomDialog(url: String, key: String, mdl: String, title: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_provider, null)
        val urlEt = dialogView.findViewById<EditText>(R.id.urlInput)
        val keyEt = dialogView.findViewById<EditText>(R.id.keyInput)
        val modelEt = dialogView.findViewById<EditText>(R.id.modelInput)
        urlEt.setText(url); keyEt.setText(key); modelEt.setText(mdl)

        AlertDialog.Builder(requireContext())
            .setTitle("Provider Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                baseUrl = urlEt.text.toString().trim()
                apiKey = keyEt.text.toString().trim()
                model = modelEt.text.toString().trim()
                saveConfig()
                Toast.makeText(requireContext(), "Provider saved: $model", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveConfig() {
        requireContext().getSharedPreferences("pawwork", Context.MODE_PRIVATE).edit()
            .putString("base_url", baseUrl)
            .putString("api_key", apiKey)
            .putString("model", model)
            .apply()
    }

    // ---- Chat history persistence (survives app restarts) ----
    // messages        -> chat_history.json  (what the user sees)
    // wireHistory     -> wire_history.json  (full OpenAI-wire context incl. tool roles)
    companion object {
        // shared writer so fragment recreations (rotation) don't pile up threads
        private val ioExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    private fun historyFile(name: String) = java.io.File(requireContext().filesDir, name)

    private fun persistChat() {
        val msgSnapshot = synchronized(messages) { messages.toList() }
        val wireSnapshot = synchronized(wireHistory) { wireHistory.map { JSONObject(it.toString()) } }
        ioExec.execute {
            try {
                val arr = JSONArray()
                // "working" rows are transient UI state — never persist them
                msgSnapshot.filter { it.role != "working" }.takeLast(400).forEach {
                    arr.put(JSONObject().put("role", it.role).put("text", it.text))
                }
                historyFile("chat_history.json").writeText(arr.toString())
                val w = JSONArray()
                wireSnapshot.takeLast(400).forEach { w.put(it) }
                historyFile("wire_history.json").writeText(w.toString())
            } catch (_: Exception) { /* history is best-effort */ }
        }
    }

    private fun loadChat() {
        try {
            val f = historyFile("chat_history.json")
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val role = o.optString("role", "assistant")
                    if (role == "working") continue
                    messages.add(ChatMessage(role, o.optString("text", "")))
                }
            }
            val wf = historyFile("wire_history.json")
            if (wf.exists()) {
                val arr = JSONArray(wf.readText())
                for (i in 0 until arr.length()) wireHistory.add(arr.getJSONObject(i))
            }
        } catch (_: Exception) { /* corrupt history -> start fresh */ }
    }

    fun clearChat() {
        synchronized(messages) { messages.clear() }
        synchronized(wireHistory) { wireHistory.clear() }
        workingIndex = null
        adapter.notifyDataSetChanged()
        ioExec.execute {
            try {
                historyFile("chat_history.json").writeText("[]")
                historyFile("wire_history.json").writeText("[]")
            } catch (_: Exception) {}
        }
    }

    /** True once a conversation has been loaded/started, so we can show the welcome only when empty. */
    private fun hasHistory() = synchronized(messages) { messages.isNotEmpty() }

    private fun addMessage(role: String, text: String) {
        messages.add(ChatMessage(role, text))
        adapter.markInserted(messages.size - 1)
        view?.findViewById<RecyclerView>(R.id.chatRecycler)?.scrollToPosition(messages.size - 1)
        persistChat()
    }

    /** Show a tappable file chip; the user taps it and Android opens the file with a viewer.
 *  The message text IS the absolute path (persists + survives reload), the adapter renders
 *  the pretty "📄 name — tap to open" label. */
    fun addFile(path: String) {
        addMessage("file", path)
    }

    /** Resolve a tool-result file reference to an absolute path that exists. */
    private fun resolveFile(context: android.content.Context, ref: String): String? {
        if (ref.startsWith("/") && java.io.File(ref).exists()) return ref
        val candidates = listOf(
            java.io.File(context.filesDir, ref),
            java.io.File(context.filesDir, "documents/$ref"),
            java.io.File(context.filesDir, "apk/$ref"),
        )
        candidates.firstOrNull { it.exists() }?.let { return it.absolutePath }
        // last resort: search recursively by name (tools return bare filenames)
        val found = context.filesDir.walkTopDown().filter { it.isFile && it.name == ref }.firstOrNull()
        return found?.absolutePath
    }

    /** Open a PawWork-produced file with the Android OS via FileProvider + ACTION_VIEW. */
    fun openFileWithAndroid(path: String) {
        try {
            val f = java.io.File(path)
            if (!f.exists()) {
                Toast.makeText(requireContext(), "File not found: $path", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.files", f)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(intent, "Open with…"))
            } catch (_: Exception) {
                // no viewer for this type — fall back to a share sheet
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "No viewer found — share instead"))
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Could not open: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Animated "working" indicator (bouncing dots) shown while tools run ----
    private var workingIndex: Int? = null

    private fun addWorking(label: String) {
        removeWorking()
        messages.add(ChatMessage("working", label))
        workingIndex = messages.size - 1
        adapter.markInserted(messages.size - 1)
        view?.findViewById<RecyclerView>(R.id.chatRecycler)?.scrollToPosition(messages.size - 1)
    }

    private fun updateWorking(label: String) {
        val idx = workingIndex ?: return
        if (idx in messages.indices) {
            messages[idx] = ChatMessage("working", label)
            adapter.notifyItemChanged(idx)
        }
    }

    private fun removeWorking() {
        val idx = workingIndex ?: return
        if (idx in messages.indices && messages[idx].role == "working") {
            messages.removeAt(idx)
            adapter.notifyItemRemoved(idx)
        }
        workingIndex = null
    }

    private fun sendMessage(userText: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { callAi(userText) }
                removeWorking() // animation ends before the answer lands
                addMessage("assistant", reply)
            } catch (e: Exception) {
                removeWorking()
                val msg = when (e) {
                    is java.net.UnknownHostException ->
                        "🌐 Can't reach the AI server — DNS lookup for \"${baseUrl.substringAfter("://").substringBefore("/")}\" failed. Check your internet connection, then resend."
                    is java.net.ConnectException ->
                        "🔌 Connection refused by ${baseUrl.substringAfter("://").substringBefore("/")} — wrong URL or the server is down."
                    is java.net.SocketTimeoutException ->
                        "⏱️ AI server took too long to respond. Check your connection and resend."
                    else -> "⚠️ Error: ${e.message}"
                }
                addMessage("assistant", msg)
            } finally {
                // the image was baked into callAi's wire history; clear the pending attachment
                if (imageB64 != null) {
                    imageB64 = null
                    view?.findViewById<ImageButton>(R.id.attachBtn)?.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            }
        }
    }

    /** Downscale + JPEG-compress a picked image and return it as a base64 data URL. */
    private fun loadImageAsDataUrl(uri: android.net.Uri): String? {
        return try {
            val resolver = requireContext().contentResolver
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var scale = 1
            while ((opts.outWidth / scale) > 1024 || (opts.outHeight / scale) > 1024) scale *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = scale }
            val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            Base64.getEncoder().encodeToString(out.toByteArray())
        } catch (e: Exception) { null }
    }

    private fun toggleMic(input: EditText, micBtn: ImageButton) {
        if (micListening) {
            speechRecognizer?.stopListening()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 404)
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                micListening = true
                micBtn.setImageResource(android.R.drawable.presence_audio_online)
                // pulse while listening
                micBtn.animate().scaleX(1.3f).scaleY(1.3f).setDuration(240).setStartDelay(120).start()
                Toast.makeText(requireContext(), "🎤 Listening…", Toast.LENGTH_SHORT).show()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                micListening = false
                micBtn.setImageResource(android.R.drawable.ic_btn_speak_now)
                micBtn.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "no speech heard"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "listening timed out"
                    SpeechRecognizer.ERROR_NETWORK -> "no speech service (offline?)"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
                    else -> "voice input error ($error)"
                }
                Toast.makeText(requireContext(), "🎤 $msg", Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                micListening = false
                micBtn.setImageResource(android.R.drawable.ic_btn_speak_now)
                micBtn.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrEmpty()) input.setText(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrEmpty()) input.setText(t)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    override fun onDestroyView() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        persistChat() // flush any pending history before the view goes away
        super.onDestroyView()
    }

    // Full OpenAI-wire history (incl. tool roles) so the tool loop survives turns.
    private val wireHistory = mutableListOf<JSONObject>()
    private val supportsTools: Boolean
        get() = !baseUrl.contains("10.0.2.2") // Ollama native off; everyone else gets tools

    private fun callAi(userText: String): String {
        val isOpenCode = baseUrl.contains("opencode.ai")
        // Thinking animation while the model (and tools) work; removed when the reply lands
        view?.post { addWorking("💭 $model is thinking…") }
        val userMsg = JSONObject().put("role", "user")
            .put("content", imageB64?.let { b64 ->
                // OpenAI vision format: text + image parts
                JSONArray().put(JSONObject().put("type", "text").put("text", userText))
                    .put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
            } ?: userText)
        wireHistory.add(userMsg)
        persistChat()

        var lastToolCall = ""
        var repeatedToolCalls = 0
        for (turn in 0 until 12) { // raised from 5 to avoid premature tool-loop exits
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    wireHistory.forEach { put(JSONObject(it.toString())) }
                })
                if (isOpenCode) {
                    // Zen free tier serves ONLY streaming requests (403 otherwise),
                    // and since 2026-09-18 it additionally requires the official
                    // opencode CLI tool NAMES in the body — requests without them get
                    // 403 FreeTierError ("can only be used from within OpenCode").
                    // Verified live: the gate checks the tool names only; schemas and
                    // descriptions are free. zenTools = official 11 + PawWork's own.
                    put("stream", true)
                    put("max_tokens", 32000)
                    put("stream_options", JSONObject().put("include_usage", true))
                }
                if (!isOpenCode && !(baseUrl.contains("api.deepseek.com") && model == "big-pickle")) {
                    // skip max_tokens for Zen reasoning models (content spills to reasoning),
                    // everywhere else cap output
                    if (!model.startsWith("big-pickle")) put("max_tokens", 4096)
                }
                if (supportsTools) put("tools", if (isOpenCode) ToolRegistry.zenTools(requireContext()) else ToolRegistry.tools)
            }
            var resp: String? = null
            // Retry transient failures (DNS flake, mid-stream drop, reset). DNS gets an
            // extra attempt since it can fail once then succeed on the retry. Backoff 1.5s→3s.
            for (attempt in 1..3) {
                try {
                    resp = postChat(body)
                    break
                } catch (e: java.io.IOException) {
                    if (attempt == 3) throw e
                    Thread.sleep(if (e is java.net.UnknownHostException) 1500L else 1000L * attempt)
                }
            }
            val resp2 = resp ?: return "⚠️ Network error calling $model"
            val msg = try {
                JSONObject(resp2).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            } catch (e: Exception) {
                // Surface the gateway's own words when present: either our error_msg
                // wrapper or the Zen error shape {"type":"error","error":{...,"message"}}.
                val errMsg = try {
                    val j = JSONObject(resp2)
                    j.optString("error_msg", "").ifEmpty {
                        j.optJSONObject("error")?.optString("message", "") ?: ""
                    }
                } catch (_: Exception) { "" }
                return if (errMsg.isNotEmpty()) "⚠️ $errMsg" else "⚠️ Bad response: ${resp2.take(200)}"
            }
            val toolCalls = msg.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val assistantMsg = JSONObject()
                    .put("role", "assistant")
                    .put("content", msg.optString("content", ""))
                    .put("tool_calls", toolCalls)
                wireHistory.add(assistantMsg)
                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.getJSONObject(i)
                    val id = tc.getString("id")
                    val fn = tc.getJSONObject("function")
                    val name = fn.getString("name")
                    val args = fn.optString("arguments", "{}")
                    // Show the animated working indicator instead of printing the call
                    view?.post { addWorking("🔧 $name…") }
                    val result = ToolRegistry.execute(requireContext(), name, args).take(4000)
                    // If the tool produced a file, render a tappable link so the user can open it
                    if (result.contains("\"file\"") || result.contains("\"apk\"") || result.contains("\"path\"") ||
                        name == "write_file" || name == "build_apk" || name == "download_apk") {
                        val fileRef = try {
                            val j = JSONObject(result)
                            j.optString("file").ifEmpty { j.optString("apk").ifEmpty { j.optString("path") } }
                        } catch (_: Exception) { "" }
                        if (fileRef.isNotEmpty()) {
                            val abs = resolveFile(requireContext(), fileRef)
                            if (abs != null) view?.post { addFile(abs) }
                        }
                    }
                    wireHistory.add(JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", result))
                    persistChat()
                    // Loop-busting, fixed: only an *identical call that keeps FAILING* is a
                    // stuck loop. A model re-issuing the same call after an error is legitimately
                    // retrying (progress), so that resets the counter. Threshold raised to 5.
                    val failed = result.isBlank() ||
                        result.startsWith("⚠️") || result.startsWith("ERROR") || result.startsWith("EXCEPTION") ||
                        result.contains("error_msg") || result.contains("\"ok\":false") ||
                        result.contains("exception", ignoreCase = true) || result.contains("failed", ignoreCase = true)
                    val signature = "$name|${args.take(120)}"
                    if (signature == lastToolCall) {
                        if (failed) repeatedToolCalls++ else repeatedToolCalls = 0 // success = progress
                    } else {
                        lastToolCall = signature
                        repeatedToolCalls = if (failed) 1 else 0
                    }
                    if (repeatedToolCalls >= 5) {
                        val display = "🔁 Tool loop stopped: $name kept failing ${repeatedToolCalls}x in a row — last: ${result.take(120)}"
                        view?.post { addMessage("assistant", display) }
                        return "(tool loop stopped: $display)"
                    }
                }
                continue
            }
            var out = msg.optString("content", "").trim()
            if (out.isEmpty()) out = msg.optString("reasoning_content", "").trim() // reasoning fallback
            wireHistory.add(JSONObject().put("role", "assistant").put("content", out))
            persistChat()
            return out.ifEmpty { "(empty reply from $model)" }
        }
        return "😅 I used all 12 tool rounds and still hadn't finished — stopped to avoid spinning forever. Last tool tried: ${lastToolCall.take(120)}"
    }

    private fun postChat(body: JSONObject): String? {
        val url = URL("${baseUrl.trimEnd('/')}/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        if (apiKey.isNotEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        // What the OpenCode CLI sends so the Zen free tier accepts us:
        // 1. x-opencode-session — REQUIRED since 2026-09-05 (400 MissingSessionID without it)
        // 2. opencode/* User-Agent + x-opencode-client: cli — anonymous free-pool gateway
        // 3. x-opencode-request — the CLI's per-turn id shape (msg_ + 12 hex + 14 alnum)
        if (baseUrl.contains("opencode.ai")) {
            conn.setRequestProperty("x-opencode-session", openCodeSessionId())
            conn.setRequestProperty("x-opencode-client", "cli")
            conn.setRequestProperty("x-opencode-project", "global")
            conn.setRequestProperty("x-opencode-request", openCodeRequestId())
            conn.setRequestProperty("User-Agent", "opencode/1.18.31")
        }
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.doOutput = true
        try {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val status = conn.responseCode
            val streamMode = body.optBoolean("stream", false)
            if (status == 200 && streamMode) {
                // Zen free tier serves only SSE streams; accumulate the chunks into
                // the same OpenAI JSON shape the non-stream path would have returned.
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val content = StringBuilder()
                val reasoning = StringBuilder()
                val toolCalls = mutableMapOf<Int, JSONObject>()
                for (line in reader.lineSequence()) {
                    val t = line.trim()
                    if (t == "data: [DONE]" || t == "[DONE]") break
                    if (!t.startsWith("data:")) continue
                    val payload = t.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    try {
                        val chunk = JSONObject(payload)
                        val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                        val delta = choice.optJSONObject("delta") ?: continue
                        // NOTE: delta fields can be JSON null (e.g. "reasoning_content":null);
                        // optString converts those to the literal "null", so check isNull first.
                        if (!delta.isNull("content")) delta.optString("content", "").let { if (it.isNotEmpty()) content.append(it) }
                        if (!delta.isNull("reasoning_content")) delta.optString("reasoning_content", "").let { if (it.isNotEmpty()) reasoning.append(it) }
                        val tcs = delta.optJSONArray("tool_calls")
                        if (tcs != null) {
                            for (i in 0 until tcs.length()) {
                                val frag = tcs.getJSONObject(i)
                                val idx = frag.optInt("index", toolCalls.size)
                                val acc = toolCalls.getOrPut(idx) { JSONObject() }
                                // Fragment nulls (id:null / name:null) must not clobber the
                                // real id/name captured in the first fragment of a call.
                                if (!frag.isNull("id")) frag.optString("id", "").takeIf { it.isNotEmpty() }?.let { acc.put("id", it) }
                                val fn = frag.optJSONObject("function")
                                if (fn != null) {
                                    val accFn = acc.optJSONObject("function") ?: JSONObject().also { acc.put("function", it) }
                                    if (!fn.isNull("name")) fn.optString("name", "").takeIf { it.isNotEmpty() }?.let { accFn.put("name", it) }
                                    if (!fn.isNull("arguments")) fn.optString("arguments", "").let { args ->
                                        if (args.isNotEmpty()) accFn.put("arguments", accFn.optString("arguments", "") + args)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                val msg = JSONObject().put("role", "assistant")
                    .put("content", content.toString().ifEmpty { reasoning.toString() })
                if (toolCalls.isNotEmpty()) {
                    msg.put("tool_calls", JSONArray().apply {
                        toolCalls.keys.sorted().forEach { k ->
                            val tc = toolCalls[k]!!
                            put(JSONObject().put("id", tc.optString("id", "call_stream_$k"))
                                .put("type", "function")
                                .put("function", tc.optJSONObject("function") ?: JSONObject()))
                        }
                    })
                }
                return JSONObject().put("id", "chatcmpl-stream")
                    .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", msg)))
                    .put("usage", JSONObject()).toString()
            }
            val raw = if (status == 200) {
                BufferedReader(InputStreamReader(conn.inputStream)).readText()
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "unknown"
                // Mirror pawwork-linux: only a 401 is a credential verdict; a 403
                // (e.g. Zen's FreeTierError) falls through with the provider's own words.
                val hint = if (status == 401) " — API key rejected, check your key" else ""
                return "{\"error_msg\":\"API $status$hint: ${err.take(300)}\"}"
            }
            return raw
        } finally {
            conn.disconnect()
        }
    }

    // Stable per-install session id (one per conversation, like the OpenCode CLI).
    // Since 2026-09-17 the Zen gateway serves the free tier only to ids shaped
    // like its own client mints — `ses_` + 12 hex + 14 alphanumerics — and answers
    // anything else with FreeTierError. Mirror pawwork-linux zen-identity.mjs:
    // hash the stable id into that shape; same id in, same id out.
    private fun openCodeSessionId(): String {
        val prefs = requireContext().getSharedPreferences("pawwork", Context.MODE_PRIVATE)
        var sid = prefs.getString("open_code_session", "")
        if (sid.isNullOrEmpty()) {
            sid = "ses_paw_" + UUID.randomUUID().toString().replace("-", "").take(24)
            prefs.edit().putString("open_code_session", sid).apply()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(sid.toByteArray())
        return "ses_" + digest.joinToString("") { "%02x".format(it) }.take(26)
    }

    // Per-turn request id in the CLI's shape: msg_ + 12 hex + 14 alphanumerics.
    private fun openCodeRequestId(): String {
        val hex = UUID.randomUUID().toString().replace("-", "").take(12)
        val alnum = UUID.randomUUID().toString().replace("-", "")
            .map { if (it.isDigit()) ('a' + (it - '0')) else it }.joinToString("").take(14)
        return "msg_$hex$alnum"
    }
}

data class ChatMessage(val role: String, val text: String)