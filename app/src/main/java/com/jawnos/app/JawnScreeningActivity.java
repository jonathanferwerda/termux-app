package com.jawnos.app;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.view.WindowManager;

import java.lang.ref.WeakReference;

/**
 * The full-screen face of a screened call: the alarm's road, for a phone whose
 * screen is off or locked.
 *
 * It is the same panel the floating window draws ({@link JawnScreenFace}), with
 * no panel of its own beyond the window it lives in. Raised over the keyguard
 * and turning the screen on, so the person does not have to unlock the phone to
 * see who is being screened - and closing itself as soon as the call is gone,
 * because a stale face over a finished call is worse than no face.
 *
 * Reached only from the app's own notification and its in-call service, so it
 * is not exported.
 */
public class JawnScreeningActivity extends Activity {

    private static WeakReference<JawnScreeningActivity> current;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private JawnScreenFace.Panel panel;

    /** The call can end (or be taken back) while this face is up; it follows. */
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (JawnInCallService.state != Call.STATE_ACTIVE) {
                finish();
                return;
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        lockScreenWindow();
        panel = new JawnScreenFace.Panel(this);
        setContentView(panel.view());
        JawnScreenFace.attachActivity(panel, this);
        current = new WeakReference<>(this);
        ui.post(watch);
    }

    /** Over the keyguard on API 27+, with the pre-27 window flags otherwise. */
    private void lockScreenWindow() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Called by the face when the call is over or taken back. */
    static void closeIfShowing() {
        JawnScreeningActivity a = current == null ? null : current.get();
        if (a == null) return;
        a.runOnUiThread(a::finish);
        current = null;
    }

    @Override
    public void onBackPressed() {
        // Swiping the face away must not leave the poller and the notification
        // behind; the call itself is untouched.
        JawnScreenFace.hide(this);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(watch);
        JawnScreenFace.detachActivity(panel);
        panel = null;
        if (current != null && current.get() == this) current = null;
        super.onDestroy();
    }
}
