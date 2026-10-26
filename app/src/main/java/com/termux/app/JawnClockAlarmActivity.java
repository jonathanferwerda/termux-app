package com.termux.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.Date;

import com.termux.R;

/**
 * The full-screen face of a ringing Clock alarm.
 *
 * It wakes and shows over the lock screen, so the person never has to unlock
 * the phone to silence it. The gesture is the whole interface: the logo's
 * filled circle sits under the thumb, an empty ring waits at the top. A tap
 * snoozes; dragging the logo into the ring dismisses. Releasing anywhere else
 * animates the logo home.
 *
 * Dismiss and snooze end the same way the notification's actions do: the
 * sound stops, the notification comes down, and a request is written to
 * {@code ~/.president/clock_control.json} for the Perl alarm worker to apply
 * to the shared alarm. {@link JawnClockReceiver#ACTION_DISMISSED} is broadcast
 * locally so the manager page and the notification agree.
 */
public class JawnClockAlarmActivity extends Activity {
    /** How close the logo's centre must come to the ring's centre to drop. */
    private static final float DISMISS_RADIUS_DP = 90f;
    private static final int FADE_MS = 220;

    private FrameLayout root;
    private FrameLayout handle;
    private View target;
    private ImageView logo;
    private TextView labelView;
    private TextView timeView;
    private TextView appView;
    private TextView hintView;

    private int touchSlop;
    private float downRawX;
    private float downRawY;
    private boolean touching;
    private boolean closing;

    private String uuid = "";
    private String label = "";
    private String app = "";
    private long at;
    private int snoozeMinutes = 5;
    private static WeakReference<JawnClockAlarmActivity> current;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        lockScreenWindow();
        setContentView(R.layout.activity_jawn_clock_alarm);

