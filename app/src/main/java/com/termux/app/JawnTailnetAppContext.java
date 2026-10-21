package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * The Java side of the Tailscale Go backend. Most of these are only used by
 * netmon / MDM / hardware-key code we do not need on a house device, so they
 * return safe defaults; the interfaces and DNS values are real.
 */
public class JawnTailnetAppContext implements libtailscale.AppContext {
    private final Context context;
    private final SharedPreferences store;

    public JawnTailnetAppContext(Context context) {
        this.context = context.getApplicationContext();
        this.store = this.context.getSharedPreferences("jawn_tailnet_store", Context.MODE_PRIVATE);
    }

    @Override
    public void log(String tag, String line) {
        Log.i("JawnTailnet/" + tag, line == null ? "" : line);
    }

    @Override
    public void encryptToPref(String key, String value) {
        store.edit().putString(key, value).commit();
    }

    @Override
    public String decryptFromPref(String key) {
        return store.getString(key, "");
    }

    @Override
    public String getStateStoreKeysJSON() {
        JSONArray out = new JSONArray();
        for (String key : store.getAll().keySet()) {
            if (key != null && key.startsWith("statestore-")) {
                out.put(key.substring("statestore-".length()));
            }
        }
        return out.toString();
    }

    @Override
    public String getOSVersion() {
        return Build.VERSION.RELEASE;
    }

    @Override
    public long getSDKInt() {
        return Build.VERSION.SDK_INT;
    }

    @Override
    public String getDeviceName() {
        return Build.MODEL == null ? "android" : Build.MODEL;
    }

    @Override
    public String getInstallSource() {
        return "jawnos";
    }

    @Override
    public boolean shouldUseGoogleDNSFallback() {
        return false;
    }

    @Override
    public boolean isChromeOS() {
        return false;
    }

    @Override
    public boolean isClientLoggingEnabled() {
        return false;
    }

    @Override
    public String getInterfacesAsJson() {
        JSONArray out = new JSONArray();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    JSONArray addrs = new JSONArray();
                    for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                        InetAddress address = ia.getAddress();
                        if (address == null) continue;
                        String host = address.getHostAddress();
                        if (host == null) continue;
                        JSONObject addr = new JSONObject();
                        addr.put("ip", host);
                        addr.put("prefixLen", (int) ia.getNetworkPrefixLength());
                        addrs.put(addr);
                    }
                    JSONObject o = new JSONObject();
                    o.put("name", nif.getName());
                    o.put("index", nif.getIndex());
                    o.put("mtu", nif.getMTU());
                    o.put("up", nif.isUp());
                    o.put("broadcast", nif.supportsMulticast());
                    o.put("loopback", nif.isLoopback());
                    o.put("pointToPoint", nif.isPointToPoint());
                    o.put("multicast", nif.supportsMulticast());
                    o.put("addrs", addrs);
                    out.put(o);
                } catch (Exception ignored) {
                    // an interface that disappears mid-scan is not an error
                }
            }
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    @Override
    public String getPlatformDNSConfig() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "";
            // While the tunnel is up it is the default network, and its own DNS
            // is MagicDNS. The base config this feeds back to the tailnet client
            // must be the physical network's resolver underneath it, or the
            // client would offer MagicDNS as its own upstream.
            LinkProperties lp = underlyingLinkProperties(cm);
            if (lp == null) return "";
            StringBuilder servers = new StringBuilder();
            for (InetAddress address : lp.getDnsServers()) {
                if (address == null) continue;
                if (servers.length() > 0) servers.append(' ');
                servers.append(address.getHostAddress());
            }
            String domains = lp.getDomains() == null ? "" : lp.getDomains();
            return servers.toString() + "\n" + domains;
        } catch (Exception e) {
            return "";
        }
    }

    /** The non-VPN network that carries the tunnel, with its real resolver. */
    private static LinkProperties underlyingLinkProperties(ConnectivityManager cm) {
        Network[] networks = cm.getAllNetworks();
        if (networks != null) {
            for (Network network : networks) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null) continue;
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties lp = cm.getLinkProperties(network);
                if (lp != null && !lp.getDnsServers().isEmpty()) return lp;
            }
        }
        Network active = cm.getActiveNetwork();
        return active == null ? null : cm.getLinkProperties(active);
    }

    // There is no MDM on a house device, so no policy value is configured.
    // libtailscale maps a Java exception whose message is "no such key" to
    // syspolicy.ErrNoSuchKey, which is how the Go side knows to use its own
    // default. Returning "" instead reads as "the admin set this to empty":
    // applySysPolicyLocked then clears Hostname, and GetString overwrites
    // ControlURL with the default controlplane.tailscale.com.
    private static RuntimeException noSuchKey() {
        return new RuntimeException("no such key");
    }

    @Override
    public String getSyspolicyStringValue(String key) {
        throw noSuchKey();
    }

    @Override
    public boolean getSyspolicyBooleanValue(String key) {
        throw noSuchKey();
    }

    @Override
    public String getSyspolicyStringArrayJSONValue(String key) {
        throw noSuchKey();
    }

    @Override
    public boolean hardwareAttestationKeySupported() {
        return false;
    }

    @Override
    public String hardwareAttestationKeyCreate() {
        throw new UnsupportedOperationException("hardware attestation is not supported");
    }

    @Override
    public void hardwareAttestationKeyRelease(String id) {
    }

    @Override
    public byte[] hardwareAttestationKeyPublic(String id) {
        throw new UnsupportedOperationException("hardware attestation is not supported");
    }

    @Override
    public byte[] hardwareAttestationKeySign(String id, byte[] data) {
        throw new UnsupportedOperationException("hardware attestation is not supported");
    }

    @Override
    public void hardwareAttestationKeyLoad(String id) {
        throw new UnsupportedOperationException("hardware attestation is not supported");
    }

    @Override
    public boolean bindSocketToNetwork(int fd) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm == null ? null : cm.getActiveNetwork();
            if (network == null) return false;
            android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.fromFd(fd);
            try {
                network.bindSocket(pfd.getFileDescriptor());
                return true;
            } finally {
                pfd.close();
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public byte[] getUserCACertsPEM() {
        return new byte[0];
    }
}
