package com.jawnos.app;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jawnos.R;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The face of a call the house is screening, as the person on the phone sees it.
 *
 * A screened call is answered by the phone and carried by the Bluetooth gateway
 * (JawnInCallService, JawnIvrAudio), so the caller's voice never reaches the
 * phone's speaker. Without something on screen the call is invisible: the phone
 * shows its ordinary in-call screen for a call nobody is holding. That is what
 * this is - the caller, what the gateway hears, and the three things a person can
 * do about it.
 *
 * Two hosts, one panel:
 *   - the screen is on: a floating window over whatever app is up, using the
 *     overlay permission the status dot already holds;
 *   - the screen is off or the phone is locked: a notification with a
 *     full-screen intent (and a direct start where Android permits one) opens
 *     {@link JawnScreeningActivity}, which raises itself over the keyguard.
 *
 * The transcript comes from GET /manager/sco/status, polled while the face is
 * up. Every part of that is optional: no gateway, no Vosk, no network is an
 * ordinary state, and the panel still shows the caller and its buttons - a
 * failed poll never reaches the call service.
 */
public final class JawnScreenFace {

    private static final String CHANNEL = "jawn_screening";
    private static final int NOTIFICATION_ID = 7501;
    private static final int CONTENT_REQUEST = 7502;
    private static final long POLL_MS = 1500L;

    private static final Handler ui = new Handler(Looper.getMainLooper());

    /** The caller this face was opened for; "" when it was opened later. */
    private static volatile String caller = "";
    /** The newest status body, parsed, or null before the first answer. */
    private static volatile JSONObject status;
    /** The answer to "Send to voicemail", shown under the transcript until the face goes. */
    private static volatile String voicemailNote = "";

    private static Thread worker;
    /** Each poller owns its own flag: a request already in flight when the
     *  face comes down must not keep running for the next face. */
    private static volatile boolean[] workerAlive;

    private static Panel overlayPanel;
    private static WindowManager overlayWindow;
    private static Panel activityPanel;

    private JawnScreenFace() {}

    private static final Runnable repaintAll = new Runnable() {
        @Override public void run() {
            if (overlayPanel != null) overlayPanel.repaint();
            if (activityPanel != null) activityPanel.repaint();
        }
    };

    // ---- the call service's side ----

    /**
     * Put the face up for a screened call. Where it lands depends on the phone,
     * not on the caller: a locked or sleeping screen has no useful place for a
     * floating window, so the full-screen activity takes it there instead.
     */
    public static void show(final Context context, final String number) {
        final Context app = context.getApplicationContext();
        final String who = number == null ? "" : number;
        ui.post(new Runnable() {
            @Override public void run() {
                caller = who.isEmpty() ? JawnPhoneState.freshNumber(120_000) : who;
                voicemailNote = "";
                if (screenHidden(app)) {
                    postNotification(app);
                    // The intent is the sanctioned road, but a restricted or
                    // OEM build can ignore it; asking directly costs nothing
                    // and the activity is singleTask, so both paths agree.
                    openActivity(app);
                } else if (!showOverlay(app)) {
                    // no overlay permission: the full-screen road still works
                    postNotification(app);
                    openActivity(app);
                }
                startPolling(app);
            }
        });
    }

    /** Take the face down: the call ended, or the person took it back. */
    public static void hide(final Context context) {
        final Context app = context.getApplicationContext();
        ui.post(new Runnable() {
            @Override public void run() {
                stopPolling();
                removeOverlay();
                cancelNotification(app);
                status = null;
                voicemailNote = "";
                JawnScreeningActivity.closeIfShowing();
            }
        });
    }

    static void attachActivity(Panel panel, Context context) {
        activityPanel = panel;
        if (caller == null || caller.isEmpty()) caller = JawnPhoneState.freshNumber(120_000);
        panel.repaint();
        startPolling(context.getApplicationContext());
    }

    static void detachActivity(Panel panel) {
        if (activityPanel == panel) activityPanel = null;
    }

    // ---- the floating panel ----

