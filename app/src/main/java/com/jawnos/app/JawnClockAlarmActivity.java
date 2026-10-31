package com.jawnos.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.RectF;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.Date;
import java.util.Random;

import com.jawnos.R;

/**
 * The full-screen face of a ringing Clock alarm.
 *
 * It wakes and shows over the lock screen, so the person never has to unlock
 * the phone to silence it. The gesture is the whole interface: the logo's
 * filled circle sits under the thumb, an empty ring waits at the top. A tap
 * snoozes; dragging the logo into the ring dismisses. Releasing anywhere else
 * animates the logo home.
 *
 * An alarm can also ask for a little work before it lets go. With
 * {@code play=math} a sum is shown and must be answered; with {@code nfc=1}
 * the phone reads the tag assigned to the alarm (reader mode) and asks jawn to
 * accept it. Both gates hold the gestures: nothing snoozes or dismisses until
 * the puzzle is solved and the tag is accepted.
 *
 * Dismiss and snooze end the same way the notification's actions do: the
 * sound stops, the notification comes down, and a request is written to
 * {@code ~/.president/clock_control.json} for the Perl alarm worker to apply
 * to the shared alarm. {@link JawnClockReceiver#ACTION_DISMISSED} is broadcast
 * locally so the manager page and the notification agree.
 */
public class JawnClockAlarmActivity extends Activity implements NfcAdapter.ReaderCallback {
    private static final String LOG_TAG = "JawnClockAlarmActivity";
    /** How close the logo's centre must come to the ring's centre to drop. */
    private static final float DISMISS_RADIUS_DP = 90f;
    private static final int FADE_MS = 220;
    /** Give the reader a moment before re-arming it, so one tap is one read. */
    private static final long NFC_REARM_MS = 900L;
    private static final long NFC_RESUME_DELAY_MS = 400L;

    private FrameLayout root;
    private FrameLayout handle;
    private View target;
    private ImageView logo;
    private TextView labelView;
    private TextView timeView;
    private TextView appView;
    private TextView hintView;
    private LinearLayout mathPanel;
    private TextView mathProblemView;
    private EditText mathAnswerView;
    private TextView statusView;

    private int touchSlop;
    private float downRawX;
    private float downRawY;
    private boolean touching;
    private volatile boolean closing;

    private String uuid = "";
    private String label = "";
    private String app = "";
    private long at;
    private int snoozeMinutes = 5;
    private String play = "";
    private int nfc = 0;
    private String nfcAction = "snooze";

