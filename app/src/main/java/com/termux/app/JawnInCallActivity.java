package com.termux.app;

import android.app.Activity;
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
import android.os.Bundle;
import android.os.Handler;
import android.telecom.Call;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.R;

/**
 * The in-call screen: who is calling, and answer / decline / hang up. It wakes
 * over the lock screen (FLAG_SHOW_WHEN_LOCKED + TURN_SCREEN_ON) and closes itself
 * once the call is gone.
 *
 * The house's own mark sits at the top, and the caller wears a round picture: the
 * person's own image when the house sent one (JawnPhoneBridge keeps the pictures
 * synced from /manager/phone/sync), the app's logo when it did not - and the
 * house's name for the number above the raw number either way.
 */
public class JawnInCallActivity extends Activity {

    private final Handler handler = new Handler();
    private ImageView avatar;
    private TextView name;
    private TextView number;
    private TextView status;
    private Button answer;
    private Button decline;
    private String paintedFor = null;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(40), dp(28), dp(28));
        root.setBackgroundColor(Color.parseColor("#0d1117"));

        TextView house = new TextView(this);
        house.setText("JawnOS");
        house.setTextColor(Color.parseColor("#4a90d9"));
        house.setTextSize(15);
        house.setLetterSpacing(0.15f);
        house.setGravity(Gravity.CENTER);

        // the caller's half: everything below the mark, centred in what is left
        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setGravity(Gravity.CENTER);
        middle.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(180), dp(180));
        ap.bottomMargin = dp(20);
        avatar.setLayoutParams(ap);

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

        middle.addView(avatar);
        middle.addView(name);
        middle.addView(number);
        middle.addView(status);
        middle.addView(answer);
        middle.addView(decline);
        root.addView(house);
        root.addView(middle);
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
            paintAvatar(digits);
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
