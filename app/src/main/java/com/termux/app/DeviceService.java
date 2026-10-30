package com.termux.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.media.AudioManager;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
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

import com.termux.R;

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
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
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
            else if ("toast.show".equals(method)) {
                JSONObject args = request.optJSONObject("args");
                String message = args == null ? "" : args.optString("message", "");
                boolean longToast = args != null && args.optBoolean("long", false);
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
                boolean on = args != null && args.optBoolean("on", false);
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
        boolean silent = args.optBoolean("silent", false);
        boolean ongoing = args.optBoolean("ongoing", false);

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
               .setAutoCancel(!ongoing && args.optBoolean("auto_cancel", true))
               .setShowWhen(true);
        if (ongoing || args.optBoolean("ongoing", false)) builder.setOngoing(true);
        if (silent || args.optBoolean("only_alert_once", false)) builder.setOnlyAlertOnce(true);

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
                progress.optInt("current", 0), progress.optBoolean("indeterminate", false));
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
}
