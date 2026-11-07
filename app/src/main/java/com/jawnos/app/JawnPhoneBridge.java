package com.jawnos.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Iterator;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

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

    /** The gateway adapter's name when the house did not name one. */
    private static final String DEFAULT_GATEWAY_BLUETOOTH = "jawn-gateway";

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

    /**
     * Is this app the phone app? Without the dialer role Android hands incoming
     * calls to another dialer, and the in-call service (which answers for the
     * reception) never runs at all.
     */
    @JavascriptInterface
    public String dialerRole() {
        try {
            android.app.role.RoleManager rm =
                (android.app.role.RoleManager) context.getSystemService(Context.ROLE_SERVICE);
            if (rm == null || !rm.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER)) return "no";
            return rm.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) ? "yes" : "no";
        } catch (Throwable t) { return "no"; }
    }

    /** Ask to become the phone app. The system shows its own confirmation. */
    @JavascriptInterface
    public void requestDialer() {
        try {
            android.app.role.RoleManager rm =
                (android.app.role.RoleManager) context.getSystemService(Context.ROLE_SERVICE);
            if (rm == null) return;
            Intent intent = rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER);
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

    /** The Bluetooth name of the gateway adapter (e.g. jawn-gateway). */
    public static String ivrBluetoothName(Context context) {
        String name = ivrFacts(context).optString("bluetooth", "");
        return name == null || name.trim().isEmpty() ? DEFAULT_GATEWAY_BLUETOOTH : name.trim();
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
        return ivrScreened(ivrFlowFor(context, number));
    }

    /**
     * Is this flow one the phone answers for the gateway? Anything the house
     * named is: the older "ivr" and "voicemail" words, and a flow of its own.
     * "ring" and "reject" are decisions the phone keeps to itself.
     */
    public static boolean ivrScreened(String flow) {
        return flow != null && !flow.isEmpty()
                && !"ring".equals(flow) && !"reject".equals(flow);
    }

    private static String flowNamed(Context context, String bucket) {
        try {
            JSONObject flows = ivrFacts(context).optJSONObject("flows");
            if (flows == null || bucket == null || bucket.isEmpty()) return "";
            String flow = flows.optString(bucket, "");
            return flow == null ? "" : flow;
        } catch (Throwable t) { return ""; }
    }

    /**
     * Is the house holding the caller on music after the thanks? The gateway
     * plays it and keeps the line up, so the in-call watchdog must not hang up
     * at the end of the prompts. The choice is the IVR pane's Hold music.
     */
    public static boolean ivrHoldOn(Context context) {
        String hold = ivrFacts(context).optString("hold", "");
        return hold != null && !hold.isEmpty() && !"none".equalsIgnoreCase(hold);
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

    // ---- telling the gateway a screened call is over ----

    /**
     * Tell the house's gateway the call it was carrying has ended. The
     * gateway hangs up on its own timeout too, so this is a courtesy: it frees
     * the handed-over link and keeps a late prompt out of the next call. It
     * runs off the main thread - the call has already gone by the time it
     * starts - and a failure changes nothing.
     */
    public static void cancelGateway(final Context context) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                int port = JawnLauncher.getJawnPort(app);
                String origin = "https://" + JawnLauncher.managerHost(app) + ":" + port;
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(origin + "/manager/sco/cancel").openConnection();
                    trustHouse(conn);
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(4000);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    String cookie = presidentCookie(app, origin, port);
                    if (cookie != null && !cookie.isEmpty()) {
                        conn.setRequestProperty("Cookie", cookie);
                    }
                    OutputStream out = conn.getOutputStream();
                    try { out.write(new byte[0]); } finally { out.close(); }
                    JawnLog.append("ivr: gateway cancel " + conn.getResponseCode());
                } catch (Throwable t) {
                    JawnLog.append("ivr: gateway cancel failed: " + t);
                } finally {
                    if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
                }
            }
        }, "jawn-ivr-cancel").start();
    }

    /**
     * POST /manager/sco/rtc: start or stop the WebRTC bridge on the machine that
     * is carrying the call. This is the same hop the Manager's screening panel
     * makes (public/js/manager/screening.js, jawnScreenRtcSignal), reused so the
     * in-call screen can offer the bridge without a page.
     *
     * The gateway spawns its bridge on "start" and closes it on "stop"; the media
     * itself is carried by whichever window can hold a microphone, because this
     * app has no WebRTC peer of its own.
     *
     * Returns the gateway's reply as text ({"ok":1,"state":"starting"} or
     * {"ok":0,"error":"..."}), or null when the house did not answer. Blocks for
     * up to the read timeout, so it belongs on a worker thread.
     */
    public static String scoRtc(Context context, String action) {
        Context app = context.getApplicationContext();
        int port = JawnLauncher.getJawnPort(app);
        String origin = "https://" + JawnLauncher.managerHost(app) + ":" + port;
        HttpURLConnection conn = null;
        try {
            String body = "action=" + Uri.encode(action == null ? "" : action);
            byte[] bytes = body.getBytes("UTF-8");
            conn = (HttpURLConnection) new URL(origin + "/manager/sco/rtc").openConnection();
            trustHouse(conn);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setFixedLengthStreamingMode(bytes.length);
            String cookie = presidentCookie(app, origin, port);
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            OutputStream out = conn.getOutputStream();
            try { out.write(bytes); } finally { out.close(); }
            if (conn.getResponseCode() != 200) {
                JawnLog.append("rtc: start/stop answered " + conn.getResponseCode());
                return null;
            }
            InputStream in = conn.getInputStream();
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) buffer.write(buf, 0, n);
                return buffer.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            JawnLog.append("rtc: bridge request failed: " + t);
            return null;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    /**
     * POST /manager/sco/hold: the hold music the gateway plays into a call it
     * is carrying.
     *
     * The contract with the house: a form-urlencoded POST whose one field is
     * `action`, with the value `start` or `stop` - the same {action:'start'} /
     * {action:'stop'} body /manager/sco/rtc takes. The reply is JSON: {"ok":1}
     * when the gateway has started or stopped the music, or
     * {"ok":0,"error":"..."} naming why it did not (no music configured, or no
     * call on the gateway). Any status other than 200, or a body that cannot be
     * read as JSON, means the house did not answer and the screen's hold state
     * is left where it was.
     *
     * Blocks for up to the read timeout, so it belongs on a worker thread.
     */
    public static String scoHold(Context context, String action) {
        Context app = context.getApplicationContext();
        int port = JawnLauncher.getJawnPort(app);
        String origin = "https://" + JawnLauncher.managerHost(app) + ":" + port;
        HttpURLConnection conn = null;
        try {
            String body = "action=" + Uri.encode(action == null ? "" : action);
            byte[] bytes = body.getBytes("UTF-8");
            conn = (HttpURLConnection) new URL(origin + "/manager/sco/hold").openConnection();
            trustHouse(conn);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setFixedLengthStreamingMode(bytes.length);
            String cookie = presidentCookie(app, origin, port);
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            OutputStream out = conn.getOutputStream();
            try { out.write(bytes); } finally { out.close(); }
            if (conn.getResponseCode() != 200) {
                JawnLog.append("hold: start/stop answered " + conn.getResponseCode());
                return null;
            }
            InputStream in = conn.getInputStream();
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) buffer.write(buf, 0, n);
                return buffer.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            JawnLog.append("hold: request failed: " + t);
            return null;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    /**
     * POST /manager/sco/voicemail: the house's voicemail, now - the flow's
     * greeting (or words the person typed) plays and the message is recorded,
     * instead of after the rest of the flow.
     *
     * The one field is `text`, optional words to speak before the recording.
     * This screen has no typed line, so it posts none and the house's own
     * greeting plays. The reply is {"ok":1,"flow":"...","step":"voicemail"} or
     * {"ok":0,"error":"..."} - no call is up, or it is already recording. The
     * call is deliberately left alone: the gateway ends it when the recording
     * does, so nothing here hangs up.  A null return means the house did not
     * answer. Blocks for up to the read timeout, so it belongs on a worker
     * thread.
     */
    public static String scoVoicemail(Context context, String text) {
        Context app = context.getApplicationContext();
        int port = JawnLauncher.getJawnPort(app);
        String origin = "https://" + JawnLauncher.managerHost(app) + ":" + port;
        HttpURLConnection conn = null;
        try {
            String body = "text=" + Uri.encode(text == null ? "" : text);
            byte[] bytes = body.getBytes("UTF-8");
            conn = (HttpURLConnection) new URL(origin + "/manager/sco/voicemail").openConnection();
            trustHouse(conn);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setFixedLengthStreamingMode(bytes.length);
            String cookie = presidentCookie(app, origin, port);
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            OutputStream out = conn.getOutputStream();
            try { out.write(bytes); } finally { out.close(); }
            if (conn.getResponseCode() != 200) {
                JawnLog.append("voicemail: request answered " + conn.getResponseCode());
                return null;
            }
            InputStream in = conn.getInputStream();
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) buffer.write(buf, 0, n);
                return buffer.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            JawnLog.append("voicemail: request failed: " + t);
            return null;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    /**
     * POST /manager/sco/flow: name the caller's flow before the link is
     * answered. The gateway holds the key until it accepts the call and then
     * runs that flow's plan; with nothing posted it keeps the flow its unknown
     * bucket names, so a missed post still leaves a working call.
     *
     * The one field is `flow`. "ivr" is the phone's older word for the built-in
     * receptionist and the caller maps it; every other key is the house's own.
     * Returns the reply as text ({"ok":1,"flow":"...","state":"..."} or
     * {"ok":0,"error":"..."}), or null when the house did not answer. Blocks
     * for up to the read timeout - kept short, because the caller is waiting on
     * the answer - so it belongs on a worker thread.
     */
    public static String scoFlow(Context context, String flow) {
        Context app = context.getApplicationContext();
        int port = JawnLauncher.getJawnPort(app);
        String origin = "https://" + JawnLauncher.managerHost(app) + ":" + port;
        HttpURLConnection conn = null;
        try {
            String body = "flow=" + Uri.encode(flow == null ? "" : flow);
            byte[] bytes = body.getBytes("UTF-8");
            conn = (HttpURLConnection) new URL(origin + "/manager/sco/flow").openConnection();
            trustHouse(conn);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setFixedLengthStreamingMode(bytes.length);
            String cookie = presidentCookie(app, origin, port);
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            OutputStream out = conn.getOutputStream();
            try { out.write(bytes); } finally { out.close(); }
            if (conn.getResponseCode() != 200) {
                JawnLog.append("flow: name answered " + conn.getResponseCode());
                return null;
            }
            InputStream in = conn.getInputStream();
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) buffer.write(buf, 0, n);
                return buffer.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            JawnLog.append("flow: request failed: " + t);
            return null;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    /**
     * GET /manager/sco/status: what the gateway is doing and the caller's live
     * transcript. The body is handed back as text for the screening face to
     * parse, because the gateway's shape grows (transcript engines, notes) and
     * a new key there should not need a new APK here.
     *
     * Blocks for up to the read timeout, so it belongs on a worker thread.
     * null means the house did not answer - an ordinary state: the face still
     * shows the caller and its two buttons, and the call service is untouched.
     */
    public static String scoStatus(Context context) {
        Context app = context.getApplicationContext();
        int port = JawnLauncher.getJawnPort(app);
        String origin = "https://" + JawnLauncher.managerHost(app) + ":" + port;
        HttpURLConnection conn = null;
        try {
            String number = JawnPhoneState.freshNumber(120_000);
            String query = (number == null || number.isEmpty()) ? "" : "?number=" + Uri.encode(number);
            conn = (HttpURLConnection) new URL(origin + "/manager/sco/status" + query).openConnection();
            trustHouse(conn);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("GET");
            String cookie = presidentCookie(app, origin, port);
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    /**
     * The app's session cookie, or a fresh one minted from the saved ticket.
     * The ticket page sets the cookie on its response before its own redirect,
     * so a plain request is enough (the same way JawnAutofillService does it).
     */
    private static String presidentCookie(Context app, String origin, int port) {
        try {
            String cookie = CookieManager.getInstance().getCookie(origin + "/");
            if (cookie != null && cookie.contains("president=")) return cookie;
        } catch (Throwable ignored) { }
        HttpURLConnection conn = null;
        try {
            String ticket = JawnLauncher.getUiUrl(app, port);
            if (ticket == null || !ticket.contains("/box_office/")) return null;
            conn = (HttpURLConnection) new URL(ticket).openConnection();
            trustHouse(conn);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(4000);
            conn.getResponseCode();
            List<String> cookies = conn.getHeaderFields().get("Set-Cookie");
            if (cookies == null) return null;
            for (String raw : cookies) {
                int at = raw.indexOf("president=");
                if (at < 0) continue;
                int end = raw.indexOf(';', at);
                String value = end > 0 ? raw.substring(at, end) : raw.substring(at);
                try { CookieManager.getInstance().setCookie(origin + "/", value); } catch (Throwable ignored) { }
                return value;
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
        }
        return null;
    }

    /** The house serves its own certificate; trust it for this loopback call. */
    private static void trustHouse(HttpURLConnection conn) {
        if (!(conn instanceof HttpsURLConnection)) return;
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[] { new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String auth) { }
                public void checkServerTrusted(X509Certificate[] chain, String auth) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            } }, new SecureRandom());
            HttpsURLConnection https = (HttpsURLConnection) conn;
            https.setSSLSocketFactory(sc.getSocketFactory());
            https.setHostnameVerifier((h, session) -> true);
        } catch (Throwable ignored) { }
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
