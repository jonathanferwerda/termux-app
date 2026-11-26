package com.jawnos.app;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

/**
 * {@code window.JawnAudioEngine}: the Studio page's own control of a native
 * take.
 *
 * The page can see {@link JawnAudioEngine}'s JSON but cannot start the
 * {@link JawnAudioService} that anchors the process, so this bridge is the one
 * place that does both. It is thin on purpose: every method answers the
 * engine's or the service's JSON unchanged, and a missing library or a bad
 * argument becomes {@code {"ok":0,"error":...}} instead of an exception in the
 * page.
 *
 * {@link #load()} also drops the backends report at
 * {@code /sdcard/Download/jawnos-native-backends.json} on a best-effort basis,
 * so the build gate can pull it over adb without the page's help (the app
 * holds MANAGE_EXTERNAL_STORAGE).
 */
public class JawnAudioEngineBridge {

    private static final String TAG = "JawnAudioEngineBridge";
    private static final String BACKENDS_FILE =
            "/sdcard/Download/jawnos-native-backends.json";
    private static final String ROUNDTRIP_FILE_PREFIX =
            "/sdcard/Download/jawnos-native-roundtrip-";

    private final Context context;
    /** Guards the one probe this process may run at a time. */
    private final Object roundTripLock = new Object();
    private boolean roundTripRunning = false;
    private volatile String roundTripStatus =
            "{\"ok\":false,\"error\":\"no round trip has started\"}";

    /** The P0 USB gate, sharing this bridge's application context. */
    private final JawnUsbProbe usbGate;

    /**
     * The native USB take: the process-wide holder, the same instance the USB
     * anchor service reads, so both act on one connection.
     */
    private final JawnUsbAudio usbAudio;

    public JawnAudioEngineBridge(Context context) {
        this.context = context.getApplicationContext();
        this.usbGate = new JawnUsbProbe(this.context);
        this.usbAudio = JawnUsbAudio.shared(this.context);
    }

    /** Whether the engine is in this build, plus its backends report. */
    @JavascriptInterface
    public String load() {
        if (!JawnAudioEngine.isLoaded()) {
            String failure = error("the audio engine is not in this build: "
                    + JawnAudioEngine.loadError());
            writeBackends(failure);
            return failure;
        }
        String backends = JawnAudioEngine.backends();
        writeBackends(backends);
        try {
            JSONObject out = new JSONObject();
            out.put("ok", 1);
            out.put("loaded", true);
            out.put("backends", asJson(backends));
            return out.toString();
        } catch (JSONException e) {
            return error("the audio engine could not report its backends: " + e);
        }
    }

    /**
     * Start a take and return the service's answer. The arguments arrive as
     * strings from the page; a number that does not parse keeps the default
     * (the HAL's own input, ten minutes), never an exception.
     */
    @JavascriptInterface
    public String start(String deviceId, String seconds, String out) {
        int id = parseInt(deviceId, -1);
        double secs = parseDouble(seconds, 600d);
        String path = out == null || out.trim().isEmpty() ? null : out;
        try {
            return JawnAudioService.start(context, id, secs, path);
        } catch (Throwable t) {
            return error("the audio service could not start: " + t);
        }
    }

    /**
     * Start a take through one Channel plan. The arguments arrive as strings;
     * an empty {@code plan} is the raw path. The engine validates the plan
     * before any stream opens, so a refused plan answers a sentence.
     */
    @JavascriptInterface
    public String startPlan(String deviceId, String seconds, String out, String plan) {
        int id = parseInt(deviceId, -1);
        double secs = parseDouble(seconds, 600d);
        String path = out == null || out.trim().isEmpty() ? null : out;
        String chosen = plan == null ? "" : plan.trim();
        try {
            return JawnAudioService.start(context, id, secs, path, chosen);
        } catch (Throwable t) {
            return error("the audio service could not start: " + t);
        }
    }

    /**
     * The engine's own tone+delay fixture plan. The panel sends these bytes
     * back through {@link #startPlan}, so the plan the Linux test measures and
     * the plan the phone runs are the same string.
     */
    @JavascriptInterface
    public String fixturePlan() {
        try {
            return JawnAudioEngine.fixturePlan();
        } catch (Throwable t) {
            return error("the audio engine could not report its fixture: " + t);
        }
    }

    /**
     * End the take: the engine joins its worker and answers the final report,
     * then the service is torn down (its notification goes with it). The
     * engine's report is returned, not the teardown's silence.
     */
    @JavascriptInterface
    public String stop() {
        String report;
        try {
            report = JawnAudioEngine.stop();
        } catch (Throwable t) {
            report = error("the audio engine could not stop: " + t);
        }
        try {
            JawnAudioService.stop(context);
        } catch (Throwable t) {
            Log.w(TAG, "could not stop the audio service", t);
        }
        return report;
    }

