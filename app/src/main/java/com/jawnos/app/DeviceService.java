package com.jawnos.app;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.Image;
import android.media.ImageReader;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.content.IntentFilter;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.graphics.ImageFormat;
import android.os.BatteryManager;
import android.os.ParcelFileDescriptor;
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.database.Cursor;
import android.os.Bundle;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.telephony.TelephonyManager;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.media.AudioManager;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.core.content.FileProvider;

import com.jawnos.R;

/**
 * M1 device bridge (docs/leaving-termux.md): the app's own answer to the
 * termux-* commands, so the API app can retire one call at a time.
 *
 * Loopback TCP, newline-delimited JSON, one request id per call - the shape the
 * jawn-bt bridge already proved. A filesystem unix socket would need JNI for the
 * listening side (android.net.LocalServerSocket binds the abstract namespace
 * only), and loopback is what the existing bridge uses, so this follows it and
 * adds the one thing jawn-bt lacks: a token. The app writes
 * $HOME/.jawn/device.token (mode 600) and the app and Termux share one uid, so
 * only jawn can read it; a stranger on loopback gets "denied" and nothing else.
 *
 * Started with jawn (JawnLauncher) and stopped with it (Stop), like the BLE
 * bridge. Every method it grows here replaces one termux-* command; the Perl
 * side calls it through subs::device_call and keeps the command as its fallback
 * until the new path is proven on a phone.
 */
public class DeviceService extends Service {

    private static final String LOG_TAG = "JawnDeviceService";
    public static final int PORT = 46000;
    private static final int NOTIFICATION_ID = 46001;
    private static final String CHANNEL_ID = "jawn-device";
    private static final String TOKEN_NAME = "device.token";
    private static final String CHANNEL_DEFAULT = "jawn_notifications";
    private static final String CHANNEL_SILENT = "jawn_notifications_silent";
    private static final int MAX_CLIENTS = 8;

    private volatile boolean running = true;
    private ServerSocket serverSocket;
    private Thread acceptor;
    private ExecutorService clients;
    private String token = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundCompat(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        token = ensureToken();
        clients = Executors.newFixedThreadPool(MAX_CLIENTS);
        acceptor = new Thread(this::acceptLoop, "jawn-device-accept");
        acceptor.start();
        Log.i(LOG_TAG, "device bridge listening on 127.0.0.1:" + PORT);
    }

