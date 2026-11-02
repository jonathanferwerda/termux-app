package com.jawnos.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.jawnos.shared.logger.Logger;

/** Starts jawn after boot (after the first unlock, when app data is available). */
public class JawnBootReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "JawnBootReceiver";

    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Logger.logInfo(LOG_TAG, "BOOT_COMPLETED received");
        // The next Clock alarm lives in a file on disk; arm it again.
        JawnClockAlarm.sync(context);
        // Whether the tunnel may heal itself is the service's decision, not
        // this receiver's: the service reads the stored enabled and autoconnect
        // choices, and the last status, before it starts anything. A refused
        // foreground start here must not cost the boot its jawn start.
        if (JawnTailnetController.isEnabled(context)) {
            try {
                JawnTailnetController.startService(context);
            } catch (RuntimeException e) {
                Logger.logInfo(LOG_TAG, "tailnet start refused at boot: " + e.getClass().getSimpleName());
            }
        }
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
