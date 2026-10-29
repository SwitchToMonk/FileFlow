package com.fileflow.app;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * FileFlow — the native shell.
 *
 * The UI is HTML/CSS/JS in assets/www. This activity owns the filesystem work
 * (all of it handed to FileEngine on a background thread) and exposes it to the
 * page through window.FileFlow.
 */
public class MainActivity extends Activity {

    private WebView web;
    private String pendingAutoDelete = null;
    private static final String DEFAULT_URL = "file:///android_asset/www/index.html";
    private static final int REQ_LEGACY = 11;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setDisplayZoomControls(false);
        web.setBackgroundColor(Color.parseColor("#FAF8FF"));

        web.addJavascriptInterface(new Bridge(), "FileFlow");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                    return true;
                }
                return false;
            }
        });

        setContentView(web);

        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1); } catch (Throwable ignored) { }
        }
        if (Build.VERSION.SDK_INT < 30) {
            try {
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LEGACY);
                }
            } catch (Throwable ignored) { }
        }

        // the periodic new-file check has to be armed here: until now it was only
        // scheduled on reboot or after a settings change, so a fresh install
        // never ran it at all
        try { NewFileWatcher.ensureChannel(this); } catch (Throwable ignored) { }
        try { AutoTasks.syncWatcher(this); } catch (Throwable ignored) { }
        try { FileWatchService.start(this); } catch (Throwable ignored) { }

        if (getIntent() != null) pendingAutoDelete = getIntent().getStringExtra("autoDeletePath");
        String url = getIntent() != null ? getIntent().getStringExtra("url") : null;
        web.loadUrl(url != null ? url : DEFAULT_URL);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i == null) return;
        String p = i.getStringExtra("autoDeletePath");
        if (p != null && web != null) {
            pendingAutoDelete = p;
            web.post(new Runnable() {
                @Override public void run() {
                    try {
                        web.evaluateJavascript("window.ffAutoDeletePrompt&&ffAutoDeletePrompt("
                                + org.json.JSONObject.quote(pendingAutoDelete) + ")", null);
                    } catch (Throwable ignored) { }
                }
            });
        }
        if (i.getStringExtra("url") != null && web != null) web.loadUrl(i.getStringExtra("url"));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null) {
            web.evaluateJavascript("window.ffBack && ffBack()", new android.webkit.ValueCallback<String>() {
                @Override public void onReceiveValue(String value) {
                    if (value == null || "false".equals(value) || "\"false\"".equals(value)) finish();
                }
            });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private static long lastWatch = 0;

    @Override
    protected void onResume() {
        super.onResume();
        // looking for new files whenever the app comes forward means a download
        // is noticed even if the alarm has not fired yet
        if (System.currentTimeMillis() - lastWatch > 20000) {
            lastWatch = System.currentTimeMillis();
            new Thread(new Runnable() {
                @Override public void run() {
                    try { NewFileWatcher.run(MainActivity.this); } catch (Throwable ignored) { }
                }
            }).start();
        }
        if (web != null) web.evaluateJavascript("window.ffAccessChanged && ffAccessChanged()", null);
    }

    private void cb(final int job, final boolean ok, final String json) {
        final String safe = json == null ? "null" : json;
        web.post(new Runnable() {
            @Override public void run() {
                try {
                    web.evaluateJavascript("window.__ff&&__ff(" + job + "," + ok + "," + org.json.JSONObject.quote(safe) + ")", null);
                } catch (Throwable ignored) { }
            }
        });
    }

    private void run(final int job, final String what, final String arg) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String out = "{}";
                    if ("scan".equals(what)) out = FileEngine.scanJson(MainActivity.this, Integer.parseInt(arg)).toString();
                    else if ("organise".equals(what)) out = FileEngine.organise(MainActivity.this, "1".equals(arg), 4000).toString();
                    else if ("fuzzy".equals(what)) {
                        JSONObject a = new JSONObject(arg);
                        out = FileEngine.fuzzy(MainActivity.this, a.optString("q"), a.optDouble("t", 0.6), 300).toString();
                    }
                    else if ("toBin".equals(what)) out = FileEngine.toBin(MainActivity.this, new JSONArray(arg)).toString();
                    else if ("listBin".equals(what)) out = FileEngine.listBin(MainActivity.this).toString();
                    else if ("restore".equals(what)) out = FileEngine.restore(MainActivity.this, new JSONArray(arg)).toString();
                    else if ("purge".equals(what)) out = FileEngine.purge(MainActivity.this, new JSONArray(arg)).toString();
                    else if ("emptyBin".equals(what)) out = FileEngine.emptyBin(MainActivity.this).toString();
                    else if ("stats".equals(what)) out = FileEngine.stats(MainActivity.this).toString();
                    else if ("counts".equals(what)) out = FileEngine.categoryCounts(MainActivity.this, 4000).toString();
                    else if ("runAuto".equals(what)) out = AutoTasks.run(MainActivity.this, false).toString();
                    else if ("recent".equals(what)) {
                        JSONObject a = new JSONObject(arg);
                        out = FileEngine.recent(MainActivity.this, a.optInt("days", 7), a.optInt("max", 20)).toString();
                    }
                    else if ("suggestions".equals(what)) out = FileEngine.suggestions(MainActivity.this).toString();
                    else if ("searchAll".equals(what)) {
                        JSONObject a = new JSONObject(arg);
                        out = FileEngine.searchAll(MainActivity.this, a.optString("q"), a.optDouble("t", 0.6), a.optBoolean("bin", true)).toString();
                    }
                    else if ("readText".equals(what)) {
                        JSONObject a = new JSONObject(arg);
                        JSONObject o = new JSONObject();
                        o.put("text", FileEngine.readText(a.optString("path"), a.optInt("max", 200000)));
                        out = o.toString();
                    }
                    else if ("fileInfo".equals(what)) {
                        JSONObject a = new JSONObject(arg);
                        out = FileEngine.fileInfo(MainActivity.this, a.optString("path")).toString();
                    }
                    else if ("binPaths".equals(what)) out = FileEngine.binPaths(MainActivity.this).toString();
                    else if ("listScheduled".equals(what)) out = FileEngine.pruneScheduled(MainActivity.this).toString();
                    else if ("scheduleDelete".equals(what)) {
                        JSONObject a = new JSONObject(arg);
                        String path = a.optString("path");
                        long at = System.currentTimeMillis() + a.optLong("delay", 3600000L);
                        JSONObject e = FileEngine.addScheduled(MainActivity.this, path, at);
                        AutoTasks.scheduleDelete(MainActivity.this, path, at);
                        out = e.toString();
                    }
                    else if ("cancelScheduled".equals(what)) {
                        JSONObject a = new JSONObject(arg);
                        String path = a.optString("path");
                        FileEngine.removeScheduled(MainActivity.this, path);
                        AutoTasks.cancelDelete(MainActivity.this, path);
                        out = "{}";
                    }
                    else if ("folders".equals(what)) out = FileEngine.folders(MainActivity.this, 400).toString();
                    else if ("deleteFolder".equals(what)) {
                        JSONObject a = new JSONObject(arg);
                        out = FileEngine.deleteFolder(MainActivity.this, a.optString("path")).toString();
                    }
                    else if ("deleteEmptyFolders".equals(what)) out = FileEngine.deleteEmptyFolders(MainActivity.this).toString();
                    else if ("checkNewFiles".equals(what)) {
                        JSONObject o = new JSONObject();
                        o.put("count", NewFileWatcher.run(MainActivity.this));
                        out = o.toString();
                    }
                    cb(job, true, out);
                } catch (Throwable t) {
                    cb(job, false, "\"" + (t.getMessage() == null ? "error" : t.getMessage().replace("\"", "'")) + "\"");
                }
            }
        }).start();
    }

    private class Bridge {

        /* ---------- access ---------- */

        @JavascriptInterface
        public boolean hasAccess() {
            try {
                if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
                return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable t) { return false; }
        }

        @JavascriptInterface
        public void requestAccess() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } else {
                            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LEGACY);
                        }
                    } catch (Throwable t) {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Throwable ignored) { }
                    }
                }
            });
        }

        /* ---------- async engine calls ---------- */

        @JavascriptInterface public void scan(int job, int limit) { run(job, "scan", String.valueOf(limit)); }
        @JavascriptInterface public void organise(int job, boolean dryRun) { run(job, "organise", dryRun ? "1" : "0"); }
        @JavascriptInterface public void fuzzy(int job, String q, int thresholdPct) {
            try {
                JSONObject a = new JSONObject();
                a.put("q", q == null ? "" : q);
                a.put("t", thresholdPct / 100.0);
                run(job, "fuzzy", a.toString());
            } catch (Throwable t) { cb(job, false, "\"bad query\""); }
        }
        @JavascriptInterface public void toBin(int job, String pathsJson) { run(job, "toBin", pathsJson); }
        @JavascriptInterface public void listBin(int job) { run(job, "listBin", "{}"); }
        @JavascriptInterface public void restore(int job, String idsJson) { run(job, "restore", idsJson); }
        @JavascriptInterface public void purge(int job, String idsJson) { run(job, "purge", idsJson); }
        @JavascriptInterface public void emptyBin(int job) { run(job, "emptyBin", "{}"); }
        @JavascriptInterface public void stats(int job) { run(job, "stats", "{}"); }
        @JavascriptInterface public void counts(int job, int limit) { run(job, "counts", String.valueOf(limit)); }
        @JavascriptInterface public void runAuto(int job) { run(job, "runAuto", "{}"); }

        @JavascriptInterface public void recent(int job, int days, int max) {
            try { JSONObject a = new JSONObject(); a.put("days", days); a.put("max", max); run(job, "recent", a.toString()); }
            catch (Throwable t) { cb(job, false, "\"bad args\""); }
        }
        @JavascriptInterface public void suggestions(int job) { run(job, "suggestions", "{}"); }
        @JavascriptInterface public void searchAll(int job, String q, int thresholdPct, boolean includeBin) {
            try {
                JSONObject a = new JSONObject();
                a.put("q", q == null ? "" : q);
                a.put("t", thresholdPct / 100.0);
                a.put("bin", includeBin);
                run(job, "searchAll", a.toString());
            } catch (Throwable t) { cb(job, false, "\"bad query\""); }
        }
        @JavascriptInterface public void readText(int job, String path, int max) {
            try { JSONObject a = new JSONObject(); a.put("path", path); a.put("max", max); run(job, "readText", a.toString()); }
            catch (Throwable t) { cb(job, false, "\"bad path\""); }
        }
        @JavascriptInterface public void fileInfo(int job, String path) {
            try { JSONObject a = new JSONObject(); a.put("path", path); run(job, "fileInfo", a.toString()); }
            catch (Throwable t) { cb(job, false, "\"bad path\""); }
        }
        @JavascriptInterface public void binPaths(int job) { run(job, "binPaths", "{}"); }
        @JavascriptInterface public void listScheduled(int job) { run(job, "listScheduled", "{}"); }
        @JavascriptInterface public void scheduleDelete(int job, String path, long delayMs) {
            try { JSONObject a = new JSONObject(); a.put("path", path); a.put("delay", delayMs); run(job, "scheduleDelete", a.toString()); }
            catch (Throwable t) { cb(job, false, "\"bad args\""); }
        }
        @JavascriptInterface public void cancelScheduled(int job, String path) {
            try { JSONObject a = new JSONObject(); a.put("path", path); run(job, "cancelScheduled", a.toString()); }
            catch (Throwable t) { cb(job, false, "\"bad args\""); }
        }
        @JavascriptInterface public void checkNewFiles(int job) { run(job, "checkNewFiles", "{}"); }

        @JavascriptInterface public void folders(int job) { run(job, "folders", "{}"); }
        @JavascriptInterface public void deleteFolder(int job, String path) {
            try { JSONObject a = new JSONObject(); a.put("path", path); run(job, "deleteFolder", a.toString()); }
            catch (Throwable t) { cb(job, false, "\"bad path\""); }
        }
        @JavascriptInterface public void deleteEmptyFolders(int job) { run(job, "deleteEmptyFolders", "{}"); }

        @JavascriptInterface
        public boolean notifEnabled() {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                return nm != null && nm.areNotificationsEnabled();
            } catch (Throwable t) { return true; }
        }

        @JavascriptInterface
        public void openNotifSettings() {
            try {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(i);
            } catch (Throwable t) { }
        }

        @JavascriptInterface
        public void requestNotif() {
            try {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                else openNotifSettings();
            } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public String takePendingAutoDelete() {
            String pth = pendingAutoDelete;
            pendingAutoDelete = null;
            return pth == null ? "" : pth;
        }

        @JavascriptInterface
        public boolean canOverlay() {
            try {
                if (Build.VERSION.SDK_INT >= 23) return Settings.canDrawOverlays(MainActivity.this);
                return true;
            } catch (Throwable t) { return false; }
        }

        @JavascriptInterface
        public boolean hasUsageAccess() {
            try {
                android.app.AppOpsManager ops =
                        (android.app.AppOpsManager) getSystemService(android.content.Context.APP_OPS_SERVICE);
                if (ops == null) return false;
                int mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), getPackageName());
                return mode == android.app.AppOpsManager.MODE_ALLOWED;
            } catch (Throwable t) { return false; }
        }

        @JavascriptInterface
        public void openUsageAccess() {
            try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); } catch (Throwable t) { }
        }

        @JavascriptInterface
        public void testOverlay() {
            try {
                java.io.File f = new java.io.File(FileEngine.root(), "Download");
                String sample = new java.io.File(f, "sample-download.zip").getAbsolutePath();
                TimerOverlayService.start(MainActivity.this, sample);
            } catch (Throwable t) { }
        }

        @JavascriptInterface
        public void requestOverlay() {
            try {
                if (Build.VERSION.SDK_INT >= 23) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            } catch (Throwable t) { }
        }

        /* ---------- prefs (rules + toggles) ---------- */

        @JavascriptInterface
        public String getPrefs() {
            try {
                JSONObject o = new JSONObject();
                o.put("rules", FileEngine.rules(MainActivity.this));
                o.put("baseDir", FileEngine.prefs(MainActivity.this).getString("baseDir", ""));
                o.put("autoOrganise", FileEngine.prefs(MainActivity.this).getBoolean("autoOrganise", false));
                o.put("autoGroup", FileEngine.prefs(MainActivity.this).getBoolean("autoGroup", false));
                o.put("autoDelete", FileEngine.prefs(MainActivity.this).getBoolean("autoDelete", false));
                o.put("autoQuery", FileEngine.prefs(MainActivity.this).getString("autoQuery", ""));
                o.put("autoThreshold", FileEngine.prefs(MainActivity.this).getInt("autoThreshold", 75));
                o.put("notify", FileEngine.prefs(MainActivity.this).getBoolean("notify", true));
                o.put("watchNew", FileEngine.prefs(MainActivity.this).getBoolean("watchNew", true));
                o.put("autoDeletePermanent", FileEngine.prefs(MainActivity.this).getBoolean("autoDeletePermanent", false));
                o.put("autoEmptyFolders", FileEngine.prefs(MainActivity.this).getBoolean("autoEmptyFolders", false));
                o.put("customAccent", FileEngine.prefs(MainActivity.this).getString("customAccent", ""));
                o.put("timerPresets", FileEngine.prefs(MainActivity.this).getString("timerPresets", ""));
                o.put("defaultPresetMs", FileEngine.prefs(MainActivity.this).getLong("defaultPresetMs", 0L));
                o.put("theme", FileEngine.prefs(MainActivity.this).getString("theme", "light"));
                o.put("accent", FileEngine.prefs(MainActivity.this).getString("accent", "blue"));
                return o.toString();
            } catch (Throwable t) { return "{}"; }
        }

        @JavascriptInterface
        public void setPrefs(String json) {
            try {
                JSONObject o = new JSONObject(json == null ? "{}" : json);
                android.content.SharedPreferences.Editor e = FileEngine.prefs(MainActivity.this).edit();
                if (o.has("rules")) e.putString("rules", o.optJSONArray("rules") == null ? "[]" : o.optJSONArray("rules").toString());
                if (o.has("baseDir")) e.putString("baseDir", o.optString("baseDir", ""));
                if (o.has("autoOrganise")) e.putBoolean("autoOrganise", o.optBoolean("autoOrganise"));
                if (o.has("autoGroup")) e.putBoolean("autoGroup", o.optBoolean("autoGroup"));
                if (o.has("autoDelete")) e.putBoolean("autoDelete", o.optBoolean("autoDelete"));
                if (o.has("autoQuery")) e.putString("autoQuery", o.optString("autoQuery", ""));
                if (o.has("autoThreshold")) e.putInt("autoThreshold", o.optInt("autoThreshold", 75));
                if (o.has("notify")) e.putBoolean("notify", o.optBoolean("notify"));
                if (o.has("watchNew")) e.putBoolean("watchNew", o.optBoolean("watchNew", true));
                if (o.has("autoDeletePermanent")) e.putBoolean("autoDeletePermanent", o.optBoolean("autoDeletePermanent"));
                if (o.has("autoEmptyFolders")) e.putBoolean("autoEmptyFolders", o.optBoolean("autoEmptyFolders"));
                if (o.has("customAccent")) e.putString("customAccent", o.optString("customAccent", ""));
                if (o.has("timerPresets")) e.putString("timerPresets", o.optString("timerPresets", ""));
                if (o.has("defaultPresetMs")) e.putLong("defaultPresetMs", o.optLong("defaultPresetMs", 0L));
                if (o.has("theme")) e.putString("theme", o.optString("theme", "light"));
                if (o.has("accent")) e.putString("accent", o.optString("accent", "blue"));
                e.apply();
                AutoTasks.sync(MainActivity.this);
            } catch (Throwable ignored) { }
        }

        /* ---------- misc ---------- */

        @JavascriptInterface
        public String appVersion() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable t) { return "1.0"; }
        }

        @JavascriptInterface
        public void theme(final String barColor, final boolean lightIcons) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        int c = Color.parseColor(barColor);
                        getWindow().setStatusBarColor(c);
                        getWindow().setNavigationBarColor(c);
                        web.setBackgroundColor(c);
                        if (Build.VERSION.SDK_INT >= 23) {
                            View d = getWindow().getDecorView();
                            int f = d.getSystemUiVisibility();
                            if (lightIcons) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                            else f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                            if (Build.VERSION.SDK_INT >= 26) {
                                if (lightIcons) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                                else f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                            }
                            d.setSystemUiVisibility(f);
                        }
                    } catch (Throwable ignored) { }
                }
            });
        }

        @JavascriptInterface
        public void notify(final String title, final String text) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm == null) return;
                if (Build.VERSION.SDK_INT >= 26) {
                    nm.createNotificationChannel(new NotificationChannel("fileflow", "FileFlow", NotificationManager.IMPORTANCE_DEFAULT));
                }
                Notification.Builder b = Build.VERSION.SDK_INT >= 26
                        ? new Notification.Builder(MainActivity.this, "fileflow")
                        : new Notification.Builder(MainActivity.this);
                b.setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle(title == null ? "FileFlow" : title)
                        .setContentText(text == null ? "" : text)
                        .setColor(0xFF1B5FC7)
                        .setAutoCancel(true);
                Intent open = new Intent(MainActivity.this, MainActivity.class);
                open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                b.setContentIntent(PendingIntent.getActivity(MainActivity.this, 51, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                nm.notify(7001, b.build());
            } catch (Throwable ignored) { }
        }

        /** open a file with whatever app handles it */
        @JavascriptInterface
        public void openFile(final String path) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        Uri u = Uri.parse("content://com.fileflow.app.files" + Uri.encode(path));
                        i.setDataAndType(u, mimeOf(path));
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Throwable t) {
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setDataAndType(Uri.fromFile(new java.io.File(path)), mimeOf(path));
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Throwable ignored) { }
                    }
                }
            });
        }

        private String mimeOf(String path) {
            String e = FileEngine.extOf(path);
            String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
            return m == null ? "*/*" : m;
        }
    }
}
