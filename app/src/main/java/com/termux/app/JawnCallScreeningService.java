package com.termux.app;

import android.net.Uri;
import android.telecom.Call;
import android.telecom.CallScreeningService;

/**
 * The caller-id half of the phone app, and the desk's doorman.
 *
 * With the Call screening role granted (Configure -> the phone app's Caller ID
 * button asks for it), Android hands every incoming call to onScreenCall with
 * its number (Call.Details.getHandle()) - which a plain phone-state listener no
 * longer gets on Android 12+. The number is left in JawnPhoneState for the
 * WebView's bridge to stamp the call's appointment with the caller's own app.
 *
 * A number on the desk's blacklist (window.JawnPhone, kept by jawnPhoneSync) is
 * refused before the phone can ring: the call is disallowed and rejected, and it
 * is left in the device's call log so the house still files what was ignored -
 * the WebView picks the refused calls up and writes them as cancelled ones. Any
 * other call is allowed through unchanged.
 */
public class JawnCallScreeningService extends CallScreeningService {

    @Override
    public void onScreenCall(Call.Details details) {
        boolean blocked = false;
        try {
            Uri handle = details.getHandle();
            if (handle != null) {
                String number = handle.getSchemeSpecificPart();
                JawnPhoneState.incoming(number);
                if (JawnPhoneBridge.isBlocked(this, number)) {
                    blocked = true;
                    JawnPhoneBridge.recordBlockedCall(this, number);
                }
            }
        } catch (Throwable ignored) { }

        CallResponse.Builder response = new CallResponse.Builder();
        if (blocked) {
            response.setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(true);
        }
        try {
            respondToCall(details, response.build());
        } catch (Throwable ignored) { }
    }
}
