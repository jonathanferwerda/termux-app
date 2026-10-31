package com.jawnos.app;

import android.app.Activity;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.webkit.JavascriptInterface;

import java.util.function.Consumer;

/**
 * Native fingerprint confirmation for the web app (window.JawnBiometric).
 *
 * The page calls confirm(); the result comes back as
 * window.jawnBiometricResult(true), (false) or ('unavailable'). The unlock
 * signature itself is still made by the web app's Ed25519 key.
 */
public final class JawnBiometric {

    private final Activity activity;
    private final Consumer<String> js;
    private CancellationSignal running;

    public JawnBiometric(Activity activity, Consumer<String> js) {
        this.activity = activity;
        this.js = js;
    }

    private void finish(boolean ok) {
        js.accept("window.jawnBiometricResult && window.jawnBiometricResult(" + (ok ? "true" : "false") + ");");
    }

    private void unavailable() {
        js.accept("window.jawnBiometricResult && window.jawnBiometricResult('unavailable');");
    }

    @JavascriptInterface
    public void confirm(String title) {
        activity.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT < 28) {
                unavailable();
                return;
            }
            try {
                if (running != null) {
                    running.cancel();
                }
                running = new CancellationSignal();
                BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                    .setTitle(title == null || title.isEmpty() ? "Unlock the house" : title)
                    .setNegativeButton("Cancel", activity.getMainExecutor(), (dialog, which) -> finish(false))
                    .build();
                prompt.authenticate(running, activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        running = null;
                        finish(true);
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        running = null;
                        						if (errorCode == 1 || errorCode == 11 || errorCode == 12) {
                        							// 1 HW unavailable, 11 no biometrics, 12 no hardware
                        							unavailable();
                        						} else {
                        							finish(false);
                        						}
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // the prompt stays up; the person can try another finger
                    }
                });
            } catch (Throwable t) {
                unavailable();
            }
        });
    }
}
