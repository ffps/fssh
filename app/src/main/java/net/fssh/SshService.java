package net.fssh;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Foreground service that runs the native dropbear SSH server
 * (libdropbear.so from nativeLibraryDir) and keeps its notification.
 */
public class SshService extends Service {
    static final int NOTIF_ID = 1, NOTIF_ERR_ID = 2;
    private static final String CHANNEL = "fssh";

    static volatile boolean running;
    static volatile String error = "";

    private Process proc;
    private volatile boolean stopping;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String shownUri = "";
    private final Runnable refreshNotif = new Runnable() {
        @Override
        public void run() {
            if (running) updateNotif();
            handler.postDelayed(this, 10000);
        }
    };
    private PowerManager.WakeLock wl;
    private WifiManager.WifiLock wfl;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "fSSH",
                    NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        // must be called promptly after startForegroundService()
        startFg(notif(getString(R.string.starting)));
        if (in == null && !Cfg.want(this)) { // sticky restart after the user stopped it
            stopSelf();
            return START_NOT_STICKY;
        }
        int port = Cfg.port(this);
        String pass = Cfg.pass(this);
        if (pass.length() == 0 || port < 1 || port > 65535) {
            fail(getString(R.string.err_config));
            return START_NOT_STICKY;
        }
        synchronized (this) {
            if (proc == null) startServer(port, pass);
        }
        return START_STICKY;
    }

    private void startFg(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, 0x40000000 /* FOREGROUND_SERVICE_TYPE_SPECIAL_USE */);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification notif(String text) {
        Intent open = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_UI, true) // "show the window", never auto-start
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, pf);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setWhen(0);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    private void fail(String msg) {
        error = msg;
        running = false;
        Cfg.setWant(this, false);
        postError(msg);
        stopSelf();
    }

    private void postError(String msg) {
        Intent open = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(msg)
                .setContentIntent(PendingIntent.getActivity(this, 1, open, pf))
                .setAutoCancel(true);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ERR_ID, b.build());
    }

    private void startServer(int port, String pass) {
        stopping = false;
        error = "";
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ERR_ID);

        String lib = getApplicationInfo().nativeLibraryDir;
        File db = new File(lib, "libdropbear.so");
        if (!db.exists()) {
            fail(getString(R.string.err_binary));
            return;
        }
        killOurs(db.getPath()); // leftovers of a previously killed app process

        File files = getFilesDir();
        File bin = new File(files, "bin");
        bin.mkdirs();
        // NetBox/WinSCP-style clients run "scp -t ..." through a shell: scp must be in PATH
        link(bin, "scp", new File(lib, "libscp.so"));
        link(bin, "ls", new File(lib, "libls.so")); // proper "ls -la" with "." and ".."
        File sshDir = new File(files, "ssh");
        sshDir.mkdirs();
        String home = Cfg.home(this);
        try {
            ProcessBuilder pb = new ProcessBuilder(db.getPath(),
                    "-F",              // stay in foreground
                    "-e",              // pass environment (ANDROID_ROOT, ...) to sessions
                    "-R",              // generate host keys on demand
                    "-D", sshDir.getPath(), // authorized_keys lives here, not in the (sdcard) home
                    "-p", String.valueOf(port),
                    "-W", "131072",
                    "-K", "60",
                    "-T", "5");
            Map<String, String> env = pb.environment();
            env.put("HOME", home);
            env.put("FSSH_KEYDIR", files.getPath());
            String path = env.get("PATH");
            env.put("PATH", bin.getPath() + (path == null || path.length() == 0 ? "" : ":" + path));
            env.put("FSSH_SCP", new File(lib, "libscp.so").getPath());
            env.put("FSSH_SFTP", new File(lib, "libsftp.so").getPath());
            env.put("TMPDIR", getCacheDir().getPath());
            pb.redirectErrorStream(true);
            proc = pb.start();

            // password goes through stdin: not visible in argv/environ
            OutputStream os = proc.getOutputStream();
            os.write(pass.getBytes("UTF-8"));
            os.close();
        } catch (Exception e) {
            proc = null;
            fail(getString(R.string.err_start) + ": " + e.getMessage());
            return;
        }

        running = true;
        acquireLocks();
        updateNotif();
        handler.removeCallbacks(refreshNotif);
        handler.postDelayed(refreshNotif, 10000); // the IP may appear later (wifi after boot)

        final Process p = proc;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String last = "";
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.length() > 0) last = line; // keep draining the pipe
                    }
                } catch (Exception ignored) {
                }
                try { p.waitFor(); } catch (InterruptedException ignored) { }
                if (!stopping) {
                    running = false;
                    Cfg.setWant(SshService.this, false);
                    error = last.length() > 0 ? last : getString(R.string.err_exited);
                    postError(error);
                    stopSelf();
                }
            }
        }, "fssh-log").start();
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fSSH");
            wl.setReferenceCounted(false);
            wl.acquire();
        } catch (RuntimeException ignored) { }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wfl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "fSSH");
            wfl.setReferenceCounted(false);
            wfl.acquire();
        } catch (RuntimeException ignored) { }
    }

    private void releaseLocks() {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (RuntimeException ignored) { }
        try { if (wfl != null && wfl.isHeld()) wfl.release(); } catch (RuntimeException ignored) { }
        wl = null;
        wfl = null;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        running = false;
        handler.removeCallbacks(refreshNotif);
        Process p;
        synchronized (this) { p = proc; proc = null; }
        String bin = new File(getApplicationInfo().nativeLibraryDir, "libdropbear.so").getPath();
        killOurs(bin);            // server, open connections and their shells
        if (p != null) p.destroy();
        releaseLocks();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- helpers

    private void updateNotif() {
        String u = uri(this);
        if (u.equals(shownUri)) return;
        shownUri = u;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, notif(u));
    }

    /** "ssh://192.168.1.29:8022" (or a hint when there is no network yet). */
    static String uri(Context c) {
        int port = Cfg.port(c);
        String ip = ip();
        return ip.length() > 0 ? "ssh://" + ip + ":" + port : c.getString(R.string.no_network, port);
    }

    private static void link(File dir, String name, File target) {
        File l = new File(dir, name);
        try {
            l.delete();
            String ln = new File("/system/bin/ln").exists() ? "/system/bin/ln" : "ln";
            new ProcessBuilder(ln, "-s", target.getPath(), l.getPath()).redirectErrorStream(true).start().waitFor();
        } catch (Exception ignored) { }
    }

    /** First IPv4 address of an up, non-loopback interface ("" if none). */
    static String ip() {
        String all = ips();
        int c = all.indexOf(',');
        return c < 0 ? all : all.substring(0, c);
    }

    /** IPv4 addresses of the up, non-loopback interfaces, comma separated. */
    static String ips() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) { }
        return sb.toString();
    }

    private static String readProc(String path) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            byte[] b = new byte[2048];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "ISO-8859-1") : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * Kills every process started from {@code bin} together with all of their
     * descendants (client sessions, shells, scp/sftp). Only our own uid is visible.
     */
    static void killOurs(String bin) {
        File[] list = new File("/proc").listFiles();
        if (list == null) return;
        int me = android.os.Process.myPid();
        Map<Integer, Integer> parent = new HashMap<Integer, Integer>();
        Set<Integer> victims = new LinkedHashSet<Integer>();
        for (File f : list) {
            int pid;
            try { pid = Integer.parseInt(f.getName()); } catch (NumberFormatException e) { continue; }
            if (pid == me) continue;
            String stat = readProc("/proc/" + pid + "/stat");
            if (stat == null) continue;
            int rp = stat.lastIndexOf(')');
            if (rp < 0) continue;
            String[] t = stat.substring(rp + 2).split(" ");
            if (t.length < 2) continue;
            try { parent.put(pid, Integer.parseInt(t[1])); } catch (NumberFormatException e) { continue; }
            String cmd = readProc("/proc/" + pid + "/cmdline");
            if (cmd != null && cmd.startsWith(bin)) victims.add(pid);
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<Integer, Integer> e : parent.entrySet()) {
                if (victims.contains(e.getValue()) && victims.add(e.getKey())) grew = true;
            }
        }
        for (int pid : victims) android.os.Process.killProcess(pid);
    }
}
