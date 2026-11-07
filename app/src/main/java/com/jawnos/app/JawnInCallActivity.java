package com.jawnos.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.jawnos.R;

/**
 * The in-call screen: who is calling, and every control the call has.
 *
 * The house's own mark sits at the top, and the caller wears a round picture: the
 * person's own image when the house sent one (JawnPhoneBridge keeps the pictures
 * synced from /manager/phone/sync), the app's logo when it did not - and the
 * house's name for the number above the raw number either way.
 *
 * A ringing call gets the two big buttons. A call that is up gets the controls:
 * speaker, mute, Bluetooth, conference (only where Telecom allows it), the
 * gateway's WebRTC bridge, its hold music, its voicemail, "Connect phone" -
 * the toggle between this phone and the house gateway for a screened call -
 * and hang up. Every control reports what it did in one note line, so a
 * refusal from Telecom is visible instead of a button that quietly fails.
 */
public class JawnInCallActivity extends Activity {

    private final Handler handler = new Handler();
    private ImageView avatar;
    private TextView name;
    private TextView number;
    private TextView status;
    private TextView route;
    private TextView note;
    private Button answer;
    private Button decline;
    private Button hangUp;
    private Button speaker;
    private Button mute;
    private Button bluetooth;
    private Button conference;
    private Button rtc;
    private Button hold;
    private Button voicemail;
    private Button connect;
    private LinearLayout controls;
    private String paintedFor = null;
    private boolean ringingNow = false;
    private boolean firstPaint = true;
    /** The screen-at-the-ear half: one sensor, two wake locks at most. */
    private SensorManager sensors;
    private Sensor proximity;
    private PowerManager.WakeLock callLock;
    private PowerManager.WakeLock earLock;
    private boolean sensorOn;
    private boolean near;
    private boolean callUp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Android 15+ draws edge-to-edge whatever the app asks for, and the
        // control people reach for last is Hang up. Opt in everywhere and pad
        // the column by the bars and the cutout, so that button sits above a
        // gesture bar and above 3-button navigation instead of under them.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.parseColor("#0d1117"));
        getWindow().setNavigationBarColor(Color.parseColor("#0d1117"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(20));
        root.setBackgroundColor(Color.parseColor("#0d1117"));

        TextView house = new TextView(this);
        house.setText("JawnOS");
        house.setTextColor(Color.parseColor("#4a90d9"));
        house.setTextSize(15);
        house.setLetterSpacing(0.15f);
        house.setGravity(Gravity.CENTER);

        // the caller's half: everything below the mark, centred in what is left,
        // and scrollable so a short screen still reaches the last control
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setGravity(Gravity.CENTER);
        middle.setLayoutParams(new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(dp(180), dp(180)));

        name = new TextView(this);
        name.setTextColor(Color.WHITE);
        name.setTextSize(30);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setGravity(Gravity.CENTER);
        name.setVisibility(View.GONE);

        number = new TextView(this);
        number.setTextColor(Color.parseColor("#8fa3b8"));
        number.setTextSize(18);
        number.setGravity(Gravity.CENTER);
        number.setPadding(0, dp(6), 0, 0);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#8fa3b8"));
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(10), 0, 0);

        route = new TextView(this);
        route.setTextColor(Color.parseColor("#6f849c"));
        route.setTextSize(13);
        route.setGravity(Gravity.CENTER);
        route.setPadding(0, dp(4), 0, dp(8));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        speaker = cell("Speaker: off", "#243b5e");
        speaker.setOnClickListener(v -> JawnInCallService.toggleSpeaker(this));

        mute = cell("Mute", "#243b5e");
        mute.setOnClickListener(v -> JawnInCallService.toggleMute(this));

        bluetooth = cell("Bluetooth", "#243b5e");
        bluetooth.setOnClickListener(v -> JawnInCallService.toggleBluetooth(this));

        conference = cell("Conference", "#243b5e");
        conference.setOnClickListener(v -> JawnInCallService.joinConference(this));

        rtc = cell("WebRTC: off", "#2f6db0");
        rtc.setOnClickListener(v -> JawnInCallService.toggleRtc(this));

        hold = cell("Hold", "#243b5e");
        hold.setOnClickListener(v -> JawnInCallService.toggleHold(this));

        voicemail = cell("Voicemail", "#243b5e");
        voicemail.setOnClickListener(v -> JawnInCallService.sendToVoicemail(this));

