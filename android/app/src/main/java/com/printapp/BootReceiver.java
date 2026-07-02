package com.printapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the print-queue relay after a reboot so the godam phone recovers
 *  from power cuts without anyone opening the app. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            if (PrintQueueService.isConfigured(context)) PrintQueueService.start(context);
        } catch (Throwable ignored) {}
    }
}
