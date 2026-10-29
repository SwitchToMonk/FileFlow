package com.fileflow.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The automatic half of FileFlow.
 *
 * The manual buttons always work. These tasks only run on their own when the
 * matching switch is on in Settings — auto organise, auto group (both run the
 * organiser) and auto delete (fuzzy-matches the saved query and moves hits to
 * the recycle bin). The bin means an automatic delete is never final.
 */
final class AutoTasks {

    private static final long INTERVAL = 30 * 60 * 1000L;
    private static final String ACTION = "com.fileflow.app.AUTO";

    private AutoTasks() { }

    static boolean anyOn(Context c) {
        try {
            if (FileEngine.prefs(c).getBoolean("autoEmptyFolders", false)) return true;
        } catch (Throwable ignored) { }
        SharedPreferences p = FileEngine.prefs(c);
        return p.getBoolean("autoOrganise", false)
                || p.getBoolean("autoGroup", false)
                || p.getBoolean("autoDelete", false);
    }

    static boolean hasAccess(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
            return c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    /** run whatever is switched on; returns a summary the UI can show */
    static JSONObject run(Context c, boolean background) {
        JSONObject out = new JSONObject();
        try {
            if (!hasAccess(c)) {
                out.put("skipped", "no access");
                return out;
            }
            SharedPreferences p = FileEngine.prefs(c);
            int organised = 0, deleted = 0;
            String organisedText = "", deletedText = "";

            if (p.getBoolean("autoOrganise", false) || p.getBoolean("autoGroup", false)) {
                JSONObject r = FileEngine.organise(c, false, 4000);
                organised = r.optInt("count", 0);
                organisedText = r.optString("bytesText", "");
            }
            if (p.getBoolean("autoDelete", false)) {
                String q = p.getString("autoQuery", "");
                int th = p.getInt("autoThreshold", 75);
                if (q != null && !q.trim().isEmpty()) {
                    JSONArray hits = FileEngine.fuzzy(c, q, th / 100.0, 200);
                    JSONArray paths = new JSONArray();
                    for (int i = 0; i < hits.length(); i++) paths.put(hits.optJSONObject(i).optString("path"));
                    if (paths.length() > 0) {
                        JSONObject r = FileEngine.toBin(c, paths);
                        deleted = r.optInt("ok", 0);
                        deletedText = r.optString("bytesText", "");
                    }
                }
            }

            out.put("organised", organised);
            out.put("organisedText", organisedText);
            out.put("deleted", deleted);
            out.put("deletedText", deletedText);

            if (background && (organised > 0 || deleted > 0) && p.getBoolean("notify", true)) {
                StringBuilder msg = new StringBuilder();
                if (organised > 0) msg.append("Organised ").append(organised).append(" files");
                if (deleted > 0) {
                    if (msg.length() > 0) msg.append(" · ");
                    msg.append("Moved ").append(deleted).append(" to the bin");
                }
                notify(c, "FileFlow tidied up", msg.toString());
            }
        } catch (Throwable t) {
            try { out.put("error", String.valueOf(t.getMessage())); } catch (Throwable ignored) { }
        }
        return out;
    }

    private static void notify(Context c, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("fileflow_auto", "Automatic tidy-up",
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, "fileflow_auto")
                    : new Notification.Builder(c);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setColor(0xFF1B5FC7)
                    .setAutoCancel(true);
            Intent open = new Intent(c, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            b.setContentIntent(PendingIntent.getActivity(c, 61, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            nm.notify(7002, b.build());
        } catch (Throwable ignored) { }
    }

    /* ---------- scheduling ---------- */

    static void sync(Context ctx) {
        if (anyOn(ctx)) schedule(ctx); else cancel(ctx);
        syncWatcher(ctx);
    }

    /* ---------- per-file auto-delete timers ---------- */

    static void scheduleDelete(Context ctx, String path, long at) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = deletePending(ctx, path);
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Throwable t) {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable ignored) { }
    }

    static void cancelDelete(Context ctx, String path) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(deletePending(ctx, path));
        } catch (Throwable ignored) { }
    }

    private static PendingIntent deletePending(Context ctx, String path) {
        Intent i = new Intent(ctx, DeleteReceiver.class);
        i.putExtra("path", path);
        return PendingIntent.getBroadcast(ctx, path.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /* ---------- new-file watcher ---------- */

    private static final long WATCH_INTERVAL = 5 * 60 * 1000L;

    static void syncWatcher(Context ctx) {
        try {
            if (FileEngine.prefs(ctx).getBoolean("watchNew", true)) FileWatchService.start(ctx);
            else FileWatchService.stop(ctx);
        } catch (Throwable ignored) { }
        try {
            boolean on = FileEngine.prefs(ctx).getBoolean("watchNew", true);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(ctx, WatchReceiver.class);
            i.setAction("com.fileflow.app.WATCH");
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 81, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (on) {
                long at = SystemClock.elapsedRealtime() + WATCH_INTERVAL;
                try {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
                } catch (Throwable t) {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
                }
            } else {
                am.cancel(pi);
            }
        } catch (Throwable ignored) { }
    }

    static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = pending(ctx);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + INTERVAL, INTERVAL, pi);
        } catch (Throwable ignored) { }
    }

    static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            am.cancel(pending(ctx));
        } catch (Throwable ignored) { }
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, AutoReceiver.class);
        i.setAction(ACTION);
        return PendingIntent.getBroadcast(ctx, 71, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
