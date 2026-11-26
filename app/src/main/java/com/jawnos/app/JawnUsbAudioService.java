package com.jawnos.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.jawnos.R;

/**
 * The process anchor for a native USB take ({@link JawnUsbAudio}).
 *
 * A HAL take is anchored by {@link JawnAudioService}, which starts the engine
 * itself. A USB take starts the engine through the holder instead, so it gets
 * its own small service: one service class owns one kind of take, and neither
 * service can end the other's take by accident. This one never starts or stops
 * {@link JawnAudioEngine} directly - it starts foreground, holds the wake lock,
 * and in {@link #onDestroy()} asks the holder to end the take when it is still
 * live. The holder then does the engine-first stop (the engine joins its USB
 * worker, then every claim is released and the fd closed).
 *
 * The foreground type is microphone|connectedDevice, the same pair the HAL
 * service claims: connectedDevice is the USB input, microphone keeps the two
 * takes symmetric. A device without a RECORD_AUDIO grant still gets the
 * connectedDevice type, so a USB-only take is anchored even then.
 */
public class JawnUsbAudioService extends Service {

    public static final String ACTION_START = "com.jawnos.app.USB_AUDIO_START";
    public static final String ACTION_STOP = "com.jawnos.app.USB_AUDIO_STOP";

    private static final String TAG = "JawnUsbAudioService";
    private static final String CHANNEL = "jawn_usb_audio";
    /** A distinct id from the HAL take's, so neither notification replaces the other. */
    private static final int NOTIFICATION_ID = 0x4A46;
    private static final String WAKE_LOCK_TAG = "JawnOS:usb-audio-take";

    private PowerManager.WakeLock wakeLock;

    // ------------------------------------------------------------------ public

    /**
     * Start the anchor. Answers false when Android refused the foreground
     * start (for example a background start on Android 12+), so the caller can
     * refuse the take instead of running it unanchored.
     */
    public static boolean start(Context context) {
        Intent intent = new Intent(context, JawnUsbAudioService.class)
                .setAction(ACTION_START);
        try {
            ContextCompat.startForegroundService(context, intent);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not start the USB take anchor", t);
            return false;
        }
    }

    /** Ask the anchor to stop; its {@code onDestroy} ends a still-live take. */
    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, JawnUsbAudioService.class));
        } catch (Throwable t) {
            Log.w(TAG, "could not stop the USB take anchor", t);
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
            // The notification's Stop action. onDestroy ends the take, so the
            // stop itself stays off the main thread there.
            stopSelf();
            return START_NOT_STICKY;
        }

        // A null intent is Android restarting the process: the holder lives in
        // memory only, so there is no take to resume. Never claim one silently.
        if (intent == null || !ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!startForegroundCompat("USB take running")) {
            Log.w(TAG, "the USB take anchor could not start a foreground service");
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireWakeLock();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // End the take while the connection is still ours. The holder's stop
        // runs the engine first (it joins its USB worker and closes the WAV),
        // then releases the claims and closes the fd - keep that off the main
        // thread, where it could block.
        JawnUsbAudio holder = JawnUsbAudio.shared(this);
        if (holder.isLive()) {
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        holder.stop();
                    } catch (Throwable t) {
                        Log.w(TAG, "the USB take could not stop", t);
                    }
                }
            }, "jawn-usb-anchor-stop");
            worker.setDaemon(true);
            worker.start();
        }
        releaseWakeLock();
        stopForegroundCompat();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ internals

    /** A partial wake lock keeps the CPU on for the stream while the screen
     *  sleeps; the foreground service is what keeps the process alive. */
    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
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
            Log.w(TAG, "startForeground(microphone|connectedDevice) failed", t);
        }
        // A USB take reads no microphone, so a missing RECORD_AUDIO grant must
        // not leave it unanchored: connectedDevice alone still keeps the
        // process and the wake lock alive for the USB stream.
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startForeground(connectedDevice) failed", t);
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
        Intent stop = new Intent(this, JawnUsbAudioService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop,
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

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "JawnOS USB take", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
