package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Browse - a real browser, kept as a sibling of the manager rather than a page
 * inside it.
 *
 * The activity runs in the ":browser" process. That process deliberately does
 * not share the manager WebView's cookie jar, so the manager URL and the session
 * cookie travel in the launch intent (JawnBrowseBridge reads them in the
 * manager process). The WebView here never receives a JavascriptInterface; the
 * measure picker is installed with evaluateJavascript and read back through a
 * ValueCallback.
 *
 * It refuses loopback and private-network navigation, never proceeds past a TLS
 * error, and only the app's own HTTP client (the usage and measure POSTs) uses
 * the house certificate trust, exactly as JawnUpdater does for the manager.
 */
public class JawnBrowserActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_APP = "app";
    public static final String EXTRA_UUID = "uuid";
    public static final String EXTRA_MANAGER = "manager";
    public static final String EXTRA_COOKIE = "cookie";
    public static final String EXTRA_MEASURE = "measure";

    private static final int REQ_FILE = 7201;
    private static final long USAGE_TICK_MS = 30_000L;
    private static final int MEASURE_HTML_CAP = 1_572_864; // 1.5 MiB, in JS characters
    private static final int MEASURE_CHUNK = 131_072;
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;

    private final List<Tab> tabs = new ArrayList<>();
    private Tab current;

    private LinearLayout tabStrip;
    private FrameLayout webContainer;
    private EditText urlBar;
    private TextView backButton;
    private TextView forwardButton;
    private TextView reloadButton;
    private TextView measureStatus;
    private ProgressBar progress;

    private String mode = "browse";
    private String measureApp = "";
    private String measureUuid = "";
    private String measureName = "";
    private String managerBase = "";
    private String authCookie = "";

    private boolean resumed;
    private boolean measureHintShown;
    private int measureGeneration;
    private ValueCallback<Uri[]> pendingFile;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService apiPool = Executors.newSingleThreadExecutor();
    private SiteSpan span;

    private static final Set<String> TWO_LEVEL_SUFFIXES = new HashSet<>();
    static {
        String[] suffixes = new String[] {
            "co.uk", "org.uk", "ac.uk", "gov.uk", "ltd.uk", "me.uk",
            "com.au", "net.au", "org.au", "edu.au", "gov.au",
            "co.nz", "net.nz", "org.nz", "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
            "co.in", "net.in", "org.in", "co.za", "org.za",
            "com.br", "net.br", "org.br", "com.mx", "com.ar", "com.tr",
            "com.cn", "net.cn", "org.cn", "com.hk", "com.sg", "com.my",
            "co.id", "co.kr", "co.th", "com.tw", "com.ph", "com.vn", "com.pk",
            "com.eg", "com.sa", "co.il", "com.ua", "com.pl", "com.es",
            "co.at", "or.at", "co.il"
        };
        for (String s : suffixes) TWO_LEVEL_SUFFIXES.add(s);
    }

    private static final TrustManager[] MANAGER_TRUST = new TrustManager[] {
        new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String auth) { }
            public void checkServerTrusted(X509Certificate[] chain, String auth) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }
    };

    // ------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // The browser is its own process and must not share WebView's data
        // directory with the manager process (Android 9+ enforces this).
        if (Build.VERSION.SDK_INT >= 28) {
            try { WebView.setDataDirectorySuffix("jawnbrowser"); } catch (Throwable ignored) { }
        }

        Intent intent = getIntent();
        String startUrl = intent == null ? "" : intent.getStringExtra(EXTRA_URL);
        String requestedMode = intent == null ? null : intent.getStringExtra(EXTRA_MODE);
        if ("measure".equals(requestedMode)) mode = "measure";
        measureApp = safe(intent == null ? null : intent.getStringExtra(EXTRA_APP));
        measureUuid = safe(intent == null ? null : intent.getStringExtra(EXTRA_UUID));
        measureName = safe(intent == null ? null : intent.getStringExtra(EXTRA_MEASURE));
        managerBase = safe(intent == null ? null : intent.getStringExtra(EXTRA_MANAGER));
        authCookie = safe(intent == null ? null : intent.getStringExtra(EXTRA_COOKIE));
        if (managerBase.endsWith("/manager")) {
            managerBase = managerBase.substring(0, managerBase.length() - "/manager".length());
        }

        setContentView(com.termux.R.layout.jawn_browser);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        tabStrip = (LinearLayout) findViewById(com.termux.R.id.browser_tabs);
        webContainer = (FrameLayout) findViewById(com.termux.R.id.browser_container);
        urlBar = (EditText) findViewById(com.termux.R.id.browser_url);
        backButton = (TextView) findViewById(com.termux.R.id.browser_back);
        forwardButton = (TextView) findViewById(com.termux.R.id.browser_forward);
        reloadButton = (TextView) findViewById(com.termux.R.id.browser_reload);
        measureStatus = (TextView) findViewById(com.termux.R.id.browser_measure_status);
        progress = (ProgressBar) findViewById(com.termux.R.id.browser_progress);

        backButton.setOnClickListener(v -> { if (current != null && current.web.canGoBack()) current.web.goBack(); });
        forwardButton.setOnClickListener(v -> { if (current != null && current.web.canGoForward()) current.web.goForward(); });
        reloadButton.setOnClickListener(v -> {
            if (current == null) return;
            if (current.loading) {
                current.web.stopLoading();
                current.loading = false;
                progress.setVisibility(View.GONE);
                updateChrome();
            } else {
                current.web.reload();
            }
        });
        findViewById(com.termux.R.id.browser_go).setOnClickListener(v -> goTyped());
        findViewById(com.termux.R.id.browser_tab_add).setOnClickListener(v -> {
            Tab tab = newTab("about:blank");
            urlBar.requestFocus();
            urlBar.selectAll();
            showKeyboard();
            if (tab != null) { /* the tab is already selected */ }
        });
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            goTyped();
            return true;
        });

        if ("measure".equals(mode)) {
            measureStatus.setVisibility(View.VISIBLE);
            measureStatus.setText("Tap the value you want to measure.");
        }

        String start = normalizeNavigation(startUrl);
        if (start.isEmpty()) start = "about:blank";
        newTab(start);
    }

    private Tab newTab(String url) {
        final Tab tab = new Tab();
        tab.web = createWebView(tab);
        tab.header = new LinearLayout(this);
        tab.header.setOrientation(LinearLayout.HORIZONTAL);
        tab.header.setGravity(Gravity.CENTER_VERTICAL);
        tab.header.setPadding(dp(10), 0, dp(4), 0);
        tab.header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tab.label = new TextView(this);
        tab.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tab.label.setTextColor(themeColor(android.R.attr.textColorPrimary, Color.BLACK));
        tab.label.setSingleLine(true);
        tab.label.setEllipsize(TextUtils.TruncateAt.END);
        tab.label.setMaxWidth(dp(170));
        tab.label.setPadding(0, 0, dp(8), 0);
        tab.label.setText("New tab");
        tab.header.addView(tab.label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView close = new TextView(this);
        close.setText("\u00d7");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        close.setTextColor(themeColor(android.R.attr.textColorPrimary, Color.BLACK));
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(6), 0, dp(6), 0);
        close.setContentDescription("Close tab");
        close.setOnClickListener(v -> closeTab(tab));
        tab.header.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tab.header.setOnClickListener(v -> selectTab(tab));

        tabs.add(tab);
        tabStrip.addView(tab.header);
        webContainer.addView(tab.web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tab.web.setVisibility(View.GONE);

        selectTab(tab);
        if (!url.isEmpty() && !"about:blank".equals(url)) tab.web.loadUrl(url);
        return tab;
    }

    private WebView createWebView(final Tab tab) {
        WebView web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setBackgroundColor(Color.WHITE);
        web.setWebViewClient(new BrowserClient(tab));
        web.setWebChromeClient(new BrowserChrome(tab));
        web.setDownloadListener(new BrowserDownloads());
        // Deliberate: no addJavascriptInterface on this WebView, ever.
        return web;
    }

    // ------------------------------------------------------------------ tabs

    private void selectTab(Tab tab) {
        if (tab == null || !tabs.contains(tab)) return;
        if (current == tab) {
            tab.web.setVisibility(View.VISIBLE);
            updateTabStyles();
            updateChrome();
            return;
        }
        if (resumed) flushUsage(false);
        if (current != null) {
            current.web.setVisibility(View.GONE);
            try { current.web.onPause(); } catch (Throwable ignored) { }
        }
        current = tab;
        tab.web.setVisibility(View.VISIBLE);
        if (resumed) {
            try { tab.web.onResume(); } catch (Throwable ignored) { }
            startSiteSpan(tab, tab.web.getUrl(), tab.web.getTitle());
        }
        updateTabStyles();
        updateChrome();
        if ("measure".equals(mode)) injectMeasurePicker(tab.web);
    }

    private void closeTab(Tab tab) {
        if (tab == null) return;
        int index = tabs.indexOf(tab);
        if (index < 0) return;
        if (tab == current) {
            flushUsage(false);
            measureGeneration++;
        }
        tabs.remove(index);
        tabStrip.removeView(tab.header);
        webContainer.removeView(tab.web);
        try {
            tab.web.stopLoading();
            tab.web.loadUrl("about:blank");
            tab.web.destroy();
        } catch (Throwable ignored) { }

        if (tabs.isEmpty()) {
            finish();
            return;
        }
        if (tab == current) {
            current = null;
            selectTab(tabs.get(Math.min(index, tabs.size() - 1)));
        } else {
            updateTabStyles();
        }
    }

    private void updateTabStyles() {
        int active = 0x33808080;
        int idle = Color.TRANSPARENT;
        for (Tab t : tabs) {
            boolean on = (t == current);
            t.header.setBackgroundColor(on ? active : idle);
            t.label.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void updateTabLabel(Tab tab) {
        if (tab == null || tab.label == null) return;
        String title = tab.title;
        if (title == null || title.trim().isEmpty()) {
            String url = tab.web.getUrl();
            title = url == null || url.isEmpty() ? "New tab" : url;
        }
        tab.label.setText(title);
    }

    // ------------------------------------------------------------- navigation

    private void goTyped() {
        String input = urlBar.getText() == null ? "" : urlBar.getText().toString();
        String url = normalizeNavigation(input);
        if (url.isEmpty()) return;
        Uri uri = Uri.parse(url);
        if (isBlockedNavigation(uri)) {
            toast("The browser cannot open local addresses.");
            return;
        }
        if (!isWebScheme(uri)) {
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
            if ("about".equals(scheme) && current != null) current.web.loadUrl(url);
            else openExternally(uri);
            urlBar.clearFocus();
            hideKeyboard();
            return;
        }
        if (current != null) current.web.loadUrl(url);
        urlBar.clearFocus();
        hideKeyboard();
    }

    /** Only web pages are typed into the browser bar. */
    private static String normalizeNavigation(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        if (!s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) s = "https://" + s;
        String lower = s.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:")) return s;
        return "";
    }

    private static boolean isWebScheme(Uri uri) {
        String scheme = uri == null ? null : uri.getScheme();
        if (scheme == null) return false;
        scheme = scheme.toLowerCase(Locale.US);
        return "http".equals(scheme) || "https".equals(scheme);
    }

    private static boolean isBlockedNavigation(Uri uri) {
        if (uri == null) return true;
        if (!isWebScheme(uri)) return false;
        return isBlockedHost(uri.getHost());
    }

    private static boolean isBlockedHost(String host) {
        if (host == null) return false;
        host = host.trim().toLowerCase(Locale.US);
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return false;
        if ("localhost".equals(host) || host.endsWith(".localhost")) return true;
        if (host.matches("^127(\\..*)?$")) return true;
        if (host.matches("^0+$") || host.matches("^0+(\\.0+)*$")) return true;
        if (host.indexOf(':') >= 0) {
            // IPv6 literal: loopback, unspecified, link-local and unique-local.
            if ("::1".equals(host) || "::".equals(host) || "0:0:0:0:0:0:0:1".equals(host)) return true;
            if (host.startsWith("fe80") || host.startsWith("fc") || host.startsWith("fd")) return true;
            return host.endsWith(":127.0.0.1");
        }
        if (host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            String[] p = host.split("\\.");
            try {
                int a = Integer.parseInt(p[0]);
                int b = Integer.parseInt(p[1]);
                if (a == 0 || a == 10 || a == 127) return true;
                if (a == 169 && b == 254) return true;
                if (a == 172 && b >= 16 && b <= 31) return true;
                if (a == 192 && b == 168) return true;
                if (a == 100 && b >= 64 && b <= 127) return true;
            } catch (Throwable ignored) { }
        }
        // A bare integer is an IPv4 literal to many resolvers.
        if (host.matches("^\\d+$")) return true;
        return false;
    }

    private void openExternally(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) {
            toast("No app can open that link.");
        }
    }

    private void goBack() {
        if (current != null && current.web.canGoBack()) {
            current.web.goBack();
        } else if (tabs.size() > 1) {
            closeTab(current);
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    private void updateChrome() {
        if (current == null) return;
        String url = current.web.getUrl();
        if (!urlBar.hasFocus()) urlBar.setText(url == null ? "about:blank" : url);

        boolean canBack = current.web.canGoBack();
        boolean canForward = current.web.canGoForward();
        backButton.setEnabled(canBack);
        backButton.setAlpha(canBack ? 1f : 0.35f);
        forwardButton.setEnabled(canForward);
        forwardButton.setAlpha(canForward ? 1f : 0.35f);
        reloadButton.setText(current.loading ? "\u00d7" : "\u27f3");
        reloadButton.setContentDescription(current.loading ? "Stop" : "Reload");
        updateTabLabel(current);
    }

    // --------------------------------------------------------------- webview

    private class BrowserClient extends WebViewClient {
        private final Tab tab;

        BrowserClient(Tab tab) { this.tab = tab; }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            tab.loading = true;
            measureGeneration++;
            if (tab == current) {
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(0);
                updateChrome();
            }
            if (tab == current && resumed) startSiteSpan(tab, url, tab.title);
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            tab.loading = false;
            String title = view.getTitle();
            if (title != null && !title.trim().isEmpty()) tab.title = title;
            updateTabLabel(tab);
            if (tab == current) {
                progress.setProgress(100);
                progress.setVisibility(View.GONE);
                updateChrome();
                if (resumed) startSiteSpan(tab, url, tab.title);
                if ("measure".equals(mode)) {
                    final int generation = measureGeneration;
                    mainHandler.postDelayed(() -> {
                        if (generation == measureGeneration && tab == current) injectMeasurePicker(view);
                    }, 400);
                }
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(Uri.parse(url));
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri != null && isWebScheme(uri) && isBlockedHost(uri.getHost())) {
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // External sites never get a bypassed certificate.
            handler.cancel();
            toast("The site's certificate is not trusted.");
        }
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) return true;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if ("http".equals(scheme) || "https".equals(scheme)) {
            if (isBlockedHost(uri.getHost())) {
                toast("The browser cannot open local addresses.");
                return true;
            }
            return false;
        }
        if ("about".equals(scheme) || "data".equals(scheme)) return false;
        if ("file".equals(scheme) || "content".equals(scheme) || "javascript".equals(scheme)) return true;
        openExternally(uri);
        return true;
    }

    private class BrowserChrome extends WebChromeClient {
        private final Tab tab;

        BrowserChrome(Tab tab) { this.tab = tab; }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (tab != current) return;
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            if (newProgress < 100) tab.loading = true;
            if (newProgress >= 100) tab.loading = false;
            reloadButton.setText(tab.loading ? "\u00d7" : "\u27f3");
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (title != null && !title.trim().isEmpty()) tab.title = title;
            updateTabLabel(tab);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (pendingFile != null) pendingFile.onReceiveValue(null);
            pendingFile = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
                return true;
            } catch (Throwable t) {
                pendingFile = null;
                return false;
            }
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
            return false;
        }
    }

    private class BrowserDownloads implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            if (url == null || url.startsWith("blob:") || url.startsWith("data:")) {
                toast("That link cannot be downloaded directly.");
                return;
            }
            try {
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                if (name == null || name.trim().isEmpty()) name = "download";
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.setTitle(name);
                request.setDescription("Downloading...");
                request.setAllowedOverMetered(true);
                request.setAllowedOverRoaming(true);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                if (userAgent != null && !userAgent.isEmpty()) {
                    request.addRequestHeader("User-Agent", userAgent);
                }
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
                try {
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                } catch (Throwable t) {
                    request.setDestinationInExternalFilesDir(JawnBrowserActivity.this,
                            Environment.DIRECTORY_DOWNLOADS, name);
                }
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm == null) throw new IllegalStateException("no download manager");
                dm.enqueue(request);
                toast("Downloading " + name);
            } catch (Throwable t) {
                toast("The download could not start.");
            }
        }
    }

    // ----------------------------------------------------------------- usage

    private static class SiteSpan {
        final long startedAt;
        final String key;
        String url;
        String title;

        SiteSpan(long startedAt, String key, String url, String title) {
            this.startedAt = startedAt;
            this.key = key;
            this.url = url;
            this.title = title;
        }
    }

    private final Runnable usageTick = new Runnable() {
        @Override
        public void run() {
            flushUsage(true);
            if (resumed) mainHandler.postDelayed(this, USAGE_TICK_MS);
        }
    };

    private void startSiteSpan(Tab tab, String url, String title) {
        if (!resumed || tab == null || tab != current) return;
        String key = registrableDomain(url);
        if (key == null) {
            flushUsage(false);
            return;
        }
        long now = System.currentTimeMillis();
        if (span != null && span.key.equals(key)) {
            span.url = url;
            span.title = title;
            return;
        }
        flushUsage(false);
        span = new SiteSpan(now, key, url, title);
    }

    private void flushUsage(boolean restart) {
        SiteSpan done = span;
        span = null;
        if (done != null) {
            long ended = System.currentTimeMillis();
            if (ended - done.startedAt >= 1000L) submitUsage(done, ended);
        }
        if (restart && resumed && current != null) {
            startSiteSpan(current, current.web.getUrl(), current.web.getTitle());
        }
    }

    private void submitUsage(final SiteSpan done, final long endedAt) {
        if (apiPool.isShutdown()) return;
        apiPool.execute(() -> {
            Map<String, String> p = new HashMap<>();
            p.put("kind", "site");
            p.put("key", done.key);
            p.put("started_at", String.valueOf(done.startedAt));
            p.put("ended_at", String.valueOf(endedAt));
            p.put("idle_ms", "0");
            p.put("source", "browser");
            p.put("url", done.url == null ? "" : done.url);
            p.put("title", done.title == null ? "" : done.title);
            try { http("POST", "/manager/usage/session", p); } catch (Throwable ignored) { }
        });
    }

    private static String registrableDomain(String url) {
        if (url == null || url.isEmpty()) return null;
        Uri uri;
        try { uri = Uri.parse(url); } catch (Throwable t) { return null; }
        if (!isWebScheme(uri)) return null;
        String host = uri.getHost();
        if (host == null) return null;
        host = host.toLowerCase(Locale.US);
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return null;
        if (host.indexOf(':') >= 0) return host;
        if (host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) return host;
        String[] parts = host.split("\\.");
        if (parts.length <= 2) return host;
        String lastTwo = parts[parts.length - 2] + "." + parts[parts.length - 1];
        if (TWO_LEVEL_SUFFIXES.contains(lastTwo) && parts.length >= 3) {
            return parts[parts.length - 3] + "." + lastTwo;
        }
        return lastTwo;
    }

    // --------------------------------------------------------------- measure

    /**
     * Installs the picker on the live page. The page only ever gets evaluated
     * JavaScript; the result comes back through evaluateJavascript's
     * ValueCallback, never through a bridge.
     */
    private void injectMeasurePicker(final WebView web) {
        if (web == null || !"measure".equals(mode)) return;
        final int generation = ++measureGeneration;
        measureStatus.setVisibility(View.VISIBLE);
        measureStatus.setText("Tap the value you want to measure.");
        web.evaluateJavascript(MEASURE_JS, value -> {
            if (generation != measureGeneration || current == null || current.web != web) return;
            pollMeasure(web, generation, 0);
        });
        if (!measureHintShown) {
            measureHintShown = true;
            toast("Tap the value you want to measure.");
        }
    }

    private void pollMeasure(final WebView web, final int generation, final int attempt) {
        if (generation != measureGeneration || current == null || current.web != web) return;
        if (attempt > 800) return;
        web.evaluateJavascript(MEASURE_POLL_JS, value -> {
            if (generation != measureGeneration || current == null || current.web != web) return;
            String text = unquoteJs(value);
            if (text == null) {
                mainHandler.postDelayed(() -> pollMeasure(web, generation, attempt + 1), 350);
                return;
            }
            try {
                JSONObject head = new JSONObject(text);
                final int length = head.optInt("len", 0);
                if (length <= 0) {
                    web.evaluateJavascript(
                            "window.__jawnMeasurePick=null;window.__jawnMeasureInstalled=false;", null);
                    mainHandler.postDelayed(() -> injectMeasurePicker(web), 200);
                    return;
                }
                measureStatus.setText("Reading the page...");
                fetchMeasureHtml(web, generation, head, 0, length, new StringBuilder());
            } catch (Throwable t) {
                mainHandler.postDelayed(() -> pollMeasure(web, generation, attempt + 1), 350);
            }
        });
    }

    private void fetchMeasureHtml(final WebView web, final int generation, final JSONObject head,
                                  final int offset, final int total, final StringBuilder out) {
        if (generation != measureGeneration || current == null || current.web != web) return;
        if (offset >= total) {
            try { head.put("html", out.toString()); } catch (Throwable t) { return; }
            postMeasure(head);
            return;
        }
        int count = Math.min(MEASURE_CHUNK, total - offset);
        String js = "(window.__jawnMeasurePick?window.__jawnMeasurePick.html.substr("
                + offset + "," + count + "):null)";
        web.evaluateJavascript(js, value -> {
            if (generation != measureGeneration || current == null || current.web != web) return;
            String piece = unquoteJs(value);
            if (piece == null || piece.isEmpty()) {
                measureStatus.setText("The page changed before it could be read.");
                return;
            }
            out.append(piece);
            fetchMeasureHtml(web, generation, head, offset + piece.length(), total, out);
        });
    }

    private void postMeasure(final JSONObject head) {
        final String app = measureApp;
        final String selector = head.optString("selector", "");
        final String html = head.optString("html", "");
        if (app.isEmpty() || selector.isEmpty() || html.isEmpty()) {
            measureStatus.setText("Nothing on this page could be measured.");
            return;
        }
        if (apiPool.isShutdown()) return;
        final Map<String, String> p = new HashMap<>();
        p.put("app", app);
        p.put("uuid", measureUuid);
        p.put("url", head.optString("url", ""));
        p.put("selector", selector);
        p.put("index", String.valueOf(head.optInt("index", 0)));
        p.put("html", html);
        p.put("title", head.optString("title", ""));
        if (!measureName.isEmpty()) p.put("measure", measureName);

        apiPool.execute(() -> {
            try {
                String body = http("POST", "/manager/web/measure/live", p);
                JSONObject answer = new JSONObject(body == null ? "{}" : body);
                runOnUiThread(() -> showCandidates(answer));
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    measureStatus.setText("The page could not be measured.");
                    toast("The page could not be measured.");
                });
            }
        });
    }

    private void showCandidates(JSONObject answer) {
        if (isFinishing() || isDestroyed()) return;
        if (answer.optInt("ok", 0) != 1) {
            measureStatus.setText("Nothing on this page could be measured.");
            toast(safe(answer.optString("error", "The page could not be measured.")));
            return;
        }
        JSONArray candidates = answer.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("No values found")
                    .setMessage("Nothing on this page read as a value. Try clicking the number itself.")
                    .setPositiveButton("Pick again", (d, w) -> {
                        if (current != null) injectMeasurePicker(current.web);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        final List<JSONObject> specs = new ArrayList<>();
        String[] labels = new String[candidates.length()];
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject c = candidates.optJSONObject(i);
            if (c == null) c = new JSONObject();
            specs.add(c.optJSONObject("spec"));
            String kind = c.optString("kind", "value");
            String value = c.optString("value", "");
            if (value.length() > 160) value = value.substring(0, 160) + "...";
            labels[i] = kind + " - " + value;
        }
        final int[] chosen = { 0 };
        new AlertDialog.Builder(this)
                .setTitle("Choose a measure")
                .setSingleChoiceItems(labels, 0, (d, which) -> chosen[0] = which)
                .setPositiveButton("Save", (d, which) -> saveCandidate(specs.get(chosen[0])))
                .setNeutralButton("Pick again", (d, which) -> {
                    if (current != null) injectMeasurePicker(current.web);
                })
                .setNegativeButton("Cancel", null)
                .show();
        measureStatus.setText("Choose the value to save.");
    }

    private void saveCandidate(final JSONObject spec) {
        if (spec == null) return;
        if (!measureName.isEmpty()) {
            submitMeasure(spec, measureName);
            return;
        }
        if (apiPool.isShutdown()) return;
        final String app = measureApp;
        final String uuid = measureUuid;
        apiPool.execute(() -> {
            List<String> names = fetchMeasureNames(app, uuid);
            runOnUiThread(() -> {
                if (names.size() == 1) {
                    submitMeasure(spec, names.get(0));
                } else if (names.size() > 1) {
                    showMeasureChooser(spec, names);
                } else {
                    // No configured measure: the route accepts any subsetting, so
                    // keep the pick rather than dropping the person's work.
                    submitMeasure(spec, "value");
                }
            });
        });
    }

    private List<String> fetchMeasureNames(String app, String uuid) {
        List<String> out = new ArrayList<>();
        try {
            Map<String, String> p = new HashMap<>();
            p.put("app", app);
            p.put("uuid", uuid);
            String body = http("GET", "/manager/web/measure/toggle", p);
            JSONObject o = new JSONObject(body == null ? "{}" : body);
            JSONObject measures = o.optJSONObject("measures");
            if (measures != null) {
                Iterator<String> it = measures.keys();
                while (it.hasNext()) out.add(it.next());
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private void showMeasureChooser(final JSONObject spec, final List<String> names) {
        final int[] chosen = { 0 };
        new AlertDialog.Builder(this)
                .setTitle("Measure")
                .setSingleChoiceItems(names.toArray(new String[0]), 0, (d, which) -> chosen[0] = which)
                .setPositiveButton("Save", (d, which) -> submitMeasure(spec, names.get(chosen[0])))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void submitMeasure(final JSONObject spec, final String measure) {
        if (apiPool.isShutdown()) return;
        final Map<String, String> p = new HashMap<>();
        p.put("app", measureApp);
        p.put("measure", measure);
        p.put("spec", spec.toString());
        apiPool.execute(() -> {
            try {
                http("POST", "/manager/web/measure/submit", p);
                runOnUiThread(() -> {
                    measureStatus.setText("Measure saved.");
                    toast("Measure saved.");
                });
            } catch (Throwable t) {
                runOnUiThread(() -> toast("The measure could not be saved."));
            }
        });
    }

    // ------------------------------------------------------------- manager API

    /**
     * The app's own HTTP client. The WebView above never gets this trust: only
     * these manager calls (loopback or the house's own name) accept the house
     * certificate, matching the existing JawnUpdater/self_deploy rail.
     */
    private String http(String method, String path, Map<String, String> params) throws Exception {
        if (managerBase == null || managerBase.isEmpty()) throw new Exception("no manager");
        String urlText = managerBase + path;
        if ("GET".equals(method) && params != null && !params.isEmpty()) {
            StringBuilder q = new StringBuilder();
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (q.length() > 0) q.append('&');
                q.append(URLEncoder.encode(e.getKey(), "UTF-8"));
                q.append('=');
                q.append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
            }
            urlText = urlText + (urlText.indexOf('?') >= 0 ? "&" : "?") + q;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        if (conn instanceof HttpsURLConnection) {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, MANAGER_TRUST, new SecureRandom());
            ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
            ((HttpsURLConnection) conn).setHostnameVerifier((host, session) -> true);
        }
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        if (authCookie != null && !authCookie.isEmpty()) {
            conn.setRequestProperty("Cookie", authCookie);
        }

        if ("POST".equals(method)) {
            conn.setDoOutput(true);
            conn.setChunkedStreamingMode(8192);
            conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8");
            OutputStream out = conn.getOutputStream();
            try {
                boolean first = true;
                if (params != null) {
                    for (Map.Entry<String, String> e : params.entrySet()) {
                        if (!first) out.write('&');
                        first = false;
                        out.write(URLEncoder.encode(e.getKey(), "UTF-8").getBytes("UTF-8"));
                        out.write('=');
                        out.write(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8")
                                .getBytes("UTF-8"));
                    }
                }
                out.flush();
            } finally {
                out.close();
            }
        }

        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(in, MAX_RESPONSE);
        conn.disconnect();
        return body;
    }

    private static String readAll(InputStream in, int max) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            int take = Math.min(n, max - total);
            if (take <= 0) break;
            bos.write(buf, 0, take);
            total += take;
        }
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String unquoteJs(String value) {
        if (value == null) return null;
        if ("null".equals(value) || "undefined".equals(value)) return null;
        try {
            return new JSONArray("[" + value + "]").getString(0);
        } catch (Throwable t) {
            return null;
        }
    }

    // --------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (current != null) {
            try { current.web.onResume(); } catch (Throwable ignored) { }
            startSiteSpan(current, current.web.getUrl(), current.web.getTitle());
            if ("measure".equals(mode)) injectMeasurePicker(current.web);
        }
        mainHandler.removeCallbacks(usageTick);
        mainHandler.postDelayed(usageTick, USAGE_TICK_MS);
    }

    @Override
    protected void onPause() {
        resumed = false;
        mainHandler.removeCallbacks(usageTick);
        flushUsage(false);
        measureGeneration++;
        if (current != null) {
            try { current.web.onPause(); } catch (Throwable ignored) { }
        }
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) { }
        super.onPause();
    }

    @Override
    protected void onStop() {
        flushUsage(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        resumed = false;
        mainHandler.removeCallbacksAndMessages(null);
        flushUsage(false);
        measureGeneration++;
        for (Tab t : new ArrayList<>(tabs)) {
            try {
                t.web.stopLoading();
                t.web.loadUrl("about:blank");
                t.web.destroy();
            } catch (Throwable ignored) { }
        }
        tabs.clear();
        apiPool.shutdown();
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (pendingFile != null) {
                pendingFile.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                pendingFile = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---------------------------------------------------------------- helpers

    private static String safe(String s) { return s == null ? "" : s; }

    private static String safe(String s, String fallback) {
        return s == null || s.trim().isEmpty() ? fallback : s;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private int themeColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(attr, value, true)) {
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
            if (value.resourceId != 0) {
                try { return getResources().getColor(value.resourceId, getTheme()); }
                catch (Throwable ignored) { }
            }
        }
        return fallback;
    }

    private void toast(String message) {
        try { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
    }

    private void showKeyboard() {
        try {
            InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (ime != null) ime.showSoftInput(urlBar, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) { }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        } catch (Throwable ignored) { }
    }

    private static class Tab {
        WebView web;
        LinearLayout header;
        TextView label;
        String title = "New tab";
        boolean loading;
    }

    // The picker. window-level capture so a page's own click handlers cannot
    // swallow the pick; click alone (not touchstart) so a scroll never captures.
    private static final String MEASURE_JS =
        "(function(){try{" +
        "if(window.__jawnMeasureInstalled){return 'installed';}" +
        "window.__jawnMeasureInstalled=true;window.__jawnMeasurePick=null;" +
        "var style=document.createElement('style');" +
        "style.textContent='.jawn-measure-hover{outline:2px solid #d21f3c !important;outline-offset:1px !important;cursor:crosshair !important;}';" +
        "(document.head||document.documentElement).appendChild(style);" +
        "var marked=[];" +
        "function attrValue(v){return String(v).replace(/\\\\/g,'\\\\\\\\').replace(/\"/g,'\\\\\"');}" +
        "function selectorFor(el){if(!el||el.nodeType!==1)return '';" +
        "if(el.id){var by=el.tagName.toLowerCase()+'[id=\"'+attrValue(el.id)+'\"]';" +
        "try{if(document.querySelectorAll(by).length===1)return by;}catch(e){}}" +
        "var parts=[];var node=el;" +
        "while(node&&node.nodeType===1&&node!==document.documentElement){" +
        "var tag=node.tagName.toLowerCase();var parent=node.parentElement;if(!parent)break;" +
        "var i=0;var sib=node;" +
        "while(sib){if(sib.nodeType===1&&sib.tagName===node.tagName){i++;}sib=sib.previousElementSibling;}" +
        "parts.unshift(tag+':nth-of-type('+i+')');node=parent;}" +
        "return parts.length?parts.join(' > '):(el.tagName||'').toLowerCase();}" +
        "function indexFor(el,selector){try{var nodes=document.querySelectorAll(selector);" +
        "for(var i=0;i<nodes.length;i++){if(nodes[i]===el)return i;}}catch(e){}return 0;}" +
        "function clear(){for(var i=0;i<marked.length;i++){try{marked[i].classList.remove('jawn-measure-hover');}catch(e){}}marked=[];}" +
        "function over(e){var el=e.target;if(!el||el.nodeType!==1)return;clear();" +
        "try{el.classList.add('jawn-measure-hover');marked.push(el);}catch(x){}}" +
        "function done(){window.removeEventListener('mouseover',over,true);" +
        "window.removeEventListener('click',click,true);clear();window.__jawnMeasureInstalled=false;" +
        "try{if(style.parentNode){style.parentNode.removeChild(style);}}catch(e){}}" +
        "function pick(el){if(!el||el.nodeType!==1)return;" +
        "var selector=selectorFor(el);var index=indexFor(el,selector);" +
        "var html='';try{html=document.documentElement.outerHTML||'';}catch(e){html='';}" +
        "if(html.length>1572864){html=html.substring(0,1572864);}" +
        "window.__jawnMeasurePick={selector:selector,index:index,html:html," +
        "title:(document.title||''),url:(location.href||'')};done();}" +
        "function click(e){if(!e.target)return;e.preventDefault();e.stopPropagation();" +
        "if(e.stopImmediatePropagation)e.stopImmediatePropagation();pick(e.target);return false;}" +
        "window.addEventListener('mouseover',over,true);" +
        "window.addEventListener('click',click,true);return 'installed';" +
        "}catch(err){return 'error:'+err;}})()";

    private static final String MEASURE_POLL_JS =
        "(window.__jawnMeasurePick?JSON.stringify({" +
        "selector:window.__jawnMeasurePick.selector," +
        "index:window.__jawnMeasurePick.index," +
        "title:window.__jawnMeasurePick.title," +
        "url:window.__jawnMeasurePick.url," +
        "len:window.__jawnMeasurePick.html.length}):null)";
}
