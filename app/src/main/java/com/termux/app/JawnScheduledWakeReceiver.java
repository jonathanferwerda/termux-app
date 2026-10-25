package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One scheduled wake per navigation interval: the alarm that replaces a
 * permanently held Termux wake lock.
 *
 * It holds a CPU-only partial lock for up to {@link #WAKE_MS}, touches
 * {@code ~/.president/wake_ready} so the Perl tree knows the scheduler is alive,
 * and re-arms the next alarm. The Perl tree's utility runner ticks every five
 * seconds, so the window is long enough to run whatever is due (navigation,
 * health, mail, the slow code sync). Once that work is finished the lock is
 * released early - see {@link #releaseWhenIdle} - so the phone sleeps for the
 * rest of the interval instead of always burning the full 45 seconds.
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
    private static final long POLL_MS = 2_000L;

    /** Only the newest wake decides when the CPU may sleep: a re-armed alarm
     *  must not be released by the thread of the wake it replaced. */
    private static final AtomicLong generation = new AtomicLong();

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

        final long myGeneration = generation.incrementAndGet();
        final long started = System.currentTimeMillis();
        final Context app = context.getApplicationContext();

        JawnLocationService.touchReady(app);
        // The navigation pool may have moved since the last beat; take the new
        // pace for the next alarm and retune the GPS listener to it.
        JawnLocationService.retunePace();
        JawnLocationService.scheduleWake(app);

        Thread worker = new Thread(() -> releaseWhenIdle(app, lock, started, myGeneration),
                "jawn-scheduled-wake");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Sleep as soon as this wake has nothing left to do.
     *
     * The manager writes {@code ~/.president/utility_idle} after a tick that ran
     * no job, and removes it as soon as one starts, so a marker newer than this
     * wake's start means every due job has finished. The screen being on means a
     * person is using the phone; a music track keeps its own lock and must not be
     * cut short. The 45s timeout stays the backstop for a tree that is down or a
     * job that overran, so the thread never outlives the lock.
     */
    private static void releaseWhenIdle(Context context, PowerManager.WakeLock lock,
                                        long started, long myGeneration) {
        long deadline = started + WAKE_MS - POLL_MS;
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MS);
                if (generation.get() != myGeneration) return;
                if (!lock.isHeld()) return;
                if (screenIsOn(context)) continue;
                if (musicWakeCount() > 0) continue;
                if (utilityIdleAt() < started) continue;
                return;
            }
        } catch (InterruptedException ignored) {
        } finally {
            if (lock.isHeld()) lock.release();
        }
    }

    private static boolean screenIsOn(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    private static File presidentFile(String name) {
        return new File(new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".president"), name);
    }

    /** When the manager last finished a utility tick with nothing due. */
    private static long utilityIdleAt() {
        File file = presidentFile("utility_idle");
        return file.exists() ? file.lastModified() : 0L;
    }

    /** How many tracks are holding the music stream's own wake lock. */
    private static int musicWakeCount() {
        File file = presidentFile("music_stream_wake");
        if (!file.exists()) return 0;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[32];
            int read = in.read(bytes);
            if (read <= 0) return 0;
            return Integer.parseInt(new String(bytes, 0, read, StandardCharsets.UTF_8).trim());
        } catch (Exception ignored) {
            return 0;
        }
    }
}
