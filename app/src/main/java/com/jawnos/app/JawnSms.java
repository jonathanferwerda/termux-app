package com.jawnos.app;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.webkit.JavascriptInterface;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.klinker.android.send_message.Message;
import com.klinker.android.send_message.Settings;
import com.klinker.android.send_message.Transaction;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;

/**
 * The app's messaging: SMS and MMS from the house, off the termux-api.
 *
 * The page is exposed window.JawnSms; a send comes back over
 * window.__jawnSmsResult, which the web side posts to /manager/sms/result.
 * SMS goes through SmsManager (any app with SEND_SMS may send). MMS goes
 * through the klinkerapps send-message library, which writes the system MMS
 * provider - on KitKat+ only the default SMS app may do that, so mms() answers
 * "not the default sms app" otherwise and share() hands the OS messaging app
 * the file instead.
 */
public class JawnSms {

    /** How the bridge pushes a result into the page (implemented by the WebView). */
    public interface Poster {
        void post(String script);
    }

    private static final String AUTHORITY_SUFFIX = ".jawnupdates";

    private final Activity activity;
    private final Poster poster;

    public JawnSms(Activity activity, Poster poster) {
        this.activity = activity;
        this.poster = poster;
    }

    // ---- the window.JawnSms surface (called on a binder thread) ----

    @JavascriptInterface
    public String available() {
        return "yes";
    }

    @JavascriptInterface
    public String isDefaultSms() {
        return isDefaultSmsApp() ? "yes" : "no";
    }

    /** Send a plain SMS; long bodies split into multipart. Answers via __jawnSmsResult. */
    @JavascriptInterface
    public String send(final String digits, final String message) {
        activity.runOnUiThread(() -> sendOnUi(digits, message));
        return "ok";
    }

    /**
     * Send an MMS. text is the message's own words (a text part inside it, not
     * a subject), parts is a JSON array of {path, mime} - absolute paths the app
     * can read (the house's storage lives in this app's own termux home).
     * Answers via __jawnSmsResult.
     */
    @JavascriptInterface
    public String mms(final String digits, final String text, final String partsJson, final String jawnUuid) {
        activity.runOnUiThread(() -> mmsOnUi(digits, text, partsJson, jawnUuid));
        return "ok";
    }

    /**
     * The fallback when the app is not the default SMS app: hand the OS
     * messaging app the file and the address, and let the user press send.
     * Answers via __jawnSmsResult ("shared").
     */
    @JavascriptInterface
    public String share(final String digits, final String message, final String path, final String mime) {
        activity.runOnUiThread(() -> shareOnUi(digits, message, path, mime));
        return "ok";
    }

    // ---- SMS ----

