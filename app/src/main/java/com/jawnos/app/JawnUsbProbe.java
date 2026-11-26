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
    /** The 48 kHz / 16-bit / 2-channel isochronous packet in a 1 ms frame. */
    private static final int RATE_OUT_PACKET = 192;
    private static final int RATE_IN_PACKET = 196;
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

    /**
     * True when libjawnaudio.so loaded, so {@link #nativeIsoProbe} exists. A
     * failure here must not stop the Java-only P0 probe; it only means the P1
     * isochronous question is reported as unanswered.
     */
    private static final boolean ISO_NATIVE;
    static {
        boolean loaded = false;
        try {
            System.loadLibrary("jawnaudio");
            loaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "libjawnaudio.so is not available: " + t);
        }
        ISO_NATIVE = loaded;
    }

    /**
     * The P1 gate: submit isochronous URBs on the connection's own usbfs fd.
     * Java's {@link android.hardware.usb.UsbRequest} cannot queue isochronous
     * transfers, so this is the only way to learn whether a direct USB audio
     * path is possible on this device. Returns JSON; never closes the fd.
     */
    private static native String nativeIsoProbe(int fd, int iface, int alt, int endpoint,
            int packetSize, int packetsPerUrb, int urbCount, int millis);

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
        JSONArray selected = new JSONArray();
        JSONArray attempts = new JSONArray();
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
                // Group each streaming interface's alternates, then select
                // the 48 kHz / 16-bit / 2-channel one ONCE and keep it active.
                // The P0 probe walked every alt in order, leaving OUT alt 6 /
                // IN alt 18 selected; the rate request is only valid on the
                // operational alt, so it was refused there.
                Map<Integer, List<UsbInterface>> altsById = new LinkedHashMap<>();
                for (UsbInterface alt : streamAlts) {
                    List<UsbInterface> list = altsById.get(alt.getId());
                    if (list == null) {
                        list = new ArrayList<>();
                        altsById.put(alt.getId(), list);
                    }
                    list.add(alt);
                }
                Map<Integer, UsbInterface> chosenById = new LinkedHashMap<>();
                Map<Integer, UsbInterface> zeroById = new LinkedHashMap<>();
                for (Map.Entry<Integer, UsbInterface> entry : streamById.entrySet()) {
                    int id = entry.getKey();
                    List<UsbInterface> alts = altsById.get(id);
                    if (alts == null) continue;
                    UsbInterface chosen = chooseRateAlt(alts);
                    if (chosen == null) {
                        errors.put("interface " + id + ": no isochronous alternate setting");
                        continue;
                    }
                    boolean ok = false;
                    String error = null;
                    try {
                        ok = conn.setInterface(chosen);
                    } catch (Throwable t) {
                        error = String.valueOf(t);
                    }
                    JSONObject row = new JSONObject();
                    put(row, "id", id);
                    put(row, "alt", chosen.getAlternateSetting());
                    put(row, "ok", ok);
                    if (error != null) put(row, "error", error);
                    altSets.put(row);
                    if (!ok) errors.put("setInterface " + id + "/"
                            + chosen.getAlternateSetting() + ": refused");
                    chosenById.put(id, chosen);
                    zeroById.put(id, altZero(alts));
                    selected.put(selectedJson(id, chosen));
                }
                // The UAC1 rate control runs at that alt for each isochronous
                // endpoint: endpoint recipient, interface recipient, alt-0
                // endpoint fallback. Every attempt keeps its raw bytes.
                for (Map.Entry<Integer, UsbInterface> entry : chosenById.entrySet()) {
                    int id = entry.getKey();
                    UsbInterface chosen = entry.getValue();
                    UsbInterface zero = zeroById.get(id);
                    for (int e = 0; e < chosen.getEndpointCount(); e++) {
                        UsbEndpoint ep = chosen.getEndpoint(e);
                        if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_ISOC) continue;
                        if (attemptRate(conn, id, chosen, zero, ep,
                                attempts, setCur, getCur, altSets, errors)) {
                            rate48000 = true;
                        }
                    }
                }
                // P1 gate: Java's UsbRequest cannot do isochronous transfers,
                // so hand the connection's fd to the native probe. It submits
                // iso URBs on the chosen AudioStreaming alt's IN endpoint.
                put(out, "usb_iso", isoProbe(conn, chosenById));
                // P2: the direct-USB round trip (a 6 ms chirp out and back)
                // on the chosen OUT/IN endpoints. It needs a patch cable
                // OUT->IN; a missing endpoint or a refused probe is a report
                // field and never aborts the rest of the probe.
                put(out, "usb_roundtrip", usbRoundTrip(conn, chosenById));
            }
        } catch (Throwable t) {
            errors.put("probe: " + t);
        } finally {
            if (conn != null) releaseAll(conn, released, errors);
            put(out, "claims", claims);
            put(out, "selected", selected);
            put(out, "alt_sets", altSets);
            put(out, "rate_attempts", attempts);
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
     * The alt whose isochronous endpoint carries the 48 kHz / 16-bit /
     * 2-channel packet: 192 bytes out, 196 in on a full-speed 1 ms frame. The
     * UM2's is alt 1. A device with neither size falls back to its first alt
     * that has an isochronous endpoint.
     */
    private static UsbInterface chooseRateAlt(List<UsbInterface> alts) {
        UsbInterface fallback = null;
        for (UsbInterface alt : alts) {
            if (alt.getAlternateSetting() < 1) continue;
            for (int e = 0; e < alt.getEndpointCount(); e++) {
                UsbEndpoint ep = alt.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_ISOC) continue;
                int wanted = ep.getDirection() == UsbConstants.USB_DIR_OUT
                        ? RATE_OUT_PACKET : RATE_IN_PACKET;
                if (ep.getMaxPacketSize() == wanted) return alt;
                if (fallback == null) fallback = alt;
            }
        }
        return fallback;
    }

    private static UsbInterface altZero(List<UsbInterface> alts) {
        for (UsbInterface alt : alts) {
            if (alt.getAlternateSetting() == 0) return alt;
        }
        return null;
    }

    private static JSONObject selectedJson(int id, UsbInterface chosen) {
        JSONObject row = new JSONObject();
        put(row, "interface", id);
        put(row, "alt", chosen.getAlternateSetting());
        JSONArray endpoints = new JSONArray();
        for (int e = 0; e < chosen.getEndpointCount(); e++) {
            UsbEndpoint ep = chosen.getEndpoint(e);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_ISOC) continue;
            JSONObject item = new JSONObject();
            put(item, "address", ep.getAddress());
            put(item, "direction",
                    ep.getDirection() == UsbConstants.USB_DIR_IN ? "in" : "out");
            put(item, "maxPacketSize", ep.getMaxPacketSize());
            endpoints.put(item);
        }
        put(row, "endpoints", endpoints);
        return row;
    }

    /**
     * The rate control for one endpoint at the selected alt: endpoint
     * recipient, then interface recipient, then the alt-0 endpoint fallback.
     * Each attempt records its alt, its wIndex and the raw GET_CUR bytes, so a
     * shifted or rejected answer is readable rather than guessed at.
     */
    private boolean attemptRate(UsbDeviceConnection conn, int ifaceId, UsbInterface chosen,
                                UsbInterface zero, UsbEndpoint ep, JSONArray attempts,
                                JSONArray setCur, JSONArray getCur, JSONArray altSets,
                                JSONArray errors) {
        int address = ep.getAddress();
        int alt = chosen.getAlternateSetting();
        if (attempt(conn, alt, address, ifaceId, "endpoint", attempts, setCur, getCur)) {
            return true;
        }
        if (attempt(conn, alt, address, ifaceId, "interface", attempts, setCur, getCur)) {
            return true;
        }
        if (zero == null) return false;
        boolean set = false;
        String error = null;
        try {
            set = conn.setInterface(zero);
        } catch (Throwable t) {
            error = String.valueOf(t);
        }
        JSONObject row = new JSONObject();
        put(row, "id", ifaceId);
        put(row, "alt", 0);
        put(row, "fallback", true);
        put(row, "ok", set);
        if (error != null) put(row, "error", error);
        altSets.put(row);
        boolean ok = attempt(conn, 0, address, ifaceId, "endpoint", attempts, setCur, getCur);
        try {
            conn.setInterface(chosen);
        } catch (Throwable t) {
            errors.put("setInterface " + ifaceId + "/" + alt + " (restore): " + t);
        }
        return ok;
    }

    /** One SET_CUR + GET_CUR pair at 48000 Hz; true when GET decodes 48000. */
    private boolean attempt(UsbDeviceConnection conn, int alt, int address, int ifaceId,
                            String recipient, JSONArray attempts, JSONArray setCur,
                            JSONArray getCur) {
        boolean interfaceRecipient = "interface".equals(recipient);
        int index = interfaceRecipient ? ifaceId : address;
        byte[] wanted = { (byte) 0x80, (byte) 0xBB, 0x00 }; // 48000, little endian
        byte[] got = new byte[3];
        int setRet = -1;
        int getRet = -1;
        String setError = null;
        String getError = null;
        try {
            setRet = conn.controlTransfer(interfaceRecipient ? SET_INTERFACE : SET_ENDPOINT,
                    SET_CUR, RATE_CONTROL, index, wanted, wanted.length, TIMEOUT_MS);
        } catch (Throwable t) {
            setError = String.valueOf(t);
        }
        try {
            getRet = conn.controlTransfer(interfaceRecipient ? GET_INTERFACE : GET_ENDPOINT,
                    GET_CUR, RATE_CONTROL, index, got, got.length, TIMEOUT_MS);
        } catch (Throwable t) {
            getError = String.valueOf(t);
        }
        int hz = getRet >= 3 ? decodeHz(got) : -1;
        String raw = hex(got);

        JSONObject entry = new JSONObject();
        put(entry, "recipient", recipient);
        put(entry, "alt", alt);
        put(entry, "interface", ifaceId);
        put(entry, "endpoint", address);
        put(entry, "index", index);
        put(entry, "set_return", setRet);
        put(entry, "get_return", getRet);
        put(entry, "raw", raw);
        put(entry, "hz", hz);
        put(entry, "requested", RATE_48000);
        if (setError != null) put(entry, "set_error", setError);
        if (getError != null) put(entry, "get_error", getError);
        attempts.put(entry);

        JSONObject setRow = new JSONObject();
        put(setRow, "alt", alt);
        put(setRow, "recipient", recipient);
        put(setRow, "interface", ifaceId);
        put(setRow, "endpoint", address);
        put(setRow, "return", setRet);
        if (setError != null) put(setRow, "error", setError);
        setCur.put(setRow);

        JSONObject getRow = new JSONObject();
        put(getRow, "alt", alt);
        put(getRow, "recipient", recipient);
        put(getRow, "interface", ifaceId);
        put(getRow, "endpoint", address);
        put(getRow, "return", getRet);
        put(getRow, "raw", raw);
        put(getRow, "hz", hz);
        if (getError != null) put(getRow, "error", getError);
        getCur.put(getRow);

        return hz == RATE_48000;
    }

    private static String hex(byte[] data) {
        if (data == null) return "";
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) out.append(String.format(Locale.US, "%02x", b));
        return out.toString();
    }

    /**
     * Run the native isochronous probe on the chosen streaming interface. The
     * interface id and the IN endpoint's address and packet size come from the
     * chosen {@link UsbInterface}, never from a hardcoded UM2 number.
     */
    private Object isoProbe(UsbDeviceConnection conn,
                            Map<Integer, UsbInterface> chosenById) {
        try {
            if (!ISO_NATIVE) {
                return isoFailure("libjawnaudio.so did not load");
            }
            int fd = conn.getFileDescriptor();
            if (fd < 0) {
                return isoFailure("the connection has no file descriptor");
            }
            UsbInterface chosen = null;
            UsbEndpoint in = null;
            for (UsbInterface iface : chosenById.values()) {
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint ep = iface.getEndpoint(e);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC
                            && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        chosen = iface;
                        in = ep;
                        break;
                    }
                }
                if (in != null) break;
            }
            if (chosen == null || in == null) {
                return isoFailure("no isochronous IN endpoint on the chosen alt");
            }
            String raw = nativeIsoProbe(fd, chosen.getId(), chosen.getAlternateSetting(),
                    in.getAddress(), in.getMaxPacketSize(), 8, 4, 300);
            try {
                return new JSONObject(raw);
            } catch (JSONException e) {
                return raw;
            }
        } catch (Throwable t) {
            return isoFailure(String.valueOf(t));
        }
    }

    private static JSONObject isoFailure(String message) {
        JSONObject out = new JSONObject();
        put(out, "ok", false);
        put(out, "error", message);
        return out;
    }

    /**
     * The direct-USB round trip on the chosen streaming interfaces: a 6 ms
     * chirp on the first chosen isochronous OUT endpoint, captured on the
     * first chosen isochronous IN endpoint. Both come from the chosen alts,
     * never from a hardcoded UM2 interface or endpoint number. The engine
     * blocks for about 1.5 s and never closes the fd; the caller's finally
     * still releases the claims. A missing endpoint is a sentence in the
     * report, never an exception.
     */
    private Object usbRoundTrip(UsbDeviceConnection conn,
                                Map<Integer, UsbInterface> chosenById) {
        try {
            UsbInterface inIface = null;
            UsbEndpoint in = null;
            UsbInterface outIface = null;
            UsbEndpoint out = null;
            for (UsbInterface iface : chosenById.values()) {
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint ep = iface.getEndpoint(e);
                    if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_ISOC) continue;
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        if (in == null) {
                            inIface = iface;
                            in = ep;
                        }
                    } else if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                        if (out == null) {
                            outIface = iface;
                            out = ep;
                        }
                    }
                }
            }
            if (in == null || inIface == null) {
                return isoFailure("no isochronous audio input on the chosen alt");
            }
            if (out == null || outIface == null) {
                return isoFailure("no isochronous audio output on the chosen alt");
            }
            int fd = conn.getFileDescriptor();
            if (fd < 0) return isoFailure("the connection has no file descriptor");
            String raw = JawnAudioEngine.roundTripUsb(fd,
                    inIface.getId(), inIface.getAlternateSetting(),
                    in.getAddress(), in.getMaxPacketSize(),
                    outIface.getId(), outIface.getAlternateSetting(),
                    out.getAddress(), out.getMaxPacketSize(),
                    1500);
            try {
                return new JSONObject(raw);
            } catch (JSONException e) {
                return raw;
            }
        } catch (Throwable t) {
            return isoFailure("the USB round trip failed: " + t);
        }
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
