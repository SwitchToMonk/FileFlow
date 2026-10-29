package com.fileflow.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The "Auto-delete" button on a new-file notification: open the timer picker. */
public class NewFileActionReceiver extends BroadcastReceiver {

    static final String ACTION = "com.fileflow.app.NEWFILE_ACTION";
    /** one tap: schedule the saved default timer, no panel */
    static final String ACTION_DEFAULT = "com.fileflow.app.NEWFILE_DEFAULT";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            String path = intent.getStringExtra("path");
            if (path == null) return;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(path.hashCode());

            if (ACTION_DEFAULT.equals(intent.getAction())) {
                // apply the saved default straight away — no panel
                long ms = FileEngine.prefs(ctx).getLong("defaultPresetMs", 0L);
                if (ms >= 60000L) {
                    long at = System.currentTimeMillis() + ms;
                    FileEngine.addScheduled(ctx, path, at);
                    AutoTasks.scheduleDelete(ctx, path, at);
                    return;
                }
                // no default saved — fall through to the panel
            }
            // show the panel over whatever app is in front
            TimerOverlayService.start(ctx, path);
        } catch (Throwable ignored) { }
    }
}
