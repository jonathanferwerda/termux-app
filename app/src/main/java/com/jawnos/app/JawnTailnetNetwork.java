package com.jawnos.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import java.net.InetAddress;

/**
 * The physical network that carries the tunnel.
 *
 * The VPN becomes the default network while it is up, so {@code
 * ConnectivityManager.getActiveNetwork()} answers with the tunnel itself.
 * Everything that needs the network underneath - the VpnService's underlying
 * networks, the DNS base config, and the sockets the Go client binds - has to
 * read this cached choice instead.
 */
final class JawnTailnetNetwork {
    private static volatile Network underlying;
    private static volatile String interfaceName = "";

    private JawnTailnetNetwork() {
    }

    static Network get() {
        return underlying;
    }

    static String getInterfaceName() {
        return interfaceName;
    }

    /** Remember the network the tunnel rides on. */
    static void set(ConnectivityManager cm, Network network) {
        underlying = network;
        LinkProperties lp = network == null || cm == null ? null : cm.getLinkProperties(network);
        String name = lp == null ? null : lp.getInterfaceName();
        interfaceName = name == null ? "" : name;
    }

    /**
     * Pick the best non-VPN network to sit under the tunnel:
     * validated, then with a resolver, then unmetered. The current network is
     * kept on a tie so a Wi-Fi/cellular pair with equal scores cannot make the
     * choice flip on every callback.
     */
    static Network pick(ConnectivityManager cm) {
        if (cm == null) return null;
        Network current = underlying;
        Network best = null;
        int best_score = -1;
        Network[] networks = cm.getAllNetworks();
        if (networks == null) return null;
        for (Network network : networks) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) continue;
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue;
            LinkProperties lp = cm.getLinkProperties(network);
            int score = 0;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 8;
            if (lp != null && !lp.getDnsServers().isEmpty()) score += 4;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) score += 2;
            if (current != null && current.equals(network)) score += 1;
            if (score > best_score) {
                best = network;
                best_score = score;
            }
        }
        return best;
    }

    static Network pick(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return pick(cm);
    }

    /** The default gateway of a network, for the Go client's netmon. */
    static String gateway(ConnectivityManager cm, Network network) {
        if (cm == null || network == null) return "";
        LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null) return "";
        for (RouteInfo route : lp.getRoutes()) {
            if (!route.isDefaultRoute()) continue;
            InetAddress gateway = route.getGateway();
            if (gateway != null) return gateway.getHostAddress();
        }
        return "";
    }
}
