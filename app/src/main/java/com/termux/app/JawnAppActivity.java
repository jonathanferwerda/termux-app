package com.termux.app;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;

import org.json.JSONObject;

import java.net.URI;

/**
 * One JawnOS app, on its own, as a plain WebView.
 *
 * <p>The home screen's JawnOS row opens this. The manager's centre_view is a
 * window fragment, not a page that can stand alone, so this activity loads the
 * manager page itself - with the WebView's session, and with the same
 * JavaScript bridges the full manager view attaches. The bridges matter before
 * the page is usable: when the manager answers with its app-required page, that
 * page signs the app in through {@code JawnBiometricKey}/{@code JawnAppStorage},
 * and without them it stayed on a dead, dark page.
 *
 * <p>Once the manager's own {@code appointmentGrabber} exists, the app's window
 * is opened, the desktop chrome is hidden, and the window is moved out of the
 * hidden desktop and pinned full screen. If the page is the Gate, or the
 * manager never becomes ready, it retries once with the ticket and once with
 * the manager - the existing fallback path - and then stops. It never loops.
 *
 * <p>It lives in the main process so the manager's session cookie is already in
 * this WebView's cookie store. This is a manager view, not the external browser,
 * so the manager bridges are allowed here; {@link JawnBrowserActivity} does not
 * get them.
 */
public class JawnAppActivity extends Activity {

    public static final String EXTRA_APP = "app";

    private static final int STAGE_STARTED = 0;
    private static final int STAGE_READY = 1;
    private static final int STAGE_GAVE_UP = 2;

    private static final int GROUND = 0xFF101010;
    private static final int BAR = 0xFF161616;
    private static final int TEXT = 0xFFF2F2F2;

    private static final long POLL_TIMEOUT_MS = 10000L;
    private static final long POLL_INTERVAL_MS = 100L;
    /** The app-required page waits for a biometric prompt (and may run a
     *  bounded reload), so it gets a longer window than an ordinary page. */
    private static final long LOGIN_TIMEOUT_MS = 60000L;

    private static final int REQ_LOC = 7201;

    /** Runs on the page until it becomes a live manager, a gate, or the
     *  timeout. 1 = ready, 2 = Gate, 3 = app-required (waiting for login),
     *  0 = keep waiting. */
    private static final String READY_PROBE =
        "(function(){try{"
            + "if (typeof appointmentGrabber === 'function') return 1;"
            + "if (document.title === 'Gate'"
            + " || document.getElementById('welcome_logo')) return 2;"
            + "if (document.title === 'JawnOS app required'"
            + " || document.getElementById('app_required')) return 3;"
            + "return 0;"
        + "}catch(e){return 0;}})()";

    private static final String CHROME_STYLE =
        "#taskbar,#start_menu,#start_menu_backlog,#clothesline,"
            + "#background,#everything,#header,#pseudonym_home{display:none!important}"
            + ".top_navbar{display:none!important}"
            // window_maker pads the contents by the navbar height and shortens
            // them by it; with the bar gone that padding is the gap at the top.
            + ".window_contents{height:100%!important;padding-top:0!important}"
            + ".window_drawer{top:0!important;height:100%!important}"
            // the dock is the keyboard here; the pinned window's 99999 would
            // otherwise draw over it and the field would focus with no keys
            + "#jawn_soft_keyboard{z-index:100000!important}"
            + " body{background:#000}";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private WebView web;
    private TextView titleView;
    private TextView opening;
    private String appName = "";
    private String houseHost = "";
    private int stage = STAGE_STARTED;
    private boolean ticketTried;
    private boolean managerTried;
    private String lastFailureUrl = "";
    private boolean polling;
    private int pollGeneration;
    private long pollDeadline;
    private boolean pollLoginWait;
    /** The gate's own sign-in can lose a race with the bridge; one extra try
     *  a few seconds later is enough (verified on the tablet). */
    private boolean pollLoginRetried;
    private long pollGateSeen;

    private JawnNfcBridge nfc;
    private JawnCamera camera;
    private JawnContactsBridge contacts;
    private JawnSpeech speech;
    private boolean pendingLocationStart;
    private volatile boolean mediaPlaying;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTheme(R.style.Theme_Termux_JawnDashboard);

        Window window = getWindow();
        window.setStatusBarColor(GROUND);
        window.setNavigationBarColor(GROUND);

