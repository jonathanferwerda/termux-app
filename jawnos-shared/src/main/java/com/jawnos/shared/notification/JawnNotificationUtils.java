package com.jawnos.shared.notification;

import android.content.Context;
import android.content.SharedPreferences;

import com.jawnos.shared.jawnos.JawnConstants;

/**
 * Hands out notification ids that the app is not already using.
 *
 * The original termux-shared file kept the counter in the Termux app's shared
 * preferences because plugins shared a uid. JawnOS is one app, so the counter
 * lives in its own small preference file and skips the two reserved ids.
 */
public class JawnNotificationUtils {

    private static final String PREFS = "jawnos_notifications";
    private static final String KEY_LAST_ID = "last_notification_id";
    private static final int DEFAULT_ID = 1000;

    public synchronized static int getNextNotificationId(final Context context) {
        if (context == null) return DEFAULT_ID;
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int next = preferences.getInt(KEY_LAST_ID, DEFAULT_ID) + 1;
        while (next == JawnConstants.TERMUX_APP_NOTIFICATION_ID
            || next == JawnConstants.TERMUX_RUN_COMMAND_NOTIFICATION_ID) {
            next++;
        }
        if (next == Integer.MAX_VALUE || next < 0) next = DEFAULT_ID;
        preferences.edit().putInt(KEY_LAST_ID, next).apply();
        return next;
    }
}
