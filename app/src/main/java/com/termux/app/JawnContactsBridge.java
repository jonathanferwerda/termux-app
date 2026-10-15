package com.termux.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The native half of the phone/contacts surface for the manager WebView.
 *
 * Exposes window.JawnContacts to the page: contacts (list/search), the call
 * log, and live call-state events (RINGING / OFFHOOK / IDLE) through
 * window.__jawnPhoneEvent. Read-only, no system role: the three READ_* runtime
 * permissions are asked for when the page calls request() (or granted by hand
 * with `pm grant` on a sideloaded device).
 *
 * Call-state note: on Android 12+ the number is only reliable once the call has
 * reached the call log (incoming caller identity is withheld from plain
 * PhoneStateListener without the dialer or call-screening role). The state
 * events still fire at ring/hangup, so an appointment can start/stop on time;
 * the number/contact is filled from the call log straight after.
 */
public class JawnContactsBridge {

    /** How the bridge pushes an event into the page (implemented by the WebView). */
    public interface Poster {
        void post(String script);
    }

    public static final int REQ_PERMS = 7004;
    public static final int REQ_SCREENING = 7005;

    private static final String[] PERMS = {
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE
    };

    private final Activity activity;
    private final Poster poster;
    private final TelephonyManager telephony;
    private PhoneStateListener callListener;
    private boolean foreground = false;

    public JawnContactsBridge(Activity activity, Poster poster) {
        this.activity = activity;
        this.poster = poster;
        this.telephony = (TelephonyManager) activity.getSystemService(Activity.TELEPHONY_SERVICE);
    }

    // ---- the window.JawnContacts surface (called on a binder thread) ----

    @JavascriptInterface
    public String available() {
        return hasPerms() ? "yes" : "no";
    }

