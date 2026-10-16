package com.termux.app;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.util.Log;

import java.util.ArrayList;

/**
 * The sms role's quick reply, and the component the role will not be granted
 * without. Android's own requirement for the default-sms-app role is a component
 * that answers android.intent.action.RESPOND_VIA_MESSAGE for the smsto scheme,
 * guarded by SEND_RESPOND_VIA_MESSAGE - the system names it in the log when a
 * package is refused the role ("not qualified ... missing RequiredComponent
 * RESPOND_VIA_MESSAGE"), which is exactly how this app was refused: everything
 * else was in place and this was not.
 *
 * It is a real face, not a formality: with the role, the call screen's "reply"
 * (from the notification shade or the lock screen) asks the default sms app to
 * send a text, with the number in the smsto: data and the words in EXTRA_TEXT.
 * That text goes out through SmsManager, the same way the app's own messages do,
 * and is logged so a reply sent from the shade is still visible afterwards.
 */
public class JawnRespondViaMessageService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null && "android.intent.action.RESPOND_VIA_MESSAGE".equals(intent.getAction())) {
                Uri data = intent.getData();
                String number = data == null ? "" : data.getSchemeSpecificPart();
                String text = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (text == null) text = intent.getStringExtra("android.intent.extra.TEXT");
                if (number != null && !number.isEmpty() && text != null && !text.isEmpty()) {
                    SmsManager sm = SmsManager.getDefault();
                    ArrayList<String> parts = sm.divideMessage(text);
                    sm.sendMultipartTextMessage(number, null, parts, null, null);
                    JawnPhoneState.incomingSms(number, text);
                    JawnLog.append("sms out (quick reply) " + number + ": " + text);
                    Log.i("JawnRespondViaMessage", number + ": " + text);
                }
            }
        } catch (Throwable t) {
            Log.e("JawnRespondViaMessage", String.valueOf(t));
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
