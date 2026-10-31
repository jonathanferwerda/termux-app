package com.jawnos.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

/**
 * A tiny key/value mirror for the web app's per-origin identity.
 *
 * The manager's device id (device_uuid) lives in localStorage, which is scoped
 * to an origin. Opening the view at 127.0.0.1 is a different origin, so the id
 * is mirrored here and universal.js restores it there. Without this, a view
 * that moves to loopback looks like an unpaired browser and lands on the app
 * gate. Only this app's own WebView is given the bridge.
 */
public class JawnAppStorage {
    private static final String PREFS = "jawn_app_state";
    private final SharedPreferences prefs;

    public JawnAppStorage(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @JavascriptInterface
    public String get(String key) {
        if (key == null) return "";
        return prefs.getString(key, "");
    }

    @JavascriptInterface
    public void set(String key, String value) {
        if (key == null || key.isEmpty()) return;
        prefs.edit().putString(key, value == null ? "" : value).apply();
    }
}
