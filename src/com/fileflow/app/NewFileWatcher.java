package com.fileflow.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Watches shared storage for files that appeared since the last look and posts
 * a notification for each one. The notification carries an "Auto-delete"
 * action that opens FileFlow's timer picker.
 */
final class NewFileWatcher {

    private static final String KNOWN = "knownFiles";
    private static final String CHANNEL = "fileflow_new";
    private static final int MAX_NOTIFY = 4;

    private NewFileWatcher() { }

    /** returns how many new files were found */
    static int run(Context c) {
        if (!AutoTasks.hasAccess(c)) return 0;
        SharedPreferences p = FileEngine.prefs(c);
        if (!p.getBoolean("watchNew", true)) return 0;

        List<File> files = FileEngine.scanFiles(c, 6000);
        Set<String> known = loadKnown(c);
        boolean firstRun = known.isEmpty();

        JSONArray fresh = new JSONArray();
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (known.contains(f.getAbsolutePath())) continue;
            // ignore anything that is not genuinely recent (older than 48h)
            if (now - f.lastModified() > 48L * 3600 * 1000) { known.add(f.getAbsolutePath()); continue; }
            fresh.put(f.getAbsolutePath());
        }

        // save the snapshot (capped so prefs stay small)
        Set<String> keep = new HashSet<>();
        for (File f : files) keep.add(f.getAbsolutePath());
        saveKnown(c, keep);

        if (firstRun) {
            // the very first look learns the baseline, but a file downloaded in
            // the last few minutes is still worth telling the user about
            JSONArray justNow = new JSONArray();
            for (int i = 0; i < fresh.length(); i++) {
                File f = new File(fresh.optString(i));
                if (now - f.lastModified() < 10L * 60 * 1000) justNow.put(f.getAbsolutePath());
            }
            fresh = justNow;
            if (fresh.length() == 0) return 0;
        }

        int n = fresh.length();
        if (n == 0) return 0;
        if (n > MAX_NOTIFY) notifySummary(c, n);
        else for (int i = 0; i < n; i++) notifyOne(c, fresh.optString(i));
        return n;
    }

    private static Set<String> loadKnown(Context c) {
        Set<String> out = new HashSet<>();
        try {
            String s = FileEngine.prefs(c).getString(KNOWN, "");
            if (s == null || s.isEmpty()) return out;
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Throwable ignored) { }
        return out;
    }

    private static void saveKnown(Context c, Set<String> set) {
        JSONArray a = new JSONArray();
        int i = 0;
        for (String s : set) { if (i++ >= 8000) break; a.put(s); }
        FileEngine.prefs(c).edit().putString(KNOWN, a.toString()).apply();
    }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL, "New files", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Tells you when a new file lands, with an auto-delete timer");
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) { }
    }

    private static void notifyOne(Context c, String path) {
        try {
            ensureChannel(c);
            File f = new File(path);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(f.getName())
                    .setContentText(FileEngine.human(f.length()) + " · " + (f.getParentFile() == null ? "" : f.getParentFile().getName()) + " · tap to set a delete timer")
                    .setColor(0xFF1B5FC7)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true);

            Intent open = new Intent(c, NewFileActionReceiver.class);
            open.setAction(NewFileActionReceiver.ACTION);
            open.putExtra("path", path);
            b.setContentIntent(PendingIntent.getBroadcast(c, path.hashCode() + 3, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

            android.graphics.drawable.Icon ic =
                    android.graphics.drawable.Icon.createWithResource(c, R.mipmap.ic_launcher);

            // opens the panel to pick a specific timer — first, so it sits leftmost
            Intent act = new Intent(c, NewFileActionReceiver.class);
            act.setAction(NewFileActionReceiver.ACTION);
            act.putExtra("path", path);
            b.addAction(new Notification.Action.Builder(ic, "Set timer", PendingIntent.getBroadcast(
                    c, path.hashCode() + 7, act,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());

            // one tap: apply the saved default timer
            long defMs = FileEngine.prefs(c).getLong("defaultPresetMs", 0L);
            if (defMs >= 60000L) {
                Intent dflt = new Intent(c, NewFileActionReceiver.class);
                dflt.setAction(NewFileActionReceiver.ACTION_DEFAULT);
                dflt.putExtra("path", path);
                b.addAction(new Notification.Action.Builder(ic, "Default timer", PendingIntent.getBroadcast(
                        c, path.hashCode() + 11, dflt,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
            }

            nm.notify(path.hashCode(), b.build());
        } catch (Throwable ignored) { }
    }

    private static void notifySummary(Context c, int n) {
        try {
            ensureChannel(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(n + " new files")
                    .setContentText("Open FileFlow to set auto-delete timers")
                    .setColor(0xFF1B5FC7)
                    .setAutoCancel(true);
            Intent open = new Intent(c, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            b.setContentIntent(PendingIntent.getActivity(c, 991, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            nm.notify(990, b.build());
        } catch (Throwable ignored) { }
    }
}
