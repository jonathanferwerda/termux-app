package com.termux.app;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * The switch and screen-on listener for the JawnOS lock screen (option A).
 *
 * The face is {@link JawnLockActivity}, drawn over the phone's keyguard; the
 * unlock is handed straight to Android with
 * {@code KeyguardManager.requestDismissKeyguard}, so the person's PIN, pattern
 * or fingerprint is the one and only unlock. An app cannot replace the system
 * keyguard, and this deliberately does not try: the face is JawnOS, the lock is
 * still the phone's.
 */
public final class JawnLockScreen {

    private static final String TAG = "JawnLockScreen";
    /** Stored in the same shared preferences the dashboard's other switches use. */
    public static final String KEY = "lock_screen";

    private static Context app;
    private static BroadcastReceiver receiver;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private JawnLockScreen() { }

    public static boolean isEnabled(Context context) {
        return JawnLauncher.prefs(context).getBoolean(KEY, false);
    }

    /** Register or drop the listener to match the stored choice. */
    public static synchronized void apply(Context context) {
        Context c = context.getApplicationContext();
        if (isEnabled(c)) start(c);
        else release();
    }

    /** Drop the listener; the service calls this when its runtime ends. */
    public static synchronized void release() {
        if (receiver == null) return;
        try { app.unregisterReceiver(receiver); } catch (Throwable ignored) { }
        receiver = null;
        app = null;
    }

    private static synchronized void start(Context context) {
        if (receiver != null) return;
        app = context;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!Intent.ACTION_SCREEN_ON.equals(intent.getAction())) return;
                // The keyguard is already up by the time this lands; a short
                // beat avoids racing the screen-on transition, and the activity
                // itself re-checks the keyguard before it draws.
                UI.postDelayed(new Runnable() {
                    public void run() { showIfLocked(ctx); }
                }, 150);
            }
        };
        try {
            context.registerReceiver(receiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        } catch (Throwable t) {
            receiver = null;
            Log.w(TAG, "cannot listen for screen on", t);
        }
    }

    private static void showIfLocked(Context context) {
        if (context == null || !isEnabled(context)) return;
        try {
            KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null || !km.isKeyguardLocked()) return;
            Intent i = new Intent(context, JawnLockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            context.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "cannot open the lock screen", t);
        }
    }
}
