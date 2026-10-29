package com.fileflow.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms the automatic tidy-up after a reboot. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            try { AutoTasks.sync(ctx.getApplicationContext()); } catch (Throwable ignored) { }
            try { FileWatchService.start(ctx.getApplicationContext()); } catch (Throwable ignored) { }
        }
    }
}
