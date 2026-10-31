package com.jawnos.app;

import android.app.Activity;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;

import org.json.JSONObject;

/**
 * The Activity half of the M1 fingerprint call: BiometricPrompt only exists as
 * an Activity flow, so DeviceService starts this translucent Activity, waits on
 * a static latch, and the Activity hands the answer back in-process.
 *
 * The answer uses termux-fingerprint's shape (auth_result) so the caller's
 * parsing does not change: a cancellation or an unavailable sensor is a
 * definitive "not approved", not a reason to prompt a second time through the
 * termux fallback.
 */
public class JawnBridgeAuthActivity extends Activity {

    private boolean answered;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT < 28) {
            deny("biometric prompt needs API 28");
            return;
        }
        String reason = getIntent() == null ? null : getIntent().getStringExtra("reason");
        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
            .setTitle("JawnOS")
            .setSubtitle(reason == null ? "Unlock JawnOS" : reason)
            .setNegativeButton("Cancel", getMainExecutor(), (dialog, which) -> deny("cancelled"))
            .build();
        prompt.authenticate(new CancellationSignal(), getMainExecutor(),
            new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    succeed();
                }
                @Override public void onAuthenticationError(int code, CharSequence message) {
                    deny(message == null ? "error " + code : message.toString());
                }
                @Override public void onAuthenticationFailed() {
                    // a bad finger is another try, not an answer
                }
            });
    }

    private void succeed() {
        if (answered) return;
        answered = true;
        try {
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("auth_result", "AUTH_RESULT_SUCCESS");
            DeviceService.deliverAuth(result);
        } catch (Throwable ignored) {}
        finish();
    }

    private void deny(String why) {
        if (answered) return;
        answered = true;
        try {
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("auth_result", "AUTH_RESULT_FAILURE");
            result.put("error", why == null ? "denied" : why);
            DeviceService.deliverAuth(result);
        } catch (Throwable ignored) {}
        finish();
    }
}
