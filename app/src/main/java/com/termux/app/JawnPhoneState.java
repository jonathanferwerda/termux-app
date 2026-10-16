package com.termux.app;

/**
 * A tiny shared slot between the phone components: the CallScreeningService puts
 * the ringing call's number here the moment the phone rings (Android 12+ no
 * longer hands it to a plain PhoneStateListener), and the WebView bridge reads it
 * to stamp the call's appointment with the caller's own app.
 */
public class JawnPhoneState {

    public static volatile String incomingNumber = "";
    public static volatile long incomingAt = 0;

    /** Set by the WebView while a page is up: a way to run a snippet in it. */
    public interface Poster { void post(String script); }
    public static volatile Poster poster;

    public static void incoming(String number) {
        incomingNumber = number == null ? "" : number;
        incomingAt = System.currentTimeMillis();
    }

    // ---- the newest delivered text (fed by JawnSmsReceiver) ----
    public static volatile String incomingSmsSender = "";
    public static volatile String incomingSmsBody = "";
    public static volatile long incomingSmsAt = 0;

    public static void incomingSms(String sender, String body) {
        incomingSmsSender = sender == null ? "" : sender;
        incomingSmsBody = body == null ? "" : body;
        incomingSmsAt = System.currentTimeMillis();
    }

    /** The number of the call ringing now, or "" when there is none or it is stale. */
    public static String freshNumber(long withinMs) {
        if (incomingNumber == null || incomingNumber.isEmpty()) return "";
        if (System.currentTimeMillis() - incomingAt > withinMs) return "";
        return incomingNumber;
    }
}
