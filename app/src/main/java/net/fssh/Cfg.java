package net.fssh;

import android.content.Context;
import android.content.SharedPreferences;

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

    static void save(Context c, int port, String pass) {
        p(c).edit().putInt("port", port).putString("pass", pass).commit();
    }
    static void setAutostart(Context c, boolean v) { p(c).edit().putBoolean("auto", v).commit(); }
    static void setWant(Context c, boolean v) { p(c).edit().putBoolean("want", v).commit(); }
    static boolean asked(Context c) { return p(c).getBoolean("asked", false); }
    static void setAsked(Context c) { p(c).edit().putBoolean("asked", true).commit(); }
}
