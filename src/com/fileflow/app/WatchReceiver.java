package com.fileflow.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Periodic look for files that landed since the last check. */
public class WatchReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context ctx, Intent intent) {
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try { NewFileWatcher.run(c); } catch (Throwable ignored) { }
                try { AutoTasks.syncWatcher(c); } catch (Throwable ignored) { }   // arm the next check
            }
        }).start();
    }
}
