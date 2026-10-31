package com.jawnos.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import android.webkit.JavascriptInterface;

/**
 * {@code window.JawnLocation}: the page's only way to ask where it is.
 *
 * The browser's own {@code navigator.geolocation} cannot say whether an answer
 * came from GPS or from Wi-Fi, so the web app stopped using it. This bridge
 * starts and stops {@link JawnLocationService} and hands back the latest
 * GPS-only fix as the same JSON the Perl tree reads.
 */
public class JawnLocationBridge {
    private final Activity activity;
    private final Runnable permissionRequester;

    public JawnLocationBridge(JawnWebViewActivity activity) {
        this(activity, activity::ensureLocationPermission);
    }

    /** The same bridge for another manager view, which supplies its own way of
     *  asking for the location permission. */
    public JawnLocationBridge(Activity activity, Runnable permissionRequester) {
        this.activity = activity;
        this.permissionRequester = permissionRequester;
    }

    /** The latest GPS fix, or "" when there has not been one yet. */
    @JavascriptInterface
    public String latest() {
        return JawnLocationService.latest();
    }

    /** Service/permission/provider state as JSON. */
    @JavascriptInterface
    public String status() {
        return JawnLocationService.status(activity);
    }

    /** Start at the default pace (four minutes). */
    @JavascriptInterface
    public void start() {
        permissionRequester.run();
    }

    /**
     * Start (or retune) the service. Both arguments are strings so the page can
     * pass what it has without a JS/Java numeric conversion surprise; an empty
     * or non-positive value keeps the stored setting.
     */
    @JavascriptInterface
    public void startAt(String minTimeMs, String minDistanceM) {
        long minTime = -1;
        float minDistance = -1;
        try {
            if (minTimeMs != null && !minTimeMs.trim().isEmpty()) {
                minTime = Long.parseLong(minTimeMs.trim());
            }
        } catch (NumberFormatException ignored) {
        }
        try {
            if (minDistanceM != null && !minDistanceM.trim().isEmpty()) {
                minDistance = Float.parseFloat(minDistanceM.trim());
            }
        } catch (NumberFormatException ignored) {
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionRequester.run();
            return;
        }
        JawnLocationService.start(activity, minTime, minDistance);
    }

    @JavascriptInterface
    public void stop() {
        JawnLocationService.stop(activity);
    }

    /** Ask for one fresh GPS fix now (an appointment start or stop). */
    @JavascriptInterface
    public void sample() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionRequester.run();
            return;
        }
        JawnLocationService.sample(activity);
    }
}