    /** Start the bridge notification with one explicit foreground type.
     *
     *  targetSdk 34+ checks every type named at the moment the service turns
     *  foreground. The two-argument startForeground() claims EVERY type the
     *  manifest declares for this service, which includes microphone and
     *  camera; without those runtime grants Android 16 kills the process with
     *  "Starting FGS with type microphone ... requires permissions". The bridge
     *  only needs dataSync to exist, so it says so, and widens the type for the
     *  length of a capture.
     */
    private void startForegroundCompat(int type) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), type);
            }
            else {
                startForeground(NOTIFICATION_ID, notification());
            }
        } catch (Throwable t) {
            Log.w(LOG_TAG, "startForeground(type=" + type + ") failed", t);
        }
    }

    /** Add camera or microphone for the duration of a capture, but only when
     *  the matching runtime grant is held. Returns true when it was applied. */
    private boolean escalateForeground(int type, String permission) {
        if (Build.VERSION.SDK_INT < 30) return false;
        try {
            if (permission != null && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            startForeground(NOTIFICATION_ID, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE | type);
            return true;
        } catch (Throwable t) {
            Log.w(LOG_TAG, "foreground type escalation failed", t);
            return false;
        }
    }

    private void deescalateForeground() {
        startForegroundCompat(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: a tree that outlives an app process restart still finds a
        // bridge to dial, and jawn already treats a missing bridge as a fallback.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable ignored) {}
        if (clients != null) clients.shutdownNow();
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        releaseMedia();
        stopRecording();
        Log.i(LOG_TAG, "device bridge stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- accept loop ------------------------------------------------------

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), MAX_CLIENTS);
            while (running) {
                final Socket client = serverSocket.accept();
                try {
                    clients.execute(() -> serve(client));
                } catch (Throwable t) {
                    closeQuietly(client);
                }
            }
        } catch (Throwable t) {
            if (running) Log.w(LOG_TAG, "accept loop ended", t);
        }
    }

    /** One connection carries many calls; the client keeps it open. */
    private void serve(Socket client) {
        try (Socket socket = client;
             BufferedReader in = new BufferedReader(
                 new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {
            socket.setSoTimeout(60000);
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JSONObject reply = handle(line);
                out.write((reply.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Throwable ignored) {
            // a client that went away is not an error
        }
    }

    private JSONObject handle(String line) {
        long id = 0;
        try {
            JSONObject request = new JSONObject(line);
            id = request.optLong("id", 0);
            JSONObject reply = new JSONObject();
            reply.put("id", id);
            if (!token.equals(request.optString("token", ""))) {
                reply.put("ok", false);
                reply.put("error", "denied");
                return reply;
            }
            String method = request.optString("method", "");
            if ("ping".equals(method)) {
                JSONObject result = new JSONObject();
                result.put("app", getPackageName());
                result.put("sdk", Build.VERSION.SDK_INT);
                result.put("pid", android.os.Process.myPid());
                reply.put("ok", true);
                reply.put("result", result);
            }
            else if ("media.thumbnail".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                String want = args == null ? "" : args.optString("path", "");
                int max = args == null ? 256 : args.optInt("max", 256);
                long atMs = args == null ? -1 : args.optLong("at_ms", -1);
                JSONObject made = mediaThumbnail(want, max, atMs);
                if (made == null) {
                    reply.put("ok", false);
                    reply.put("error", "no thumbnail for " + want);
                }
                else {
                    reply.put("ok", true);
                    reply.put("result", made);
                }
            }
            else if ("image.resize".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                JSONObject made = imageResize(args == null ? new JSONObject() : args);
                if (made == null) {
                    reply.put("ok", false);
                    reply.put("error", "could not resize the image");
                }
                else {
                    reply.put("ok", true);
                    reply.put("result", made);
                }
            }
            else if ("image.save".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                JSONObject made = imageSave(args == null ? new JSONObject() : args);
                if (made == null) {
                    reply.put("ok", false);
                    reply.put("error", "could not save the image");
                }
                else {
                    reply.put("ok", true);
                    reply.put("result", made);
                }
            }
            else if ("image.edit".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                JSONObject made = imageEdit(args == null ? new JSONObject() : args);
                if (made == null) {
                    reply.put("ok", false);
                    reply.put("error", "could not edit the image");
                }
                else {
                    reply.put("ok", true);
                    reply.put("result", made);
                }
            }
            else if ("image.cover".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                JSONObject made = imageCover(args == null ? new JSONObject() : args);
                if (made == null) {
                    reply.put("ok", false);
                    reply.put("error", "could not cover-crop the image");
                }
                else {
                    reply.put("ok", true);
                    reply.put("result", made);
                }
            }
            else if ("image.identify".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                JSONObject made = imageIdentify(args == null ? new JSONObject() : args);
                if (made == null) {
                    reply.put("ok", false);
                    reply.put("error", "could not identify the image");
                }
                else {
                    reply.put("ok", true);
                    reply.put("result", made);
                }
            }
            else if ("image.convert".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                JSONObject made = imageConvert(args == null ? new JSONObject() : args);
                if (made == null) {
                    reply.put("ok", false);
                    reply.put("error", "could not convert the image");
                }
                else {
                    reply.put("ok", true);
                    reply.put("result", made);
                }
            }
            else if ("image.gray".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                JSONObject made = imageGray(args == null ? new JSONObject() : args);
                if (made == null) {
                    reply.put("ok", false);
                    reply.put("error", "could not read the image as gray");
                }
                else {
                    reply.put("ok", true);
                    reply.put("result", made);
                }
            }
            else if ("image.normalize".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                JSONObject made = imageNormalize(args == null ? new JSONObject() : args);
                if (made == null) {
                    reply.put("ok", false);
                    reply.put("error", "could not normalise the image");
                }
                else {
                    reply.put("ok", true);
                    reply.put("result", made);
                }
            }
            else if ("image.montage".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                JSONObject made = imageMontage(args == null ? new JSONObject() : args);
                if (made == null) {
                    reply.put("ok", false);
                    reply.put("error", "could not make the montage");
                }
                else {
                    reply.put("ok", true);
                    reply.put("result", made);
                }
            }
            else if ("battery.status".equals(method)) {
                reply.put("ok", true);
                reply.put("result", battery());
            }
            else if ("network.interfaces".equals(method)) {
                reply.put("ok", true);
                reply.put("result", networkInterfaces());
            }
            else if ("toast.show".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                String message = args == null ? "" : args.optString("message", "");
                boolean longToast = args != null && flag(args, "long", false);
                toast(message, longToast);
                reply.put("ok", true);
                reply.put("result", new JSONObject());
            }
            else if ("notification.post".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                reply.put("ok", true);
                reply.put("result", notificationPost(args == null ? new JSONObject() : args));
            }
            else if ("notification.remove".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                notificationRemove(args == null ? "" : args.optString("id", ""));
                reply.put("ok", true);
                reply.put("result", new JSONObject());
            }
            else if ("clipboard.get".equals(method)) {
                JSONObject result = new JSONObject();
                result.put("text", clipboardGet());
                reply.put("ok", true);
                reply.put("result", result);
            }
            else if ("clipboard.set".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                clipboardSet(args == null ? "" : args.optString("text", ""));
                reply.put("ok", true);
                reply.put("result", new JSONObject());
            }
            else if ("audio.volume".equals(method)) {
                reply.put("ok", true);
                reply.put("result", volume(request.optJSONObject("args")));
            }
            else if ("hardware.torch".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                boolean on = args != null && flag(args, "on", false);
                JSONObject result = new JSONObject();
                result.put("on", torch(on));
                reply.put("ok", true);
                reply.put("result", result);
            }
            else if ("power.wake_lock".equals(method) || "power.wake_unlock".equals(method)) {
                boolean held = wakeLock("power.wake_lock".equals(method));
                JSONObject result = new JSONObject();
                result.put("held", held);
                reply.put("ok", true);
                reply.put("result", result);
            }
            else if ("sms.send".equals(method)) {
                reply.put("ok", true);
                reply.put("result", smsSend(request.optJSONObject("args")));
            }
            else if ("sms.list".equals(method)) {
                reply.put("ok", true);
                reply.put("result", smsList(request.optJSONObject("args")));
            }
            else if ("telephony.call_log".equals(method)) {
                reply.put("ok", true);
                reply.put("result", callLog(request.optJSONObject("args")));
            }
            else if ("contact.list".equals(method)) {
                reply.put("ok", true);
                reply.put("result", contacts(request.optJSONObject("args")));
            }
            else if ("location.current".equals(method)) {
                reply.put("ok", true);
                reply.put("result", location(request.optJSONObject("args")));
            }
            else if ("speech.speak".equals(method)) {
                reply.put("ok", true);
                reply.put("result", speech(request.optJSONObject("args")));
            }
            else if ("telephony.dial".equals(method)) {
                reply.put("ok", true);
                reply.put("result", dial(request.optJSONObject("args")));
            }
            else if ("telephony.deviceinfo".equals(method)) {
                reply.put("ok", true);
                reply.put("result", telephonyInfo());
            }
            else if ("media.player".equals(method)) {
                reply.put("ok", true);
                reply.put("result", media(request.optJSONObject("args")));
            }
            else if ("share.send".equals(method)) {
                reply.put("ok", true);
                reply.put("result", share(request.optJSONObject("args")));
            }
            else if ("open.url".equals(method)) {
                reply.put("ok", true);
                reply.put("result", open(request.optJSONObject("args")));
            }
            else if ("notification.list".equals(method)) {
                reply.put("ok", true);
                reply.put("result", notificationList());
            }
            else if ("media.record".equals(method)) {
                reply.put("ok", true);
                reply.put("result", record(request.optJSONObject("args")));
            }
            else if ("camera.photo".equals(method)) {
                boolean escalated = escalateForeground(
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA, Manifest.permission.CAMERA);
                try {
                    reply.put("ok", true);
                    reply.put("result", cameraPhoto(request.optJSONObject("args")));
                }
                finally {
                    if (escalated) deescalateForeground();
                }
            }
            else if ("auth.fingerprint".equals(method)) {
                reply.put("ok", true);
                reply.put("result", fingerprint(request.optJSONObject("args")));
            }
            else {
                reply.put("ok", false);
                reply.put("error", "unknown method");
            }
            return reply;
        } catch (Throwable t) {
            JSONObject reply = new JSONObject();
            try {
                reply.put("id", id);
                reply.put("ok", false);
                reply.put("error", String.valueOf(t.getMessage()));
            } catch (Throwable ignored) {}
            return reply;
        }
    }

    /** The termux-battery-status shape, so a caller's parsing does not change. */
    private JSONObject battery() {
        JSONObject result = new JSONObject();
        try {
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return result;

            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            result.put("present", intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true));
            if (level >= 0 && scale > 0) {
                result.put("percentage", Math.round(level * 100.0f / scale));
            }
            if (scale > 0) result.put("scale", scale);
            result.put("health", health(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)));
            result.put("status", status(intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)));
            result.put("plugged", plugged(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)));

            int temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (temperature != Integer.MIN_VALUE) {
                result.put("temperature", temperature / 10.0);
            }
            int voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (voltage >= 0) result.put("voltage", voltage);
            String technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            if (technology != null) result.put("technology", technology);

            BatteryManager manager = (BatteryManager) getSystemService(BATTERY_SERVICE);
            if (manager != null) {
                long now = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                long average = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
                long charge = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                if (now != Long.MIN_VALUE) result.put("current", now);
                if (average != Long.MIN_VALUE) result.put("current_average", average);
                if (charge != Long.MIN_VALUE) result.put("charge_counter", charge);
            }
        } catch (Throwable t) {
            Log.w(LOG_TAG, "battery read failed", t);
        }
        return result;
    }

    /** The interfaces and IPv4 addresses, read through the Java API.
     *
     *  The runtime has no ifconfig, and `ip addr` cannot bind a netlink socket
     *  as an app uid (SELinux), so the tree's network report was empty on the
     *  phone and tablet and nothing knew its own address. NetworkInterface uses
     *  the ordinary socket API and needs no permission; the default gateway
     *  comes from the active network's LinkProperties. The shape is the tree's
     *  NIC report: one object per interface name. */
    private JSONObject networkInterfaces() {
        JSONObject out = new JSONObject();
        JSONObject interfaces = new JSONObject();
        try {
            String gateway = defaultGateway();
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                } catch (Exception ignored) {
                    continue;
                }
                String ip = null, netmask = null, broadcast = null;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    ip = a.getHostAddress();
                    netmask = netmaskFor(ia.getNetworkPrefixLength());
                    if (ia.getBroadcast() != null) broadcast = ia.getBroadcast().getHostAddress();
                    break;
                }
                if (ip == null) continue;
                JSONObject o = new JSONObject();
                o.put("nic", ni.getName());
                o.put("ip", ip);
                if (netmask != null) o.put("netmask", netmask);
                if (broadcast != null) o.put("broadcast", broadcast);
                if (gateway != null) o.put("gw", gateway);
                try {
                    byte[] mac = ni.getHardwareAddress();
                    if (mac != null && mac.length == 6) {
                        StringBuilder sb = new StringBuilder();
                        for (byte b : mac) {
                            if (sb.length() > 0) sb.append(':');
                            sb.append(String.format("%02x", b & 0xff));
                        }
                        o.put("mac", sb.toString());
                    }
                } catch (Exception ignored) { }
                interfaces.put(ni.getName(), o);
            }
            out.put("interfaces", interfaces);
        } catch (Throwable t) {
            Log.w(LOG_TAG, "network.interfaces read failed", t);
            try { out.put("interfaces", interfaces); } catch (Exception ignored) { }
            try { out.put("error", String.valueOf(t.getMessage())); } catch (Exception ignored) { }
        }
        return out;
    }

    private String defaultGateway() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            for (Network n : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (RouteInfo r : lp.getRoutes()) {
                    if (!r.isDefaultRoute()) continue;
                    InetAddress gw = r.getGateway();
                    if (gw instanceof Inet4Address && !gw.isAnyLocalAddress()) {
                        return gw.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String netmaskFor(int prefix) {
        if (prefix <= 0) return "0.0.0.0";
        if (prefix >= 32) return "255.255.255.255";
        int mask = 0xffffffff << (32 - prefix);
        return ((mask >>> 24) & 0xff) + "." + ((mask >>> 16) & 0xff) + "."
            + ((mask >>> 8) & 0xff) + "." + (mask & 0xff);
    }

    private static String status(int value) {
        switch (value) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "CHARGING";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "DISCHARGING";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "NOT_CHARGING";
            case BatteryManager.BATTERY_STATUS_FULL: return "FULL";
            default: return "UNKNOWN";
        }
    }

    private static String health(int value) {
        switch (value) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "GOOD";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "OVERHEAT";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "DEAD";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "OVER_VOLTAGE";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "UNSPECIFIED_FAILURE";
            case BatteryManager.BATTERY_HEALTH_COLD: return "COLD";
            default: return "UNKNOWN";
        }
    }

    private static String plugged(int value) {
        switch (value) {
            case BatteryManager.BATTERY_PLUGGED_AC: return "PLUGGED_AC";
            case BatteryManager.BATTERY_PLUGGED_USB: return "PLUGGED_USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "PLUGGED_WIRELESS";
            default:
                if (Build.VERSION.SDK_INT >= 33 && value == 8) {   // BATTERY_PLUGGED_DOCK, API 33
                    return "PLUGGED_DOCK";
                }
                return "UNPLUGGED";
        }
    }

    // --- native replacements ---------------------------------------------

    /** A native toast: no shell, no termux-toast, native duration. */
    private void toast(final String message, final boolean longToast) {
        if (message == null || message.isEmpty()) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                Toast.makeText(getApplicationContext(), message,
                    longToast ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * A native notification with the app's own control: two channels (normal and
     * quiet), a small-icon name (system or app drawable), a large icon from any
     * file, actions that open the WebView at a URL, a progress bar, grouping,
     * vibration and colour. The tap opens JawnWebViewActivity, with the caller's
     * url when it names one.
     */
    private JSONObject notificationPost(JSONObject args) throws Exception {
        ensureChannels();
        String tag = args.optString("id", "");
        if (tag.isEmpty()) tag = String.valueOf(System.currentTimeMillis());
        int id = Math.abs(tag.hashCode());
        String title = args.optString("title", "JawnOS");
        String text = args.optString("text", args.optString("message", ""));
        boolean silent = flag(args, "silent", false);
        boolean ongoing = flag(args, "ongoing", false);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent open = new Intent(this, JawnWebViewActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String url = args.optString("url", "");
        if (!url.isEmpty()) open.putExtra(JawnWebViewActivity.EXTRA_URL, url);
        PendingIntent contentIntent = PendingIntent.getActivity(this, id, open, piFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, silent ? CHANNEL_SILENT : CHANNEL_DEFAULT)
            : new Notification.Builder(this);
        builder.setContentTitle(title)
               .setContentText(text)
               .setSmallIcon(smallIcon(args.optString("small_icon", "")))
               .setContentIntent(contentIntent)
               .setAutoCancel(!ongoing && flag(args, "auto_cancel", true))
               .setShowWhen(true);
        if (ongoing || flag(args, "ongoing", false)) builder.setOngoing(true);
        if (silent || flag(args, "only_alert_once", false)) builder.setOnlyAlertOnce(true);

        String subText = args.optString("sub_text", "");
        if (!subText.isEmpty()) builder.setSubText(subText);
        String bigText = args.optString("big_text", "");
        if (!bigText.isEmpty()) builder.setStyle(new Notification.BigTextStyle().bigText(bigText));
        String group = args.optString("group", "");
        if (!group.isEmpty()) builder.setGroup(group);
        if (args.has("color")) builder.setColor((int) args.optLong("color", 0));
        if (args.has("number")) builder.setNumber(args.optInt("number", 0));

        JSONObject progress = args.optJSONObject("progress");
        if (progress != null) {
            builder.setProgress(progress.optInt("max", 100),
                progress.optInt("current", 0), flag(progress, "indeterminate", false));
        }

        if (!silent && args.has("vibrate")) {
            long[] pattern = vibration(args.optString("vibrate", ""));
            if (pattern.length > 0) builder.setVibrate(pattern);
        }

        String large = args.optString("large_icon", "");
        if (!large.isEmpty()) {
            Bitmap bitmap = BitmapFactory.decodeFile(large);
            if (bitmap != null) builder.setLargeIcon(bitmap);
        }

        JSONArray actions = args.optJSONArray("actions");
        if (actions != null) {
            for (int i = 0, added = 0; i < actions.length() && added < 3; i++) {
                JSONObject action = actions.optJSONObject(i);
                if (action == null) continue;
                String label = action.optString("label", "");
                if (label.isEmpty()) continue;
                Intent target = new Intent(this, JawnWebViewActivity.class);
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                String actionUrl = action.optString("url", "");
                if (!actionUrl.isEmpty()) target.putExtra(JawnWebViewActivity.EXTRA_URL, actionUrl);
                PendingIntent pi = PendingIntent.getActivity(this, id * 31 + added + 1, target, piFlags);
                builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_send, label, pi).build());
                added++;
            }
        }

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) throw new IllegalStateException("no notification manager");
        manager.notify(tag, id, builder.build());

        JSONObject result = new JSONObject();
        result.put("id", tag);
        result.put("notification_id", id);
        return result;
    }

    private void notificationRemove(String tag) {
        if (tag == null || tag.isEmpty()) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(tag, Math.abs(tag.hashCode()));
    }

    /** "200,150,200" -> {200, 150, 200}; anything unparsable is ignored. */
    private static long[] vibration(String spec) {
        if (spec == null || spec.trim().isEmpty()) return new long[0];
        String[] parts = spec.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i].trim());
            } catch (NumberFormatException e) {
                return new long[0];
            }
        }
        return out;
    }

    /**
     * A small icon is a resource: a drawable name the app ships, then a known
     * jawn concept, then the launcher-ish default. The large icon is a file the
     * caller names, so a notification can wear its own picture.
     */
    private int smallIcon(String name) {
        if (name != null && !name.isEmpty()) {
            int res = getResources().getIdentifier(name, "drawable", getPackageName());
            if (res != 0) return res;
        }
        String key = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        switch (key) {
            case "message": case "chat": case "sms": return android.R.drawable.stat_notify_chat;
            case "mail": case "email": return android.R.drawable.ic_dialog_email;
            case "alarm": case "clock": case "timer": return android.R.drawable.ic_lock_idle_alarm;
            case "call": case "phone": return android.R.drawable.stat_notify_missed_call;
            case "warning": case "alert": case "error": return android.R.drawable.ic_dialog_alert;
            case "info": return android.R.drawable.ic_dialog_info;
            case "download": return android.R.drawable.stat_sys_download;
            case "sync": case "refresh": return android.R.drawable.stat_notify_sync;
            case "camera": case "photo": return android.R.drawable.ic_menu_camera;
            case "location": case "gps": return android.R.drawable.ic_menu_mylocation;
            case "music": case "media": return android.R.drawable.ic_media_play;
            case "battery": return android.R.drawable.ic_lock_idle_charging;
            case "lock": case "security": return android.R.drawable.ic_lock_lock;
            case "check": case "done": return android.R.drawable.checkbox_on_background;
            case "store": case "cart": case "market": return android.R.drawable.ic_menu_agenda;
            case "print": case "printer": return android.R.drawable.ic_menu_share;
            case "session": case "jawn": default: return R.drawable.ic_new_session;
        }
    }

    /** The two channels the app already uses, so the person's own settings hold. */
    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel normal = new NotificationChannel(
            CHANNEL_DEFAULT, "JawnOS", NotificationManager.IMPORTANCE_DEFAULT);
        normal.setShowBadge(true);
        manager.createNotificationChannel(normal);
        NotificationChannel quiet = new NotificationChannel(
            CHANNEL_SILENT, "JawnOS (quiet)", NotificationManager.IMPORTANCE_LOW);
        quiet.setShowBadge(true);
        quiet.setSound(null, null);
        quiet.enableVibration(false);
        manager.createNotificationChannel(quiet);
    }

    // --- camera, biometric -----------------------------------------------

    /**
     * One JPEG from the requested camera, headless: Camera2 + ImageReader, no
     * preview surface. The tree's `-c` index (0 back, 1 front) is matched
     * against the camera id list. Camera permission is already granted; the
     * service declares the camera foreground type.
     */
    private JSONObject cameraPhoto(JSONObject args) throws Exception {
        String path = args == null ? "" : args.optString("path", "");
        if (path.isEmpty()) throw new IllegalArgumentException("no path");
        String wanted = String.valueOf(args == null ? 0 : args.optInt("camera", 0));

        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (manager == null) throw new IllegalStateException("no camera manager");
        String[] ids = manager.getCameraIdList();
        if (ids == null || ids.length == 0) throw new IllegalStateException("no camera");
        String id = ids[0];
        for (String candidate : ids) {
            if (candidate.equals(wanted)) { id = candidate; break; }
        }

        HandlerThread thread = new HandlerThread("jawn-camera");
        thread.start();
        final Handler handler = new Handler(thread.getLooper());
        ImageReader reader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1);
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch captured = new CountDownLatch(1);
        final CameraDevice[] device = new CameraDevice[1];
        final boolean[] saved = new boolean[1];
        final String[] failure = new String[1];

        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader source) {
                Image image = source.acquireNextImage();
                if (image == null) { captured.countDown(); return; }
                try {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(path)) {
                        out.write(bytes);
                    }
                    saved[0] = true;
                } catch (Throwable t) {
                    failure[0] = String.valueOf(t.getMessage());
                } finally {
                    image.close();
                    captured.countDown();
                }
            }
        }, handler);

        try {
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    device[0] = camera;
                    opened.countDown();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    opened.countDown();
                }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    failure[0] = "camera open error " + error;
                    opened.countDown();
                }
            }, handler);
            if (!opened.await(10, TimeUnit.SECONDS) || device[0] == null) {
                throw new IllegalStateException(failure[0] == null ? "camera open timed out" : failure[0]);
            }
            final CaptureRequest.Builder builder =
                device[0].createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(reader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            device[0].createCaptureSession(Collections.singletonList(reader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession session) {
                        try {
                            session.capture(builder.build(), null, handler);
                        } catch (Throwable t) {
                            failure[0] = String.valueOf(t.getMessage());
                            captured.countDown();
                        }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                        failure[0] = "capture session failed";
                        captured.countDown();
                    }
                }, handler);
            if (!captured.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("capture timed out");
            if (!saved[0]) throw new IllegalStateException(failure[0] == null ? "capture failed" : failure[0]);
        } finally {
            try { if (device[0] != null) device[0].close(); } catch (Throwable ignored) {}
            try { reader.close(); } catch (Throwable ignored) {}
            thread.quitSafely();
        }

        JSONObject result = new JSONObject();
        result.put("path", path);
        result.put("camera", id);
        java.io.File file = new java.io.File(path);
        result.put("bytes", file.exists() ? file.length() : 0);
        return result;
    }

    private static final Object authLock = new Object();
    private static JSONObject authResult;

    /** Startup of the prompt Activity; its answer arrives through deliverAuth. */
    private JSONObject fingerprint(JSONObject args) throws Exception {
        if (Build.VERSION.SDK_INT < 28) throw new IllegalStateException("biometric prompt needs API 28");
        String reason = args == null ? "Unlock JawnOS" : args.optString("reason", "Unlock JawnOS");
        synchronized (authLock) { authResult = null; }

        Intent intent = new Intent(this, JawnBridgeAuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("reason", reason);
        startActivity(intent);

        long deadline = System.currentTimeMillis() + 60000;
        synchronized (authLock) {
            while (authResult == null && System.currentTimeMillis() < deadline) {
                authLock.wait(500);
            }
            JSONObject result = authResult;
            authResult = null;
            if (result == null) throw new IllegalStateException("no answer from the prompt");
            return result;
        }
    }

    static void deliverAuth(JSONObject result) {
        synchronized (authLock) {
            authResult = result;
            authLock.notifyAll();
        }
    }

    /** termux-notification-list's shape, from the listener's active set. */
    private JSONObject notificationList() throws Exception {
        StatusBarNotification[] active = JawnNotificationListener.active();
        if (active == null) throw new IllegalStateException("notification access not granted");
        JSONArray list = new JSONArray();
        for (StatusBarNotification sbn : active) {
            Notification notification = sbn.getNotification();
            Bundle extras = notification == null ? null : notification.extras;
            CharSequence title = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TEXT);
            JSONObject item = new JSONObject();
            item.put("id", sbn.getId());
            item.put("tag", sbn.getTag() == null ? "" : sbn.getTag());
            item.put("package", sbn.getPackageName());
            item.put("title", title == null ? "" : title.toString());
            item.put("text", text == null ? "" : text.toString());
            item.put("content", text == null ? "" : text.toString());
            item.put("when", sbn.getPostTime());
            item.put("ongoing", sbn.isOngoing());
            list.put(item);
        }
        JSONObject result = new JSONObject();
        result.put("notifications", list);
        return result;
    }

    private MediaRecorder recorder;
    private String recordPath;

    /**
     * media.record: MediaRecorder to the caller's path (MPEG-4/AAC in what the
     * tree names .mp3 — the same container termux-microphone-record writes).
     * Start returns at once; stop reports the bytes written.
     */
    private synchronized JSONObject record(JSONObject args) throws Exception {
        String action = args == null ? "start" : args.optString("action", "start");
        JSONObject result = new JSONObject();
        if ("stop".equals(action)) {
            stopRecording();
            result.put("recording", false);
            if (recordPath != null) {
                java.io.File file = new java.io.File(recordPath);
                result.put("bytes", file.exists() ? file.length() : 0);
                recordPath = null;
            }
            return result;
        }
        if (!"start".equals(action)) throw new IllegalArgumentException("unknown action");

        String path = args.optString("path", "");
        if (path.isEmpty()) throw new IllegalArgumentException("no path");
        stopRecording();

        escalateForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, Manifest.permission.RECORD_AUDIO);
        MediaRecorder fresh = new MediaRecorder();
        fresh.setAudioSource(MediaRecorder.AudioSource.MIC);
        fresh.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        fresh.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        fresh.setAudioEncodingBitRate(64000);
        fresh.setAudioSamplingRate(44100);
        fresh.setOutputFile(path);
        fresh.prepare();
        fresh.start();
        recorder = fresh;
        recordPath = path;
        result.put("recording", true);
        return result;
    }

    private synchronized void stopRecording() {
        if (recorder == null) return;
        try { recorder.stop(); } catch (Throwable t) { Log.w(LOG_TAG, "recorder stop", t); }
        try { recorder.release(); } catch (Throwable ignored) {}
        recorder = null;
        deescalateForeground();
    }

    // --- media, share, open ------------------------------------------------

    private MediaPlayer mediaPlayer;

    /** One player for jawn's clips (Piper's WAV, notification sounds). */
    private synchronized JSONObject media(JSONObject args) throws Exception {
        String action = args == null ? "play" : args.optString("action", "play");
        JSONObject result = new JSONObject();
        if ("stop".equals(action)) {
            releaseMedia();
            result.put("playing", false);
            return result;
        }
        if (!"play".equals(action)) throw new IllegalArgumentException("unknown action");

        String path = args.optString("path", "");
        if (path.isEmpty()) throw new IllegalArgumentException("no path");
        releaseMedia();
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build());
        mediaPlayer.setDataSource(path);
        mediaPlayer.prepare();
        mediaPlayer.start();
        result.put("playing", true);
        result.put("duration_ms", mediaPlayer.getDuration());
        return result;
    }

    private synchronized void releaseMedia() {
        if (mediaPlayer == null) return;
        try { mediaPlayer.stop(); } catch (Throwable ignored) {}
        try { mediaPlayer.release(); } catch (Throwable ignored) {}
        mediaPlayer = null;
    }

    private static final String FILE_AUTHORITY_SUFFIX = ".jawnupdates";

    private Uri sharedUri(java.io.File file) {
        return FileProvider.getUriForFile(this, getPackageName() + FILE_AUTHORITY_SUFFIX, file);
    }

    /**
     * share.send: ACTION_SEND with a file (a FileProvider URI, so targetSdk-28
     * file:// exposure is never involved) and/or text. The old caller passed the
     * mime as termux-share's -a action, so accept either shape.
     */
    private JSONObject share(JSONObject args) throws Exception {
        Intent send = new Intent(Intent.ACTION_SEND);
        String type = args == null ? "" : args.optString("type", "");
        String path = args == null ? "" : args.optString("path", "");
        String text = args == null ? "" : args.optString("text", "");
        if (type.startsWith("android.intent.action.")) {
            send.setAction(type);
            type = "";
        }
        if (!path.isEmpty()) {
            java.io.File file = new java.io.File(path);
            if (!file.exists()) throw new IllegalArgumentException("no such file");
            Uri uri = sharedUri(file);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (type.isEmpty()) {
                String found = getContentResolver().getType(uri);
                if (found != null) type = found;
            }
        }
        if (!text.isEmpty()) {
            if (path.isEmpty()) send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
        }
        send.setType(type.isEmpty() ? "*/*" : type);
        send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // A service is not an Activity: the flag belongs on the chooser too,
        // not only on the intent the chooser wraps.
        Intent chooser = Intent.createChooser(send, null);
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(chooser);
        JSONObject result = new JSONObject();
        result.put("shared", true);
        return result;
    }

    /** open.url: ACTION_VIEW for a URL, or a path through the FileProvider. */
    private JSONObject open(JSONObject args) throws Exception {
        String url = args == null ? "" : args.optString("url", "");
        String path = args == null ? "" : args.optString("path", "");
        Intent view;
        if (!url.isEmpty()) {
            view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        }
        else if (!path.isEmpty()) {
            java.io.File file = new java.io.File(path);
            if (!file.exists()) throw new IllegalArgumentException("no such file");
            Uri uri = sharedUri(file);
            String type = getContentResolver().getType(uri);
            view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, type == null ? "*/*" : type);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        else {
            throw new IllegalArgumentException("no url or path");
        }
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (view.resolveActivity(getPackageManager()) == null) {
            throw new IllegalStateException("no handler");
        }
        startActivity(view);
        JSONObject result = new JSONObject();
        result.put("opened", true);
        return result;
    }

    // --- location, speech, telephony ---------------------------------------

    /**
     * One GPS fix, bounded. The app's JawnLocationService is the primary source
     * (jawn reads its file); this is the fallback for a phone whose service has
     * not run yet, and the timeout keeps a cold GPS from holding the worker.
     */
    private JSONObject location(JSONObject args) throws Exception {
        String provider = args == null ? "gps" : args.optString("provider", "gps");
        if ("network".equals(provider)) provider = LocationManager.NETWORK_PROVIDER;
        else provider = LocationManager.GPS_PROVIDER;
        int timeout = args == null ? 15000 : Math.max(1000, Math.min(60000, args.optInt("timeout_ms", 15000)));

        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) throw new IllegalStateException("no location manager");
        if (!manager.isProviderEnabled(provider)) {
            throw new IllegalStateException(provider + " disabled");
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final Location[] fix = new Location[1];
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                synchronized (fix) { if (fix[0] == null) fix[0] = location; }
                latch.countDown();
            }
            @Override public void onProviderEnabled(String name) {}
            @Override public void onProviderDisabled(String name) { latch.countDown(); }
            @Override public void onStatusChanged(String name, int status, android.os.Bundle extras) {}
        };
        try {
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            latch.await(timeout, TimeUnit.MILLISECONDS);
        } finally {
            manager.removeUpdates(listener);
        }

        Location found = fix[0];
        if (found == null) found = manager.getLastKnownLocation(provider);
        if (found == null) throw new IllegalStateException("no fix");

        JSONObject result = new JSONObject();
        result.put("provider", LocationManager.GPS_PROVIDER.equals(provider) ? "gps" : "network");
        result.put("latitude", found.getLatitude());
        result.put("longitude", found.getLongitude());
        result.put("accuracy", found.hasAccuracy() ? found.getAccuracy() : 0);
        result.put("time", found.getTime());
        result.put("elapsedMs", Math.max(0, System.currentTimeMillis() - found.getTime()));
        return result;
    }

    private TextToSpeech tts;
    private boolean ttsReady;
    private final Object ttsLock = new Object();

    /** TextToSpeech initialises asynchronously; wait once, then reuse it. */
    private boolean ensureTts() {
        synchronized (ttsLock) {
            if (ttsReady) return true;
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] ok = new boolean[1];
            tts = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    ok[0] = status == TextToSpeech.SUCCESS;
                    latch.countDown();
                }
            });
            try { latch.await(6, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            ttsReady = ok[0];
            if (!ttsReady && tts != null) {
                try { tts.shutdown(); } catch (Throwable ignored) {}
                tts = null;
            }
            return ttsReady;
        }
    }

    /** Piper stays first in the tree; this is the Android fallback when it cannot run. */
    private JSONObject speech(JSONObject args) throws Exception {
        String text = args == null ? "" : args.optString("text", "");
        if (text.isEmpty()) throw new IllegalArgumentException("no text");
        if (!ensureTts()) throw new IllegalStateException("tts engine unavailable");

        if (args.has("rate")) tts.setSpeechRate((float) args.optDouble("rate", 1.0));
        if (args.has("pitch")) tts.setPitch((float) args.optDouble("pitch", 1.0));
        String language = args.optString("language", "");
        if (!language.isEmpty()) {
            tts.setLanguage(new Locale(language));
        }
        String voice = args.optString("voice", "");
        if (!voice.isEmpty() && tts.getVoices() != null) {
            for (Voice candidate : tts.getVoices()) {
                if (candidate.getName().equals(voice)) { tts.setVoice(candidate); break; }
            }
        }

        int mode = flag(args, "flush", true) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
        int rc = tts.speak(text, mode, null, "jawn-" + System.currentTimeMillis());
        if (rc != TextToSpeech.SUCCESS) throw new IllegalStateException("speak failed: " + rc);

        JSONObject result = new JSONObject();
        result.put("spoken", true);
        result.put("engine", tts.getDefaultEngine() == null ? "" : tts.getDefaultEngine());
        return result;
    }

    /** ACTION_CALL, the native form of termux-telephony-call (CALL_PHONE granted). */
    private JSONObject dial(JSONObject args) throws Exception {
        String number = args == null ? "" : args.optString("number", "").trim();
        if (number.isEmpty()) throw new IllegalArgumentException("no number");
        Intent call = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
        call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        JSONObject result = new JSONObject();
        if (flag(args, "dry_run", false)) {
            result.put("would_dial", call.resolveActivity(getPackageManager()) != null);
            result.put("dry_run", true);
            return result;
        }
        startActivity(call);
        result.put("dialed", true);
        return result;
    }

    /** TelephonyManager in termux-telephony-deviceinfo's shape. */
    private JSONObject telephonyInfo() throws Exception {
        JSONObject result = new JSONObject();
        TelephonyManager manager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (manager == null) {
            result.put("phone_type", "none");
            result.put("sim_state", "");
            result.put("network_type", "");
            return result;
        }
        String phoneType = "none";
        try {
            switch (manager.getPhoneType()) {
                case TelephonyManager.PHONE_TYPE_GSM: phoneType = "gsm"; break;
                case TelephonyManager.PHONE_TYPE_CDMA: phoneType = "cdma"; break;
                case TelephonyManager.PHONE_TYPE_SIP: phoneType = "sip"; break;
                default: phoneType = "none";
            }
        } catch (Throwable ignored) {}
        result.put("phone_type", phoneType);
        result.put("sim_state", simState(manager.getSimState()));
        String network = "";
        try {
            network = Build.VERSION.SDK_INT >= 24
                ? networkType(manager.getDataNetworkType())
                : networkType(manager.getNetworkType());
        } catch (Throwable ignored) {}
        result.put("network_type", network);
        result.put("operator", manager.getNetworkOperatorName() == null ? "" : manager.getNetworkOperatorName());
        result.put("sim_operator", manager.getSimOperatorName() == null ? "" : manager.getSimOperatorName());
        return result;
    }

    private static String simState(int state) {
        switch (state) {
            case TelephonyManager.SIM_STATE_READY: return "ready";
            case TelephonyManager.SIM_STATE_ABSENT: return "absent";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED: return "pin_required";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED: return "puk_required";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED: return "network_locked";
            case TelephonyManager.SIM_STATE_NOT_READY: return "not_ready";
            case TelephonyManager.SIM_STATE_PERM_DISABLED: return "perm_disabled";
            case TelephonyManager.SIM_STATE_CARD_IO_ERROR: return "card_io_error";
            default: return "unknown";
        }
    }

    private static String networkType(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_GPRS: return "gprs";
            case TelephonyManager.NETWORK_TYPE_EDGE: return "edge";
            case TelephonyManager.NETWORK_TYPE_UMTS: return "umts";
            case TelephonyManager.NETWORK_TYPE_CDMA: return "cdma";
            case TelephonyManager.NETWORK_TYPE_EVDO_0: return "evdo_0";
            case TelephonyManager.NETWORK_TYPE_EVDO_A: return "evdo_a";
            case TelephonyManager.NETWORK_TYPE_1xRTT: return "1xrtt";
            case TelephonyManager.NETWORK_TYPE_HSDPA: return "hsdpa";
            case TelephonyManager.NETWORK_TYPE_HSUPA: return "hsupa";
            case TelephonyManager.NETWORK_TYPE_HSPA: return "hspa";
            case TelephonyManager.NETWORK_TYPE_IDEN: return "iden";
            case TelephonyManager.NETWORK_TYPE_EVDO_B: return "evdo_b";
            case TelephonyManager.NETWORK_TYPE_LTE: return "lte";
            case TelephonyManager.NETWORK_TYPE_EHRPD: return "ehrpd";
            case TelephonyManager.NETWORK_TYPE_HSPAP: return "hspap";
            case TelephonyManager.NETWORK_TYPE_GSM: return "gsm";
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA: return "td_scdma";
            case TelephonyManager.NETWORK_TYPE_IWLAN: return "iwlan";
            default:
                if (Build.VERSION.SDK_INT >= 29 && type == TelephonyManager.NETWORK_TYPE_NR) return "nr";
                return "unknown";
        }
    }

    // --- SMS, call log, contacts ------------------------------------------

    /**
     * SmsManager sends multipart itself. A dry_run answers how many parts the
     * message would become without touching the radio, so a test (or a
     * preview) can prove the path without sending anything.
     */
    private JSONObject smsSend(JSONObject args) throws Exception {
        String number = args == null ? "" : args.optString("number", "").trim();
        String message = args == null ? "" : args.optString("message", "");
        boolean dryRun = args != null && flag(args, "dry_run", false);
        JSONObject result = new JSONObject();
        if (number.isEmpty()) throw new IllegalArgumentException("no number");
        SmsManager manager = Build.VERSION.SDK_INT >= 31
            ? getSystemService(SmsManager.class)
            : SmsManager.getDefault();
        if (manager == null) throw new IllegalStateException("no SmsManager");
        ArrayList<String> parts = manager.divideMessage(message);
        result.put("parts", parts == null ? 0 : parts.size());
        result.put("dry_run", dryRun);
        if (!dryRun) {
            manager.sendMultipartTextMessage(number, null, parts, null, null);
        }
        result.put("sent", !dryRun);
        return result;
    }

    /** The last N messages in the termux-sms-list shape ({type, number, body, received}). */
    private JSONObject smsList(JSONObject args) throws Exception {
        int limit = limit(args, 10);
        ContentResolver resolver = getContentResolver();
        String[] columns = {
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE
        };
        JSONArray messages = new JSONArray();
        try (Cursor cursor = resolver.query(Telephony.Sms.CONTENT_URI, columns, null, null,
                Telephony.Sms.DATE + " DESC")) {
            if (cursor != null) {
                while (cursor.moveToNext() && messages.length() < limit) {
                    JSONObject message = new JSONObject();
                    String address = cursor.getString(1);
                    String body = cursor.getString(2);
                    message.put("number", address == null ? "" : address);
                    message.put("body", body == null ? "" : body);
                    message.put("received", cursor.getLong(3));
                    message.put("type", smsType(cursor.getInt(4)));
                    messages.put(message);
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("messages", messages);
        return result;
    }

    /** The last N calls in the termux-call-log shape ({name, phone_number, date, duration}). */
    private JSONObject callLog(JSONObject args) throws Exception {
        int limit = limit(args, 30);
        ContentResolver resolver = getContentResolver();
        String[] columns = {
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE
        };
        JSONArray calls = new JSONArray();
        try (Cursor cursor = resolver.query(CallLog.Calls.CONTENT_URI, columns, null, null,
                CallLog.Calls.DATE + " DESC")) {
            if (cursor != null) {
                while (cursor.moveToNext() && calls.length() < limit) {
                    JSONObject call = new JSONObject();
                    String number = cursor.getString(0);
                    String name = cursor.getString(1);
                    call.put("phone_number", number == null ? "" : number);
                    call.put("name", name == null ? "" : name);
                    call.put("date", cursor.getLong(2));
                    call.put("duration", callDuration(cursor.getLong(3)));
                    call.put("type", callType(cursor.getInt(4)));
                    calls.put(call);
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("calls", calls);
        return result;
    }

    /** Every phone number in the contacts, capped, as [{name, number}]. */
    private JSONObject contacts(JSONObject args) throws Exception {
        int limit = limit(args, 2000);
        ContentResolver resolver = getContentResolver();
        String[] columns = {
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        };
        JSONArray list = new JSONArray();
        try (Cursor cursor = resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (cursor != null) {
                while (cursor.moveToNext() && list.length() < limit) {
                    String name = cursor.getString(0);
                    String number = cursor.getString(1);
                    if (number == null || number.isEmpty()) continue;
                    JSONObject contact = new JSONObject();
                    contact.put("name", name == null ? "" : name);
                    contact.put("number", number);
                    list.put(contact);
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("contacts", list);
        return result;
    }

    /**
     * Perl sends a boolean as 1/0 (a JSON number), and callers may send true or
     * "true"; JSONObject.optBoolean reads only a real JSON boolean, so every
     * flag goes through here instead.
     */
    private static boolean flag(JSONObject args, String key, boolean fallback) {
        if (args == null || !args.has(key)) return fallback;
        Object value = args.opt(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        if (value instanceof String) {
            String text = ((String) value).trim();
            return "1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
        }
        return fallback;
    }

    private static int limit(JSONObject args, int fallback) {
        int limit = args == null ? fallback : args.optInt("limit", fallback);
        return Math.max(1, Math.min(5000, limit));
    }

    private static String smsType(int type) {
        switch (type) {
            case Telephony.Sms.MESSAGE_TYPE_INBOX: return "inbox";
            case Telephony.Sms.MESSAGE_TYPE_SENT: return "sent";
            case Telephony.Sms.MESSAGE_TYPE_DRAFT: return "draft";
            case Telephony.Sms.MESSAGE_TYPE_OUTBOX: return "outbox";
            case Telephony.Sms.MESSAGE_TYPE_FAILED: return "failed";
            case Telephony.Sms.MESSAGE_TYPE_QUEUED: return "queued";
            default: return "unknown";
        }
    }

    private static String callType(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE: return "incoming";
            case CallLog.Calls.OUTGOING_TYPE: return "outgoing";
            case CallLog.Calls.MISSED_TYPE: return "missed";
            case CallLog.Calls.REJECTED_TYPE: return "rejected";
            case CallLog.Calls.BLOCKED_TYPE: return "blocked";
            case CallLog.Calls.VOICEMAIL_TYPE: return "voicemail";
            default: return "unknown";
        }
    }

    /** Seconds -> "H:MM:SS" or "M:SS", the shape the tree already splits on ':'. */
    private static String callDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, secs);
        return String.format(java.util.Locale.US, "%d:%02d", minutes, secs);
    }

    // --- clipboard, volume, torch, wake lock ------------------------------

    private String clipboardGet() {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager == null || !manager.hasPrimaryClip()) return "";
            ClipData clip = manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            return text == null ? "" : text.toString();
        } catch (Throwable t) {
            Log.w(LOG_TAG, "clipboard read failed", t);
            return "";
        }
    }

    private void clipboardSet(String text) {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("jawn", text == null ? "" : text));
            }
        } catch (Throwable t) {
            Log.w(LOG_TAG, "clipboard write failed", t);
        }
    }

    /** The termux-volume shape: every stream as {stream, volume, max_volume, muted}. */
    private JSONObject volume(JSONObject args) throws Exception {
        JSONObject result = new JSONObject();
        JSONArray streams = new JSONArray();
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        result.put("streams", streams);
        if (manager == null) return result;

        if (args != null && args.has("stream") && args.has("volume")) {
            int id = streamId(args.optString("stream", ""));
            if (id >= 0) {
                int max = manager.getStreamMaxVolume(id);
                int level = Math.max(0, Math.min(max, args.optInt("volume", 0)));
                manager.setStreamVolume(id, level, 0);
            }
        }

        int[] ids = {
            AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION
        };
        for (int id : ids) {
            JSONObject stream = new JSONObject();
            stream.put("stream", streamName(id));
            stream.put("volume", manager.getStreamVolume(id));
            stream.put("max_volume", manager.getStreamMaxVolume(id));
            stream.put("muted", Build.VERSION.SDK_INT >= 23 && manager.isStreamMute(id));
            streams.put(stream);
        }
        return result;
    }

    private static int streamId(String name) {
        String key = name == null ? "" : name.trim().toLowerCase(java.util.Locale.US);
        switch (key) {
            case "call": case "voice_call": case "in_call": return AudioManager.STREAM_VOICE_CALL;
            case "system": return AudioManager.STREAM_SYSTEM;
            case "ring": case "ringer": return AudioManager.STREAM_RING;
            case "music": case "media": return AudioManager.STREAM_MUSIC;
            case "alarm": return AudioManager.STREAM_ALARM;
            case "notification": case "notifications": return AudioManager.STREAM_NOTIFICATION;
            default: return -1;
        }
    }

    private static String streamName(int id) {
        switch (id) {
            case AudioManager.STREAM_VOICE_CALL: return "call";
            case AudioManager.STREAM_SYSTEM: return "system";
            case AudioManager.STREAM_RING: return "ring";
            case AudioManager.STREAM_MUSIC: return "music";
            case AudioManager.STREAM_ALARM: return "alarm";
            case AudioManager.STREAM_NOTIFICATION: return "notification";
            default: return String.valueOf(id);
        }
    }

    /** The first camera with a flash unit owns the torch; the id is cached. */
    private String torchCameraId;

    private boolean torch(boolean on) {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (manager == null) return false;
            if (torchCameraId == null) {
                for (String id : manager.getCameraIdList()) {
                    Boolean flash = manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (flash != null && flash) {
                        torchCameraId = id;
                        break;
                    }
                }
            }
            if (torchCameraId == null) return false;
            manager.setTorchMode(torchCameraId, on);
            return on;
        } catch (Throwable t) {
            Log.w(LOG_TAG, "torch failed", t);
            return false;
        }
    }

    private PowerManager.WakeLock wakeLock;

    /** One partial wake lock, named so it is attributable in dumpsys power. */
    private synchronized boolean wakeLock(boolean on) {
        try {
            PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
            if (manager == null) return false;
            if (on) {
                if (wakeLock == null) {
                    wakeLock = manager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "jawnos:device-bridge");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) wakeLock.acquire();
                return true;
            }
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            return false;
        } catch (Throwable t) {
            Log.w(LOG_TAG, "wake lock failed", t);
            return false;
        }
    }

    // --- token and notification ------------------------------------------

    /** The token lives where Termux's HOME is: /data/data/<pkg>/files/home. */
    private String ensureToken() {
        try {
            File dir = new File(getFilesDir(), "home/.jawn");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(LOG_TAG, "cannot create " + dir);
                return "";
            }
            File file = new File(dir, TOKEN_NAME);
            if (file.exists()) {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
                    String existing = in.readLine();
                    if (existing != null) {
                        existing = existing.trim();
                        if (existing.matches("[0-9a-f]{32,}")) return existing;
                    }
                }
            }
            String fresh = newToken();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write((fresh + "\n").getBytes(StandardCharsets.UTF_8));
            }
            try { android.system.Os.chmod(file.getAbsolutePath(), 0600); } catch (Throwable ignored) {}
            return fresh;
        } catch (Throwable t) {
            Log.w(LOG_TAG, "token setup failed", t);
            return "";
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "JawnOS device bridge", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Serves device calls (battery and the rest) to jawn");
        manager.createNotificationChannel(channel);
    }

    private Notification notification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return builder
            .setContentTitle("JawnOS device bridge")
            .setContentText("Serving device calls to jawn")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build();
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (Throwable ignored) {}
    }
    /**
     * A PNG thumbnail of a picture, a film frame or a PDF page, as {data, width,
     * height, kind}. Null means "not something this can render", and the caller
     * falls back to the shell pipeline. The manager's thumbnail routes ask for
     * this first so a phone with no ImageMagick can still draw a gallery.
     */
    private JSONObject mediaThumbnail(String path, int max, long atMs) {
        Bitmap bitmap = null;
        String kind = null;
        java.io.File file = new java.io.File(path);
        if (!file.isFile()) return null;
        if (max <= 0) max = 256;

        if (atMs >= 0 || isVideo(file)) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(path);
                bitmap = atMs >= 0
                        ? retriever.getFrameAtTime(atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        : retriever.getFrameAtTime();
                if (bitmap == null) bitmap = retriever.getFrameAtTime(0);
                if (bitmap != null) kind = "video";
            } catch (Throwable ignored) {
                bitmap = null;
            } finally {
                try { retriever.release(); } catch (Throwable ignored) { }
            }
        }

        if (bitmap == null) {
            bitmap = decodePicture(file, max);
            if (bitmap != null) kind = "image";
        }
        if (bitmap == null) {
            bitmap = decodePdfPage(file, max);
            if (bitmap != null) kind = "pdf";
        }
        if (bitmap == null) return null;
        bitmap = fitWithin(bitmap, max);

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        try {
            JSONObject result = new JSONObject();
            result.put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
            result.put("width", width);
            result.put("height", height);
            result.put("kind", kind);
            return result;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final String[] VIDEO_EXTENSIONS = {
        ".mp4", ".m4v", ".mkv", ".webm", ".mov", ".avi", ".mpg", ".mpeg",
        ".ts", ".m2ts", ".3gp", ".ogv", ".flv", ".wmv",
    };

    /** A container the retriever understands: ask it for a frame, cheaply. */
    private static boolean isVideo(java.io.File file) {
        String name = file.getName().toLowerCase();
        for (String ext : VIDEO_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    /** Decode a picture, honouring its EXIF orientation and sampling no finer
     *  than `max` in either axis; no fit-shrink, so a caller that needs to
     *  fill a square has pixels to spare. */
    private static Bitmap decodeOriented(java.io.File file, int max) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= max && bounds.outHeight / (sample * 2) >= max) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap == null) return null;

            return applyExifOrientation(bitmap, file);
        } catch (Throwable t) {
            return null;
        }
    }


    /** Apply the EXIF orientation to a decoded bitmap, all eight values.
     *  BitmapFactory does not rotate, and writeBitmap re-encodes without the
     *  metadata, so a phone photo came back sideways once it was resized. */
    private static Bitmap applyExifOrientation(Bitmap bitmap, java.io.File file) {
        if (bitmap == null || file == null) return bitmap;
        int orientation = exifOrientation(file);
        return applyExifOrientation(bitmap, orientation);
    }

    /** The EXIF orientation value, or NORMAL when there is none. */
    private static int exifOrientation(java.io.File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** Apply an already-read EXIF orientation value. */
    private static Bitmap applyExifOrientation(Bitmap bitmap, int orientation) {
        if (bitmap == null) return bitmap;
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(-90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(-90);
                break;
            default:
                return bitmap;
        }
        try {
            Bitmap turned = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (turned != bitmap) bitmap.recycle();
            return turned;
        } catch (Throwable t) {
            return bitmap;
        }
    }

    /** Decode a picture through its EXIF orientation, sampling so the decoded
     *  pixels still cover targetWidth × targetHeight (both axes). */
    private static Bitmap decodeOrientedSize(java.io.File file, int targetWidth, int targetHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int orientation = exifOrientation(file);
            boolean swap = orientation == ExifInterface.ORIENTATION_TRANSPOSE
                    || orientation == ExifInterface.ORIENTATION_ROTATE_90
                    || orientation == ExifInterface.ORIENTATION_TRANSVERSE
                    || orientation == ExifInterface.ORIENTATION_ROTATE_270;
            int width = swap ? bounds.outHeight : bounds.outWidth;
            int height = swap ? bounds.outWidth : bounds.outHeight;
            int sample = sampleSize(width, height, Math.max(1, targetWidth), Math.max(1, targetHeight));
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap == null) return null;
            return applyExifOrientation(bitmap, orientation);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Decode a plain image, sampling no finer than `target` in either axis,
     *  with no EXIF rotation and no fit-shrink. The conversion paths do their
     *  own geometry and only need enough pixels to fill the output, so a 35 MP
     *  scan is not loaded whole. target <= 0 means 2048. */
    private static Bitmap decodeSampled(java.io.File file, int target) {
        try {
            if (target <= 0) target = 2048;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Decode a picture, honouring its EXIF orientation and never loading more
     *  pixels than the thumbnail needs. */
    private static Bitmap decodePicture(java.io.File file, int max) {
        Bitmap bitmap = decodeOriented(file, max);
        return bitmap == null ? null : fitWithin(bitmap, max);
    }

    /** The first page of a PDF, drawn at thumbnail size. */
    private static Bitmap decodePdfPage(java.io.File file, int max) {
        ParcelFileDescriptor fd = null;
        PdfRenderer renderer = null;
        try {
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(fd);
            if (renderer.getPageCount() < 1) return null;
            PdfRenderer.Page page = renderer.openPage(0);
            try {
                int pageWidth = page.getWidth();
                int pageHeight = page.getHeight();
                if (pageWidth <= 0 || pageHeight <= 0) return null;
                double scale = Math.min((double) max / pageWidth, (double) max / pageHeight);
                int width = Math.max(1, (int) Math.round(pageWidth * scale));
                int height = Math.max(1, (int) Math.round(pageHeight * scale));
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(android.graphics.Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            } finally {
                page.close();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (renderer != null) renderer.close(); } catch (Throwable ignored) { }
            try { if (fd != null) fd.close(); } catch (Throwable ignored) { }
        }
    }

    /** Scale down to fit a square of the given side; a smaller picture is kept. */
    private static Bitmap fitWithin(Bitmap bitmap, int max) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= max && height <= max) return bitmap;
        double scale = Math.min((double) max / width, (double) max / height);
        int newWidth = Math.max(1, (int) Math.round(width * scale));
        int newHeight = Math.max(1, (int) Math.round(height * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }


    /** Raw width, height and kind of a picture, a video or a PDF's first page.
     *
     *  Dimensions are the ones the file stores: EXIF rotation is not applied,
     *  which is what `magick identify` reports.
     */
    private JSONObject imageIdentify(JSONObject args) {
        try {
            String path = args.optString("path", "");
            File file = new File(path);
            if (!file.isFile()) return null;

            JSONObject result = new JSONObject();
            if (isVideo(file)) {
                int[] size = videoFrameSize(file);
                if (size == null) return null;
                result.put("width", size[0]);
                result.put("height", size[1]);
                result.put("kind", "video");
                return result;
            }
            if (!"pdf".equals(extensionOf(path))) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, bounds);
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    result.put("width", bounds.outWidth);
                    result.put("height", bounds.outHeight);
                    result.put("kind", "image");
                    return result;
                }
            }
            int[] size = pdfPageSize(file);
            if (size == null) return null;
            result.put("width", size[0]);
            result.put("height", size[1]);
            result.put("kind", "pdf");
            return result;
        } catch (Throwable t) {
            Log.w(LOG_TAG, "image.identify failed", t);
            return null;
        }
    }

    /** Decode a picture, a video frame or a PDF page, scale it down to the
     *  requested bounds and write it at args.format/args.quality.
     *
     *  `magick convert` does not honour EXIF unless asked, so auto_orient is
     *  off by default and only decodeOriented is used when it is set.
     */
    private JSONObject imageConvert(JSONObject args) {
        Bitmap bitmap = null;
        Bitmap scaled = null;
        try {
            String path = args.optString("path", "");
            String out = args.optString("out", "");
            if (out.isEmpty()) return null;
            File source = new File(path);
            if (!source.isFile()) return null;

            int maxWidth = args.optInt("max_width", 0);
            int maxHeight = args.optInt("max_height", 0);
            int quality = args.optInt("quality", 90);
            String format = args.optString("format", "");
            if (format.isEmpty()) format = extensionOf(out);
            int target = Math.max(1024, Math.max(maxWidth, maxHeight));

            if (isVideo(source)) {
                bitmap = videoFrame(source);
            }
            else if ("pdf".equals(extensionOf(path))) {
                bitmap = decodePdfNatural(source);
            }
            else if (truthy(args, "auto_orient")) {
                bitmap = decodeOriented(source, target);
            }
            else {
                bitmap = decodeSampled(source, target);
            }
            if (bitmap == null) return null;

            double scale = 1.0;
            if (maxWidth > 0) scale = Math.min(scale, (double) maxWidth / bitmap.getWidth());
            if (maxHeight > 0) scale = Math.min(scale, (double) maxHeight / bitmap.getHeight());
            int width = Math.max(1, (int) Math.round(bitmap.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(bitmap.getHeight() * scale));

            scaled = bitmap;
            if (width != bitmap.getWidth() || height != bitmap.getHeight()) {
                scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = null;
                }
            }

            boolean written = writeBitmap(scaled, new File(out), format, quality);
            scaled.recycle();
            scaled = null;
            bitmap = null;
            if (!written) return null;

            JSONObject result = new JSONObject();
            result.put("path", out);
            result.put("width", width);
            result.put("height", height);
            return result;
        } catch (Throwable t) {
            Log.w(LOG_TAG, "image.convert failed", t);
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (scaled != null) scaled.recycle();
        }
    }

    /** The picture as exactly width × height bytes of 8-bit luminance.
     *
     *  This replaces `magick <file> -resize WxH! -colorspace Gray -depth 8
     *  gray:-`. Transparent pixels are flattened onto white first; the scale
     *  is exact, ignoring the aspect ratio; alpha is not part of the result.
     */
    private JSONObject imageGray(JSONObject args) {
        Bitmap bitmap = null;
        Bitmap flat = null;
        Bitmap scaled = null;
        try {
            String path = args.optString("path", "");
            int width = args.optInt("width", 0);
            int height = args.optInt("height", 0);
            if (width <= 0 || height <= 0) return null;
            File source = new File(path);
            if (!source.isFile()) return null;

            bitmap = decodeUnoriented(source);
            if (bitmap == null) return null;

            int sourceWidth = bitmap.getWidth();
            int sourceHeight = bitmap.getHeight();
            int pixelCount = sourceWidth * sourceHeight;
            int[] pixels = new int[pixelCount];
            bitmap.getPixels(pixels, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight);
            bitmap.recycle();
            bitmap = null;

            for (int i = 0; i < pixelCount; i++) {
                int colour = pixels[i];
                int alpha = (colour >>> 24) & 0xFF;
                int red = (colour >> 16) & 0xFF;
                int green = (colour >> 8) & 0xFF;
                int blue = colour & 0xFF;
                if (alpha != 255) {
                    red = (red * alpha + 255 * (255 - alpha)) / 255;
                    green = (green * alpha + 255 * (255 - alpha)) / 255;
                    blue = (blue * alpha + 255 * (255 - alpha)) / 255;
                }
                pixels[i] = 0xFF000000 | (red << 16) | (green << 8) | blue;
            }
            flat = Bitmap.createBitmap(pixels, sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888);
            pixels = null;

            scaled = flat;
            if (sourceWidth != width || sourceHeight != height) {
                scaled = Bitmap.createScaledBitmap(flat, width, height, true);
                if (scaled != flat) {
                    flat.recycle();
                    flat = null;
                }
            }

            int[] outPixels = new int[width * height];
            scaled.getPixels(outPixels, 0, width, 0, 0, width, height);
            byte[] gray = new byte[width * height];
            for (int i = 0; i < outPixels.length; i++) {
                int colour = outPixels[i];
                int red = (colour >> 16) & 0xFF;
                int green = (colour >> 8) & 0xFF;
                int blue = colour & 0xFF;
                gray[i] = (byte) ((red * 299 + green * 587 + blue * 114) / 1000);
            }

            JSONObject result = new JSONObject();
            result.put("data", Base64.encodeToString(gray, Base64.NO_WRAP));
            result.put("width", width);
            result.put("height", height);
            return result;
        } catch (Throwable t) {
            Log.w(LOG_TAG, "image.gray failed", t);
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (flat != null) flat.recycle();
            if (scaled != null) scaled.recycle();
        }
    }

    /** Greyscale, stretch the levels, trim the corner-coloured border and fit
     *  within `max`, writing a PNG.
     *
     *  The tree's fallback runs `magick <src> -colorspace Gray -normalize -trim
     *  +repage -resize 1024x1024> <dst>` with no deskew; this is the same
     *  pipeline in pixels. The trim compares each pixel with the TOP-LEFT one.
     */
    private JSONObject imageNormalize(JSONObject args) {
        Bitmap cropped = null;
        Bitmap scaled = null;
        try {
            String path = args.optString("path", "");
            String out = args.optString("out", "");
            int max = args.optInt("max", 1024);
            if (out.isEmpty()) return null;
            if (max <= 0) max = 1024;
            File source = new File(path);
            if (!source.isFile()) return null;

            Bitmap bitmap = decodeSampled(source, Math.max(1024, max));
            if (bitmap == null) return null;

            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int pixelCount = width * height;
            int[] pixels = new int[pixelCount];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            bitmap.recycle();

            byte[] gray = new byte[pixelCount];
            int minimum = 255;
            int maximum = 0;
            for (int i = 0; i < pixelCount; i++) {
                int colour = pixels[i];
                int value = (((colour >> 16) & 0xFF) * 299
                        + ((colour >> 8) & 0xFF) * 587
                        + (colour & 0xFF) * 114) / 1000;
                gray[i] = (byte) value;
                if (value < minimum) minimum = value;
                if (value > maximum) maximum = value;
            }
            pixels = null;

            if (maximum > minimum) {
                int span = maximum - minimum;
                for (int i = 0; i < pixelCount; i++) {
                    gray[i] = (byte) (((gray[i] & 0xFF) - minimum) * 255 / span);
                }
            }
            int corner = gray[0] & 0xFF;

            int left = 0;
            int top = 0;
            int right = width - 1;
            int bottom = height - 1;
            int boxLeft = width;
            int boxTop = height;
            int boxRight = -1;
            int boxBottom = -1;
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    if ((gray[row + x] & 0xFF) != corner) {
                        if (x < boxLeft) boxLeft = x;
                        if (x > boxRight) boxRight = x;
                        if (y < boxTop) boxTop = y;
                        if (y > boxBottom) boxBottom = y;
                    }
                }
            }
            if (boxRight >= boxLeft && boxBottom >= boxTop) {
                left = boxLeft;
                top = boxTop;
                right = boxRight;
                bottom = boxBottom;
            }
            int cropWidth = right - left + 1;
            int cropHeight = bottom - top + 1;

            int[] croppedPixels = new int[cropWidth * cropHeight];
            for (int y = 0; y < cropHeight; y++) {
                int from = (top + y) * width + left;
                int to = y * cropWidth;
                for (int x = 0; x < cropWidth; x++) {
                    int value = gray[from + x] & 0xFF;
                    croppedPixels[to + x] = 0xFF000000 | (value << 16) | (value << 8) | value;
                }
            }
            cropped = Bitmap.createBitmap(croppedPixels, cropWidth, cropHeight, Bitmap.Config.ARGB_8888);
            croppedPixels = null;

            double scale = Math.min(1.0, Math.min((double) max / cropWidth, (double) max / cropHeight));
            int outWidth = Math.max(1, (int) Math.round(cropWidth * scale));
            int outHeight = Math.max(1, (int) Math.round(cropHeight * scale));
            scaled = cropped;
            if (outWidth != cropWidth || outHeight != cropHeight) {
                scaled = Bitmap.createScaledBitmap(cropped, outWidth, outHeight, true);
                if (scaled != cropped) {
                    cropped.recycle();
                    cropped = null;
                }
            }

            boolean written = writeBitmap(scaled, new File(out), "png", 100);
            if (!written) return null;

            JSONObject result = new JSONObject();
            result.put("path", out);
            result.put("width", scaled.getWidth());
            result.put("height", scaled.getHeight());
            return result;
        } catch (Throwable t) {
            Log.w(LOG_TAG, "image.normalize failed", t);
            return null;
        } finally {
            if (cropped != null) cropped.recycle();
            if (scaled != null) scaled.recycle();
        }
    }

    /** Place pictures left to right, top aligned and with no gaps, on a
     *  transparent canvas, and write the PNG.
     */
    private JSONObject imageMontage(JSONObject args) {
        ArrayList<Bitmap> pictures = new ArrayList<Bitmap>();
        Bitmap canvasBitmap = null;
        try {
            String out = args.optString("out", "");
            JSONArray paths = args.optJSONArray("paths");
            if (out.isEmpty() || paths == null) return null;

            int totalWidth = 0;
            int tallest = 0;
            for (int i = 0; i < paths.length(); i++) {
                String path = paths.optString(i, "");
                if (path.isEmpty()) continue;
                File file = new File(path);
                if (!file.isFile()) continue;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bitmap == null) continue;
                pictures.add(bitmap);
                totalWidth += bitmap.getWidth();
                if (bitmap.getHeight() > tallest) tallest = bitmap.getHeight();
            }
            if (pictures.isEmpty() || totalWidth <= 0 || tallest <= 0) return null;

            canvasBitmap = Bitmap.createBitmap(totalWidth, tallest, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(canvasBitmap);
            int x = 0;
            for (Bitmap picture : pictures) {
                canvas.drawBitmap(picture, x, 0, null);
                x += picture.getWidth();
            }

            boolean written = writeBitmap(canvasBitmap, new File(out), "png", 100);
            if (!written) return null;

            JSONObject result = new JSONObject();
            result.put("path", out);
            result.put("width", totalWidth);
            result.put("height", tallest);
            return result;
        } catch (Throwable t) {
            Log.w(LOG_TAG, "image.montage failed", t);
            return null;
        } finally {
            for (Bitmap picture : pictures) picture.recycle();
            if (canvasBitmap != null) canvasBitmap.recycle();
        }
    }

    /** Raw dimensions of a video's picture, as the retriever reports them. */
    private static int[] videoFrameSize(java.io.File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            int width = 0;
            int height = 0;
            try {
                width = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            } catch (Throwable ignored) { }
            try {
                height = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            } catch (Throwable ignored) { }
            if (width <= 0 || height <= 0) return null;
            return new int[] { width, height };
        } catch (Throwable t) {
            return null;
        } finally {
            try { retriever.release(); } catch (Throwable ignored) { }
        }
    }

    /** Raw width and height of a PDF's first page, in points. */
    private static int[] pdfPageSize(java.io.File file) {
        ParcelFileDescriptor fd = null;
        PdfRenderer renderer = null;
        try {
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(fd);
            if (renderer.getPageCount() < 1) return null;
            PdfRenderer.Page page = renderer.openPage(0);
            try {
                int width = page.getWidth();
                int height = page.getHeight();
                if (width <= 0 || height <= 0) return null;
                return new int[] { width, height };
            } finally {
                page.close();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (renderer != null) renderer.close(); } catch (Throwable ignored) { }
            try { if (fd != null) fd.close(); } catch (Throwable ignored) { }
        }
    }

    /** The first frame of a video, at the size the file stores. */
    private static Bitmap videoFrame(java.io.File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            Bitmap bitmap = retriever.getFrameAtTime();
            if (bitmap == null) bitmap = retriever.getFrameAtTime(0);
            return bitmap;
        } catch (Throwable t) {
            return null;
        } finally {
            try { retriever.release(); } catch (Throwable ignored) { }
        }
    }

    /** A PDF page at its natural size: decodePdfPage fits a square, so it is
     *  given the page's own longest side and does not scale. */
    private static Bitmap decodePdfNatural(java.io.File file) {
        int[] size = pdfPageSize(file);
        if (size == null) return null;
        return decodePdfPage(file, Math.max(size[0], size[1]));
    }

    /** An image, a video's first frame or a PDF's first page with no EXIF
     *  rotation — the raw pixels a colour-space conversion wants. */
    private static Bitmap decodeUnoriented(java.io.File file) {
        if (isVideo(file)) return videoFrame(file);
        if ("pdf".equals(extensionOf(file.getName()))) return decodePdfNatural(file);
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    /** A JS-style truthy argument: true, a non-zero number or "true"/"1"/... */
    private static boolean truthy(JSONObject args, String key) {
        Object value = args == null ? null : args.opt(key);
        if (value == null || value == JSONObject.NULL) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        String text = String.valueOf(value).trim().toLowerCase();
        return text.equals("true") || text.equals("1") || text.equals("yes") || text.equals("on");
    }

    /** Fill a square of `size` from the centre of the picture, then write it.
     *
     *  The avatars and covers used `magick <file> -resize NxN^ -gravity center
     *  -extent NxN -quality Q`; this scales until both sides reach `size` and
     *  crops the middle, which is what that command did.
     */
    private JSONObject imageCover(JSONObject args) {
        Bitmap bitmap = null;
        Bitmap scaled = null;
        Bitmap cropped = null;
        try {
            String path = args.optString("path", "");
            String out = args.optString("out", "");
            int size = args.optInt("size", 160);
            int quality = args.optInt("quality", 82);
            if (out.isEmpty() || size < 1) return null;
            File source = new File(path);
            if (!source.isFile()) return null;

            bitmap = decodeOriented(source, size);
            if (bitmap == null) return null;

            double scale = Math.max((double) size / bitmap.getWidth(), (double) size / bitmap.getHeight());
            int width = Math.max(size, (int) Math.round(bitmap.getWidth() * scale));
            int height = Math.max(size, (int) Math.round(bitmap.getHeight() * scale));
            scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = null;

            int left = Math.max(0, (width - size) / 2);
            int top = Math.max(0, (height - size) / 2);
            cropped = Bitmap.createBitmap(scaled, left, top, size, size);
            if (cropped != scaled) scaled.recycle();
            scaled = null;

            boolean written = writeBitmap(cropped, new File(out), extensionOf(out), quality);
            if (!written) return null;

            JSONObject result = new JSONObject();
            result.put("path", out);
            result.put("width", size);
            result.put("height", size);
            return result;
        } catch (Throwable t) {
            Log.w(LOG_TAG, "image.cover failed", t);
            return null;
        } finally {
            if (cropped != null) cropped.recycle();
            if (scaled != null) scaled.recycle();
            if (bitmap != null) bitmap.recycle();
        }
    }

    /** Resize an image to fit width × height without upscaling.
     *
     *  The tree's camera and upload paths used `magick <file> -resize WxH
     *  <file>`; this decodes with an inSampleSize and scales once, then writes
     *  the same format (JPEG/PNG/WebP) through a temp file. */
    private JSONObject imageResize(JSONObject args) {
        try {
            String path = args.optString("path", "");
            String out = args.optString("out", path);
            int width = args.optInt("width", 0);
            int height = args.optInt("height", 0);
            int max = args.optInt("max", 0);
            File source = new File(path);
            if (!source.isFile()) return null;

            // Decode through the EXIF orientation, so both the pixels and the
            // dimensions the target is computed from are the ones a person sees.
            // The sampler is told both target axes, so a very large photo is not
            // decoded whole for a 4K target.
            Bitmap bitmap;
            if (width > 0 && height > 0) {
                bitmap = decodeOrientedSize(source, width, height);
            }
            else {
                bitmap = decodeOriented(source, Math.max(1024, max));
            }
            if (bitmap == null) return null;

            int sourceWidth = bitmap.getWidth();
            int sourceHeight = bitmap.getHeight();
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                bitmap.recycle();
                return null;
            }

            if (width <= 0 && height <= 0 && max > 0) {
                width = max;
                height = max;
            }
            if (width <= 0 && height <= 0) {
                width = sourceWidth;
                height = sourceHeight;
            }

            double scale = Math.min((double) width / sourceWidth, (double) height / sourceHeight);
            if (scale > 1) scale = 1;
            int targetWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
            int targetHeight = Math.max(1, (int) Math.round(sourceHeight * scale));

            Bitmap scaled = bitmap;
            if (bitmap.getWidth() != targetWidth || bitmap.getHeight() != targetHeight) {
                scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
                if (scaled != bitmap) bitmap.recycle();
            }
            boolean written = writeBitmap(scaled, new File(out), extensionOf(out), 90);
            scaled.recycle();
            if (!written) return null;

            JSONObject result = new JSONObject();
            result.put("path", out);
            result.put("width", targetWidth);
            result.put("height", targetHeight);
            return result;
        } catch (Throwable t) {
            Log.w(LOG_TAG, "image.resize failed", t);
            return null;
        }
    }

    /** Decode an image and write it as args.out, at args.format/args.quality. */
    private JSONObject imageSave(JSONObject args) {
        try {
            String path = args.optString("path", "");
            String out = args.optString("out", "");
            if (out.isEmpty()) return null;
            Bitmap bitmap = decodeOriented(new File(path), 4096);
            if (bitmap == null) return null;
            String format = args.optString("format", extensionOf(out));
            int quality = args.optInt("quality", 90);
            boolean written = writeBitmap(bitmap, new File(out), format, quality);
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            bitmap.recycle();
            if (!written) return null;

            JSONObject result = new JSONObject();
            result.put("path", out);
            result.put("width", width);
            result.put("height", height);
            return result;
        } catch (Throwable t) {
            Log.w(LOG_TAG, "image.save failed", t);
            return null;
        }
    }

    /** Apply the palette's edit steps to a picture, natively.
     *
     *  `/manager/palette` used to shell out once per step:
     *  `magick <file> -<duty> <value> <file>`. These are the same steps in
     *  Bitmap and Canvas terms, so the palette keeps working on a phone where
     *  magick is not installed. `fill` is accepted and left alone: under magick
     *  the bare `-fill` only set a setting and changed no pixel. An unknown
     *  duty is named in `skipped` instead of failing the whole list.
     */
    private JSONObject imageEdit(JSONObject args) {
        Bitmap bitmap = null;
        try {
            String path = args.optString("path", "");
            String out = args.optString("out", path);
            JSONArray steps = args.optJSONArray("duties");
            bitmap = decodeOriented(new File(path), 4096);
            if (bitmap == null) return null;

            JSONArray skipped = new JSONArray();
            int applied = 0;
            if (steps != null) {
                for (int i = 0; i < steps.length(); i++) {
                    JSONObject step = steps.optJSONObject(i);
                    if (step == null) continue;
                    String duty = step.optString("duty", "");
                    String value = step.optString("value", "");
                    Bitmap next;
                    try {
                        next = applyImageDuty(bitmap, duty, value);
                    } catch (Throwable t) {
                        next = null;
                    }
                    if (next == null) {
                        skipped.put(duty);
                        continue;
                    }
                    if (next != bitmap) bitmap.recycle();
                    bitmap = next;
                    applied++;
                }
            }

            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            boolean written = writeBitmap(bitmap, new File(out), extensionOf(out), 92);
            if (!written) return null;

            JSONObject result = new JSONObject();
            result.put("path", out);
            result.put("width", width);
            result.put("height", height);
            result.put("applied", applied);
            result.put("skipped", skipped);
            return result;
        } catch (Throwable t) {
            Log.w(LOG_TAG, "image.edit failed", t);
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    /** One palette step; null means the duty is unknown and the picture is left alone. */
    private static Bitmap applyImageDuty(Bitmap bitmap, String duty, String value) {
        switch (duty) {
            case "rotate":
                return rotateBitmap(bitmap, dutyNumber(value, 90));
            case "crop":
                return cropBitmap(bitmap, value);
            case "modulate":
                return colorizedBitmap(bitmap, brightnessMatrix(dutyNumber(value, 100)));
            case "level":
                return colorizedBitmap(bitmap, levelMatrix(dutyNumber(value, 0)));
            case "black-threshold":
                return thresholdBitmap(bitmap, dutyNumber(value, 50));
            case "gaussian-blur":
                return blurBitmap(bitmap, dutyNumber(value, 3));
            case "sharpen":
                return sharpenBitmap(bitmap, dutyNumber(value, 2));
            case "fill":
                // magick's bare -fill changed no pixel, so neither does this
                return bitmap;
            default:
                return null;
        }
    }

    /** The first number in a step value, so "25%", "3" and " 90 " all read. */
    private static double dutyNumber(String value, double fallback) {
        if (value == null) return fallback;
        java.util.regex.Matcher match = java.util.regex.Pattern
            .compile("[-+]?[0-9]*\\.?[0-9]+")
            .matcher(value.trim());
        if (!match.find()) return fallback;
        try {
            return Double.parseDouble(match.group());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static Bitmap rotateBitmap(Bitmap bitmap, double degrees) {
        double turn = degrees % 360;
        if (turn == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate((float) turn);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        return rotated == null ? bitmap : rotated;
    }

    /** magick's crop geometry: WxH+X+Y, with a negative offset allowed. */
    private static Bitmap cropBitmap(Bitmap bitmap, String geometry) {
        java.util.regex.Matcher match = java.util.regex.Pattern
            .compile("^\\s*([0-9]+)x([0-9]+)([+-][0-9]+)([+-][0-9]+)\\s*$")
            .matcher(geometry == null ? "" : geometry);
        if (!match.find()) return null;
        int width = Integer.parseInt(match.group(1));
        int height = Integer.parseInt(match.group(2));
        int x = Integer.parseInt(match.group(3));
        int y = Integer.parseInt(match.group(4));
        if (width < 1 || height < 1) return null;
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(bitmap.getWidth(), x + width);
        int bottom = Math.min(bitmap.getHeight(), y + height);
        if (right - left < 1 || bottom - top < 1) return null;
        if (left == 0 && top == 0 && right == bitmap.getWidth() && bottom == bitmap.getHeight()) {
            return bitmap;
        }
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);
    }

    private static Bitmap colorizedBitmap(Bitmap bitmap, ColorMatrix matrix) {
        Bitmap out = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        if (out == null) return null;
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(bitmap, 0, 0, paint);
        return out;
    }

    /** magick -modulate <percent>: brightness about the black point. */
    private static ColorMatrix brightnessMatrix(double percent) {
        float scale = (float) Math.max(0.0, percent / 100.0);
        return new ColorMatrix(new float[] {
            scale, 0, 0, 0, 0,
            0, scale, 0, 0, 0,
            0, 0, scale, 0, 0,
            0, 0, 0, 1, 0,
        });
    }

    /** magick -level <percent>%: raise the black point, keep white at white. */
    private static ColorMatrix levelMatrix(double percent) {
        double black = Math.min(0.95, Math.max(0.0, percent / 100.0));
        float scale = (float) (1.0 / (1.0 - black));
        float offset = (float) (-black * 255.0 / (1.0 - black));
        return new ColorMatrix(new float[] {
            scale, 0, 0, 0, offset,
            0, scale, 0, 0, offset,
            0, 0, scale, 0, offset,
            0, 0, 0, 1, 0,
        });
    }

    /** magick -black-threshold: pixels at or below the luma limit become black. */
    private static Bitmap thresholdBitmap(Bitmap bitmap, double percent) {
        int limit = (int) Math.round(Math.min(100.0, Math.max(0.0, percent)) * 255 / 100.0);
        Bitmap out = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return null;
        int width = out.getWidth();
        int height = out.getHeight();
        int[] pixels = new int[width];
        for (int y = 0; y < height; y++) {
            out.getPixels(pixels, 0, width, 0, y, width, 1);
            boolean touched = false;
            for (int x = 0; x < width; x++) {
                int p = pixels[x];
                int luma = (((p >> 16) & 0xff) * 299 + ((p >> 8) & 0xff) * 587 + (p & 0xff) * 114) / 1000;
                if (luma <= limit) {
                    pixels[x] = (p >>> 24) << 24;
                    touched = true;
                }
            }
            if (touched) out.setPixels(pixels, 0, width, 0, y, width, 1);
        }
        return out;
    }

    /** magick -gaussian-blur <sigma>: three box passes stand in for the Gaussian. */
    private static Bitmap blurBitmap(Bitmap bitmap, double sigma) {
        int radius = (int) Math.max(1, Math.round(sigma));
        Bitmap out = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return null;
        int width = out.getWidth();
        int height = out.getHeight();
        int[] pixels = new int[width * height];
        int[] scratch = new int[pixels.length];
        out.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int pass = 0; pass < 3; pass++) {
            boxBlurPass(pixels, scratch, width, height, radius, true);
            boxBlurPass(scratch, pixels, width, height, radius, false);
        }
        out.setPixels(pixels, 0, width, 0, 0, width, height);
        return out;
    }

    /** A running-sum box blur along one axis; runs in O(pixels), not O(pixels * radius). */
    private static void boxBlurPass(int[] src, int[] dst, int width, int height, int radius, boolean horizontal) {
        int span = radius * 2 + 1;
        int lines = horizontal ? height : width;
        int length = horizontal ? width : height;
        for (int line = 0; line < lines; line++) {
            long a = 0;
            long r = 0;
            long g = 0;
            long b = 0;
            for (int k = -radius; k <= radius; k++) {
                int index = horizontal
                    ? line * width + clampIndex(k, width)
                    : clampIndex(k, height) * width + line;
                int p = src[index];
                a += (p >>> 24);
                r += (p >> 16) & 0xff;
                g += (p >> 8) & 0xff;
                b += p & 0xff;
            }
            for (int i = 0; i < length; i++) {
                int index = horizontal ? line * width + i : i * width + line;
                dst[index] = ((int) (a / span) << 24)
                    | ((int) (r / span) << 16)
                    | ((int) (g / span) << 8)
                    | (int) (b / span);
                int leaving = horizontal
                    ? src[line * width + clampIndex(i - radius, width)]
                    : src[clampIndex(i - radius, height) * width + line];
                int entering = horizontal
                    ? src[line * width + clampIndex(i + radius + 1, width)]
                    : src[clampIndex(i + radius + 1, height) * width + line];
                a += (entering >>> 24) - (leaving >>> 24);
                r += ((entering >> 16) & 0xff) - ((leaving >> 16) & 0xff);
                g += ((entering >> 8) & 0xff) - ((leaving >> 8) & 0xff);
                b += (entering & 0xff) - (leaving & 0xff);
            }
        }
    }

    private static int clampIndex(int index, int size) {
        if (index < 0) return 0;
        if (index >= size) return size - 1;
        return index;
    }

    /** magick -sharpen <sigma>: a 3x3 kernel whose strength follows sigma. */
    private static Bitmap sharpenBitmap(Bitmap bitmap, double sigma) {
        double amount = Math.min(0.25, Math.max(0.05, sigma / 16.0));
        float center = (float) (1.0 + 8.0 * amount);
        float edge = (float) -amount;
        float[] kernel = {
            edge, edge, edge,
            edge, center, edge,
            edge, edge, edge,
        };
        Bitmap out = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return null;
        int width = out.getWidth();
        int height = out.getHeight();
        int[] src = new int[width * height];
        int[] dst = new int[src.length];
        out.getPixels(src, 0, width, 0, 0, width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float a = 0;
                float r = 0;
                float g = 0;
                float b = 0;
                int k = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int row = clampIndex(y + dy, height) * width;
                    for (int dx = -1; dx <= 1; dx++, k++) {
                        int p = src[row + clampIndex(x + dx, width)];
                        float w = kernel[k];
                        a += (p >>> 24) * w;
                        r += ((p >> 16) & 0xff) * w;
                        g += ((p >> 8) & 0xff) * w;
                        b += (p & 0xff) * w;
                    }
                }
                dst[y * width + x] = (clampChannel(a) << 24) | (clampChannel(r) << 16)
                    | (clampChannel(g) << 8) | clampChannel(b);
            }
        }
        out.setPixels(dst, 0, width, 0, 0, width, height);
        return out;
    }

    private static int clampChannel(float value) {
        if (value <= 0) return 0;
        if (value >= 255) return 255;
        return Math.round(value);
    }

    private static int sampleSize(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        int sample = 1;
        while (sourceWidth / (sample * 2) >= targetWidth
            && sourceHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        return sample;
    }

    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
    }

    private static boolean writeBitmap(Bitmap bitmap, File out, String format, int quality) {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        File temp = new File(out.getPath() + ".part");
        try (FileOutputStream stream = new FileOutputStream(temp)) {
            String kind = format == null ? "" : format.toLowerCase();
            Bitmap.CompressFormat compress = kind.equals("png") ? Bitmap.CompressFormat.PNG
                : kind.equals("webp") ? Bitmap.CompressFormat.WEBP
                : Bitmap.CompressFormat.JPEG;
            int useQuality = compress == Bitmap.CompressFormat.PNG ? 100 : Math.max(1, Math.min(100, quality));
            if (!bitmap.compress(compress, useQuality, stream)) {
                temp.delete();
                return false;
            }
        } catch (Throwable t) {
            temp.delete();
            return false;
        }
        if (!temp.renameTo(out)) {
            out.delete();
            if (!temp.renameTo(out)) {
                temp.delete();
                return false;
            }
        }
        return true;
    }

}
