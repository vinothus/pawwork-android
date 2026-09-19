package com.pawwork.template;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
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
        Bridge bridge = new Bridge();
        web.addJavascriptInterface(bridge, "Tpl");
        String st = getIntent() == null ? null : getIntent().getStringExtra("selfTest");
        try {
            java.io.File bm = new java.io.File(ctx.getFilesDir(), "selftest-boot.txt");
            java.io.FileOutputStream bf = new java.io.FileOutputStream(bm);
            bf.write(("boot " + System.currentTimeMillis() + " selfTest=" + (st != null)).getBytes("UTF-8"));
            bf.close();
        } catch (Exception ign) {}
        if (st != null) {
            final android.os.Handler h = new android.os.Handler(getMainLooper());
            final Runnable[] kick = new Runnable[1];
            kick[0] = new Runnable() {
                int n = 0;
                @Override public void run() {
                    try {
                        web.evaluateJavascript(
                                "(function(){try{if(typeof bootChat==='function')bootChat();return window.__chatBooted?('booted:'+document.title):('nodoc:'+document.readyState);}catch(e){return 'err:'+e;}})()",
                                new ValueCallback<String>() {
                                    @Override public void onReceiveValue(String v) {
                                        try {
                                            java.io.File jm = new java.io.File(ctx.getFilesDir(), "selftest-js.txt");
                                            java.io.FileOutputStream jf = new java.io.FileOutputStream(jm);
                                            jf.write(("[" + n + "] js=" + v + " at " + System.currentTimeMillis()).getBytes("UTF-8"));
                                            jf.close();
                                        } catch (Exception ign) {}
                                    }
                                });
                    } catch (Exception ign) {}
                    if (n++ < 14) h.postDelayed(kick[0], 3000);
                }
            };
            h.postDelayed(kick[0], 3000);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    try {
                        java.io.File pf = new java.io.File(ctx.getFilesDir(), "selftest-pagefin.txt");
                        java.io.FileOutputStream ff = new java.io.FileOutputStream(pf);
                        ff.write(("pagefin " + url + " " + System.currentTimeMillis()).getBytes("UTF-8"));
                        ff.close();
                    } catch (Exception ign) {}
                    view.evaluateJavascript("try{if(typeof bootChat==='function')bootChat();}catch(e){}", null);
                }
            });
        } else {
            web.setWebViewClient(new WebViewClient());
        }
        if ("hello".equals(st)) {
            web.loadUrl("file:///android_asset/hello.html");
        } else {
            web.loadUrl("file:///android_asset/home.html");
        }
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
        public String assetList(String prefix) {
            try {
                String p = (prefix == null) ? "" : prefix;
                JSONArray arr = new JSONArray();
                String[] names = ctx.getAssets().list(p);
                if (names != null) {
                    for (String n : names) {
                        String full = p.isEmpty() ? n : p + "/" + n;
                        boolean dir = false;
                        try {
                            ctx.getAssets().open(full);
                        } catch (Exception e) {
                            try {
                                String[] kids = ctx.getAssets().list(full);
                                dir = kids != null && kids.length > 0;
                            } catch (Exception e2) { dir = false; }
                        }
                        arr.put(new JSONObject().put("type", dir ? "dir" : "asset").put("path", full));
                    }
                }
                return new JSONObject().put("ok", true).put("assets", arr).toString();
            } catch (Exception e) {
                return err(e.getMessage() == null ? "asset list failed" : e.getMessage());
            }
        }

        @JavascriptInterface
        public String assetRead(String path, boolean asB64) {
            return readAsset(path, asB64);
        }

        @JavascriptInterface
        public String mediaList() {
            try {
                JSONArray audio = new JSONArray();
                JSONArray video = new JSONArray();
                walkMedia("", audio, video);
                return new JSONObject().put("ok", true).put("audio", audio).put("video", video).toString();
            } catch (Exception e) {
                return err(e.getMessage() == null ? "media list failed" : e.getMessage());
            }
        }

        private void walkMedia(String dir, JSONArray audio, JSONArray video) throws Exception {
            String d = (dir == null) ? "" : dir;
            String[] names = ctx.getAssets().list(d);
            if (names == null) return;
            for (String n : names) {
                String full = d.isEmpty() ? n : d + "/" + n;
                boolean isFile = true;
                try { ctx.getAssets().open(full); } catch (Exception e) { isFile = false; }
                if (!isFile) { walkMedia(full, audio, video); continue; }
                String l = n.toLowerCase();
                String type = null;
                if (l.endsWith(".mp3") || l.endsWith(".wav") || l.endsWith(".m4a") || l.endsWith(".ogg") || l.endsWith(".oga"))
                    type = "audio";
                else if (l.endsWith(".mp4") || l.endsWith(".webm") || l.endsWith(".m4v") || l.endsWith(".ogv"))
                    type = "video";
                if (type != null)
                    (type.equals("audio") ? audio : video).put(
                            new JSONObject().put("name", n).put("path", full).put("bytes", assetLen(full)));
            }
        }

        private long assetLen(String path) {
            try { return readAll(ctx.getAssets().open(path)).length; } catch (Exception e) { return 0; }
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

        @JavascriptInterface
        public String fsRead(String path) {
            try {
                File base = ctx.getFilesDir();
                String rel = (path == null || path.isEmpty()) ? "" : (path.startsWith("/") ? path.substring(1) : path);
                File f = new File(base, rel);
                if (!f.getCanonicalPath().startsWith(base.getCanonicalPath()))
                    return err("outside app storage: " + path);
                if (!f.exists() || !f.isFile())
                    return err("not a file: " + path);
                byte[] d = readAll(new FileInputStream(f));
                boolean text = isText(d);
                if (!text)
                    return new JSONObject().put("ok", true).put("path", rel).put("bytes", d.length)
                            .put("encoding", "base64").put("data", Base64.encodeToString(d, Base64.NO_WRAP)).toString();
                return new JSONObject().put("ok", true).put("path", rel).put("bytes", d.length)
                        .put("encoding", "utf8").put("data", new String(d, "UTF-8")).toString();
            } catch (Exception e) {
                return err(e.getMessage() == null ? "fs read failed" : e.getMessage());
            }
        }

        @JavascriptInterface
        public String fsWrite(String path, boolean isB64, String data) {
            try {
                File base = ctx.getFilesDir();
                String rel = (path == null || path.isEmpty()) ? "out.txt" : (path.startsWith("/") ? path.substring(1) : path);
                File f = new File(base, rel);
                if (!f.getCanonicalPath().startsWith(base.getCanonicalPath()))
                    return err("outside app storage: " + path);
                File parent = f.getParentFile();
                if (parent != null) parent.mkdirs();
                byte[] d = isB64 ? Base64.decode(data, Base64.DEFAULT) : data.getBytes("UTF-8");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                fos.write(d);
                fos.close();
                return new JSONObject().put("ok", true).put("path", rel).put("bytes", d.length).toString();
            } catch (Exception e) {
                return err(e.getMessage() == null ? "fs write failed" : e.getMessage());
            }
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
