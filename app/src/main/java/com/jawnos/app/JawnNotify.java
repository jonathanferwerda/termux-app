package com.jawnos.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.jawnos.R;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One app, one channel, one group, one small icon.
 *
 * Every notice used to land on a single "JawnOS" channel with the house mark,
 * so a Mail notice sat in the same bundle as a Clock alarm and could not be
 * told apart at a glance. Here each notice's app becomes:
 *
 *  - a channel named for the app ("Mail"), so its sound, importance and badge
 *    are the person's own choice in Settings;
 *  - a group key ("jawn:mailbox"), so mail never shares the automatic bundle
 *    Android builds for the app's other notices;
 *  - a small icon the app's name picks (an envelope for mailbox, a clock for
 *    clock, ...), with ic_new_session as the house fallback;
 *  - a group summary titled with the app name once two notices are waiting, so
 *    the collapsed row reads "Mail" rather than the package name. A single
 *    notice is left without a summary, so it stands on its own.
 *
 * Android still attributes the notification to the posting package, so the
 * header and the launcher badge say JawnOS; only a second APK could change
 * that.
 */
final class JawnNotify {
    static final String DEFAULT_CHANNEL = "jawn_notifications";
    static final String SILENT_CHANNEL = "jawn_notifications_silent";
    static final String GROUP_PREFIX = "jawn:";
    private static final String SUMMARY_PREFIX = "jawn-summary:";
    private static final int SUMMARY_BASE_ID = 0x6a770001;

    // The notices this process has posted, by group. getActiveNotifications()
    // returns nothing on some phones (the SM-X400 reported zero while three
    // notices were live), so the summary count cannot depend on it alone.
    private static final Map<String, Set<String>> CHILDREN = new HashMap<>();

    private JawnNotify() { }

    /** A stable, channel-safe key for the sender's app name. */
    static String appKey(String app) {
        if (app == null) return "";
        String key = app.trim().toLowerCase(Locale.US);
        key = key.replaceAll("[^a-z0-9]+", "_");
        key = key.replaceAll("^_+|_+$", "");
        if (key.length() > 40) key = key.substring(0, 40);
        return key;
    }

