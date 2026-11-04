package com.jawnos.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * window.JawnPhone: the house's phone facts, handed to the native side because a
 * call arrives when no page is open. One blob rides it, kept fresh by the page
 * (public/js/manager/manager.js, jawnPhoneSync):
 *
 *   blocked - the desk's blacklist, as bare digits. JawnCallScreeningService
 *             refuses a listed caller before the phone rings.
 *   names   - the house's numbers by name. The in-call screen names the caller,
 *             and the app's contacts can wear the house's own names.
 *   avatars - a small round picture per number, as a data URI, for the in-call
 *             screen; the app's own logo stands in for anyone without one.
 *
 * The blob is kept in SharedPreferences rather than a static: call screening is
 * often started into a process with no WebView up, so the list has to be there
 * when the phone rings, not only while a page is loaded. Numbers are compared on
 * their last ten digits, the way the house matches them (subs::phone_number_app).
 */
public class JawnPhoneBridge {

    private static final String PREFS = "jawn_phone";
    private static final String FACTS = "facts";
    private static final String CALLS = "blocked_calls";
    private static final String CALLS_LOG = "calls";

    private final Context context;

    public JawnPhoneBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    // ---- the page's side ----

    @JavascriptInterface
    public void setFacts(String json) {
        try {
            prefs().edit().putString(FACTS, json == null ? "" : json).apply();
        } catch (Throwable ignored) { }
    }

    @JavascriptInterface
    public String facts() {
        try { return prefs().getString(FACTS, ""); } catch (Throwable t) { return ""; }
    }

