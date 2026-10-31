package com.jawnos.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;

/**
 * The delivery reports for the texts JawnOS sends itself: the sent broadcast
 * SmsManager fires comes back here (see JawnSms), where the newest report is
 * logged and left in JawnPhoneState. An incoming text is a different broadcast
 * entirely - android.provider.Telephony.SMS_DELIVER, handled by
 * JawnSmsDeliverReceiver (which is what files it, now that JawnOS is the
 * default sms app).
 */
public class JawnSmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !"android.provider.Telephony.SMS_DELIVERED".equals(intent.getAction())) return;
        try {
            Bundle bundle = intent.getExtras();
            if (bundle == null) return;
            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus == null) return;
            String format = bundle.containsKey("format") ? bundle.getString("format") : "3gpp";
            for (Object pdu : pdus) {
                SmsMessage msg = SmsMessage.createFromPdu((byte[]) pdu, format);
                String sender = msg.getDisplayOriginatingAddress();
                String body = msg.getDisplayMessageBody();
                JawnPhoneState.incomingSms(sender, body);
                android.util.Log.i("JawnSmsReceiver", sender + ": " + body);
                appendLog(sender + ": " + body);
            }
        } catch (Throwable t) {
            android.util.Log.e("JawnSmsReceiver", String.valueOf(t));
        }
    }

    private void appendLog(String line) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(JawnLog.file(), true);
            fw.write("sms in " + line + "\n");
            fw.close();
        } catch (Throwable ignored) {}
    }
}