    private static boolean showOverlay(Context app) {
        try {
            if (overlayPanel != null) {
                overlayPanel.repaint();
                return true;
            }
            if (!Settings.canDrawOverlays(app)) return false;
            overlayWindow = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (overlayWindow == null) return false;
            Panel panel = new Panel(app);
            int screenW = app.getResources().getDisplayMetrics().widthPixels;
            int screenH = app.getResources().getDisplayMetrics().heightPixels;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    Math.min(dp(app, 520), (int) (screenW * 0.92f)),
                    Math.min(dp(app, 460), (int) (screenH * 0.62f)),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // NOT_FOCUSABLE keeps the panel out of the keyboard's and
                    // the back key's way; the buttons in it still take a touch.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(app, 48);
            overlayWindow.addView(panel.view(), lp);
            overlayPanel = panel;
            panel.repaint();
            return true;
        } catch (Throwable t) {
            JawnLog.append("screening: no floating panel: " + t);
            return false;
        }
    }

    private static void removeOverlay() {
        try {
            if (overlayPanel != null && overlayWindow != null) {
                overlayWindow.removeView(overlayPanel.view());
            }
        } catch (Throwable ignored) { }
        overlayPanel = null;
        overlayWindow = null;
    }

    // ---- the lock-screen road ----

    /**
     * The alarm's pattern: a high-importance notification carrying a
     * full-screen intent. It has no sound of its own - the call is the sound -
     * and it stays up until the call is over, because swiping it away would
     * hide the only way to end the call from the lock screen.
     */
    private static void postNotification(Context app) {
        NotificationManager nm =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Screened calls", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("A call JawnOS is answering for the house");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setSound(null, null);
            channel.enableVibration(false);
            nm.createNotificationChannel(channel);

            Intent open = new Intent(app, JawnScreeningActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent content = PendingIntent.getActivity(
                    app, CONTENT_REQUEST, open, flags());

            String who = caller == null || caller.isEmpty() ? "A screened call" : "Call from: " + caller;
            Notification.Builder builder = new Notification.Builder(app, CHANNEL);
            builder.setSmallIcon(R.drawable.ic_new_session)
                    .setContentTitle(who)
                    .setContentText("JawnOS is screening this call")
                    .setCategory(Notification.CATEGORY_CALL)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setContentIntent(content)
                    .setFullScreenIntent(content, true)
                    .setColor(0xFF2F8F6D);
            // Android 14 made USE_FULL_SCREEN_INTENT a special access; a
            // sideloaded build can be left denied. The face is then asked for
            // directly below, which is the same fallback the alarm uses.
            if (!JawnClockNotifier.fullScreenAllowed(app)) {
                JawnLog.append("screening: full-screen intent not allowed; asking directly");
            }
            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Throwable t) {
            JawnLog.append("screening: could not post the notice: " + t);
        }
    }

    private static void openActivity(Context app) {
        try {
            Intent i = new Intent(app, JawnScreeningActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            app.startActivity(i);
        } catch (Throwable t) {
            JawnLog.append("screening: could not open the face: " + t);
        }
    }

    private static void cancelNotification(Context app) {
        try {
            NotificationManager nm =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
        } catch (Throwable ignored) { }
    }

    private static boolean screenHidden(Context app) {
        try {
            KeyguardManager km =
                    (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            boolean locked = km != null && km.isKeyguardLocked();
            boolean awake = pm == null || pm.isInteractive();
            return locked || !awake;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- polling the gateway ----

    /**
     * One worker for the life of the face. The status request blocks on the
     * house for a moment, so it never runs on the main thread; the parsing is
     * done here too, and the main thread only paints.
     */
    private static void startPolling(final Context app) {
        if (worker != null) return;
        final boolean[] alive = { true };
        workerAlive = alive;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                boolean reported = false;
                while (alive[0]) {
                    String raw = JawnPhoneBridge.scoStatus(app);
                    if (!alive[0]) return;
                    if (raw != null) {
                        try {
                            status = new JSONObject(raw);
                            reported = false;
                            ui.post(repaintAll);
                        } catch (Throwable ignored) {
                            // a body we cannot read is the same as no body
                        }
                    } else if (!reported) {
                        // once per outage, not once per poll
                        reported = true;
                        JawnLog.append("screening: the gateway status could not be read");
                    }
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException stop) {
                        return;
                    }
                }
            }
        }, "jawn-screening");
        worker.start();
    }

    private static void stopPolling() {
        boolean[] alive = workerAlive;
        if (alive != null) alive[0] = false;
        workerAlive = null;
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
    }

    // ---- taking the call back ----

