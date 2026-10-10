package com.termux.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shows the tail of jawn's log without going near the console. */
public class JawnLogActivity extends Activity {

    private static final int TAIL_BYTES = 32 * 1024;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView text;
    private ScrollView scroll;
    private boolean resumed = false;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        scroll = new ScrollView(this);
        text = new TextView(this);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(11);
        text.setTextIsSelectable(true);
        scroll.addView(text);
        setContentView(scroll);
    }

    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
        ui.postDelayed(poll, 2000);
    }

    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(poll);
        super.onPause();
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            if (!resumed) return;
            refresh();
            ui.postDelayed(this, 2000);
        }
    };

    private void refresh() {
        final String tail = JawnLog.tail(TAIL_BYTES);
        if (tail.equals(text.getText().toString())) return;
        text.setText(tail);
        scroll.post(new Runnable() {
            public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
    }
}
