package com.jawnos.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.jawnos.shared.logger.Logger;

/**
 * An APK update kills the app process. The tailnet is a sticky foreground
 * service, which Android restarts after a crash but NOT after a package
 * replace, and the boot receiver only fires on a reboot - so an update used to
 * leave the tunnel down until somebody opened the app. That is exactly how both
 * handhelds sat offline for two days after the 2026-10-31 build.
 *
 * MY_PACKAGE_REPLACED is delivered to this app after every update and is an
 * allowed context for starting a foreground service, so the tunnel is asked to
 * come back here instead of waiting for a person.
 */
public class JawnUpdateReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "JawnUpdateReceiver";

    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        Logger.logInfo(LOG_TAG, "MY_PACKAGE_REPLACED received");
        // The service re-reads the person's stored choices; this only nudges it
        // when the tailnet is both joined and set to heal itself.
        if (!JawnTailnetController.isEnabled(context)) return;
        if (!JawnTailnetController.isAutoconnect(context)) return;
        try {
            JawnTailnetController.startService(context);
        } catch (RuntimeException e) {
            Logger.logInfo(LOG_TAG, "tailnet start refused after update: " + e.getClass().getSimpleName());
        }
    }
}
