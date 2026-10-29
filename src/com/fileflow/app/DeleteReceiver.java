package com.fileflow.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;

import java.io.File;

/** Fires when an auto-delete timer elapses. */
public class DeleteReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        final Context c = ctx.getApplicationContext();
        final String path = intent.getStringExtra("path");
        if (path == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // a timer means the file goes for good — no recycle bin
                    File f = new File(path);
                    boolean ok = false;
                    if (f.exists()) ok = f.delete();
                    FileEngine.removeScheduled(c, path);
                    if (ok) notifyDone(c, f.getName(), true);
                } catch (Throwable ignored) { }
            }
        }).start();
    }

    private void notifyDone(Context c, String name, boolean permanent) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("fileflow_timer", "Auto-delete timers", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, "fileflow_timer") : new Notification.Builder(c);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Auto-deleted")
                    .setContentText(name + " was deleted")
                    .setColor(0xFF1B5FC7)
                    .setAutoCancel(true);
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(20000L);   // clears itself after 20 s
            Intent open = new Intent(c, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            b.setContentIntent(PendingIntent.getActivity(c, 77, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            nm.notify(77, b.build());
        } catch (Throwable ignored) { }
    }
}
