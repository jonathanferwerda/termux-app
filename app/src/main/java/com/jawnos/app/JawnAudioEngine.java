package com.jawnos.app;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Studio take's entry into the Rust audio engine.
 *
 * The engine (engine/src/oboe_device.rs) is a cdylib with a PLAIN C ABI:
 * {@code jawn_audio_start}, {@code jawn_audio_stop}, {@code jawn_audio_status},
 * {@code jawn_audio_backends} and {@code jawn_audio_free}. Those are not JNI
 * symbols, so a Java {@code native} method cannot bind to them directly. The
 * small C++ shim in
 * app/src/main/cpp/JawnAudioEngineJni.cpp is compiled into libjawnaudio.so -
 * the same library {@link JawnAudioOboe} already loads - and it dlopen()s
 * libjawn_audio.so and exports the Java_com_jawnos_app_JawnAudioEngine_*
 * symbols below. This class therefore loads "jawnaudio" (the shim), never
 * "jawn_audio", and the Rust ABI stays untouched.
 *
 * A build without either library still runs: every method answers
 * {@code {"ok":0,"error":...}} instead of throwing, the same rule
 * JawnAudioOboe follows.
 */
public final class JawnAudioEngine {

    private static final boolean LOADED;
    private static final String LOAD_ERROR;

    static {
        boolean loaded = false;
        String error = "";
        try {
            // The shim, not the Rust library: these JNI symbols live here.
            System.loadLibrary("jawnaudio");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            error = e.getMessage() == null ? "the native library is missing" : e.getMessage();
        }
        LOADED = loaded;
        LOAD_ERROR = error;
    }

    private JawnAudioEngine() {
    }

    private static native String nativeStart(int deviceId, double seconds, String out);

    private static native String nativeStartPlan(int deviceId, double seconds, String out, String plan);

    private static native String nativeFixturePlan();

    private static native String nativeStop();

    private static native String nativeStatus();

    private static native String nativeBackends();

    /** Whether the shim library loaded, so a caller can explain a refusal. */
    public static boolean isLoaded() {
        return LOADED;
    }

    public static String loadError() {
        return LOAD_ERROR;
    }

    /**
     * Start a capture take. {@code deviceId < 0} lets the HAL pick the input;
     * a null {@code out} runs without a WAV, and any other value is the WAV
     * path (the engine treats only null as "no WAV", so a path is passed
     * through unchanged). The engine returns immediately with
     * {@code {"ok":true,"state":"started",...}} and reports the take's real
     * shape later through {@link #status()}.
     */
    public static String start(int deviceId, double seconds, String out) {
        if (!LOADED) return failure("the audio engine is not in this build: " + LOAD_ERROR);
        try {
            return nativeStart(deviceId, seconds, out);
        } catch (Throwable t) {
            return failure("the audio engine could not start: " + t);
        }
    }

    /**
     * Start a take through one Channel plan (docs/studio-oboe-slice.md §9 item
     * 3). A null or empty {@code plan} is exactly {@link #start}: the raw path.
     * Any other value is the engine's one-object plan JSON, such as the bytes
     * {@link #fixturePlan()} returns.
     */
    public static String startPlan(int deviceId, double seconds, String out, String plan) {
        if (!LOADED) return failure("the audio engine is not in this build: " + LOAD_ERROR);
        try {
            return nativeStartPlan(deviceId, seconds, out, plan == null ? "" : plan);
        } catch (Throwable t) {
            return failure("the audio engine could not start: " + t);
        }
    }

    /**
     * The engine's own tone+delay fixture plan, as the JSON string a take
     * carries. Asking the engine for these bytes is what keeps the Linux test
     * and the phone on one plan instead of two copies.
     */
    public static String fixturePlan() {
        if (!LOADED) return failure("the audio engine is not in this build: " + LOAD_ERROR);
        try {
            return nativeFixturePlan();
        } catch (Throwable t) {
            return failure("the audio engine could not report its fixture: " + t);
        }
    }

    /** End the take (if any). The engine joins its worker and closes the WAV
     *  before answering with the final report. Safe to call when idle. */
    public static String stop() {
        if (!LOADED) return failure("the audio engine is not in this build: " + LOAD_ERROR);
        try {
            return nativeStop();
        } catch (Throwable t) {
            return failure("the audio engine could not stop: " + t);
        }
    }

    /** The last status this process reported, as the engine wrote it. */
    public static String status() {
        if (!LOADED) return failure("the audio engine is not in this build: " + LOAD_ERROR);
        try {
            return nativeStatus();
        } catch (Throwable t) {
            return failure("the audio engine could not report: " + t);
        }
    }

    /** The engine's backends report: the same one-line JSON
     *  {@code --backends --json} prints, such as
     *  {@code {"alsa":false,...,"oboe":true,"null":true}}. */
    public static String backends() {
        if (!LOADED) return failure("the audio engine is not in this build: " + LOAD_ERROR);
        try {
            return nativeBackends();
        } catch (Throwable t) {
            return failure("the audio engine could not report its backends: " + t);
        }
    }

    /** True for the start sentence ({@code state:"started"}), false for a
     *  final report or an error. */
    public static boolean running(String json) {
        try {
            JSONObject answer = new JSONObject(json == null ? "" : json);
            return answer.optBoolean("ok", false) && "started".equals(answer.optString("state", ""));
        } catch (Throwable t) {
            return false;
        }
    }

    private static String failure(String error) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", 0);
            out.put("error", error);
        } catch (JSONException ignored) {
            return "{\"ok\":0,\"error\":\"the audio engine is unavailable\"}";
        }
        return out.toString();
    }
}
