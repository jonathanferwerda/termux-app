package com.termux.app;

import android.app.Activity;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * The native half of the Web NFC shim for the manager WebView.
 *
 * Android's WebView has no Web NFC: 'NDEFReader' in window is false there, so
 * the web app's NFC (manager.js / configure.js) would silently switch itself
 * off. This class exposes window.JawnNFC to the page and drives Android's
 * NfcAdapter (reader mode) so scan and write behave like the Web NFC the app
 * was written against - the web side presents the same NDEFReader surface over
 * this bridge (public/js/manager/manager.js, jawnNfcAdapter()).
 *
 * Reading is continuous while the view is foregrounded (reader mode is armed on
 * resume, not only when the page calls scan()), so a tag tapped anywhere in the
 * app reaches the page instead of Android's default tag viewer. A tag discovered
 * while a write is pending is written instead. The tag's NDEF records are
 * marshalled to the same shape Web NFC hands the page: {recordType, mediaType,
 * encoding, data} with data base64 (the JS side turns it back into bytes).
 */
public class JawnNfcBridge implements NfcAdapter.ReaderCallback {

    /** How the bridge pushes an event into the page (implemented by the WebView). */
    public interface Poster {
        void post(String script);
    }

    private final Activity activity;
    private final Poster poster;
    private final NfcAdapter adapter;

    private volatile boolean scanning = false;
    private volatile boolean foreground = false;
    private volatile NdefMessage pendingWrite = null;

    public JawnNfcBridge(Activity activity, Poster poster) {
        this.activity = activity;
        this.poster = poster;
        this.adapter = NfcAdapter.getDefaultAdapter(activity);
    }

    // ---- the window.JawnNFC surface (called on a binder thread) ----

    @JavascriptInterface
    public String available() {
        return (adapter != null && adapter.isEnabled()) ? "yes" : "no";
    }

    @JavascriptInterface
    public String startScan() {
        if (adapter == null || !adapter.isEnabled()) return "no";
        scanning = true;
        activity.runOnUiThread(this::enableReaderMode);
        return "ok";
    }

    @JavascriptInterface
    public String stopScan() {
        scanning = false;
        // Reader mode stays on while the view is foregrounded (see onResume):
        // a tag tapped anywhere in the app should reach the page rather than
        // Android's default tag viewer - the "magic" a browser's Web NFC gives.
        if (!foreground) activity.runOnUiThread(this::disableReaderMode);
        return "ok";
    }