        root = findViewById(R.id.alarm_root);
        handle = findViewById(R.id.alarm_handle);
        target = findViewById(R.id.alarm_target);
        logo = findViewById(R.id.alarm_logo);
        labelView = findViewById(R.id.alarm_label);
        timeView = findViewById(R.id.alarm_time);
        appView = findViewById(R.id.alarm_app);
        hintView = findViewById(R.id.alarm_hint);

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        handle.setOnTouchListener(this::onHandleTouch);
        handle.setOnClickListener(v -> {
            if (!closing) snooze();
        });
        readIntent(getIntent());
        current = new WeakReference<>(this);
    }

    /** Called by the receiver when a notification action (or another machine)
     *  ends the ring, so the full-screen face does not stay over the keyguard. */
    public static void closeIfShowing() {
        JawnClockAlarmActivity a = current == null ? null : current.get();
        if (a == null) return;
        a.runOnUiThread(a::closeSilently);
    }

    private void closeSilently() {
        if (closing) return;
        closing = true;
        JawnClockSound.stop();
        JawnClockNotifier.cancel(this);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            finish();
            overridePendingTransition(0, 0);
        }).start();
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
        // An alarm is a promise to be seen; keep the panel lit while it rings.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void readIntent(Intent intent) {
        if (intent != null) {
            uuid = text(intent.getStringExtra("uuid"));
            label = text(intent.getStringExtra("label"));
            app = text(intent.getStringExtra("app"));
            at = intent.getLongExtra("at", 0L);
            snoozeMinutes = intent.getIntExtra("snooze", 5);
        }
        if (snoozeMinutes <= 0) snoozeMinutes = 5;
        paint();
    }

    private void paint() {
        labelView.setText(label.isEmpty() ? getString(R.string.clock_alarm_default_label) : label);
        long when = at > 0L ? at : System.currentTimeMillis();
        timeView.setText(timeText(when));
        if (app.isEmpty()) {
            appView.setVisibility(View.GONE);
        } else {
            appView.setText(app.replace('_', ' '));
            appView.setVisibility(View.VISIBLE);
        }
        hintView.setText(R.string.clock_alarm_hint);
    }

    /**
     * The logo follows the finger. On release: a drag that reached the ring
     * dismisses, a press with no significant movement (short or long) snoozes,
     * and anything else springs the logo home.
     */
    private boolean onHandleTouch(View view, MotionEvent event) {
        if (closing) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                handle.animate().cancel();
                handle.setScaleX(1f);
                handle.setScaleY(1f);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!touching) return true;
                handle.setTranslationX(event.getRawX() - downRawX);
                handle.setTranslationY(event.getRawY() - downRawY);
                highlightTarget(isOverTarget());
                return true;
            case MotionEvent.ACTION_UP:
                touching = false;
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                float moved = (float) Math.hypot(dx, dy);
                highlightTarget(false);
                if (isOverTarget() && moved > touchSlop) {
                    dismiss();
                } else if (moved <= touchSlop) {
                    handle.performClick();
                } else {
                    resetHandle();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                highlightTarget(false);
                resetHandle();
                return true;
            default:
                return true;
        }
    }

    private void highlightTarget(boolean active) {
        target.setAlpha(active ? 1f : 0.8f);
        target.setScaleX(active ? 1.08f : 1f);
        target.setScaleY(active ? 1.08f : 1f);
    }

    /** Within the forgiving drop radius, or physically overlapping the ring. */
    private boolean isOverTarget() {
        int[] h = new int[2];
        int[] t = new int[2];
        handle.getLocationOnScreen(h);
        target.getLocationOnScreen(t);

        float handleX = h[0] + handle.getWidth() / 2f;
        float handleY = h[1] + handle.getHeight() / 2f;
        float targetX = t[0] + target.getWidth() / 2f;
        float targetY = t[1] + target.getHeight() / 2f;
        if (Math.hypot(handleX - targetX, handleY - targetY) <= dp(DISMISS_RADIUS_DP)) {
            return true;
        }

        RectF ring = new RectF(t[0], t[1], t[0] + target.getWidth(), t[1] + target.getHeight());
        RectF disc = new RectF(h[0], h[1], h[0] + handle.getWidth(), h[1] + handle.getHeight());
        return RectF.intersects(ring, disc);
    }

    private void resetHandle() {
        handle.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(260)
                .setInterpolator(new OvershootInterpolator(1.15f))
                .start();
    }

    /** Fly the logo into the ring, then close as a dismissal. */
    private void dismiss() {
        if (closing) return;
        closing = true;

        int[] h = new int[2];
        int[] t = new int[2];
        handle.getLocationOnScreen(h);
        target.getLocationOnScreen(t);
        float deltaX = (t[0] + target.getWidth() / 2f) - (h[0] + handle.getWidth() / 2f);
        float deltaY = (t[1] + target.getHeight() / 2f) - (h[1] + handle.getHeight() / 2f);

        target.animate().alpha(1f).scaleX(1.12f).scaleY(1.12f).setDuration(180).start();
        handle.animate()
                .translationX(handle.getTranslationX() + deltaX)
                .translationY(handle.getTranslationY() + deltaY)
                .scaleX(0.45f)
                .scaleY(0.45f)
                .alpha(0.15f)
                .setDuration(180)
                .withEndAction(() -> complete(true))
                .start();
    }

    /** A short pulse, then close as a snooze. */
    private void snooze() {
        if (closing) return;
        closing = true;
        handle.animate()
                .scaleX(1.06f)
                .scaleY(1.06f)
                .alpha(0.85f)
                .setDuration(140)
                .withEndAction(() -> complete(false))
                .start();
    }

    /**
     * End the ring exactly as the notification actions do, then fade away.
     * The Perl worker polls the control file and applies the shared alarm; the
     * phone also re-reads its own schedule so a snooze has a wake to land on.
     */
    private void complete(boolean dismissed) {
        Log.i("JawnClockAlarmActivity", (dismissed ? "dismiss" : "snooze")
                + " gesture for " + uuid + " (" + snoozeMinutes + " min)");
        JawnClockSound.stop();
        JawnClockNotifier.cancel(this);
        JawnClockNotifier.releaseWakeLock();
        if (!uuid.isEmpty()) {
            JawnClockReceiver.writeControl(this, uuid,
                    dismissed ? "dismiss" : "snooze", snoozeMinutes);
        }
        // Do not re-read the schedule: the Perl worker writes the snooze (or the
        // next alarm) and broadcasts ACTION_SYNC. Syncing the old file here would
        // arm the moment that just rang and start it again immediately.
        JawnClockAlarm.cancel(this);

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            finish();
            overridePendingTransition(0, 0);
        }).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTask: a second alarm reuses this screen, so put it back in its
        // starting state before showing the new alarm.
        closing = false;
        touching = false;
        handle.animate().cancel();
        handle.setTranslationX(0f);
        handle.setTranslationY(0f);
        handle.setScaleX(1f);
        handle.setScaleY(1f);
        handle.setAlpha(1f);
        highlightTarget(false);
        root.setAlpha(1f);
        readIntent(intent);
    }

    @Override
    protected void onDestroy() {
        if (current != null && current.get() == this) current = null;
        super.onDestroy();
    }

    /** Back is never a silent dismissal; it is the same gentle snooze. */
    @Override
    public void onBackPressed() {
        snooze();
    }

    private String timeText(long when) {
        try {
            return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(when));
        } catch (Exception e) {
            return "";
        }
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
