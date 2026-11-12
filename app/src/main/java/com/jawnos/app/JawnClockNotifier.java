package com.jawnos.app;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import com.jawnos.R;

import java.text.DateFormat;
import java.util.Date;

/**
 * The lock-screen face of an alarm, and the heads-up notice before it.
 *
 * The sound is played by {@link JawnClockSound} on the alarm stream. This class
 * draws the notification; the ring one is a high-importance alarm-channel
 * notification with a full-screen intent that opens
 * {@link JawnClockAlarmActivity}, and two actions - Dismiss and Snooze - that
 * end the ring without opening anything.
 */
public final class JawnClockNotifier {
    private static final String LOG_TAG = "JawnClockNotifier";
    public static final String CHANNEL = "jawn_alarms";
    /** The alarm channel used while the house is silent: no sound, no vibration. */
    public static final String SILENT_CHANNEL = "jawn_alarms_silent";
    public static final String UPCOMING_CHANNEL = "jawn_alarm_upcoming";
    private static final int NOTIFICATION_ID = 7301;
    /** A fresh id per ring: Android only honours a full-screen intent when the
     *  notification is newly posted, so re-using one id turned a second ring
     *  into a heads-up. The block is small; cancel() sweeps all of it. */
    private static final int RING_ID_BASE = 7400;
    private static final int RING_ID_SPAN = 50;
    private static final String RING_PREFS = "jawn_clock_alarm";
    private static final String RING_PREF_ID = "ring_id";
    private static int ringSeq = (int) ((System.currentTimeMillis() / 1000) % RING_ID_SPAN);

    /** The ring posted last, remembered across a process restart. */
    private static int lastRingId(Context context) {
        SharedPreferences p = context.getSharedPreferences(RING_PREFS, Context.MODE_PRIVATE);
        return p.getInt(RING_PREF_ID, 0);
    }

    private static void rememberRingId(Context context, int id) {
        SharedPreferences p = context.getSharedPreferences(RING_PREFS, Context.MODE_PRIVATE);
        p.edit().putInt(RING_PREF_ID, id).apply();
    }
    private static final int CONTENT_REQUEST = 7302;
    private static final int DISMISS_REQUEST = 7307;
    private static final int SNOOZE_REQUEST = 7308;
    private static final int FULLSCREEN_REQUEST = 7309;
    private static final int DEFAULT_SNOOZE_MINUTES = 5;
    private static final int UPCOMING_ID = 7304;
    private static final int UPCOMING_CONTENT_REQUEST = 7305;
    private static final int UPCOMING_SKIP_REQUEST = 7306;
    private static final long WAKE_MS = 15 * 60_000L;

    private static PowerManager.WakeLock wakeLock;

    private JawnClockNotifier() {}

    public static void show(Context context, String label, String uuid, String app, long at) {
        show(context, label, uuid, app, at, DEFAULT_SNOOZE_MINUTES);
    }

    public static void show(Context context, String label, String uuid, String app, long at,
                            int snoozeMinutes) {
        show(context, label, uuid, app, at, snoozeMinutes, "", 0, "snooze");
    }

