package com.jawnos.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.jawnos.R;

/**
 * Posts one of jawn's notifications from the app itself, so the badge lands on
 * the app's own launcher icon.
 *
 * jawn's notification_sender used to hand every notification to
 * termux-notification, which belongs to the JawnOS API app - an app with no
 * launcher icon, so nothing ever badged. It now broadcasts here instead
 * (com.jawnos.app.JAWN_NOTIFY: title, message, tag), and falls back to
 * termux-notification only when this app is not installed. The app's own
 * notifications (the service and friends) are made badge-less, so the icon
 * shows jawn's notifications and nothing else.
 *
 * A notification marked silent (a Clock alarm or timer, whose sound the app
 * plays on the alarm stream) goes to a quiet channel so the notification
 * system does not add its own chime over it.
 */
public class JawnNotifyReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.jawnos.app.JAWN_NOTIFY";
    public static final String CHANNEL = "jawn_notifications";
    public static final String SILENT_CHANNEL = "jawn_notifications_silent";

    @Override
    public void onReceive(Context context, Intent intent) {
        String title = intent.getStringExtra("title");
        String message = intent.getStringExtra("message");
        String tag = intent.getStringExtra("tag");
        boolean silent = intent.getBooleanExtra("silent", false);
        if (title == null || title.isEmpty()) title = "JawnOS";
        if (message == null) message = "";
        int id = Math.abs((tag != null && !tag.isEmpty() ? tag : String.valueOf(System.currentTimeMillis())).hashCode());

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        String channelId = silent ? SILENT_CHANNEL : CHANNEL;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (silent) {
                NotificationChannel channel = new NotificationChannel(
                    SILENT_CHANNEL, "JawnOS (quiet)", NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(true);
                channel.setSound(null, null);
                channel.enableVibration(false);
                nm.createNotificationChannel(channel);
            }
            else {
                // badges ON here: this is the channel the launcher icon should count
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "JawnOS", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setShowBadge(true);
                nm.createNotificationChannel(channel);
            }
        }

        Intent open = new Intent(context, JawnWebViewActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentIntent = PendingIntent.getActivity(context, id, open, flags);

        Notification.Builder builder = new Notification.Builder(context, channelId);
        builder.setContentTitle(title)
               .setContentText(message)
               .setSmallIcon(R.drawable.ic_new_session)
               .setAutoCancel(true)
               .setContentIntent(contentIntent);
        if (silent) {
            builder.setOnlyAlertOnce(true);
        }
        nm.notify(id, builder.build());
    }
}
