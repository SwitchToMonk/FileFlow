package com.fileflow.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Watches the folders files actually land in and raises a notification the
 * moment one appears.
 *
 * Polling with an alarm was useless in the background: Android defers those
 * alarms by many minutes (or indefinitely in Doze), so the notification only
 * ever showed up when the app was opened. This is event driven instead — a
 * FileObserver on each key folder fires on create/move-in, and a slow poll
 * inside the service is only a safety net.
 */
public class FileWatchService extends Service {

    private static final String CHANNEL = "fileflow_watch";
    private static final int FOREGROUND_ID = 4101;
    private static final long POLL_MS = 5 * 60 * 1000L;
    private static final long DEBOUNCE_MS = 1500L;

    private final List<FileObserver> observers = new ArrayList<>();
    private Handler handler;
    private Runnable pending;
    private Runnable poll;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try { startForeground(FOREGROUND_ID, buildForeground()); } catch (Throwable ignored) { }
        startWatching();
        schedulePoll();
        return START_STICKY;
    }

    private Notification buildForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "File watching",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps an eye out for new files");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Watching for new files")
                .setContentText("FileFlow will tell you when something new arrives")
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        b.setContentIntent(PendingIntent.getActivity(this, 51, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return b.build();
    }

    /** the folders downloads, screenshots, camera shots and transfers land in */
    private List<File> watchDirs() {
        List<File> dirs = new ArrayList<>();
        try {
            File root = FileEngine.root();
            if (root == null || !root.exists()) return dirs;
            dirs.add(root);
            File[] kids = root.listFiles();
            if (kids == null) return dirs;
            for (File f : kids) {
                if (!f.isDirectory()) continue;
                String n = f.getName();
                if (n.startsWith(".") || "Android".equalsIgnoreCase(n)) continue;
                dirs.add(f);
                // one level down catches DCIM/Camera, Pictures/Screenshots,
                // WhatsApp/Media and friends
                if (dirs.size() < 60) {
                    File[] sub = f.listFiles();
                    if (sub == null) continue;
                    int added = 0;
                    for (File s : sub) {
                        if (s.isDirectory() && !s.getName().startsWith(".") && added < 6) {
                            dirs.add(s); added++;
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return dirs;
    }

    private void startWatching() {
        stopWatching();
        if (!FileEngine.prefs(this).getBoolean("watchNew", true)) return;
        final int mask = FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE;
        for (File d : watchDirs()) {
            try {
                FileObserver o = new FileObserver(d.getAbsolutePath(), mask) {
                    @Override public void onEvent(int event, String path) {
                        if (path == null) return;
                        if (path.startsWith(".")) return;
                        trigger();
                    }
                };
                o.startWatching();
                observers.add(o);
            } catch (Throwable ignored) { }
        }
    }

    private void stopWatching() {
        for (FileObserver o : observers) {
            try { o.stopWatching(); } catch (Throwable ignored) { }
        }
        observers.clear();
    }

    /** a burst of events (a download writes several) collapses into one check */
    private void trigger() {
        if (handler == null) return;
        if (pending != null) handler.removeCallbacks(pending);
        pending = new Runnable() {
            @Override public void run() {
                pending = null;
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { NewFileWatcher.run(FileWatchService.this); } catch (Throwable ignored) { }
                    }
                }).start();
            }
        };
        handler.postDelayed(pending, DEBOUNCE_MS);
    }

    private void schedulePoll() {
        if (handler == null) return;
        poll = new Runnable() {
            @Override public void run() {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { NewFileWatcher.run(FileWatchService.this); } catch (Throwable ignored) { }
                    }
                }).start();
                handler.postDelayed(poll, POLL_MS);
            }
        };
        handler.postDelayed(poll, POLL_MS);
    }

    @Override
    public void onDestroy() {
        stopWatching();
        try { if (handler != null && poll != null) handler.removeCallbacks(poll); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    /* ---------- start / stop helpers ---------- */

    static void start(Context c) {
        try {
            if (!FileEngine.prefs(c).getBoolean("watchNew", true)) { stop(c); return; }
            Intent i = new Intent(c, FileWatchService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) { }
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, FileWatchService.class)); } catch (Throwable ignored) { }
    }
}
