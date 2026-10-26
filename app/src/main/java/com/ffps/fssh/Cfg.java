package com.ffps.fssh;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

/** Settings storage (app-private SharedPreferences). */
final class Cfg {
    static final int DEFAULT_PORT = 8022;

    private Cfg() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("fssh", Context.MODE_PRIVATE);
    }

    static int port(Context c) { return p(c).getInt("port", DEFAULT_PORT); }
    static String pass(Context c) { return p(c).getString("pass", ""); }
    static boolean autostart(Context c) { return p(c).getBoolean("auto", false); }
    /** true = the user wants the server running (restored after a process kill) */
    static boolean want(Context c) { return p(c).getBoolean("want", false); }

    /** Directory where sessions start (and scp/sftp relative paths resolve). */
    static String home(Context c) {
        String h = p(c).getString("home", null);
        return (h == null || h.length() == 0) ? defaultHome() : h;
    }

    static String defaultHome() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
    }

    static void save(Context c, int port, String pass, String home) {
        p(c).edit().putInt("port", port).putString("pass", pass).putString("home", home).commit();
    }
    static void setAutostart(Context c, boolean v) { p(c).edit().putBoolean("auto", v).commit(); }
    static void setWant(Context c, boolean v) { p(c).edit().putBoolean("want", v).commit(); }
    static boolean showPass(Context c) { return p(c).getBoolean("show", true); }
    static void setShowPass(Context c, boolean v) { p(c).edit().putBoolean("show", v).commit(); }
    static boolean asked(Context c) { return p(c).getBoolean("asked", false); }
    static void setAsked(Context c) { p(c).edit().putBoolean("asked", true).commit(); }
}
