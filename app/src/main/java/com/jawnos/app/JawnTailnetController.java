package com.jawnos.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import libtailscale.Application;
import libtailscale.IPNService;
import libtailscale.InputStream;
import libtailscale.Libtailscale;
import libtailscale.LocalAPIResponse;
import libtailscale.NotificationCallback;
import libtailscale.NotificationManager;

/**
 * Drives the Go Tailscale backend through its local API. This is the one-time
 * join (ControlURL + Hostname + auth key) and the running/stopped prefs; the
 * VpnService itself lives in JawnTailnetService.
 */
public class JawnTailnetController {
    public static final String PREFS = "jawn_tailnet";

    // The one status vocabulary. The dashboard, the manager's tailnet panel and
    // the retry loop all read these strings, so nothing may invent a new one:
    // setStatus() records an unknown value as "error" instead.
    public static final String OFF = "off";
    public static final String STARTING = "starting";
    public static final String CONNECTED = "connected";
    public static final String DISCONNECTED = "disconnected";
    public static final String NEEDS_APPROVAL = "needs_approval";
    public static final String NEEDS_KEY = "needs_key";
    public static final String NEEDS_SERVER = "needs_server";
    public static final String NO_NETWORK = "no_network";
    public static final String ERROR = "error";

    // The prefs contract inside "jawn_tailnet". The auth key is device-local
    // and the only secret here: it is never written to the status file, a log,
    // or the APK.
    private static final String K_ENABLED = "enabled";
    private static final String K_AUTOCONNECT = "autoconnect";
    private static final String K_AUTH_KEY = "auth_key";
    private static final String K_CONTROL_URL = "control_url";
    private static final String K_STATUS = "status";
    private static final String K_LAST_OK_AT = "last_ok_at";
    private static final String K_ATTEMPTS = "attempts";
    private static final String K_NEXT_RETRY_AT = "next_retry_at";

    // The Go state directory, so whoever inspects the tailnet finds the health
    // next to it. Rewritten atomically on every status change; the Perl
    // manager reads it to see why the tunnel is down.
    private static final String STATUS_DIR = "tailnet";
    private static final String STATUS_FILE = "status.json";

    private static final String TAG = "JawnTailnet";
    // One lifecycle step at a time. A network-change restart runs on its own
    // thread, and a second one overlapping its stop made the Go backend abort
    // the whole app process.
    private static final Object LIFECYCLE = new Object();
    private static Application app;
    private static NotificationManager watcher;
    private static volatile String loginUrl = "";
    // The last BackendState the Go client reported, by name ("Running", ...).
    // Memory only: prefs has a fixed key set and this fills the status file's
    // "backend" field.
    private static volatile String lastBackend = "NoState";
    private static final Object STATUS_FILE_LOCK = new Object();

    private final Context context;
    private final IPNService service;

    public JawnTailnetController(JawnTailnetService service) {
        this.service = service;
        this.context = service.getApplicationContext();
    }

    /** Start (once) the Go backend and return its Application handle. */
    public static synchronized Application ensureApp(Context context) {
        if (app == null) {
            java.io.File state = new java.io.File(context.getFilesDir(), "tailnet");
            java.io.File direct = new java.io.File(context.getCacheDir(), "tailnet-files");
            state.mkdirs();
            direct.mkdirs();
            app = Libtailscale.start(state.getAbsolutePath(), direct.getAbsolutePath(), false,
                    new JawnTailnetAppContext(context));
        }
        return app;
    }

