package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The house's inbox: with the default-sms-app role, Android hands every delivered
 * text to this receiver alone - the framework stops filing them itself, because
 * the default app is now responsible for the message store. So this is where a
 * text becomes real, three times over:
 *
 *  - it is written into the system's message store the way every default sms app
 *    files its own, which is also what the house's own poll reads
 *    (termux-sms list) and what every other app sees;
 *  - it is left in JawnPhoneState, for the WebView bridge;
 *  - and it is appended to jawn's log, so a text is never lost to a failed write.
 *
 * A long message arrives as several pdus in one broadcast: the parts are joined
 * per sender and stored as one message.
 */
public class JawnSmsDeliverReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        try {
            if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) return;
            SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (messages == null || messages.length == 0) return;
            Map<String, StringBuilder> bodies = new LinkedHashMap<>();
            Map<String, Long> times = new LinkedHashMap<>();
            for (SmsMessage m : messages) {
                if (m == null) continue;
                String sender = m.getDisplayOriginatingAddress();
                if (sender == null) sender = "";
                StringBuilder body = bodies.get(sender);
                if (body == null) { body = new StringBuilder(); bodies.put(sender, body); }
                String part = m.getDisplayMessageBody();
                if (part != null) body.append(part);
                if (!times.containsKey(sender)) times.put(sender, m.getTimestampMillis());
            }
            for (Map.Entry<String, StringBuilder> e : bodies.entrySet()) {
                String sender = e.getKey();
                String text = e.getValue().toString();
                Long at = times.get(sender);
                store(context, sender, text, at == null ? System.currentTimeMillis() : at);
                JawnPhoneState.incomingSms(sender, text);
                JawnLog.append("sms in " + sender + ": " + text);
                Log.i("JawnSmsDeliverReceiver", sender + ": " + text);
            }
        } catch (Throwable t) {
            Log.e("JawnSmsDeliverReceiver", String.valueOf(t));
        }
    }

    /**
     * One row in the message store, the way a default sms app files its own (the
     * provider makes the thread). Never throws: a failed write still leaves the
     * text in the log above.
     */
    private void store(Context context, String address, String body, long at) {
        try {
            ContentValues values = new ContentValues();
            values.put(Telephony.Sms.ADDRESS, address);
            values.put(Telephony.Sms.BODY, body);
            values.put(Telephony.Sms.DATE, at);
            values.put(Telephony.Sms.DATE_SENT, at);
            values.put(Telephony.Sms.READ, 0);
            values.put(Telephony.Sms.SEEN, 0);
            values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX);
            ContentResolver cr = context.getContentResolver();
            cr.insert(Telephony.Sms.Inbox.CONTENT_URI, values);
        } catch (Throwable t) {
            Log.e("JawnSmsDeliverReceiver", "could not store the message: " + t);
        }
    }
}
