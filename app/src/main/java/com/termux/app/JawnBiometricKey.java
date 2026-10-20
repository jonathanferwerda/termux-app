package com.termux.app;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.function.Consumer;

/**
 * The phone's own biometric unlock key (window.JawnBiometricKey).
 *
 * A non-exportable EC P-256 key in Android Keystore, created with
 * setUserAuthenticationRequired(true): the OS will not let the app sign with it
 * until the person passes the fingerprint prompt. The public half is enrolled
 * with the JawnOS house, which verifies the signature. This is a real factor -
 * unlike a JS "confirmed" callback - and it is the one local signature the
 * padlock can accept for the phone's own screen.
 *
 * JS surface:
 *   hasKey()           -> {ok:true} | {ok:false,error:'no key'}
 *   enroll(title)      -> {ok:true,pubkey:<base64 SPKI DER>}
 *   sign(msgB64,title) -> {ok:true,signature:<base64 DER>}
 * results arrive through window.jawnosBiometricKeyResult(...).
 */
public final class JawnBiometricKey {

    private static final String ALIAS = "jawnos_padlock_biometric";
    private final Activity activity;
    private final Consumer<String> js;
    private CancellationSignal running;

    public JawnBiometricKey(Activity activity, Consumer<String> js) {
        this.activity = activity;
        this.js = js;
    }

    private void reply(String json) {
        js.accept("window.jawnosBiometricKeyResult && window.jawnosBiometricKeyResult(" + json + ");");
    }

    private void fail(String error) {
        reply("{\"ok\":false,\"error\":\"" + error + "\"}");
    }

    private void unavailable() {
        fail("unavailable");
    }

    private static boolean supported() {
        return Build.VERSION.SDK_INT >= 28;
    }

    private KeyPair loadOrCreate(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            if (!create) { return null; }
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);
            if (Build.VERSION.SDK_INT >= 30) {
                spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
            }
            else {
                // -1: every use of the key needs a fresh authentication, and the
                // BiometricPrompt CryptoObject is what authorises this one.
                spec.setUserAuthenticationValidityDurationSeconds(-1);
            }
            kpg.initialize(spec.build());
            return kpg.generateKeyPair();
        }
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) ks.getEntry(ALIAS, null);
        if (entry == null) { return null; }
        return new KeyPair(entry.getCertificate().getPublicKey(), entry.getPrivateKey());
    }

    private void prompt(String title, Signature sig, Runnable after) {
        if (running != null) { running.cancel(); }
        running = new CancellationSignal();
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
            .setTitle(title == null || title.isEmpty() ? "Unlock the house" : title);
        if (Build.VERSION.SDK_INT >= 30) {
            // A tablet with no fingerprint reader can still protect the key with
            // its own device credential (PIN/pattern/face where supported). The
            // key is created with the same two auth types.
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        }
        else {
            builder.setNegativeButton("Cancel", activity.getMainExecutor(), (dialog, which) -> {
                running = null;
                fail("cancelled");
            });
        }
        BiometricPrompt prompt = builder.build();
        BiometricPrompt.AuthenticationCallback callback = new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                running = null;
                after.run();
            }

            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                running = null;
                if (errorCode == 1 || errorCode == 11 || errorCode == 12) {
                    // 1 HW unavailable, 11 no biometrics, 12 no hardware
                    unavailable();
                }
                else if (errorCode == 7 || errorCode == 9) {
                    // 7 lockout, 9 permanent lockout
                    fail("locked");
                }
                else {
                    fail("cancelled");
                }
            }

            @Override
            public void onAuthenticationFailed() {
                // the prompt stays up; the person can try another finger
            }
        };
        if (sig != null) {
            prompt.authenticate(new BiometricPrompt.CryptoObject(sig), running, activity.getMainExecutor(), callback);
        }
        else {
            prompt.authenticate(running, activity.getMainExecutor(), callback);
        }
    }

    @JavascriptInterface
    public void hasKey() {
        activity.runOnUiThread(() -> {
            if (!supported()) { unavailable(); return; }
            try {
                KeyPair pair = loadOrCreate(false);
                if (pair == null) { fail("no key"); return; }
                reply("{\"ok\":true}");
            }
            catch (android.security.keystore.KeyPermanentlyInvalidatedException e) { fail("invalidated"); }
            catch (Throwable t) { fail("no key"); }
        });
    }

    /** Make a fresh biometric key. The prompt proves a person is present; the OS
     *  binds the key to the enrolled biometric from then on. */
    @JavascriptInterface
    public void enroll(String title) {
        activity.runOnUiThread(() -> {
            if (!supported()) { unavailable(); return; }
            prompt(title, null, () -> {
                try {
                    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                    ks.load(null);
                    if (ks.containsAlias(ALIAS)) { ks.deleteEntry(ALIAS); }
                    KeyPair pair = loadOrCreate(true);
                    if (pair == null) { unavailable(); return; }
                    String pub = Base64.encodeToString(pair.getPublic().getEncoded(), Base64.NO_WRAP);
                    reply("{\"ok\":true,\"pubkey\":\"" + pub + "\"}");
                }
                catch (Throwable t) { unavailable(); }
            });
        });
    }

    /** Sign a base64 message after the OS biometric prompt. The key is released
     *  only for this operation, and only after the person authenticates. */
    @JavascriptInterface
    public void sign(String messageBase64, String title) {
        final byte[] message;
        try {
            message = Base64.decode(messageBase64, Base64.NO_WRAP);
        }
        catch (Throwable t) {
            fail("not accepted");
            return;
        }
        activity.runOnUiThread(() -> {
            if (!supported()) { unavailable(); return; }
            final KeyPair pair;
            try {
                pair = loadOrCreate(false);
            }
            catch (android.security.keystore.KeyPermanentlyInvalidatedException e) { fail("invalidated"); return; }
            catch (java.security.UnrecoverableKeyException e) { fail("invalidated"); return; }
            catch (Throwable t) { fail("no key"); return; }
            if (pair == null) { fail("no key"); return; }
            try {
                PrivateKey key = pair.getPrivate();
                Signature sig = Signature.getInstance("SHA256withECDSA");
                sig.initSign(key);
                prompt(title, sig, () -> {
                    try {
                        sig.update(message);
                        byte[] out = sig.sign();
                        String encoded = Base64.encodeToString(out, Base64.NO_WRAP);
                        reply("{\"ok\":true,\"signature\":\"" + encoded + "\"}");
                    }
                    catch (Throwable t) { fail("not accepted"); }
                });
            }
            catch (android.security.keystore.KeyPermanentlyInvalidatedException e) { fail("invalidated"); }
            catch (Throwable t) { fail("invalidated"); }
        });
    }
}