    public void startWatching() {
        Application application = ensureApp(context);
        if (watcher != null) return;
        // Refresh the file as watching starts: the panel should see a current
        // "at" even when nothing has changed since the last process died.
        publishStatus(context);
        watcher = application.watchNotifications(0, new NotificationCallback() {
            @Override
            public void onNotify(byte[] data) {
                try {
                    JSONObject o = new JSONObject(new String(data, StandardCharsets.UTF_8));
                    String url = o.optString("BrowseToURL", "");
                    if (url != null && !url.isEmpty() && !url.equals(loginUrl)) {
                        // every start can re-emit the same approval URL; one
                        // notification per distinct URL, not one per start
                        loginUrl = url;
                        if (service instanceof JawnTailnetService) {
                            ((JawnTailnetService) service).notifyLogin(url);
                        }
                    }
                    // The callback reports the whole lifecycle, not just
                    // running/stopped, and sends the numeric enum codes as
                    // strings. Map every state we know; an unknown one leaves
                    // the previous status alone rather than guessing.
                    String backend = o.optString("BackendState", o.optString("State", ""));
                    if (!backend.isEmpty()) {
                        lastBackend = backendName(backend);
                        String mapped = statusForBackend(backend, hasKey(context));
                        if (mapped != null) setStatus(context, mapped);
                    }
                } catch (Exception ignored) {
                    // a notification we do not understand is not fatal
                }
            }
        });
    }

    public void start() {
        synchronized (LIFECYCLE) {
            startLocked();
        }
    }

    private void startLocked() {
        String control = controlUrl(context);
        if (control.isEmpty()) {
            setStatus(context, NEEDS_SERVER);
            return;
        }
        Application application = ensureApp(context);
        String name = nodeName(context);
        String key = prefs().getString(K_AUTH_KEY, "");
        boolean haveKey = key != null && !key.isEmpty();
        noteAttempt(context);
        // A node key on disk outlives the process, so an empty auth_key alone
        // does not prove this device cannot join. Ask the backend first: with
        // neither a node key nor a preauth key the start below can only sit at
        // NeedsLogin, and saying so now is the only honest status available.
        boolean missingKey = !haveNodeKey(application) && !haveKey;
        if (missingKey) setStatus(context, NEEDS_KEY);
        try {
            // PATCH takes ipn.MaskedPrefs: a value is only applied when its
            // <Field>Set flag is true, so without these the whole edit is
            // silently ignored.
            // MagicDNS has to be the device's resolver while the tunnel is the
            // default network. Headscale carries 1.1.1.1 and 8.8.8.8 upstreams,
            // so external names are forwarded rather than left to a VPN network
            // with no DNS of its own; without it the client cannot resolve
            // Headscale or DERP after an exit node changes the routes, and the
            // device drops off the tailnet. The base config still comes from
            // the physical network (JawnTailnetAppContext).
            String masked = "{\"ControlURL\":\"" + control + "\",\"ControlURLSet\":true"
                    + ",\"Hostname\":\"" + json(name) + "\",\"HostnameSet\":true"
                    + ",\"CorpDNS\":true,\"CorpDNSSet\":true}";
            String full = call(application, "PATCH", "prefs", masked);
            JSONObject prefsObject;
            try {
                prefsObject = new JSONObject(full == null || full.isEmpty() ? masked : full);
            } catch (Exception e) {
                prefsObject = new JSONObject(masked);
            }
            // ControlURL is only honoured from Start's initial prefs; a PATCH
            // cannot change it. Without it here the client registers against
            // controlplane.tailscale.com, which rejects a headscale hskey as
            // "invalid key: unable to validate API key".
            prefsObject.put("ControlURL", control);
            prefsObject.put("Hostname", name);
            prefsObject.put("CorpDNS", true);
            // Start only calls cc.Login() when the profile is not logged out;
            // the migrated ipn-android profile starts out LoggedOut=true, so
            // without this the auth key is never presented and the device
            // sits at NeedsLogin with no registration attempt at all.
            prefsObject.put("LoggedOut", false);
            // WantRunning belongs in start's UpdatePrefs, not the masked PATCH:
            // setting it in editPrefs starts a control client the start call
            // then tears down, and a slow register can be cancelled mid-flight.
            prefsObject.put("WantRunning", true);
            String startJson = "{\"UpdatePrefs\":" + prefsObject.toString()
                    + (haveKey ? ",\"AuthKey\":\"" + json(key) + "\"" : "")
                    + "}";
            call(application, "POST", "start", startJson);
            // Start with a key can create a node key, so re-read: the state it
            // landed in decides whether an interactive login is still needed.
            // A fresh profile has no node key, and start alone leaves it at
            // NeedsLogin. `tailscale up` calls StartLoginInteractive in exactly
            // this case (HaveNodeKey false). The auth key travels with that
            // login, so this is what presents it to the control server; without
            // a key it is also what returns the browser approval URL.
            JSONObject after = null;
            try {
                after = readStatus(application);
            } catch (Exception ignored) {
                // a status we cannot parse just means we ask to log in
            }
            boolean nodeKey = after != null && after.optBoolean("HaveNodeKey", false);
            if (!nodeKey) {
                call(application, "POST", "login-interactive", null);
            }
            Libtailscale.requestVPN(service);
            prefs().edit().putBoolean(K_ENABLED, true).apply();
            String backend = after == null ? "" : after.optString("BackendState", "");
            if (!backend.isEmpty()) lastBackend = backendName(backend);
            String mapped = statusForBackend(backend, haveKey);
            setStatus(context, mapped != null ? mapped : (missingKey ? NEEDS_KEY : STARTING));
        } catch (Exception e) {
            // Never log the message or the stack: the request body carried the
            // auth key and a Go error can quote it.
            Log.e(TAG, "start failed: " + e.getClass().getSimpleName());
            setStatus(context, ERROR);
        }
    }

