package com.jawnos.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * The other half of an inbound MMS: the carrier's WAP push stores an envelope
 * (the library's PushReceiver, in this same app) and its own fetch then fails on
 * Android 14, so nothing is ever downloaded. This receiver asks the system to
 * pull whatever is pending, a few seconds later so the envelope is stored first.
 */
public class JawnMmsPush extends BroadcastReceiver {
    private static final String TAG = "JawnMms";
    private static final long DELAY_MS = 4000;

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult result = goAsync();
        final Context app = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(DELAY_MS);
                MmsPull.pending(app);
            } catch (Throwable t) {
                Log.e(TAG, "push pull: " + t);
            } finally {
                result.finish();
            }
        }, "jawn-mms-push");
        worker.start();
    }
}
