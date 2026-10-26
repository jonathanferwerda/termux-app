package com.termux.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;

/**
 * The sound of a Clock alarm or timer, played by the app on Android's alarm
 * stream.
 *
 * The Perl side plays a desktop alarm through sox, but on a phone that lands on
 * the media stream, where the ringer's Do Not Disturb and the alarm volume do
 * not reach it. This player asks for {@link AudioAttributes#USAGE_ALARM}, so an
 * alarm rings through the alarm channel even when the media volume is down.
 *
 * A short chime loops until Dismiss; a long recording plays once. The volume
 * ramp moves from a quiet start to the alarm's chosen level, and a guard stops
 * a ring that nobody answered.
 */
public final class JawnClockSound {
    private static final String LOG_TAG = "JawnClockSound";

    private static MediaPlayer player;
    private static Handler handler;
    private static Runnable ramp;
    private static Runnable guard;
    private static PowerManager.WakeLock wakeLock;
    private static String currentPath;
    private static long startedAt;
    /** The alarm stream's volume before the ring, so stop() can put it back. */
    private static AudioManager alarmAudio;
    private static int savedAlarmVolume = -1;

    private JawnClockSound() {}

    /**
     * @param relativePath a file below the jawn folder, e.g.
     *                     public/sounds/notifications/chime.mp3
     */
    public static synchronized void play(Context context, String relativePath, int volumePercent,
                                         int rampSeconds, boolean loop, int guardMs) {
        // The exact wake and jawn's ring broadcast arrive within a second of each
        // other; they describe one alarm, so the second one must not restart it.
        if (player != null && currentPath != null && currentPath.equals(relativePath)
                && System.currentTimeMillis() - startedAt < 5000L) {
            return;
        }
        stop();
        if (relativePath == null || relativePath.isEmpty()) return;
        File file = new File(JawnLauncher.getJawnDirPath(context), relativePath);
        if (!file.exists()) {
            Log.w(LOG_TAG, "sound missing: " + file.getAbsolutePath());
            return;
        }
        // A phone's volume keys turn down the alarm stream, and a ring nobody
        // hears is a missed alarm. Raise it for the ring; the player's own
        // volume (the alarm's percentage, and the ramp) scales on top.
        raiseAlarmVolume(context);
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setLooping(loop);
            mp.setOnCompletionListener(done -> restoreAlarmVolume());
            mp.setDataSource(file.getAbsolutePath());

            float target = Math.max(0.02f, Math.min(1f, volumePercent / 100f));
            final float start = rampSeconds > 0 ? target * 0.15f : target;
            mp.setVolume(start, start);
            mp.prepare();
            mp.start();
            player = mp;
            currentPath = relativePath;
            startedAt = System.currentTimeMillis();

            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jawnos:clock-sound");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire(Math.max(60_000L, guardMs > 0 ? guardMs + 15_000L : 60_000L));
                }
            }

            handler = new Handler(Looper.getMainLooper());
            if (rampSeconds > 0) {
                final long total = rampSeconds * 1000L;
                final long began = System.currentTimeMillis();
                final float from = start;
                final float to = target;
                ramp = new Runnable() {
                    @Override public void run() {
                        if (player == null) return;
                        float p = (System.currentTimeMillis() - began) / (float) total;
                        if (p > 1f) p = 1f;
                        float v = from + ((to - from) * p);
                        try { player.setVolume(v, v); } catch (Exception ignored) {}
                        if (p < 1f) handler.postDelayed(this, 250L);
                    }
                };
                handler.postDelayed(ramp, 250L);
            }
            else {
                try { mp.setVolume(target, target); } catch (Exception ignored) {}
            }

            if (guardMs > 0) {
                guard = new Runnable() {
                    @Override public void run() {
                        Log.i(LOG_TAG, "ring guard reached; stopping");
                        stop();
                    }
                };
                handler.postDelayed(guard, guardMs);
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "could not play " + file.getAbsolutePath(), e);
            stop();
        }
    }

    public static synchronized void stop() {
        if (handler != null) {
            if (ramp != null) handler.removeCallbacks(ramp);
            if (guard != null) handler.removeCallbacks(guard);
        }
        ramp = null;
        guard = null;
        handler = null;
        currentPath = null;
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        restoreAlarmVolume();
    }

    private static void raiseAlarmVolume(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int current = am.getStreamVolume(AudioManager.STREAM_ALARM);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            if (max <= 0 || current >= max) return;
            alarmAudio = am;
            savedAlarmVolume = current;
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
            Log.i(LOG_TAG, "alarm stream " + current + " -> " + max);
        } catch (Exception e) {
            Log.w(LOG_TAG, "could not raise the alarm stream", e);
            alarmAudio = null;
            savedAlarmVolume = -1;
        }
    }

    private static void restoreAlarmVolume() {
        if (alarmAudio != null && savedAlarmVolume >= 0) {
            try {
                alarmAudio.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
            } catch (Exception ignored) {}
        }
        alarmAudio = null;
        savedAlarmVolume = -1;
    }
}
