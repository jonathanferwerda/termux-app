package com.termux.app;

/** Hands the established TUN fd to the Go backend. */
public class JawnTailnetParcel implements libtailscale.ParcelFileDescriptor {
    private final android.os.ParcelFileDescriptor pfd;

    public JawnTailnetParcel(android.os.ParcelFileDescriptor pfd) {
        this.pfd = pfd;
    }

    @Override
    public int detach() {
        return pfd.detachFd();
    }
}
