package com.termux.app;

import android.app.assist.AssistStructure;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.text.InputType;
import android.view.View;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.webkit.CookieManager;
import android.widget.RemoteViews;

import com.termux.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The vault as Android's autofill service.
 *
 * <p>An input method only sees the field that has focus, so the keyboard can save
 * or fill one field at a time. This service is the other half: Android hands it
 * the whole screen's form (including a web page's domain), so it can offer one
 * dataset that puts the username and password into every matching field at once
 * - in Chrome and any other app.
 *
 * <p>The data lives in the manager's encrypted vault. The service asks the
 * manager over loopback with the app's session cookie; a locked house answers
 * locked, and no dataset is offered. No secret is written to a log or to disk
 * here.
 */
public class JawnAutofillService extends AutofillService {

    private static final String TAG = "JawnAutofill";

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancellationSignal,
                              FillCallback callback) {
        if (request == null || request.getFillContexts() == null || request.getFillContexts().isEmpty()) {
            callback.onSuccess(null);
            return;
        }
        AssistStructure structure = request.getFillContexts()
            .get(request.getFillContexts().size() - 1).getStructure();
        final Screen screen = read(structure);
        if (screen.fields.isEmpty()) { callback.onSuccess(null); return; }

        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                FillResponse response = null;
                try {
                    response = build(screen);
                } catch (Throwable t) {
                    response = null;
                }
                final FillResponse answer = response;
                if (cancellationSignal != null && cancellationSignal.isCanceled()) { return; }
                main.post(new Runnable() {
                    @Override
                    public void run() { callback.onSuccess(answer); }
                });
            }
        }).start();
    }

    // ---- the screen ---------------------------------------------------------

    private static class Field {
        AutofillId id;
        String hint = "";
        String entry = "";
        String[] hints;
        String value = "";
        int variation;
        boolean password;
        boolean user;
        boolean web;
    }

    private static class Screen {
        String pkg = "";
        String domain = "";
        final List<Field> fields = new ArrayList<>();
    }

    private Screen read(AssistStructure structure) {
        Screen screen = new Screen();
        if (structure == null) { return screen; }
        try {
            if (structure.getActivityComponent() != null) {
                screen.pkg = structure.getActivityComponent().getPackageName();
            }
        } catch (Throwable ignored) { }
        for (int w = 0; w < structure.getWindowNodeCount(); w++) {
            walk(structure.getWindowNodeAt(w).getRootViewNode(), screen, "");
        }
        return screen;
    }

    private void walk(AssistStructure.ViewNode node, Screen screen, String domain) {
        if (node == null) { return; }
        String ownDomain = null;
        try { ownDomain = node.getWebDomain(); } catch (Throwable ignored) { }
        if (ownDomain != null && ownDomain.length() > 0) { domain = ownDomain; }
        try {
            AutofillId id = node.getAutofillId();
            int type = node.getAutofillType();
            if (id != null && (type == View.AUTOFILL_TYPE_TEXT || type == View.AUTOFILL_TYPE_NONE)) {
                Field field = new Field();
                field.id = id;
                field.hint = node.getHint() == null ? "" : node.getHint().toString();
                field.entry = node.getIdEntry() == null ? "" : node.getIdEntry();
                field.hints = node.getAutofillHints();
                int inputType = node.getInputType();
                field.variation = inputType & InputType.TYPE_MASK_VARIATION;
                field.password = isPassword(field);
                field.user = isUser(field);
                field.web = domain.length() > 0;
                AutofillValue current = node.getAutofillValue();
                if (current != null && current.isText()) {
                    field.value = current.getTextValue() == null ? "" : current.getTextValue().toString();
                }
                if (field.password || field.user || field.hint.length() > 0 || field.entry.length() > 0) {
                    screen.fields.add(field);
                    if (screen.domain.length() == 0 && domain.length() > 0) { screen.domain = domain; }
                }
            }
        } catch (Throwable ignored) { }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChildAt(i), screen, domain);
        }
    }

    private static boolean isPassword(Field f) {
        if (f.variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
            || f.variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            || f.variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            || f.variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) { return true; }
        return hasHint(f, "password") || hasHint(f, "current-password") || hasHint(f, "new-password")
            || contains(f.hint + " " + f.entry, "pass");
    }

    private static boolean isUser(Field f) {
        return hasHint(f, "username") || hasHint(f, "email") || hasHint(f, "emailaddress")
            || contains(f.hint + " " + f.entry, "user") || contains(f.hint + " " + f.entry, "email")
            || contains(f.hint + " " + f.entry, "login");
    }

    private static boolean hasHint(Field f, String want) {
        if (f.hints == null) { return false; }
        for (String h : f.hints) {
            if (h != null && h.equalsIgnoreCase(want)) { return true; }
        }
        return false;
    }

    private static boolean contains(String text, String want) {
        return text != null && text.toLowerCase().contains(want);
    }

    // ---- the manager --------------------------------------------------------

    private FillResponse build(Screen screen) {
        Set<String> hints = new LinkedHashSet<>();
        boolean password = false;
        // A WebView page's structure also contains the browser's own toolbar
        // (address bar, back, reload). Those native fields have no web domain;
        // filling them would put a password in the address bar. When the screen
        // names a web domain, only the page's own fields take part.
        List<Field> active = new ArrayList<>();
        for (Field f : screen.fields) {
            if (screen.domain.length() == 0 || f.web) { active.add(f); }
        }
        for (Field f : active) {
            if (f.password) { password = true; hints.add("password"); }
            if (f.user) { hints.add("username"); }
            if (f.hint.length() > 0) { hints.add(f.hint.toLowerCase()); }
            if (f.entry.length() > 0) { hints.add(f.entry.toLowerCase()); }
            if (f.hints != null) {
                for (String h : f.hints) { if (h != null && h.length() > 0) { hints.add(h.toLowerCase()); } }
            }
        }
        if (!password) { return null; }
        try {
            JSONArray hintArray = new JSONArray();
            for (String h : hints) { hintArray.put(h); }
            String status = request("/credentials/status?app=" + enc(screen.pkg)
                + "&origin=" + enc(screen.domain) + "&hints=" + enc(hintArray.toString()), null);
            if (status == null) { return null; }
            JSONObject answer = new JSONObject(status);
            if (answer.optInt("locked", 0) == 1 || answer.optInt("ok", 0) != 1) { return null; }
            JSONArray matches = answer.optJSONArray("matches");
            FillResponse.Builder response = new FillResponse.Builder();
            int added = 0;
            for (int i = 0; matches != null && i < matches.length() && added < 3; i++) {
                JSONObject match = matches.optJSONObject(i);
                if (match == null) { continue; }
                String uuid = match.optString("uuid", "");
                if (uuid.length() == 0) { continue; }
                String filled = request("/credentials/fill", "uuid=" + enc(uuid));
                if (filled == null) { continue; }
                JSONObject fields = new JSONObject(filled);
                if (fields.optInt("ok", 0) != 1) { continue; }
                JSONObject values = fields.optJSONObject("fields");
                if (values == null) { continue; }
                JSONObject map = values.optJSONObject("fields");
                String username = values.optString("username", "");
                String secret = values.optString("password", "");
                String value = values.optString("value", "");
                RemoteViews presentation = presentation(match.optString("label", "JawnOS"),
                    screen.domain.length() > 0 ? screen.domain : screen.pkg);
                Dataset.Builder dataset = new Dataset.Builder(presentation);
                boolean filledOne = false;
                for (Field f : active) {
                    String put = null;
                    if (f.password) { put = secret; }
                    else if (f.user) { put = username; }
                    if (put == null || put.length() == 0) {
                        if (map != null) {
                            if (f.entry.length() > 0) { put = map.optString(f.entry, null); }
                            if ((put == null || put.length() == 0) && f.hint.length() > 0) {
                                put = map.optString(f.hint, null);
                            }
                        }
                    }
                    if ((put == null || put.length() == 0) && active.size() == 1) { put = value; }
                    if (put != null && put.length() > 0) {
                        dataset.setValue(f.id, AutofillValue.forText(put));
                        filledOne = true;
                    }
                }
                if (filledOne) {
                    response.addDataset(dataset.build());
                    added++;
                }
            }
            // Ask Android for its save prompt when a password field is submitted.
            // A response may carry SaveInfo alone: that is what lets a WebView app
            // file a password for a site the vault has not seen yet. Chrome keeps
            // its own password manager, so this half is exercised by an app whose
            // WebView uses the system autofill framework.
            List<AutofillId> passwordIds = new ArrayList<>();
            List<AutofillId> usernameIds = new ArrayList<>();
            for (Field f : active) {
                if (f.password) { passwordIds.add(f.id); }
                else if (f.user) { usernameIds.add(f.id); }
            }
            if (!passwordIds.isEmpty()) {
                SaveInfo.Builder save = new SaveInfo.Builder(
                    SaveInfo.SAVE_DATA_TYPE_PASSWORD, passwordIds.toArray(new AutofillId[0]));
                if (!usernameIds.isEmpty()) {
                    save.setOptionalIds(usernameIds.toArray(new AutofillId[0]));
                }
                response.setSaveInfo(save.build());
            }
            if (added == 0 && passwordIds.isEmpty()) { return null; }
            return response.build();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Android's save prompt: the submitted form holds the values, so a new
     * password is saved without the keyboard's sheet (with the site the browser
     * reports). A locked house, or one with no vault, refuses it.
     */
    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        if (request == null || request.getFillContexts() == null || request.getFillContexts().isEmpty()) {
            callback.onSuccess();
            return;
        }
        AssistStructure structure = request.getFillContexts()
            .get(request.getFillContexts().size() - 1).getStructure();
        final Screen screen = read(structure);
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String username = "";
                    String password = "";
                    Set<String> hints = new LinkedHashSet<>();
                    for (Field f : screen.fields) {
                        if (screen.domain.length() > 0 && !f.web) { continue; }
                        if (f.password) { password = f.value; hints.add("password"); }
                        if (f.user) { username = f.value; hints.add("username"); }
                        if (f.hint.length() > 0) { hints.add(f.hint.toLowerCase()); }
                        if (f.entry.length() > 0) { hints.add(f.entry.toLowerCase()); }
                    }
                    if (password.length() > 0) {
                        JSONArray hintArray = new JSONArray();
                        for (String h : hints) { hintArray.put(h); }
                        String label = screen.domain.length() > 0 ? screen.domain : screen.pkg;
                        String form = "app=" + enc(screen.pkg)
                            + "&origin=" + enc(screen.domain)
                            + "&label=" + enc(label)
                            + "&password=" + enc(password)
                            + "&hints=" + enc(hintArray.toString());
                        if (username.length() > 0) { form += "&username=" + enc(username); }
                        request("/credentials/save", form);
                    }
                } catch (Throwable ignored) { }
                main.post(new Runnable() {
                    @Override
                    public void run() { callback.onSuccess(); }
                });
            }
        }).start();
    }

    private RemoteViews presentation(String label, String subtitle) {
        RemoteViews views = new RemoteViews(getPackageName(), R.layout.autofill_dataset);
        views.setImageViewResource(R.id.autofill_icon, R.mipmap.ic_launcher);
        views.setTextViewText(R.id.autofill_title, label == null || label.length() == 0 ? "JawnOS" : label);
        views.setTextViewText(R.id.autofill_subtitle, subtitle == null ? "" : subtitle);
        return views;
    }

    /** A GET, or a form POST when `form` is given, on the manager's loopback. */
    private String request(String path, String form) {
        HttpURLConnection connection = null;
        try {
            int port = JawnLauncher.getJawnPort(this);
            String host = JawnLauncher.managerHost(this);
            URL url = new URL("https://" + host + ":" + port + "/manager" + path);
            connection = (HttpURLConnection) url.openConnection();
            if (connection instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) connection;
                // the house's own certificate, the same one the app's WebView trusts
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] { new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                    public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                } }, null);
                https.setSSLSocketFactory(context.getSocketFactory());
                https.setHostnameVerifier((hostName, session) -> true);
            }
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(4000);
            connection.setRequestProperty("Accept", "application/json");
            String cookie = cookie(host, port);
            if (cookie != null && cookie.length() > 0) {
                connection.setRequestProperty("Cookie", cookie);
            }
            if (form != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream out = connection.getOutputStream();
                out.write(form.getBytes("UTF-8"));
                out.close();
            }
            int code = connection.getResponseCode();
            if (code != 200) { return null; }
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) { body.append(line); }
            reader.close();
            return body.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (connection != null) { connection.disconnect(); }
        }
    }

    /**
     * The app's session cookie, or a fresh one minted from the saved ticket. The
     * ticket page sets the cookie on its response before its own redirect, so a
     * plain request is enough; nothing here follows the redirect into a browser.
     */
    private String cookie(String host, int port) {
        try {
            String cookie = CookieManager.getInstance().getCookie("https://" + host + ":" + port + "/");
            if (cookie != null && cookie.contains("president=")) { return cookie; }
        } catch (Throwable ignored) { }
        HttpURLConnection connection = null;
        try {
            String ticket = JawnLauncher.getUiUrl(this, port);
            if (ticket == null || !ticket.contains("/box_office/")) { return null; }
            URL url = new URL(ticket);
            connection = (HttpURLConnection) url.openConnection();
            if (connection instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) connection;
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] { new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                    public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                } }, null);
                https.setSSLSocketFactory(context.getSocketFactory());
                https.setHostnameVerifier((hostName, session) -> true);
            }
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(4000);
            connection.getResponseCode();
            List<String> cookies = connection.getHeaderFields().get("Set-Cookie");
            if (cookies == null) { return null; }
            for (String raw : cookies) {
                int at = raw.indexOf("president=");
                if (at >= 0) {
                    int end = raw.indexOf(';', at);
                    String value = end > 0 ? raw.substring(at, end) : raw.substring(at);
                    CookieManager.getInstance().setCookie("https://" + host + ":" + port + "/", value);
                    return value;
                }
            }
        } catch (Throwable ignored) { }
        finally {
            if (connection != null) { connection.disconnect(); }
        }
        return null;
    }

    private static String enc(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Throwable t) { return ""; }
    }
}
