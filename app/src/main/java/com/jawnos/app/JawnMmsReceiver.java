package com.jawnos.app;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.klinker.android.send_message.MmsReceivedReceiver;

/**
 * The inbound MMS door.
 *
 * A carrier delivers a picture message as a WAP push. The library's own push
 * receiver (com.android.mms.transaction.PushReceiver, declared for
 * WAP_PUSH_DELIVER) only stores the notification envelope and then broadcasts
 * com.klinker.android.messaging.MMS_RECEIVED. This subclass is what actually
 * downloads the message from the carrier's MMSC, acknowledges it and hands back
 * the stored row, with its picture and text parts.
 *
 * Without it the manifest registered the library's abstract base class, for an
 * action the push never sends, so nothing ever downloaded: the notification row
 * appeared as an inbox message with no parts, and Chat saw only its words - or
 * nothing at all once the row expired.
 */
public class JawnMmsReceiver extends MmsReceivedReceiver {
    private static final String TAG = "JawnMms";

    @Override
    public void onMessageReceived(Context context, Uri messageUri) {
        Log.i(TAG, "inbound mms downloaded: " + messageUri);
    }

    @Override
    public void onError(Context context, String error) {
        Log.e(TAG, "inbound mms download failed: " + error);
    }
}
