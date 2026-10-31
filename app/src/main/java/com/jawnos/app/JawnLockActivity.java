package com.jawnos.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

import com.jawnos.R;

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
    private TextView themeLabel;
    private LinearLayout face;
    private TextView hint;
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
        // The face stays up until the person acts. The unlock is one gesture
        // away: a tap calls the phone's own credential prompt, and a fingerprint
        // on the sensor arrives as ACTION_USER_PRESENT. Raising that prompt
        // straight away would bury the face under the system bouncer, which is
        // the OneUI screen this face is meant to replace.
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshClock();
        // The activity survives a screen-off, so the theme is re-read every
        // time the face is shown: a start/stop appointment may have moved it
        // while the phone slept.
        applyTheme(JawnTheme.current(this));
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
        // Let the face run under the camera cutout, so a light theme has no
        // black band where the status bar would be.
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
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
        face = root;
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

        themeLabel = new TextView(this);
        themeLabel.setTextColor(0xFFE8C547);
        themeLabel.setTextSize(13);
        themeLabel.setLetterSpacing(0.08f);
        themeLabel.setGravity(Gravity.CENTER);
        themeLabel.setVisibility(View.GONE);
        LinearLayout.LayoutParams themeLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        themeLp.topMargin = dp(12);
        root.addView(themeLabel, themeLp);

        hint = new TextView(this);
        hint.setText("Tap to unlock");
        hint.setTextColor(0x8AFFFFFF);
        hint.setTextSize(15);
        hint.setLetterSpacing(0.06f);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(34);
        root.addView(hint, hintLp);

        setContentView(root);
        refreshClock();
    }

    /** Paint the face with the theme the manager recorded for native surfaces. */
    private void applyTheme(JawnTheme theme) {
        if (theme == null) return;
        if (face != null) face.setBackgroundColor(theme.background);
        if (clock != null) clock.setTextColor(theme.ink);
        if (date != null) date.setTextColor(theme.muted);
        if (hint != null) hint.setTextColor(theme.accent);
        if (themeLabel != null) {
            String label = theme.name == null ? "" : theme.name.replace('_', ' ').trim();
            if (theme.appointment && !label.isEmpty()) {
                label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
                themeLabel.setText(label);
                themeLabel.setTextColor(theme.accent);
                themeLabel.setVisibility(View.VISIBLE);
            } else {
                themeLabel.setVisibility(View.GONE);
            }
        }
        Window window = getWindow();
        window.setStatusBarColor(theme.background);
        window.setNavigationBarColor(theme.background);
        // The cutout area is painted by the window background, not the layout.
        window.setBackgroundDrawable(new ColorDrawable(theme.background));
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
