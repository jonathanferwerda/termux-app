package com.termux.app;

import android.app.Activity;
import android.content.Intent;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.util.Locale;

/**
 * window.JawnBrowse - the manager page's door to the native browser.
 *
 * Only ever attached to the manager's WebView. It runs in the manager process
 * (where the session cookie lives), then hands the browser URL, the manager base
 * URL and the session cookie to {@link JawnBrowserActivity} in the ":browser"
 * process, which deliberately does not share the manager's cookie jar.
 */
public class JawnBrowseBridge {

    private final Activity activity;

    public JawnBrowseBridge(Activity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void open(String url, String mode, String app, String uuid) {
        final String target = normalize(url);
        final String safeMode = "measure".equals(mode) ? "measure" : "browse";
        final String safeApp = app == null ? "" : app;
        final String safeUuid = uuid == null ? "" : uuid;

        activity.runOnUiThread(() -> {
            try {
                int port = JawnLauncher.getJawnPort(activity);
                String manager = JawnLauncher.getManagerUrl(activity, port);
                String base = manager;
                if (base.endsWith("/manager")) {
                    base = base.substring(0, base.length() - "/manager".length());
                }
                String cookie = CookieManager.getInstance().getCookie(manager);
                if (cookie == null || cookie.isEmpty()) {
                    cookie = CookieManager.getInstance().getCookie(base);
                }

                Intent i = new Intent(activity, JawnBrowserActivity.class);
                i.putExtra(JawnBrowserActivity.EXTRA_URL, target);
                i.putExtra(JawnBrowserActivity.EXTRA_MODE, safeMode);
                i.putExtra(JawnBrowserActivity.EXTRA_APP, safeApp);
                i.putExtra(JawnBrowserActivity.EXTRA_UUID, safeUuid);
                i.putExtra(JawnBrowserActivity.EXTRA_MANAGER, base);
                i.putExtra(JawnBrowserActivity.EXTRA_COOKIE, cookie == null ? "" : cookie);
                activity.startActivity(i);
            } catch (Throwable t) {
                try {
                    Toast.makeText(activity, "The browser is unavailable.",
                            Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) { }
            }
        });
    }

    /**
     * The manager only opens web pages in this surface. Anything that is not
     * http/https/about is dropped rather than handed to another scheme.
     */
    private static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "about:blank";
        if (!s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) s = "https://" + s;
        String lower = s.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("about:")) {
            return s;
        }
        return "about:blank";
    }
}