    @JavascriptInterface
    public String write(String json) {
        if (adapter == null || !adapter.isEnabled()) return "no";
        try {
            JSONArray records = new JSONObject(json).optJSONArray("records");
            pendingWrite = recordsToMessage(records);
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
        activity.runOnUiThread(this::enableReaderMode);
        return "ok";
    }

    // ---- lifecycle (called by the activity on the UI thread) ----

    // The view is foregrounded: listen continuously, whether or not the page
    // has armed a scan. Android's reader mode then delivers every tag to us
    // instead of the default "new tag collected" screen.
    public void onResume() {
        foreground = true;
        enableReaderMode();
    }

    public void onPause() {
        foreground = false;
        disableReaderMode();
    }

    private void enableReaderMode() {
        if (adapter == null) return;
        int flags = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                  | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V
                  | NfcAdapter.FLAG_READER_NFC_BARCODE;
        Bundle opts = new Bundle();
        opts.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
        try { adapter.enableReaderMode(activity, this, flags, opts); } catch (Throwable ignored) { }
    }

    private void disableReaderMode() {
        if (adapter == null) return;
        try { adapter.disableReaderMode(activity); } catch (Throwable ignored) { }
    }

    // ---- tag handling ----

    @Override
    public void onTagDiscovered(Tag tag) {
        // NDEF I/O can block; keep it off the main/reader thread.
        new Thread(() -> {
            if (pendingWrite != null) {
                NdefMessage msg = pendingWrite;
                pendingWrite = null;
                String result = writeTag(tag, msg);
                if (result.startsWith("error")) {
                    String error = result.substring(6).replace("\\", "\\\\").replace("\"", "\\\"");
                    emit("write", "{\"ok\":false,\"error\":\"" + error + "\"}");
                } else {
                    emit("write", "{\"ok\":true}");
                }
                if (scanning) activity.runOnUiThread(this::enableReaderMode);
                return;
            }
            String payload = readTag(tag);
            emit("reading", payload == null ? "{}" : payload);
        }).start();
    }

    private String readTag(Tag tag) {
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) return null;
        try {
            ndef.connect();
            NdefMessage msg = ndef.getNdefMessage();
            ndef.close();
            if (msg == null) return null;
            JSONArray records = new JSONArray();
            for (NdefRecord r : msg.getRecords()) {
                JSONObject j = recordToJson(r);
                if (j != null) records.put(j);
            }
            return new JSONObject().put("serial", serialOf(tag)).put("records", records).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String writeTag(Tag tag, NdefMessage msg) {
        try {
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                ndef.connect();
                if (!ndef.isWritable()) { ndef.close(); return "error:read-only tag"; }
                ndef.writeNdefMessage(msg);
                ndef.close();
                return "ok";
            }
            NdefFormatable formatable = NdefFormatable.get(tag);
            if (formatable != null) {
                formatable.connect();
                formatable.format(msg);
                formatable.close();
                return "ok";
            }
            return "error:unsupported tag";
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    /** One NDEF record, shaped like the Web NFC record the page reads. */
    private JSONObject recordToJson(NdefRecord r) {
        try {
            short tnf = r.getTnf();
            byte[] type = r.getType();
            byte[] payload = r.getPayload();
            if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_TEXT)) {
                int langLength = payload[0] & 0x3F;
                boolean utf16 = (payload[0] & 0x80) != 0;
                byte[] text = Arrays.copyOfRange(payload, 1 + langLength, payload.length);
                return new JSONObject()
                    .put("recordType", "text")
                    .put("encoding", utf16 ? "utf-16" : "utf-8")
                    .put("data", Base64.encodeToString(text, Base64.NO_WRAP));
            }
            if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_URI)) {
                android.net.Uri uri = r.toUri();
                if (uri == null) return null;
                String uriString = uri.toString();
                return new JSONObject()
                    .put("recordType", "url")
                    .put("data", Base64.encodeToString(uriString.getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP));
            }
            if (tnf == NdefRecord.TNF_MIME_MEDIA) {
                String mime = r.toMimeType();
                return new JSONObject()
                    .put("recordType", "mime")
                    .put("mediaType", mime == null ? "application/octet-stream" : mime)
                    .put("data", Base64.encodeToString(payload, Base64.NO_WRAP));
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private NdefMessage recordsToMessage(JSONArray records) throws Exception {
        NdefRecord[] out = new NdefRecord[records.length()];
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.getJSONObject(i);
            String recordType = r.optString("recordType", "");
            byte[] data = Base64.decode(r.optString("data", ""), Base64.DEFAULT);
            if ("text".equals(recordType)) {
                out[i] = NdefRecord.createTextRecord("en", new String(data, Charset.forName("UTF-8")));
            } else if ("url".equals(recordType)) {
                out[i] = NdefRecord.createUri(new String(data, Charset.forName("UTF-8")));
            } else {
                String mediaType = r.optString("mediaType", "application/octet-stream");
                out[i] = NdefRecord.createMime(mediaType, data);
            }
        }
        return new NdefMessage(out);
    }

    private String serialOf(Tag tag) {
        byte[] id = tag.getId();
        if (id == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : id) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void emit(String type, String json) {
        poster.post("window.__jawnNfcEvent && window.__jawnNfcEvent('" + type + "'," + json + ");");
    }
}
