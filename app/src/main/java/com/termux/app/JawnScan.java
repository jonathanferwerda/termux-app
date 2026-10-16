package com.termux.app;

import android.app.Activity;
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

    /** How the bridge pushes a result into the page (implemented by the WebView). */
    public interface Poster {
        void post(String script);
    }

    private final Activity activity;
    private final Poster poster;

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

    private void scanOnUi() {
        try {
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

    /** Hand the code to the page; null (fail/cancel) reports an empty string. */
    private void report(String code) {
        String value = code == null ? "" : code;
        poster.post("window.jawnScanResult && window.jawnScanResult(" + JSONObject.quote(value) + ");");
    }
}
