package com.jawnos.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * {@code window.JawnNetwork}: the house's view of the device's networking.
 *
 * The manager's Network app asks the phone/tablet itself, because only the
 * device knows its SSID, signal, address and VPN. Every status method returns a
 * JSON string; the page parses it.
 *
 * This bridge is registered by {@link JawnWebViewActivity} and is only usable
 * while the WebView's top-level page is the local JawnOS origin. The activity
 * also adds/removes the JS interface as the page changes, so a remote page the
 * view ever navigates to (for example the one allowed sponsor link) does not
 * keep it.
 *
 * Connecting is Android's decision, not ours: on API 29+ a password is handed
 * over as a {@link WifiNetworkSuggestion}; the result says
 * {@code mode:'suggested'}, never "connected". The honest connected state is
 * {@link #status()}. Where a suggestion cannot be used (an older Android, an
 * enterprise network, Wi-Fi switched off, or Android refusing the suggestion)
 * the Wi-Fi panel is opened and the result says {@code mode:'settings'}.
 */
public class JawnNetwork {

    private final Activity activity;
    private final Context context;
    private final BooleanSupplier localPage;

    public JawnNetwork(JawnWebViewActivity activity) {
        this(activity, activity::isLocalJawnPage);
    }

    /** The same bridge for another manager view: {@code localPage} answers the
     *  only activity-specific question, whether the top-level page is the
     *  house's own origin. */
    public JawnNetwork(Activity activity, BooleanSupplier localPage) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.localPage = localPage;
    }

    // ------------------------------------------------------------------
    // status
    // ------------------------------------------------------------------

    @JavascriptInterface
    public String status() {
        if (!local()) return denied();
        try {
            ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities activeCaps =
                active == null || cm == null ? null : cm.getNetworkCapabilities(active);

            boolean vpn = false;
            Network physical = null;
            NetworkCapabilities physicalCaps = null;
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities c = cm.getNetworkCapabilities(n);
                    if (c == null) continue;
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true;
                }
            }
            // With a VPN up the active network is the tunnel; report the
            // physical link underneath it when there is one.
            if (active != null && isPhysical(activeCaps)) {
                physical = active;
                physicalCaps = activeCaps;
            } else if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities c = cm.getNetworkCapabilities(n);
                    if (!isPhysical(c)) continue;
                    if (physical == null || rank(c) < rank(physicalCaps)) {
                        physical = n;
                        physicalCaps = c;
                    }
                }
            }
            if (physical == null && activeCaps != null) {
                physical = active;
                physicalCaps = activeCaps;
            }
            LinkProperties primary = physical == null || cm == null
                ? null : cm.getLinkProperties(physical);
            if (primary == null && active != null && cm != null) {
                primary = cm.getLinkProperties(active);
            }

            String type = transportName(physicalCaps);
            String ssid = "";
            int signal = 0;
            if ("wifi".equals(type)) {
                WifiManager wifi = wifi();
                if (wifi != null) {
                    try {
                        WifiInfo info = wifi.getConnectionInfo();
                        if (info != null && info.getNetworkId() != -1) {
                            String name = cleanSsid(info.getSSID());
                            if (!name.isEmpty()) {
                                ssid = name;
                                int rssi = info.getRssi();
                                signal = rssi <= -127 ? 0 : rssi;
                            }
                        }
                    } catch (Throwable ignored) {
                        // no permission / location off: report what we can
                    }
                }
            }

            JSONObject out = new JSONObject();
            out.put("connected", active != null && activeCaps != null);
            out.put("type", type);
            out.put("ssid", ssid);
            out.put("signal", signal);
            out.put("ip", firstIp(primary));
            out.put("vpn", vpn);
            try {
                out.put("interfaces", interfaces(cm));
            } catch (Throwable t) {
                out.put("interfaces", new JSONArray());
            }
            return out.toString();
        } catch (Throwable t) {
            return scanError("Could not read the network: " + t);
        }
    }

    // ------------------------------------------------------------------
    // scan
    // ------------------------------------------------------------------

    @JavascriptInterface
    public String scan() {
        if (!local()) return denied();
        WifiManager wifi = wifi();
        if (wifi == null) return scanError("This device has no Wi-Fi.");
        try {
            if (!wifi.isWifiEnabled()) return scanError("Wi-Fi is off.");
            if (Build.VERSION.SDK_INT >= 23
                    && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                return scanError("Location permission is needed to scan for Wi-Fi networks.");
            }
            if (!locationServicesOn()) {
                return scanError("Turn on location services to scan for Wi-Fi networks.");
            }
            try {
                wifi.startScan();
            } catch (Throwable ignored) {
                // throttled or refused; the cached list below is still useful
            }
            List<ScanResult> results;
            try {
                results = wifi.getScanResults();
            } catch (SecurityException se) {
                return scanError("Android refused the scan; check location permission and services.");
            }
            Map<String, ScanResult> strongest = new LinkedHashMap<>();
            if (results != null) {
                for (ScanResult r : results) {
                    if (r == null) continue;
                    String name = cleanSsid(r.SSID);
                    if (name.isEmpty()) continue;
                    ScanResult seen = strongest.get(name);
                    if (seen == null || r.level > seen.level) strongest.put(name, r);
                }
            }
            List<ScanResult> list = new ArrayList<>(strongest.values());
            Collections.sort(list, new Comparator<ScanResult>() {
                @Override
                public int compare(ScanResult a, ScanResult b) {
                    return Integer.compare(b.level, a.level);
                }
            });
            JSONArray networks = new JSONArray();
            for (ScanResult r : list) {
                JSONObject n = new JSONObject();
                n.put("ssid", cleanSsid(r.SSID));
                n.put("signal", r.level);
                n.put("secure", isSecure(r));
                networks.put(n);
            }
            JSONObject out = new JSONObject();
            out.put("networks", networks);
            return out.toString();
        } catch (Throwable t) {
            return scanError("Could not scan: " + t);
        }
    }

    // ------------------------------------------------------------------
    // connect / disconnect
    // ------------------------------------------------------------------

    @JavascriptInterface
    public String connect(String ssid, String password) {
        if (!local()) return connectResult(false, "error",
            "JawnNetwork is available only on the local JawnOS page.");
        final String name = ssid == null ? "" : ssid.trim();
        if (name.isEmpty()) return connectResult(false, "error", "Choose a network name.");
        final String pass = password == null ? "" : password;
        WifiManager wifi = wifi();
        if (wifi == null) return connectResult(false, "error", "This device has no Wi-Fi.");

        try {
            if (isConnectedTo(wifi, name)) {
                return connectResult(true, "suggested",
                    "This device is already connected to \"" + name + "\".");
            }
            if (!wifi.isWifiEnabled()) {
                boolean opened = openSettingsPanel("wifi");
                return connectResult(opened, "settings", opened
                    ? "Wi-Fi is off. Turn it on in the Wi-Fi panel, then connect to \"" + name + "\"."
                    : "Wi-Fi is off and the Wi-Fi panel could not be opened.");
            }
            if (Build.VERSION.SDK_INT >= 29) {
                return suggest(wifi, name, pass);
            }
            boolean opened = openSettingsPanel("wifi");
            return connectResult(opened, "settings", opened
                ? "Android " + Build.VERSION.RELEASE + " needs the Wi-Fi panel to join \"" + name + "\"."
                : "The Wi-Fi panel could not be opened.");
        } catch (Throwable t) {
            return connectResult(false, "error", "Could not connect: " + t);
        }
    }

    @JavascriptInterface
    public String disconnect() {
        if (!local()) {
            return jsonOk(false, "JawnNetwork is available only on the local JawnOS page.");
        }
        WifiManager wifi = wifi();
        if (wifi == null) return jsonOk(false, "This device has no Wi-Fi.");
        if (Build.VERSION.SDK_INT >= 29) {
            return jsonOk(false,
                "Android does not let apps disconnect Wi-Fi. Open the Wi-Fi panel and disconnect there.");
        }
        try {
            boolean ok = wifi.disconnect();
            return jsonOk(ok, ok ? "Disconnected from Wi-Fi." : "Android did not disconnect Wi-Fi.");
        } catch (Throwable t) {
            return jsonOk(false, "Could not disconnect: " + t);
        }
    }

    /** API 29+: a suggestion is the only honest connect an app can make. */
    private String suggest(WifiManager wifi, String ssid, String password) {
        ScanResult found = findScan(wifi, ssid);
        if (found != null && isEnterprise(found)) {
            boolean opened = openSettingsPanel("wifi");
            return connectResult(opened, "settings", opened
                ? "\"" + ssid + "\" uses enterprise security. Choose it in the Wi-Fi panel."
                : "The Wi-Fi panel could not be opened.");
        }
        boolean owe = found != null && capabilitiesOf(found).contains("OWE");
        boolean needsPassword = found == null ? !password.isEmpty()
            : (isSecure(found) && !owe);
        if (needsPassword && password.isEmpty()) {
            return connectResult(false, "error",
                "\"" + ssid + "\" is secured; enter its password.");
        }
        try {
            WifiNetworkSuggestion.Builder builder =
                new WifiNetworkSuggestion.Builder().setSsid(ssid);
            if (owe) {
                // Enhanced Open is encrypted without a shared password.
                builder.setIsEnhancedOpen(true);
            } else if (needsPassword && isWpa3Only(found)) {
                builder.setWpa3Passphrase(password);
            } else if (needsPassword) {
                builder.setWpa2Passphrase(password);
            }
            // An open network needs no security type at all.
            // The house asked for this network; do not make the person hunt
            // for a system notification before Android may use the suggestion.
            builder.setIsUserInteractionRequired(false);
            int status = wifi.addNetworkSuggestions(
                Collections.singletonList(builder.build()));
            switch (status) {
                case WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS:
                    return connectResult(true, "suggested",
                        "Android will join \"" + ssid + "\" when it is in range.");
                case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE:
                    return connectResult(true, "suggested",
                        "Android already has a suggestion for \"" + ssid + "\".");
                case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP:
                    return connectResult(false, "error",
                        "Too many saved Wi-Fi suggestions; remove one in Wi-Fi settings first.");
                case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_NOT_ALLOWED:
                case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED:
                    return connectResult(false, "error",
                        "Android does not allow this app to suggest Wi-Fi networks. Use the Wi-Fi panel.");
                case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID:
                    return connectResult(false, "error", "Android rejected the network details.");
                default:
                    return connectResult(false, "error",
                        "Android returned Wi-Fi suggestion status " + status + ".");
            }
        } catch (SecurityException se) {
            boolean opened = openSettingsPanel("wifi");
            return connectResult(opened, "settings", opened
                ? "Android needs the Wi-Fi panel for \"" + ssid + "\"."
                : "The Wi-Fi panel could not be opened.");
        } catch (IllegalArgumentException iae) {
            boolean opened = openSettingsPanel("wifi");
            return connectResult(opened, "settings", opened
                ? "Android needs the Wi-Fi panel for \"" + ssid + "\"."
                : "The Wi-Fi panel could not be opened.");
        }
    }

    // ------------------------------------------------------------------
    // vpn
    // ------------------------------------------------------------------

    @JavascriptInterface
    public String vpn() {
        if (!local()) return denied();
        boolean active = false;
        String name = "";
        String type = "";
        try {
            ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities c = cm.getNetworkCapabilities(n);
                    if (c == null || !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                    active = true;
                    type = "vpn";
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp != null && lp.getInterfaceName() != null
                            && !lp.getInterfaceName().isEmpty()) {
                        name = lp.getInterfaceName();
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
            // report inactive rather than fail the page
        }
        try {
            JSONObject out = new JSONObject();
            out.put("active", active);
            out.put("name", name.isEmpty() && active ? "vpn" : name);
            out.put("type", type);
            return out.toString();
        } catch (Throwable t) {
            return denied();
        }
    }

    // ------------------------------------------------------------------
    // settings
    // ------------------------------------------------------------------

    @JavascriptInterface
    public void openSettings(String kind) {
        if (!local()) return;
        openSettingsPanel(kind);
    }

    private boolean openSettingsPanel(String kind) {
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.US);
        Intent first;
        if ("vpn".equals(k)) {
            first = new Intent(Settings.ACTION_VPN_SETTINGS);
        } else if (Build.VERSION.SDK_INT >= 29) {
            first = new Intent(Settings.Panel.ACTION_WIFI);
        } else {
            first = new Intent(Settings.ACTION_WIFI_SETTINGS);
        }
        try {
            activity.startActivity(first);
            return true;
        } catch (Throwable ignored) {
            // fall through to the full settings screen
        }
        try {
            activity.startActivity(new Intent("vpn".equals(k)
                ? Settings.ACTION_WIRELESS_SETTINGS : Settings.ACTION_WIFI_SETTINGS));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private boolean local() {
        return activity != null && localPage != null && localPage.getAsBoolean();
    }

    private WifiManager wifi() {
        try {
            return (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean locationServicesOn() {
        try {
            LocationManager lm =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isConnectedTo(WifiManager wifi, String ssid) {
        try {
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) return false;
            return ssid.equals(cleanSsid(info.getSSID()));
        } catch (Throwable t) {
            return false;
        }
    }

    private static ScanResult findScan(WifiManager wifi, String ssid) {
        try {
            List<ScanResult> results = wifi.getScanResults();
            if (results == null) return null;
            for (ScanResult r : results) {
                if (r != null && ssid.equals(cleanSsid(r.SSID))) return r;
            }
        } catch (Throwable ignored) {
            // no permission or no cached scan
        }
        return null;
    }

    private static String cleanSsid(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.equals("<unknown ssid>") || s.equals("0x") || s.equals("<unknown>")) return "";
        return s;
    }

    private static String capabilitiesOf(ScanResult result) {
        return result == null || result.capabilities == null
            ? "" : result.capabilities.toUpperCase(Locale.US);
    }

    private static boolean isSecure(ScanResult result) {
        String c = capabilitiesOf(result);
        return c.contains("WPA") || c.contains("WEP") || c.contains("EAP")
            || c.contains("PSK") || c.contains("SAE") || c.contains("OWE");
    }

    private static boolean isEnterprise(ScanResult result) {
        return capabilitiesOf(result).contains("EAP");
    }

    private static boolean isWpa3Only(ScanResult result) {
        String c = capabilitiesOf(result);
        return c.contains("SAE") && !c.contains("PSK");
    }

    private static boolean isPhysical(NetworkCapabilities caps) {
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            || caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH));
    }

    private static int rank(NetworkCapabilities caps) {
        if (caps == null) return 99;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return 0;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return 1;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return 2;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return 3;
        return 99;
    }

    private static String transportName(NetworkCapabilities caps) {
        if (caps == null) return "none";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "bluetooth";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
        return "other";
    }

    private static String firstIp(LinkProperties lp) {
        if (lp == null) return "";
        try {
            String first = "";
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la == null) continue;
                InetAddress a = la.getAddress();
                if (a == null) continue;
                String text = addressText(a);
                if (text.isEmpty()) continue;
                if (a instanceof Inet4Address) return text;
                if (first.isEmpty()) first = text;
            }
            return first;
        } catch (Throwable t) {
            return "";
        }
    }

    private static JSONArray interfaces(ConnectivityManager cm) throws Exception {
        JSONArray out = new JSONArray();
        if (cm == null) return out;
        Set<String> seen = new HashSet<>();
        for (Network n : cm.getAllNetworks()) {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            String name = lp.getInterfaceName();
            if (name == null || name.isEmpty() || !seen.add(name)) continue;
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("type", transportName(cm.getNetworkCapabilities(n)));
            o.put("ip", firstIp(lp));
            o.put("up", true);
            out.put(o);
        }
        return out;
    }

    private static String addressText(InetAddress a) {
        String text = a.getHostAddress();
        if (text == null) return "";
        int scope = text.indexOf('%');
        if (scope >= 0) text = text.substring(0, scope);
        return text;
    }

    private static String denied() {
        return scanError("JawnNetwork is available only on the local JawnOS page.");
    }

    private static String scanError(String message) {
        try {
            return new JSONObject().put("error", message).toString();
        } catch (Throwable t) {
            return "{\"error\":\"internal error\"}";
        }
    }

    private static String jsonOk(boolean ok, String message) {
        try {
            return new JSONObject().put("ok", ok).put("message", message).toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"message\":\"internal error\"}";
        }
    }

    private static String connectResult(boolean ok, String mode, String message) {
        try {
            return new JSONObject().put("ok", ok).put("mode", mode)
                .put("message", message).toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"mode\":\"error\",\"message\":\"internal error\"}";
        }
    }
}