    /**
     * Send this caller to the house's voicemail without moving the call.
     *
     * The phone carrying a screened call must not connect the caller to itself,
     * so the panel's way out is the house's voicemail: the gateway plays the
     * greeting and records the message on the line it already owns. This is the
     * same POST the in-call screen's Voicemail button makes, and the same rule:
     * only a screened call the gateway is carrying has one. The answer is
     * painted on the panel; nothing here hangs up.
     */
    private static void sendToVoicemail(final Context context) {
        final Context app = context.getApplicationContext();
        JawnInCallService.Audio now = JawnInCallService.audioState(app);
        if (!now.gatewayCarried) {
            voicemailNote = now.screened
                    ? "Voicemail is unavailable: the call is not on the house gateway."
                    : "Voicemail is unavailable: this call is not being screened by the house.";
            ui.post(repaintAll);
            return;
        }
        voicemailNote = "sending the caller to the house voicemail...";
        ui.post(repaintAll);
        new Thread(new Runnable() {
            @Override public void run() {
                JawnInCallService.VoicemailResult result = JawnInCallService.voicemailResult(
                        JawnPhoneBridge.scoVoicemail(app, null));
                // the gateway stops its music before the greeting, so the call
                // screen must not keep painting "Hold: on"
                if (result.accepted) JawnInCallService.holdOn = false;
                String painted = result.note;
                voicemailNote = painted;
                JawnLog.append("screening: " + painted);
                ui.post(repaintAll);
            }
        }, "jawn-screen-voicemail").start();
    }

    private static void connectPhone(Context context) {
        // The phone's own audio first, so the person hears the caller at once;
        // the gateway is then told to stop playing. The call itself never moves.
        // The person then lands on the in-call screen: that is where the rest of
        // the controls (speaker, mute, Bluetooth, hang up) now live.
        JawnInCallService.connectPhone(context);
        JawnInCallService.openScreen(context);
        hide(context);
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    private static int flags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }

    // ---- the panel itself ----

    /**
     * The one layout both hosts draw: the caller, the transcript as bubbles,
     * and the two buttons. The host sizes it - a fixed card in a floating
     * window, the whole screen in the activity - so the transcript takes
     * whatever is left of the height.
     */
    public static final class Panel {

        private final Context context;
        private final LinearLayout root;
        private final TextView callerView;
        private final TextView noteView;
        private final LinearLayout bubbles;
        private final ScrollView scroll;
        private final Button voicemail;
        private String painted = null;

        public Panel(Context context) {
            this.context = context;
            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(18), dp(16), dp(18), dp(16));
            root.setBackground(card(0xF20D1117, 0xFF243243));

            TextView label = new TextView(context);
            label.setText("SCREENING");
            label.setTextColor(Color.parseColor("#4a90d9"));
            label.setTextSize(11);
            label.setLetterSpacing(0.15f);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            root.addView(label);

            callerView = new TextView(context);
            callerView.setTextColor(Color.WHITE);
            callerView.setTextSize(22);
            callerView.setTypeface(Typeface.DEFAULT_BOLD);
            callerView.setPadding(0, dp(2), 0, dp(10));
            root.addView(callerView);

            scroll = new ScrollView(context);
            scroll.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            scroll.setFillViewport(true);
            bubbles = new LinearLayout(context);
            bubbles.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(bubbles, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT));
            root.addView(scroll);

            noteView = new TextView(context);
            noteView.setTextColor(Color.parseColor("#8fa3b8"));
            noteView.setTextSize(12);
            noteView.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
            noteView.setPadding(0, dp(6), 0, 0);
            noteView.setVisibility(View.GONE);
            root.addView(noteView);

            // The phone carrying a screened call must not connect the caller to
            // itself: the house's voicemail is the way out that leaves the line
            // where it is. Same request as the in-call screen's Voicemail
            // button - the gateway plays the greeting and records the message.
            voicemail = pill("Send to voicemail", "#8a5b16");
            LinearLayout.LayoutParams vmlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            vmlp.topMargin = dp(12);
            voicemail.setLayoutParams(vmlp);
            voicemail.setOnClickListener(v -> sendToVoicemail(context));
            root.addView(voicemail);

            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(6);
            buttons.setLayoutParams(blp);

            Button connect = pill("Connect phone", "#1d7a3e");
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            clp.rightMargin = dp(6);
            connect.setLayoutParams(clp);
            connect.setOnClickListener(v -> connectPhone(context));

