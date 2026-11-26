package com.jawnos.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The P0 USB gate: a Java-only probe of the Behringer UM2, run from the Studio
 * page, to answer one question before any engine work - can this app take the
 * audio interfaces away from the kernel and talk UAC1 to them directly?
 *
 * It detects the device, asks for USB permission, opens the connection, force
 * claims every AudioControl / AudioStreaming interface (this is the detach of
 * snd-usb-audio / the Android USB audio HAL), selects each isochronous
 * alternate setting, sends the UAC1 SET_CUR SAMPLING_FREQ_CONTROL for 48000 Hz
 * and reads it back with GET_CUR. It records all of it as JSON and always
 * releases the claims and closes the connection, so the tablet's normal audio
 * returns.
 *
 * It is deliberately not an audio path: the UM2 is isochronous, and Java's
 * {@link android.hardware.usb.UsbRequest} cannot queue isochronous transfers.
 * This proves claim, alt setting and class control only.
 */
public class JawnUsbProbe {

    private static final String TAG = "JawnUsbProbe";

    /** The dynamic permission action; the PendingIntent is package-scoped. */
    static final String ACTION_PERMISSION = "com.jawnos.app.USB_PERMISSION";

    private static final int AUDIO_CLASS = UsbConstants.USB_CLASS_AUDIO;
    private static final int AUDIO_CONTROL_SUBCLASS = 1;
    private static final int AUDIO_STREAMING_SUBCLASS = 2;

    // Behringer UM2 (UAC1): vendor 0x08bb, product 0x2902.
    private static final int UM2_VENDOR = 0x08bb;
    private static final int UM2_PRODUCT = 0x2902;

    private static final int RATE_48000 = 48000;
    /** wValue: CS_SAMPLING_FREQ_CONTROL (0x01) in the high byte, 0x01 selector. */
    private static final int RATE_CONTROL = 0x0100;
    private static final int SET_CUR = 0x01;
    private static final int GET_CUR = 0x81;
    private static final int TIMEOUT_MS = 1000;

    // bmRequestType: class, host->device / device->host, endpoint / interface.
    private static final int SET_ENDPOINT = 0x22;
    private static final int GET_ENDPOINT = 0xA2;
    private static final int SET_INTERFACE = 0x21;
    private static final int GET_INTERFACE = 0xA1;

    private static final String OUT_PREFIX = "/sdcard/Download/jawnos-usb-probe-";

    private final Context context;
    /** Guards {@link #running}, {@link #last}, the receiver and the claim list. */
    private final Object lock = new Object();

    private boolean running = false;
    private String last = "{\"ok\":false,\"state\":\"idle\"}";
    private UsbDeviceConnection connection;
    private final List<UsbInterface> claimed = new ArrayList<>();
    private BroadcastReceiver permissionReceiver;