    /** The name the person sees: Mail, Clock, Market, or the app's own words. */
    static String label(String app, String override) {
        if (override != null && !override.trim().isEmpty()) return override.trim();
        String key = appKey(app);
        switch (key) {
            case "mailbox": case "mail": case "email": case "inbox": return "Mail";
            case "box_office": return "Box Office";
            case "market": case "store": return "Market";
            case "scan": return "Scan";
            case "security": return "Security";
            case "customs": return "Customs";
            case "clock": return "Clock";
            case "me": case "": return "JawnOS";
            default: break;
        }
        String name = app == null ? "" : app.trim().replace('_', ' ').replace('-', ' ');
        if (name.isEmpty()) return "JawnOS";
        StringBuilder out = new StringBuilder();
        for (String word : name.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) out.append(word.substring(1).toLowerCase(Locale.US));
        }
        return out.length() == 0 ? "JawnOS" : out.toString();
    }

    /**
     * A small icon is a resource: a drawable name the app ships, then a known
     * jawn app, then the house mark. The drawables are monochrome vectors
     * because Android draws the status-bar icon as a silhouette.
     */
    static int smallIcon(Context context, String name) {
        if (name != null && !name.isEmpty()) {
            int res = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
            if (res != 0) return res;
        }
        String key = appKey(name);
        if (key.contains("mail") || key.equals("inbox")) return R.drawable.ic_notify_mail;
        if (key.contains("message") || key.contains("chat") || key.contains("sms") || key.equals("text")) return R.drawable.ic_notify_chat;
        if (key.contains("clock") || key.contains("alarm") || key.contains("timer")) return R.drawable.ic_notify_clock;
        if (key.contains("security") || key.contains("customs") || key.contains("lock")) return R.drawable.ic_notify_lock;
        if (key.contains("box_office") || key.contains("ticket") || key.equals("pass")) return R.drawable.ic_notify_ticket;
        if (key.contains("appoint") || key.contains("calendar") || key.contains("schedule")) return R.drawable.ic_notify_calendar;
        if (key.contains("market") || key.contains("store") || key.contains("shop")
            || key.contains("cart") || key.contains("receipt") || key.contains("payment")
            || key.contains("budget") || key.contains("wallet")) return R.drawable.ic_notify_market;
        if (key.contains("scan") || key.contains("document") || key.contains("paper")) return R.drawable.ic_notify_scan;
        if (key.contains("music") || key.contains("media") || key.contains("song") || key.contains("audio")) return R.drawable.ic_notify_music;
        if (key.contains("print")) return R.drawable.ic_notify_print;
        if (key.contains("phone") || key.contains("call")) return R.drawable.ic_notify_phone;
        if (key.contains("warning") || key.contains("alert") || key.contains("error") || key.contains("fail")) return R.drawable.ic_notify_warning;
        if (key.contains("camera") || key.contains("photo") || key.contains("picture")) return R.drawable.ic_notify_camera;
        if (key.contains("location") || key.contains("gps")) return R.drawable.ic_notify_location;
        if (key.contains("download")) return R.drawable.ic_notify_download;
        if (key.contains("sync") || key.contains("refresh")) return R.drawable.ic_notify_sync;
        if (key.contains("battery") || key.contains("charge")) return R.drawable.ic_notify_battery;
        if (key.contains("check") || key.contains("done") || key.contains("complete")) return R.drawable.ic_notify_check;
        if (key.contains("info") || key.contains("notice")) return R.drawable.ic_notify_info;
        return R.drawable.ic_new_session;
    }

    /** The channel this app's notices use, created the first time it is seen. */
    static String channel(Context context, String appKey, String label, boolean silent) {
        String id = channelId(appKey, silent);
        if (Build.VERSION.SDK_INT < 26) return id;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return id;
        NotificationChannel channel = new NotificationChannel(
            id, label + (silent ? " (quiet)" : ""),
            silent ? NotificationManager.IMPORTANCE_LOW : NotificationManager.IMPORTANCE_DEFAULT);
        channel.setShowBadge(true);
        if (silent) {
            channel.setSound(null, null);
            channel.enableVibration(false);
        }
        manager.createNotificationChannel(channel);
        return id;
    }

    static String channelId(String appKey, boolean silent) {
        String base = silent ? SILENT_CHANNEL : DEFAULT_CHANNEL;
        if (appKey == null || appKey.isEmpty()) return base;
        return base + "__" + appKey;
    }

    static String groupFor(String appKey) {
        if (appKey == null || appKey.isEmpty()) return "";
        return GROUP_PREFIX + appKey;
    }

    /**
     * Post (or refresh) the summary that names this app while two or more of
     * its notices are waiting. With one notice the summary is removed, so the
     * notice shows individually; with none, nothing is left behind.
     */
    static void refreshSummary(Context context, String app, String label, String channelId,
                               String group, int icon, int color, String tag) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || group == null || group.isEmpty()) return;
        String summaryTag = summaryTag(group);
        int id = summaryId(group);
        int remembered = remember(group, tag);
        int active = childrenIn(manager, group, tag) + 1;
        // The listener sees every live child; the map only fills in when the
        // person has not granted notification access.
        int count = JawnNotificationListener.active() != null
            ? active : Math.max(remembered, active);
        Log.d("JawnNotify", "summary " + group + " map=" + remembered + " active=" + active);
        if (count < 2) {
            manager.cancel(summaryTag, id);
            return;
        }
        Intent open = new Intent(context, JawnAppActivity.class);
        open.putExtra(JawnAppActivity.EXTRA_APP, app == null ? "" : app);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(context, id, open, flags());
        Notification.Builder builder = new Notification.Builder(context, channelId);
        builder.setContentTitle(label)
               .setContentText("New notifications")
               .setSmallIcon(icon)
               .setGroup(group)
               .setGroupSummary(true)
               .setOnlyAlertOnce(true)
               .setAutoCancel(false)
               .setShowWhen(false)
               .setContentIntent(content);
        if (Build.VERSION.SDK_INT >= 26) {
            // Children do the alerting; the summary is only a header.
            builder.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN);
        }
        if (color != 0) builder.setColor(color);
        manager.notify(summaryTag, id, builder.build());
    }

    /**
     * Drop summaries whose group no longer has a notice. A dismissed notice is
     * excluded by tag because Android may still report it while the delete
     * broadcast is being delivered.
     */
    static void pruneSummaries(Context context, String group, String dismissedTag) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        Set<String> touched = new HashSet<>();
        if (dismissedTag != null) {
            if (group != null && !group.isEmpty()) {
                forget(group, dismissedTag);
                touched.add(group);
            }
            else {
                touched.addAll(forgetEverywhere(dismissedTag));
            }
            // The listener sees the whole shade; the manager's own query
            // answers only the newest notice on some phones.
            boolean listener = JawnNotificationListener.active() != null;
            for (String g : touched) {
                int left = listener ? childrenIn(manager, g, dismissedTag) : groupSize(g);
                Log.d("JawnNotify", "prune " + dismissedTag + " " + g + " left=" + left);
                if (left < 2) manager.cancel(summaryTag(g), summaryId(g));
            }
            if (!touched.isEmpty()) return;
        }
        // A stale tag (the process restarted) falls through to the scan.
        StatusBarNotification[] active = active(manager);
        if (active == null) return;
        Map<String, StatusBarNotification> summaries = new HashMap<>();
        for (StatusBarNotification sbn : active) {
            if (sbn == null) continue;
            String tag = sbn.getTag();
            String key = sbn.getGroupKey();
            if (tag != null && tag.startsWith(SUMMARY_PREFIX)) {
                if (key != null) summaries.put(key, sbn);
                continue;
            }
            if (dismissedTag != null && dismissedTag.equals(tag)) continue;
        }
        for (Map.Entry<String, StatusBarNotification> entry : summaries.entrySet()) {
            if (group != null && !group.equals(entry.getKey())) continue;
            int left = countIn(active, entry.getKey(), null);
            if (left >= 2) continue;
            StatusBarNotification summary = entry.getValue();
            manager.cancel(summary.getTag(), summary.getId());
        }
    }

    /** Add this notice to its group and return how many are remembered. */
    private static int remember(String group, String tag) {
        synchronized (CHILDREN) {
            Set<String> tags = CHILDREN.get(group);
            if (tags == null) {
                tags = new HashSet<>();
                CHILDREN.put(group, tags);
            }
            if (tag != null) tags.add(tag);
            return tags.size();
        }
    }

    private static void forget(String group, String tag) {
        synchronized (CHILDREN) {
            Set<String> tags = CHILDREN.get(group);
            if (tags == null) return;
            if (tag != null) tags.remove(tag);
            if (tags.isEmpty()) CHILDREN.remove(group);
        }
    }

    private static Set<String> forgetEverywhere(String tag) {
        Set<String> touched = new HashSet<>();
        synchronized (CHILDREN) {
            Iterator<Map.Entry<String, Set<String>>> it = CHILDREN.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Set<String>> entry = it.next();
                Set<String> tags = entry.getValue();
                if (tag != null && tags.remove(tag)) touched.add(entry.getKey());
                if (tags.isEmpty()) it.remove();
            }
        }
        return touched;
    }

    private static String summaryTag(String group) {
        return SUMMARY_PREFIX + group;
    }

    private static int summaryId(String group) {
        return SUMMARY_BASE_ID + (group.hashCode() & 0x0fffffff);
    }

    private static int groupSize(String group) {
        synchronized (CHILDREN) {
            Set<String> tags = CHILDREN.get(group);
            return tags == null ? 0 : tags.size();
        }
    }

    /** The broadcast a dismissed notice sends so a stale summary can go too. */
    static PendingIntent pruneIntent(Context context, String group, String tag) {
        Intent prune = new Intent(context, JawnNotifyReceiver.class)
            .setAction(JawnNotifyReceiver.ACTION_PRUNE)
            .putExtra("group", group)
            .putExtra("tag", tag);
        int code = (group + "|" + tag).hashCode() & 0x7fffffff;
        return PendingIntent.getBroadcast(context, code, prune, flags());
    }

    private static int childrenIn(NotificationManager manager, String group, String excludeTag) {
        return countIn(active(manager), group, excludeTag);
    }

    private static int countIn(StatusBarNotification[] active, String group, String excludeTag) {
        if (active == null) return 0;
        int count = 0;
        for (StatusBarNotification sbn : active) {
            if (sbn == null) continue;
            if (sbn.getNotification() != null
                && (sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            if (excludeTag != null && excludeTag.equals(sbn.getTag())) continue;
            if (inGroup(sbn, group)) count++;
        }
        return count;
    }

    /**
     * A notice belongs to the group when its channel is that app's channel.
     * The listener may report an autogroup override key instead of the key the
     * app set, so the channel is the stable handle; the key is the fallback.
     */
    private static boolean inGroup(StatusBarNotification sbn, String group) {
        String appKey = group.startsWith(GROUP_PREFIX)
            ? group.substring(GROUP_PREFIX.length()) : "";
        if (!appKey.isEmpty() && sbn.getNotification() != null) {
            String channel = sbn.getNotification().getChannelId();
            if (channel != null
                && (channel.startsWith(DEFAULT_CHANNEL) || channel.startsWith(SILENT_CHANNEL))
                && channel.endsWith("__" + appKey)) return true;
        }
        return sameGroup(group, sbn.getGroupKey());
    }

    /**
     * The system may report an autogroup override key ("0|pkg|g:jawn:mailbox")
     * instead of the key the app set, so match the suffix as well.
     */
    private static boolean sameGroup(String group, String key) {
        if (key == null) return false;
        if (group.equals(key)) return true;
        return key.endsWith("g:" + group);
    }

    /**
     * The listener is the only reliable view of the shade; the manager's own
     * query can report just the newest notice. Without notification access it
     * is null and the manager is the fallback.
     */
    private static StatusBarNotification[] active(NotificationManager manager) {
        StatusBarNotification[] fromListener = JawnNotificationListener.active();
        if (fromListener != null) return fromListener;
        try {
            return manager.getActiveNotifications();
        }
        catch (Throwable ignored) {
            return null;
        }
    }

    private static int flags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }
}
