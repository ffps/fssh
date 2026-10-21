package net.fssh;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Settings window: port, password, autostart, start/stop. Built in code to keep the APK tiny. */
public class MainActivity extends Activity {
    private static final int GREEN = 0xFF0D9488, RED = 0xFFD32F2F;

    private EditText etPort, etPass;
    private CheckBox cbShow, cbAuto;
    private TextView tvStatus;
    private Button btn;
    private final Handler h = new Handler();
    private boolean shownRunning;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            h.postDelayed(this, 1000);
        }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle(R.string.app_name);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(16));

        col.addView(label(R.string.port));
        etPort = new EditText(this);
        etPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPort.setSingleLine(true);
        etPort.setText(String.valueOf(Cfg.port(this)));
        col.addView(etPort, new LinearLayout.LayoutParams(-1, -2));

        col.addView(label(R.string.password));
        etPass = new EditText(this);
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPass.setSingleLine(true);
        etPass.setText(Cfg.pass(this));
        col.addView(etPass, new LinearLayout.LayoutParams(-1, -2));

        cbShow = new CheckBox(this);
        cbShow.setText(R.string.show_password);
        cbShow.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean on) {
                int sel = etPass.getSelectionStart();
                etPass.setTransformationMethod(on ? null : PasswordTransformationMethod.getInstance());
                if (sel >= 0) etPass.setSelection(sel);
            }
        });
        col.addView(cbShow);

        cbAuto = new CheckBox(this);
        cbAuto.setText(R.string.autostart);
        cbAuto.setChecked(Cfg.autostart(this));
        cbAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean on) {
                Cfg.setAutostart(MainActivity.this, on);
            }
        });
        col.addView(cbAuto);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(15);
        tvStatus.setPadding(0, dp(16), 0, dp(16));
        col.addView(tvStatus, new LinearLayout.LayoutParams(-1, -2));

        btn = new Button(this);
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(true);
        btn.setMinWidth(dp(180));
        btn.setMinimumHeight(dp(48));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggle(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); // not stretched
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(btn, lp);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);

        askPermissions();
    }

    private TextView label(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(13);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private void askPermissions() {
        if (Cfg.asked(this) || Build.VERSION.SDK_INT < 23) return;
        Cfg.setAsked(this);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else if (Build.VERSION.SDK_INT <= 29) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    private void toggle() {
        if (SshService.running) {
            Cfg.setWant(this, false);
            stopService(new Intent(this, SshService.class));
            SshService.running = false;
            refresh();
            return;
        }
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            port = 0;
        }
        if (port < 1 || port > 65535) {
            etPort.setError(getString(R.string.err_port));
            return;
        }
        String pass = etPass.getText().toString();
        if (pass.length() == 0) {
            etPass.setError(getString(R.string.err_password));
            Toast.makeText(this, R.string.err_password, Toast.LENGTH_SHORT).show();
            return;
        }
        Cfg.save(this, port, pass);
        Cfg.setWant(this, true);
        SshService.error = "";
        Intent s = new Intent(this, SshService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s);
        else startService(s);
        SshService.running = true; // optimistic; the service corrects it on failure
        refresh();
    }

    private void refresh() {
        boolean run = SshService.running;
        shownRunning = run;
        etPort.setEnabled(!run);
        etPass.setEnabled(!run);
        btn.setText(run ? R.string.stop : R.string.start);
        btn.setBackgroundDrawable(bg(run ? RED : GREEN));
        String err = SshService.error;
        if (run) {
            String ips = SshService.ips();
            tvStatus.setText(getString(R.string.status_running, Cfg.port(this))
                    + (ips.length() > 0 ? "\n" + ips : ""));
            tvStatus.setTextColor(GREEN);
        } else if (err != null && err.length() > 0) {
            tvStatus.setText(getString(R.string.status_error, err));
            tvStatus.setTextColor(RED);
        } else {
            tvStatus.setText(R.string.status_stopped);
            tvStatus.setTextColor(0xFF666666);
        }
    }

    private Drawable bg(int color) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rect(darker(color)));
        s.addState(new int[]{-android.R.attr.state_enabled}, rect(0xFF9E9E9E));
        s.addState(new int[]{}, rect(color));
        return s;
    }

    private Drawable rect(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(4));
        return g;
    }

    private static int darker(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] *= 0.75f;
        return Color.HSVToColor(hsv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // process may have been restarted while the service is alive: nothing to restore, state is polled
        h.post(poll);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(poll);
        if (!shownRunning) {
            // keep what the user typed
            try {
                int port = Integer.parseInt(etPort.getText().toString().trim());
                if (port >= 1 && port <= 65535) Cfg.save(this, port, etPass.getText().toString());
            } catch (NumberFormatException ignored) {
            }
        }
        super.onPause();
    }
}