        Intent intent = getIntent();
        appName = intent == null ? "" : intent.getStringExtra(EXTRA_APP);
        if (appName == null) appName = "";
        appName = appName.trim();
        String label = formatAppName(appName);
        setTitle(label);
        // The Recents card is the app, with its own name and colour, rather
        // than an unlabelled black rectangle over the Home task.
        try {
            setTaskDescription(new ActivityManager.TaskDescription(label, null, GROUND));
        } catch (Throwable ignored) {
            // older devices keep the default task description
        }

        try {
            houseHost = new URI(JawnLauncher.getManagerUrl(this, JawnLauncher.getJawnPort(this))).getHost();
        } catch (Throwable ignored) {
            houseHost = "";
        }
        if (houseHost == null) houseHost = "";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(GROUND);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BAR);
        bar.setPadding(dp(8), dp(6), dp(12), dp(6));

        TextView back = new TextView(this);
        back.setText("\u2039 Back");
        back.setTextColor(TEXT);
        back.setTextSize(14);
        back.setPadding(dp(10), dp(8), dp(10), dp(8));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        titleView = new TextView(this);
        titleView.setText(label);
        titleView.setTextColor(TEXT);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        titleParams.leftMargin = dp(4);
        bar.addView(titleView, titleParams);

        // The app view is the app: its own window's title bar and this native
        // one both go, and Android's Back gesture/button leaves the app.
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                if (m != null) Log.i("JawnApp", m.messageLevel() + " " + m.message()
                    + " @" + m.sourceId() + ":" + m.lineNumber());
                return false;
            }
        });
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Same cookie store as the manager: this activity is in the main
        // process, so the session the manager minted is already here.
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(GROUND);
        web.setWebViewClient(new HouseClient());

        // The manager view's own bridges, in the same order and under the same
        // names as JawnWebViewActivity. The app-required page needs these to
        // sign this device in before the manager will open.
        nfc = new JawnNfcBridge(this, this::runJs);
        web.addJavascriptInterface(nfc, "JawnNFC");
        camera = new JawnCamera(this, this::runJs);
        web.addJavascriptInterface(camera, "JawnCamera");
        contacts = new JawnContactsBridge(this, this::runJs);
        web.addJavascriptInterface(contacts, "JawnContacts");
        web.addJavascriptInterface(new JawnSms(this, this::runJs), "JawnSms");
        web.addJavascriptInterface(new JawnScan(this, this::runJs), "JawnScan");
        web.addJavascriptInterface(new JawnMedia(), "JawnMedia");
        web.addJavascriptInterface(new JawnPhoneBridge(this), "JawnPhone");
        web.addJavascriptInterface(new AdminBridge(), "JawnAdmin");
        web.addJavascriptInterface(new JawnVpnBridge(this), "JawnVPN");
        web.addJavascriptInterface(new JawnBiometric(this, this::runJs), "JawnBiometric");
        web.addJavascriptInterface(new JawnBiometricKey(this, this::runJs), "JawnBiometricKey");
        speech = new JawnSpeech(this, this::runJs);
        web.addJavascriptInterface(speech, "JawnSpeech");
        web.addJavascriptInterface(new JawnAppStorage(this), "JawnAppStorage");
        web.addJavascriptInterface(new JawnLocationBridge(this, this::ensureLocationPermission), "JawnLocation");
        web.addJavascriptInterface(new JawnBrowseBridge(this), "JawnBrowse");
        // No external page can replace the house here (HouseClient sends those
        // to another app), so the bridge stays on for the life of the view.
        web.addJavascriptInterface(new JawnNetwork(this, () -> true), "JawnNetwork");

        root.addView(web, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // A native cover while the manager boots: the app's name, not the
        // manager's desktop. `JawnAdmin.opened()` lifts it when the app window
        // is pinned; a safety timer lifts it even if that never arrives.
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(GROUND);
        frame.addView(root, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        opening = new TextView(this);
        opening.setText("Opening " + label + "\u2026");
        opening.setTextColor(TEXT);
        opening.setTextSize(16);
        opening.setGravity(Gravity.CENTER);
        opening.setBackgroundColor(GROUND);
        frame.addView(opening, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() { hideOpening(); }
        }, 8000L);

        // pauseTimers() is process-wide, not per view: JawnWebViewActivity
        // pauses the timers when it leaves the screen, and a page that loads
        // afterwards stops dead at its first external script because the
        // parser's script task sits in the paused timer queue. Resuming here
        // (and in onResume) is what lets this view parse at all.
        web.resumeTimers();
        web.onResume();

        startManagerView();
    }

    /** Run a script in the page (the bridges' callbacks use this). */
    private void runJs(final String js) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(js, null);
            }
        });
    }

    /**
     * The first page: the manager when the WebView already holds a session,
     * otherwise the ticket (which mints one and redirects to the manager).
     */
    private void startManagerView() {
        int port = JawnLauncher.getJawnPort(this);
        String manager = JawnLauncher.getManagerUrl(this, port);
        if (appName.isEmpty()) {
            stage = STAGE_READY;
            titleView.setText(formatAppName(appName));
            load(manager);
            return;
        }
        if (hasSession(manager)) {
            managerTried = true;
            load(manager);
        } else {
            ticketTried = true;
            load(JawnLauncher.getUiUrl(this, port));
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

    private void load(String url) {
        polling = false;
        pollGeneration++;
        web.loadUrl(url);
    }

    /**
     * The manager page failed, or it answered with the Gate/app-required page.
     * Try the ticket once (it mints the session), then the manager once; then
     * stop. Each URL is tried at most once across the whole activity, and a
     * repeated error for the URL that just failed is ignored.
     */
    private void handleFailure(String failedUrl) {
        if (stage == STAGE_READY || stage == STAGE_GAVE_UP) return;
        if (failedUrl != null && !failedUrl.isEmpty()) {
            if (failedUrl.equals(lastFailureUrl)) return;
            lastFailureUrl = failedUrl;
        }

        int port = JawnLauncher.getJawnPort(this);
        if (!ticketTried) {
            ticketTried = true;
            load(JawnLauncher.getUiUrl(this, port));
            return;
        }
        if (!managerTried) {
            managerTried = true;
            load(JawnLauncher.getManagerUrl(this, port));
            return;
        }
        stage = STAGE_GAVE_UP;
    }

    /** Poll the loaded page until the manager's JS is up. */
    private void startPolling() {
        if (polling || stage == STAGE_READY || stage == STAGE_GAVE_UP || web == null) return;
        polling = true;
        pollLoginWait = false;
        pollDeadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        final int gen = ++pollGeneration;
        pollForGrabber(gen);
    }

    private void pollForGrabber(final int gen) {
        if (web == null || gen != pollGeneration
            || stage == STAGE_READY || stage == STAGE_GAVE_UP) {
            polling = false;
            return;
        }
        web.evaluateJavascript(READY_PROBE, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (gen != pollGeneration
                    || stage == STAGE_READY || stage == STAGE_GAVE_UP) {
                    polling = false;
                    return;
                }
                if ("1".equals(value)) {
                    polling = false;
                    injectApp();
                    return;
                }
                if ("3".equals(value) && !pollLoginWait) {
                    // The app-required page is signing this device in. Give the
                    // biometric prompt time; the page reloads when it succeeds.
                    pollLoginWait = true;
                    pollGateSeen = System.currentTimeMillis();
                    pollDeadline = Math.max(pollDeadline,
                        System.currentTimeMillis() + LOGIN_TIMEOUT_MS);
                }
                else if ("3".equals(value) && pollLoginWait && !pollLoginRetried
                        && System.currentTimeMillis() - pollGateSeen > 4000L) {
                    // The page's own attempt can run before the key bridge is
                    // ready and report "Could not sign in this app". Ask once
                    // more from here, then let the page decide to reload.
                    pollLoginRetried = true;
                    if (web != null) {
                        web.evaluateJavascript(
                            "(function(){try{jawnAppLogin(true).then(function(ok){"
                                + "if(ok){window.location.reload();}}).catch(function(){});"
                                + "}catch(e){}})()", null);
                    }
                }
                if ("2".equals(value) || System.currentTimeMillis() >= pollDeadline) {
                    polling = false;
                    handleFailure(null);
                    return;
                }
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        pollForGrabber(gen);
                    }
                }, POLL_INTERVAL_MS);
            }
        });
    }

    /**
     * Open the app's window through the manager's own function, then - only
     * once that window is actually on the page - hide the desktop chrome and
     * pin the window full screen. The window is appended inside
     * {@code #everything}, which the chrome style hides, so it is moved to
     * {@code body} before being sized.
     */
    private void injectApp() {
        stage = STAGE_READY;
        final String quotedApp = JSONObject.quote(appName);
        String js =
            "(function(){"
                + "var app=" + quotedApp + ";"
                // Every manager app has its own opener: the start menu's icon
                // handler (music opens the player, gallery the gallery, passes
                // the ticket maker). The start menu is not part of this page,
                // so its markup is fetched once into a hidden box and the
                // matching icon is pressed. An app with no icon there (a
                // person's own app) is an appointment window instead.
                + "try{"
                + "var special={box_office:'ticketmaker',budget:'budget',gallery:'image_toggle',configure:'main_logo',search:'search_app_toggle'};"
                + "var go=function(){"
                + "  var box=document.getElementById('jawnos_hidden_start_menu');"
                + "  if(!box)return false;"
                + "  var icon=document.getElementById(special[app]||(app+'_toggle'));"
                + "  if(!icon)return false;"
                + "  $(icon).trigger('click');"
                + "  return true;};"
                + "if(!go()){"
                + "  $.getJSON('/manager/start_menu',{menu:'app'},function(d){"
                + "    if(!d||!d.html){appointmentGrabber(app,Date.now());return;}"
                + "    var box=document.getElementById('jawnos_hidden_start_menu');"
                + "    if(!box){box=document.createElement('div');box.id='jawnos_hidden_start_menu';"
                + "      box.style.display='none';document.body.appendChild(box);}"
                + "    box.innerHTML=d.html;"
                + "    if(!go()){appointmentGrabber(app,Date.now());}"
                + "  }).fail(function(){appointmentGrabber(app,Date.now());});"
                + "}"
                + "}catch(e){}"
                + "var st=document.getElementById('jawnos_single_app_style');"
                + "if(!st){st=document.createElement('style');"
                + "st.id='jawnos_single_app_style';"
                + "(document.head||document.documentElement).appendChild(st);}"
                + "var tries=0;"
                + "var hold=function(){"
                + "var all=document.querySelectorAll('.wind'),w=null;"
                + "for(var i=0;i<all.length;i++){"
                + "if(all[i].getAttribute('app')===app){w=all[i];break;}}"
                + "if(!w)return false;"
                // The manager lays a phone window out at 71-80% and lifts it below
                // the reserved line; its maximizer can run again after this
                // script (a resize, a second window), and an inline pin loses
                // that race. A rule with !important wins every later inline
                // style, so the one window stays the whole screen.
                + "var sel='.wind[app='+JSON.stringify(app)+']';"
                + "st.textContent=" + JSONObject.quote(CHROME_STYLE)
                    + "+sel+'{position:fixed!important;left:0!important;top:0!important;"
                    + "width:100%!important;height:100%!important;max-width:100%!important;"
                    + "max-height:100%!important;margin:0!important;z-index:99999!important;"
                    + "display:block!important;border-radius:0!important;box-shadow:none!important}'"
                    + "+sel+'::after{display:none!important}'"
                    + "+sel+' .window_contents{height:100%!important;padding-top:0!important}'"
                    + "+sel+' .window_drawer{top:0!important;height:100%!important}';"
                + "if(w.parentNode&&w.parentNode!==document.body){document.body.appendChild(w);}"
                + "try{if(window.JawnAdmin&&JawnAdmin.opened){JawnAdmin.opened();}}catch(e){}"
                + "w.style.setProperty('position','fixed','important');"
                + "w.style.setProperty('left','0','important');"
                + "w.style.setProperty('top','0','important');"
                + "w.style.setProperty('width','100%','important');"
                + "w.style.setProperty('height','100%','important');"
                + "w.style.setProperty('z-index','99999','important');"
                + "w.style.setProperty('display','block','important');"
                + "return true;};"
                + "setTimeout(function(){"
                + "if(hold())return;"
                + "var tid=setInterval(function(){"
                + "if(hold()||++tries>=20){clearInterval(tid);}},100);"
                + "},0);"
            + "})();";
        try {
            web.evaluateJavascript(js, null);
        } catch (Throwable ignored) {
            // the page went away; nothing else to do
        }
    }

    /** Ask for the location permission once, then start the GPS service
     *  (the JawnLocation bridge's way in). */
    public void ensureLocationPermission() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
            pendingLocationStart = true;
            requestPermissions(new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION }, REQ_LOC);
            return;
        }
        JawnLocationService.start(this, 0, -1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.resumeTimers();
            web.onResume();
        }
        if (nfc != null) nfc.onResume();
        if (contacts != null) contacts.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        if (nfc != null) nfc.onPause();
        if (contacts != null) contacts.onPause();
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
            // no cookie store in this process
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (speech != null && speech.onPermissionResult(requestCode, permissions, results)) return;
        if (requestCode == JawnContactsBridge.REQ_PERMS) {
            if (contacts != null) contacts.onPermissionResult();
            return;
        }
        if (requestCode == REQ_LOC) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingLocationStart && ok) JawnLocationService.start(this, 0, -1);
            pendingLocationStart = false;
        }
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        // Let the app walk its own history first (a Gallery photo, then its
        // album, then the albums page), then the WebView's history, and only
        // then leave the app.
        final String quotedApp = JSONObject.quote(appName);
        try {
            web.evaluateJavascript(
                "(function(){try{return typeof jawnosAppBack==='function'"
                    + "?!!jawnosAppBack(" + quotedApp + "):false;}catch(e){return false;}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if ("true".equals(value)) return;
                        if (web != null && web.canGoBack()) {
                            web.goBack();
                            return;
                        }
                        finish();
                    }
                });
        } catch (Throwable t) {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (camera != null && camera.onActivityResult(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        polling = false;
        pollGeneration++;
        if (speech != null) {
            speech.destroy();
            speech = null;
        }
        if (web != null) {
            try {
                web.destroy();
            } catch (Throwable ignored) {
                // already torn down
            }
            web = null;
        }
        super.onDestroy();
    }

    private boolean isHouse(String url) {
        if (url == null) return false;
        try {
            String host = new URI(url).getHost();
            if (host == null) return false;
            return host.equals(houseHost) || host.equals("127.0.0.1") || host.equals("localhost");
        } catch (Throwable t) {
            return false;
        }
    }

    private void hideOpening() {
        if (opening == null) return;
        final TextView cover = opening;
        opening = null;
        cover.setVisibility(View.GONE);
    }

    /** "marker" -> "Marker"; a stable title from the app name alone. */
    private static String formatAppName(String app) {
        if (app == null || app.isEmpty()) return "JawnOS";
        String spaced = app.replace('_', ' ').replace('-', ' ');
        if (spaced.isEmpty()) return "JawnOS";
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** window.JawnMedia - the page tells the app when audio/video is playing.
     *  This view never pauses its WebView, so playback simply continues. */
    public class JawnMedia {
        @android.webkit.JavascriptInterface
        public void playing(final boolean playing) {
            mediaPlaying = playing;
        }
    }

    /** window.JawnAdmin - the start menu's admin gear. */
    public class AdminBridge {
        @android.webkit.JavascriptInterface
        public void opened() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { hideOpening(); }
            });
        }

        @android.webkit.JavascriptInterface
        public void open() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    startActivity(new Intent(JawnAppActivity.this, JawnDashboardActivity.class));
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void retry() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    stage = STAGE_STARTED;
                    ticketTried = false;
                    managerTried = false;
                    lastFailureUrl = "";
                    startManagerView();
                }
            });
        }
    }

    private final class HouseClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request == null || request.getUrl() == null
                ? "" : request.getUrl().toString();
            if (isHouse(url)) return false;
            // The app window is not a browser: anything off the house leaves
            // the app rather than replacing the single-app view.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable ignored) {
                // nothing on the device can open it
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.i("JawnApp", "finished " + url);
            if (stage == STAGE_READY || stage == STAGE_GAVE_UP) return;
            // Hide the manager's desk before the app window exists, so the
            // person sees the app's own cover rather than a flash of Manager.
            if (!appName.isEmpty() && isHouse(url)) {
                try {
                    view.evaluateJavascript(
                        "(function(){var s=document.getElementById('jawnos_single_app_shell');"
                            + "if(!s){s=document.createElement('style');s.id='jawnos_single_app_shell';"
                            + "(document.head||document.documentElement).appendChild(s);}"
                            + "s.textContent=" + JSONObject.quote(CHROME_STYLE) + ";})()", null);
                } catch (Throwable ignored) { }
            }
            startPolling();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                handleFailure(request.getUrl() == null ? "" : request.getUrl().toString());
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse response) {
            if (request != null && request.isForMainFrame()) {
                handleFailure(request.getUrl() == null ? "" : request.getUrl().toString());
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // The house serves its own certificate (a wildcard, or self-signed
            // on a name of its own); trust only that origin, never a stranger.
            String host = error == null ? null : Uri.parse(error.getUrl()).getHost();
            boolean ok = host != null
                && (host.equals(houseHost) || host.equals("127.0.0.1") || host.equals("localhost"));
            Log.i("JawnApp", "ssl host=" + host + " url=" + (error == null ? "" : error.getUrl())
                + " primary=" + (error == null ? -1 : error.getPrimaryError()) + " proceed=" + ok);
            if (ok) {
                handler.proceed();
            } else {
                handler.cancel();
            }
        }
    }
}
