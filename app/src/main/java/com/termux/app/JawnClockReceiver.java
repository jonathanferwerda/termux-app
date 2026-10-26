package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The bridge between jawn's Clock and Android's alarm machinery.
 *
 * jawn broadcasts these messages from the Perl side:
 *
 *   com.termux.app.JAWN_CLOCK_SYNC      the next alarm changed (re-arm)
 *   com.termux.app.JAWN_CLOCK_RING      an alarm is ringing now (show and sound it)
 *   com.termux.app.JAWN_CLOCK_DISMISSED Dismiss/Snooze (take it down)
 *   com.termux.app.JAWN_CLOCK_TIMER     a timer finished (sound the chime)
 *
 * and AlarmManager delivers CLOCK_ALARM five seconds before the named time and
 * CLOCK_UPCOMING half an hour before it. The pre-notice carries a Skip today
 * action; the skip ends the phone's own wake at once and leaves a request the
 * Perl side reads and applies to the shared alarm. Dismiss and Snooze (from
 * the notification actions or the full-screen alarm) write the same kind of
 * request to clock_control.json for the Perl alarm worker to poll.
 */
public class JawnClockReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "JawnClockReceiver";
    public static final String ACTION_SYNC = "com.termux.app.JAWN_CLOCK_SYNC";
    public static final String ACTION_RING = "com.termux.app.JAWN_CLOCK_RING";
    public static final String ACTION_DISMISSED = "com.termux.app.JAWN_CLOCK_DISMISSED";
    public static final String ACTION_TIMER = "com.termux.app.JAWN_CLOCK_TIMER";
    public static final String ACTION_PREVIEW = "com.termux.app.JAWN_CLOCK_PREVIEW";
    public static final String ACTION_SKIP = "com.termux.app.JAWN_CLOCK_SKIP";
    public static final String ACTION_DISMISS = "com.termux.app.JAWN_CLOCK_DISMISS";
    public static final String ACTION_SNOOZE = "com.termux.app.JAWN_CLOCK_SNOOZE";
    public static final String ACTION_UPCOMING = JawnClockAlarm.ACTION_UPCOMING;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();

        if (ACTION_SYNC.equals(action)) {
            JawnClockAlarm.sync(context, intent);
            JawnClockNotifier.cancelUpcoming(context);
            return;
        }

        if (ACTION_DISMISSED.equals(action)) {
            JawnClockSound.stop();
            JawnClockNotifier.cancel(context);
            JawnClockNotifier.cancelUpcoming(context);
            JawnClockNotifier.releaseWakeLock();
            JawnClockAlarm.sync(context);
            return;
        }

        if (ACTION_DISMISS.equals(action) || ACTION_SNOOZE.equals(action)) {
            // The notification's actions and the lock-screen gestures both end
            // the ring here, then leave the shared-alarm edit to the Perl side.
            boolean snooze = ACTION_SNOOZE.equals(action);
            int minutes = intent.getIntExtra("snooze", 5);
            if (minutes <= 0) minutes = 5;
            JawnClockSound.stop();
            JawnClockNotifier.cancel(context);
            JawnClockNotifier.releaseWakeLock();
            writeControl(context, intent.getStringExtra("uuid"),
                    snooze ? "snooze" : "dismiss", minutes);
            // End this wake now. The Perl worker writes the next schedule (or the
            // snooze) and broadcasts ACTION_SYNC, which arms it again. Calling
            // sync() here would re-read the old moment and ring again at once.
            JawnClockAlarm.cancel(context);
            return;
        }

        if (ACTION_SKIP.equals(action)) {
            skipToday(context, intent);
            return;
        }

        if (ACTION_UPCOMING.equals(action)) {
            JawnClockNotifier.showUpcoming(context,
                    intent.getStringExtra("label"),
                    intent.getStringExtra("uuid"),
                    intent.getStringExtra("app"),
                    intent.getLongExtra("at", 0L));
            return;
        }

        if (ACTION_PREVIEW.equals(action)) {
            if (intent.getIntExtra("stop", 0) == 1) JawnClockSound.stop();
            else playSound(context, intent, false, false);
            return;
        }

        if (ACTION_TIMER.equals(action)) {
            playSound(context, intent, false, false);
            return;
        }

        if (ACTION_RING.equals(action)) {
            show(context, intent, false);
            playSound(context, intent, false, true);
            return;
        }

        if (JawnClockAlarm.ACTION_ALARM.equals(action)) {
            // The exact alarm has done its job and does not repeat. The ring
            // starts at the named moment, not at this five-second lead.
            show(context, intent, true);
            playSound(context, intent, true, true);
        }
    }

    private void show(Context context, Intent intent, boolean fromExactAlarm) {
        String label = intent.getStringExtra("label");
        String uuid = intent.getStringExtra("uuid");
        String app = intent.getStringExtra("app");
        long at = intent.getLongExtra("at", 0L);
        int snooze = intent.getIntExtra("snooze", 0);
        if (fromExactAlarm && (label == null || label.isEmpty() || at <= 0L)) {
            JSONObject data = JawnClockAlarm.read(context);
            if (data != null) {
                if (label == null || label.isEmpty()) label = data.optString("label", "");
                if (uuid == null || uuid.isEmpty()) uuid = data.optString("uuid", "");
                if (app == null || app.isEmpty()) app = data.optString("app", "");
                if (at <= 0L) at = data.optLong("at", 0L);
                if (snooze <= 0) snooze = data.optInt("snooze", 0);
            }
        }
        if (snooze <= 0) snooze = 5;
        Log.i(LOG_TAG, "clock alarm ringing: " + (label == null ? "" : label));
        JawnClockNotifier.show(context, label, uuid, app, at, snooze);
    }

    /** Sound an alarm or a timer on the alarm stream, at the alarm's own level. */
    private void playSound(Context context, Intent intent, boolean delayToAt, boolean alarmRing) {
        String file = intent.getStringExtra("file");
        int volume = intent.getIntExtra("volume", 70);
        int ramp = intent.getIntExtra("ramp", 0);
        int guard = intent.getIntExtra("guard", 300_000);
        // An alarm ring repeats until it is answered even when an older jawn
        // sent no loop extra; a timer or preview keeps its one-shot behaviour.
        int loopExtra = intent.getIntExtra("loop", -1);
        boolean loop = loopExtra < 0 ? alarmRing : loopExtra == 1;
        long at = intent.getLongExtra("at", 0L);
        if (file == null || file.isEmpty()) {
            JSONObject data = JawnClockAlarm.read(context);
            if (data != null) {
                file = data.optString("file", "");
                volume = data.optInt("volume", volume);
                ramp = data.optInt("ramp", ramp);
                guard = data.optInt("guard", guard);
                loop = data.optInt("loop", loop ? 1 : 0) == 1;
                if (at <= 0L) at = data.optLong("at", 0L);
            }
        }
        if (file == null || file.isEmpty()) {
            Log.w(LOG_TAG, "ring without a sound file");
            return;
        }
        final Context app = context.getApplicationContext();
        final String path = file;
        final int v = volume, r = ramp, g = guard;
        final boolean l = loop;
        long wait = (delayToAt && at > System.currentTimeMillis()) ? at - System.currentTimeMillis() : 0L;
        if (wait > 100L) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    JawnClockSound.play(app, path, v, r, l, g);
                }
            }, wait);
        }
        else {
            JawnClockSound.play(app, path, v, r, l, g);
        }
    }

    /**
     * Leave the Perl alarm worker a request it polls and applies to the shared
     * alarm. Nothing here talks HTTP; the phone's own state is already ended by
     * the caller, and this file is the edit that has to reach every other device.
     */
    static void writeControl(Context context, String uuid, String action, int minutes) {
        if (uuid == null || uuid.isEmpty()) return;
        try {
            File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".president");
            if (!dir.exists()) dir.mkdirs();
            JSONObject json = new JSONObject();
            json.put("action", action);
            json.put("uuid", uuid);
            json.put("minutes", minutes);
            try (FileOutputStream out = new FileOutputStream(new File(dir, "clock_control.json"))) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            Log.i(LOG_TAG, action + " written for " + uuid);
        } catch (Exception e) {
            Log.w(LOG_TAG, "could not write the clock control request", e);
        }
    }

    /** Skip the next occurrence for today: end the phone's wake and ask jawn. */
    private void skipToday(Context context, Intent intent) {
        String uuid = intent.getStringExtra("uuid");
        long at = intent.getLongExtra("at", 0L);
        JawnClockSound.stop();
        JawnClockNotifier.cancel(context);
        JawnClockNotifier.cancelUpcoming(context);
        JawnClockNotifier.releaseWakeLock();
        JawnClockAlarm.cancel(context);
        if (uuid == null || uuid.isEmpty()) return;
        try {
            File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".president");
            if (!dir.exists()) dir.mkdirs();
            JSONObject json = new JSONObject();
            json.put("uuid", uuid);
            if (at > 0L) json.put("at", at);
            try (FileOutputStream out = new FileOutputStream(new File(dir, "clock_skip.json"))) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            Log.i(LOG_TAG, "skip today written for " + uuid);
        } catch (Exception e) {
            Log.w(LOG_TAG, "could not write the skip request", e);
        }
    }
}