    private boolean mathRequired;
    private volatile boolean mathSolved;
    private int mathA;
    private int mathB;
    private int mathC;
    private boolean nfcRequired;
    private NfcAdapter nfcAdapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

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
        mathPanel = findViewById(R.id.alarm_math_panel);
        mathProblemView = findViewById(R.id.alarm_math_problem);
        mathAnswerView = findViewById(R.id.alarm_math_answer);
        statusView = findViewById(R.id.alarm_status);

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        handle.setOnTouchListener(this::onHandleTouch);
        handle.setOnClickListener(v -> {
            if (closing) return;
            if (blocked()) {
                promptRequirement();
                return;
            }
            snooze();
        });
        mathAnswerView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                onMathTyped(s == null ? "" : s.toString());
            }
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

    /** Called by the receiver when jawn refuses the scanned tag. */
    public static void showRejected() {
        JawnClockAlarmActivity a = current == null ? null : current.get();
        if (a == null) return;
        a.runOnUiThread(a::onRejected);
    }

    private void closeSilently() {
        if (closing) return;
        closing = true;
        disableNfcReader();
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
            play = text(intent.getStringExtra("play"));
            nfc = intent.getIntExtra("nfc", 0);
            nfcAction = text(intent.getStringExtra("nfc_action"));
        }
        if (snoozeMinutes <= 0) snoozeMinutes = 5;
        if (!"dismiss".equals(nfcAction)) nfcAction = "snooze";
        mathRequired = "math".equals(play);
        nfcRequired = nfc == 1;
        mathSolved = false;
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
        clearStatus();
        mathAnswerView.setText("");
        mathAnswerView.setEnabled(true);
        if (mathRequired) {
            newProblem();
            mathPanel.setVisibility(View.VISIBLE);
        } else {
            mathPanel.setVisibility(View.GONE);
        }
        paintHint();
    }

    /** The hint names every gate between the person and the end of the ring. */
    private void paintHint() {
        String hint;
        if (nfcRequired) {
            String scan = getString("dismiss".equals(nfcAction)
                    ? R.string.clock_alarm_nfc_hint_dismiss : R.string.clock_alarm_nfc_hint);
            if (mathRequired && !mathSolved) {
                hint = getString(R.string.clock_alarm_math_hint) + "\n" + scan;
            } else if (mathRequired) {
                hint = getString("dismiss".equals(nfcAction)
                        ? R.string.clock_alarm_math_correct_nfc_dismiss
                        : R.string.clock_alarm_math_correct_nfc_snooze);
            } else {
                hint = scan;
            }
        } else if (mathRequired) {
            hint = getString(mathSolved ? R.string.clock_alarm_math_correct
                    : R.string.clock_alarm_math_hint);
        } else {
            hint = getString(R.string.clock_alarm_hint);
        }
        hintView.setText(hint);
    }

    /** a × b − c, with 2 <= a,b <= 12 and 0 <= c <= min(12, a*b - 1), so the
     *  answer is always a positive whole number. */
    private void newProblem() {
        Random random = new Random();
        mathA = 2 + random.nextInt(11);
        mathB = 2 + random.nextInt(11);
        int product = mathA * mathB;
        int ceiling = Math.min(12, product - 1);
        mathC = ceiling <= 0 ? 0 : random.nextInt(ceiling + 1);
        mathProblemView.setText(getString(R.string.clock_alarm_math_prompt, mathA, mathB, mathC));
    }

    private void onMathTyped(String raw) {
        if (!mathRequired || mathSolved || closing) return;
        String typed = raw.trim();
        if (typed.isEmpty()) return;
        int value;
        try {
            value = Integer.parseInt(typed);
        } catch (NumberFormatException e) {
            return;
        }
        int answer = mathA * mathB - mathC;
        if (value == answer) {
            mathSolved = true;
            mathAnswerView.setEnabled(false);
            mathAnswerView.clearFocus();
            hideKeyboard();
            clearStatus();
            paintHint();
            Log.i(LOG_TAG, "math answered; gestures " + (nfcRequired ? "still wait on NFC" : "enabled"));
        } else if (typed.length() >= String.valueOf(answer).length()) {
            showStatus(R.string.clock_alarm_math_wrong);
        }
    }

    /** True while a puzzle or a tag still stands between the person and the end. */
    private boolean blocked() {
        return (mathRequired && !mathSolved) || nfcRequired;
    }

    /** An attempted gesture while blocked says what is still needed. */
    private void promptRequirement() {
        if (mathRequired && !mathSolved) {
            showStatus(R.string.clock_alarm_math_hint);
        } else if (nfcRequired) {
            showStatus("dismiss".equals(nfcAction)
                    ? R.string.clock_alarm_nfc_hint_dismiss : R.string.clock_alarm_nfc_hint);
        }
    }

    /**
     * The logo follows the finger. On release: a drag that reached the ring
     * dismisses, a press with no significant movement (short or long) snoozes,
     * and anything else springs the logo home. A gate in the way refuses.
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
                    if (blocked()) {
                        promptRequirement();
                        resetHandle();
                    } else {
                        dismiss();
                    }
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
        if (blocked()) {
            promptRequirement();
            resetHandle();
            return;
        }
        closing = true;
        disableNfcReader();

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
        if (blocked()) {
            promptRequirement();
            return;
        }
        closing = true;
        disableNfcReader();
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
        Log.i(LOG_TAG, (dismissed ? "dismiss" : "snooze")
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

    // ---- NFC gate ----

    private void onRejected() {
        if (closing) return;
        showStatus(R.string.clock_alarm_nfc_rejected);
        enableNfcReader();
    }

    private void enableNfcReader() {
        if (!nfcRequired || closing) return;
        if (nfcAdapter == null) nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null || !nfcAdapter.isEnabled()) {
            showStatus(R.string.clock_alarm_nfc_unavailable);
            return;
        }
        int flags = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V
                | NfcAdapter.FLAG_READER_NFC_BARCODE;
        Bundle opts = new Bundle();
        opts.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
        try {
            nfcAdapter.enableReaderMode(this, this, flags, opts);
        } catch (Exception e) {
            Log.w(LOG_TAG, "could not start NFC reader mode", e);
            showStatus(R.string.clock_alarm_nfc_unavailable);
        }
    }

    private void disableNfcReader() {
        if (nfcAdapter == null) return;
        try {
            nfcAdapter.disableReaderMode(this);
        } catch (Exception ignored) {}
    }

    /**
     * A tag was tapped. NDEF I/O blocks, so this runs on the reader thread.
     * The tag is never judged here: jawn checks that it is the one assigned to
     * this alarm. A good tag ends the ring with ACTION_DISMISSED; a bad one
     * comes back as ACTION_REJECT and the alarm keeps sounding.
     */
    @Override
    public void onTagDiscovered(Tag tag) {
        if (closing || !nfcRequired) return;
        if (mathRequired && !mathSolved) {
            ui.post(() -> {
                if (closing) return;
                showStatus(R.string.clock_alarm_nfc_first);
                enableNfcReader();
            });
            return;
        }
        ui.post(() -> {
            if (!closing) showStatus(R.string.clock_alarm_nfc_checking);
        });
        String serial = hexOf(tag.getId());
        String tagUuid = uuidFromTagJson(JawnNfcBridge.readTagJson(tag));
        if (uuid.isEmpty() || tagUuid.isEmpty()) {
            // Nothing jawn could judge: ask for the tag again rather than
            // leaving the ring with no way through.
            ui.postDelayed(() -> {
                if (closing) return;
                showStatus(R.string.clock_alarm_nfc_unreadable);
                enableNfcReader();
            }, NFC_REARM_MS);
            return;
        }
        Log.i(LOG_TAG, "tag " + serial + " (" + tagUuid + ") for " + uuid);
        JawnClockReceiver.writeControlForNfc(this, uuid, nfcAction, snoozeMinutes, tagUuid, serial);
        // Do not finish and do not silence anything: the Perl side decides.
        // A reject re-arms the reader; an acceptance broadcasts DISMISSED,
        // which the receiver turns into closeIfShowing().
    }

    /** The uuid out of a tag jawn wrote as JSON (MIME application/json or text). */
    private static String uuidFromTagJson(String json) {
        if (json == null || json.isEmpty()) return "";
        try {
            JSONArray records = new JSONObject(json).optJSONArray("records");
            if (records == null) return "";
            String found = "";
            for (int i = 0; i < records.length(); i++) {
                JSONObject record = records.optJSONObject(i);
                if (record == null) continue;
                String data = record.optString("data", "");
                if (data.isEmpty()) continue;
                byte[] raw;
                try {
                    raw = Base64.decode(data, Base64.DEFAULT);
                } catch (Exception e) {
                    continue;
                }
                String body = new String(raw, Charset.forName("UTF-8")).trim();
                if (body.isEmpty()) continue;
                try {
                    String uuid = text(new JSONObject(body).optString("uuid", ""));
                    if (!uuid.isEmpty()) found = uuid;
                } catch (Exception ignored) {
                    // not JSON: leave it; a second record may be
                }
            }
            return found;
        } catch (Exception e) {
            return "";
        }
    }

    private static String hexOf(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ---- lifecycle ----

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcRequired) ui.postDelayed(this::enableNfcReader, NFC_RESUME_DELAY_MS);
    }

    @Override
    protected void onPause() {
        disableNfcReader();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTask: a second alarm reuses this screen, so put it back in its
        // starting state before showing the new alarm.
        closing = false;
        touching = false;
        disableNfcReader();
        handle.animate().cancel();
        handle.setTranslationX(0f);
        handle.setTranslationY(0f);
        handle.setScaleX(1f);
        handle.setScaleY(1f);
        handle.setAlpha(1f);
        highlightTarget(false);
        root.setAlpha(1f);
        readIntent(intent);
        if (nfcRequired) ui.postDelayed(this::enableNfcReader, NFC_RESUME_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        disableNfcReader();
        if (current != null && current.get() == this) current = null;
        super.onDestroy();
    }

    /** Back is never a silent dismissal; it is the same gentle snooze. */
    @Override
    public void onBackPressed() {
        if (blocked()) {
            promptRequirement();
            return;
        }
        snooze();
    }

    private void showStatus(int res) {
        showStatus(getString(res));
    }

    private void showStatus(String message) {
        if (statusView == null) return;
        if (message == null || message.isEmpty()) {
            statusView.setText("");
            statusView.setVisibility(View.GONE);
        } else {
            statusView.setText(message);
            statusView.setVisibility(View.VISIBLE);
        }
    }

    private void clearStatus() {
        showStatus("");
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(mathAnswerView.getWindowToken(), 0);
        } catch (Exception ignored) {}
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