    /** Open the default dialer with the number typed in; this places no call. */
    @JavascriptInterface
    public void dial(String number) {
        try {
            if (number == null) return;
            String clean = number.replaceAll("[^0-9+*#]", "");
            if (clean.isEmpty()) return;
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + clean));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) { }
    }

    /** The calls this phone refused, handed over and cleared in one step. */
    @JavascriptInterface
    public String takeBlockedCalls() {
        try {
            String calls = prefs().getString(CALLS, "[]");
            prefs().edit().putString(CALLS, "[]").apply();
            return calls == null ? "[]" : calls;
        } catch (Throwable t) { return "[]"; }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences prefsOf(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- the native half's side (no page needed) ----

    /** Is this caller on the desk's blacklist? Compared on the last ten digits. */
    public static boolean isBlocked(Context context, String number) {
        String want = lastTen(number);
        if (want.isEmpty()) return false;
        try {
            JSONArray list = factsOf(context).optJSONArray("blocked");
            if (list == null) return false;
            for (int i = 0; i < list.length(); i++) {
                if (lastTen(list.optString(i)).equals(want)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** The house's name for this number, or "" when it does not know it. */
    public static String nameFor(Context context, String number) {
        return valueFor(context, "names", number);
    }

    // ---- the IVR policy (fed by GET /manager/phone/sync) ----

    /** Is the reception flow enabled for this phone? */
    public static boolean ivrEnabled(Context context) {
        return "yes".equals(ivrFacts(context).optString("enabled", "no"));
    }

    /** unknown, all or off: which callers the reception flow should take. */
    public static String ivrRoute(Context context) {
        return ivrFacts(context).optString("route", "unknown");
    }

    /** The selected gateway's signatorial (or IP), or "" when none is set. */
    public static String ivrMachine(Context context) {
        return ivrFacts(context).optString("machine", "");
    }

    /** Is the selected gateway's remote_machines row connected right now? */
    public static boolean ivrMachineAvailable(Context context) {
        return ivrFacts(context).optBoolean("machine_available", false);
    }

    /**
     * The house's relationship for this number: self, friend, stranger, enemy,
     * or "" when the house has no person for it. Fed by GET /manager/phone/sync.
     */
    public static String relationshipFor(Context context, String number) {
        String want = lastTen(number);
        if (want.isEmpty()) return "";
        try {
            JSONObject map = factsOf(context).optJSONObject("relationships");
            if (map == null) return "";
            Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!lastTen(k).equals(want)) continue;
                JSONObject row = map.optJSONObject(k);
                String value = row == null ? "" : row.optString("value", "");
                return value == null ? "" : value;
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /** The per-number route set from the Phone app, or "" when the person decides. */
    public static String routeFor(Context context, String number) {
        return valueFor(context, "routes", number);
    }

    /**
     * Which flow an incoming number gets: ring, ivr, voicemail or reject.
     * The order is the house's: an explicit route for the number wins, then the
     * caller's relationship, then - for a number the house cannot name -
     * "unknown". With the reception flow off, everything rings.
     */
    public static String ivrFlowFor(Context context, String number) {
        if (!ivrEnabled(context)) return "ring";
        if (isBlocked(context, number)) return flowNamed(context, "blacklisted");
        String route = routeFor(context, number);
        if (!route.isEmpty()) return route;
        String bucket = relationshipFor(context, number);
        if (bucket.isEmpty()) {
            bucket = nameFor(context, number).isEmpty() ? "unknown" : "stranger";
        }
        String flow = flowNamed(context, bucket);
        return flow.isEmpty() ? "ring" : flow;
    }

    /**
     * Should this call be answered and handed to the Bluetooth gateway?
     *
     * The gateway is reachable when one is named and online, or - the fact that
     * matters most, because it is what carries the audio - when a Bluetooth
     * Hands-Free device is connected right now. The settings key is a hint that
     * can be stale in the page-written facts; the connected headset is not.
     */
    public static boolean ivrShouldScreen(Context context, String number) {
        if (!ivrEnabled(context)) return false;
        if (!ivrMachineAvailable(context) && !JawnIvrAudio.scoAvailable(context)) return false;
        String flow = ivrFlowFor(context, number);
        return "ivr".equals(flow) || "voicemail".equals(flow);
    }

    private static String flowNamed(Context context, String bucket) {
        try {
            JSONObject flows = ivrFacts(context).optJSONObject("flows");
            if (flows == null || bucket == null || bucket.isEmpty()) return "";
            String flow = flows.optString(bucket, "");
            return flow == null ? "" : flow;
        } catch (Throwable t) { return ""; }
    }

    private static JSONObject ivrFacts(Context context) {
        try {
            JSONObject ivr = factsOf(context).optJSONObject("ivr");
            return ivr == null ? new JSONObject() : ivr;
        } catch (Throwable t) { return new JSONObject(); }
    }

    /** The caller's picture as a data URI, or "" when the house has none. */
    public static String avatarFor(Context context, String number) {
        return valueFor(context, "avatars", number);
    }

    private static String valueFor(Context context, String key, String number) {
        String want = lastTen(number);
        if (want.isEmpty()) return "";
        try {
            JSONObject map = factsOf(context).optJSONObject(key);
            if (map == null) return "";
            Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (lastTen(k).equals(want)) {
                    String value = map.optString(k, "");
                    return value == null ? "" : value;
                }
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /**
     * The finished calls, handed over and cleared in one step: one record per
     * call, written by JawnInCallService when the call ends - whether or not a
     * page was up, which is the whole point of keeping them here.
     */
    @JavascriptInterface
    public String takeCalls() {
        try {
            String calls = prefs().getString(CALLS_LOG, "[]");
            prefs().edit().putString(CALLS_LOG, "[]").apply();
            return calls == null || calls.isEmpty() ? "[]" : calls;
        } catch (Throwable t) { return "[]"; }
    }

    /** Remember a finished call, for the page to file when it is next up. */
    public static void recordCall(Context context, String number, String direction,
                                  long started, long ended, boolean answered) {
        recordCall(context, number, direction, started, ended, answered, false);
    }

    /** The same record, saying whether the reception flow answered it. */
    public static void recordCall(Context context, String number, String direction,
                                  long started, long ended, boolean answered, boolean screened) {
        String want = lastTen(number);
        if (want.isEmpty()) return;
        try {
            SharedPreferences p = prefsOf(context);
            String raw = p.getString(CALLS_LOG, "[]");
            JSONArray list = new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
            JSONObject call = new JSONObject();
            call.put("number", want);
            call.put("direction", direction == null ? "incoming" : direction);
            call.put("started", started);
            call.put("ended", ended);
            call.put("answered", answered);
            if (screened) call.put("screened", true);
            list.put(call);
            p.edit().putString(CALLS_LOG, list.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** Remember a refused call, so the page can file it once it is open again. */
    public static void recordBlockedCall(Context context, String number) {
        String want = lastTen(number);
        if (want.isEmpty()) return;
        try {
            SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = p.getString(CALLS, "[]");
            JSONArray list = new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
            JSONObject call = new JSONObject();
            call.put("number", want);
            call.put("at", System.currentTimeMillis());
            list.put(call);
            p.edit().putString(CALLS, list.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** The comparable part of a number: its digits, and at most the last ten. */
    public static String lastTen(String number) {
        if (number == null) return "";
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (Character.isDigit(c)) digits.append(c);
        }
        String d = digits.toString();
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    private static JSONObject factsOf(Context context) {
        try {
            String raw = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FACTS, "");
            if (raw == null || raw.isEmpty()) return new JSONObject();
            return new JSONObject(raw);
        } catch (Throwable t) { return new JSONObject(); }
    }
}
