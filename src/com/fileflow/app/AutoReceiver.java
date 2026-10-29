package com.fileflow.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Runs the switched-on automatic tasks on the 30-minute beat. */
public class AutoReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context ctx, Intent intent) {
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try { AutoTasks.run(c, true); } catch (Throwable ignored) { }
            }
        }).start();
    }
}