    public void stop() {
        synchronized (LIFECYCLE) {
            stopLocked();
        }
    }

    private void stopLocked() {
        try {
            Application application = ensureApp(context);
            call(application, "PATCH", "prefs", "{\"WantRunning\":false}");
        } catch (Exception ignored) {
        }
        Libtailscale.serviceDisconnect(service);
        // A person stopping the tunnel clears the retry budget: autoconnect
        // must not treat the next boot as a failure to recover from.
        prefs().edit().putBoolean(K_ENABLED, false)
                .putInt(K_ATTEMPTS, 0).putLong(K_NEXT_RETRY_AT, 0L).apply();
        setStatus(context, OFF);
    }

    /** Re-establish an already-joined tunnel after Android restarted the
     *  service (always-on, or a sticky restart). The stored prefs already say
     *  what to run; only the TUN has to be handed back. */
    public void resume() {
        synchronized (LIFECYCLE) {
            try {
                Application application = ensureApp(context);
                call(application, "PATCH", "prefs", "{\"WantRunning\":true,\"WantRunningSet\":true}");
            } catch (Exception e) {
                Log.e(TAG, "resume failed", e);
            }
            Libtailscale.requestVPN(service);
        }
    }

    /** Re-establish the tunnel after the phone's underlying network changed.
     *  A Wi-Fi/cellular switch can leave the Go client pinned to the old
     *  default route, so the transport is stopped and started again while the
     *  user's enabled choice is kept. Runs on the service's background thread. */
    public void restart() {
        synchronized (LIFECYCLE) {
            restartLocked();
        }
    }

    private void restartLocked() {
        if (!isEnabled(context)) return;
        try {
            Application application = ensureApp(context);
            call(application, "PATCH", "prefs", "{\"WantRunning\":false}");
        } catch (Exception ignored) {
        }
        Libtailscale.serviceDisconnect(service);
        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        start();
    }

    private static String call(Application application, String method, String endpoint, String body)
            throws Exception {
        String path = "/localapi/v0/" + endpoint;
        LocalAPIResponse response = application.callLocalAPI(6000, method, path,
                body == null ? null : new Body(body));
        if (response == null) return "";
        byte[] data = response.bodyBytes();
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }

