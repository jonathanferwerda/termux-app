package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * One scheduled wake per navigation interval: the alarm that replaces a
 * permanently held Termux wake lock.
 *
 * It holds a CPU-only partial lock for {@link #WAKE_MS}, touches
 * {@code ~/.president/wake_ready} so the Perl tree knows the scheduler is alive,
 * and re-arms the next alarm. The Perl tree's utility runner ticks every five
 * seconds, so a window this long is enough to run whatever is due (navigation,
 * health, mail, the slow code sync) and then let the device sleep.
 *
 * This is separate from {@link JawnWakeReceiver}, which is the exported
 * {@code JAWN_WAKE} hook adb uses to start jawn from outside the app.
 *
 * No service and no notification: the app's foreground services keep this
 * process alive, and the wake lock's own timeout releases it.
 */
public class JawnScheduledWakeReceiver extends BroadcastReceiver {
    public static final String ACTION_WAKE = "com.termux.app.SCHEDULED_WAKE";
    private static final long WAKE_MS = 45_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_WAKE.equals(intent.getAction())) return;

        // A stopped location service must not keep arming alarms: the Perl side
        // would read a fresh wake_ready and release its own lock for nothing.
        if (!JawnLocationService.isEnabled(context)) {
            JawnLocationService.cancelWake(context);
            JawnLocationService.clearReady(context);
            return;
        }

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;

        // The app process also hosts the location foreground service, so it
        // outlives this callback; the timeout releases the lock on its own.
        PowerManager.WakeLock lock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "jawnos:scheduled-wake");
        lock.setReferenceCounted(false);
        lock.acquire(WAKE_MS);

        JawnLocationService.touchReady(context);
        JawnLocationService.scheduleWake(context);
    }
}
