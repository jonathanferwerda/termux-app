package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

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
    public static final String CONTROL_URL = "https://thejunderground.com";

    private static final String TAG = "JawnTailnet";
    private static Application app;
    private static NotificationManager watcher;
    private static volatile String loginUrl = "";

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
        watcher = application.watchNotifications(0, new NotificationCallback() {
            @Override
            public void onNotify(byte[] data) {
                try {
                    JSONObject o = new JSONObject(new String(data, StandardCharsets.UTF_8));
                    String url = o.optString("BrowseToURL", "");
                    if (url != null && !url.isEmpty()) {
                        loginUrl = url;
                        if (service instanceof JawnTailnetService) {
                            ((JawnTailnetService) service).notifyLogin(url);
                        }
                    }
                    String state = o.optString("State", "");
                    if ("Running".equals(state) || "5".equals(state)) {
                        setStatus(context, "connected");
                    } else if ("Stopped".equals(state) || "3".equals(state)) {
                        setStatus(context, "disconnected");
                    }
                } catch (Exception ignored) {
                    // a notification we do not understand is not fatal
                }
            }
        });
    }

    public void start() {
        Application application = ensureApp(context);
        String name = nodeName(context);
        String key = prefs().getString("auth_key", "");
        try {
            // PATCH takes ipn.MaskedPrefs: a value is only applied when its
            // <Field>Set flag is true, so without these the whole edit is
            // silently ignored.
            // The house does not take over the device's DNS. Headscale's
            // MagicDNS proxy is only as good as its upstreams, and a wedged
            // proxy (its request queue full) makes every name lookup time out
            // and reads as "no internet". The app reaches itself at 127.0.0.1
            // and peers by their tailnet addresses, so the device's own
            // resolver stays in charge.
            String masked = "{\"ControlURL\":\"" + CONTROL_URL + "\",\"ControlURLSet\":true"
                    + ",\"Hostname\":\"" + json(name) + "\",\"HostnameSet\":true"
                    + ",\"CorpDNS\":false,\"CorpDNSSet\":true}";
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
            prefsObject.put("ControlURL", CONTROL_URL);
            prefsObject.put("Hostname", name);
            prefsObject.put("CorpDNS", false);
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
                    + (key == null || key.isEmpty() ? "" : ",\"AuthKey\":\"" + json(key) + "\"")
                    + "}";
            call(application, "POST", "start", startJson);
            // A fresh profile has no node key, and start alone leaves it at
            // NeedsLogin. `tailscale up` calls StartLoginInteractive in exactly
            // this case (HaveNodeKey false). The auth key travels with that
            // login, so this is what presents it to the control server; without
            // a key it is also what returns the browser approval URL.
            boolean haveNodeKey = false;
            try {
                JSONObject status = new JSONObject(call(application, "GET", "status", null));
                haveNodeKey = status.optBoolean("HaveNodeKey", false);
            } catch (Exception ignored) {
                // a status we cannot parse just means we ask to log in
            }
            if (!haveNodeKey) {
                call(application, "POST", "login-interactive", null);
            }
            Libtailscale.requestVPN(service);
            prefs().edit().putBoolean("enabled", true).putString("status", "starting").apply();
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            prefs().edit().putString("status", "error: " + e.getMessage()).apply();
        }
    }

    public void stop() {
        try {
            Application application = ensureApp(context);
            call(application, "PATCH", "prefs", "{\"WantRunning\":false}");
        } catch (Exception ignored) {
        }
        Libtailscale.serviceDisconnect(service);
        prefs().edit().putBoolean("enabled", false).putString("status", "off").apply();
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
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getLoginUrl() {
        return loginUrl;
    }

    /** The tailnet as the remote_control VPN button needs it: the peers, which
     *  of them offer an exit node, and which one this device is using now. */
    public static String statusJson(Context context) {
        try {
            Application application = ensureApp(context);
            String raw = call(application, "GET", "status", null);
            JSONObject status = new JSONObject(raw == null || raw.isEmpty() ? "{}" : raw);
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
            JSONObject result = new JSONObject();
            result.put("available", peers != null);
            result.put("exit", exit);
            result.put("peers", out);
            result.put("haveNodeKey", status.optBoolean("HaveNodeKey", false));
            return result.toString();
        } catch (Exception e) {
            Log.e(TAG, "tailnet status failed", e);
            try {
                JSONObject result = new JSONObject();
                result.put("available", false);
                result.put("exit", "");
                result.put("peers", new JSONArray());
                result.put("error", String.valueOf(e.getMessage()));
                return result.toString();
            } catch (Exception ignored) {
                return "{\"available\":false,\"exit\":\"\",\"peers\":[]}";
            }
        }
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
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("enabled", false);
    }

    public static void startService(Context context) {
        Intent i = new Intent(context, JawnTailnetService.class);
        i.setAction(JawnTailnetService.ACTION_START);
        ContextCompat.startForegroundService(context, i);
    }

    public static void stopService(Context context) {
        Intent i = new Intent(context, JawnTailnetService.class);
        i.setAction(JawnTailnetService.ACTION_STOP);
        context.startService(i);
    }

    public static void setStatus(Context context, String status) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("status", status).apply();
    }

    public static String statusText(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean enabled = p.getBoolean("enabled", false);
        String status = p.getString("status", enabled ? "starting" : "not joined");
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