            Button hangup = pill("Disconnect", "#b0342e");
            hangup.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            hangup.setOnClickListener(v -> {
                JawnInCallService.hangup();
                hide(context);
            });

            buttons.addView(connect);
            buttons.addView(hangup);
            root.addView(buttons);
        }

        public View view() {
            return root;
        }

        /**
         * Paint from the newest status. The transcript is rebuilt only when the
         * words changed, so a poll that changed nothing costs one string
         * compare and no views.
         */
        void repaint() {
            String who = caller == null ? "" : caller;
            if (who.isEmpty()) who = JawnPhoneState.freshNumber(120_000);

            JSONObject st = status;
            JSONObject sc = st == null ? null : st.optJSONObject("caller");
            String scName = sc == null ? "" : sc.optString("name", "");
            String scNum = sc == null ? "" : sc.optString("number", "");
            String label = !scName.isEmpty() ? scName : (!scNum.isEmpty() ? scNum : who);
            callerView.setText(label.isEmpty() ? "A screened call" : "Call from: " + label);
            JSONArray list = st == null ? null : st.optJSONArray("transcript");
            String partial = st == null ? "" : st.optString("partial", "");
            String engine = st == null ? "" : st.optString("transcript_engine", "");
            String note = st == null ? "" : st.optString("transcript_note", "");

            int count = list == null ? 0 : list.length();
            String last = "";
            if (count > 0) {
                JSONObject row = list.optJSONObject(count - 1);
                last = row == null ? "" : row.optString("text", "");
            }
            String signature = count + "|" + last + "|" + partial;
            if (!signature.equals(painted)) {
                painted = signature;
                bubbles.removeAllViews();
                for (int i = 0; i < count; i++) {
                    JSONObject row = list.optJSONObject(i);
                    if (row == null) continue;
                    String text = row.optString("text", "");
                    if (text.isEmpty()) continue;
                    bubbles.addView(bubble(text, "caller".equals(row.optString("who", "")), false));
                }
                // the sentence still being said: dimmed, so nobody reads it
                // as something the caller already finished
                if (!partial.isEmpty()) bubbles.addView(bubble(partial, true, true));
                if (count > 0 || !partial.isEmpty()) {
                    scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                }
            }

            // The voicemail answer outranks the transcript hint: it is the
            // reply to something the person just pressed. The button follows
            // the call: only a screened call the gateway still carries has a
            // voicemail to send it to.
            boolean canVoicemail = JawnInCallService.audioState(context).voicemailOffered;
            voicemail.setEnabled(canVoicemail);
            voicemail.setAlpha(canVoicemail ? 1f : 0.45f);

            // No engine is the one case worth explaining: the note says why
            // there is nothing to read. With an engine and no words yet, the
            // face simply waits.
            String hint = voicemailNote;
            if (hint.isEmpty()) {
                if (engine.isEmpty()) hint = note;
                else if (count == 0 && partial.isEmpty()) hint = "listening...";
            }
            noteView.setText(hint);
            noteView.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
        }

        private TextView bubble(String text, boolean callerSide, boolean dim) {
            TextView t = new TextView(context);
            t.setText(text);
            t.setTextSize(15);
            t.setTextColor(dim ? 0xFF9FB0C2 : 0xFFF2F7F4);
            if (dim) t.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(dim ? 0x991B2530 : (callerSide ? 0xFF1B2530 : 0xFF1D4F8A));
            bg.setCornerRadius(dp(14));
            t.setBackground(bg);
            t.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = callerSide ? Gravity.START : Gravity.END;
            lp.topMargin = dp(4);
            if (callerSide) lp.rightMargin = dp(36);
            else lp.leftMargin = dp(36);
            t.setLayoutParams(lp);
            return t;
        }

        private Button pill(String text, String fill) {
            Button b = new Button(context);
            b.setText(text);
            b.setTextColor(Color.WHITE);
            b.setTextSize(15);
            b.setAllCaps(false);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor(fill));
            bg.setCornerRadius(dp(24));
            b.setBackground(bg);
            b.setPadding(dp(10), dp(10), dp(10), dp(10));
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            return b;
        }

        private GradientDrawable card(int fill, int stroke) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(fill);
            d.setCornerRadius(dp(18));
            d.setStroke(dp(1), stroke);
            return d;
        }

        private int dp(int value) {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    context.getResources().getDisplayMetrics());
        }
    }
}
