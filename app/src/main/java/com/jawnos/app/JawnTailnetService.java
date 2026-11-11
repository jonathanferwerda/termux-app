package com.jawnos.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.system.OsConstants;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.jawnos.R;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import libtailscale.Application;
import libtailscale.IPNService;
import libtailscale.Libtailscale;
import libtailscale.LocalAPIResponse;
import libtailscale.VPNServiceBuilder;

/**
 * The JawnOS Tailnet: the Android VpnService half of the embedded Tailscale
 * client. The Go backend is libtailscale (built from tailscale-android); this
 * service gives it the permission, the notification, and the TUN builder.
 *
 * <p>It is also the tunnel's supervisor. A start that never reached Running
 * used to leave the status at "starting" for as long as the process lived, and
 * a tunnel that dropped on its own was never chased: the only wake-up was a
 * network change. The rules here are:
 *
 * <ul>
 *   <li>chase the tunnel only while it is enabled and autoconnect is on;</li>
 *   <li>leash every start: if the backend is not Running after half a minute,
 *       its own state replaces "starting" and the retry ladder takes over;</li>
 *   <li>the ladder is 5s, 15s, 60s, 5m, 15m (capped) and resets on connected;</li>
 *   <li>the short rungs live in a Handler; the same retry is mirrored into an
 *       allow-while-idle alarm, because a Handler dies with the process and
 *       freezes in Doze.</li>
 * </ul>
 */
public class JawnTailnetService extends VpnService implements IPNService {
    public static final String ACTION_START = "com.jawnos.app.TAILNET_START";
    public static final String ACTION_STOP = "com.jawnos.app.TAILNET_STOP";
    public static final String ACTION_FOREGROUND_ONLY = "com.jawnos.app.TAILNET_FOREGROUND";
    /** A nudge from outside (a wake broadcast): read the stored choices and the
     *  status, and only then start anything. */
    public static final String ACTION_SUPERVISE = "com.jawnos.app.TAILNET_SUPERVISE";
    /** The retry alarm's own action. It is kept separate from the nudges so an
     *  alarm armed under one build is never read as "start now" by another. */
    public static final String ACTION_RETRY = "com.jawnos.app.TAILNET_RETRY";

    private static final String CHANNEL = "jawn_tailnet";
    private static final String LOGIN_CHANNEL = "jawn_tailnet_login";
    private static final int NOTIF_ID = 0x4A41;
    private static final int LOGIN_ID = 0x4A42;
    private static final int RETRY_REQUEST = 0x4A43;
    private static final String TAG = "JawnTailnetService";

    /** The retry ladder, in order: rung = attempts - 1, capped at the last.
     *  A device that cannot join must not wake the radio all night, but a
     *  Wi-Fi blip should be back long before a person notices. */
    private static final long[] BACKOFF_MS = {
            5_000L, 15_000L, 60_000L, 5 * 60_000L, 15 * 60_000L
    };
    /** How long a start may sit at "starting" before the backend's real state
     *  replaces it. The local API calls behind start() are bounded by their own
     *  six-second timeouts, so half a minute is a generous leash. */
    private static final long START_VERIFY_MS = 30_000L;
    /** A person has to act here (approve the node, paste a key). Retrying
     *  faster only wakes the radio; the status already says what is needed. */
    private static final long SLOW_RETRY_MS = 15 * 60_000L;
    /** No physical network: the network callback is the fast path back, this is
     *  the safety net for a phone that slept through the return. */
    private static final long NO_NETWORK_RETRY_MS = 60_000L;
    /** Android spaces allow-while-idle alarms at least this far apart, so the
     *  alarm is a floor under the ladder, never the whole schedule. */
    private static final long ALARM_FLOOR_MS = 15 * 60_000L;

    /** The supervisor's own memory, separate from the controller's prefs: the
     *  controller owns the status vocabulary and the retry time it publishes,
     *  this file owns how far up the ladder it has climbed. */
    private static final String SUPERVISOR_PREFS = "jawn_tailnet_supervisor";
    private static final String KEY_ATTEMPTS = "attempts";

