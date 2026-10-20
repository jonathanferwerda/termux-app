package com.termux.app;

import android.content.Context;
import android.webkit.JavascriptInterface;

/**
 * The remote_control VPN button's native half. The embedded Tailscale client
 * owns the VpnService, so the page asks this bridge to read the tailnet status
 * and to choose the exit node. A Linux peer uses the manager route instead;
 * the page prefers this bridge whenever it is present.
 */
public class JawnVpnBridge {
    private final Context context;

    public JawnVpnBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    @JavascriptInterface
    public String status() {
        return JawnTailnetController.statusJson(context);
    }

    @JavascriptInterface
    public String setExitNode(String ip) {
        return JawnTailnetController.setExitNode(context, ip == null ? "" : ip);
    }
}
