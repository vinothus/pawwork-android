package com.pawwork.template;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * PawWork-generated app host (v2, 2026-09-19).
 *
 * Runs assets/home.html in a WebView (served through WebViewAssetLoader's virtual
 * https origin — the same proven pattern PawWork itself uses, so the embedded Pyodide
 * runtime's fetch() calls work on every WebView). home.html reads assets/config.json
 * through the {@code Tpl} bridge and EXECUTES the functionality embedded by PawWork's
 * build_apk tool:
 *   lang=js     → "code" runs as JavaScript with a Paw API (log/text/read/write/list) plus
 *                 canvas for offline image math;
 *   lang=python → "code" runs as real CPython via the Pyodide runtime that build_apk embeds
 *                 into the APK (self-contained, works fully offline, ~15 MB bigger).
 * The {@code Tpl} bridge also exposes the app's private file storage to the embedded code.
 */
public class MainActivity extends Activity {

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        wv.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return loader.shouldInterceptRequest(Uri.parse(url));
            }
        });
        wv.addJavascriptInterface(new Tpl(this), "Tpl");
        setContentView(wv);
        wv.loadUrl("https://appassets.androidplatform.net/assets/home.html");
    }

    /** Bridge that the embedded functionality (JS and Python) can call into. */
    static class Tpl {
        private final Context ctx;
        Tpl(Context c) { ctx = c; }

        private static String err(String m) {
            try { return new JSONObject().put("ok", false).put("error", m).toString(); }
            catch (Exception e) { return "{\"ok\":false,\"error\":\"" + m + "\"}"; }
        }

        @JavascriptInterface
        public String getConfig() {
            try {
                InputStream in = ctx.getAssets().open("config.json");
                byte[] buf = new byte[4096];
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                return out.toString("UTF-8");
            } catch (Exception e) {
                return "{\"label\":\"PawCode App\",\"message\":\"Built by PawWork\",\"lang\":\"js\",\"code\":\"\"}";
            }
        }

        @JavascriptInterface public void toast(String msg) { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show(); }
        @JavascriptInterface public void log(String msg) { /* rendered by home.html */ }

        @JavascriptInterface
        public String fsRead(String path) {
            try {
                File base = ctx.getFilesDir();
                File f = new File(base, path.startsWith("/") ? path.substring(1) : path);
                if (!f.getCanonicalPath().startsWith(base.getCanonicalPath()))
                    return err("outside app storage: " + path);
                if (!f.exists()) return err("not found: " + path);
                byte[] bytes = readAll(f);
                boolean text = isText(bytes);
                return new JSONObject()
                    .put("ok", true).put("path", f.getAbsolutePath()).put("bytes", bytes.length)
                    .put("encoding", text ? "utf8" : "base64")
                    .put("data", text ? new String(bytes, "UTF-8")
                        : Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .toString();
            } catch (Exception e) { return err(e.getMessage() == null ? "read failed" : e.getMessage()); }
        }

        @JavascriptInterface
        public String fsWrite(String path, String data) {
            try {
                File base = ctx.getFilesDir();
                File f = new File(base, path.startsWith("/") ? path.substring(1) : path);
                if (!f.getCanonicalPath().startsWith(base.getCanonicalPath()))
                    return err("outside app storage: " + path);
                byte[] bytes = (data != null && data.startsWith("b64:"))
                    ? Base64.decode(data.substring(4), Base64.NO_WRAP)
                    : String.valueOf(data).getBytes("UTF-8");
                File p = f.getParentFile();
                if (p != null) p.mkdirs();
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(bytes); fo.close();
                return new JSONObject().put("ok", true).put("path", f.getAbsolutePath())
                    .put("bytes", bytes.length).toString();
            } catch (Exception e) { return err(e.getMessage() == null ? "write failed" : e.getMessage()); }
        }

        @JavascriptInterface
        public String fsList(String path) {
            try {
                File base = ctx.getFilesDir();
                File start = (path == null || path.isEmpty()) ? base
                    : new File(base, path.startsWith("/") ? path.substring(1) : path);
                if (!start.getCanonicalPath().startsWith(base.getCanonicalPath()))
                    return err("outside app storage: " + path);
                JSONArray arr = new JSONArray();
                if (start.exists()) listInto(arr, base, start);
                return new JSONObject().put("ok", true).put("files", arr).toString();
            } catch (Exception e) { return err(e.getMessage() == null ? "list failed" : e.getMessage()); }
        }

        /** List all injected media (audio/video) assets the runner can play. */
        @JavascriptInterface
        public String mediaList() {
            try {
                String[] kids = ctx.getAssets().list("media");
                JSONArray arr = new JSONArray();
                if (kids != null) for (String k : kids) {
                    JSONObject o = new JSONObject().put("name", k);
                    String lk = k.toLowerCase();
                    String type = (lk.endsWith(".mp4") || lk.endsWith(".webm") || lk.endsWith(".mkv")) ? "video"
                        : (lk.endsWith(".mp3") || lk.endsWith(".ogg") || lk.endsWith(".wav") || lk.endsWith(".m4a")) ? "audio"
                        : "asset";
                    o.put("type", type);
                    try { o.put("bytes", ctx.getAssets().openFd("media/" + k).getLength()); } catch (Exception ignored) {}
                    arr.put(o);
                }
                return new JSONObject().put("ok", true).put("media", arr).toString();
            } catch (Exception e) { return err(e.getMessage() == null ? "media list failed" : e.getMessage()); }
        }

        /** Read an injected asset (text or base64) — the file pane opens these too. */
        @JavascriptInterface
        public String assetRead(String path, boolean asB64) {
            try {
                String p = (path == null || path.isEmpty()) ? "" : (path.startsWith("/") ? path.substring(1) : path);
                InputStream in = ctx.getAssets().open(p);  // throws → caught below
                byte[] bytes = readAll(in); in.close();
                boolean text = isText(bytes);
                if (asB64 || !text)
                    return new JSONObject().put("ok", true).put("path", p).put("bytes", bytes.length)
                        .put("encoding", "base64").put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).toString();
                return new JSONObject().put("ok", true).put("path", p).put("bytes", bytes.length)
                    .put("encoding", "utf8").put("data", new String(bytes, "UTF-8")).toString();
            } catch (Exception e) { return err("asset not found: " + path); }
        }

        /** Recursive list of ALL injected assets (home.html, pyodide, media, files…). */
        @JavascriptInterface
        public String assetList() {
            try {
                JSONArray arr = new JSONArray();
                listAssetsInto(arr, "");
                return new JSONObject().put("ok", true).put("assets", arr).toString();
            } catch (Exception e) { return err(e.getMessage() == null ? "asset list failed" : e.getMessage()); }
        }

        private void listAssetsInto(JSONArray arr, String prefix) throws Exception {
            String dir = prefix.isEmpty() ? "" : prefix;
            String[] kids = ctx.getAssets().list(dir);
            if (kids == null) return;
            for (String k : kids) {
                String p = dir.isEmpty() ? k : dir + "/" + k;
                JSONObject o = new JSONObject().put("path", p);
                boolean isDir = ctx.getAssets().list(p).length > 0;
                if (isDir) { listAssetsInto(arr, p); continue; }
                try { o.put("bytes", ctx.getAssets().openFd(p).getLength()); } catch (Exception ignored) {}
                arr.put(o);
            }
        }

        private void listInto(JSONArray arr, File base, File f) throws Exception {
            if (f.isFile()) {
                arr.put(new JSONObject().put("path", f.getAbsolutePath().substring(base.getAbsolutePath().length()))
                    .put("bytes", f.length()));
            } else if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) listInto(arr, base, k);
            }
        }

        private static byte[] readAll(File f) throws Exception {
            FileInputStream in = new FileInputStream(f);
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            } finally { in.close(); }
        }

        private static boolean isText(byte[] bytes) {
            int n = Math.min(bytes.length, 4096);
            for (int i = 0; i < n; i++) if (bytes[i] == 0) return false;
            return true;
        }
    }
}