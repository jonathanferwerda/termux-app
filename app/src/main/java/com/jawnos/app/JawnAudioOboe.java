package com.jawnos.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;

import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * {@code window.JawnAudioOboe}: the Studio page's own way into the phone's
 * low-latency audio path.
 *
 * The page can already capture with {@code getUserMedia}, but that runs
 * through Chromium's audio service: it cannot ask for an exclusive (MMAP)
 * stream, choose the frames per burst, or say what it was actually granted.
 * This bridge starts the native Oboe duplex stream
 * (app/src/main/cpp/JawnAudioOboe.cpp) and hands back the facts the page
 * cannot otherwise see - the granted sharing and performance mode, the sample
 * rate, frames per burst, buffer size in frames, xrun count and a running
 * frame count.
 *
 * It is deliberately thin. The gain it applies is the monitoring gain the page
 * asked for; there is no routing, focus or effect policy here. Everything that
 * shapes the sound stays on the page and, later, in the engine.
 *
 * A build without the native library - or a device whose ABI it does not
 * cover - must still run: every method answers with {@code {"ok":0,...}} and
 * the link error instead of throwing, so the Studio page can say why.
 */
public class JawnAudioOboe {

    /** The activity's request code for the microphone prompt. */
    public static final int REQ_PERMS = 7101;

    private static final boolean LOADED;
    private static final String LOAD_ERROR;

    static {
        boolean loaded = false;
        String error = "";
        try {
            System.loadLibrary("jawnaudio");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            error = e.getMessage() == null ? "the native library is missing" : e.getMessage();
        }
        LOADED = loaded;
        LOAD_ERROR = error;
    }

    private static native String nativeStart(int sampleRate, int framesPerBurst, float gain);

    private static native String nativeStop();

    private static native String nativeStats(boolean microphoneGranted);

    private static native void nativeSetGain(float gain);

    private final Activity activity;
    private final Runnable permissionRequester;
    // The arguments of a start that is waiting on the runtime prompt. The
    // activity calls onPermissionResult() once the answer arrives.
    private boolean pendingStart;
    private int wantRate = 48000;
    private int wantBurst = 192;
    private float wantGain = 0.8f;

    public JawnAudioOboe(Activity activity, Runnable permissionRequester) {
        this.activity = activity;
        this.permissionRequester = permissionRequester;
    }

    /** Whether the native library loaded, and the facts a page needs to decide
     *  whether to offer the native path at all. */
    @JavascriptInterface
    public String load() {
        JSONObject answer = new JSONObject();
        try {
            answer.put("ok", LOADED ? 1 : 0);
            answer.put("loaded", LOADED);
            answer.put("microphone", microphoneGranted());
            answer.put("version", com.jawnos.BuildConfig.VERSION_NAME);
            if (!LOADED) {
                answer.put("error", "the native audio library is not in this build: " + LOAD_ERROR);
            }
        } catch (JSONException ignored) {
            return failure("could not describe the native audio path");
        }
        return answer.toString();
    }

    /**
     * Open the duplex stream. The three arguments are strings so the page can
     * pass whatever it has; an unreadable or out-of-range value falls back to
     * the house rate (48 kHz), a 4 ms burst and unity-ish gain. A missing
     * microphone grant asks for it once and answers {@code waiting:true}; the
     * start runs when the prompt is answered.
     */
    @JavascriptInterface
    public String start(String sampleRate, String framesPerBurst, String gain) {
        wantRate = number(sampleRate, 48000, 8000, 192000);
        wantBurst = number(framesPerBurst, 192, 16, 4096);
        wantGain = number(gain, 0.8f, 0f, 4f);
        if (!LOADED) return failure("the native audio library is not in this build: " + LOAD_ERROR);
        if (!microphoneGranted()) {
            if (permissionRequester != null) {
                pendingStart = true;
                permissionRequester.run();
                return waiting("waiting for the microphone permission");
            }
            return failure("the microphone permission is not granted");
        }
        pendingStart = false;
        return nativeStart(wantRate, wantBurst, wantGain);
    }

    /** Close the stream. Safe to call when it was never started. */
    @JavascriptInterface
    public String stop() {
        pendingStart = false;
        if (!LOADED) return failure("the native audio library is not in this build: " + LOAD_ERROR);
        return nativeStop();
    }

    /** The granted facts and the run's frame count, as JSON. */
    @JavascriptInterface
    public String stats() {
        if (!LOADED) return failure("the native audio library is not in this build: " + LOAD_ERROR);
        return nativeStats(microphoneGranted());
    }

    /** Move the monitoring gain without reopening the stream. */
    @JavascriptInterface
    public void setGain(String gain) {
        wantGain = number(gain, wantGain, 0f, 4f);
        if (LOADED) nativeSetGain(wantGain);
    }

    /**
     * The activity has answered the microphone prompt. A grant starts the
     * stream that was waiting; its result is not returned (the page polls
     * {@link #stats()}, whose own error says why a start failed).
     */
    void onPermissionResult() {
        if (!pendingStart) return;
        pendingStart = false;
        if (microphoneGranted() && LOADED) {
            nativeStart(wantRate, wantBurst, wantGain);
        }
    }

    private boolean microphoneGranted() {
        return activity != null
                && ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private static int number(String text, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(text == null ? "" : text.trim());
            return (value < min || value > max) ? fallback : value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float number(String text, float fallback, float min, float max) {
        try {
            float value = Float.parseFloat(text == null ? "" : text.trim());
            if (!(value >= min) || value > max) return fallback;
            return value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String failure(String error) {
        return answer(0, false, false, error);
    }

    private static String waiting(String note) {
        return answer(0, false, true, note);
    }

    private static String answer(int ok, boolean running, boolean waiting, String error) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", ok);
            out.put("running", running);
            if (waiting) out.put("waiting", true);
            if (error != null && !error.isEmpty()) out.put("error", error);
        } catch (JSONException ignored) {
            return "{\"ok\":0,\"running\":false}";
        }
        return out.toString();
    }
}