    /**
     * @param play      "" for a normal alarm, "math" for one answered by a sum
     * @param nfc       1 when the assigned NFC tag is the way through
     * @param nfcAction "snooze" or "dismiss" for the tag's effect
     */
    public static void show(Context context, String label, String uuid, String app, long at,
                            int snoozeMinutes, String play, int nfc, String nfcAction) {
        if (snoozeMinutes <= 0) snoozeMinutes = DEFAULT_SNOOZE_MINUTES;
        if (play == null) play = "";
        if (nfcAction == null || nfcAction.isEmpty()) nfcAction = "snooze";
        String title = (label == null || label.isEmpty())
                ? context.getString(R.string.clock_alarm_default_label) : label;
        if (at <= 0L) at = System.currentTimeMillis();
        cancelUpcoming(context);

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jawnos:clock-alarm");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire(WAKE_MS);
            // API 26 has no Activity.setTurnScreenOn; a short full wake is how
            // that phone's screen comes on for the full-screen intent.
            if (Build.VERSION.SDK_INT < 27) {
                PowerManager.WakeLock screen = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE, "jawnos:clock-screen");
                screen.setReferenceCounted(false);
                screen.acquire(10_000L);
            }
        }

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        createChannel(nm);
        boolean quiet = JawnSilence.active(context);
        if (quiet) createSilentChannel(nm);

        Intent open = new Intent(context, JawnClockAlarmActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("uuid", uuid == null ? "" : uuid);
        open.putExtra("label", title);
        open.putExtra("app", app == null ? "" : app);
        open.putExtra("at", at);
        open.putExtra("snooze", snoozeMinutes);
        open.putExtra("play", play);
        open.putExtra("nfc", nfc);
        open.putExtra("nfc_action", nfcAction);
        PendingIntent content = PendingIntent.getActivity(context, CONTENT_REQUEST, open, flags());

        // The drawer's own controls: each ends the ring in the receiver and
        // leaves the shared-alarm edit to the Perl worker.
        Intent dismiss = new Intent(context, JawnClockReceiver.class)
                .setAction(JawnClockReceiver.ACTION_DISMISS)
                .putExtra("uuid", uuid == null ? "" : uuid)
                .putExtra("label", title)
                .putExtra("at", at)
                .putExtra("snooze", snoozeMinutes);
        PendingIntent dismissPending = PendingIntent.getBroadcast(
                context, DISMISS_REQUEST, dismiss, flags());

        Intent snooze = new Intent(context, JawnClockReceiver.class)
                .setAction(JawnClockReceiver.ACTION_SNOOZE)
                .putExtra("uuid", uuid == null ? "" : uuid)
                .putExtra("label", title)
                .putExtra("at", at)
                .putExtra("snooze", snoozeMinutes);
        PendingIntent snoozePending = PendingIntent.getBroadcast(
                context, SNOOZE_REQUEST, snooze, flags());

        Notification.Builder builder = new Notification.Builder(context, quiet ? SILENT_CHANNEL : CHANNEL);
        builder.setSmallIcon(R.drawable.ic_new_session)
                .setContentTitle(title)
                .setContentText(timeText(at) + (app == null || app.isEmpty() ? "" : " · " + app.replace('_', ' ')))
                .setWhen(at)
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_MAX)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(content)
                .setFullScreenIntent(content, true)
                .setColor(0xFFC94F4F);
        if (JawnSilence.active(context)) {
            builder.setVibrate(null);
        }
        // A sum or a tag is the way through this alarm; a one-tap Dismiss
        // would walk around it. The notification still opens the activity,
        // where the puzzle or the tag is enforced.
        if (nfc == 1 || "math".equals(play)) {
            Log.i(LOG_TAG, "ring gated by " + ("math".equals(play) ? "math" : "nfc")
                    + "; notification actions withheld");
        } else {
            builder.addAction(new Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_new_session),
                            context.getString(R.string.clock_alarm_dismiss), dismissPending).build())
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_new_session),
                            context.getString(R.string.clock_alarm_snooze, snoozeMinutes),
                            snoozePending).build());
        }
        boolean fullScreen = fullScreenAllowed(context);
        // A phone with notifications turned off still needs a way to answer.
        // The alarm face is asked for directly below in that case, just as it
        // is when the full-screen permission is missing.
        boolean canNotify = nm.areNotificationsEnabled();
        if (!canNotify) {
            Log.w(LOG_TAG, "notifications are disabled; the alarm face is the fallback");
        }
        if (!fullScreen) {
            // Android 14 made USE_FULL_SCREEN_INTENT a special access. The app
            // is an alarm app, but a sideloaded build is not classified by a
            // store, so the system can leave it denied and turn the alarm into
            // a heads-up notice. Offer the switch that grants it; the alarm
            // still opens the face directly below where Android permits it.
            Log.w(LOG_TAG, "full-screen intent not allowed; offering the lock-screen permission");
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_new_session),
                    context.getString(R.string.clock_alarm_fullscreen_permission),
                    fullScreenSettings(context)).build());
        }
        try {
            // A new id each time, so the full-screen intent fires again on a
            // second ring instead of silently updating a notification Android
            // already decided about. Cancel only the previous ring: a wide sweep
            // trips Android's cancel rate limit and the current one survives.
            int previous = lastRingId(context);
            if (previous > 0) nm.cancel(previous);
            int ringId = RING_ID_BASE + (++ringSeq % RING_ID_SPAN);
            rememberRingId(context, ringId);
            Log.i(LOG_TAG, "ring notification id=" + ringId + " for " + uuid);
            nm.notify(ringId, builder.build());
        } catch (Exception e) {
            Log.w(LOG_TAG, "could not post alarm notification", e);
        }
        // Ask for the face directly as well as posting the full-screen intent.
        // The intent is the sanctioned road, but a restricted or OEM build can
        // ignore it; the activity is singleTask, so when both arrive the second
        // one just brings the same face forward.
        startOverLockScreen(context, open);
    }

    /**
     * Android 14+ gates USE_FULL_SCREEN_INTENT behind a special access that is
     * granted by default only to apps a store classifies as alarm or calling
     * apps. A sideloaded build can be left denied, which is why an alarm that
     * used to open over the keyguard became a heads-up notice. Ask the system.
     */
    public static boolean fullScreenAllowed(Context context) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        try {
            Object allowed = nm.getClass().getMethod("canUseFullScreenIntent").invoke(nm);
            return allowed instanceof Boolean && ((Boolean) allowed);
        } catch (Exception e) {
            return false;
        }
    }

    private static PendingIntent fullScreenSettings(Context context) {
        Intent allow = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:" + context.getPackageName()));
        allow.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, FULLSCREEN_REQUEST, allow, flags());
    }

    /**
     * The permission-free road: with the phone locked or asleep, ask for the
     * alarm face directly, whether or not the full-screen intent was accepted.
     * Android may refuse a background activity start, in which case the
     * notification above (or the intent) is still the way in once unlocked.
     */
    private static void startOverLockScreen(Context context, Intent open) {
        try {
            KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean locked = km != null && km.isKeyguardLocked();
            boolean awake = pm != null && pm.isInteractive();
            if (locked || !awake) {
                context.startActivity(open);
                Log.i(LOG_TAG, "asked for the alarm face directly");
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "could not show the alarm face directly", e);
        }
    }

    /** Half an hour before: say when the alarm will sound, and offer the skip. */
    public static void showUpcoming(Context context, String label, String uuid, String app, long at) {
        if (at <= 0L) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        createUpcomingChannel(nm);

        String appt = (app == null || app.isEmpty()) ? "" : app.replace('_', ' ');
        String title = "Alarm";
        String text = "Alarm" + (appt.isEmpty() ? "" : " for " + appt) + " will sound at " + timeText(at);

        Intent open = new Intent(context, JawnWebViewActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(context, UPCOMING_CONTENT_REQUEST, open, flags());

        Intent skip = new Intent(context, JawnClockReceiver.class)
                .setAction(JawnClockReceiver.ACTION_SKIP)
                .putExtra("uuid", uuid == null ? "" : uuid)
                .putExtra("at", at)
                .putExtra("app", app == null ? "" : app)
                .putExtra("label", label == null ? "" : label);
        PendingIntent skipPending = PendingIntent.getBroadcast(context, UPCOMING_SKIP_REQUEST, skip, flags());

        Notification.Builder builder = new Notification.Builder(context, UPCOMING_CHANNEL);
        builder.setSmallIcon(R.drawable.ic_new_session)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setWhen(at)
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_new_session),
                        "Skip today", skipPending).build());
        if (JawnSilence.active(context)) {
            builder.setVibrate(null);
        }
        try {
            nm.notify(UPCOMING_ID, builder.build());
        } catch (Exception e) {
            Log.w(LOG_TAG, "could not post the alarm notice", e);
        }
    }

    public static void cancel(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        int previous = lastRingId(context);
        if (previous > 0) nm.cancel(previous);
        rememberRingId(context, 0);
        // the fixed ids older builds used, including the one that straddled the
        // upcoming notice (7304); this alarm owns both, so clearing them is right
        nm.cancel(NOTIFICATION_ID);
        nm.cancel(7303);
        cancelUpcoming(context);
    }

    public static void cancelUpcoming(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(UPCOMING_ID);
    }

    public static void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private static void createChannel(NotificationManager nm) {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("JawnOS alarms");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // The app plays the alarm's own sound on the alarm stream; a channel
        // sound here would talk over it.
        channel.setSound(null, null);
        channel.enableVibration(true);
        nm.createNotificationChannel(channel);
    }

    /** The same importance, with nothing that can make a sound or a buzz. */
    private static void createSilentChannel(NotificationManager nm) {
        NotificationChannel channel = new NotificationChannel(
                SILENT_CHANNEL, "Alarms (silent)", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("JawnOS alarms while the house is silent");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setSound(null, null);
        channel.enableVibration(false);
        nm.createNotificationChannel(channel);
    }

    private static void createUpcomingChannel(NotificationManager nm) {
        NotificationChannel channel = new NotificationChannel(
                UPCOMING_CHANNEL, "Alarm reminders", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Half an hour before a JawnOS alarm");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        nm.createNotificationChannel(channel);
    }

    private static int flags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }

    private static String timeText(long at) {
        try {
            return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at));
        } catch (Exception e) {
            return "";
        }
    }
}
