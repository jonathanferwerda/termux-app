package com.termux.app;

import android.net.VpnService;

import libtailscale.ParcelFileDescriptor;
import libtailscale.VPNServiceBuilder;

/** The Go backend's view of Android's {@link VpnService.Builder}. */
public class JawnTailnetBuilder implements VPNServiceBuilder {
    private final VpnService.Builder builder;

    public JawnTailnetBuilder(VpnService.Builder builder) {
        this.builder = builder;
    }

    @Override
    public void setMTU(int mtu) {
        builder.setMtu(mtu);
    }

    @Override
    public void addAddress(String address, int prefixLength) {
        builder.addAddress(address, prefixLength);
    }

    @Override
    public void addDNSServer(String address) {
        builder.addDnsServer(address);
    }

    @Override
    public void addSearchDomain(String domain) {
        builder.addSearchDomain(domain);
    }

    @Override
    public void addRoute(String address, int prefixLength) {
        builder.addRoute(address, prefixLength);
    }

    @Override
    public void excludeRoute(String address, int prefixLength) {
        // VpnService.Builder.excludeRoute needs API 33; the house has no split
        // routes that require it, so leaving the route in is correct here.
    }

    @Override
    public ParcelFileDescriptor establish() {
        android.os.ParcelFileDescriptor pfd = builder.establish();
        return pfd == null ? null : new JawnTailnetParcel(pfd);
    }
}
