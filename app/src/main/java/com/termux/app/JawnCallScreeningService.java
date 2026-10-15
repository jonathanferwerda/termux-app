package com.termux.app;

import android.net.Uri;
import android.telecom.Call;
import android.telecom.CallScreeningService;

/**
 * The caller-id half of the phone app.
 *
 * With the Call screening role granted (Configure -> the phone app's Caller ID
 * button asks for it), Android hands every incoming call to onScreenCall with
 * its number (Call.Details.getHandle()) - which a plain phone-state listener no
 * longer gets on Android 12+. The number is left in JawnPhoneState for the
 * WebView's bridge to stamp the call's appointment with the caller's own app,
 * and the call is allowed through unchanged: this app never blocks a call.
 */
public class JawnCallScreeningService extends CallScreeningService {

    @Override
    public void onScreenCall(Call.Details details) {
        try {
            Uri handle = details.getHandle();
            if (handle != null) {
                JawnPhoneState.incoming(handle.getSchemeSpecificPart());
            }
        } catch (Throwable ignored) { }
        // allow the call - this app only wants the number, not to block it
        try {
            respondToCall(details, new CallResponse.Builder().build());
        } catch (Throwable ignored) { }
    }
}