    private SharedPreferences prefs() {
        return prefs(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The Headscale control URL this device joins. Empty means it has not been
     *  set yet: the dashboard asks for it before the tailnet can start. */
    public static String controlUrl(Context context) {
        return normalizeControlUrl(prefs(context).getString(K_CONTROL_URL, ""));
    }

    /** Store a validated control URL. Returns false and keeps the old value
     *  when the input is not an https:// URL with a host. */
    public static boolean setControlUrl(Context context, String value) {
        String normalized = normalizeControlUrl(value);
        if (normalized.isEmpty()) return false;
        prefs(context).edit().putString(K_CONTROL_URL, normalized).apply();
        return true;
    }

    /** A control URL is a base https URL: trim, drop trailing slashes, and
     *  require a host. A path is allowed but not expected. */
    public static String normalizeControlUrl(String value) {
        if (value == null) return "";
        String v = value.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        if (v.isEmpty()) return "";
        try {
            java.net.URI u = new java.net.URI(v);
            if (u.getScheme() == null || !"https".equalsIgnoreCase(u.getScheme())) return "";
            if (u.getHost() == null || u.getHost().isEmpty()) return "";
            return v;
        } catch (Exception e) {
            return "";
        }
    }

    /** GET /status parsed. Throws when the local API is not up; callers that
     *  can live without it swallow that, because an unreadable status is not a
     *  reason to fail the start. */
    private static JSONObject readStatus(Application application) throws Exception {
        return new JSONObject(call(application, "GET", "status", null));
    }

    private static boolean haveNodeKey(Application application) {
        try {
            return readStatus(application).optBoolean("HaveNodeKey", false);
        } catch (Exception ignored) {
            // no answer means "cannot tell"; the prefs key decides
            return false;
        }
    }

    /** The health object, small on purpose: the Perl manager reads it to see
     *  why the tunnel is down. It never carries the auth key or a login URL. */
    private static String buildStatusJson(Context context) {
        SharedPreferences p = prefs(context);
        String state = p.getString(K_STATUS, OFF);
        if (!isStatus(state)) state = ERROR;
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("online", CONNECTED.equals(state));
            o.put("has_key", hasKey(context));
            o.put("attempts", p.getInt(K_ATTEMPTS, 0));
            o.put("at", System.currentTimeMillis());
            o.put("backend", lastBackend);
            o.put("next_retry_at", p.getLong(K_NEXT_RETRY_AT, 0L));
            return o.toString();
        } catch (Exception e) {
            return "{\"state\":\"" + state + "\",\"online\":" + CONNECTED.equals(state)
                    + ",\"has_key\":" + hasKey(context)
                    + ",\"attempts\":" + p.getInt(K_ATTEMPTS, 0)
                    + ",\"at\":" + System.currentTimeMillis()
                    + ",\"backend\":\"" + lastBackend + "\",\"next_retry_at\":"
                    + p.getLong(K_NEXT_RETRY_AT, 0L) + "}";
        }
    }

    /** Write the status file atomically: a temp file in the same directory,
     *  then a rename, so a reader never sees a half-written JSON. */
    private static void publishStatus(Context context) {
        final String body = buildStatusJson(context);
        File target = new File(new File(context.getFilesDir(), STATUS_DIR), STATUS_FILE);
        File dir = target.getParentFile();
        if (dir == null) return;
        synchronized (STATUS_FILE_LOCK) {
            dir.mkdirs();
            File temp = new File(dir, STATUS_FILE + ".tmp");
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(temp);
                out.write(body.getBytes(StandardCharsets.UTF_8));
                out.flush();
                try {
                    out.getFD().sync();
                } catch (Exception ignored) {
                    // durability is nice; the atomic rename is what matters
                }
                out.close();
                out = null;
                if (!temp.renameTo(target)) {
                    // POSIX rename replaces the target; the delete is only for a
                    // filesystem where rename-to-existing fails.
                    target.delete();
                    temp.renameTo(target);
                }
            } catch (Exception e) {
                Log.w(TAG, "tailnet status file write failed", e);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    public static String getLoginUrl() {
        return loginUrl;
    }

    /** The tunnel's health for the WebView bridge: the same object the status
     *  file holds, plus the peer list the remote_control VPN button still
     *  reads from this one call. */
    public static String statusJson(Context context) {
        JSONObject result;
        try {
            result = new JSONObject(buildStatusJson(context));
        } catch (Exception e) {
            result = new JSONObject();
        }
        // Peers only exist while the Go client is up; asking for them with the
        // tunnel off would start a backend just to say "nothing", so skip it.
        if (app == null && !isEnabled(context)) {
            try {
                result.put("available", false);
                result.put("exit", "");
                result.put("peers", new JSONArray());
            } catch (Exception ignored) {
            }
            return result.toString();
        }
        try {
            Application application = ensureApp(context);
            JSONObject status = readStatus(application);
            JSONObject peers = status.optJSONObject("Peer");
            JSONArray out = new JSONArray();
            String exit = "";
            if (peers != null) {
                Iterator<String> keys = peers.keys();
                while (keys.hasNext()) {
                    JSONObject peer = peers.optJSONObject(keys.next());
                    if (peer == null) continue;
                    String ip = firstTailnetAddress(peer.optJSONArray("TailscaleIPs"));
                    if (ip.isEmpty()) continue;
                    JSONObject o = new JSONObject();
                    o.put("ip", ip);
                    o.put("name", peer.optString("HostName", peer.optString("DNSName", "")));
                    o.put("online", peer.optBoolean("Online", false));
                    o.put("exit", peer.optBoolean("ExitNodeOption", false));
                    out.put(o);
                    if (peer.optBoolean("ExitNode", false)) exit = ip;
                }
            }
            result.put("available", peers != null);
            result.put("exit", exit);
            result.put("peers", out);
            result.put("haveNodeKey", status.optBoolean("HaveNodeKey", false));
        } catch (Exception e) {
            Log.e(TAG, "tailnet status failed", e);
            try {
                result.put("available", false);
                result.put("exit", "");
                result.put("peers", new JSONArray());
                result.put("error", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
        }
        return result.toString();
    }

    /** Choose an exit node ("" clears it) through the embedded client's local
     *  API. The local network stays direct so the house's own addresses still
     *  answer while the VPN is on. */
    public static String setExitNode(Context context, String ip) {
        String target = ip == null ? "" : ip;
        try {
            if (!target.isEmpty() && !target.matches("[0-9a-fA-F:.]+")) {
                return result(false, target, "not a tailnet address");
            }
            Application application = ensureApp(context);
            if (!target.isEmpty()) {
                JSONObject status = new JSONObject(statusJson(context));
                JSONArray peers = status.optJSONArray("peers");
                boolean offered = false;
                if (peers != null) {
                    for (int i = 0; i < peers.length(); i++) {
                        JSONObject peer = peers.optJSONObject(i);
                        if (peer != null && target.equals(peer.optString("ip")) && peer.optBoolean("exit")) {
                            offered = true;
                            break;
                        }
                    }
                }
                if (!offered) return result(false, target, "that machine does not offer an exit node");
            }
            // Clear the resolved node id as well as the address: an id left
            // behind wins over an empty ExitNodeIP, so clearing only the
            // address leaves the device still exiting through the old peer.
            JSONObject patch = new JSONObject();
            patch.put("ExitNodeID", "");
            patch.put("ExitNodeIDSet", true);
            patch.put("ExitNodeIP", target);
            patch.put("ExitNodeIPSet", true);
            patch.put("ExitNodeAllowLANAccess", true);
            patch.put("ExitNodeAllowLANAccessSet", true);
            call(application, "PATCH", "prefs", patch.toString());
            return result(true, target, null);
        } catch (Exception e) {
            Log.e(TAG, "setting the exit node failed", e);
            return result(false, target, String.valueOf(e.getMessage()));
        }
    }

    private static String result(boolean ok, String exit, String error) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            o.put("exit", exit == null ? "" : exit);
            if (error != null) o.put("error", error);
            return o.toString();
        } catch (Exception ignored) {
            return ok ? "{\"ok\":true}" : "{\"ok\":false}";
        }
    }

    private static String firstTailnetAddress(JSONArray addresses) {
        if (addresses == null) return "";
        for (int i = 0; i < addresses.length(); i++) {
            String ip = addresses.optString(i, "");
            if (ip.startsWith("100.")) return ip;
        }
        return "";
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(K_ENABLED, false);
    }

    /** The self-healing switch. On by default: a dropped tunnel should come
     *  back without anyone opening the dashboard. */
    public static boolean isAutoconnect(Context context) {
        return prefs(context).getBoolean(K_AUTOCONNECT, true);
    }

    public static void setAutoconnect(Context context, boolean on) {
        prefs(context).edit().putBoolean(K_AUTOCONNECT, on).apply();
        // Turning it back on should not wait out a backoff set while it was off.
        if (on) setNextRetry(context, 0L); else publishStatus(context);
    }

    /** Whether this device holds a preauth key. A node key already on disk can
     *  still start the tunnel without one, so this is not the whole story. */
    public static boolean hasKey(Context context) {
        String key = prefs(context).getString(K_AUTH_KEY, "");
        return key != null && !key.isEmpty();
    }

    /** Store a device-local preauth key ("" clears it). The key never leaves
     *  this pref: not the status file, not a log. */
    public static void setKey(Context context, String key) {
        String value = key == null ? "" : key.trim();
        prefs(context).edit().putString(K_AUTH_KEY, value).apply();
        if (!value.isEmpty() && NEEDS_KEY.equals(getStatus(context))) {
            // A key just arrived, so the next attempt is worth making now; the
            // status stays needs_key until that attempt reports otherwise.
            setNextRetry(context, 0L);
        } else {
            publishStatus(context);
        }
    }

    public static void startService(Context context) {
        Intent i = new Intent(context, JawnTailnetService.class);
        i.setAction(JawnTailnetService.ACTION_START);
        ContextCompat.startForegroundService(context, i);
    }

    /** Ask the service to check itself. Unlike startService this never tears a
     *  healthy tunnel down: the service resumes only what is not running, and
     *  stops itself when nothing is enabled. Safe to call on every process
     *  start (an update, a wake, a person opening the app). */
    public static void supervise(Context context) {
        Intent i = new Intent(context, JawnTailnetService.class);
        i.setAction(JawnTailnetService.ACTION_SUPERVISE);
        try {
            ContextCompat.startForegroundService(context, i);
        } catch (RuntimeException ignored) {
            // a background start can be refused; the next event retries
        }
    }

    public static void stopService(Context context) {
        Intent i = new Intent(context, JawnTailnetService.class);
        i.setAction(JawnTailnetService.ACTION_STOP);
        context.startService(i);
    }

    public static String getStatus(Context context) {
        String status = prefs(context).getString(K_STATUS, OFF);
        return isStatus(status) ? status : ERROR;
    }

    /** Record a status and rewrite the status file. The value must be one of
     *  the constants above; anything else is recorded as "error" so the panel
     *  never has to understand a private dialect. */
    public static void setStatus(Context context, String status) {
        String next = status;
        if (!isStatus(next)) {
            Log.w(TAG, "unknown tailnet status ignored");
            next = ERROR;
        }
        SharedPreferences.Editor edit = prefs(context).edit().putString(K_STATUS, next);
        if (CONNECTED.equals(next)) {
            // last_ok_at separates "connected now" from "was joined two days
            // ago", and a success resets the retry budget.
            edit.putLong(K_LAST_OK_AT, System.currentTimeMillis());
            edit.putInt(K_ATTEMPTS, 0);
            edit.putLong(K_NEXT_RETRY_AT, 0L);
        }
        edit.apply();
        publishStatus(context);
    }

    public static boolean isStatus(String status) {
        return OFF.equals(status) || STARTING.equals(status) || CONNECTED.equals(status)
                || DISCONNECTED.equals(status) || NEEDS_APPROVAL.equals(status)
                || NEEDS_KEY.equals(status) || NEEDS_SERVER.equals(status)
                || NO_NETWORK.equals(status) || ERROR.equals(status);
    }

    /** Map a Go BackendState (enum name, or the numeric code it also sends:
     *  1=NoState, 2=NeedsLogin, 3=NeedsMachineAuth, 4=Stopped, 5=Running) onto
     *  the status vocabulary. Returns null for a state we do not map, so the
     *  caller can leave the last good status alone. */
    public static String statusForBackend(String backend, boolean hasKey) {
        if (backend == null) return null;
        String s = backend.trim();
        if (s.isEmpty()) return null;
        if ("Running".equalsIgnoreCase(s) || "5".equals(s)) return CONNECTED;
        if ("Starting".equalsIgnoreCase(s) || "NoState".equalsIgnoreCase(s) || "1".equals(s)) {
            return STARTING;
        }
        if ("Stopped".equalsIgnoreCase(s) || "4".equals(s)) return DISCONNECTED;
        if ("NeedsLogin".equalsIgnoreCase(s) || "2".equals(s)) {
            // A key in hand means the login is waiting on the control server's
            // approval; no key means this device has nothing to log in with.
            return hasKey ? NEEDS_APPROVAL : NEEDS_KEY;
        }
        // NeedsMachineAuth is the server holding the node for a person to
        // approve, which looks the same from here as an approval URL.
        if ("NeedsMachineAuth".equalsIgnoreCase(s) || "3".equals(s)) return NEEDS_APPROVAL;
        return null;
    }

    private static String backendName(String raw) {
        String s = raw == null ? "" : raw.trim();
        if ("1".equals(s)) return "NoState";
        if ("2".equals(s)) return "NeedsLogin";
        if ("3".equals(s)) return "NeedsMachineAuth";
        if ("4".equals(s)) return "Stopped";
        if ("5".equals(s)) return "Running";
        return s;
    }

    public static long lastOkAt(Context context) {
        return prefs(context).getLong(K_LAST_OK_AT, 0L);
    }

    /** 0 means "no retry scheduled", so a caller may attempt immediately. */
    public static long nextRetryAt(Context context) {
        return prefs(context).getLong(K_NEXT_RETRY_AT, 0L);
    }

    public static void setNextRetry(Context context, long at) {
        prefs(context).edit().putLong(K_NEXT_RETRY_AT, at).apply();
        publishStatus(context);
    }

    /** Count one start attempt, so a log that keeps repeating is visible as a
     *  number rather than inferred from timestamps. */
    public static void noteAttempt(Context context) {
        SharedPreferences p = prefs(context);
        p.edit().putInt(K_ATTEMPTS, p.getInt(K_ATTEMPTS, 0) + 1).apply();
        publishStatus(context);
    }

    public static String statusText(Context context) {
        SharedPreferences p = prefs(context);
        boolean enabled = p.getBoolean(K_ENABLED, false);
        String status = p.getString(K_STATUS, enabled ? STARTING : OFF);
        if (!isStatus(status)) status = ERROR;
        return "Node: " + nodeName(context) + "  \u2022  " + status;
    }

    /** The node name Headscale will publish: config.json's domain prefix. */
    public static String nodeName(Context context) {
        String domain = JawnLauncher.getJawnDomain(context);
        if (domain != null && !domain.isEmpty()) {
            int dot = domain.indexOf('.');
            return dot > 0 ? domain.substring(0, dot) : domain;
        }
        String model = Build.MODEL == null ? "android" : Build.MODEL;
        return model.replaceAll("[^A-Za-z0-9-]", "");
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** A one-shot body for the Go local API. */
    private static class Body implements InputStream {
        private final byte[] bytes;
        private boolean read;

        Body(String value) {
            this.bytes = value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] read() {
            if (read) return null;
            read = true;
            return bytes;
        }

        @Override
        public void close() {
        }
    }
}
