package com.termux.app;

import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import org.json.JSONObject;

/**
 * The JawnOS keyboard as an Android input method.
 *
 * <p>It is the manager's own soft keyboard, not a second copy: the WebView loads
 * {@code /manager/keyboard/ime}, a page that includes the same
 * {@code soft_keyboard} fragment and the same {@code soft_keyboard.js} the dock
 * uses. That keeps the layouts, the calculator, the unit/currency conversions
 * and the field-aware recommendation row identical - same data, same code, same
 * live rates - wherever the keyboard is used.
 *
 * <p>{@code window.JawnIme} is added before the page parses. In that mode
 * soft_keyboard.js commits every key through the {@link InputConnection}
 * instead of a DOM field, and this service reports the text around the caret
 * back after each edit so the conversion and recommendation rows can read it.
 *
 * <p>The page needs the manager, so it is loaded on the host the app opens with
 * the app's session cookie. When the house cannot be reached - no session, no
 * ticket, the Gate, or a load error - the bundled
 * {@code assets/ime/keyboard.html} is the fallback: a plain typing surface that
 * still works, without the field-aware extras.
 */
public class JawnImeService extends InputMethodService {

    private static final String TAG = "JawnIme";
    private static final String FALLBACK_URL = "file:///android_asset/ime/keyboard.html";
    private static final int LOAD_TIMEOUT_MS = 4000;

    private WebView web;
    private FrameLayout frame;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean numeric;
    private String context = "";

    private String keyboardUrl;
    private String houseHost = "";
    private boolean minting = false;
    private boolean fallbackActive = false;
    private boolean fallbackRetry = false;
    private boolean pageLoaded = false;

    private final Runnable loadTimeout = new Runnable() {
        @Override
        public void run() {
            if (!pageLoaded) { loadFallback("timeout"); }
        }
    };

