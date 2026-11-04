package com.jawnos.app;

import android.webkit.WebView;

/**
 * The manager page's own on-screen keyboard owns Back while it is up.
 *
 * <p>The dock is a DOM keyboard, not a platform input method, so the WebView's
 * activity would otherwise treat Back as history and leave the screen. Ask the
 * page whether the dock is showing; if it is, hide it and consume the press,
 * and only fall through to history when it is not.
 */
final class JawnSoftKeyboard {
    private JawnSoftKeyboard() {}

    static void hideIfOpen(final WebView web, final Runnable next) {
        if (web == null) {
            next.run();
            return;
        }
        final String js = "(function(){try{"
                + "if(typeof softKbVisible==='function'&&typeof softKbHide==='function'&&softKbVisible()){softKbHide();return 'kbd';}"
                + "return '';}catch(e){return '';}})()";
        try {
            web.evaluateJavascript(js, value -> {
                if (value != null && value.contains("kbd")) return;
                next.run();
            });
        } catch (Throwable t) {
            next.run();
        }
    }
}
