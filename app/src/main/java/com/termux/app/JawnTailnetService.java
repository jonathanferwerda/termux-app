package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.system.OsConstants;

import androidx.core.app.NotificationCompat;

import com.termux.R;

import java.util.UUID;

import libtailscale.IPNService;
import libtailscale.Libtailscale;
import libtailscale.VPNServiceBuilder;

/**
 * The JawnOS Tailnet: the Android VpnService half of the embedded Tailscale
 * client. The Go backend is libtailscale (built from tailscale-android); this
 * service gives it the permission, the notification, and the TUN builder.
 */
public class JawnTailnetService extends VpnService implements IPNService {
    public static final String ACTION_START = "com.termux.app.TAILNET_START";
    public static final String ACTION_STOP = "com.termux.app.TAILNET_STOP";
    public static final String ACTION_FOREGROUND_ONLY = "com.termux.app.TAILNET_FOREGROUND";

    private static final String CHANNEL = "jawn_tailnet";
    private static final String LOGIN_CHANNEL = "jawn_tailnet_login";
    private static final int NOTIF_ID = 0x4A41;
    private static final int LOGIN_ID = 0x4A42;

    private final String id = UUID.randomUUID().toString();
    private boolean closed = false;
    private JawnTailnetController controller;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile long lastNetworkAt = 0;
    private volatile boolean restartPending = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel(CHANNEL, "JawnOS Tailnet", NotificationManager.IMPORTANCE_LOW);
        createChannel(LOGIN_CHANNEL, "JawnOS Tailnet login", NotificationManager.IMPORTANCE_DEFAULT);
        controller = new JawnTailnetController(this);
        controller.startWatching();
        watchNetwork();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            controller.stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_FOREGROUND_ONLY.equals(action)) {
            startForeground(NOTIF_ID, notification("Waiting for approval"));
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, notification("Starting"));
        // the local API calls can wait on the backend; keep the UI thread free
        new Thread(() -> controller.start(), "jawn-tailnet-start").start();
        return START_STICKY;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public VPNServiceBuilder newBuilder() {
        Builder builder = new Builder()
                .setSession("JawnOS Tailnet")
                .allowFamily(OsConstants.AF_INET)
                .allowFamily(OsConstants.AF_INET6);
        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false);
        }
        return new JawnTailnetBuilder(builder);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        disconnectVPN();
        Libtailscale.serviceDisconnect(this);
    }

    @Override
    public void disconnectVPN() {
        stopSelf();
    }

    @Override
    public void updateVpnStatus(boolean status) {
        JawnTailnetController.setStatus(this, status ? "connected" : "disconnected");
        updateNotification(status ? "Connected" : "Disconnected");
    }

    @Override
    public void onDestroy() {
        unwatchNetwork();
        close();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        unwatchNetwork();
        controller.stop();
        close();
        super.onRevoke();
    }

    /** The phone can change its default network (Wi-Fi to cellular, a new
     *  Wi-Fi) while the tunnel is up. The Go client can keep using the old
     *  route, so a settled change asks the controller to stop and start the
     *  tunnel. The request excludes VPN transports, which keeps the tunnel
     *  itself from triggering the watcher. */
    private void watchNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                scheduleRestart();
            }

            @Override
            public void onLost(Network network) {
                scheduleRestart();
            }
        };
        try {
            cm.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            networkCallback = null;
        }
    }

    private void unwatchNetwork() {
        if (networkCallback == null) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
        networkCallback = null;
    }

    /** Debounce a switch (a Wi-Fi change often arrives as a loss and an
     *  availability seconds apart), then restart the tunnel once the network
     *  has been stable for two seconds. */
    private void scheduleRestart() {
        lastNetworkAt = System.currentTimeMillis();
        if (restartPending) return;
        restartPending = true;
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (System.currentTimeMillis() - lastNetworkAt < 2000) continue;
                break;
            }
            restartPending = false;
            if (JawnTailnetController.isEnabled(JawnTailnetService.this)) {
                controller.restart();
            }
        }, "jawn-tailnet-network").start();
    }

    /** Called from the Go notification watcher when Headscale needs approval. */
    public void notifyLogin(String url) {
        Intent open = new Intent(this, JawnWebViewActivity.class)
                .putExtra(JawnWebViewActivity.EXTRA_URL, url);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, LOGIN_CHANNEL)
                .setSmallIcon(R.drawable.jawn_logo)
                .setContentTitle("JawnOS Tailnet")
                .setContentText("Tap to approve this device")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(LOGIN_ID, n);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, JawnDashboardActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, JawnTailnetService.class).setAction(ACTION_STOP);
        PendingIntent sp = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.jawn_logo)
                .setContentTitle("JawnOS Tailnet")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(0, "Stop", sp)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, notification(text));
    }

    private void createChannel(String channelId, String name, int importance) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(channelId, name, importance));
        }
    }
}
