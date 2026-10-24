package com.termux.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * The phone's own speech recognizer for the on-screen keyboard
 * (window.JawnSpeech).
 *
 * A WebView has no Web Speech API, so the keyboard's microphone asks this
 * bridge instead. start(language) begins listening; a final phrase arrives at
 * window.jawnSpeechResult(text, true) and an interim one with false. The page
 * restarts the recognizer after each phrase while its microphone is still on,
 * so a person can keep talking. Errors are named ('permission', 'network',
 * 'no-speech', 'busy', 'language', 'unavailable') over
 * window.jawnSpeechState('error', reason).
 *
 * Only this app's own WebView is given the bridge.
 */
public final class JawnSpeech implements RecognitionListener {

    private static final int REQ_AUDIO = 7011;

    private final Activity activity;
    private final Consumer<String> js;
    private SpeechRecognizer recognizer;
    private boolean wantListen = false;
    private String lang = "en-US";

    public JawnSpeech(Activity activity, Consumer<String> js) {
        this.activity = activity;
        this.js = js;
    }

    private void post(String script) {
        js.accept(script);
    }

    private void state(String state, String detail) {
        post("window.jawnSpeechState && window.jawnSpeechState("
            + JSONObject.quote(state) + ","
            + JSONObject.quote(detail == null ? "" : detail) + ");");
    }

    private void result(String text, boolean isFinal) {
        if (text == null || text.isEmpty()) return;
        post("window.jawnSpeechResult && window.jawnSpeechResult("
            + JSONObject.quote(text) + "," + (isFinal ? "true" : "false") + ");");
    }

    @JavascriptInterface
    public boolean available() {
        return SpeechRecognizer.isRecognitionAvailable(activity);
    }

    @JavascriptInterface
    public void start(String language) {
        if (language != null && !language.isEmpty()) lang = language;
        activity.runOnUiThread(() -> {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                    wantListen = false;
                    state("error", "unavailable");
                    return;
                }
                if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    wantListen = true;
                    activity.requestPermissions(
                        new String[] { Manifest.permission.RECORD_AUDIO }, REQ_AUDIO);
                    return;
                }
                wantListen = true;
                begin();
            } catch (Throwable t) {
                wantListen = false;
                state("error", "unavailable");
            }
        });
    }

    @JavascriptInterface
    public void stop() {
        wantListen = false;
        activity.runOnUiThread(() -> {
            try {
                if (recognizer != null) recognizer.stopListening();
            } catch (Throwable t) { /* the listener is going away */ }
        });
    }

    /** The activity's permission result; true when it was this bridge's ask. */
    public boolean onPermissionResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_AUDIO) return false;
        boolean granted = results != null && results.length > 0
            && results[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && wantListen) begin();
        else {
            wantListen = false;
            state("error", "permission");
        }
        return true;
    }

    public void destroy() {
        wantListen = false;
        try {
            if (recognizer != null) {
                recognizer.cancel();
                recognizer.destroy();
            }
        } catch (Throwable t) { /* already gone */ }
        recognizer = null;
    }

    private void begin() {
        try {
            if (recognizer != null) {
                try { recognizer.cancel(); } catch (Throwable ignored) { }
                try { recognizer.destroy(); } catch (Throwable ignored) { }
                recognizer = null;
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(this);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            recognizer.startListening(intent);
            state("listening", "");
        } catch (Throwable t) {
            wantListen = false;
            state("error", "busy");
        }
    }

    private String best(Bundle results) {
        if (results == null) return null;
        ArrayList<String> heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (heard == null || heard.isEmpty()) return null;
        return heard.get(0);
    }

    @Override
    public void onReadyForSpeech(Bundle params) { state("listening", ""); }

    @Override
    public void onBeginningOfSpeech() { }

    @Override
    public void onRmsChanged(float rmsdB) { }

    @Override
    public void onBufferReceived(byte[] buffer) { }

    @Override
    public void onEndOfSpeech() { }

    @Override
    public void onError(int error) {
        String reason;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                reason = "no-speech"; break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                reason = "permission"; break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                reason = "network"; break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                reason = "busy"; break;
            // ERROR_LANGUAGE_NOT_SUPPORTED (12) and ERROR_LANGUAGE_UNAVAILABLE
            // (13) arrived in API 31; this app compiles against 30, so they are
            // named by number rather than by a constant the build cannot see.
            case 12:
            case 13:
                reason = "language"; break;
            default:
                reason = "unavailable"; break;
        }
        wantListen = false;
        state("error", reason);
    }

    @Override
    public void onResults(Bundle results) {
        result(best(results), true);
        state("stopped", "");
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        result(best(partialResults), false);
    }

    @Override
    public void onEvent(int eventType, Bundle params) { }
}