    private void sendOnUi(String digits, String message) {
        String id = nextId();
        if (digits == null || digits.trim().isEmpty() || message == null || message.isEmpty()) {
            emit("{\"ok\":false,\"error\":\"no number or message\",\"id\":" + js(id) + "}");
            return;
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            emit("{\"ok\":false,\"error\":\"send sms permission missing\",\"id\":" + js(id) + "}");
            return;
        }
        try {
            SmsManager sm = SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(message);
            ArrayList<PendingIntent> sent = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) { sent.add(sentIntent(id)); }
            sm.sendMultipartTextMessage(digits.trim(), null, parts, sent, null);
            // the result arrives from the broadcast, not here
        } catch (Throwable t) {
            emit("{\"ok\":false,\"error\":" + js(String.valueOf(t.getMessage())) + ",\"id\":" + js(id) + "}");
        }
    }

    private PendingIntent sentIntent(String id) {
        Intent i = new Intent("com.jawnos.app.JAWN_SMS_SENT");
        i.putExtra("jawn_sms_id", id);
        i.setPackage(activity.getPackageName());
        return PendingIntent.getBroadcast(activity, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---- MMS ----

    private void mmsOnUi(String digits, String text, String partsJson, String jawnUuid) {
        String id = nextId();
        if (!isDefaultSmsApp()) {
            emit("{\"ok\":false,\"error\":\"not the default sms app\",\"id\":" + js(id) + "}");
            return;
        }
        try {
            JSONArray arr = new JSONArray(partsJson == null ? "[]" : partsJson);
            Settings settings = new Settings();
            settings.setUseSystemSending(true);
            Transaction transaction = new Transaction(activity, settings);
            // the words are the message's text part, not its subject: an mms
            // with a subject and a separate sms for the same words put two
            // bubbles in the system thread for one thing the person sent
            Message message = new Message(text == null ? "" : text, digits == null ? "" : digits.trim());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject part = arr.optJSONObject(i);
                if (part == null) continue;
                String path = part.optString("path", "");
                String mime = part.optString("mime", "");
                if (mime == null || mime.isEmpty()) mime = "image/jpeg";
                if (path.isEmpty() || !new File(path).exists()) continue;
                byte[] bytes = readBytes(new File(path));
                if (bytes == null) continue;
                if (mime.startsWith("image/")) {
                    android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(path);
                    if (bm != null) { message.addImage(bm); continue; }
                    message.addMedia(bytes, mime);
                }
                else if (mime.startsWith("audio/")) { message.addAudio(bytes, mime); }
                else if (mime.startsWith("video/")) { message.addVideo(bytes, mime); }
                else { message.addMedia(bytes, mime); }
            }
            // the transaction reports through its own listener on its thread
            transaction.sendNewMessage(message, id.hashCode());
            // The library persists an outbox row before handing the PDU to the
            // system MMS service and the provider drops that row once the send
            // lands, so the thread would lose the picture. Copy it to a
            // persistent sent row first - that row is the system thread's own
            // copy, and its id is what the house row wears too.
            long[] saved = copyOutboxToSent(digits);
            emit("{\"ok\":true,\"via\":\"mms\",\"id\":" + js(id)
                + ",\"mms_id\":" + saved[0] + ",\"mms_date\":" + saved[2]
                + ",\"mms_thread\":" + saved[1] + ",\"jawn_uuid\":" + js(jawnUuid) + "}");
        } catch (Throwable t) {
            emit("{\"ok\":false,\"error\":" + js(String.valueOf(t.getMessage())) + ",\"id\":" + js(id) + "}");
        }
    }

    /**
     * Copy the library's outbox row to a persistent sent MMS row in the stock
     * thread. Returns {id, threadId, date} - or {-1, 0, 0} when there was no
     * outbox row to copy (the id then says the house row has no system twin).
     */
    private long[] copyOutboxToSent(String digits) {
        ContentResolver cr = activity.getContentResolver();
        Cursor c = null;
        try {
            Uri outbox = Uri.parse("content://mms/outbox");
            c = cr.query(outbox, null, null, null, "_id DESC");
            if (c == null || !c.moveToFirst()) return new long[] { -1, 0, 0 };
            long oldId = c.getLong(c.getColumnIndexOrThrow("_id"));
            ContentValues v = new ContentValues();
            // the row's own envelope, so other messaging apps read it the way
            // they read any sent mms (the columns the provider rejects are not
            // in this list; _id and the lifecycle fields are set below)
            String[] keys = new String[] {
                "sub_id", "thread_id", "sub", "sub_cs",
                "ct_t", "ct_l", "exp", "m_cls", "m_type", "m_size",
                "pri", "rr", "rpt_a", "resp_st", "tr_id", "d_rpt", "text_only"
            };
            for (String key : keys) {
                int idx = c.getColumnIndex(key);
                if (idx >= 0 && !c.isNull(idx)) v.put(key, c.getString(idx));
            }
            long threadId = Telephony.Threads.getOrCreateThreadId(activity, digits == null ? "" : digits);
            int tid = c.getColumnIndex("thread_id");
            if (tid >= 0 && !c.isNull(tid)) threadId = c.getLong(tid);
            long now = System.currentTimeMillis() / 1000;
            long date = now;
            int didx = c.getColumnIndex("date");
            if (didx >= 0 && !c.isNull(didx)) {
                try { date = c.getLong(didx); } catch (Throwable ignored) { }
            }
            if (date <= 0) date = now;
            v.put("thread_id", threadId);
            v.put(Telephony.Mms.DATE, date);
            v.put(Telephony.Mms.DATE_SENT, now);
            v.put(Telephony.Mms.MESSAGE_BOX, 2);
            v.put(Telephony.Mms.READ, 1);
            v.put(Telephony.Mms.SEEN, 1);
            v.put("creator", activity.getPackageName());
            // the send request's own id is what a reader uses to recognise the
            // message; the outbox row has one, but a fresh one is better than
            // none when it does not
            String mid = v.getAsString("m_id");
            if (mid == null || mid.isEmpty()) v.put("m_id", java.util.UUID.randomUUID().toString());
            Uri sent = cr.insert(Telephony.Mms.CONTENT_URI, v);
            c.close();
            c = null;
            if (sent == null) return new long[] { -1, 0, 0 };
            long newId = ContentUris.parseId(sent);
            Uri oldBase = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, oldId);
            Uri newBase = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, newId);
            copyAddresses(cr, Uri.withAppendedPath(oldBase, "addr"), Uri.withAppendedPath(newBase, "addr"));
            copyParts(cr, Uri.withAppendedPath(oldBase, "part"), Uri.withAppendedPath(newBase, "part"));
            return new long[] { newId, threadId, date };
        } catch (Throwable t) {
            android.util.Log.e("JawnSms", "copy outbox to sent: " + t);
            return new long[] { -1, 0, 0 };
        } finally {
            if (c != null) c.close();
        }
    }

    private void copyAddresses(ContentResolver cr, Uri from, Uri to) {
        Cursor c = cr.query(from, null, null, null, null);
        if (c == null) return;
        try {
            while (c.moveToNext()) {
                ContentValues v = new ContentValues();
                for (int i = 0; i < c.getColumnCount(); i++) {
                    String name = c.getColumnName(i);
                    if (c.isNull(i) || "_id".equals(name)) continue;
                    v.put(name, c.getString(i));
                }
                cr.insert(to, v);
            }
        } finally { c.close(); }
    }

    private void copyParts(ContentResolver cr, Uri from, Uri to) {
        Cursor c = cr.query(from, null, null, null, null);
        if (c == null) return;
        try {
            while (c.moveToNext()) {
                ContentValues v = new ContentValues();
                String data = null;
                for (int i = 0; i < c.getColumnCount(); i++) {
                    String name = c.getColumnName(i);
                    if (c.isNull(i) || "_id".equals(name)) continue;
                    if ("_data".equals(name)) { data = c.getString(i); continue; }
                    v.put(name, c.getString(i));
                }
                if (data != null) {
                    File src = new File(data);
                    if (src.exists()) {
                        File dst = File.createTempFile("jawn-mms-", ".part", activity.getCacheDir());
                        copyFile(src, dst);
                        v.put("_data", dst.getAbsolutePath());
                    }
                }
                cr.insert(to, v);
            }
        } catch (Throwable t) {
            android.util.Log.e("JawnSms", "copy mms parts: " + t);
        } finally { c.close(); }
    }

    private void copyFile(File from, File to) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(from);
        java.io.FileOutputStream out = new java.io.FileOutputStream(to);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    // ---- share (the OS messaging app sends it) ----

    private void shareOnUi(String digits, String message, String path, String mime) {
        String id = nextId();
        try {
            File f = new File(path);
            if (!f.exists()) { throw new IllegalArgumentException("no file at " + path); }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime == null || mime.isEmpty() ? "image/*" : mime);
            send.putExtra(Intent.EXTRA_STREAM, uriFor(providerFile(f)));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (digits != null && !digits.trim().isEmpty()) {
                send.putExtra("address", digits.trim());
            }
            if (message != null && !message.isEmpty()) {
                send.putExtra(Intent.EXTRA_TEXT, message);
            }
            Intent chooser = Intent.createChooser(send, "Send with");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(chooser);
            emit("{\"ok\":true,\"via\":\"share\",\"id\":" + js(id) + "}");
        } catch (Throwable t) {
            emit("{\"ok\":false,\"error\":" + js(String.valueOf(t.getMessage())) + ",\"id\":" + js(id) + "}");
        }
    }

    // ---- the sent broadcast ----

    private boolean receiverRegistered = false;

    private void ensureReceiver() {
        if (receiverRegistered) return;
        receiverRegistered = true;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String id = intent.getStringExtra("jawn_sms_id");
                    int code = getResultCode();
                    boolean ok = code == Activity.RESULT_OK;
                    String error = null;
                    if (!ok) { error = "sms send failed (" + code + ")"; }
                    emit("{\"ok\":" + ok + ",\"error\":" + js(error) + ",\"id\":" + js(id) + "}");
                }
            };
            IntentFilter f = new IntentFilter("com.jawnos.app.JAWN_SMS_SENT");
            activity.registerReceiver(r, f);
        } catch (Throwable ignored) {}
    }

    /** The file as bytes, or null when it is missing or too big to hand over. */
    private byte[] readBytes(File f) {
        try {
            long len = f.length();
            if (len <= 0 || len > 8L * 1024 * 1024) return null;
            byte[] buf = new byte[(int) len];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            return buf;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isDefaultSmsApp() {
        // Android 16 no longer reports the SMS role through the deprecated
        // Telephony.Sms.getDefaultSmsPackage() - it answered null while the role
        // was held - so ask the RoleManager first, and keep the old call as the
        // fallback for devices below Android 10.
        if (Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager rm =
                (android.app.role.RoleManager) activity.getSystemService(Context.ROLE_SERVICE);
            if (rm != null) {
                return rm.isRoleHeld(android.app.role.RoleManager.ROLE_SMS);
            }
        }
        if (Build.VERSION.SDK_INT >= 19) {
            String def = Telephony.Sms.getDefaultSmsPackage(activity);
            return def != null && def.equals(activity.getPackageName());
        }
        return true;
    }

    /**
     * The file spelled the way the FileProvider's <files-path> root sees it.
     * TermuxConstants and subs::home() say /data/data/... while getFilesDir()
     * is /data/user/0/..., and a path outside that root makes getUriForFile
     * throw "Failed to find configured root".
     */
    private File providerFile(File f) {
        String termuxFiles = "/data/data/" + activity.getPackageName() + "/files";
        String abs = f.getAbsolutePath();
        if (abs.startsWith(termuxFiles + "/")) {
            return new File(activity.getFilesDir(), abs.substring(termuxFiles.length() + 1));
        }
        return f;
    }

    private Uri uriFor(File f) {
        return FileProvider.getUriForFile(activity, activity.getPackageName() + AUTHORITY_SUFFIX, f);
    }

    private String nextId() {
        ensureReceiver();
        return "sms" + System.currentTimeMillis();
    }

    private void emit(String json) {
        poster.post("window.__jawnSmsResult && window.__jawnSmsResult(" + json + ");");
    }

    private static String js(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
