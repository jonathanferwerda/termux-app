package com.termux.app;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * The M1 answer to termux-notification-list. Android only lets an app read the
 * notification shade through this service and only after the person grants
 * "Notification access" in Settings; without the grant the service never
 * connects and subs::device_call falls back to the termux command.
 *
 * One instance is published here so DeviceService (same process, same uid) can
 * ask for the active notifications without a second binder hop.
 */
public class JawnNotificationListener extends NotificationListenerService {

    private static volatile JawnNotificationListener instance;

    @Override
    public void onListenerConnected() {
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /** Null when the person has not granted notification access. */
    public static StatusBarNotification[] active() {
        JawnNotificationListener service = instance;
        if (service == null) return null;
        try {
            return service.getActiveNotifications();
        } catch (Throwable t) {
            return null;
        }
    }
}
