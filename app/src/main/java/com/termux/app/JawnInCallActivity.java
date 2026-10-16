package com.termux.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.telecom.Call;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The in-call screen: the number, and answer / decline / hang up. It is shown
 * by JawnInCallService (the dialer role's InCallService) and closes itself
 * once the call is gone.
 */
public class JawnInCallActivity extends Activity {

    private final Handler handler = new Handler();
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
        root.setBackgroundColor(Color.parseColor("#10141a"));

        number = new TextView(this);
        number.setTextColor(Color.WHITE);
        number.setTextSize(28);
        number.setGravity(Gravity.CENTER);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#8fa3b8"));
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);

        answer = new Button(this);
        answer.setText("Answer");
        answer.setTextColor(Color.WHITE);
        answer.setBackgroundColor(Color.parseColor("#1d7a3e"));
        answer.setOnClickListener(v -> JawnInCallService.answer());

        decline = new Button(this);
        decline.setText("Decline");
        decline.setTextColor(Color.WHITE);
        decline.setBackgroundColor(Color.parseColor("#b0342e"));
        decline.setOnClickListener(v -> JawnInCallService.reject());

        root.addView(number);
        root.addView(status);
        root.addView(answer);
        root.addView(decline);
        setContentView(root);
        handler.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            int s = JawnInCallService.state;
            number.setText(JawnPhoneState.freshNumber(60_000));
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
