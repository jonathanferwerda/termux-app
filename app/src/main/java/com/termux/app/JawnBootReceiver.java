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
        if (!JawnLauncher.isAutostartEnabled(context)) {
            Logger.logInfo(LOG_TAG, "Autostart disabled; not starting jawn");
            return;
        }
        // Bring the BLE bridge up first, so jawn's listener has a daemon to
        // dial the moment it starts. Same shared UID, and BOOT_COMPLETED is an
        // allowed foreground-service-start context.
        try {
            Intent ble = new Intent();
            ble.setClassName("com.termux.api", "com.termux.api.JawnBleService");
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(ble);
            else context.startService(ble);
            Logger.logInfo(LOG_TAG, "BLE bridge start requested");
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "Could not start the BLE bridge: " + t);
        }
        JawnLauncher.startJawnBackground(context);
    }
}
