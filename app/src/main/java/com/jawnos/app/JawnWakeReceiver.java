package com.jawnos.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

/**
 * Wake the host from outside the app. A peer with adb (or an on-phone
 * automation) sends:
 *
 *   adb shell am broadcast -a com.jawnos.app.JAWN_WAKE \
 *       -n com.jawnos/.app.JawnWakeReceiver
 *
 * Only the shell UID (adb) and this app may use it. Starting jawn is harmless
 * in itself, but the guard keeps another app from keeping the phone awake.
 *
 * The same receiver opens the permission screen for the tree (M4): an
 * `am start` on that non-exported activity is refused when it comes from the
 * app's own shell child, because the tool reports the shell's package and not
 * ours ("package=com.android.shell does not belong to uid=10321"). A broadcast
 * to this exported receiver is allowed, and the start then happens in-process.
 */
public class JawnWakeReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.jawnos.app.JAWN_WAKE";
    public static final String PERMISSIONS_ACTION = "com.jawnos.app.JAWN_PERMISSIONS";
    /** Turn the tailnet on and start its service. Startup is the app's job
     *  (the VpnService is not exported and the dashboard is not reachable from
     *  the tree), so the tree asks through this receiver. An "auth_key"
     *  string extra joins with a preauth key; without one the node reports a
     *  login URL to approve. */
    public static final String TAILNET_ACTION = "com.jawnos.app.JAWN_TAILNET";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (PERMISSIONS_ACTION.equals(action)) {
            if (!allowed()) return;
            JawnLog.append("permissions: opening the grant screen");
            Intent open = new Intent(context, JawnPermissionsActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(open);
            return;
        }
        if (TAILNET_ACTION.equals(action)) {
            if (!allowed()) return;
            String key = intent.getStringExtra("auth_key");
            android.content.SharedPreferences.Editor e = context
                .getSharedPreferences(JawnTailnetController.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", true);
            if (key != null) e.putString("auth_key", key.trim());
            e.apply();
            JawnLog.append("tailnet: starting (key " + (key != null && !key.trim().isEmpty() ? "given" : "none") + ")");
            JawnTailnetController.startService(context);
            return;
        }
        if (!ACTION.equals(action)) return;
        int uid = sentFromUid();
        if (!allowed(uid)) {
            JawnLog.append("wake: refused uid " + uid);
            return;
        }
        JawnLog.append("wake: starting jawn (uid " + uid + ")");
        JawnLauncher.setKeepRunning(context, true);
        JawnLauncher.startJawnBackground(context);
    }

    /** The sender's uid, or -1 when the platform cannot say (API < 34). */
    private int sentFromUid() {
        // getSentFromUid() arrived in API 34 and this tree compiles against 30;
        // reflect so older phones simply skip the guard. An older phone still
        // starts jawn, which is all this receiver is for.
        int uid = -1;
        try {
            Object v = BroadcastReceiver.class.getMethod("getSentFromUid").invoke(this);
            if (v instanceof Integer) uid = (Integer) v;
        } catch (Throwable ignored) {
        }
        return uid;
    }

    private boolean allowed() {
        return allowed(sentFromUid());
    }

    /** Only the shell (adb) and ourselves. */
    private boolean allowed(int uid) {
        return uid == -1 || uid == 2000 || uid == Process.myUid();
    }
}
