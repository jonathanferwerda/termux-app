package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import com.termux.R;

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
    public static final String UPCOMING_CHANNEL = "jawn_alarm_upcoming";
    private static final int NOTIFICATION_ID = 7301;
    private static final int CONTENT_REQUEST = 7302;
    private static final int DISMISS_REQUEST = 7307;
    private static final int SNOOZE_REQUEST = 7308;
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
        if (snoozeMinutes <= 0) snoozeMinutes = DEFAULT_SNOOZE_MINUTES;
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

        Intent open = new Intent(context, JawnClockAlarmActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("uuid", uuid == null ? "" : uuid);
        open.putExtra("label", title);
        open.putExtra("app", app == null ? "" : app);
        open.putExtra("at", at);
        open.putExtra("snooze", snoozeMinutes);
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

        Notification.Builder builder = new Notification.Builder(context, CHANNEL);
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
                .setColor(0xFFC94F4F)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_new_session),
                        context.getString(R.string.clock_alarm_dismiss), dismissPending).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_new_session),
                        context.getString(R.string.clock_alarm_snooze, snoozeMinutes),
                        snoozePending).build());
        // canUseFullScreenIntent() is API 34 and the app compiles against SDK 30;
        // ask for it reflectively so the same APK still builds and older phones
        // simply skip the check.
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Object allowed = nm.getClass().getMethod("canUseFullScreenIntent").invoke(nm);
                if (allowed instanceof Boolean && !((Boolean) allowed)) {
                    Log.w(LOG_TAG, "full-screen intent not allowed; the alarm remains a heads-up notification");
                }
            } catch (Exception ignored) {}
        }
        try {
            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Log.w(LOG_TAG, "could not post alarm notification", e);
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
        try {
            nm.notify(UPCOMING_ID, builder.build());
        } catch (Exception e) {
            Log.w(LOG_TAG, "could not post the alarm notice", e);
        }
    }

    public static void cancel(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
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
