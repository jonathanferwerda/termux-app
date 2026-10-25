package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import com.termux.R;

import java.text.DateFormat;
import java.util.Date;

/**
 * The lock-screen face of an alarm.
 *
 * The sound belongs to the Perl side (it plays the chosen chime or long track
 * through the operating system). This class is what wakes the screen and puts
 * the alarm in front of the person while the phone is locked: a high-importance
 * alarm-channel notification with a full-screen intent that opens the manager,
 * where the Clock ring card waits. The wake lock is held across the ring so a
 * long track is not cut off by the phone going back to sleep.
 */
public final class JawnClockNotifier {
    private static final String LOG_TAG = "JawnClockNotifier";
    public static final String CHANNEL = "jawn_alarms";
    private static final int NOTIFICATION_ID = 7301;
    private static final int CONTENT_REQUEST = 7302;
    private static final long WAKE_MS = 15 * 60_000L;

    private static PowerManager.WakeLock wakeLock;

    private JawnClockNotifier() {}

    public static void show(Context context, String label, String uuid, String app, long at) {
        String title = (label == null || label.isEmpty()) ? "Alarm" : label;
        if (at <= 0L) at = System.currentTimeMillis();

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

        Intent open = new Intent(context, JawnWebViewActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra(JawnWebViewActivity.EXTRA_SHOW_WHEN_LOCKED, true);
        open.putExtra("clock_alarm_uuid", uuid == null ? "" : uuid);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(context, CONTENT_REQUEST, open, flags);

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setDefaults(Notification.DEFAULT_VIBRATE);
        }
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

    public static void cancel(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }

    public static void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private static void createChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("JawnOS alarms");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // The Perl side plays the alarm's own sound; a channel sound here would
        // talk over a long track.
        channel.setSound(null, null);
        channel.enableVibration(true);
        nm.createNotificationChannel(channel);
    }

    private static String timeText(long at) {
        try {
            return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at));
        } catch (Exception e) {
            return "";
        }
    }
}
