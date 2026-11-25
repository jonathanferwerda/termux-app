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

    private final Context context;

    public JawnAudioEngineBridge(Context context) {
        this.context = context.getApplicationContext();
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
