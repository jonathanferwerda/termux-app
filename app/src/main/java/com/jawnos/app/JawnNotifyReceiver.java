package com.jawnos.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Posts one of jawn's notifications from the app itself, so the badge lands on
 * the app's own launcher icon.
 *
 * jawn's notification_sender used to hand every notification to
 * termux-notification, which belongs to the JawnOS API app - an app with no
 * launcher icon, so nothing ever badged. It now broadcasts here instead
 * (com.jawnos.app.JAWN_NOTIFY: title, message, tag), and falls back to
 * termux-notification only when this app is not installed.
 *
 * The app's own notifications (the service and friends) are made badge-less, so
 * the icon shows jawn's notifications and nothing else. Each notice's app names
 * its channel, group and small icon (JawnNotify); a notification marked silent
 * (a Clock alarm or timer, whose sound the app plays on the alarm stream) uses
 * that app's quiet channel.
 *
 * This is the fallback for a build older than the device bridge. The bridge
 * (DeviceService.notificationPost) posts the same notice with the same names.
 */
public class JawnNotifyReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.jawnos.app.JAWN_NOTIFY";
    public static final String ACTION_PRUNE = "com.jawnos.app.JAWN_NOTIFY_PRUNE";
    public static final String CHANNEL = JawnNotify.DEFAULT_CHANNEL;
    public static final String SILENT_CHANNEL = JawnNotify.SILENT_CHANNEL;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_PRUNE.equals(intent.getAction())) {
            JawnNotify.pruneSummaries(context, intent.getStringExtra("group"), intent.getStringExtra("tag"));
            return;
        }
        String title = intent.getStringExtra("title");
        String message = intent.getStringExtra("message");
        String tag = intent.getStringExtra("tag");
        // Silence outranks the caller's own silent flag: every notice is quiet.
        boolean silenced = JawnSilence.active(context);
        boolean silent = silenced || intent.getBooleanExtra("silent", false);
        if (title == null || title.isEmpty()) title = "JawnOS";
        if (message == null) message = "";
        String app = intent.getStringExtra("app");
        app = app == null ? "" : app.trim();
        String appKey = JawnNotify.appKey(app);
        String appLabel = JawnNotify.label(app, intent.getStringExtra("app_name"));
        String iconName = intent.getStringExtra("small_icon");
        if (iconName == null || iconName.isEmpty()) iconName = app;
        int icon = JawnNotify.smallIcon(context, iconName);
        String channelId = JawnNotify.channel(context, appKey, appLabel, silent);
        String group = JawnNotify.groupFor(appKey);
        String notifyTag = tag != null && !tag.isEmpty() ? tag : String.valueOf(System.currentTimeMillis());
        int id = notifyTag.hashCode() & 0x7fffffff;

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // A notice that names an app opens that app on its own, full screen,
        // the way the Home row does. The older fallback carries no app, so it
        // keeps the WebView (and its url when the sender gave one).
        String timestamp = intent.getStringExtra("timestamp");
        String url = intent.getStringExtra("url");
        timestamp = timestamp == null ? "" : timestamp.trim();
        url = url == null ? "" : url;
        Intent open;
        if (!app.isEmpty()) {
            open = new Intent(context, JawnAppActivity.class);
            open.putExtra(JawnAppActivity.EXTRA_APP, app);
            if (!timestamp.isEmpty()) open.putExtra(JawnAppActivity.EXTRA_TIMESTAMP, timestamp);
        }
        else {
            open = new Intent(context, JawnWebViewActivity.class);
            if (!url.isEmpty()) open.putExtra(JawnWebViewActivity.EXTRA_URL, url);
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
            | (android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentIntent = PendingIntent.getActivity(context, id, open, flags);

        Notification.Builder builder = new Notification.Builder(context, channelId);
        builder.setContentTitle(title)
               .setContentText(message)
               .setSmallIcon(icon)
               .setAutoCancel(true)
               .setContentIntent(contentIntent);
        if (!group.isEmpty()) {
            builder.setGroup(group);
            builder.setDeleteIntent(JawnNotify.pruneIntent(context, group, notifyTag));
        }
        if (silent) {
            builder.setOnlyAlertOnce(true);
        }
        nm.notify(notifyTag, id, builder.build());
        if (!group.isEmpty()) {
            JawnNotify.refreshSummary(context, app, appLabel, channelId, group, icon, 0, notifyTag);
        }
    }
}
