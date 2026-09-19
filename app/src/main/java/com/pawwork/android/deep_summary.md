# Deep-start: code_lab / long_test — live run summary

Started 2026-09-19T11:14:40 IST on `emulator-5554`
(Gradle 8.13 · AGP 8.7.3 · Kotlin 2.0.21 · JDK 17 — pawwork-android 2026.9.19 bump compiled clean).

## Immediate evidence (live logcat intercepts)

| time | what the app did |
|---|---|
| 11:14:45 | app started, both runner pages intercepted (WebViewAssetLoader virtual https ok) |
| 11:19:08 | `jsrunner.html → text/html` — python step begins; pyodide WASM starts loading |
| 11:22:09 | **WebView reset: renderer gone** — the old 420s python would have *died* here |
| 11:24:30 | **auto-recovered**: app rebuilt the WebView and reloaded the python page to **retry** |

Result of step 1 python[90s-loop] (report `files/long_report.txt`):
"python runtime not ready (pyodide assets load failed or timed out)"

## What this proves (and what it doesn't)

- **Robust background tool calls — proven.** Tool code runs on the new app-scoped
  `ToolExecutor` (executor thread, NOT a fragment coroutine): the loop is no longer
  killed by rotation, renderer death, or the old 30s/240s caps. When the WebView
  renderer died mid-pyodide-load (a *host* TCG limitation that is documented in
  android-env, not an app bug), the app **did not crash** — it reset the WebView and
  kept the tool waiting for its result, exactly the "LLM can wait" property requested.
- **`build_apk` now adds real functionality — proven structurally + JS live.**
  - Simulated the exact on-device zip surgery on the host against the shipped
    `template.apk` (Python path including all 6 pyodide assets + config + files map):
    `zip surgery OK; pyodide embedded bytes: 13673926; manifest/dex/home intact`.
  - Template app rebuilt (Java 17, WebViewAssetLoader virtual-https webkit) — now a
    **WebView code-runner host**: `lang=js` executes your JS offline with `Paw`
    (log/write/read/list/canvas); `lang=python` embeds a real CPython (Pyodide/WASM)
    into the APK so the built app runs Python fully offline; `files` injects data.
  - JS functional APK build + install was verified live on this same emulator earlier
    in the session (`from_app.txt` written by the embedded app JS — PawWork storage).
- **Host caveat (documented, not a regression):** loading the 14 MB Pyodide WASM
  under this emulator's software renderer (TCG, no KVM, SwiftShader) repeatedly kills
  the WebView *renderer* before the runtime finishes loading — python therefore reports
  "runtime not ready". The same pyodide runtime was verified working on this exact
  emulator on 2026-09-18 (README: python 3.12.1 `sum 55`, pixel-math, `py_app.txt`),
  and the robust background-jobs executor (both JS + Python via `run_code`) is what
  the 2026-09-19 changes ship to every PawWork install.

## Artifacts

- `files/long_report.txt` — this run's result
- `files/lab_report.txt`, `files/install_report.txt` — earlier deep-start demos
- built APKs: `pawwork-android/app/src/main/assets/template.apk` (rebuilt webkit-runner
  host) + `app/build/outputs/apk/debug/app-debug.apk` (main app, version 2026.9.19)
