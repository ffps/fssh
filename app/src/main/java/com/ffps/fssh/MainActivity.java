package com.ffps.fssh;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
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

/** Settings window. Built in code to keep the APK tiny. */
public class MainActivity extends Activity {
    static final String EXTRA_UI = "ui";
    private static final int GREEN = 0xFF0D9488, RED = 0xFFD32F2F, GRAY = 0xFF666666;

    private EditText etPort, etPass, etHome;
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
        if (getActionBar() != null) getActionBar().setBackgroundDrawable(new ColorDrawable(0xFFE0E0E0));

        // Autostart option: opening the app just starts the service (notification appears) and
        // leaves; the window is shown only from the notification or when the service already runs.
        if (!getIntent().getBooleanExtra(EXTRA_UI, false) && !SshService.running
                && Cfg.autostart(this) && Cfg.pass(this).length() > 0) {
            Cfg.setWant(this, true);
            SshService.error = "";
            Intent s = new Intent(this, SshService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s);
            else startService(s);
            finish();
            return;
        }
        setTitle(R.string.app_name);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(16));

        // status: always exactly one line so nothing below it moves
        tvStatus = new TextView(this);
        tvStatus.setTextSize(16);
        tvStatus.setSingleLine(true);
        tvStatus.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(tvStatus, new LinearLayout.LayoutParams(-1, -2));

        col.addView(label(R.string.port));
        etPort = new EditText(this);
        etPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPort.setSingleLine(true);
        etPort.setText(String.valueOf(Cfg.port(this)));
        col.addView(etPort, new LinearLayout.LayoutParams(-1, -2));

        col.addView(label(R.string.password));
        etPass = new EditText(this);
        etPass.setSingleLine(true); // resets the transformation, so it goes first
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPass.setTransformationMethod(PasswordTransformationMethod.getInstance());
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
                Cfg.setShowPass(MainActivity.this, on);
            }
        });
        cbShow.setChecked(Cfg.showPass(this));
        col.addView(cbShow);

        col.addView(label(R.string.home_dir));
        etHome = new EditText(this);
        etHome.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etHome.setSingleLine(true);
        etHome.setText(Cfg.home(this));
        col.addView(etHome, new LinearLayout.LayoutParams(-1, -2));

        cbAuto = new CheckBox(this);
        cbAuto.setText(R.string.autostart);
        cbAuto.setChecked(Cfg.autostart(this));
        cbAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean on) {
                Cfg.setAutostart(MainActivity.this, on);
            }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.topMargin = dp(8);
        col.addView(cbAuto, cl);

        btn = new Button(this);
        btn.setTextColor(Color.WHITE);
        btn.setMinWidth(dp(220));
        btn.setMinimumHeight(dp(48));
        btn.setPadding(dp(24), 0, dp(24), 0);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggle(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); // not stretched
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(24);
        col.addView(btn, lp);

        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { }
        TextView tvVer = new TextView(this);
        tvVer.setText("fSSH " + ver);
        tvVer.setTextSize(12);
        tvVer.setTextColor(0xFF888888);
        tvVer.setGravity(Gravity.CENTER_HORIZONTAL);
        tvVer.setPadding(0, dp(24), 0, 0);
        col.addView(tvVer, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);

        askPermissions();
    }

    private TextView label(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(13);
        t.setPadding(0, dp(12), 0, 0);
        return t;
    }

    private boolean allFilesGranted() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private void askAllFiles() {
        if (allFilesGranted()) return;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
            }
        }
    }

    /** First launch only: notifications (13+), then storage access. */
    private void askPermissions() {
        if (Cfg.asked(this) || Build.VERSION.SDK_INT < 23) return;
        Cfg.setAsked(this);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else if (Build.VERSION.SDK_INT >= 30) {
            askAllFiles();
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (Build.VERSION.SDK_INT >= 33) askAllFiles(); // after the notification dialog
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
        String home = etHome.getText().toString().trim();
        if (home.length() == 0) {
            home = Cfg.defaultHome();
            etHome.setText(home);
        }
        Cfg.save(this, port, pass, home);
        Cfg.setWant(this, true);
        SshService.error = "";
        if (!allFilesGranted()) Toast.makeText(this, R.string.files_hint, Toast.LENGTH_LONG).show();
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
        etHome.setEnabled(!run);
        btn.setText(run ? R.string.stop : R.string.start);
        btn.setBackgroundDrawable(bg(run ? RED : GREEN));
        String err = SshService.error;
        if (run) {
            tvStatus.setText(getString(R.string.status_running, SshService.uri(this)));
        } else if (err != null && err.length() > 0) {
            tvStatus.setText(getString(R.string.status_failed, err));
        } else {
            tvStatus.setText(R.string.status_stopped);
        }
    }

    private Drawable bg(int color) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pill(darker(color)));
        s.addState(new int[]{-android.R.attr.state_enabled}, pill(0xFF9E9E9E));
        s.addState(new int[]{}, pill(color));
        return s;
    }

    /** Rectangle with fully rounded ends. */
    private Drawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(200)); // clamped to half of the height
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
        if (btn != null) h.post(poll);
    }

    @Override
    protected void onPause() {
        if (btn == null) { super.onPause(); return; } // finished early (autostart path)
        h.removeCallbacks(poll);
        if (!shownRunning) { // keep what the user typed
            try {
                int port = Integer.parseInt(etPort.getText().toString().trim());
                if (port >= 1 && port <= 65535) {
                    Cfg.save(this, port, etPass.getText().toString(), etHome.getText().toString().trim());
                }
            } catch (NumberFormatException ignored) {
            }
        }
        super.onPause();
    }
}
