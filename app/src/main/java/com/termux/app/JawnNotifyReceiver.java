package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.termux.R;

/**
 * Posts one of jawn's notifications from the app itself, so the badge lands on
 * the app's own launcher icon.
 *
 * jawn's notification_sender used to hand every notification to
 * termux-notification, which belongs to the JawnOS API app - an app with no
 * launcher icon, so nothing ever badged. It now broadcasts here instead
 * (com.termux.app.JAWN_NOTIFY: title, message, tag), and falls back to
 * termux-notification only when this app is not installed. The app's own
 * notifications (the service and friends) are made badge-less, so the icon
 * shows jawn's notifications and nothing else.
 */
public class JawnNotifyReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.termux.app.JAWN_NOTIFY";
    public static final String CHANNEL = "jawn_notifications";

    @Override
    public void onReceive(Context context, Intent intent) {
        String title = intent.getStringExtra("title");
        String message = intent.getStringExtra("message");
        String tag = intent.getStringExtra("tag");
        if (title == null || title.isEmpty()) title = "JawnOS";
        if (message == null) message = "";
        int id = Math.abs((tag != null && !tag.isEmpty() ? tag : String.valueOf(System.currentTimeMillis())).hashCode());

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // badges ON here: this is the channel the launcher icon should count
            NotificationChannel channel = new NotificationChannel(
                CHANNEL, "JawnOS", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(true);
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(context, JawnWebViewActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentIntent = PendingIntent.getActivity(context, id, open, flags);

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? new Notification.Builder(context, CHANNEL)
            : new Notification.Builder(context);
        builder.setContentTitle(title)
               .setContentText(message)
               .setSmallIcon(R.drawable.ic_new_session)
               .setAutoCancel(true)
               .setContentIntent(contentIntent);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_DEFAULT);
        }
        nm.notify(id, builder.build());
    }
}
