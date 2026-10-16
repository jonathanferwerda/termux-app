package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
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
