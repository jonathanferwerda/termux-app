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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The native USB take: hold a {@link UsbDeviceConnection} for the whole take
 * and hand its file descriptor to the engine's direct-USB backend
 * ({@code jawn_audio_start_usb}).
 *
 * Unlike {@link JawnUsbProbe}, which claims, measures and always releases, this
 * holder keeps the connection open until {@link #stop()} - the engine reads and
 * writes isochronous URBs through the same usbfs fd for the whole take. It is
 * deliberately device-agnostic: no vendor, product or interface number is
 * hardcoded. A device qualifies when it has an AudioStreaming interface with an
 * isochronous IN endpoint; the 48 kHz operational alternate is the one whose
 * packet is 196 bytes in / 192 bytes out (48 kHz / 16-bit / 2-channel in a 1 ms
 * frame), else the first alternate with an isochronous endpoint of that
 * direction.
 *
 * One take at a time. {@link #start(String, String)} returns at once and does
 * the USB permission request, open, claim and alt selection on a worker thread;
 * {@link #stop()} ends the engine first (it joins the USB worker), then releases
 * every claim and closes the connection, and writes the final report to
 * {@code /sdcard/Download/} with the take's WAV path so the take is verifiable
 * without the app.
 */
public class JawnUsbAudio {

    private static final String TAG = "JawnUsbAudio";

    /** The process-wide holder; every caller goes through {@link #shared}. */
    private static volatile JawnUsbAudio shared;

    /** The dynamic permission action; the PendingIntent is package-scoped. */
    static final String ACTION_PERMISSION = "com.jawnos.app.USB_TAKE_PERMISSION";

    private static final int AUDIO_CLASS = UsbConstants.USB_CLASS_AUDIO;
    private static final int AUDIO_CONTROL_SUBCLASS = 1;
    private static final int AUDIO_STREAMING_SUBCLASS = 2;

    /** The 48 kHz / 16-bit / 2-channel isochronous packet in a 1 ms frame. */
    private static final int RATE_IN_PACKET = 196;
    private static final int RATE_OUT_PACKET = 192;

    private static final String REPORT_PREFIX = "/sdcard/Download/jawnos-usb-take-";

    private final Context context;

    /** Guards the state a caller can see: active/holding, last and the receiver. */
    private final Object stateLock = new Object();
    /** Serialises open/claim/start against stop, so a stop never races a claim. */
    private final Object sessionLock = new Object();

    private volatile boolean cancelled = false;
    private boolean active = false;
    private boolean holding = false;
    /** One-shot: true once this take's teardown (a stop or its deadline) was claimed. */
    private boolean finalized = false;
    private String last = "{\"ok\":false,\"state\":\"idle\"}";
    private String takePath = "";
    private String takeSeconds = "0";
    private String takePlan = "";
    private BroadcastReceiver permissionReceiver;

    /** The held connection and its claims; touched only under {@link #sessionLock}. */
    private UsbDeviceConnection connection;
    private final List<UsbInterface> claimed = new ArrayList<>();

    /**
     * One holder for the whole process. The Studio bridge and the foreground
     * service that anchors a USB take must act on the same
     * {@link UsbDeviceConnection}: a second holder could open its own
     * connection to the same device and fail the forced claim. Construction is
     * therefore private and every caller reaches the holder through here.
     */
    public static JawnUsbAudio shared(Context context) {
        JawnUsbAudio holder = shared;
        if (holder == null) {
            synchronized (JawnUsbAudio.class) {
                holder = shared;
                if (holder == null) {
                    holder = new JawnUsbAudio(context.getApplicationContext());
                    shared = holder;
                }
            }
        }
        return holder;
    }

    private JawnUsbAudio(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Begin a native USB take and return what it is doing now. The permission
     * request, the open, the force-claims and the alt selection all happen on a
     * worker thread, so this never blocks the WebView. A second start while a
     * take is live answers a sentence instead of opening a second stream.
     */
    public String start(String seconds, String plan) {
        synchronized (stateLock) {
            if (active) {
                return errorJson("a USB take is already running; stop it first");
            }
            active = true;
            cancelled = false;
            finalized = false;
            takeSeconds = seconds == null ? "0" : seconds.trim();
            takePlan = plan == null ? "" : plan;
            takePath = newTakePath();
            JSONObject out = state("detecting");
            put(out, "ok", true);
            put(out, "take", takePath);
            last = out.toString();
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runStart();
                } catch (Throwable t) {
                    finish(errorJson("the USB take failed: " + t));
                }
            }
        }, "jawn-usb-take");
        worker.setDaemon(true);
        worker.start();
        return status();
    }

    /**
     * End the take: the engine is stopped first (it joins its USB worker and
     * closes the WAV), then every claim is released and the connection closed.
     * The final report is written beside the take so it can be read without the
     * app. Safe when idle.
     */
    public String stop() {
        synchronized (stateLock) {
            if (!active && !holding) return last;
            cancelled = true;
        }
        unregisterPermission();
        return finishTake(true, false);
    }

    /**
     * The one-shot teardown shared by a user stop and the deadline watch. The
     * first caller wins; a concurrent second call waits on {@link #sessionLock}
     * and then returns the report the first one published, so a tap on Stop as
     * the take expires can never finalize twice.
     *
     * @param engineStop true on a user stop: the engine is stopped before any
     *                   claim is released, the order this holder always used.
     *                   False when the engine already produced its final
     *                   report on its own at the deadline.
     * @param stopAnchor true on the deadline path, which has no bridge behind
     *                   it to drop the foreground service afterwards.
     */
    private String finishTake(boolean engineStop, boolean stopAnchor) {
        String answer;
        boolean held;
        synchronized (sessionLock) {
            synchronized (stateLock) {
                if (finalized) return last;
                finalized = true;
                held = holding;
            }
            if (!held) {
                // The take never reached the engine (stopped while detecting
                // or requesting permission); there is no report to write.
                synchronized (stateLock) {
                    holding = false;
                    active = false;
                }
                return last;
            }
            String report;
            if (engineStop) {
                try {
                    report = JawnAudioEngine.stop();
                } catch (Throwable t) {
                    report = errorJson("the audio engine could not stop: " + t);
                }
            } else {
                // The deadline report is already final: the engine's worker is
                // done with the fd once its status stops saying "started".
                try {
                    report = JawnAudioEngine.status();
                } catch (Throwable t) {
                    report = errorJson("the audio engine could not report: " + t);
                }
            }
            releaseAll();
            // Write the report while active is still true: a new start must be
            // refused until this take's path and file are settled.
            answer = finalizeReport(report, takePath);
            synchronized (stateLock) {
                holding = false;
                active = false;
                last = answer;
            }
        }
        if (stopAnchor) stopAnchorIfIdle(0);
        return answer;
    }

    /**
     * Watch a held take for its own deadline. A USB take with {@code seconds}
     * &gt; 0 stops its worker at the deadline and leaves the final report in the
     * engine's status; this thread notices that report and runs the same
     * release-and-close as a user stop, off the main thread because the status
     * read and the release can block.
     */
    private void startExpiryWatch() {
        Thread watch = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    synchronized (stateLock) {
                        if (!holding || finalized) return;
                    }
                    String now;
                    try {
                        now = JawnAudioEngine.status();
                    } catch (Throwable t) {
                        now = null;
                    }
                    if (now != null && isFinalReport(now)) {
                        finishTake(false, true);
                        return;
                    }
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException stop) {
                        return;
                    }
                }
            }
        }, "jawn-usb-expiry");
        try {
            watch.setDaemon(true);
            watch.start();
        } catch (Throwable t) {
            // The take is already holding; never let a watch failure reach the
            // worker's catch and finalize a live take.
            Log.w(TAG, "could not start the deadline watch", t);
        }
    }

    /**
     * True when the engine's JSON is a final report rather than a live take:
     * no {@code state} at all (the USB report has none) or any state other than
     * the four the engine uses while it is coming up or running.
     */
    private static boolean isFinalReport(String json) {
        try {
            JSONObject answer = new JSONObject(json == null ? "" : json);
            String state = answer.optString("state", "");
            return !("started".equals(state) || "detecting".equals(state)
                    || "opening".equals(state) || "requesting".equals(state));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The engine's live status while the take is held, else the last answer
     * this holder published (detecting, requesting, opened, or the report).
     */
    public String status() {
        boolean held;
        String fallback;
        synchronized (stateLock) {
            held = holding;
            fallback = last;
        }
        if (held) {
            try {
                String live = JawnAudioEngine.status();
                if (live != null && !live.isEmpty()) return live;
            } catch (Throwable t) {
                Log.w(TAG, "could not read the engine status", t);
            }
        }
        return fallback;
    }

    /**
     * The attached USB audio device this holder would claim, without opening
     * or claiming anything. Qualification is exactly {@link #findDevice}'s: an
     * AudioStreaming interface with an isochronous IN endpoint. Answers the
     * device and whether the app already holds its USB permission, or the same
     * sentence a start would give when none is attached.
     */
    public String describe() {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) return errorJson("this build cannot see a UsbManager");
        UsbDevice device = findDevice(manager);
        if (device == null) return errorJson("no USB audio input is attached");
        JSONObject out = state("attached");
        put(out, "ok", true);
        JSONObject row = deviceJson(device);
        put(row, "permission", manager.hasPermission(device));
        put(out, "device", row);
        return out.toString();
    }

    /**
     * True while a take is being set up or is holding the connection. The
     * foreground service reads this in {@code onDestroy} to decide whether it
     * still has a take to end.
     */
    public boolean isLive() {
        synchronized (stateLock) {
            return active || holding;
        }
    }

    // ------------------------------------------------------------------ start

    private void runStart() {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            finish(errorJson("this build cannot see a UsbManager"));
            return;
        }
        UsbDevice device = findDevice(manager);
        if (device == null) {
            finish(errorJson("no USB audio input is attached"));
            return;
        }
        if (!manager.hasPermission(device)) {
            askPermission(manager, device);
            return;
        }
        openAndStart(manager, device);
    }

    /**
     * The first attached device with an AudioStreaming interface that carries
     * an isochronous IN endpoint. Nothing is matched by vendor/product, so any
     * USB audio device works; when several qualify, the first one wins.
     */
    private static UsbDevice findDevice(UsbManager manager) {
        try {
            Map<String, UsbDevice> list = manager.getDeviceList();
            if (list == null) return null;
            for (UsbDevice device : list.values()) {
                if (hasIsoIn(device)) return device;
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not list USB devices", t);
        }
        return null;
    }

    private static boolean hasIsoIn(UsbDevice device) {
        if (device == null) return false;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() != AUDIO_CLASS) continue;
            if (iface.getInterfaceSubclass() != AUDIO_STREAMING_SUBCLASS) continue;
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC
                        && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    return true;
                }
            }
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
                final UsbDevice target = extra != null ? extra : device;
                unregisterPermission();
                if (!granted) {
                    finish(errorJson("USB permission was not granted"));
                    return;
                }
                if (cancelled) {
                    finish(errorJson("the USB take was stopped"));
                    return;
                }
                // The open and claim can block; keep them off this thread.
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            openAndStart(manager, target);
                        } catch (Throwable t) {
                            finish(errorJson("the USB take failed: " + t));
                        }
                    }
                }, "jawn-usb-take-open");
                worker.setDaemon(true);
                worker.start();
            }
        };
        synchronized (stateLock) {
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
        put(out, "ok", true);
        put(out, "device", deviceJson(device));
        put(out, "take", takePath);
        put(out, "message", "tap Allow on the USB permission dialog");
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
        synchronized (stateLock) {
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

    // ------------------------------------------------------------- open + start

    private void openAndStart(UsbManager manager, UsbDevice device) {
        synchronized (sessionLock) {
            if (cancelled) return;

            JSONObject opening = state("opening");
            put(opening, "ok", false);
            put(opening, "device", deviceJson(device));
            put(opening, "take", takePath);
            publish(opening.toString());

            UsbDeviceConnection conn;
            try {
                conn = manager.openDevice(device);
            } catch (Throwable t) {
                finish(errorJson("could not open the USB device: " + t));
                return;
            }
            if (conn == null) {
                finish(errorJson("could not open the USB device (permission refused"
                        + " or it was removed)"));
                return;
            }
            connection = conn;
            if (cancelled) {
                releaseAll();
                finish(errorJson("the USB take was stopped"));
                return;
            }

            List<UsbInterface> controlAlts = new ArrayList<>();
            List<UsbInterface> streamAlts = new ArrayList<>();
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                if (iface.getInterfaceClass() != AUDIO_CLASS) continue;
                if (iface.getInterfaceSubclass() == AUDIO_CONTROL_SUBCLASS) {
                    controlAlts.add(iface);
                } else if (iface.getInterfaceSubclass() == AUDIO_STREAMING_SUBCLASS) {
                    streamAlts.add(iface);
                }
            }

            // Force-claim AudioControl (alt 0) and each AudioStreaming
            // interface (alt 0 object). The force flag detaches the kernel's
            // snd-usb-audio / the Android USB audio HAL from what the engine
            // will drive.
            if (!controlAlts.isEmpty()) claim(conn, preferredAlt(controlAlts));
            Map<Integer, List<UsbInterface>> altsById = new LinkedHashMap<>();
            for (UsbInterface alt : streamAlts) {
                List<UsbInterface> list = altsById.get(alt.getId());
                if (list == null) {
                    list = new ArrayList<>();
                    altsById.put(alt.getId(), list);
                }
                list.add(alt);
            }
            for (Map.Entry<Integer, List<UsbInterface>> entry : altsById.entrySet()) {
                claim(conn, altZero(entry.getValue()));
            }

            // The 48 kHz operational alternate: 196-byte IN / 192-byte OUT
            // isochronous packet, else the first alternate with an isochronous
            // endpoint of that direction. The endpoint's own address and packet
            // size are what the engine receives.
            Pick in = null;
            Pick out = null;
            for (Map.Entry<Integer, List<UsbInterface>> entry : altsById.entrySet()) {
                if (in == null) {
                    in = pickAlt(entry.getValue(), UsbConstants.USB_DIR_IN, RATE_IN_PACKET);
                }
                if (out == null) {
                    out = pickAlt(entry.getValue(), UsbConstants.USB_DIR_OUT, RATE_OUT_PACKET);
                }
            }
            if (in == null) {
                releaseAll();
                finish(errorJson("the USB device has no isochronous audio input"));
                return;
            }
            // One interface carries one active alt. If the output is on the
            // same interface id at a different alt, capture wins: keep the
            // output only when the chosen alt also carries it.
            if (out != null && out.iface.getId() == in.iface.getId()
                    && out.iface.getAlternateSetting() != in.iface.getAlternateSetting()) {
                UsbEndpoint same = isoEndpoint(in.iface, UsbConstants.USB_DIR_OUT);
                out = same == null ? null : new Pick(in.iface, same);
            }

            if (!setInterface(conn, in.iface)) {
                releaseAll();
                finish(errorJson("the USB capture alternate setting was refused"));
                return;
            }
            if (out != null && out.iface.getId() != in.iface.getId()) {
                if (!setInterface(conn, out.iface)) {
                    releaseAll();
                    finish(errorJson("the USB playback alternate setting was refused"));
                    return;
                }
            }

            int fd = conn.getFileDescriptor();
            if (fd < 0) {
                releaseAll();
                finish(errorJson("the USB connection has no file descriptor"));
                return;
            }

            int ifaceOut = -1;
            int altOut = -1;
            int epOut = -1;
            int packetOut = 0;
            if (out != null) {
                ifaceOut = out.iface.getId();
                altOut = out.iface.getAlternateSetting();
                epOut = out.ep.getAddress();
                packetOut = out.ep.getMaxPacketSize();
            }

            String seconds;
            String plan;
            String path;
            synchronized (stateLock) {
                seconds = takeSeconds;
                plan = takePlan;
                path = takePath;
            }
            double secs = parseSeconds(seconds);

            if (cancelled) {
                releaseAll();
                finish(errorJson("the USB take was stopped"));
                return;
            }

            String answer = JawnAudioEngine.usbStart(fd,
                    in.iface.getId(), in.iface.getAlternateSetting(),
                    in.ep.getAddress(), in.ep.getMaxPacketSize(),
                    ifaceOut, altOut, epOut, packetOut,
                    secs, path, plan);

            if (!JawnAudioEngine.running(answer)) {
                releaseAll();
                finish(answer);
                return;
            }

            JSONObject started;
            try {
                started = new JSONObject(answer);
            } catch (JSONException e) {
                started = state("started");
            }
            put(started, "ok", true);
            put(started, "take", path);
            put(started, "seconds", secs);
            put(started, "input", selectedJson(in));
            if (out != null) put(started, "output", selectedJson(out));
            synchronized (stateLock) {
                holding = true;
                last = started.toString();
            }
        }
        // A held take can reach its own seconds deadline; the watch finalizes
        // it outside these locks.
        startExpiryWatch();
    }

    /** Force-claim one interface; the force flag is what detaches the HAL. */
    private void claim(UsbDeviceConnection conn, UsbInterface iface) {
        if (iface == null) return;
        boolean ok;
        try {
            ok = conn.claimInterface(iface, true);
        } catch (Throwable t) {
            Log.w(TAG, "claimInterface " + iface.getId() + " failed: " + t);
            return;
        }
        if (ok) {
            claimed.add(iface);
        } else {
            Log.w(TAG, "claimInterface " + iface.getId() + " was refused");
        }
    }

    private static boolean setInterface(UsbDeviceConnection conn, UsbInterface iface) {
        try {
            return conn.setInterface(iface);
        } catch (Throwable t) {
            Log.w(TAG, "setInterface " + iface.getId() + "/"
                    + iface.getAlternateSetting() + " failed: " + t);
            return false;
        }
    }

    /**
     * Release every claim, then close. The caller holds {@link #sessionLock}.
     * Best effort: the phone's own audio returns once the interfaces are free.
     */
    private void releaseAll() {
        UsbDeviceConnection conn = connection;
        if (conn != null) {
            for (UsbInterface iface : claimed) {
                try {
                    conn.releaseInterface(iface);
                } catch (Throwable t) {
                    Log.w(TAG, "releaseInterface " + iface.getId() + " failed: " + t);
                }
            }
        }
        claimed.clear();
        if (conn != null) {
            try {
                conn.close();
            } catch (Throwable t) {
                Log.w(TAG, "connection.close failed: " + t);
            }
        }
        connection = null;
    }

    // ------------------------------------------------------------ alt selection

    private static UsbInterface preferredAlt(List<UsbInterface> alts) {
        for (UsbInterface iface : alts) {
            if (iface.getAlternateSetting() == 0) return iface;
        }
        return alts.get(0);
    }

    private static UsbInterface altZero(List<UsbInterface> alts) {
        for (UsbInterface iface : alts) {
            if (iface.getAlternateSetting() == 0) return iface;
        }
        return null;
    }

    private static UsbEndpoint isoEndpoint(UsbInterface iface, int direction) {
        for (int e = 0; e < iface.getEndpointCount(); e++) {
            UsbEndpoint ep = iface.getEndpoint(e);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC
                    && ep.getDirection() == direction) {
                return ep;
            }
        }
        return null;
    }

    /**
     * The alt whose isochronous endpoint carries {@code packet} bytes in this
     * direction, else the first alt with an isochronous endpoint of the
     * direction. Alt 0 is the zero-bandwidth setting on most devices, so it is
     * only a last resort if it somehow carries the endpoint.
     */
    private static Pick pickAlt(List<UsbInterface> alts, int direction, int packet) {
        UsbInterface fallback = null;
        UsbEndpoint fallbackEp = null;
        UsbInterface zero = null;
        UsbEndpoint zeroEp = null;
        for (UsbInterface alt : alts) {
            UsbEndpoint ep = isoEndpoint(alt, direction);
            if (ep == null) continue;
            if (alt.getAlternateSetting() < 1) {
                if (zeroEp == null) {
                    zero = alt;
                    zeroEp = ep;
                }
                continue;
            }
            if (ep.getMaxPacketSize() == packet) return new Pick(alt, ep);
            if (fallbackEp == null) {
                fallback = alt;
                fallbackEp = ep;
            }
        }
        if (fallbackEp != null) return new Pick(fallback, fallbackEp);
        if (zeroEp != null) return new Pick(zero, zeroEp);
        return null;
    }

    private static JSONObject selectedJson(Pick pick) {
        JSONObject row = new JSONObject();
        put(row, "id", pick.iface.getId());
        put(row, "alt", pick.iface.getAlternateSetting());
        put(row, "endpoint", pick.ep.getAddress());
        put(row, "packet", pick.ep.getMaxPacketSize());
        put(row, "direction",
                pick.ep.getDirection() == UsbConstants.USB_DIR_IN ? "in" : "out");
        return row;
    }

    private static final class Pick {
        final UsbInterface iface;
        final UsbEndpoint ep;

        Pick(UsbInterface iface, UsbEndpoint ep) {
            this.iface = iface;
            this.ep = ep;
        }
    }

    // -------------------------------------------------------------------- json

    /** The take's WAV in app-private storage, beside the HAL take's files. */
    private String newTakePath() {
        File dir = new File(context.getFilesDir(), "studio");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "jawnos-usb-take-" + stamp() + ".wav").getAbsolutePath();
    }

    /**
     * Attach the take path and write the report to Download, so the take is
     * verifiable without the app. Best effort: the report is returned even when
     * the write fails (its {@code file} key is dropped then).
     */
    private String finalizeReport(String report, String take) {
        JSONObject found;
        try {
            found = new JSONObject(report == null ? "" : report);
        } catch (Throwable t) {
            return report == null ? "" : report;
        }
        put(found, "take", take == null || take.isEmpty() ? null : take);
        String file = REPORT_PREFIX + stamp() + ".json";
        put(found, "file", file);
        if (!writeJson(found.toString(), file)) found.remove("file");
        return found.toString();
    }

    private static boolean writeJson(String text, String path) {
        try (FileWriter writer = new FileWriter(new File(path), false)) {
            writer.write(text);
            writer.write("\n");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not write " + path, t);
            return false;
        }
    }

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

    private static double parseSeconds(String text) {
        if (text == null) return 0d;
        try {
            double value = Double.parseDouble(text.trim());
            if (Double.isNaN(value) || Double.isInfinite(value)) return 0d;
            return value;
        } catch (Throwable ignored) {
            return 0d;
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

    private void publish(String json) {
        synchronized (stateLock) {
            last = json;
        }
    }

    private void finish(String json) {
        synchronized (stateLock) {
            last = json;
            holding = false;
            active = false;
        }
        // A setup that never reached the engine leaves nothing for the anchor
        // to keep alive: drop it so a notification saying "USB take running"
        // and its wake lock do not outlive the attempt. Re-check liveness just
        // after, so a take that starts in the same instant keeps its anchor.
        stopAnchorIfIdle(2000);
    }

    /**
     * Drop the USB anchor unless a take is already live again, so an ending
     * take can never cancel the anchor of one that starts in the same instant.
     */
    private void stopAnchorIfIdle(long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isLive()) JawnUsbAudioService.stop(context);
            }
        }, delayMs);
    }

    private static String errorJson(String message) {
        JSONObject out = state("error");
        put(out, "ok", false);
        put(out, "error", message);
        return out.toString();
    }
}
