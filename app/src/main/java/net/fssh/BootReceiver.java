package net.fssh;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Starts the server after boot (no window is shown). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent in) {
        if (!Cfg.autostart(c) || Cfg.pass(c).length() == 0) return;
        Cfg.setWant(c, true);
        try {
            Intent s = new Intent(c, SshService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        } catch (RuntimeException ignored) {
            // background start refused by the system; user can start manually
        }
    }
}
