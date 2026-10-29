package com.fileflow.app;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The auto-delete panel. A window rather than an activity, so it slides up over
 * whatever app the user is in. Kept compact: one default action, a small grid of
 * presets, and custom / dismiss in a single row.
 */
public class TimerOverlayService extends Service {

    static final String EXTRA_PATH = "path";
    private static final String FGS_CHANNEL = "fileflow_panel";
    private static final int FGS_ID = 4202;
    private static final long[] BUILTIN_MS = {3600000L, 86400000L, 604800000L, 2592000000L};
    private static final String[] BUILTIN_LABEL = {"1 hour", "1 day", "1 week", "1 month"};

    private WindowManager wm;
    private View root;
    private String path;

    private int cDays = 0, cHours = 0, cMinutes = 0;
    private TextView daysVal, hoursVal, minsVal, customSummary, customSave;

    /* the panel must not outlive the app it is sitting on */
    private BroadcastReceiver sysDialog;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable autoClose;
    private Runnable watchApp;

    /* palette */
    private int cBg, cFg, cFgDim, cBtnBg, cAccent, cAccentText;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try { startForeground(FGS_ID, quiet()); } catch (Throwable ignored) { }
        path = intent != null ? intent.getStringExtra(EXTRA_PATH) : null;
        if (path == null) { dismiss(); return START_NOT_STICKY; }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            askForPermission();
            dismiss();
            return START_NOT_STICKY;
        }
        watchForLeaving();
        show();
        return START_NOT_STICKY;
    }

    /**
     * An overlay window sits above every app, so leaving the current app does
     * not close it on its own. Android broadcasts ACTION_CLOSE_SYSTEM_DIALOGS
     * when home or recents is pressed; we also drop the panel when the screen
     * turns off, and a long timeout guarantees it never lingers forever.
     */
    private void watchForLeaving() {
        try {
            sysDialog = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    try {
                        String reason = intent.getStringExtra("reason");
                        if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) { dismiss(); return; }
                        if (reason == null) return;
                        if ("home".equals(reason) || "recentapps".equals(reason)
                                || "assist".equals(reason) || "lock".equals(reason)) {
                            dismiss();
                        }
                    } catch (Throwable ignored) { }
                }
            };
            IntentFilter f = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(sysDialog, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(sysDialog, f);
        } catch (Throwable ignored) { }

        // With usage access granted we can watch the app in front and close the
        // moment it changes — which is what actually happens when home is pressed.
        try {
            final String startedOn = foregroundPkg();
            if (startedOn != null) {
                watchApp = new Runnable() {
                    @Override public void run() {
                        try {
                            String now = foregroundPkg();
                            if (now != null && !now.equals(startedOn)) { dismiss(); return; }
                        } catch (Throwable ignored) { }
                        try { handler.postDelayed(watchApp, 600L); } catch (Throwable ignored) { }
                    }
                };
                handler.postDelayed(watchApp, 600L);
            }
        } catch (Throwable ignored) { }

        // safety net: never leave a stray panel behind
        try {
            autoClose = new Runnable() {
                @Override public void run() { dismiss(); }
            };
            handler.postDelayed(autoClose, 30000L);
        } catch (Throwable ignored) { }
    }

    static void start(Context c, String path) {
        try {
            Intent i = new Intent(c, TimerOverlayService.class);
            i.putExtra(EXTRA_PATH, path);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) { }
    }

    /* ---------------------------------------------------------- chrome ---- */

    private Notification quiet() {
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        FGS_CHANNEL, "Timer panel", android.app.NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, FGS_CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Choose a delete timer")
                .setPriority(Notification.PRIORITY_MIN)
                .setOngoing(true);
        return b.build();
    }

    private void askForPermission() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            String chId = "fileflow_perm";
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new android.app.NotificationChannel(
                        chId, "Permissions", android.app.NotificationManager.IMPORTANCE_HIGH));
            }
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 88, i,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, chId) : new Notification.Builder(this);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Allow the timer panel")
                    .setContentText("FileFlow needs \"Display over other apps\" to show the delete timer on top of other apps")
                    .setContentIntent(pi).setAutoCancel(true).setColor(0xFF1B5FC7);
            nm.notify(88, b.build());
        } catch (Throwable ignored) { }
    }

    /* --------------------------------------------------------- palette ---- */

    private void loadTheme() {
        try {
            String t = FileEngine.prefs(this).getString("theme", "light");
            if ("pitch".equals(t)) {
                cBg = 0xFF000000; cFg = 0xFFF2F2F2; cFgDim = 0xFFA8A8A8; cBtnBg = 0xFF171719;
            } else if ("dark".equals(t)) {
                cBg = 0xFF1B1C1F; cFg = 0xFFF2F2F2; cFgDim = 0xFFB3B3B3; cBtnBg = 0xFF2E3033;
            } else {
                cBg = 0xFFFFFFFF; cFg = 0xFF191919; cFgDim = 0xFF767676; cBtnBg = 0xFFF0F0F0;
            }
            String custom = FileEngine.prefs(this).getString("customAccent", "");
            cAccent = (custom != null && custom.matches("#[0-9a-fA-F]{6}"))
                    ? (0xFF000000 | Integer.parseInt(custom.substring(1), 16))
                    : accentColor(FileEngine.prefs(this).getString("accent", "blue"));
            cAccentText = 0xFFFFFFFF;
        } catch (Throwable t) {
            cBg = 0xFF1B1C1F; cFg = 0xFFF2F2F2; cFgDim = 0xFFB3B3B3; cBtnBg = 0xFF2E3033;
            cAccent = 0xFF1B6EF3; cAccentText = 0xFFFFFFFF;
        }
    }

    private int accentColor(String name) {
        if ("purple".equals(name)) return 0xFF8B5CF6;
        if ("green".equals(name)) return 0xFF12B76A;
        if ("pink".equals(name)) return 0xFFEC4899;
        if ("amber".equals(name)) return 0xFFF59E0B;
        if ("teal".equals(name)) return 0xFF14B8A6;
        if ("red".equals(name)) return 0xFFEF4444;
        if ("indigo".equals(name)) return 0xFF6366F1;
        return 0xFF1B6EF3;
    }

    /** the package currently in front, or null when usage access is not granted */
    private String foregroundPkg() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            UsageEvents ue = usm.queryEvents(now - 15000, now);
            if (ue == null) return null;
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            long lastTs = 0;
            while (ue.hasNextEvent()) {
                ue.getNextEvent(e);
                int type = e.getEventType();
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND
                        || type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (e.getTimeStamp() >= lastTs) { lastTs = e.getTimeStamp(); last = e.getPackageName(); }
                }
            }
            return last;
        } catch (Throwable t) { return null; }
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /* --------------------------------------------------------- presets ---- */

    private static class Preset { String label; long ms; Preset(String l, long m) { label = l; ms = m; } }

    private List<Preset> presets() {
        List<Preset> out = new ArrayList<>();
        try {
            String raw = FileEngine.prefs(this).getString("timerPresets", "");
            if (raw != null && !raw.isEmpty()) {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length() && out.size() < 4; i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    long ms = o.optLong("ms", 0);
                    if (ms < 60000L) continue;
                    out.add(new Preset(o.optString("label", "Timer"), ms));
                }
            }
        } catch (Throwable ignored) { }
        if (out.isEmpty()) {
            for (int i = 0; i < BUILTIN_MS.length; i++) out.add(new Preset(BUILTIN_LABEL[i], BUILTIN_MS[i]));
        }
        return out;
    }

    private long defaultMs() {
        try { return FileEngine.prefs(this).getLong("defaultPresetMs", 0L); } catch (Throwable t) { return 0L; }
    }

    private static String human(long ms) {
        long d = ms / 86400000L, h = (ms % 86400000L) / 3600000L, m = (ms % 3600000L) / 60000L;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append(" d ");
        if (h > 0) sb.append(h).append(" h ");
        if (m > 0 || sb.length() == 0) sb.append(m).append(" m");
        return sb.toString().trim();
    }

    /* ------------------------------------------------------------ panel ---- */

    private void show() {
        try {
            loadTheme();
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) { stopSelf(); return; }

            LinearLayout outer = new LinearLayout(this);
            outer.setOrientation(LinearLayout.VERTICAL);

            View scrim = new View(this);
            scrim.setBackgroundColor(0x99000000);
            scrim.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
            scrim.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dismiss(); }
            });
            outer.addView(scrim);

            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(round(cBg, 22));
            panel.setPadding(dp(14), dp(12), dp(14), dp(12));
            panel.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

            TextView title = new TextView(this);
            title.setText("Auto-delete");
            title.setTextColor(cFg);
            title.setTextSize(16);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            panel.addView(title);

            TextView sub = new TextView(this);
            sub.setText(new File(path).getName() + " · permanently deleted when the timer ends");
            sub.setTextColor(cFgDim);
            sub.setTextSize(11);
            sub.setPadding(0, dp(2), 0, dp(10));
            panel.addView(sub);

            // the saved default, one tap
            final long def = defaultMs();
            if (def >= 60000L) {
                TextView useDefault = pill("Use default · " + human(def), cAccent, cAccentText);
                useDefault.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { pick(def); }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
                lp.bottomMargin = dp(6);
                useDefault.setLayoutParams(lp);
                panel.addView(useDefault);
            }

            // presets, two per row
            List<Preset> list = presets();
            LinearLayout row = null;
            for (int i = 0; i < list.size(); i++) {
                if (i % 2 == 0) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                    rlp.bottomMargin = dp(6);
                    row.setLayoutParams(rlp);
                    panel.addView(row);
                }
                final long ms = list.get(i).ms;
                TextView b = pill(list.get(i).label, cBtnBg, cFg);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(40), 1f);
                blp.rightMargin = (i % 2 == 0) ? dp(6) : 0;
                b.setLayoutParams(blp);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { pick(ms); }
                });
                if (row != null) row.addView(b);
            }

            // custom + keep, one row
            LinearLayout tail = new LinearLayout(this);
            tail.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(38));
            tlp.topMargin = dp(2);
            tail.setLayoutParams(tlp);

            TextView custom = pill("Custom…", cBtnBg, cAccent);
            custom.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));
            custom.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openCustom(panel); }
            });
            tail.addView(custom);

            TextView keep = pill("Keep", cBtnBg, cFgDim);
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, -1, 1f);
            klp.leftMargin = dp(6);
            keep.setLayoutParams(klp);
            keep.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dismiss(); }
            });
            tail.addView(keep);
            panel.addView(tail);

            outer.addView(panel);

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.START;
            root = outer;
            wm.addView(root, lp);
        } catch (Throwable t) {
            stopSelf();
        }
    }

    private TextView pill(String text, int bg, int fg) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setBackground(round(bg, 20));
        return t;
    }

    /* ---------------------------------------------------------- custom ---- */

    private void openCustom(LinearLayout panel) {
        try {
            for (int i = 0; i < panel.getChildCount(); i++) panel.getChildAt(i).setVisibility(View.GONE);
            cDays = 0; cHours = 0; cMinutes = 0;

            TextView t = new TextView(this);
            t.setText("Custom timer");
            t.setTextColor(cFg);
            t.setTextSize(16);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            panel.addView(t);

            customSummary = new TextView(this);
            customSummary.setTextColor(cFgDim);
            customSummary.setTextSize(11);
            customSummary.setPadding(0, dp(2), 0, dp(8));
            panel.addView(customSummary);

            panel.addView(stepper("Days", 0));
            panel.addView(stepper("Hours", 1));
            panel.addView(stepper("Minutes", 2));

            TextView save = new TextView(this);
            customSave = save;
            save.setText("Set timer");
            save.setTextColor(cAccentText);
            save.setTextSize(13);
            save.setTypeface(Typeface.DEFAULT_BOLD);
            save.setGravity(Gravity.CENTER);
            save.setBackground(round(cAccent, 20));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(40));
            slp.topMargin = dp(8);
            save.setLayoutParams(slp);
            save.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    long ms = cDays * 86400000L + cHours * 3600000L + cMinutes * 60000L;
                    if (ms < 60000L) return;   // the button stays dimmed until a value is picked
                    pick(ms);
                }
            });
            panel.addView(save);

            TextView back = pill("Back", cBtnBg, cFgDim);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(38));
            blp.topMargin = dp(6);
            back.setLayoutParams(blp);
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dismiss(); }
            });
            panel.addView(back);

            updateCustom();
        } catch (Throwable ignored) { }
    }

    private void updateCustom() {
        try {
            if (daysVal != null) daysVal.setText(String.valueOf(cDays));
            if (hoursVal != null) hoursVal.setText(String.valueOf(cHours));
            if (minsVal != null) minsVal.setText(String.valueOf(cMinutes));
            long total = cDays * 86400000L + cHours * 3600000L + cMinutes * 60000L;
            if (customSummary != null) {
                customSummary.setText("Permanently deletes in " + human(total));
            }
            if (customSave != null) {
                boolean empty = total < 60000L;
                customSave.setAlpha(empty ? 0.45f : 1f);
                customSave.setBackground(round(empty ? cBtnBg : cAccent, 20));
            }
        } catch (Throwable ignored) { }
    }

    private LinearLayout stepper(String label, final int unit) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(42));
        rlp.bottomMargin = dp(4);
        row.setLayoutParams(rlp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(cFg);
        name.setTextSize(13);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(name);

        TextView minus = stepButton("\u2212");
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { bump(unit, -1); }
        });
        row.addView(minus);

        TextView val = new TextView(this);
        val.setText("0");
        val.setTextColor(cFg);
        val.setTextSize(15);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setGravity(Gravity.CENTER);
        val.setLayoutParams(new LinearLayout.LayoutParams(dp(46), -2));
        row.addView(val);
        if (unit == 0) daysVal = val; else if (unit == 1) hoursVal = val; else minsVal = val;

        TextView plus = stepButton("+");
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { bump(unit, 1); }
        });
        row.addView(plus);
        return row;
    }

    private TextView stepButton(String txt) {
        TextView b = new TextView(this);
        b.setText(txt);
        b.setTextColor(cFg);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(cBtnBg, 18));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        return b;
    }

    private void bump(int unit, int dir) {
        if (unit == 0) cDays = Math.max(0, Math.min(365, cDays + dir));
        else if (unit == 1) { cHours += dir; if (cHours > 23) cHours = 0; if (cHours < 0) cHours = 23; }
        else { cMinutes += dir; if (cMinutes > 59) cMinutes = 0; if (cMinutes < 0) cMinutes = 59; }
        updateCustom();
    }

    /* ------------------------------------------------------------ apply ---- */

    private void pick(long ms) {
        try {
            long at = System.currentTimeMillis() + ms;
            FileEngine.addScheduled(this, path, at);
            AutoTasks.scheduleDelete(this, path, at);
            try {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(path.hashCode());
            } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
        dismiss();
    }

    private void dismiss() {
        try { if (handler != null && autoClose != null) handler.removeCallbacks(autoClose); } catch (Throwable ignored) { }
        try { if (handler != null && watchApp != null) handler.removeCallbacks(watchApp); } catch (Throwable ignored) { }
        try {
            if (sysDialog != null) { unregisterReceiver(sysDialog); sysDialog = null; }
        } catch (Throwable ignored) { }
        try { if (wm != null && root != null) wm.removeView(root); } catch (Throwable ignored) { }
        root = null;
        try { stopForeground(true); } catch (Throwable ignored) { }
        stopSelf();
    }

    @Override public void onDestroy() {
        try { if (handler != null && autoClose != null) handler.removeCallbacks(autoClose); } catch (Throwable ignored) { }
        try { if (handler != null && watchApp != null) handler.removeCallbacks(watchApp); } catch (Throwable ignored) { }
        try { if (sysDialog != null) { unregisterReceiver(sysDialog); sysDialog = null; } } catch (Throwable ignored) { }
        try { if (wm != null && root != null) wm.removeView(root); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
