package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;

/**
 * The house's inbox: when JawnOS is the default sms app, every delivered text
 * lands here first. The web side still reads the message store its own way
 * (the phone's termux poll), so this receiver's job is to log the arrival and
 * hold the newest one where the webview can ask - nothing is lost by being the
 * default app.
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
