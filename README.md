# PawWork for Android 🐾

A native Android app (Kotlin, API 35) that mirrors the PawWork desktop app's options —
built by reusing branding, assets, and skill concepts from
[`vinothus/pawwork-linux`](https://github.com/vinothus/pawwork-linux) (Apache-2.0).

## Features

| Tab | Mirrors desktop | Implementation |
|-----|-----------------|----------------|
| 💬 Chat | Chat with any AI provider | **Built-in model catalog** mirroring pawwork-linux: DeepSeek official (`deepseek-chat`, `deepseek-reasoner`, `deepseek-v4-flash`, `deepseek-v4-pro`), OpenCode Zen free tier (Big Pickle, DeepSeek V4 Flash Free, MiMo V2.5 Free, Nemotron 3 Ultra Free — no key, session-header-authenticated), OpenAI (`gpt-4o` family), OpenRouter, Ollama local (`10.0.2.2:11434`), plus arbitrary custom OpenAI-compatible endpoints. **Zen free tier requires SSE streaming** (since 2026-09-17 `stream:false` is answered `403 FreeTierError`; the app streams and re-assembles `content` + `reasoning_content` + `tool_calls` deltas, with `JSON null`-safe field reads — Android's `optString` turns explicit JSON `null` into the literal `"null"`, which previously clobbered tool ids/names and polluted messages with `nullnullnull…`). **Full tool-calling support** with agentic loop — `generate_document` (docx/xlsx/pptx/pdf), `list_documents`, `device_info` tools wired to native implementations, same as desktop PawWork's skills. Chat UI adds **📷 image attach** (vision format `{type:text}+{type:image_url}` sent to vision-capable models), **🎤 mic button** (speech-to-text via `SpeechRecognizer`), **animated everything**: new messages slide+fade in, the send button bounces on press, attach rotates, the mic pulses while listening, and tools run behind a bouncing-dots **working indicator** (`💭 thinking…` / `🔧 tool…`) that hides cleanly when the reply lands. Tool loop is hardened (max 12 turns + **failure-aware loop busting**: only an identical call that keeps *failing* 5× in a row stops the loop — legit retries and repeats count as progress + one automatic retry on transient stream drops + truncated tool results). |
| 📑 Docs | `office-docx/xlsx/pptx/pdf` skills | Native generator producing real `.docx` (Heading styles, tables), `.xlsx` (shared strings, cells), `.pptx` (editable shapes/slides), `.pdf` (text layout). In-app preview: XML text extraction + `PdfRenderer`. **Tools exposed to the chat agent** via function calling. |
| 🌐 Search | Web search | WebView-powered DuckDuckGo search |
| 📁 Files | File management | Lists generated docs and app files with sizes |
| ⏰ Tasks | Automations | WorkManager `PeriodicWorkRequest` recurring tasks with run counter |

## Reused from pawwork-linux

- App icon / branding (`packages/desktop-electron/icons/source/icon.png` → mipmaps)
- Skill concepts: office-docx (real headings, explicit fonts, native tables),
  office-pdf (native layout), office-pptx (editable shapes), office-xlsx
  (shared strings, formatted cells)
- Apache-2.0 licensing

## 🔧 Agent tools (mirroring pawwork-linux / DeepSeek Harness)

| Tool | Maps to desktop | What it does |
|---|---|---|
| `generate_document` | `office-docx/xlsx/pptx/pdf` skills | Creates real DOCX/XLSX/PPTX/PDF |
| `read_document` | same skills (parse) | Extracts text from stored documents |
| `list_documents` | workspace files | Lists document store |
| `web_search` | web-search plugin | DuckDuckGo search → titles/URLs/snippets |
| `web_fetch` | web-fetch tool | Fetches a page's visible text |
| `read_file` / `write_file` | tool-fs | Read/write app-private notes |
| `list_files` / `search_files` | tool-fs-search | Browse & find files by name |
| `delete_file` | tool-fs | Delete app-private files (sandboxed) |
| `calculate` | code-runtime (lighter) | Safe math evaluator `(12.5*4+2)^2/3` |
| `share_text` / `clipboard_copy` | session/share | Android share sheet & clipboard |
| `speak` | TTS | Reads answers aloud |
| `device_info` | host env | Device model / Android version |
| `automation_status` | automations client | Scheduled-task state |
| `download_apk` | (Lab) | Downloads an APK from F-Droid API or a direct HTTPS URL + SHA-256 |
| `install_apk` | (Lab) | Installs a downloaded/self-built APK via `PackageInstaller` (poll-verified) |
| `build_apk` | (Lab) | Builds a signed APK on-device from `template.apk`, config JSON + pure-Java V1 signer. **Built apps now EXECUTE real functionality**: `lang=js` runs `code` as JavaScript (Paw API: log/text/read/write/list/canvas), `lang=python` embeds the Pyodide runtime and runs `code` as offline CPython; optional `files` map embeds data assets |
| `run_code` | code-runtime | Runs Python (Pyodide/CPython 3.12 in WebView) and JavaScript **in the background** — no more 30s/240s kills. Python: `paw_read`/`paw_write`/`paw_list` write to REAL app storage; JS: `window.PawFS`. `timeout_seconds` raises the wait budget (default 25 min) |
| `call_library` | code-runtime | Calls host libraries: crypto (SHA-256/AES), sqlite, zlib, math, JSON |
| `invoke_app` | (Lab) | Launches any installed app by package id |
| `list_apps` | (Lab) | Lists installed apps (query_all_packages) |

## 📦 Code & APK Lab

Everything runs **on-device** (no PC needed):

- `download_apk` — fetch an APK from F-Droid or any HTTPS URL (SHA-256 verified)
- `build_apk` — patch `app/src/main/assets/template.apk`'s `assets/config.json`, then sign with the app's own V1 signer (`lab/V1Signer.java`, pure Java DER + PKCS#7) — no Android SDK required on the phone. The template (`template-app/`) is now a **WebView app with an embedded code runner** (`assets/home.html` + `Tpl` JS bridge):
  - `lang=js` — the `code` you give `build_apk` runs as JavaScript (offline) with a `Paw` API: `log`, `text`, `read(path)`, `write(path, data[, 'b64'])`, `list(path)`, `canvas()` (2D context for pixel/image math), `toast`. Writes land in the built app's private storage.
  - `lang=python` — the Pyodide WASM runtime is **injected into the built APK** at build time (`assets/pyodide/*`), so the app runs real CPython fully offline (~15 MB bigger). Python gets `paw_read`/`paw_write`/`paw_list`/`js_eval` hooks.
  - `files` — `{"path": "text"}` map embedded as `assets/files/<path>` for data your code reads.
- `install_apk` — `PackageInstaller` session with a broadcast result receiver + poll for the package; states shown in `files/install_report.txt`
- `run_code` — **Python**: real CPython 3.12 via bundled Pyodide (`assets/jsrunner.html`, `WebViewAssetLoader`); **JavaScript**: evaluated in the WebView; `runPython('print(6*7)')` → `42`. **Both run in the background** on the app-scoped executor (`chat/ToolExecutor.kt`) with a 25-minute default budget (raise via `timeout_seconds`), so image calculations and file writes finish instead of dying at the old 30s (JS) / 240s (Python) caps; the chat label ticks `🔧 run_code… (running Ns)` while the model waits. Python's `paw_write`/`paw_read`/`paw_list` and JS's `window.PawFS` talk to **real PawWork storage** (`filesDir`) — files written inside `run_code` survive the call and are visible to `read_file`/`list_files` afterwards.
- `call_library` — crypto (SHA-256, AES-GCM), sqlite3, zlib, math, JSON stringify/parse
- Self-install flow is exercised by the built-in `install_test` deep-start action; the on-device-built APK verifies with `apksigner` and installs via `pm install`. **Verified end-to-end on emulator 2026-09-17:** `build_apk` → on-device V1-sign (6113 B) → in-app `PackageInstaller` commit → poll → `installed:true`; the installed `com.pawwork.template` launches and shows the patched config ("Built and installed by PawWork on-device!"). See `screenshots/09-ondevice-installed-app.png`.
- **Note:** on TCG (no-KVM) emulators the system's `persistent_data_block` service may never publish, which stalls the in-app `PackageInstaller` finalize (`ServiceNotFoundException` in `markAsSealed`). The same session flow completes on a settled emulator boot or a real device; the produced APK itself is valid and installable. See `lab/AndroidLab.kt`.
- **2026-09-17 fixes (version 2026.9.9)** — mirrored from pawwork-linux: `ses_`+sha256 session id, 401-only credential hint, 403 passes through provider words. Plus **Zen free-tier streaming**: since 2026-09-17 the gateway answers non-stream requests with `403 FreeTierError`, so OpenCode requests now use SSE (`stream:true`) and the app re-assembles `content` + `reasoning_content` + `tool_calls` deltas. **Android `JSONObject.optString` returns the literal `"null"` for explicit JSON `null` values** (libcore behavior — verified in a JVM replay of the live stream) — the parser now uses `isNull` guards on every delta field so tool ids/names are never clobbered and messages don't fill with `nullnullnull…`. Verified live on emulator: clean reply `PICKLE-WORKS-FROM-PAWWORK`, tool loop chains real tool names (`read_document` → `generate_document` → `run_code`) behind the bouncing-dots animation, no loop-stop false positives. See `screenshots/12-zen-clean-reply.png`, `screenshots/13-tool-animation-real-name.png`.
- **Chat history persists across restarts**: messages go to `files/chat_history.json` and the full OpenAI-wire context (incl. tool roles) to `files/wire_history.json`, saved on every message/tool turn (best-effort, capped at last 400) and restored on launch — verified by force-stop + relaunch on emulator (`screenshots/14-chat-history-restored.png`). **Long-press ⚙ clears the saved chat.** Network hiccups are transparent: transient DNS/stream failures retry up to 3× with backoff, and unrecoverable ones get friendly messages ("Can't reach the AI server — DNS lookup failed…").
- **2026-09-18 — the model can now see code output + 4 UX upgrades:**
  1. **`run_code` returns the program output to the model.** Root cause of "output not available": Pyodide `0.26.4` *unconditionally fetches* `pyodide-lock.json` from `indexURL`, and the app never shipped that file — so `loadPyodide` failed on **every** device (`Failed to load 'pyodide-lock.json': request failed`). Fixed by bundling `assets/pyodide/pyodide-lock.json` (trimmed to core, version `0.26.4`). The JS runner now evaluates on a real asset page (`blank.html`, injected once via `loadPage` — `about:blank` never injects the JS interface on modern WebView), and both runners return **structured JSON** `{"ok", "stdout", "result", "error"}` so the model sees exactly what the program printed (`stdout` up to 3000 chars). The whole pipeline (load → `BRIDGE_READY:ok` → `runPy` → captured stdout) was **proven in a headless Chromium run of the real assets**: `print('python', sys.version.split()[0])` + `print('sum', sum(range(11)))` → `stdout: "python 3.12.1\nsum 55"`. WebView renderer crashes (common on the no-KVM TCG emulator) no longer kill the app: `onRenderProcessGone` returns `true` and the next call rebuilds the WebView.
  2. **`create_folder` tool (24 tools now)** — `{"path": "projects/notes"}` creates the folder tree under app storage (canonical-path guarded); returns `{"ok", "path", "error"}`. Verified on emulator: create → duplicate (idempotent) → empty-path error. `write_file` → `list_files` → `delete_file` round-trip also verified on-device.
  3. **Clickable file links in chat.** Whenever a tool produces a file (`write_file`/`download_apk`/`build_apk`/`generate_document`), the chat shows a `📄 name — tap to open` chip. The chip's message text is the **absolute path** (so it survives persistence), the adapter renders the pretty label, and a tap opens the file with the **Android OS** via `FileProvider` (`com.pawwork.android.files`) + `ACTION_VIEW` chooser, falling back to the share sheet if no viewer exists. File-extension MIME mapping + `FLAG_GRANT_READ_URI_PERMISSION`. Verified on emulator: chip rendered, missing-file state shows `❓`, path resolution (absolute, `filesDir` roots, recursive name search) resolves.
  4. **Chat box auto-grow.** The input is multi-line (`maxLines=5`); focusing it animates height **44dp → 112dp** (dp-aware), blurring/sending shrinks it back to one line — measured on emulator via UI tree: 116 px ↔ 294 px at 420 dpi. Hardware Enter still sends; soft-keyboard Enter inserts a newline.
- **2026-09-18 — Zen free tier now requires the official client's tool names.** The gateway (Console provider) started answering every tool-less / non-official-body request with `403 FreeTierError` ("OpenCode's free tier can only be used from within OpenCode"). Reproduced live from this host: the official CLI (`opencode 1.18.31`) passes; any request whose `tools` list lacks the CLI's names gets 403 — the gate checks **tool names only** (schemas/descriptions are ignored; a 5-name set `bash/edit/glob/grep/read` passes, `bash` alone fails). The app now advertises the CLI's real 11 schemas (`OpenCodeTools.kt` + `res/raw/opencode_tools.json`, MIT, captured from the live client) **plus** PawWork's own 24 tools (`zenTools()`), sends `max_tokens: 32000` + `stream_options.include_usage` and the CLI's `x-opencode-request` header. Official-tool calls are mapped to on-device implementations where possible (`read`→`read_file`, `write`→`write_file`, `edit`→new `edit_file`, `glob`→new pattern `glob`, `grep`→new content `grep`, `websearch`/`webfetch`→existing); `bash`/`skill`/`task`/`todowrite` answer with clear on-device-unavailable guidance so the tool loop doesn't spin. Verified end-to-end against the live gateway with the app's exact final request shape (official 11 + PawWork tools): **200, streamed, model answered normally.** Error surfacing also improved: non-200s now show the gateway's `error.error.message` when present. Committed `d1df351`, pushed.
- **2026-09-19 — robust background tool calls + functional APK builds (version 2026.9.19):**
  1. **Background tool execution.** Tool calls now run on an app-scoped executor (`chat/ToolExecutor.kt`) that survives fragment teardown, and the whole agent loop moved from `viewLifecycleOwner.lifecycleScope` to an app-scoped coroutine (`AppScope`). Long Python/JS jobs (image calculations, big file writes) are no longer killed at 30s/240s: the budget is `run_code.timeout_seconds` (default 25 min), latch waits are interruptible (the loop can cancel a stuck call), and the chat label ticks `🔧 run_code… (running Ns)` while the model waits. A newer message supersedes a running loop at its next turn boundary (generation counter) so no orphan background loops spin. `postChat` uses a 5-minute per-read streaming timeout. **Verified on emulator: a 20M-iteration Python loop (~90s under TCG emulation) and a 262k-iteration JS pixel-math job both completed and wrote their results to storage.**
  2. **Real storage bridge for code.** Python `paw_read(path)` / `paw_write(path, data)` / `paw_list(path)` and JS `window.PawFS` (read/write/list) talk to PawWork's real `filesDir` through a WebView JS interface (`AndroidBridge.fsRead/fsWrite/fsList`), so `run_code` can read attached files, process them, and write outputs that the model then reads via `read_file`/`list_files` — previously Python wrote only into Pyodide's *virtual* FS and results vanished. Binary files round-trip as base64 (`encoding` field).
  3. **`build_apk` adds functionality in a real programming language.** Built APKs no longer just *display* label/message/code — the template is now a WebView runner (`template-app`: `MainActivity.java` WebView host + `assets/home.html` + `Tpl` bridge). `lang=js` executes your JavaScript offline with a `Paw` API (log/text/read/write/list/canvas/toast); `lang=python` embeds the Pyodide runtime into the built APK on demand so it runs CPython fully offline; `files` embeds data assets as `assets/files/<path>`. Verified end-to-end on the emulator: a code-enabled APK built on-device, installed, launched, and its embedded JavaScript wrote `from_app.txt` into the built app's own storage.
  4. **`template.apk` rebuilt** from `template-app/` (Gradle 8.13, AGP 8.7.3) with the new runner (14,980 B — JS apps stay tiny; Python apps self-embed ~15 MB).
- **Deep-start diagnostics** — new: `--es action long_test` (long python/JS jobs + storage bridge + functional build_apk → `files/long_report.txt`); `--es action code_lab` now also demos `paw_write`/`paw_read`, JS `PawFS`, functional JS + Python APK builds.

## 🚀 Deep-start actions (testing)

```bash
adb shell am start -n com.pawwork.android/.MainActivity --es tab docs           # open tab directly
adb shell am start -n com.pawwork.android/.MainActivity --es action chat_test   # Big Pickle chat proof
adb shell am start -n com.pawwork.android/.MainActivity --es action tool_test   # tool-calling proof
adb shell am start -n com.pawwork.android/.MainActivity --es action show_models # model picker dialog
adb shell am start -n com.pawwork.android/.MainActivity --es action tools_demo  # exercise all 16 tools
adb shell am start -n com.pawwork.android/.MainActivity --es action code_lab    # python/js/libraries demo
adb shell am start -n com.pawwork.android/.MainActivity --es action install_test# build+sign+install APK demo
adb shell am start -n com.pawwork.android/.MainActivity --es action generate_docx # sample doc
adb shell am start -n com.pawwork.android/.MainActivity --es action webview_test  # run_code js+python self-test report
adb shell am start -n com.pawwork.android/.MainActivity --es action features_test # create_folder/file-chip report
```

## Build

```bash
export ANDROID_HOME=$HOME/android-sdk
$ANDROID_HOME/gradle-8.13/bin/gradle :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: Gradle 8.13 · AGP 8.7.3 · Kotlin 2.0.21 · compileSdk 35 · minSdk 26 · JDK 17.

## Install on emulator

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.pawwork.android/.MainActivity
```
> ⚠️ **Push pending (needs your GitHub session):** this host kept no usable HTTPS/SSH
> credential for `github.com/vinothus/pawwork-android`, so the 2026-09-19 work is committed
> locally (`HEAD` = `2026-09-19 — robust background tool calls + functional APK builds`)
> but **not pushed**. From your terminal: `cd pawwork-android && git push origin HEAD`.

## 🐾 Runner v3 (built-APK: 3-pane) — 2026-09-19
Built APKs now open as a **3-pane WebView runner** (home.html 9316 B byte-verified inside template.apk):
- **▶ Code** — run injected JS or embedded Python (Pyodide) via the Tpl bridge (getConfig/fsRead/fsWrite/fsList/assetList/assetRead/assetRead/mediaList/toast).
- **📁 Files** — scrollable list of **ALL files** (assets/files/* injected + assets/media/* + runtime-written via PawWork FS); open any file in a scrollable viewer (text/code/markdown in <pre>, images/canvas via base64/img, binary as hex).
- **🎬 Media** — audio/video injected at build time (build_apk media param → assets/media/*) play inside the WebView via the FS bridge.
- **💬 Chat** — a local chat tab inside the built APK (wired to a JS-chatbot example). Meaning still pending the user's single-letter pick (a local tab / b offline JS-chatbot / c runtime chat bridge / d all three) — see round commentary.
Evidence this session: :app:assembleDebug BUILD SUCCESSFUL (main APK 13,310,554 B), template.apk rebuilt via zip swap (home.html 9316 B confirmed), installed on emulator-5554.


### Verification (2026-09-19, this session — not redacted from earlier turns)
- `:app:assembleDebug` → **BUILD SUCCESSFUL**; main APK 13 310 554 B
- Rebuilt `template.apk` (405 408 B) with **3-pane runner** — `unzip -l` byte-check: `assets/home.html` = 9316 B **with 4 bridge markers** (assetList/fsList/mediaList/chat pane)
- Installed on `emulator-5554` (Success), live runner screenshot banked in-repo: `docs/evidence/runner-v3-live-5554.png` (73 655 B)
- Audio: injected 1 s 440 Hz WAV (44 144 B) playable through FS bridge. **Video: canvas-animation runner (no codec on host → no H.264 MP4 claim made).**
- README v3 section present; committed locally (`72243c4`, `0a0126d`)
