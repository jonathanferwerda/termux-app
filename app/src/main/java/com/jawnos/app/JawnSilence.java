package com.jawnos.app;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The house's Silence, enforced on the phone.
 *
 * Silence is the king of this app's audio: every other directive - an alarm,
 * a notification, a Piper clip, the Android TTS fallback, the phone's ringer,
 * or a manager request to move a stream - is secondary to it. A silent house
 * sets every stream except the call stream to zero, puts the ringer on silent
 * and, when the app holds DND access, sets the interruption filter to none. The
 * call stream is deliberately left alone: it is the Hands-Free link that carries
 * the receptionist's IVR to the caller.
 *
 * The state and the snapshot are kept in SharedPreferences, not a static, so a
 * process that starts while the house is quiet knows to re-apply it, and a
 * process that exits while it is quiet leaves the snapshot for the next one to
 * restore. The page hands the state over through window.JawnPhone.setFacts /
 * setSilence; a device with no page still enforces the last state it was told.
 */
public final class JawnSilence {

    private static final String LOG_TAG = "JawnSilence";
    private static final String PREFS = "jawn_silence";
    private static final String ON = "on";
    private static final String SNAPSHOT = "snapshot";
    private static final Object LOCK = new Object();
    private static volatile Context appContext;

    private JawnSilence() {}

    /** Remember the application context so a player with no Context can ask. */
    public static void attach(Context context) {
        if (context == null) return;
        try { appContext = context.getApplicationContext(); } catch (Throwable ignored) { }
    }

    public static boolean active() {
        Context app = appContext;
        return app != null && active(app);
    }

    public static boolean active(Context context) {
        if (context == null) return false;
        try {
            return prefs(context).getBoolean(ON, false);
        }
        catch (Throwable t) { return false; }
    }

    /**
     * Turn silence on or off. Turning it on records what the phone was doing
     * first; turning it on again re-asserts the mute without overwriting that
     * first snapshot. Turning it off puts every stream and the ringer back.
     */
    public static void set(Context context, boolean on) {
        if (context == null) return;
        attach(context);
        final Context app = appContext;
        if (app == null) return;
        synchronized (LOCK) {
            boolean was = active(app);
            if (on) {
                if (was) { apply(app); return; }
                enter(app);
            }
            else if (was) {
                exit(app);
            }
        }
    }

    /** Re-apply after a process start; no-op when the house is not quiet. */
    public static void ensure() {
        final Context app = appContext;
        if (app != null && active(app)) {
            synchronized (LOCK) { apply(app); }
        }
    }

    /**
     * Re-apply without a transition. A player that is stopping restores the
     * stream it raised; this puts silence back on top of it, so an alarm's own
     * volume restore can never make the phone audible while the house is quiet.
     */
    public static void reassert() {
        final Context app = appContext;
        if (app != null && active(app)) {
            synchronized (LOCK) { apply(app); }
        }
    }

    private static void enter(Context app) {
        JSONObject snapshot = new JSONObject();
        AudioManager am = audio(app);
        if (am != null) {
            try {
                JSONArray streams = new JSONArray();
                for (int id : mutedStreams()) {
                    JSONObject row = new JSONObject();
                    row.put("id", id);
                    row.put("volume", am.getStreamVolume(id));
                    streams.put(row);
                }
                snapshot.put("streams", streams);
                snapshot.put("ringer", am.getRingerMode());
            }
            catch (Throwable t) { }
        }
        NotificationManager nm = notifications(app);
        if (nm != null) {
            try {
                boolean access = nm.isNotificationPolicyAccessGranted();
                snapshot.put("dnd_access", access);
                if (access) snapshot.put("filter", nm.getCurrentInterruptionFilter());
            }
            catch (Throwable t) { }
        }
        try {
            prefs(app).edit()
                    .putBoolean(ON, true)
                    .putString(SNAPSHOT, snapshot.toString())
                    .commit();
        }
        catch (Throwable t) { Log.w(LOG_TAG, "could not save the silence snapshot", t); }

        apply(app);
        // The app's own players are not the system's streams: stop them, so a
        // clip or a ring already in flight does not carry on into the silence.
        DeviceService.silenceNow();
        JawnClockSound.stop();
    }

    private static void exit(Context app) {
        JSONObject snapshot = null;
        try {
            String raw = prefs(app).getString(SNAPSHOT, "");
            if (raw != null && !raw.isEmpty()) snapshot = new JSONObject(raw);
        }
        catch (Throwable t) { }

        AudioManager am = audio(app);
        if (am != null && snapshot != null) {
            JSONArray streams = snapshot.optJSONArray("streams");
            if (streams != null) {
                for (int i = 0; i < streams.length(); i++) {
                    JSONObject row = streams.optJSONObject(i);
                    if (row == null) continue;
                    setVolume(am, row.optInt("id", -1), row.optInt("volume", 0));
                }
            }
            if (snapshot.has("ringer")) {
                try {
                    if (am.getRingerMode() == AudioManager.RINGER_MODE_SILENT) {
                        am.setRingerMode(snapshot.optInt("ringer", AudioManager.RINGER_MODE_NORMAL));
                    }
                }
                catch (Throwable t) { }
            }
        }

        NotificationManager nm = notifications(app);
        if (nm != null && snapshot != null
                && snapshot.optBoolean("dnd_access", false) && snapshot.has("filter")) {
            try {
                if (nm.isNotificationPolicyAccessGranted()
                        && nm.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_NONE) {
                    nm.setInterruptionFilter(snapshot.optInt("filter", NotificationManager.INTERRUPTION_FILTER_ALL));
                }
            }
            catch (Throwable t) { }
        }

        try {
            prefs(app).edit().putBoolean(ON, false).remove(SNAPSHOT).commit();
        }
        catch (Throwable t) { }
    }

    private static void apply(Context app) {
        AudioManager am = audio(app);
        if (am != null) {
            for (int id : mutedStreams()) setVolume(am, id, 0);
            try { am.setRingerMode(AudioManager.RINGER_MODE_SILENT); }
            catch (Throwable t) { }
        }
        NotificationManager nm = notifications(app);
        if (nm != null) {
            try {
                if (nm.isNotificationPolicyAccessGranted()) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
                }
            }
            catch (Throwable t) { }
        }
    }

    /**
     * Every stream that is not the call link. The call stream is the IVR's own
     * audio path; muting it would make the receptionist talk to nobody.
     */
    private static int[] mutedStreams() {
        return new int[] {
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_DTMF,
            AudioManager.STREAM_ACCESSIBILITY
        };
    }

    private static void setVolume(AudioManager am, int id, int volume) {
        if (am == null || id < 0) return;
        try {
            int max = am.getStreamMaxVolume(id);
            int level = Math.max(0, Math.min(max, volume));
            am.setStreamVolume(id, level, 0);
        }
        catch (Throwable t) { }
    }

    private static AudioManager audio(Context context) {
        try {
            return (AudioManager) context.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);
        }
        catch (Throwable t) { return null; }
    }

    private static NotificationManager notifications(Context context) {
        try {
            return (NotificationManager) context.getApplicationContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
        }
        catch (Throwable t) { return null; }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
