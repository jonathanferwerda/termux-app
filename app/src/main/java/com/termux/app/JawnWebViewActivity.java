package com.termux.app;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.net.URI;

/**
 * The JawnOS web app, full screen, in the app's own WebView.
 *
 * This is the home-screen "JawnOS UI" icon's target: unlike the installed PWA,
 * it does not depend on Chrome, a WebAPK, or the browser's trust of the house
 * certificate - so a certificate rotation cannot silently drop it. It loads the
 * saved ticket (JawnLauncher.getUiUrl) to sign in, falls back to the manager on
 * the configured domain, then loopback.
 *
 * NFC has no Web NFC in a WebView, so it is bridged natively (JawnNfcBridge,
 * exposed to the page as window.JawnNFC); the web app presents the same
 * NDEFReader surface over that bridge.
 */
public class JawnWebViewActivity extends Activity {

    private WebView web;
    private JawnNfcBridge nfc;
    private String houseHost = "";

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setBackgroundColor(Color.BLACK);
        web.setWebViewClient(new HouseClient());
        web.setWebChromeClient(new WebChromeClient());
        nfc = new JawnNfcBridge(this, this::runJs);
        web.addJavascriptInterface(nfc, "JawnNFC");
        web.addJavascriptInterface(new AdminBridge(), "JawnAdmin");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        String url = destination();
        try { houseHost = new URI(url).getHost(); } catch (Exception e) { houseHost = ""; }
        if (houseHost == null) houseHost = "";
        loadWhenReady(url);
    }

    /** Straight to the manager when the view already holds a session - the
     *  splash (and its animation) is skipped and the view is kept; the ticket
     *  only when it does not, which is where the animation runs. */
    private String destination() {
        int port = JawnLauncher.getJawnPort(this);
        String manager = JawnLauncher.getManagerUrl(this, port);
        if (hasSession(manager)) return manager;
        return JawnLauncher.getUiUrl(this, port);
    }

    private boolean hasSession(String url) {
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            return cookie != null && cookie.indexOf("president=") >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Bring jawn up if it is down, then load - the icon should land on the
     *  manager even right after a reboot or an app install that killed jawn.
     *  When it cannot be reached, fall back to the dashboard, not a dead page. */
    private void loadWhenReady(final String url) {
        new Thread(() -> {
            boolean up = JawnLauncher.jawnIsUp(JawnWebViewActivity.this);
            if (!up) {
                JawnLauncher.startJawn(JawnWebViewActivity.this);
                for (int i = 0; i < 40 && !up; i++) {
                    try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                    up = JawnLauncher.jawnIsUp(JawnWebViewActivity.this);
                }
            }
            final boolean ready = up;
            runOnUiThread(() -> { if (ready) web.loadUrl(url); else openDashboard(true); });
        }).start();
    }

    /** The administration panel (the dashboard): what to show when the view
     *  cannot load, and where the start menu's admin gear goes. */
    private void openDashboard(boolean andFinish) {
        startActivity(new android.content.Intent(this, JawnDashboardActivity.class));
        if (andFinish) finish();
    }

    /** window.JawnAdmin - the web start menu's admin gear calls open(), the
     *  Android counterpart of the desktop view's `jawnos` message handler. */
    public class AdminBridge {
        @android.webkit.JavascriptInterface
        public void open() {
            runOnUiThread(() -> openDashboard(false));
        }
    }

    /** Run a script in the page (the NFC bridge posts events through here). */
    private void runJs(String script) {
        web.post(() -> web.evaluateJavascript(script, null));
    }

    protected void onResume() {
        super.onResume();
        immersive();
        if (nfc != null) nfc.onResume();
    }

    protected void onPause() {
        if (nfc != null) nfc.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTask: a re-tap of the home screen icon brings this activity
        // forward without recreating it, so reload - a fresh ticket, or a jawn
        // that has since restarted, is picked up instead of the stale page.
        if (web != null) {
            if (web == null) return;
            // Already on the destination: keep the view rather than reloading it.
            String current = web.getUrl();
            if (current != null && current.startsWith(JawnLauncher.getManagerUrl(this, JawnLauncher.getJawnPort(this)))) return;
            String url = destination();
            try { houseHost = new URI(url).getHost(); } catch (Exception e) { houseHost = ""; }
            if (houseHost == null) houseHost = "";
            loadWhenReady(url);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** The house, on any of its names, keeps navigation in the app. */
    private boolean inHouse(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) return false;
            return host.equals(houseHost) || host.equals("127.0.0.1") || host.equals("localhost");
        } catch (Exception e) {
            return false;
        }
    }

    private class HouseClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (inHouse(url)) return false;
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable ignored) { }
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            // A dead page is no use in its own screen: hand back to the
            // dashboard, which can start jawn and try again.
            if (request.isForMainFrame()) openDashboard(true);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // The house serves its own certificate (a wildcard, or self-signed
            // on a name of its own); trust it rather than block the app.
            String host = Uri.parse(error.getUrl()).getHost();
            if (host != null && (host.equals(houseHost) || host.equals("127.0.0.1") || host.equals("localhost"))) {
                handler.proceed();
            } else {
                handler.cancel();
            }
        }
    }
}
