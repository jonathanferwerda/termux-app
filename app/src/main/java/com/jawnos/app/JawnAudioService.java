package com.jawnos.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.jawnos.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The process anchor for a Studio take. It owns the Rust engine
 * ({@link JawnAudioEngine}), records into app-private storage and stays
 * foreground while the take runs, so a page reload - or the screen going off -
 * does not end it.
 *
 * The foreground type is {@code microphone|connectedDevice}: microphone is the
 * capture itself, and connectedDevice covers a USB input. Android 14+ refuses
 * the microphone type unless RECORD_AUDIO is already granted, so a missing
 * grant is answered with a sentence and the service stops before it ever claims
 * the type.
 *
 * Capture alone needs no audio focus (docs/studio-oboe-slice.md §6): there is
 * deliberately no AudioFocusRequest here. When the monitoring half lands it
 * takes AUDIOFOCUS_GAIN_TRANSIENT for one take and mutes the monitor on
 * AUDIOFOCUS_LOSS_TRANSIENT without dropping the recording.
 *
 * The engine's DiskWriter writes as it records and closes the WAV when
 * {@code jawn_audio_stop} joins its worker, so {@link #onDestroy()} stops the
 * engine first, then drops the wake lock.
 */
public class JawnAudioService extends Service {

    public static final String ACTION_START = "com.jawnos.app.AUDIO_START";
    public static final String ACTION_STOP = "com.jawnos.app.AUDIO_STOP";
    public static final String EXTRA_DEVICE_ID = "device_id";
    public static final String EXTRA_SECONDS = "seconds";
    public static final String EXTRA_OUT = "out";
    /** One Channel plan's JSON, empty on the raw path. */
    public static final String EXTRA_PLAN = "plan";

    /** Negative asks the HAL to pick the input, the same rule the engine uses. */
    public static final int DEFAULT_DEVICE = -1;

    private static final String TAG = "JawnAudioService";
    private static final String CHANNEL = "jawn_audio";
    private static final int NOTIFICATION_ID = 0x4A45;

    private static volatile String lastStatus = "";

    private final LocalBinder binder = new LocalBinder();

    private PowerManager.WakeLock wakeLock;
    private boolean running = false;
    private String takePath = "";

    // ------------------------------------------------------------------ public

    /** True when the person has granted the runtime microphone permission. */
    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** The last JSON this process produced, for a caller that is not bound. */
    public static String latestStatus() {
        return lastStatus;
    }

    /**
     * Start a take. Refuses up front, with a sentence, when RECORD_AUDIO is
     * missing: starting a foreground service and then stopping it would leave
     * the notification half-armed, and Android 14 would refuse the microphone
     * type anyway.
     */
    public static String start(Context context, int deviceId, double seconds, String out) {
        return start(context, deviceId, seconds, out, "");
    }

    /**
     * Start a take through one Channel plan. An empty {@code plan} is the raw
     * path and is exactly the four-argument start above.
     */
    public static String start(Context context, int deviceId, double seconds, String out,
            String plan) {
        if (!hasPermission(context)) {
            lastStatus = errorJson("the microphone permission is not granted; "
                    + "allow RECORD_AUDIO to record a take");
            return lastStatus;
        }
        Intent intent = new Intent(context, JawnAudioService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_SECONDS, seconds)
                .putExtra(EXTRA_OUT, out == null ? "" : out)
                .putExtra(EXTRA_PLAN, plan == null ? "" : plan);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable t) {
            lastStatus = errorJson("the audio service could not start: " + t);
            return lastStatus;
        }
        return "{\"ok\":true,\"state\":\"starting\"}";
    }

    /** Ask a running service to close the take and stop. */
    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, JawnAudioService.class));
        } catch (Throwable t) {
            Log.w(TAG, "could not stop the audio service", t);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            endTake();
            stopSelf();
            return START_NOT_STICKY;
        }

        // A null intent is Android restarting the process; a take's path and
        // duration live only in the caller's intent, so there is nothing to
        // resume. Never start a new take silently.
        if (intent == null || !ACTION_START.equals(action)) {
            lastStatus = errorJson("no take was requested");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!hasPermission(this)) {
            lastStatus = errorJson("the microphone permission is not granted; "
                    + "allow RECORD_AUDIO to record a take");
            Log.w(TAG, lastStatus);
            stopSelf();
            return START_NOT_STICKY;
        }

        int deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, DEFAULT_DEVICE);
        double seconds = intent.getDoubleExtra(EXTRA_SECONDS, 0d);
        String out = intent.getStringExtra(EXTRA_OUT);
        if (out == null || out.trim().isEmpty()) out = defaultTakePath();
        String plan = intent.getStringExtra(EXTRA_PLAN);
        if (plan == null) plan = "";

        if (!startForegroundCompat("Recording")) {
            lastStatus = errorJson("the microphone foreground service was refused");
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        acquireWakeLock();

        String answer = plan.trim().isEmpty()
                ? JawnAudioEngine.start(deviceId, seconds, out)
                : JawnAudioEngine.startPlan(deviceId, seconds, out, plan);
        lastStatus = answer;
        if (!JawnAudioEngine.running(answer)) {
            Log.w(TAG, "the take did not start: " + answer);
            running = false;
            releaseWakeLock();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        takePath = out;
        updateNotification("Recording " + new File(out).getName());
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // Stop first: the engine joins its worker and closes the WAV. A
        // system-initiated stop reaches here without onStartCommand(ACTION_STOP).
        if (running) {
            endTake();
        } else if (!JawnUsbAudio.shared(this).isLive()) {
            // The engine may belong to a native USB take, which is anchored by
            // JawnUsbAudioService and stopped by its own holder. A HAL teardown
            // must never end that take.
            JawnAudioEngine.stop();
        }
        releaseWakeLock();
        running = false;
        takePath = "";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ------------------------------------------------------------------- binder

    /** The service's control surface: start, stop and status without an Intent. */
    public class LocalBinder extends Binder {

        public JawnAudioService service() {
            return JawnAudioService.this;
        }

        public String start(int deviceId, double seconds, String out) {
            return JawnAudioService.start(JawnAudioService.this, deviceId, seconds, out);
        }

        public String start(int deviceId, double seconds, String out, String plan) {
            return JawnAudioService.start(JawnAudioService.this, deviceId, seconds, out, plan);
        }

        public String stop() {
            return endTake();
        }

        public String status() {
            return JawnAudioService.this.status();
        }
    }

    /** The engine's last report, or its live status while a take runs. */
    public String status() {
        String answer = JawnAudioEngine.status();
        if (answer != null && !answer.isEmpty()) return answer;
        return lastStatus;
    }

    public String takePath() {
        return takePath;
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ internals

    private String endTake() {
        String answer = running ? JawnAudioEngine.stop() : JawnAudioEngine.status();
        if (answer != null && !answer.isEmpty()) {
            lastStatus = answer;
            writeSidecar(takePath, answer);
        }
        running = false;
        takePath = "";
        releaseWakeLock();
        stopForegroundCompat();
        return lastStatus;
    }

    /**
     * The final report beside the take ({@code <take>.wav.json}) so the build
     * gate can pull it over adb. Best effort: a take without its sidecar is
     * still a take.
     */
    private void writeSidecar(String take, String report) {
        if (take == null || take.trim().isEmpty() || report == null) return;
        File sidecar = new File(take + ".json");
        try (FileWriter writer = new FileWriter(sidecar, false)) {
            writer.write(report);
            writer.write("\n");
        } catch (Throwable t) {
            Log.w(TAG, "could not write " + sidecar, t);
        }
    }

    /** A take lives in app-private storage; the manager imports the WAV later. */
    private String defaultTakePath() {
        File dir = new File(getFilesDir(), "studio");
        if (!dir.exists()) dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return new File(dir, "take-" + stamp + ".wav").getAbsolutePath();
    }

    /** A partial wake lock keeps the CPU on for audio while the screen sleeps;
     *  the foreground service is what keeps the process alive. */
    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JawnOS:audio-take");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        wakeLock = null;
    }

    private boolean startForegroundCompat(String text) {
        Notification notification = notification(text);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                                | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startForeground(type=microphone|connectedDevice) failed", t);
            return false;
        }
    }

    private void stopForegroundCompat() {
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, JawnDashboardActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, JawnAudioService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.jawn_logo)
                .setContentTitle("JawnOS Studio")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openIntent)
                .addAction(0, "Stop", stopIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification(text));
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "JawnOS Studio", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private static String errorJson(String error) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", 0);
            out.put("error", error);
        } catch (JSONException ignored) {
            return "{\"ok\":0,\"error\":\"the audio service reported an error\"}";
        }
        return out.toString();
    }
}