        connect = cell("Connect phone", "#1d7a3e");
        connect.setOnClickListener(v -> JawnInCallService.connectPhone(this));
        // the way off the gateway gets a row of its own, full width
        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        wide.setMargins(dp(4), dp(4), dp(4), dp(4));
        connect.setLayoutParams(wide);

        controls.addView(row(speaker, mute));
        controls.addView(row(bluetooth, conference));
        controls.addView(row(rtc, hold));
        controls.addView(row(voicemail));
        controls.addView(connect);

        note = new TextView(this);
        note.setTextColor(Color.parseColor("#8fa3b8"));
        note.setTextSize(12);
        note.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(4), dp(6), dp(4), 0);
        note.setMaxLines(3);
        note.setMinHeight(dp(34));

        middle.addView(avatar);
        middle.addView(name);
        middle.addView(number);
        middle.addView(status);
        middle.addView(route);
        middle.addView(controls);
        middle.addView(note);
        scroll.addView(middle);

        answer = pill("Answer", "#1d7a3e", dp(230), dp(56));
        answer.setTextSize(17);
        answer.setOnClickListener(v -> JawnInCallService.answer());

        decline = pill("Decline", "#b0342e", dp(230), dp(56));
        decline.setTextSize(17);
        decline.setOnClickListener(v -> JawnInCallService.reject());

        hangUp = pill("Hang up", "#b0342e", dp(0), dp(52));
        hangUp.setTextSize(17);
        hangUp.setOnClickListener(v -> JawnInCallService.hangup());

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        bottom.addView(answer);
        bottom.addView(decline);
        bottom.addView(hangUp);

        root.addView(house);
        root.addView(scroll);
        root.addView(bottom);
        setContentView(root);
        // the bars and the cutout live outside the content on every current
        // Android; the padding keeps the house's mark and Hang up inside them
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(dp(24) + bars.left, dp(32) + bars.top,
                    dp(24) + bars.right, dp(20) + bars.bottom);
            return insets;
        });
        startProximity();
        handler.post(tick);
    }

    /** A rounded button: the shape both the big buttons and the control grid wear. */
    private Button pill(String text, String colour, int width, int height) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(colour));
        bg.setCornerRadius(dp(30));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.topMargin = dp(10);
        if (width == 0) lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
        b.setLayoutParams(lp);
        return b;
    }

    /** One cell of the control grid: half the width, and dark by default. */
    private Button cell(String text, String colour) {
        Button b = pill(text, colour, 0, dp(48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        b.setTextSize(14);
        return b;
    }

    private LinearLayout row(Button left, Button right) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        r.addView(left);
        if (right != null) r.addView(right);
        return r;
    }

    /** A grid row with one cell: a gateway action the width of the grid. */
    private LinearLayout row(Button only) {
        return row(only, null);
    }

    /** Paint the button as on or off without changing its shape. */
    private void tint(Button b, String colour, boolean enabled) {
        String key = colour + (enabled ? "1" : "0");
        if (key.equals(b.getTag())) return; // the tick is every 400 ms
        b.setTag(key);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(colour));
        bg.setCornerRadius(dp(24));
        b.setBackground(bg);
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.45f);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    // ---- the screen at the ear ----

    /**
     * The screen at the ear, the way the stock dialer does it.
     *
     * A proximity sensor drives two wake locks: the call lock (partial) keeps
     * this process and the call bookkeeping running while the display is dark,
     * and the ear lock is a PROXIMITY_SCREEN_OFF wake lock - the platform
     * switch that turns the display off while the sensor reads near and back on
     * when it reads far. KEEP_SCREEN_ON is dropped for the same window, or it
     * would hold the display against the ear lock.
     *
     * Only a call that is up arms this. A ringing call keeps its screen, and a
     * screen the person turned off stays theirs.
     */
    private void startProximity() {
        try {
            sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            proximity = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                callLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jawnos:call-up");
                callLock.setReferenceCounted(false);
                // the same lock AOSP dialer holds; WAKE_LOCK is already in the
                // manifest, so this asks for no new grant
                earLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                        "jawnos:call-ear");
                earLock.setReferenceCounted(false);
            }
        } catch (Throwable t) {
            JawnLog.append("call screen: no proximity control: " + t);
        }
        if (proximity == null) {
            JawnLog.append("call screen: no proximity sensor on this phone");
        }
    }

    private final SensorEventListener ear = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            float value = event.values == null || event.values.length == 0 ? -1f : event.values[0];
            boolean now = value >= 0f && proximity != null
                    && value < proximity.getMaximumRange();
            if (now == near) return;
            near = now;
            applyEar();
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    /** Arm the ear while a call is up; disarm it the moment the call is not. */
    private void proximityFor(boolean up) {
        if (up != callUp) {
            callUp = up;
            if (up) openProximity();
            else closeProximity();
        }
        applyEar();
    }

    private void openProximity() {
        near = false;
        if (sensors == null || proximity == null || sensorOn) return;
        try {
            sensorOn = sensors.registerListener(ear, proximity, SensorManager.SENSOR_DELAY_UI);
        } catch (Throwable t) {
            sensorOn = false;
            JawnLog.append("call screen: the proximity sensor would not open: " + t);
        }
    }

    private void closeProximity() {
        near = false;
        if (sensors != null && sensorOn) {
            try { sensors.unregisterListener(ear); } catch (Throwable ignored) { }
        }
        sensorOn = false;
    }

    /**
     * Hold the ear lock only while the phone is at the face of an up call, and
     * let go on the way back. Releasing the lock is what brings the display
     * back; the partial lock is released with it.
     */
    private void applyEar() {
        if (callUp && near) {
            if (callLock != null && !callLock.isHeld()) {
                try { callLock.acquire(); } catch (Throwable ignored) { }
            }
            if (earLock != null && !earLock.isHeld()) {
                try {
                    // the flag would hold the display on against the lock
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    earLock.acquire();
                    JawnLog.append("call screen: display off at the ear");
                } catch (Throwable t) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    JawnLog.append("call screen: could not darken the display: " + t);
                }
            }
            return;
        }
        // far from the face, or no call: the display is the person again
        if (earLock != null && earLock.isHeld()) {
            try { earLock.release(); } catch (Throwable ignored) { }
            try {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Throwable ignored) { }
            JawnLog.append("call screen: display back on");
        }
        if (callLock != null && callLock.isHeld()) {
            try { callLock.release(); } catch (Throwable ignored) { }
        }
    }

    /** Nothing of this screen outlives it: locks released, sensor closed. */
    private void stopProximity() {
        callUp = false;
        near = false;
        closeProximity();
        applyEar();
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) { }
    }

    /** Repaint the round picture once per caller, not once per tick. */
    private void paintAvatar(String digits) {
        if (paintedFor != null && paintedFor.equals(digits)) return;
        paintedFor = digits;
        Bitmap source = null;
        String data = JawnPhoneBridge.avatarFor(this, digits);
        if (data != null && data.startsWith("data:image")) source = decodeData(data);
        if (source == null) source = BitmapFactory.decodeResource(getResources(), R.drawable.jawn_logo);
        avatar.setImageBitmap(circle(source));
    }

    private Bitmap decodeData(String data) {
        try {
            int comma = data.indexOf(',');
            if (comma < 0) return null;
            byte[] bytes = Base64.decode(data.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable t) { return null; }
    }

    /** Centre-cropped to a square, then drawn through a circular shader. */
    private Bitmap circle(Bitmap source) {
        if (source == null) return null;
        try {
            int size = Math.min(source.getWidth(), source.getHeight());
            if (size <= 0) return source;
            Bitmap square = Bitmap.createBitmap(source,
                    (source.getWidth() - size) / 2, (source.getHeight() - size) / 2, size, size);
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            BitmapShader shader = new BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix matrix = new Matrix();
            float scale = (float) size / (float) square.getWidth();
            matrix.setScale(scale, scale);
            shader.setLocalMatrix(matrix);
            paint.setShader(shader);
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
            return out;
        } catch (Throwable t) { return source; }
    }

    /** The picture shrinks once the call is up, so the controls fit under it. */
    private void sizeAvatar(boolean ringing) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(ringing ? 180 : 120), dp(ringing ? 180 : 120));
        lp.topMargin = dp(ringing ? 0 : 6);
        lp.bottomMargin = dp(ringing ? 20 : 6);
        avatar.setLayoutParams(lp);
    }

    private String audioRoute(JawnInCallService.Audio a) {
        if (a.bluetooth) {
            String who = a.bluetoothName.isEmpty() ? "Bluetooth" : a.bluetoothName;
            return "Audio: " + who + (JawnInCallService.onGateway() ? " (the house gateway)" : "");
        }
        if (a.speaker) return "Audio: speaker";
        if (a.route == CallAudioState.ROUTE_WIRED_HEADSET) return "Audio: headset";
        return "Audio: this phone";
    }

    /** The one line under the grid: what just happened, or why a control is off. */
    private String noteFor(JawnInCallService.Audio a) {
        long age = System.currentTimeMillis() - JawnInCallService.controlNoteAt;
        if (!JawnInCallService.controlNote.isEmpty() && age < 20_000) {
            return JawnInCallService.controlNote;
        }
        // which side of the gateway toggle the call is on: the same line is
        // the reason Hold and Voicemail are off while the call is home
        if (a.screened) return a.connectWhy;
        if (!a.conference) return a.conferenceWhy;
        if (!a.bluetoothOffered) return "Bluetooth is unavailable: no Bluetooth device is connected.";
        if (!a.holdOffered) return a.holdWhy;
        return "";
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            int s = JawnInCallService.state;
            // the ear lock is armed only while a call is really up - not while
            // it rings, not once it has ended - and applyEar releases it the
            // moment this turns false
            proximityFor(s == Call.STATE_ACTIVE || s == Call.STATE_DIALING
                    || s == Call.STATE_CONNECTING);
            if (s == Call.STATE_DISCONNECTED) {
                // no call: the screen has done its job
                finish();
                return;
            }
            String digits = JawnPhoneState.freshNumber(60_000);
            String who = JawnPhoneBridge.nameFor(JawnInCallActivity.this, digits);
            if (who != null && !who.isEmpty()) {
                name.setText(who);
                name.setVisibility(View.VISIBLE);
            }
            else {
                name.setVisibility(View.GONE);
            }
            number.setText(digits);
            paintAvatar(digits);

            boolean ringing = s == Call.STATE_RINGING;
            if (firstPaint || ringing != ringingNow) {
                firstPaint = false;
                ringingNow = ringing;
                sizeAvatar(ringing);
                answer.setVisibility(ringing ? View.VISIBLE : View.GONE);
                decline.setVisibility(ringing ? View.VISIBLE : View.GONE);
                hangUp.setVisibility(ringing ? View.GONE : View.VISIBLE);
                controls.setVisibility(ringing ? View.GONE : View.VISIBLE);
            }

            if (ringing) {
                status.setText("ringing");
                route.setText("");
                note.setText("");
                handler.postDelayed(this, 400);
                return;
            }

            if (s == Call.STATE_DIALING) status.setText("dialing");
            else if (s == Call.STATE_CONNECTING) status.setText("connecting");
            else if (s == Call.STATE_HOLDING) status.setText("on hold");
            else if (JawnInCallService.onGateway()) status.setText("screening - the house is answering");
            else status.setText("on the call");

            JawnInCallService.Audio a = JawnInCallService.audioState(JawnInCallActivity.this);
            route.setText(audioRoute(a));

            speaker.setText(a.speaker ? "Speaker: on" : "Speaker: off");
            tint(speaker, a.speaker ? "#1d7a3e" : "#243b5e", true);

            mute.setText(a.muted ? "Muted" : "Mute");
            tint(mute, a.muted ? "#8a5b16" : "#243b5e", true);

            bluetooth.setText(a.bluetooth ? "Bluetooth: on" : "Bluetooth");
            tint(bluetooth, a.bluetooth ? "#1d7a3e" : "#243b5e", a.bluetoothOffered);

            tint(conference, "#243b5e", a.conference);

            rtc.setText(JawnInCallService.rtcOn ? "WebRTC: on" : "WebRTC: off");
            tint(rtc, JawnInCallService.rtcOn ? "#1d7a3e" : "#2f6db0", true);

            hold.setText(a.hold ? "Hold: on" : "Hold");
            tint(hold, a.hold ? "#1d7a3e" : "#243b5e", a.holdOffered || a.hold);

            // voicemail is the same screened, gateway-carried case as
            // hold: the gateway owns the line and does the recording
            tint(voicemail, "#243b5e", a.gatewayCarried);

            // One control, two directions: on the gateway it brings the call
            // home, and once home it hands the call back, so it is never a
            // dead end. Only a call the house screened has a gateway at all.
            connect.setVisibility(a.screened ? View.VISIBLE : View.GONE);
            connect.setText(a.gatewayCarried ? "Connect phone" : "Send to gateway");
            tint(connect, "#1d7a3e", true);

            note.setText(noteFor(a));
            handler.postDelayed(this, 400);
        }
    };

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        stopProximity();
        super.onDestroy();
    }
}
