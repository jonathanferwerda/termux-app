package com.jawnos.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;

/**
 * The house's ringtone for a live call, played on Android's ring stream.
 *
 * The chosen sound is a house file (public/sounds/notifications/<name>.mp3) the
 * Phone app saved for this device; /manager/phone/sync hands it here, so the
 * ring is the one the person picked, not the system's. It loops until the call
 * is answered, declined or disconnected. The house's Silence wins: a silenced
 * phone plays nothing, and engaging silence stops a ring already in flight.
 *
 * The system's own ringer is not muted. While JawnOS is the default dialer this
 * is the only app that rings; when it is not, the in-call service does not run
 * at all, so there is no second ring to suppress.
 */
public final class JawnRinger {
    private static final String LOG_TAG = "JawnRinger";

    private static MediaPlayer player;
    private static PowerManager.WakeLock wakeLock;

    private JawnRinger() {}

    public static synchronized void start(Context context) {
        stop();
        if (JawnSilence.active(context)) return;
        String ringtone = JawnPhoneBridge.ringtoneFor(context);
        String base = JawnLauncher.getJawnDirPath(context) + "/public/sounds/";
        File file = new File(base + ringtone);
        if (!file.exists()) file = new File(base + "notifications/ring.mp3");
        if (!file.exists()) {
            Log.w(LOG_TAG, "ringtone missing: " + file.getAbsolutePath());
            return;
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setLooping(true);
            mp.setDataSource(file.getAbsolutePath());
            mp.prepare();
            mp.start();
            player = mp;
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jawnos:phone-ring");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) wakeLock.acquire(120_000L);
            }
        } catch (Throwable t) {
            Log.w(LOG_TAG, "could not ring", t);
            stop();
        }
    }

    public static synchronized void stop() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }
}
