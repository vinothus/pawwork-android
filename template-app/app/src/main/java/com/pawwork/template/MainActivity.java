package com.pawwork.template;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/** PawWork template runner: asset + media bridge + 3-pane file/media/chat runner. */
public class MainActivity extends Activity {
    private WebView web;
    private Context ctx;
    private static final String TAG = "PawWorkTemplate";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ctx = this;
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/home.html");
        setContentView(web);
    }

    private class Bridge {
        private String ok(boolean value) {
            try {
                return new JSONObject().put("ok", value).toString();
            } catch (Exception e) {
                return "{\"ok\":" + value + "}";
            }
        }

        private String err(String message) {
            try {
                return new JSONObject().put("ok", false).put("error", message == null ? "bridge error" : message).toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"" + (message == null ? "bridge error" : message) + "\"}";
            }
        }

        private boolean isText(byte[] d) {
            if (d.length == 0) return true;
            int probes = Math.min(d.length, 512);
            int bad = 0;
            for (int i = 0; i < probes; i++) {
                int v = d[i] & 0xff;
                if (v == 0) return false;
                if (v < 9 || (v > 13 && v < 32)) bad++;
            }
            return bad < probes / 8;
        }

        private String readAsset(String path, boolean asB64) {
            try {
                String p = (path == null || path.isEmpty()) ? "" : (path.startsWith("/") ? path.substring(1) : path);
                InputStream in = ctx.getAssets().open(p);
                byte[] d = readAll(in);
                boolean text = isText(d);
                if (asB64 || !text)
                    return new JSONObject().put("ok", true).put("path", p).put("bytes", d.length)
                            .put("encoding", "base64").put("data", Base64.encodeToString(d, Base64.NO_WRAP)).toString();
                return new JSONObject().put("ok", true).put("path", p).put("bytes", d.length)
                        .put("encoding", "utf8").put("data", new String(d, "UTF-8")).toString();
            } catch (Exception e) {
                return err(e.getMessage() == null ? "asset not found" : e.getMessage());
            }
        }

        private JSONArray listPath(String dir) throws Exception {
            JSONArray arr = new JSONArray();
            String[] names = ctx.getAssets().list(dir == null ? "" : dir);
            if (names != null) {
                for (String n : names) {
                    arr.put(new JSONObject().put("name", n).put("path", (dir == null || dir.isEmpty()) ? n : dir + "/" + n));
                }
            }
            return arr;
        }

        @JavascriptInterface
        public String assetList() {
            try { return new JSONObject().put("ok", true).put("items", listPath(null)).toString(); }
            catch (Exception e) { return err(e.getMessage() == null ? "asset list failed" : e.getMessage()); }
        }

        @JavascriptInterface
        public String assetRead(String path, boolean asB64) {
            return readAsset(path, asB64);
        }

        @JavascriptInterface
        public String mediaList() {
            try {
                JSONObject o = new JSONObject()
                        .put("ok", true)
                        .put("audio", listPath("media"))
                        .put("video", listPath("video"));
                return o.toString();
            } catch (Exception e) {
                return err(e.getMessage() == null ? "media list failed" : e.getMessage());
            }
        }

        @JavascriptInterface
        public String mediaRead(String path, boolean asB64) {
            try {
                String p = (path == null || path.isEmpty()) ? "" : (path.startsWith("/") ? path.substring(1) : path);
                InputStream in = ctx.getAssets().open(p);
                byte[] d = readAll(in);
                boolean text = isText(d);
                if (asB64 || !text)
                    return new JSONObject().put("ok", true).put("path", p).put("bytes", d.length)
                            .put("encoding", "base64").put("data", Base64.encodeToString(d, Base64.NO_WRAP)).toString();
                return new JSONObject().put("ok", true).put("path", p).put("bytes", d.length)
                        .put("encoding", "utf8").put("data", new String(d, "UTF-8")).toString();
            } catch (Exception e) {
                return err(e.getMessage() == null ? "media not found" : e.getMessage());
            }
        }

        @JavascriptInterface
        public String fsList() {
            try {
                JSONArray arr = new JSONArray();
                File base = ctx.getFilesDir();
                File[] kids = base.listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        arr.put(new JSONObject().put("name", k.getName()).put("path", k.getName()).put("bytes", k.length()));
                    }
                }
                return new JSONObject().put("ok", true).put("fs", arr).toString();
            } catch (Exception e) { return err(e.getMessage() == null ? "fs list failed" : e.getMessage()); }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }
}
