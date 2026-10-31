package com.jawnos.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.webkit.JavascriptInterface;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONObject;

/**
 * The app's barcode scanner: window.JawnScan hands the read off to Google Play
 * services' "code scanner", which draws its own on-device scan sheet (no camera
 * permission of ours, any supported format), and hands the raw value back over
 * window.jawnScanResult(code), the way the other bridges report.
 *
 * market.js asks for this first, so a phone shows that sheet instead of the
 * WebView's own camera; a failure or a cancel reports an empty string and the
 * web side falls back to typing the code.
 */
public class JawnScan {

    /** The manager asks the bridge to put the scan sheet away. */
    public static final String ACTION_STOP = "com.jawnos.app.SCAN_STOP";

    /** The library's invisible activity that launched the sheet, one below it. */
    private static final String DELEGATE =
        "com.google.mlkit.vision.codescanner.internal.GmsBarcodeScanningDelegateActivity";

    /** How the bridge pushes a result into the page (implemented by the WebView). */
    public interface Poster {
        void post(String script);
    }

    private final Activity activity;
    private final Poster poster;
    private BroadcastReceiver stopReceiver;

    public JawnScan(Activity activity, Poster poster) {
        this.activity = activity;
        this.poster = poster;
    }

    // ---- the window.JawnScan surface (called on a binder thread) ----

    @JavascriptInterface
    public String available() {
        return "yes";
    }

    /** Open the scanner; the code (or "") arrives on window.jawnScanResult. */
    @JavascriptInterface
    public String scan() {
        activity.runOnUiThread(this::scanOnUi);
        return "ok";
    }

    /**
     * Put the scan sheet away. The page is paused behind Google's sheet while it
     * is open, so the reliable caller is the stop broadcast below; this method
     * covers a page that is still running (a cancel before the sheet settles).
     */
    @JavascriptInterface
    public String close() {
        activity.runOnUiThread(this::dismissScanner);
        return "ok";
    }

    private void scanOnUi() {
        try {
            registerStop();
            GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .enableAutoZoom()
                .build();
            GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(activity, options);
            scanner.startScan()
                .addOnSuccessListener(barcode ->
                    report(barcode == null ? null : barcode.getRawValue()))
                .addOnCanceledListener(() -> report(null))
                .addOnFailureListener(e -> report(null));
        } catch (Throwable t) {
            report(null);
        }
    }

    // ---- the native stop rail ----

    private void registerStop() {
        if (stopReceiver != null) return;
        stopReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                dismissScanner();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_STOP);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // Context.RECEIVER_EXPORTED, spelled out because this SDK predates it
                activity.registerReceiver(stopReceiver, filter, 2);
            } else {
                activity.registerReceiver(stopReceiver, filter);
            }
        } catch (Throwable t) {
            stopReceiver = null;
        }
    }

    private void unregisterStop() {
        BroadcastReceiver r = stopReceiver;
        stopReceiver = null;
        if (r == null) return;
        try { activity.unregisterReceiver(r); } catch (Throwable t) { }
    }

    /**
     * Dismiss Google's sheet. It runs in our task, above the library's invisible
     * delegate activity, so bringing the delegate back to the front with CLEAR_TOP
     * finishes the sheet. The same singleton delegate then stands in front of the
     * page (invisible) until the next scan reuses it.
     */
    private void dismissScanner() {
        unregisterStop();
        try {
            Intent i = new Intent();
            i.setClassName(activity, DELEGATE);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(i);
        } catch (Throwable t) {
        }
        report(null);
    }

    /** Hand the code to the page; null (fail/cancel) reports an empty string. */
    private void report(String code) {
        unregisterStop();
        String value = code == null ? "" : code;
        poster.post("window.jawnScanResult && window.jawnScanResult(" + JSONObject.quote(value) + ");");
    }
}