    public JawnUsbProbe(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Start the one-shot probe and return what it is doing now: the started
     * sentence, or the permission request. The panel polls {@link #status()}
     * while the state is {@code requesting} or {@code running}.
     */
    public String probe() {
        synchronized (lock) {
            if (running) return last;
            running = true;
            JSONObject started = state("detecting");
            put(started, "message", "looking for the UM2");
            last = started.toString();
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    detect();
                } catch (Throwable t) {
                    finish(errorJson("the probe failed: " + t));
                }
            }
        }, "jawn-usb-probe");
        worker.setDaemon(true);
        worker.start();
        return status();
    }

    /** The probe's last answer: the started sentence, the request, or the report. */
    public String status() {
        synchronized (lock) {
            return last;
        }
    }

    // ------------------------------------------------------------------ detect

    private void detect() {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            finish(errorJson("this build cannot see a UsbManager"));
            return;
        }
        UsbDevice device = findDevice(manager);
        if (device == null) {
            JSONObject out = state("error");
            put(out, "ok", false);
            put(out, "error", "no USB audio device is attached"
                    + " - plug in the UM2 and run the probe again");
            finish(out.toString());
            return;
        }
        if (!manager.hasPermission(device)) {
            askPermission(manager, device);
            return;
        }
        measureAsync(manager, device, "granted");
    }

    /** The UM2 first; otherwise any attached device with an audio interface. */
    private static UsbDevice findDevice(UsbManager manager) {
        HashMap<String, UsbDevice> list = manager.getDeviceList();
        if (list == null) return null;
        UsbDevice fallback = null;
        for (UsbDevice device : list.values()) {
            if (device.getVendorId() == UM2_VENDOR && device.getProductId() == UM2_PRODUCT) {
                return device;
            }
            if (fallback == null && hasAudioInterface(device)) fallback = device;
        }
        return fallback;
    }

    private static boolean hasAudioInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == AUDIO_CLASS) return true;
        }
        return false;
    }

    // --------------------------------------------------------------- permission

    private void askPermission(final UsbManager manager, final UsbDevice device) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context received, Intent intent) {
                if (intent == null || !ACTION_PERMISSION.equals(intent.getAction())) return;
                boolean granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbDevice extra = deviceFrom(intent);
                UsbDevice target = extra != null ? extra : device;
                try {
                    if (granted) {
                        measureAsync(manager, target, "granted");
                    } else {
                        JSONObject out = state("denied");
                        put(out, "ok", false);
                        put(out, "device", deviceJson(target));
                        put(out, "error", "USB permission was not granted");
                        finish(out.toString());
                    }
                } finally {
                    unregisterPermission();
                }
            }
        };
        synchronized (lock) {
            permissionReceiver = receiver;
        }
        try {
            ContextCompat.registerReceiver(context, receiver,
                    new IntentFilter(ACTION_PERMISSION),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            unregisterPermission();
            finish(errorJson("could not register the USB permission receiver: " + t));
            return;
        }
        Intent intent = new Intent(ACTION_PERMISSION).setPackage(context.getPackageName());
        PendingIntent pending = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        JSONObject out = state("requesting");
        put(out, "ok", false);
        put(out, "device", deviceJson(device));
        put(out, "message", "tap Allow on the USB permission dialog, then wait");
        publish(out.toString());
        try {
            manager.requestPermission(device, pending);
        } catch (Throwable t) {
            unregisterPermission();
            finish(errorJson("could not request USB permission: " + t));
        }
    }

    private void unregisterPermission() {
        BroadcastReceiver receiver;
        synchronized (lock) {
            receiver = permissionReceiver;
            permissionReceiver = null;
        }
        if (receiver == null) return;
        try {
            context.unregisterReceiver(receiver);
        } catch (Throwable t) {
            Log.w(TAG, "could not unregister the USB permission receiver", t);
        }
    }

    private void measureAsync(final UsbManager manager, final UsbDevice device,
                              final String permission) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    measure(manager, device, permission);
                } catch (Throwable t) {
                    finish(errorJson("the probe failed: " + t));
                }
            }
        }, "jawn-usb-probe-measure");
        worker.setDaemon(true);
        worker.start();
    }

    // ----------------------------------------------------------------- measure

    private void measure(UsbManager manager, UsbDevice device, String permission) {
        JSONObject out = state("running");
        put(out, "ok", false);
        put(out, "device", deviceJson(device));
        put(out, "permission", permission);

        JSONArray interfaces = new JSONArray();
        List<UsbInterface> controlAlts = new ArrayList<>();
        List<UsbInterface> streamAlts = new ArrayList<>();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            interfaces.put(interfaceJson(iface));
            if (iface.getInterfaceClass() != AUDIO_CLASS) continue;
            if (iface.getInterfaceSubclass() == AUDIO_CONTROL_SUBCLASS) controlAlts.add(iface);
            else if (iface.getInterfaceSubclass() == AUDIO_STREAMING_SUBCLASS) streamAlts.add(iface);
        }
        put(out, "interfaces", interfaces);
        put(out, "audio", audioJson(controlAlts, streamAlts));
        publish(out.toString());

        JSONArray claims = new JSONArray();
        JSONArray altSets = new JSONArray();
        JSONArray setCur = new JSONArray();
        JSONArray getCur = new JSONArray();
        JSONArray released = new JSONArray();
        JSONArray errors = new JSONArray();
        boolean claimOk = false;
        boolean rate48000 = false;

        UsbDeviceConnection conn = null;
        try {
            conn = manager.openDevice(device);
            if (conn == null) {
                errors.put("openDevice returned null (permission refused or the device"
                        + " was removed)");
            } else {
                connection = conn;
                // AudioControl is claimed once, on its alt 0 object.
                if (!controlAlts.isEmpty()) {
                    claimOk |= claim(conn, preferredAlt(controlAlts), claims);
                }
                // AudioStreaming is one interface id with several alts; claim
                // the id once (alt 0 when present), then switch alts.
                Map<Integer, UsbInterface> streamById = new LinkedHashMap<>();
                for (UsbInterface alt : streamAlts) {
                    UsbInterface seen = streamById.get(alt.getId());
                    if (seen == null || seen.getAlternateSetting() != 0) {
                        streamById.put(alt.getId(), alt);
                    }
                }
                for (UsbInterface iface : streamById.values()) {
                    claimOk |= claim(conn, iface, claims);
                }
                // Select each streaming alt >= 1 that carries an isochronous
                // endpoint; that is what a real stream would use.
                for (UsbInterface alt : streamAlts) {
                    if (alt.getAlternateSetting() < 1 || !hasIsoEndpoint(alt)) continue;
                    boolean ok = false;
                    String error = null;
                    try {
                        ok = conn.setInterface(alt);
                    } catch (Throwable t) {
                        error = String.valueOf(t);
                    }
                    JSONObject row = new JSONObject();
                    put(row, "id", alt.getId());
                    put(row, "alt", alt.getAlternateSetting());
                    put(row, "ok", ok);
                    if (error != null) put(row, "error", error);
                    altSets.put(row);
                    if (!ok) errors.put("setInterface " + alt.getId() + "/"
                            + alt.getAlternateSetting() + ": refused");
                }
                // UAC1 sample rate, once per distinct isochronous endpoint.
                Map<Integer, Object[]> byAddress = new LinkedHashMap<>();
                for (UsbInterface alt : streamAlts) {
                    if (alt.getAlternateSetting() < 1) continue;
                    for (int e = 0; e < alt.getEndpointCount(); e++) {
                        UsbEndpoint ep = alt.getEndpoint(e);
                        if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_ISOC) continue;
                        if (!byAddress.containsKey(ep.getAddress())) {
                            byAddress.put(ep.getAddress(), new Object[] { alt, ep });
                        }
                    }
                }
                for (Object[] pair : byAddress.values()) {
                    rate48000 |= sendRate(conn, (UsbInterface) pair[0],
                            (UsbEndpoint) pair[1], setCur, getCur, errors);
                }
            }
        } catch (Throwable t) {
            errors.put("probe: " + t);
        } finally {
            if (conn != null) releaseAll(conn, released, errors);
            put(out, "claims", claims);
            put(out, "alt_sets", altSets);
            put(out, "set_cur", setCur);
            put(out, "get_cur", getCur);
            put(out, "released", released);
            put(out, "errors", errors);
            boolean ok = claimOk && rate48000;
            put(out, "ok", ok);
            put(out, "state", ok ? "done" : "failed");
            String path = OUT_PREFIX + stamp() + ".json";
            put(out, "file", path);
            if (!writeJson(out, path)) out.remove("file");
            finish(out.toString());
        }
    }

    private static UsbInterface preferredAlt(List<UsbInterface> alts) {
        for (UsbInterface iface : alts) {
            if (iface.getAlternateSetting() == 0) return iface;
        }
        return alts.get(0);
    }

    private static boolean hasIsoEndpoint(UsbInterface iface) {
        for (int e = 0; e < iface.getEndpointCount(); e++) {
            if (iface.getEndpoint(e).getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                return true;
            }
        }
        return false;
    }

    /**
     * Force-claim one interface. The force flag is the point: it asks usbfs to
     * disconnect whatever driver holds the interface (snd-usb-audio under the
     * Android USB audio HAL), which is what a direct path needs.
     */
    private boolean claim(UsbDeviceConnection conn, UsbInterface iface, JSONArray claims) {
        boolean ok = false;
        String error = null;
        try {
            ok = conn.claimInterface(iface, true);
        } catch (Throwable t) {
            error = String.valueOf(t);
        }
        JSONObject row = new JSONObject();
        put(row, "id", iface.getId());
        put(row, "interfaceClass", iface.getInterfaceClass());
        put(row, "interfaceSubclass", iface.getInterfaceSubclass());
        put(row, "alternateSetting", iface.getAlternateSetting());
        put(row, "force", true);
        put(row, "ok", ok);
        if (error != null) put(row, "error", error);
        claims.put(row);
        if (ok) {
            synchronized (lock) {
                claimed.add(iface);
            }
        }
        return ok;
    }

    /**
     * SET_CUR then GET_CUR for 48000 Hz on one isochronous endpoint. The
     * endpoint recipient is tried first; if the read does not come back, the
     * interface recipient (wIndex = interface id) is tried as the fallback some
     * UAC1 devices want.
     */
    private boolean sendRate(UsbDeviceConnection conn, UsbInterface iface, UsbEndpoint ep,
                             JSONArray setCur, JSONArray getCur, JSONArray errors) {
        int epAddress = ep.getAddress();
        byte[] wanted = { (byte) 0x80, (byte) 0xBB, 0x00 }; // 48000, little endian
        byte[] got = new byte[3];
        String setError = null;
        String getError = null;
        int setRet = -1;
        int getRet = -1;
        try {
            setRet = conn.controlTransfer(SET_ENDPOINT, SET_CUR, RATE_CONTROL,
                    epAddress, wanted, wanted.length, TIMEOUT_MS);
        } catch (Throwable t) {
            setError = String.valueOf(t);
        }
        try {
            getRet = conn.controlTransfer(GET_ENDPOINT, GET_CUR, RATE_CONTROL,
                    epAddress, got, got.length, TIMEOUT_MS);
        } catch (Throwable t) {
            getError = String.valueOf(t);
        }
        boolean interfaceRecipient = false;
        String endpointError = null;
        if (getRet < 3) {
            byte[] again = new byte[3];
            int setRet2 = -1;
            int getRet2 = -1;
            String setError2 = null;
            String getError2 = null;
            try {
                setRet2 = conn.controlTransfer(SET_INTERFACE, SET_CUR, RATE_CONTROL,
                        iface.getId(), wanted, wanted.length, TIMEOUT_MS);
            } catch (Throwable t) {
                setError2 = String.valueOf(t);
            }
            try {
                getRet2 = conn.controlTransfer(GET_INTERFACE, GET_CUR, RATE_CONTROL,
                        iface.getId(), again, again.length, TIMEOUT_MS);
            } catch (Throwable t) {
                getError2 = String.valueOf(t);
            }
            if (getRet2 >= 3) {
                setRet = setRet2;
                getRet = getRet2;
                got = again;
                setError = setError2;
                getError = getError2;
                interfaceRecipient = true;
            } else {
                endpointError = join(endpointError, "endpoint: " + getRet
                        + (getError == null ? "" : " " + getError));
                endpointError = join(endpointError, "interface: " + getRet2
                        + (getError2 == null ? "" : " " + getError2));
            }
        }
        JSONObject setRow = new JSONObject();
        put(setRow, interfaceRecipient ? "interface" : "endpoint",
                interfaceRecipient ? iface.getId() : epAddress);
        put(setRow, "return", setRet);
        put(setRow, "requested", RATE_48000);
        if (setError != null) put(setRow, "error", setError);
        setCur.put(setRow);

        int hz = getRet >= 3 ? decodeHz(got) : -1;
        JSONObject getRow = new JSONObject();
        put(getRow, interfaceRecipient ? "interface" : "endpoint",
                interfaceRecipient ? iface.getId() : epAddress);
        put(getRow, "return", getRet);
        put(getRow, "hz", hz);
        if (getError != null) put(getRow, "error", getError);
        getCur.put(getRow);
        if (endpointError != null) errors.put(endpointError);
        return getRet >= 3 && hz == RATE_48000;
    }

    /** Release each claim, then close; the phone's own audio comes back. */
    private void releaseAll(UsbDeviceConnection conn, JSONArray released, JSONArray errors) {
        List<UsbInterface> copy;
        synchronized (lock) {
            copy = new ArrayList<>(claimed);
            claimed.clear();
        }
        for (UsbInterface iface : copy) {
            boolean ok = false;
            String error = null;
            try {
                ok = conn.releaseInterface(iface);
            } catch (Throwable t) {
                error = String.valueOf(t);
            }
            JSONObject row = new JSONObject();
            put(row, "id", iface.getId());
            put(row, "ok", ok);
            if (error != null) put(row, "error", error);
            released.put(row);
            if (!ok) errors.put("releaseInterface " + iface.getId() + ": refused");
        }
        try {
            conn.close();
        } catch (Throwable t) {
            errors.put("close: " + t);
        }
        synchronized (lock) {
            if (connection == conn) connection = null;
        }
    }

    // ------------------------------------------------------------------- json

    private static JSONObject state(String name) {
        JSONObject out = new JSONObject();
        put(out, "state", name);
        return out;
    }

    private static JSONObject deviceJson(UsbDevice device) {
        JSONObject out = new JSONObject();
        if (device == null) return out;
        put(out, "vendorId", device.getVendorId());
        put(out, "productId", device.getProductId());
        put(out, "deviceName", String.valueOf(device.getDeviceName()));
        put(out, "interfaceCount", device.getInterfaceCount());
        return out;
    }

    private static JSONObject interfaceJson(UsbInterface iface) {
        JSONObject out = new JSONObject();
        put(out, "id", iface.getId());
        put(out, "interfaceClass", iface.getInterfaceClass());
        put(out, "interfaceSubclass", iface.getInterfaceSubclass());
        put(out, "alternateSetting", iface.getAlternateSetting());
        JSONArray endpoints = new JSONArray();
        for (int e = 0; e < iface.getEndpointCount(); e++) {
            UsbEndpoint ep = iface.getEndpoint(e);
            JSONObject row = new JSONObject();
            put(row, "address", ep.getAddress());
            put(row, "direction",
                    ep.getDirection() == UsbConstants.USB_DIR_IN ? "in" : "out");
            put(row, "type", endpointType(ep.getType()));
            put(row, "maxPacketSize", ep.getMaxPacketSize());
            put(row, "interval", ep.getInterval());
            endpoints.put(row);
        }
        put(out, "endpoints", endpoints);
        return out;
    }

    private static JSONObject audioJson(List<UsbInterface> controlAlts,
                                        List<UsbInterface> streamAlts) {
        JSONObject out = new JSONObject();
        put(out, "control", controlAlts.isEmpty() ? -1 : controlAlts.get(0).getId());
        JSONArray streaming = new JSONArray();
        List<Integer> seen = new ArrayList<>();
        for (UsbInterface iface : streamAlts) {
            if (!seen.contains(iface.getId())) {
                seen.add(iface.getId());
                streaming.put(iface.getId());
            }
        }
        put(out, "streaming", streaming);
        return out;
    }

    private static String endpointType(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL: return "control";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC: return "isochronous";
            case UsbConstants.USB_ENDPOINT_XFER_BULK: return "bulk";
            case UsbConstants.USB_ENDPOINT_XFER_INT: return "interrupt";
            default: return "type_" + type;
        }
    }

    private static int decodeHz(byte[] data) {
        return (data[0] & 0xff) | ((data[1] & 0xff) << 8) | ((data[2] & 0xff) << 16);
    }

    private static UsbDevice deviceFrom(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            }
            @SuppressWarnings("deprecation")
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            return device;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Join two failure notes without dropping either. */
    private static String join(String left, String right) {
        if (left == null) return right;
        return left + " | " + right;
    }

    private static void put(JSONObject out, String key, Object value) {
        try {
            out.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException ignored) {
            // A JSONObject cannot refuse a String key; nothing to do.
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    /** Best effort only: MANAGE_EXTERNAL_STORAGE is granted in this build. */
    private static boolean writeJson(JSONObject out, String path) {
        try (FileWriter writer = new FileWriter(new File(path), false)) {
            writer.write(out.toString(2));
            writer.write("\n");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not write " + path, t);
            return false;
        }
    }

    private void publish(String json) {
        synchronized (lock) {
            last = json;
        }
    }

    private void finish(String json) {
        synchronized (lock) {
            last = json;
            running = false;
        }
    }

    private static String errorJson(String message) {
        JSONObject out = state("error");
        put(out, "ok", false);
        put(out, "error", message);
        return out.toString();
    }
}
