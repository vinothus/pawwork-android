package com.pawwork.template;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.InputStream;

/** Minimal PawWork-generated app: reads assets/config.json and shows it. */
public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String title = "PawCode App", message = "Built by PawWork build_apk", code = "";
        try {
            InputStream in = getAssets().open("config.json");
            byte[] buf = new byte[in.available()]; in.read(buf); in.close();
            JSONObject cfg = new JSONObject(new String(buf, "UTF-8"));
            if (cfg.has("label")) title = cfg.getString("label");
            if (cfg.has("message")) message = cfg.getString("message");
            if (cfg.has("code")) code = cfg.getString("code");
        } catch (Exception ignored) {}

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.rgb(255, 248, 239));

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(26);
        tv.setGravity(Gravity.CENTER);

        TextView mv = new TextView(this);
        mv.setText(message);
        mv.setTextSize(16);
        mv.setPadding(0, 24, 0, 0);

        TextView cv = new TextView(this);
        cv.setText(code.isEmpty() ? "" : "code: " + code);
        cv.setTextSize(12);
        cv.setPadding(0, 24, 0, 0);

        final String toastMsg = message;
        Button btn = new Button(this);
        btn.setText("Made with PawWork 🐾");
        btn.setOnClickListener(v -> Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show());

        root.addView(tv); root.addView(mv); root.addView(cv); root.addView(btn);
        setContentView(root);
    }
}