    @Override
    public View onCreateInputView() {
        // An input method's window is not hardware accelerated by default; a
        // WebView needs it to paint and scroll smoothly.
        try {
            getWindow().getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        } catch (Throwable ignored) { }

        keyboardUrl = keyboardUrl();
        try { houseHost = Uri.parse(keyboardUrl).getHost(); }
        catch (Throwable ignored) { houseHost = ""; }

        WebView view = new WebView(this);
        web = view;
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        // the layout choice and the conversion history are localStorage
        settings.setDomStorageEnabled(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setSupportMultipleWindows(false);
        settings.setSaveFormData(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        view.setBackgroundColor(Color.rgb(242, 243, 245));
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);

        // InputMethodService wraps the returned view in WRAP_CONTENT, so a
        // WebView measures its own page (whose height:100% then has nothing to
        // resolve against) and collapses. A fixed-height container gives the
        // window its height and lets the page fill it. The page reports its real
        // height through the bridge once it has painted.
        FrameLayout host = new FrameLayout(this);
        frame = host;
        host.setBackgroundColor(Color.rgb(242, 243, 245));
        host.setMinimumHeight(keyboardHeight() + navigationBarHeight());
        host.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight() + navigationBarHeight()));
        // the system's navigation bar is drawn over the foot of the window; the
        // page gets the height above it, so the last key row is never behind it
        host.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight(), Gravity.TOP));

        view.addJavascriptInterface(new Bridge(), "JawnIme");
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                pageLoaded = true;
                ui.removeCallbacks(loadTimeout);
                configure(v);
                syncText();
                // The Gate can be rendered at the same URL when the session has
                // lapsed; only the manager's keyboard page carries the dock.
                if (url != null && url.startsWith(JawnImeService.this.keyboardUrl)) {
                    v.evaluateJavascript(
                        "(function(){return !!(window.softKbImeStart && document.getElementById('jawn_soft_keyboard'));})()",
                        new android.webkit.ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                if (!"true".equals(value)) { loadFallback("not the keyboard page"); }
                            }
                        });
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) { return true; }
                // The ticket page's own redirector lands on /manager. The cookie
                // is set by then, so go to the keyboard page instead of painting
                // the whole manager inside an input method.
                if (minting && "/manager".equals(uri.getPath())) {
                    minting = false;
                    v.loadUrl(keyboardUrl);
                    return true;
                }
                return !allowedHost(uri.getHost());
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) { loadFallback("load error"); }
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler,
                                           android.net.http.SslError error) {
                // The house serves its own certificate (a wildcard, or a
                // self-signed one on a name of its own). Trust only the manager.
                String host = Uri.parse(error.getUrl()).getHost();
                if (host != null && allowedHost(host)) { handler.proceed(); }
                else { handler.cancel(); }
            }
        });
        loadStart();
        ui.postDelayed(loadTimeout, LOAD_TIMEOUT_MS);
        return host;
    }

    /**
     * Load the keyboard page when the app already holds a session. Without one,
     * the ticket page mints it (the same door the dashboard uses) and its
     * redirect is caught above; with no ticket either, the bundled keyboard is
     * the answer so the person can still type.
     */
    private void loadStart() {
        String cookie = null;
        try { cookie = CookieManager.getInstance().getCookie(keyboardUrl); }
        catch (Throwable ignored) { }
        if (cookie != null && cookie.contains("president=")) {
            web.loadUrl(keyboardUrl);
            return;
        }
        String ticket = "";
        try { ticket = JawnLauncher.getUiUrl(this, JawnLauncher.getJawnPort(this)); }
        catch (Throwable ignored) { }
        if (ticket != null && ticket.contains("/box_office/")) {
            minting = true;
            web.loadUrl(ticket);
            return;
        }
        loadFallback("no session");
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        int cls = info == null ? 0 : (info.inputType & InputType.TYPE_MASK_CLASS);
        numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE;
        context = contextFor(info);
        if (web != null) {
            // a load that raced the manager's own restart falls back; try the
            // keyboard page again on the next field rather than staying plain
            if (fallbackActive && fallbackRetry) {
                fallbackActive = false;
                fallbackRetry = false;
                pageLoaded = false;
                loadStart();
            }
            web.onResume();
            web.resumeTimers();
            configure(web);
            syncText();
        }
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart,
                                  int newSelEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd);
        // an edit the app made itself (autocorrect, a paste) must reach the rows too
        syncText();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        if (web != null) { web.onPause(); }
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacks(loadTimeout);
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    /** The manager's keyboard page on the host the app opens. */
    private String keyboardUrl() {
        String host = JawnLauncher.managerHost(this);
        return "https://" + host + ":" + JawnLauncher.getJawnPort(this)
            + "/manager/keyboard/ime";
    }

    private boolean allowedHost(String host) {
        if (host == null) { return false; }
        return host.equals(houseHost) || host.equals("127.0.0.1") || host.equals("localhost");
    }

    private void loadFallback(String why) {
        if (fallbackActive || web == null) { return; }
        fallbackActive = true;
        fallbackRetry = !"no session".equals(why);
        pageLoaded = false;
        ui.removeCallbacks(loadTimeout);
        Log.w(TAG, "using the bundled keyboard: " + why);
        web.loadUrl(FALLBACK_URL);
    }

    private void configure(WebView view) {
        if (view == null) { return; }
        InputConnection ic = getCurrentInputConnection();
        CharSequence before = ic == null ? null : ic.getTextBeforeCursor(256, 0);
        String text = before == null ? "null" : JSONObject.quote(before.toString());
        view.evaluateJavascript(
            "window.imeConfigure && imeConfigure(" + (numeric ? "true" : "false") + ","
                + JSONObject.quote(context) + "," + text + ");", null);
    }

    /**
     * What kind of value the field names. Android gives an input method only the
     * editor's class and its hint; the hint is where an app usually says "time",
     * "duration" or "warranty", and a datetime editor is a timestamp in words.
     */
    private String contextFor(EditorInfo info) {
        if (info == null) { return ""; }
        CharSequence hint = info.hintText;
        String text = hint == null ? "" : hint.toString().toLowerCase();
        if (text.contains("warranty")) { return "warranty"; }
        if (text.contains("duration")) { return "duration"; }
        if (text.contains("repeat") || text.contains("schedule")) { return "schedule"; }
        if (text.contains("time") || text.contains("date") || text.contains("when")) { return "when"; }
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        if (cls == InputType.TYPE_CLASS_DATETIME) { return "when"; }
        return "";
    }

    /** Tell the page the text before the caret. It is what the rows read. */
    private void syncText() {
        if (web == null) { return; }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) { return; }
        CharSequence before = ic.getTextBeforeCursor(256, 0);
        CharSequence after = ic.getTextAfterCursor(256, 0);
        String js = "window.imeText && imeText("
            + JSONObject.quote(before == null ? "" : before.toString()) + ","
            + JSONObject.quote(after == null ? "" : after.toString()) + ");";
        web.evaluateJavascript(js, null);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int navigationBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    /** About the height of the manager's dock, but never more than half a short screen. */
    private int keyboardHeight() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int wanted = dp(300);
        int ceiling = (int) (Math.min(dm.widthPixels, dm.heightPixels) * 0.55f);
        return Math.max(dp(200), Math.min(wanted, ceiling));
    }

    /**
     * The page's own height report, in CSS px (which are dp in this viewport).
     * The system draws its navigation bar over the foot of an input method's
     * window, so the window is the dock plus that strip; the page lifts the dock
     * above it.
     */
    private void resize(int heightCss) {
        if (frame == null) { return; }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int nav = navigationBarHeight();
        int px = (int) (heightCss * dm.density + 0.5f);
        // the layout chooser is the tallest panel; allow it most of the screen
        int ceiling = (int) (dm.heightPixels * 0.75f);
        final int content = Math.max(dp(170), Math.min(px, ceiling));
        final int height = content + nav;
        final ViewGroup.LayoutParams lp = frame.getLayoutParams();
        if (lp != null && lp.height == height) { return; }
        ui.post(new Runnable() {
            @Override
            public void run() {
                ViewGroup.LayoutParams params = frame.getLayoutParams();
                if (params != null) {
                    params.height = height;
                    frame.setLayoutParams(params);
                }
                frame.setMinimumHeight(dp(170));
                if (web != null) {
                    ViewGroup.LayoutParams wp = web.getLayoutParams();
                    if (wp != null) {
                        wp.height = content;
                        web.setLayoutParams(wp);
                    }
                }
                frame.requestLayout();
                try { getWindow().getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT, height); } catch (Throwable ignored) { }
            }
        });
    }

    /** window.JawnIme: the page's keys land in the editor. */
    public class Bridge {

        @JavascriptInterface
        public void send(String text) {
            final String value = text == null ? "" : text;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) { ic.commitText(value, 1); }
                    syncText();
                }
            });
        }

        @JavascriptInterface
        public void backspace() {
            deleteBack(1);
        }

        @JavascriptInterface
        public void deleteBack(final int count) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic == null) { return; }
                    CharSequence selected = ic.getSelectedText(0);
                    if (selected != null && selected.length() > 0) {
                        ic.commitText("", 1);
                    } else {
                        ic.deleteSurroundingText(Math.max(1, count), 0);
                    }
                    syncText();
                }
            });
        }

        @JavascriptInterface
        public void enter() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (!sendDefaultEditorAction(true)) {
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) { ic.commitText("\n", 1); }
                    }
                    syncText();
                }
            });
        }

        /** A number field's whole value is the calculator's current operand. */
        @JavascriptInterface
        public void setNumber(String text) {
            replaceAll(text);
        }

        /** Replace everything the editor holds (the dock assigns field.value). */
        @JavascriptInterface
        public void replaceAll(String text) {
            final String value = text == null ? "" : text;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic == null) { return; }
                    CharSequence before = ic.getTextBeforeCursor(4096, 0);
                    CharSequence after = ic.getTextAfterCursor(4096, 0);
                    int b = before == null ? 0 : before.length();
                    int a = after == null ? 0 : after.length();
                    boolean selected = false;
                    if (b + a > 0) {
                        try {
                            ic.setSelection(0, b + a);
                            selected = true;
                        } catch (Throwable ignored) { }
                        if (!selected) { ic.deleteSurroundingText(b, a); }
                    }
                    ic.commitText(value, 1);
                    syncText();
                }
            });
        }

        /** Ask the service to report the editor's text again. */
        @JavascriptInterface
        public void sync() {
            ui.post(new Runnable() {
                @Override
                public void run() { syncText(); }
            });
        }

        /** The page has painted; size the input method window to it. */
        @JavascriptInterface
        public void resize(final float heightCss) {
            ui.post(new Runnable() {
                @Override
                public void run() { JawnImeService.this.resize((int) heightCss); }
            });
        }

        @JavascriptInterface
        public void hide() {
            ui.post(new Runnable() {
                @Override
                public void run() { requestHideSelf(0); }
            });
        }
    }
}
