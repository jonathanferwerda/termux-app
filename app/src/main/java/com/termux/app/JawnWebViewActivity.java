package com.termux.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

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
    private JawnCamera camera;
    private JawnContactsBridge contacts;
    public static final String EXTRA_URL = "com.termux.app.EXTRA_URL";
    private String houseHost = "";
    private boolean gateFallback = false;
    // one-time move of the paired device id into loopback
    private boolean migrateThenLoopback = false;
    // The page says when a track is playing, so the activity can leave the
    // WebView (and the music) running while it is in the background.
    private volatile boolean mediaPlaying = false;
    private boolean isResumed = false;
    private PermissionRequest pendingPermission;
    private ValueCallback<Uri[]> pendingFile;
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingGeoOrigin;
    private static final int REQ_PERMS = 7001;
    private static final int REQ_FILE = 7002;
    private static final int REQ_GEO = 7003;

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Deliberately no FLAG_KEEP_SCREEN_ON: this is a UI, not a kiosk.
        // Holding the screen awake while it idles was the phone's biggest heat
        // and battery cost; the system screen timeout now applies normally.
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // navigator.geolocation is refused by a WebView unless asked for; the
        // web app records travel and stamps appointments with it.
        s.setGeolocationEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setBackgroundColor(Color.BLACK);
        web.setWebViewClient(new HouseClient());
        web.setWebChromeClient(new HouseChrome());
        nfc = new JawnNfcBridge(this, this::runJs);
        web.addJavascriptInterface(nfc, "JawnNFC");
        camera = new JawnCamera(this, this::runJs);
        web.addJavascriptInterface(camera, "JawnCamera");
        contacts = new JawnContactsBridge(this, this::runJs);
        web.addJavascriptInterface(contacts, "JawnContacts");
        web.addJavascriptInterface(new JawnSms(this, this::runJs), "JawnSms");
        web.addJavascriptInterface(new JawnScan(this, this::runJs), "JawnScan");
        // the music player keeps the WebView alive while a track plays
        web.addJavascriptInterface(new JawnMedia(), "JawnMedia");
        web.addJavascriptInterface(new JawnPhoneBridge(this), "JawnPhone");
        web.addJavascriptInterface(new AdminBridge(), "JawnAdmin");
        // the remote_control panel's VPN button: the embedded tailnet client owns
        // the exit node, so the page reads and sets it through this bridge
        web.addJavascriptInterface(new JawnVpnBridge(this), "JawnVPN");
        web.addJavascriptInterface(new JawnBiometric(this, this::runJs), "JawnBiometric");
        web.addJavascriptInterface(new JawnBiometricKey(this, this::runJs), "JawnBiometricKey");
        // the paired-device id survives an origin change (the view moving to
        // 127.0.0.1) because universal.js mirrors it here
        web.addJavascriptInterface(new JawnAppStorage(this), "JawnAppStorage");
        // a nudge for the page when something happens with no page's finger in
        // it - a call that just ended is filed from the service's own record
        JawnPhoneState.poster = this::runJs;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // The manager UI is static while it idles; a 120 Hz panel redrawing
        // it 120 times a second is battery and heat for no visible gain.
        // Pin this window to 60 Hz - the page still animates smoothly.
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredRefreshRate = 60f;
        getWindow().setAttributes(lp);

        String extraUrl = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_URL);
        if (extraUrl != null && !extraUrl.isEmpty()) {
            try { houseHost = new URI(extraUrl).getHost(); } catch (Exception e) { houseHost = ""; }
            if (houseHost == null) houseHost = "";
            web.loadUrl(extraUrl);
            return;
        }
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
        if (JawnLauncher.getJawnLoopback(this) && JawnLauncher.getStoredDeviceUuid(this).isEmpty()) {
            // Loopback has no localStorage from the domain origin. Open the
            // domain form once so onPageFinished can copy the paired-device id
            // into JawnAppStorage, then move to loopback for good.
            migrateThenLoopback = true;
            return JawnLauncher.getDomainUiUrl(this, port);
        }
        String manager = JawnLauncher.getManagerUrl(this, port);
        if (hasSession(manager)) return manager;
        return JawnLauncher.getUiUrl(this, port);
    }

    /** The session cookie is scoped to the origin. When the view opens at
     *  loopback, copy the domain's cookie there so an existing session survives
     *  the move; the ticket also mints a fresh one. */
    private void copySessionToLoopback(String url) {
        try {
            if (!JawnLauncher.getJawnLoopback(this) || url == null || !url.startsWith("https://127.0.0.1")) return;
            String domain = JawnLauncher.getJawnDomain(this);
            if (domain.isEmpty()) return;
            int port = JawnLauncher.getJawnPort(this);
            CookieManager cm = CookieManager.getInstance();
            String cookie = cm.getCookie("https://" + domain + ":" + port);
            if (cookie != null && !cookie.isEmpty()) {
                cm.setCookie("https://127.0.0.1:" + port, cookie);
            }
        } catch (Throwable ignored) {
            // a cookie we cannot copy only means the ticket has to mint one
        }
    }

    private boolean hasSession(String url) {
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            return cookie != null && cookie.indexOf("president=") >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Land on the manager. When jawn already answers, load straight in - the
     *  everyday case, where a splash would only flash. When it does not, show
     *  the logo-with-a-glow splash at once, carrying an Admin Panel button, and
     *  keep waiting in the background: the moment jawn answers, the view
     *  replaces the splash. A machine left waiting still has the button, so it
     *  is never just a dead screen. */
    private void loadWhenReady(final String url) {
        gateFallback = false;
        copySessionToLoopback(url);
        // Load the page first. The port check can disagree with a busy or
        // just-restarted tree - the dashboard said "running" while this screen
        // stayed on the splash - and a splash that never leaves is worse than
        // the page reporting its own error (onReceivedError opens the
        // dashboard). Only start the tree when the port really is closed.
        web.loadUrl(url);
        if (!JawnLauncher.jawnIsUp(this)) {
            JawnLauncher.startJawn(this);
        }
    }

    /** The logo-with-a-glow splash (assets/splash.html), shown while jawn is
     *  being reached; its Admin Panel button is window.JawnAdmin.open(). */
    private void showSplash() {
        runOnUiThread(() -> {
            try { web.loadUrl("file:///android_asset/splash.html"); } catch (Throwable ignored) { }
        });
    }

    /** Nudge the splash's status line (a no-op when the splash is not up). */
    private void splashStatus(String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
        runJs("window.jawnSplashStatus && window.jawnSplashStatus(\"" + escaped + "\");");
    }

    /** The administration panel (the dashboard): what to show when the view
     *  cannot load, and where the start menu's admin gear goes. */
    private void openDashboard(boolean andFinish) {
        startActivity(new android.content.Intent(this, JawnDashboardActivity.class));
        if (andFinish) finish();
    }

    /** window.JawnMedia - the page tells the app when audio/video is playing.
     *  The music player calls playing(true) on a track and playing(false) a
     *  few seconds after a real stop; while it is true the activity does not
     *  pause its WebView, so playback continues with the screen off or the app
     *  in the background. */
    public class JawnMedia {
        @android.webkit.JavascriptInterface
        public void playing(final boolean playing) {
            mediaPlaying = playing;
            if (!playing && !isResumed) {
                runOnUiThread(() -> {
                    if (!isResumed && web != null) {
                        web.onPause();
                        web.pauseTimers();
                    }
                });
            }
        }
    }

    /** window.JawnAdmin - the web start menu's admin gear calls open(), the
     *  Android counterpart of the desktop view's `jawnos` message handler. */
    public class AdminBridge {
        @android.webkit.JavascriptInterface
        public void open() {
            runOnUiThread(() -> openDashboard(false));
        }

        @android.webkit.JavascriptInterface
        public void retry() {
            runOnUiThread(() -> { gateFallback = false; loadWhenReady(destination()); });
        }
    }

    /** Run a script in the page (the NFC bridge posts events through here). */
    private void runJs(String script) {
        web.post(() -> web.evaluateJavascript(script, null));
    }

    protected void onResume() {
        super.onResume();
        isResumed = true;
        immersive();
        if (nfc != null) nfc.onResume();
        if (contacts != null) contacts.onResume();
        if (web != null) {
            // Resume the WebView's own timers. The page reconnects its own
            // sockets when it becomes visible again (websocket.js wakes on
            // visibilitychange/focus/pageshow/resume), so a full reload here only
            // lost the open windows and flashed the splash on every wake.
            web.resumeTimers();
            web.onResume();
        }
    }

    protected void onPause() {
        isResumed = false;
        if (nfc != null) nfc.onPause();
        if (contacts != null) contacts.onPause();
        if (web != null) {
            // A playing track owns the WebView. Pausing it here (the screen
            // turning off, the app switched away from) stopped the music and
            // the queue with it; the page releases this through JawnMedia when
            // playback really ends, and the timers stop then.
            if (!mediaPlaying) {
                web.onPause();
                // Stop the page's timers entirely while it is off-screen rather
                // than letting the WebView keep them warm.
                web.pauseTimers();
            }
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    private void immersive() {
        // The admin panel's "Show the status and navigation bars" option keeps
        // the clock/battery/notification bar and the home/back buttons on
        // screen; otherwise the view fills the screen.
        if (JawnLauncher.areSystemBarsShown(this)) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            return;
        }
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
    @Override
    protected void onDestroy() {
        JawnPhoneState.poster = null;
        super.onDestroy();
    }

    private boolean inHouse(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) return false;
            return host.equals(houseHost) || host.equals("127.0.0.1") || host.equals("localhost");
        } catch (Exception e) {
            return false;
        }
    }

    /** Lets the page use the device's own camera and microphone: the web app
     *  drives getUserMedia and MediaRecorder directly (jp.js, studio.js), which
     *  a WebView refuses until the app grants the capture and holds the runtime
     *  permission. Also backs <input type=file> (onShowFileChooser), so a photo
     *  or a file picked from the page is the OS picker, not a dead control. */
    private class HouseChrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                if (!inHouse(request.getOrigin().toString())) { request.deny(); return; }
                List<String> need = new ArrayList<>();
                for (String r : request.getResources()) {
                    String perm = null;
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) perm = Manifest.permission.CAMERA;
                    else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) perm = Manifest.permission.RECORD_AUDIO;
                    if (perm != null && Build.VERSION.SDK_INT >= 23
                        && checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                        need.add(perm);
                    }
                }
                if (!need.isEmpty()) {
                    pendingPermission = request;
                    requestPermissions(need.toArray(new String[0]), REQ_PERMS);
                } else {
                    request.grant(request.getResources());
                }
            });
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            if (!inHouse(origin)) { callback.invoke(origin, false, false); return; }
            if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // ask once; the answer is delivered in onRequestPermissionsResult
                if (pendingGeo == null) {
                    pendingGeo = callback;
                    pendingGeoOrigin = origin;
                    requestPermissions(new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION }, REQ_GEO);
                } else {
                    callback.invoke(origin, false, false);
                }
                return;
            }
            callback.invoke(origin, true, false);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (pendingFile != null) { pendingFile.onReceiveValue(null); }
            pendingFile = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
                return true;
            } catch (Throwable t) {
                pendingFile = null;
                return false;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == JawnContactsBridge.REQ_PERMS) { if (contacts != null) contacts.onPermissionResult(); return; }
        if (requestCode == REQ_GEO) {
            boolean geoOk = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingGeo != null) { pendingGeo.invoke(pendingGeoOrigin, geoOk, false); pendingGeo = null; pendingGeoOrigin = null; }
            return;
        }
        if (requestCode != REQ_PERMS || pendingPermission == null) return;
        boolean granted = true;
        for (int r : results) { if (r != PackageManager.PERMISSION_GRANTED) granted = false; }
        if (granted) pendingPermission.grant(pendingPermission.getResources());
        else pendingPermission.deny();
        pendingPermission = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (camera != null && camera.onActivityResult(requestCode, resultCode, data)) return;
        if (requestCode == REQ_FILE) {
            if (pendingFile != null) {
                pendingFile.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                pendingFile = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private class HouseClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            // A navigation ends whatever the old page was playing. The new page
            // says playing(true) again when a track actually starts.
            mediaPlaying = false;
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            // the sms send and dial intents ride in as extras; hand them to the
            // page once (the chat opens on the number, the phone pad fills)
            injectLaunchExtras(view);
            if (migrateThenLoopback) {
                migrateThenLoopback = false;
                view.evaluateJavascript(
                    "(function(){try{var u=localStorage.getItem('device_uuid')||window.jawnLockedDevice||'';" +
                    "if(u&&window.JawnAppStorage)window.JawnAppStorage.set('device_uuid',u);}catch(e){}})();",
                    value -> {
                        String loop = JawnLauncher.getUiUrl(JawnWebViewActivity.this,
                                JawnLauncher.getJawnPort(JawnWebViewActivity.this));
                        copySessionToLoopback(loop);
                        view.loadUrl(loop);
                    });
                return;
            }
            // A lapsed session cookie makes the manager answer with the Gate and
            // the view sits on the login screen though a fresh ticket is on hand.
            // Spot the Gate, fall back to the ticket once - and if the Gate
            // stands even after that, show the splash, whose Admin Panel and Try
            // again buttons always work, so the view is never trapped.
            if (url == null) return;
            String manager = JawnLauncher.getManagerUrl(JawnWebViewActivity.this, JawnLauncher.getJawnPort(JawnWebViewActivity.this));
            if (!url.startsWith(manager)) return;
            view.evaluateJavascript("(document.title||'').trim().toLowerCase()==='gate'?'gate':'ok'", value -> {
                if (value == null || !value.contains("gate")) return;
                if (!gateFallback) {
                    gateFallback = true;
                    String ticket = JawnLauncher.getUiUrl(JawnWebViewActivity.this, JawnLauncher.getJawnPort(JawnWebViewActivity.this));
                    if (!ticket.equals(url)) { view.loadUrl(ticket); return; }
                }
                showSplash();
            });
        }

        // ---- the system's sms send and dial intents -------------------------
        // JawnComposeSmsActivity and JawnDialerActivity open this activity with
        // jawn_sms_number / jawn_sms_body / jawn_dial_number extras; the page
        // reads them once, through window.__jawnLaunchExtras, so a share-to-sms
        // opens the number's conversation and a dial intent fills the Phone pad.
        private void injectLaunchExtras(WebView view) {
            Intent in = getIntent();
            if (in == null) return;
            String smsNumber = in.getStringExtra("jawn_sms_number");
            String smsBody = in.getStringExtra("jawn_sms_body");
            String dialNumber = in.getStringExtra("jawn_dial_number");
            if (smsNumber == null && smsBody == null && dialNumber == null) return;
            in.removeExtra("jawn_sms_number");
            in.removeExtra("jawn_sms_body");
            in.removeExtra("jawn_dial_number");
            try {
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("sms_number", smsNumber == null ? "" : smsNumber);
                j.put("sms_body", smsBody == null ? "" : smsBody);
                j.put("dial_number", dialNumber == null ? "" : dialNumber);
                String json = j.toString();
                String script = "window.__jawnLaunchExtras && window.__jawnLaunchExtras(" + json + ");";
                view.evaluateJavascript(script, null);
            } catch (Throwable t) {
                android.util.Log.e("JawnWebView", "extras: " + t);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (inHouse(url) || url.startsWith("https://thejunderground.com")) return false;
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable ignored) { }
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            // A dead page is no use in its own screen. If the domain form could
            // not be reached for the one-time id move, go to loopback anyway
            // (the ticket may still mint a session); otherwise hand back to the
            // dashboard, which can start jawn and try again.
            if (request.isForMainFrame()) {
                if (migrateThenLoopback) {
                    migrateThenLoopback = false;
                    String loop = JawnLauncher.getUiUrl(JawnWebViewActivity.this,
                            JawnLauncher.getJawnPort(JawnWebViewActivity.this));
                    copySessionToLoopback(loop);
                    view.loadUrl(loop);
                    return;
                }
                openDashboard(true);
            }
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
