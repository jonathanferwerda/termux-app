package com.termux.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * The phone's exact wake for a jawn Clock alarm.
 *
 * jawn writes the next alarm to {@code ~/.president/clock_alarm.json} and
 * broadcasts {@link com.termux.app.JawnClockReceiver#ACTION_SYNC}. This class
 * reads that file (or the broadcast's own extras) and sets an exact,
 * allow-while-idle AlarmManager alarm five seconds before the clock time. That
 * wake gives the Perl alarm worker a CPU to fire on, and the receiver posts the
 * lock-screen notification and opens the app. Android may have the phone in
 * Doze, so this is separate from the navigation-interval wake: an alarm is a
 * promise to wake at a named moment, not at the next convenient one.
 */
public final class JawnClockAlarm {
    private static final String LOG_TAG = "JawnClockAlarm";
    public static final String ACTION_ALARM = "com.termux.app.CLOCK_ALARM";
    private static final int REQUEST_CODE = 7201;
    /** Wake this early so the worker has the CPU when the minute turns. */
    private static final long LEAD_MS = 5_000L;

    private JawnClockAlarm() {}

    private static File alarmFile() {
        return new File(new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".president"),
                "clock_alarm.json");
    }

    /** The next alarm the Perl side wrote, or null when there is none. */
    public static JSONObject read(Context context) {
        File file = alarmFile();
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), 16384)];
            int read = in.read(bytes);
            if (read <= 0) return null;
            return new JSONObject(new String(bytes, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(LOG_TAG, "could not read " + file.getAbsolutePath(), e);
            return null;
        }
    }

    public static void sync(Context context) {
        sync(context, null);
    }

    /**
     * Re-read the schedule. A broadcast carries the same fields for the moment
     * jawn has just written them; the file remains the source that survives a
     * process restart or a reboot.
     */
    public static void sync(Context context, Intent intent) {
        long at = 0L;
        String label = "";
        String uuid = "";
        String app = "";
        JSONObject data = read(context);
        if (data != null) {
            at = data.optLong("at", 0L);
            label = data.optString("label", "");
            uuid = data.optString("uuid", "");
            app = data.optString("app", "");
        }
        if (intent != null && intent.getLongExtra("at", 0L) > 0L) {
            at = intent.getLongExtra("at", 0L);
            label = intent.getStringExtra("label") != null ? intent.getStringExtra("label") : label;
            uuid = intent.getStringExtra("uuid") != null ? intent.getStringExtra("uuid") : uuid;
            app = intent.getStringExtra("app") != null ? intent.getStringExtra("app") : app;
        }
        schedule(context, at, label, uuid, app);
    }

    public static void schedule(Context context, long at, String label, String uuid, String app) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pending = pendingIntent(context, at, label, uuid, app);
        long now = System.currentTimeMillis();
        if (at <= 0L || at < now - 60_000L) {
            am.cancel(pending);
            pending.cancel();
            Log.i(LOG_TAG, "no next clock alarm");
            return;
        }

        long wakeAt = Math.max(now + 1_000L, at - LEAD_MS);
        try {
            // The app targets SDK 28, so Android 12's exact-alarm permission gate
            // does not apply; setExactAndAllowWhileIdle is the strongest wake this
            // compile target can ask for.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAt, pending);
                Log.i(LOG_TAG, "clock alarm at " + wakeAt + " (" + label + ")");
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, wakeAt, pending);
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "could not schedule clock alarm", e);
        }
    }

    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pending = pendingIntent(context, 0L, "", "", "");
        am.cancel(pending);
        pending.cancel();
    }

    private static PendingIntent pendingIntent(Context context, long at, String label, String uuid, String app) {
        Intent intent = new Intent(context, JawnClockReceiver.class)
                .setAction(ACTION_ALARM)
                .putExtra("at", at)
                .putExtra("label", label)
                .putExtra("uuid", uuid)
                .putExtra("app", app);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
