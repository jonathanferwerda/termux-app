package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        startForeground(NOTIFICATION_ID, notification());

        token = ensureToken();
        clients = Executors.newFixedThreadPool(MAX_CLIENTS);
        acceptor = new Thread(this::acceptLoop, "jawn-device-accept");
        acceptor.start();
        Log.i(LOG_TAG, "device bridge listening on 127.0.0.1:" + PORT);
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
            else if ("battery.status".equals(method)) {
                reply.put("ok", true);
                reply.put("result", battery());
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
}
