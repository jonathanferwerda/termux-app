package com.termux.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

import com.termux.R;

/**
 * JawnOS's lock screen face (option A).
 *
 * It is drawn over the system keyguard and the unlock is handed to Android with
 * {@link KeyguardManager#requestDismissKeyguard}: the phone's own PIN, pattern
 * or fingerprint is the one unlock, so there is no second prompt behind it.
 * The face is only the face - the system keyguard is still the gate.
 */
public class JawnLockActivity extends Activity {

    private TextView clock;
    private TextView date;
    private boolean closing;
    private BroadcastReceiver present;
    private KeyguardManager keyguard;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        lockScreenWindow();
        buildFace();
        listenForPresent();
        // A secure keyguard gets its prompt straight away (the face is behind
        // it). A swipe-only keyguard has nothing to ask, so leave the face up
        // until the person taps it.
        if (keyguard != null && keyguard.isKeyguardSecure()) {
            getWindow().getDecorView().postDelayed(new Runnable() {
                public void run() { unlock(); }
            }, 450);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshClock();
        // Started by a screen-on that raced the person's own unlock, or left
        // over after the keyguard went away: do not become a second gate.
        if (keyguard != null && !keyguard.isKeyguardLocked()) close();
    }

    @Override
    protected void onDestroy() {
        if (present != null) {
            try { unregisterReceiver(present); } catch (Throwable ignored) { }
            present = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // The keyguard is still up; Back must not step around the face.
    }

    /** Over the keyguard on API 27+, with the pre-27 window flags otherwise. */
    private void lockScreenWindow() {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void buildFace() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(48));
        root.setBackgroundColor(Color.parseColor("#0B0B0C"));
        root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { unlock(); }
        });

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.jawn_logo);
        mark.setAdjustViewBounds(true);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(dp(64), dp(64));
        markLp.bottomMargin = dp(30);
        root.addView(mark, markLp);

        clock = new TextView(this);
        clock.setTextColor(0xFFF2F2F2);
        clock.setTextSize(62);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setGravity(Gravity.CENTER);
        root.addView(clock);

        date = new TextView(this);
        date.setTextColor(0xB3FFFFFF);
        date.setTextSize(17);
        date.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dateLp.topMargin = dp(6);
        root.addView(date, dateLp);

        TextView hint = new TextView(this);
        hint.setText("JawnOS");
        hint.setTextColor(0x66FFFFFF);
        hint.setTextSize(13);
        hint.setLetterSpacing(0.18f);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(34);
        root.addView(hint, hintLp);

        setContentView(root);
        refreshClock();
    }

    private void refreshClock() {
        long now = System.currentTimeMillis();
        if (clock != null) {
            clock.setText(DateFormat.getTimeFormat(this).format(new Date(now)));
        }
        if (date != null) {
            date.setText(DateFormat.format("EEEE, MMMM d", now));
        }
    }

    /** Hand the unlock to the phone's own keyguard credential. */
    private void unlock() {
        if (closing) return;
        KeyguardManager km = keyguard != null ? keyguard
            : (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardLocked()) { close(); return; }
        try {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() { close(); }

                @Override
                public void onDismissCancelled() { }

                @Override
                public void onDismissError() { }
            });
        } catch (Throwable t) {
            // A stack that cannot ask leaves the face up rather than opening
            // the phone without a credential.
        }
    }

    /** The system unlocked by its own means (a sensor on the keyguard). */
    private void listenForPresent() {
        present = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) close();
            }
        };
        try {
            registerReceiver(present, new IntentFilter(Intent.ACTION_USER_PRESENT));
        } catch (Throwable t) {
            present = null;
        }
    }

    private void close() {
        if (closing) return;
        closing = true;
        finish();
        overridePendingTransition(0, 0);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
