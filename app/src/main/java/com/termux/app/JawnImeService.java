package com.termux.app;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * The JawnOS keyboard as an Android input method, drawn by the same HTML/CSS
 * keyboard the manager uses rather than a native Android view.
 *
 * <p>The page is a standalone asset: a light panel, white keys, grey specials
 * and the yellow return, with abc / 123 / #+= pages. A small JavaScript bridge
 * commits every key through the InputConnection, so the same page serves every
 * Android app. The manager's dock inside JawnOS apps keeps its field-aware
 * extras (time chips, units, conversions); this is the keyboard for the rest.
 */
public class JawnImeService extends InputMethodService {

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean numeric;

    @Override
    public View onCreateInputView() {
        // An input method's window is not hardware accelerated by default; a
        // WebView needs it to paint and scroll smoothly.
        try {
            getWindow().getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        } catch (Throwable ignored) { }

        WebView view = new WebView(this);
        web = view;
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDomStorageEnabled(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setSupportMultipleWindows(false);
        settings.setSaveFormData(false);
        settings.setAllowContentAccess(false);

        view.setBackgroundColor(0xFFF2F3F5);
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);

        // InputMethodService wraps the returned view in WRAP_CONTENT, so a
        // WebView measures its own page (whose height:100% then has nothing to
        // resolve against) and collapses. A fixed-height container gives the
        // window its height and lets the page fill it.
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFFF2F3F5);
        frame.setMinimumHeight(keyboardHeight());
        frame.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight()));
        frame.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight()));

        view.addJavascriptInterface(new Bridge(), "JawnIme");
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                configure(v);
            }
        });
        view.loadUrl("file:///android_asset/ime/keyboard.html");
        return frame;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        int cls = info == null ? 0 : (info.inputType & InputType.TYPE_MASK_CLASS);
        numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE;
        if (web != null) {
            web.onResume();
            web.resumeTimers();
            configure(web);
        }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        if (web != null) { web.onPause(); }
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onDestroy() {
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

    private void configure(WebView view) {
        if (view == null) { return; }
        view.evaluateJavascript("imeConfigure(" + (numeric ? "true" : "false") + ");", null);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** About the height of the manager's dock, but never more than half a short screen. */
    private int keyboardHeight() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int wanted = dp(300);
        int ceiling = (int) (Math.min(dm.widthPixels, dm.heightPixels) * 0.55f);
        return Math.max(dp(200), Math.min(wanted, ceiling));
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
                }
            });
        }

        @JavascriptInterface
        public void backspace() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic == null) { return; }
                    CharSequence selected = ic.getSelectedText(0);
                    if (selected != null && selected.length() > 0) {
                        ic.commitText("", 1);
                    } else {
                        ic.deleteSurroundingText(1, 0);
                    }
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
                }
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
