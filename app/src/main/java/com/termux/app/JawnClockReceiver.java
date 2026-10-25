package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

/**
 * The bridge between jawn's Clock and Android's alarm machinery.
 *
 * jawn broadcasts three messages from the Perl side:
 *
 *   com.termux.app.JAWN_CLOCK_SYNC     the next alarm changed (re-arm)
 *   com.termux.app.JAWN_CLOCK_RING     an alarm is ringing now (show it)
 *   com.termux.app.JAWN_CLOCK_DISMISSED Dismiss/Snooze (take it down)
 *
 * and AlarmManager delivers com.termux.app.CLOCK_ALARM five seconds before the
 * named time, which is the wake that gets the phone out of Doze. The exact
 * alarm and the ring broadcast both end at {@link JawnClockNotifier}, so the
 * lock screen shows the alarm whichever one arrives first.
 */
public class JawnClockReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "JawnClockReceiver";
    public static final String ACTION_SYNC = "com.termux.app.JAWN_CLOCK_SYNC";
    public static final String ACTION_RING = "com.termux.app.JAWN_CLOCK_RING";
    public static final String ACTION_DISMISSED = "com.termux.app.JAWN_CLOCK_DISMISSED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();

        if (ACTION_SYNC.equals(action)) {
            JawnClockAlarm.sync(context, intent);
            return;
        }

        if (ACTION_DISMISSED.equals(action)) {
            JawnClockNotifier.cancel(context);
            JawnClockNotifier.releaseWakeLock();
            JawnClockAlarm.sync(context);
            return;
        }

        if (ACTION_RING.equals(action)) {
            show(context, intent, false);
            return;
        }

        if (JawnClockAlarm.ACTION_ALARM.equals(action)) {
            // The exact alarm has done its job and does not repeat. jawn's
            // following sync broadcast arms the next one; re-reading here would
            // still see this alarm and re-arm it a second early.
            show(context, intent, true);
        }
    }

    private void show(Context context, Intent intent, boolean fromExactAlarm) {
        String label = intent.getStringExtra("label");
        String uuid = intent.getStringExtra("uuid");
        String app = intent.getStringExtra("app");
        long at = intent.getLongExtra("at", 0L);
        if (fromExactAlarm && (label == null || label.isEmpty() || at <= 0L)) {
            JSONObject data = JawnClockAlarm.read(context);
            if (data != null) {
                if (label == null || label.isEmpty()) label = data.optString("label", "");
                if (uuid == null || uuid.isEmpty()) uuid = data.optString("uuid", "");
                if (app == null || app.isEmpty()) app = data.optString("app", "");
                if (at <= 0L) at = data.optLong("at", 0L);
            }
        }
        Log.i(LOG_TAG, "clock alarm ringing: " + (label == null ? "" : label));
        JawnClockNotifier.show(context, label, uuid, app, at);
    }
}
