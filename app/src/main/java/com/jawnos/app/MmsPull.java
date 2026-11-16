package com.jawnos.app;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.util.Log;

/**
 * Fetch the picture messages the phone holds only an envelope for.
 *
 * A carrier sends an MMS as a notification (m_type 130) that somebody must pull
 * from the MMSC. The library's own pull cannot do it on Android 14: it registers
 * its delivery receiver without the exported flag the platform has required
 * since 14, so the pull dies with a RemoteException and the envelope is left to
 * expire. The system MMS service can do the fetch properly, so ask it - this is
 * the same road a send takes.
 */
final class MmsPull {
    private static final String TAG = "JawnMms";

    private MmsPull() { }

    /** Ask the system to fetch every pending envelope. The fetches run in the
     *  background; the answer is how many were asked for. */
    static int pending(Context context) {
        ContentResolver resolver = context.getContentResolver();
        int asked = 0;
        String selection = Telephony.Mms.MESSAGE_BOX + " = ? AND " + Telephony.Mms.MESSAGE_TYPE + " = ?";
        // 130 is the provider's own token for NOTIFICATION_IND
        String[] selectionArgs = { String.valueOf(Telephony.Mms.MESSAGE_BOX_INBOX), "130" };
        String[] columns = { Telephony.Mms._ID, Telephony.Mms.CONTENT_LOCATION, Telephony.Mms.SUBSCRIPTION_ID };
        try (Cursor cursor = resolver.query(Telephony.Mms.CONTENT_URI, columns, selection, selectionArgs,
                Telephony.Mms.DATE + " DESC")) {
            if (cursor == null) return 0;
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String location = cursor.getString(1);
                if (location == null || location.isEmpty()) continue;
                int subId = cursor.isNull(2) ? -1 : cursor.getInt(2);
                Uri row = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id);
                Intent downloaded = new Intent("com.jawnos.mms.DOWNLOADED");
                downloaded.setPackage(context.getPackageName());
                PendingIntent done = PendingIntent.getBroadcast(context, (int) id, downloaded,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                try {
                    SmsManager sms = SmsManager.getSmsManagerForSubscriptionId(subId >= 0
                            ? subId : SubscriptionManager.getDefaultSmsSubscriptionId());
                    sms.downloadMultimediaMessage(context, location, row, null, done);
                    asked++;
                } catch (Throwable t) {
                    Log.e(TAG, "pull " + id + ": " + t);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "pending scan: " + t);
        }
        if (asked > 0) Log.i(TAG, "asked the system to fetch " + asked + " picture message(s)");
        return asked;
    }
}
