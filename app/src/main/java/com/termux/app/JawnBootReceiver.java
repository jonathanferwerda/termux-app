package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.termux.shared.logger.Logger;

/** Starts jawn after boot (after the first unlock, when app data is available). */
public class JawnBootReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "JawnBootReceiver";

    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Logger.logInfo(LOG_TAG, "BOOT_COMPLETED received");
        if (JawnTailnetController.isEnabled(context)) JawnTailnetController.startService(context);
        if (!JawnLauncher.isAutostartEnabled(context)) {
            Logger.logInfo(LOG_TAG, "Autostart disabled; not starting jawn");
            return;
        }
        // startJawnBackground() also brings the BLE bridge up first (when it is
        // tied to jawn), so jawn's listener has a daemon to dial the moment it
        // starts. BOOT_COMPLETED is an allowed foreground-service-start context.
        JawnLauncher.startJawnBackground(context);
    }
}
