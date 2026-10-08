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
        JawnLauncher.startJawn(context);
    }
}