    @JavascriptInterface
    public void request() {
        if (hasPerms()) { emitContacts("permission", "{\"granted\":true}"); return; }
        activity.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 23) activity.requestPermissions(PERMS, REQ_PERMS);
        });
    }

    @JavascriptInterface
    public String list(String query) {
        if (!hasPerms()) return "{\"error\":\"no-permission\"}";
        return contactsJson(query == null ? "" : query.trim());
    }

    @JavascriptInterface
    public String callLog(int limit) {
        if (!hasPerms()) return "{\"error\":\"no-permission\"}";
        return callLogJson(limit);
    }

    @JavascriptInterface
    public String incomingNumber() {
        return JawnPhoneState.freshNumber(15000);
    }

    @JavascriptInterface
    public String screeningRole() {
        if (Build.VERSION.SDK_INT < 29) return "no";
        try {
            android.app.role.RoleManager rm = (android.app.role.RoleManager) activity.getSystemService(android.content.Context.ROLE_SERVICE);
            return (rm != null && rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)) ? "yes" : "no";
        } catch (Throwable t) { return "no"; }
    }

    @JavascriptInterface
    public void requestScreening() {
        if (Build.VERSION.SDK_INT < 29) return;
        activity.runOnUiThread(() -> {
            try {
                android.app.role.RoleManager rm = (android.app.role.RoleManager) activity.getSystemService(android.content.Context.ROLE_SERVICE);
                if (rm == null) return;
                if (rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)) { emitContacts("screening", "{\"held\":true}"); return; }
                activity.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING), REQ_SCREENING);
            } catch (Throwable ignored) { }
        });
    }

    // ---- lifecycle (called by the activity on the UI thread) ----

    public void onResume() {
        foreground = true;
        registerCallListener();
    }

    public void onPause() {
        foreground = false;
        unregisterCallListener();
    }

    public void onPermissionResult() {
        emitContacts("permission", hasPerms() ? "{\"granted\":true}" : "{\"granted\":false}");
        if (hasPerms() && foreground) registerCallListener();
    }

    // ---- internals ----

    private boolean hasPerms() {
        if (Build.VERSION.SDK_INT < 23) return true;
        for (String p : PERMS) {
            if (activity.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void registerCallListener() {
        if (telephony == null || !hasPerms()) return;
        unregisterCallListener();
        PhoneStateListener l = new PhoneStateListener() {
            @Override public void onCallStateChanged(int state, String phoneNumber) {
                emitCallState(state, phoneNumber == null ? "" : phoneNumber);
            }
        };
        callListener = l;
        try { telephony.listen(l, PhoneStateListener.LISTEN_CALL_STATE); } catch (Throwable ignored) { }
    }

    private void unregisterCallListener() {
        if (telephony != null && callListener != null) {
            try { telephony.listen(callListener, PhoneStateListener.LISTEN_NONE); } catch (Throwable ignored) { }
        }
        callListener = null;
    }

    private String contactsJson(String query) {
        JSONArray out = new JSONArray();
        ContentResolver cr = activity.getContentResolver();
        String[] projection = {
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        };
        String selection = null;
        String[] args = null;
        if (!query.isEmpty()) {
            selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? OR "
                      + ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?";
            args = new String[]{ "%" + query + "%", "%" + query + "%" };
        }
        Cursor c = null;
        try {
            c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, selection, args,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC");
            if (c == null) return "{\"contacts\":[]}";
            LinkedHashMap<Long, JSONObject> byId = new LinkedHashMap<>();
            LinkedHashMap<Long, JSONArray> numbers = new LinkedHashMap<>();
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                String number = c.getString(2);
                int type = c.getInt(3);
                JSONObject contact = byId.get(id);
                if (contact == null) {
                    contact = new JSONObject();
                    contact.put("id", String.valueOf(id));
                    contact.put("name", name == null ? "" : name);
                    byId.put(id, contact);
                    numbers.put(id, new JSONArray());
                }
                JSONObject n = new JSONObject();
                n.put("type", phoneType(type));
                n.put("number", number == null ? "" : number);
                numbers.get(id).put(n);
            }
            for (Map.Entry<Long, JSONObject> e : byId.entrySet()) {
                e.getValue().put("numbers", numbers.get(e.getKey()));
                out.put(e.getValue());
            }
            return new JSONObject().put("contacts", out).toString();
        } catch (Exception e) {
            return "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}";
        } finally {
            if (c != null) c.close();
        }
    }

    private String callLogJson(int limit) {
        if (limit <= 0 || limit > 500) limit = 100;
        JSONArray out = new JSONArray();
        ContentResolver cr = activity.getContentResolver();
        Cursor c = null;
        try {
            c = cr.query(CallLog.Calls.CONTENT_URI,
                new String[]{
                    CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE,
                    CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME
                },
                null, null, CallLog.Calls.DATE + " DESC");
            if (c == null) return "{\"calls\":[]}";
            int i = 0;
            while (c.moveToNext() && i < limit) {
                JSONObject o = new JSONObject();
                o.put("number", c.getString(0) == null ? "" : c.getString(0));
                o.put("type", callType(c.getInt(1)));
                o.put("date", c.getLong(2));
                o.put("duration", c.getLong(3));
                o.put("name", c.getString(4) == null ? "" : c.getString(4));
                out.put(o);
                i++;
            }
            return new JSONObject().put("calls", out).toString();
        } catch (Exception e) {
            return "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}";
        } finally {
            if (c != null) c.close();
        }
    }

    private void emitCallState(int state, String number) {
        String s;
        if (state == TelephonyManager.CALL_STATE_RINGING) s = "RINGING";
        else if (state == TelephonyManager.CALL_STATE_OFFHOOK) s = "OFFHOOK";
        else s = "IDLE";
        if (number == null || number.isEmpty()) {
            String fresh = JawnPhoneState.freshNumber(15000);
            if (!fresh.isEmpty()) number = fresh;
        }
        try {
            JSONObject o = new JSONObject().put("state", s).put("number", number == null ? "" : number);
            poster.post("window.__jawnPhoneEvent && window.__jawnPhoneEvent('state'," + o.toString() + ");");
        } catch (Throwable ignored) { }
    }

    private void emitContacts(String type, String json) {
        poster.post("window.__jawnContactsEvent && window.__jawnContactsEvent('" + type + "'," + json + ");");
    }

    private static String phoneType(int t) {
        switch (t) {
            case ContactsContract.CommonDataKinds.Phone.TYPE_HOME: return "home";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE: return "mobile";
            case ContactsContract.CommonDataKinds.Phone.TYPE_WORK: return "work";
            default: return "other";
        }
    }

    private static String callType(int t) {
        switch (t) {
            case CallLog.Calls.INCOMING_TYPE: return "incoming";
            case CallLog.Calls.OUTGOING_TYPE: return "outgoing";
            case CallLog.Calls.MISSED_TYPE: return "missed";
            case CallLog.Calls.REJECTED_TYPE: return "rejected";
            case CallLog.Calls.BLOCKED_TYPE: return "blocked";
            case CallLog.Calls.VOICEMAIL_TYPE: return "voicemail";
            default: return "other";
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
