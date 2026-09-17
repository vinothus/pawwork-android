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
| `build_apk` | (Lab) | Builds a signed APK on-device from `template.apk`, config JSON + pure-Java V1 signer |
| `run_code` | code-runtime | Runs Python (Pyodide/CPython 3.12 in WebView) and JavaScript inline |
| `call_library` | code-runtime | Calls host libraries: crypto (SHA-256/AES), sqlite, zlib, math, JSON |
| `invoke_app` | (Lab) | Launches any installed app by package id |
| `list_apps` | (Lab) | Lists installed apps (query_all_packages) |

## 📦 Code & APK Lab

Everything runs **on-device** (no PC needed):

- `download_apk` — fetch an APK from F-Droid or any HTTPS URL (SHA-256 verified)
- `build_apk` — patch `app/src/main/assets/template.apk`'s `assets/config.json` (label/message/code → a tiny Java Android app that toasts it), then sign with the app's own V1 signer (`lab/V1Signer.java`, pure Java DER + PKCS#7) — no Android SDK required on the phone
- `install_apk` — `PackageInstaller` session with a broadcast result receiver + poll for the package; states shown in `files/install_report.txt`
- `run_code` — **Python**: real CPython 3.12 via bundled Pyodide (`assets/jsrunner.html`, `WebViewAssetLoader`); **JavaScript**: evaluated in the WebView; `runPython('print(6*7)')` → `42`
- `call_library` — crypto (SHA-256, AES-GCM), sqlite3, zlib, math, JSON stringify/parse
- Self-install flow is exercised by the built-in `install_test` deep-start action; the on-device-built APK verifies with `apksigner` and installs via `pm install`. **Verified end-to-end on emulator 2026-09-17:** `build_apk` → on-device V1-sign (6113 B) → in-app `PackageInstaller` commit → poll → `installed:true`; the installed `com.pawwork.template` launches and shows the patched config ("Built and installed by PawWork on-device!"). See `screenshots/09-ondevice-installed-app.png`.
- **Note:** on TCG (no-KVM) emulators the system's `persistent_data_block` service may never publish, which stalls the in-app `PackageInstaller` finalize (`ServiceNotFoundException` in `markAsSealed`). The same session flow completes on a settled emulator boot or a real device; the produced APK itself is valid and installable. See `lab/AndroidLab.kt`.
- **2026-09-17 fixes (version 2026.9.9)** — mirrored from pawwork-linux: `ses_`+sha256 session id, 401-only credential hint, 403 passes through provider words. Plus **Zen free-tier streaming**: since 2026-09-17 the gateway answers non-stream requests with `403 FreeTierError`, so OpenCode requests now use SSE (`stream:true`) and the app re-assembles `content` + `reasoning_content` + `tool_calls` deltas. **Android `JSONObject.optString` returns the literal `"null"` for explicit JSON `null` values** (libcore behavior — verified in a JVM replay of the live stream) — the parser now uses `isNull` guards on every delta field so tool ids/names are never clobbered and messages don't fill with `nullnullnull…`. Verified live on emulator: clean reply `PICKLE-WORKS-FROM-PAWWORK`, tool loop chains real tool names (`read_document` → `generate_document` → `run_code`) behind the bouncing-dots animation, no loop-stop false positives. See `screenshots/12-zen-clean-reply.png`, `screenshots/13-tool-animation-real-name.png`.
- **Chat history persists across restarts**: messages go to `files/chat_history.json` and the full OpenAI-wire context (incl. tool roles) to `files/wire_history.json`, saved on every message/tool turn (best-effort, capped at last 400) and restored on launch — verified by force-stop + relaunch on emulator (`screenshots/14-chat-history-restored.png`). **Long-press ⚙ clears the saved chat.** Network hiccups are transparent: transient DNS/stream failures retry up to 3× with backoff, and unrecoverable ones get friendly messages ("Can't reach the AI server — DNS lookup failed…").

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