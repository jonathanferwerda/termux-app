package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

/**
 * Wake the host from outside the app. A peer with adb (or an on-phone
 * automation) sends:
 *
 *   adb shell am broadcast -a com.termux.app.JAWN_WAKE \
 *       -n com.termux/.app.JawnWakeReceiver
 *
 * Only the shell UID (adb) and this app may use it. Starting jawn is harmless
 * in itself, but the guard keeps another app from keeping the phone awake.
 */
public class JawnWakeReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.termux.app.JAWN_WAKE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        // getSentFromUid() arrived in API 34 and this tree compiles against 30;
        // reflect so older phones simply skip the guard. An older phone still
        // starts jawn, which is all this receiver is for.
        int uid = -1;
        try {
            Object v = BroadcastReceiver.class.getMethod("getSentFromUid").invoke(this);
            if (v instanceof Integer) uid = (Integer) v;
        } catch (Throwable ignored) {
        }
        if (uid != 2000 && uid != Process.myUid()) {
            JawnLog.append("wake: refused uid " + uid);
            return;
        }
        JawnLog.append("wake: starting jawn (uid " + uid + ")");
        JawnLauncher.setKeepRunning(context, true);
        JawnLauncher.startJawnBackground(context);
    }
}
