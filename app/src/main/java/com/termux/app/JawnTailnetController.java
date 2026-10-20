package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

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
            String prefsJson = "{\"ControlURL\":\"" + CONTROL_URL
                    + "\",\"Hostname\":\"" + json(name)
                    + "\",\"WantRunning\":true,\"LoggedOut\":false}";
            call(application, "PATCH", "prefs", prefsJson);
            String startJson = "{\"UpdatePrefs\":" + prefsJson
                    + (key == null || key.isEmpty() ? "" : ",\"AuthKey\":\"" + json(key) + "\"")
                    + "}";
            call(application, "POST", "start", startJson);
            call(application, "POST", "login-interactive", null);
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