    private final String id = UUID.randomUUID().toString();
    private boolean closed = false;
    private JawnTailnetController controller;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Network underlying;
    private final Object restartLock = new Object();
    private volatile long lastNetworkAt = 0;
    private volatile boolean restartPending = false;

    // The supervisor. Decisions and scheduling run on the main looper; the
    // work that can block (a start, the backend probe) runs on its own thread.
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retry = this::retryNow;
    private final Runnable verify = this::verifyStart;
    private final Object attemptLock = new Object();
    private volatile boolean attemptRunning = false;
    private volatile boolean restartInFlight = false;
    /** When the current start's leash runs out, so a retry that fires
     *  mid-wait cannot stack a second start behind the first. */
    private volatile long leashUntil = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel(CHANNEL, "JawnOS Tailnet", NotificationManager.IMPORTANCE_LOW);
        createChannel(LOGIN_CHANNEL, "JawnOS Tailnet login", NotificationManager.IMPORTANCE_DEFAULT);
        controller = new JawnTailnetController(this);
        controller.startWatching();
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        underlying = JawnTailnetNetwork.pick(connectivityManager);
        JawnTailnetNetwork.set(connectivityManager, underlying);
        watchNetwork();
    }


    /** A long-lived local server has no dataSync budget: Android 15 cuts a
     *  dataSync foreground service off after roughly six hours a day and then
     *  crashes the whole app. specialUse is the type for a service of this
     *  shape (a local server the person asked to keep running). */
    private void startForegroundCompat(Notification n) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
        else {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            controller.stop();
            // the person stopped it: nothing may bring it back by itself
            clearRetry();
            supervisorPrefs().edit().putInt(KEY_ATTEMPTS, 0).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_FOREGROUND_ONLY.equals(action)) {
            startForegroundCompat(notification(label(JawnTailnetController.getStatus(this))));
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) && !JawnTailnetController.isEnabled(this)) {
            // Android re-binds a VPN service (always-on, or a sticky restart)
            // even after the user stopped it. The stored choice, not the
            // intent, decides whether the tunnel comes back.
            clearRetry();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RETRY.equals(action) || ACTION_SUPERVISE.equals(action)) {
            startForegroundCompat(notification(label(JawnTailnetController.getStatus(this))));
            if (!supervise()) {
                // nothing is enabled, or manual mode has nothing to chase
                stopSelf();
                return START_NOT_STICKY;
            }
            return START_STICKY;
        }
        startForegroundCompat(notification(label(JawnTailnetController.STARTING)));
        // the local API calls can wait on the backend; keep the UI thread free
        if (ACTION_START.equals(action)) {
            launchAttempt(true);
        }
        else {
            // a system restart of an enabled tunnel: no prefs edit, no new
            // login, just re-establish the TUN this process used to hold
            launchAttempt(false);
        }
        return START_STICKY;
    }

    // ------------------------------------------------------------- supervisor

    private SharedPreferences supervisorPrefs() {
        return getSharedPreferences(SUPERVISOR_PREFS, MODE_PRIVATE);
    }

    /** How many starts have failed since the tunnel was last connected. */
    private int attempts() {
        return supervisorPrefs().getInt(KEY_ATTEMPTS, 0);
    }

    /** The person's two switches: the tunnel is wanted, and it may heal itself.
     *  Both are checked before every automatic move, so turning either off is
     *  an immediate stop to the retry loop, not just a preference nobody reads. */
    private boolean shouldWatch() {
        return JawnTailnetController.isEnabled(this)
                && JawnTailnetController.isAutoconnect(this);
    }

    /** The next rung: 5s, 15s, 60s, 5m, then 15m for as long as it fails. */
    private long nextDelay() {
        int index = Math.min(Math.max(attempts(), 1) - 1, BACKOFF_MS.length - 1);
        return BACKOFF_MS[index];
    }

    /** How long to wait after a state that is not a failed start. */
    private long delayFor(String status) {
        if (JawnTailnetController.NEEDS_APPROVAL.equals(status)
                || JawnTailnetController.NEEDS_KEY.equals(status)
                || JawnTailnetController.NEEDS_SERVER.equals(status)
                || JawnTailnetController.ERROR.equals(status)) {
            return SLOW_RETRY_MS;
        }
        if (JawnTailnetController.NO_NETWORK.equals(status)) return NO_NETWORK_RETRY_MS;
        return nextDelay();
    }

    /** One supervision decision, from any nudge: the retry alarm, a wake
     *  broadcast, a sticky restart. True when the service should stay up. */
    private boolean supervise() {
        if (!JawnTailnetController.isEnabled(this)) {
            clearRetry();
            return false;
        }
        String status = JawnTailnetController.getStatus(this);
        if (JawnTailnetController.CONNECTED.equals(status)) {
            // already where autoconnect wants it; spend the ladder's memory
            onConnected();
            return true;
        }
        if (!JawnTailnetController.isAutoconnect(this)) {
            // enabled but manual: keep whatever is up, never chase it back
            clearRetry();
            return false;
        }
        if (attemptRunning || restartInFlight) {
            // one attempt at a time; its own leash owns the next move
            return true;
        }
        if (JawnTailnetController.STARTING.equals(status)
                && System.currentTimeMillis() < leashUntil) {
            // a start is already under its leash: let the verdict, not a timer,
            // decide whether another one is owed
            return true;
        }
        launchAttempt(true);
        return true;
    }

    /** Open an attempt: climb one rung and arm the leash that will replace
     *  "starting" with the backend's real state if Running never comes. */
    private void beginAttempt() {
        handler.removeCallbacks(retry);
        handler.removeCallbacks(verify);
        supervisorPrefs().edit().putInt(KEY_ATTEMPTS, attempts() + 1).apply();
        leashUntil = System.currentTimeMillis() + START_VERIFY_MS;
        handler.postDelayed(verify, START_VERIFY_MS);
    }

    /** Run one start/resume off the UI thread, one at a time: a second start
     *  overlapping a stop made the Go backend abort the whole process. */
    private void launchAttempt(final boolean fresh) {
        synchronized (attemptLock) {
            if (attemptRunning) return;
            attemptRunning = true;
        }
        new Thread(() -> {
            try {
                attempt(fresh);
            } finally {
                synchronized (attemptLock) {
                    attemptRunning = false;
                }
            }
        }, "jawn-tailnet-attempt").start();
    }

    /** A start that cannot begin has to say why. Calling start() with no
     *  network, or without the VPN consent, only produces a "starting" that
     *  nothing will ever finish. */
    private void attempt(boolean fresh) {
        try {
            if (!JawnTailnetController.isEnabled(this)) {
                clearRetry();
                return;
            }
            // this attempt owns the leash: a stale one from an earlier start
            // would report on the wrong attempt
            handler.removeCallbacks(verify);
            Network network = JawnTailnetNetwork.pick(connectivityManager);
            if (network == null) {
                standDown(JawnTailnetController.NO_NETWORK, NO_NETWORK_RETRY_MS);
                return;
            }
            if (VpnService.prepare(this) != null) {
                // declaring the VPN is a person's tap in an Activity; a service
                // cannot post it, and without it the TUN cannot exist at all
                standDown(JawnTailnetController.ERROR, SLOW_RETRY_MS);
                return;
            }
            beginAttempt();
            JawnTailnetController.setStatus(this, JawnTailnetController.STARTING);
            updateNotification(label(JawnTailnetController.STARTING));
            JawnLog.append("tailnet: start attempt " + attempts());
            if (fresh) {
                controller.start();
            }
            else {
                // resume() does not count itself, and the panel should show
                // that the system tried to bring the tunnel back
                JawnTailnetController.noteAttempt(this);
                controller.resume();
            }
        } catch (Exception e) {
            // never the message: a start body carries the auth key and a Go
            // error can quote it back
            Log.e(TAG, "tailnet attempt failed: " + e.getClass().getSimpleName());
            JawnLog.append("tailnet: attempt failed (" + e.getClass().getSimpleName() + ")");
            standDown(JawnTailnetController.ERROR, SLOW_RETRY_MS);
        }
    }

    /** The leash. Runs on the main looper and hands the probe to a thread: the
     *  Go local API can wait, and the main looper has a service to run. */
    private void verifyStart() {
        new Thread(this::verifyStartBlocking, "jawn-tailnet-verify").start();
    }

    private void verifyStartBlocking() {
        if (!JawnTailnetController.isEnabled(this)) {
            clearRetry();
            return;
        }
        if (attemptRunning) {
            // the start itself has not returned yet: do not stack a second one
            // and do not call it a failure, but look again when it may be done
            handler.removeCallbacks(verify);
            leashUntil = System.currentTimeMillis() + START_VERIFY_MS;
            handler.postDelayed(verify, START_VERIFY_MS);
            return;
        }
        String status = JawnTailnetController.getStatus(this);
        if (JawnTailnetController.CONNECTED.equals(status)) {
            // it arrived after all; the leash was only slow
            onConnected();
            return;
        }
        if (!JawnTailnetController.STARTING.equals(status)) {
            // the watcher wrote a real state while we waited (needs approval, a
            // key, stopped). Adopt it rather than overwrite it.
            updateNotification(label(status));
            scheduleRetry(delayFor(status));
            return;
        }
        // Still "starting" when the leash runs out. Ask the backend itself;
        // anything it will not name falls back to the closest defensible state.
        String mapped = JawnTailnetController.statusForBackend(backendState(),
                JawnTailnetController.hasKey(this));
        if (mapped == null || JawnTailnetController.STARTING.equals(mapped)) {
            mapped = fallbackState();
        }
        if (JawnTailnetController.CONNECTED.equals(mapped)) {
            JawnTailnetController.setStatus(this, mapped);
            updateNotification(label(mapped));
            onConnected();
            return;
        }
        Log.i(TAG, "tailnet start did not reach Running; reporting " + mapped);
        standDown(mapped, delayFor(mapped));
    }

    /** The Go client's own BackendState ("Running", "NeedsLogin", ...), or ""
     *  when the local API cannot answer. Read-only: it must not disturb a start
     *  that is still in flight. */
    private String backendState() {
        try {
            Application application = JawnTailnetController.ensureApp(this);
            LocalAPIResponse response = application.callLocalAPI(2000L, "GET",
                    "/localapi/v0/status", null);
            if (response == null) return "";
            byte[] data = response.bodyBytes();
            if (data == null || data.length == 0) return "";
            return new JSONObject(new String(data, StandardCharsets.UTF_8))
                    .optString("BackendState", "");
        } catch (Exception e) {
            Log.w(TAG, "tailnet backend status unavailable: " + e.getClass().getSimpleName());
            return "";
        }
    }

    /** The backend would not name a state (no local API, or it is still
     *  Starting). An approval URL it published is the one thing we do know;
     *  otherwise the attempt did not take, and that is "disconnected". */
    private String fallbackState() {
        String url = JawnTailnetController.getLoginUrl();
        if (url != null && !url.isEmpty()) return JawnTailnetController.NEEDS_APPROVAL;
        return JawnTailnetController.DISCONNECTED;
    }

    /** Record a real state and plan the next try. It refuses to walk a
     *  connected tunnel back: only the backend's own callback may do that. */
    private void standDown(String status, long delayMs) {
        if (!JawnTailnetController.CONNECTED.equals(status)
                && JawnTailnetController.CONNECTED.equals(JawnTailnetController.getStatus(this))) {
            return;
        }
        JawnTailnetController.setStatus(this, status);
        updateNotification(label(status));
        JawnLog.append("tailnet: " + status + "; next try in " + (delayMs / 1000) + "s");
        scheduleRetry(delayMs);
    }

    /** Persist the next try and arm both of its carriers: the Handler for the
     *  short rungs, the alarm for Doze and process death. */
    private void scheduleRetry(long delayMs) {
        if (!shouldWatch()) {
            // never chase a tunnel the person turned off, or one they asked to
            // connect by hand
            clearRetry();
            return;
        }
        long at = System.currentTimeMillis() + delayMs;
        // the absolute time is the controller's: the dashboard and the Perl
        // manager read it from the status file
        JawnTailnetController.setNextRetry(this, at);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, delayMs);
        armRetryAlarm(at);
    }

    /** A Handler dies with the process and freezes in Doze, so the retry is
     *  also an alarm. setAndAllowWhileIdle needs no permission and fires
     *  through Doze; it is inexact by design, which is why the Handler still
     *  carries the short rungs and this is the floor under them. */
    private void armRetryAlarm(long at) {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        long when = Math.max(at, System.currentTimeMillis() + ALARM_FLOOR_MS);
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, retryIntent());
        } catch (Exception e) {
            Log.w(TAG, "could not arm the tailnet retry alarm", e);
        }
    }

    /** No retry is owed any more. The alarm is cancelled through the same
     *  PendingIntent it was armed with, so it cannot fire behind our back. */
    private void clearRetry() {
        handler.removeCallbacks(retry);
        handler.removeCallbacks(verify);
        leashUntil = 0;
        JawnTailnetController.setNextRetry(this, 0L);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = retryIntent();
        am.cancel(pi);
        pi.cancel();
    }

    private PendingIntent retryIntent() {
        Intent i = new Intent(this, JawnTailnetService.class).setAction(ACTION_RETRY);
        // a foreground start: the process may have been killed since the alarm
        // was armed, and onStartCommand has to post the notification at once
        return PendingIntent.getForegroundService(this, RETRY_REQUEST, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The ladder is spent: reset it and drop the alarm. */
    private void onConnected() {
        supervisorPrefs().edit().putInt(KEY_ATTEMPTS, 0).apply();
        clearRetry();
    }

    private void retryNow() {
        if (!supervise()) stopSelf();
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
        if (status) {
            JawnTailnetController.setStatus(this, JawnTailnetController.CONNECTED);
            updateNotification(label(JawnTailnetController.CONNECTED));
            onConnected();
            applyUnderlying();
            return;
        }
        JawnTailnetController.setStatus(this, JawnTailnetController.DISCONNECTED);
        updateNotification(label(JawnTailnetController.DISCONNECTED));
        // The tunnel dropping by itself is the case that used to stay down
        // until a person noticed. A disconnect that belongs to our own network
        // restart is excluded: its stop step reports the same thing, and its
        // leash is already armed.
        if (!attemptRunning && !restartInFlight) scheduleRetry(nextDelay());
    }

    @Override
    public void onDestroy() {
        unwatchNetwork();
        handler.removeCallbacks(retry);
        handler.removeCallbacks(verify);
        // An enabled tunnel keeps its retry alarm: it is what brings this
        // service back after the system kills the process. A person's stop
        // (ACTION_STOP) and onRevoke() have already cleared it.
        if (!JawnTailnetController.isEnabled(this)) clearRetry();
        close();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        unwatchNetwork();
        controller.stop();
        // stop() can report one last disconnect, which schedules a retry; the
        // consent is gone, so nothing is owed
        clearRetry();
        close();
        super.onRevoke();
    }

    /** The phone can change its default network (Wi-Fi to cellular, a new
     *  Wi-Fi) while the tunnel is up. Keep the VpnService pointed at the
     *  network underneath and let the Go client rebind its sockets; only a
     *  real change of that network asks the controller to restart the
     *  tunnel. The request excludes VPN transports, so the tunnel's own
     *  coming and going is not a network change - restarting on that turned
     *  every restart into the next one. */
    private void watchNetwork() {
        if (connectivityManager == null) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                refreshUnderlying();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return;
                refreshUnderlying();
            }

            @Override
            public void onLost(Network network) {
                refreshUnderlying();
            }
        };
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            networkCallback = null;
        }
    }

    private void unwatchNetwork() {
        if (networkCallback == null) return;
        if (connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
        networkCallback = null;
    }

    /** Adopt the physical network under the tunnel, tell Android about it and
     *  poke the Go client's netmon; restart only when the network itself
     *  changed. */
    private void refreshUnderlying() {
        Network next = JawnTailnetNetwork.pick(connectivityManager);
        if (next != null && next.equals(underlying)) return;
        if (next == null && underlying == null) return;
        underlying = next;
        JawnTailnetNetwork.set(connectivityManager, next);
        applyUnderlying();
        if (next == null) {
            // No physical network to ride: that is a state of its own, not a
            // slow start. The tunnel cannot come up until one returns, so say so
            // and let the retry ladder (or the next callback) pick it up.
            if (JawnTailnetController.isEnabled(this)
                    && !JawnTailnetController.CONNECTED.equals(JawnTailnetController.getStatus(this))) {
                standDown(JawnTailnetController.NO_NETWORK, NO_NETWORK_RETRY_MS);
            }
            return;
        }
        scheduleRestart();
    }

    /** Tell Android which network carries the tunnel. The system keeps the
     *  underlying network alive and routes the VPN's own sockets over it;
     *  leaving it null leaves the tunnel with no physical network to lean on
     *  after a switch. */
    private void applyUnderlying() {
        try {
            setUnderlyingNetworks(underlying == null ? new Network[0] : new Network[]{underlying});
        } catch (Exception ignored) {
            // an unestablished VpnService refuses the call; the next update
            // carries the value anyway
        }
        // The Go client asks netmon for the default interface when it starts,
        // and at that moment the tunnel is not up yet: "Rebind; defIf=\"\"".
        // A network event with the physical interface named gets it rebinding
        // its sockets over the right network.
        try {
            Libtailscale.onDNSConfigChanged(JawnTailnetNetwork.getInterfaceName());
        } catch (Throwable ignored) {
        }
        String gateway = JawnTailnetNetwork.gateway(connectivityManager, underlying);
        if (!gateway.isEmpty()) {
            try {
                Libtailscale.onGatewayChanged(gateway);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Debounce a switch (a Wi-Fi change often arrives as a loss and an
     *  availability seconds apart), then restart the tunnel once the network
     *  has been stable for two seconds. Only one restart runs at a time: a
     *  second start overlapping a stop made the Go backend abort the whole
     *  process. */
    private void scheduleRestart() {
        lastNetworkAt = System.currentTimeMillis();
        synchronized (restartLock) {
            if (restartPending) return;
            restartPending = true;
        }
        new Thread(() -> {
            try {
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
                // The person's two choices gate a network restart: a tunnel they
                // stopped, or one they connect by hand, is left alone.
                if (!JawnTailnetController.isEnabled(JawnTailnetService.this)
                        || !JawnTailnetController.isAutoconnect(JawnTailnetService.this)) {
                    return;
                }
                restartInFlight = true;
                try {
                    beginAttempt();
                    JawnLog.append("tailnet: network changed; restarting");
                    controller.restart();
                } finally {
                    restartInFlight = false;
                }
            } finally {
                synchronized (restartLock) {
                    restartPending = false;
                }
            }
        }, "jawn-tailnet-network").start();
    }

    /** Called from the Go notification watcher when Headscale needs approval.
     *  The approval itself is the controller's: this only shows where to tap. */
    public void notifyLogin(String url) {
        Intent open = new Intent(this, JawnWebViewActivity.class)
                .putExtra(JawnWebViewActivity.EXTRA_URL, url);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, LOGIN_CHANNEL)
                .setSmallIcon(R.drawable.jawn_logo)
                .setContentTitle("JawnOS Tailnet")
                .setContentText("Tap to approve this device")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(LOGIN_ID, n);
    }

    /** The one place a status becomes words on the notification. It carries the
     *  reason a person needs: waiting for approval and waiting for a network
     *  are different problems, and "starting" forever told nobody anything. */
    private String label(String status) {
        if (JawnTailnetController.CONNECTED.equals(status)) return "Connected";
        if (JawnTailnetController.STARTING.equals(status)) return "Starting";
        if (JawnTailnetController.NO_NETWORK.equals(status)) return "Waiting for network";
        if (JawnTailnetController.NEEDS_APPROVAL.equals(status)) return "Waiting for approval";
        if (JawnTailnetController.NEEDS_KEY.equals(status)) return "Needs an auth key";
        if (JawnTailnetController.ERROR.equals(status)) return "Tailnet error";
        if (JawnTailnetController.OFF.equals(status)) return "Stopped";
        return shouldWatch() ? "Reconnecting" : "Disconnected";
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