    /** The engine's last report, live while a take runs. */
    @JavascriptInterface
    public String status() {
        try {
            return JawnAudioEngine.status();
        } catch (Throwable t) {
            return error("the audio engine could not report: " + t);
        }
    }

    /** Every input the phone can see, with USB inputs marked. */
    @JavascriptInterface
    public String devices() {
        JSONArray list = new JSONArray();
        try {
            AudioManager manager =
                    (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null) {
                for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    JSONObject item = new JSONObject();
                    item.put("id", device.getId());
                    item.put("name", String.valueOf(device.getProductName()));
                    item.put("type", device.getType());
                    item.put("typeName", typeName(device.getType()));
                    JSONArray rates = new JSONArray();
                    for (int rate : device.getSampleRates()) rates.put(rate);
                    item.put("sampleRates", rates);
                    JSONArray channels = new JSONArray();
                    for (int count : device.getChannelCounts()) channels.put(count);
                    item.put("channelCounts", channels);
                    item.put("usb", isUsb(device.getType()));
                    list.put(item);
                }
            }
        } catch (Throwable t) {
            return error("the audio devices could not be listed: " + t);
        }
        try {
            JSONObject out = new JSONObject();
            out.put("ok", 1);
            out.put("devices", list);
            return out.toString();
        } catch (JSONException e) {
            return error("the audio devices could not be listed: " + e);
        }
    }

    /** The engine's own backends JSON, the same as {@code --backends --json}. */
    @JavascriptInterface
    public String backends() {
        try {
            return JawnAudioEngine.backends();
        } catch (Throwable t) {
            return error("the audio engine could not report its backends: " + t);
        }
    }

    /** Every output the phone can see, with USB outputs marked. */
    @JavascriptInterface
    public String outputs() {
        JSONArray list = new JSONArray();
        try {
            AudioManager manager =
                    (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null) {
                for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    JSONObject item = new JSONObject();
                    item.put("id", device.getId());
                    item.put("name", String.valueOf(device.getProductName()));
                    item.put("type", device.getType());
                    item.put("typeName", typeName(device.getType()));
                    JSONArray rates = new JSONArray();
                    for (int rate : device.getSampleRates()) rates.put(rate);
                    item.put("sampleRates", rates);
                    JSONArray channels = new JSONArray();
                    for (int count : device.getChannelCounts()) channels.put(count);
                    item.put("channelCounts", channels);
                    item.put("usb", isUsb(device.getType()));
                    list.put(item);
                }
            }
        } catch (Throwable t) {
            return error("the audio outputs could not be listed: " + t);
        }
        try {
            JSONObject out = new JSONObject();
            out.put("ok", 1);
            out.put("outputs", list);
            return out.toString();
        } catch (JSONException e) {
            return error("the audio outputs could not be listed: " + e);
        }
    }

    /**
     * Start the duplex round-trip probe on a worker thread and return the
     * started sentence at once. Device ids below zero mean the HAL's default.
     * The panel then polls {@link #roundtripStatus()} until the engine's report
     * replaces it. The probe blocks for its capture window; running it here is
     * what keeps the WebView responsive.
     */
    @JavascriptInterface
    public String roundtrip(String deviceIn, String deviceOut) {
        int inId = parseInt(deviceIn, -1);
        int outId = parseInt(deviceOut, -1);
        if (!JawnAudioEngine.isLoaded()) {
            return error("the audio engine is not in this build: " + JawnAudioEngine.loadError());
        }
        synchronized (roundTripLock) {
            if (roundTripRunning) return error("a round trip is already measuring");
            roundTripRunning = true;
            roundTripStatus = "{\"ok\":true,\"state\":\"started\",\"probe\":\"roundtrip\","
                    + "\"device_in_id\":" + inId + ",\"device_out_id\":" + outId + "}";
        }
        Thread worker = new Thread(() -> {
            String report;
            try {
                report = JawnAudioEngine.roundTrip(inId, outId);
            } catch (Throwable t) {
                report = error("the round trip failed: " + t);
            }
            String path = writeRoundTrip(report);
            if (path != null) report = attachFile(report, path);
            synchronized (roundTripLock) {
                roundTripStatus = report;
                roundTripRunning = false;
            }
        }, "jawn-audio-roundtrip");
        worker.setDaemon(true);
        worker.start();
        synchronized (roundTripLock) {
            return roundTripStatus;
        }
    }

    /** The probe's last answer: the started sentence, then the report or error. */
    @JavascriptInterface
    public String roundtripStatus() {
        synchronized (roundTripLock) {
            return roundTripStatus;
        }
    }

    /**
     * The P0 USB gate: find the UM2, ask for USB permission, force-claim its
     * audio interfaces, select the isochronous alternate setting and read back
     * the UAC1 48000 Hz setting. It returns at once - the started sentence, or
     * the permission request - and the panel polls {@link #usbProbeStatus()}.
     */
    @JavascriptInterface
    public String usbProbe() {
        try {
            return usbGate.probe();
        } catch (Throwable t) {
            return error("the USB probe could not start: " + t);
        }
    }

    /** The USB probe's last JSON: its state, then the recorded report. */
    @JavascriptInterface
    public String usbProbeStatus() {
        try {
            return usbGate.status();
        } catch (Throwable t) {
            return error("the USB probe could not report: " + t);
        }
    }

    /**
     * Start the native USB take: anchor it in {@link JawnUsbAudioService},
     * then have the holder open and claim the device and hand its fd to the
     * engine. The anchor comes first so a sleeping screen or a backgrounded
     * process cannot end the take. It returns at once (the detecting or
     * requesting sentence); the holder asks for USB permission, opens and
     * claims on its own worker thread, and the panel polls
     * {@link #usbTakeStatus()}.
     */
    @JavascriptInterface
    public String usbTake(String seconds, String plan) {
        if (!JawnUsbAudioService.start(context)) {
            return error("the USB take anchor could not start");
        }
        try {
            return usbAudio.start(seconds, plan);
        } catch (Throwable t) {
            // Do not leave an anchor holding the process for a take that never
            // began.
            JawnUsbAudioService.stop(context);
            return error("the USB take could not start: " + t);
        }
    }

    /**
     * End the USB take: the holder stops the engine first (joining its USB
     * worker), then releases the claims and closes the connection. The anchor
     * is dropped afterwards; its {@code onDestroy} then sees no live take and
     * only releases the wake lock. The final report names the WAV, so the take
     * can be pulled without the app.
     */
    @JavascriptInterface
    public String usbTakeStop() {
        String report;
        try {
            report = usbAudio.stop();
        } catch (Throwable t) {
            report = error("the USB take could not stop: " + t);
        }
        JawnUsbAudioService.stop(context);
        return report;
    }

    /** The USB take's live status while held, else its last report. */
    @JavascriptInterface
    public String usbTakeStatus() {
        try {
            return usbAudio.status();
        } catch (Throwable t) {
            return error("the USB take could not report: " + t);
        }
    }

    /**
     * The attached USB audio device the direct take would claim, and whether
     * its USB permission is already granted. It opens and claims nothing, so
     * the panel can show "a UM2 is present but not allowed yet" before a take.
     */
    @JavascriptInterface
    public String usbAudioDevice() {
        try {
            return usbAudio.describe();
        } catch (Throwable t) {
            return error("the USB audio device could not be reported: " + t);
        }
    }

    // ------------------------------------------------------------------ internals

    /** Embed a JSON string as an object when it is one, or as text when not. */
    private static Object asJson(String json) {
        try {
            return new JSONObject(json);
        } catch (Throwable ignored) {
            return json == null ? "" : json;
        }
    }

    private static int parseInt(String text, int fallback) {
        if (text == null) return fallback;
        try {
            return Integer.parseInt(text.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String text, double fallback) {
        if (text == null) return fallback;
        try {
            double value = Double.parseDouble(text.trim());
            if (Double.isNaN(value) || Double.isInfinite(value)) return fallback;
            return value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean isUsb(int type) {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    private static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "builtin_mic";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "builtin_speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "builtin_earpiece";
            case AudioDeviceInfo.TYPE_LINE_ANALOG: return "line_analog";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL: return "line_digital";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "bluetooth_sco";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "wired_headset";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "usb_device";
            case AudioDeviceInfo.TYPE_USB_ACCESSORY: return "usb_accessory";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "usb_headset";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "telephony";
            case AudioDeviceInfo.TYPE_HDMI: return "hdmi";
            default: return "type_" + type;
        }
    }

    /** Best effort only: the gate reads it over adb when the write lands. */
    private static void writeBackends(String backends) {
        if (backends == null) return;
        try (FileWriter writer = new FileWriter(new File(BACKENDS_FILE), false)) {
            writer.write(backends);
            writer.write("\n");
        } catch (Throwable t) {
            Log.w(TAG, "could not write " + BACKENDS_FILE, t);
        }
    }

    /** Best effort only, like the backends file: the panel names the path. */
    private String writeRoundTrip(String report) {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        String path = ROUNDTRIP_FILE_PREFIX + stamp + ".json";
        try (FileWriter writer = new FileWriter(new File(path), false)) {
            writer.write(report == null ? "" : report);
            writer.write("\n");
        } catch (Throwable t) {
            Log.w(TAG, "could not write " + path, t);
            return null;
        }
        return path;
    }

    /** Add the sidecar path to the engine's JSON, keeping every engine key. */
    private static String attachFile(String report, String path) {
        try {
            JSONObject found = new JSONObject(report);
            found.put("file", path);
            return found.toString();
        } catch (Throwable ignored) {
            return report;
        }
    }

    private static String error(String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", 0);
            out.put("error", message);
        } catch (JSONException ignored) {
            return "{\"ok\":0,\"error\":\"the audio bridge is unavailable\"}";
        }
        return out.toString();
    }
}
