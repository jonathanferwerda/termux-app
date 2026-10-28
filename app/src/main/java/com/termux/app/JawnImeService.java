package com.termux.app;

import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The JawnOS keyboard as an Android input method.
 *
 * <p>The manager's own dock is still the full keyboard: it knows the field it
 * is in and offers the time chips, units and conversions. An input method sees
 * only an editor's type, so this is the same shape and colours - letters first,
 * a number pad for number fields, symbols behind one key - for every app on the
 * device. Android requires the person to enable it in Settings once; the
 * dashboard's Keyboard card opens that screen.
 */
public class JawnImeService extends InputMethodService {

    // The manager's dock, not the old black IME: a light panel, white keys
    // with a soft edge, grey specials and the same yellow return/shift accent.
    private static final int GROUND = 0xFFF2F3F5;
    private static final int KEY = 0xFFFFFFFF;
    private static final int SPECIAL = 0xFFE4E7EB;
    private static final int ACCENT = 0xFFE8C547;
    private static final int INK = 0xFF161616;
    private static final int MUTED = 0xFF5A5A5A;

    private static final String[] LETTER_ROWS = { "qwertyuiop", "asdfghjkl" };
    private static final String LETTER_LAST = "zxcvbnm";
    private static final String[] SYMBOL_ROWS = { "1234567890", "@#$%&*()-+", "/:;!?,.=_" };

    private boolean shift;
    private boolean symbols;
    private boolean numeric;
    private LinearLayout root;
    private final Handler repeat = new Handler(Looper.getMainLooper());
    private Runnable repeatRun;

    @Override
    public View onCreateInputView() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(GROUND);
        root.setPadding(dp(4), dp(6), dp(4), dp(8));
        build();
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        int cls = info == null ? 0 : (info.inputType & InputType.TYPE_MASK_CLASS);
        numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE;
        symbols = false;
        shift = false;
        if (root != null) { build(); }
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onDestroy() {
        stopRepeat();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ layout

    private void build() {
        if (root == null) { return; }
        root.removeAllViews();
        if (numeric) {
            addRow(new String[] { "1", "2", "3", "4", "5" }, null);
            addRow(new String[] { "6", "7", "8", "9", "0" }, null);
            addRow(new String[] { ".", ",", "-", "+", "⌫" }, new String[] { null, null, null, null, "⌫" });
            addRow(new String[] { "ABC", "space", "⏎" }, new String[] { "ABC", "space", "⏎" });
            return;
        }
        if (symbols) {
            addRow(SYMBOL_ROWS[0].split(""), null);
            addRow(SYMBOL_ROWS[1].split(""), null);
            addRow(SYMBOL_ROWS[2].split(""), null);
            addRow(new String[] { "ABC", ",", "space", ".", "⌫", "⏎" },
                   new String[] { "ABC", null, "space", null, "⌫", "⏎" });
            return;
        }
        addRow(LETTER_ROWS[0].split(""), null);
        addRow(LETTER_ROWS[1].split(""), null);
        String[] last = new String[LETTER_LAST.length() + 2];
        last[0] = "⇧";
        for (int i = 0; i < LETTER_LAST.length(); i++) { last[i + 1] = String.valueOf(LETTER_LAST.charAt(i)); }
        last[last.length - 1] = "⌫";
        String[] lastSpecial = new String[last.length];
        lastSpecial[0] = "⇧";
        lastSpecial[last.length - 1] = "⌫";
        addRow(last, lastSpecial);
        addRow(new String[] { "?123", ",", "space", ".", "⏎" },
               new String[] { "?123", null, "space", null, "⏎" });
    }

    private void addRow(String[] keys, String[] specials) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < keys.length; i++) {
            String label = keys[i];
            String special = specials != null && i < specials.length ? specials[i] : null;
            float weight = "space".equals(special) ? 3f : (label.length() > 1 ? 1.4f : 1f);
            row.addView(key(label, special, weight));
        }
        root.addView(row);
    }

    private TextView key(String label, String special, float weight) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        boolean isSpecial = special != null;
        v.setTextColor(isSpecial ? MUTED : INK);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, label.length() > 1 ? 13 : 20);
        int colour = KEY;
        if (isSpecial) { colour = SPECIAL; }
        if ("⇧".equals(special) && shift) { colour = ACCENT; v.setTextColor(GROUND); }
        if ("⏎".equals(special)) { colour = ACCENT; v.setTextColor(GROUND); }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colour);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFD5D9DE);
        v.setBackground(bg);
        v.setPadding(0, dp(11), 0, dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        v.setLayoutParams(lp);

        v.setOnClickListener(view -> press(special != null ? special : label));
        if ("⌫".equals(special)) {
            v.setOnLongClickListener(view -> {
                press("⌫");
                startRepeat();
                return true;
            });
            v.setOnTouchListener((view, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP
                        || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    stopRepeat();
                }
                return false;
            });
        }
        return v;
    }

    // -------------------------------------------------------------------- keys

    private void press(String key) {
        if ("⇧".equals(key)) {
            shift = !shift;
            build();
            return;
        }
        if ("?123".equals(key)) {
            symbols = true;
            numeric = false;
            build();
            return;
        }
        if ("ABC".equals(key)) {
            symbols = false;
            numeric = false;
            build();
            return;
        }
        if ("⌫".equals(key)) {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) { return; }
            CharSequence selected = ic.getSelectedText(0);
            if (selected != null && selected.length() > 0) {
                ic.commitText("", 1);
            } else {
                ic.deleteSurroundingText(1, 0);
            }
            return;
        }
        if ("⏎".equals(key)) {
            if (!sendDefaultEditorAction(true)) {
                commit("\n");
            }
            return;
        }
        if ("space".equals(key)) {
            commit(" ");
            return;
        }
        String text = key;
        if (shift && text.matches("[a-z]")) {
            text = text.toUpperCase();
        }
        commit(text);
        if (shift) {
            shift = false;
            build();
        }
    }

    private void commit(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) { ic.commitText(text, 1); }
    }

    private void startRepeat() {
        stopRepeat();
        repeatRun = new Runnable() {
            @Override
            public void run() {
                press("⌫");
                repeat.postDelayed(this, 55);
            }
        };
        repeat.postDelayed(repeatRun, 380);
    }

    private void stopRepeat() {
        if (repeatRun != null) {
            repeat.removeCallbacks(repeatRun);
            repeatRun = null;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
