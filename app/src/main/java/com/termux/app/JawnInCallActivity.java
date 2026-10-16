package com.termux.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.telecom.Call;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The in-call screen: who is calling, and answer / decline / hang up. It is shown
 * by JawnInCallService (the dialer role's InCallService), wakes over the lock
 * screen (FLAG_SHOW_WHEN_LOCKED + TURN_SCREEN_ON), and closes itself once the
 * call is gone. The caller wears the house's own name for the number when the
 * house knows one - JawnPhoneBridge keeps the numbers by name synced from
 * /manager/phone/sync - and the raw number otherwise.
 */
public class JawnInCallActivity extends Activity {

    private final Handler handler = new Handler();
    private TextView name;
    private TextView number;
    private TextView status;
    private Button answer;
    private Button decline;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(Color.parseColor("#0d1117"));

        TextView house = new TextView(this);
        house.setText("JawnOS");
        house.setTextColor(Color.parseColor("#4a90d9"));
        house.setTextSize(14);
        house.setGravity(Gravity.CENTER);
        house.setPadding(0, 0, 0, dp(24));

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
        status.setPadding(0, dp(16), 0, dp(20));

        answer = pill("Answer", "#1d7a3e");
        answer.setOnClickListener(v -> JawnInCallService.answer());

        decline = pill("Decline", "#b0342e");
        decline.setOnClickListener(v -> JawnInCallService.reject());

        root.addView(house);
        root.addView(name);
        root.addView(number);
        root.addView(status);
        root.addView(answer);
        root.addView(decline);
        setContentView(root);
        handler.post(tick);
    }

    /** A rounded button, sized for a thumb: the only two controls on the screen. */
    private Button pill(String text, String colour) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(17);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(colour));
        bg.setCornerRadius(dp(30));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(230), dp(56));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            int s = JawnInCallService.state;
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
            if (s == Call.STATE_RINGING) {
                status.setText("ringing");
                answer.setVisibility(View.VISIBLE);
                answer.setText("Answer");
                decline.setVisibility(View.VISIBLE);
                decline.setText("Decline");
            }
            else if (s == Call.STATE_ACTIVE || s == Call.STATE_DIALING) {
                status.setText(s == Call.STATE_ACTIVE ? "on the call" : "dialing");
                answer.setVisibility(View.VISIBLE);
                answer.setText("Hang up");
                answer.setOnClickListener(v -> JawnInCallService.hangup());
                decline.setVisibility(View.GONE);
            }
            else {
                // no call: the screen has done its job
                finish();
                return;
            }
            handler.postDelayed(this, 400);
        }
    };

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }
}
