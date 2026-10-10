package com.termux.app;

import android.os.Handler;
import android.os.Looper;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * jawn's log file (~/manager.log): bounded reads for the viewer, and a size cap
 * so it cannot grow without bound. The master, President.pl and Manager.pl all
 * append to it, so it used to be pruned by hand. Trimming it in place is safe
 * because every writer opens it in append mode -- truncating only moves the end
 * they keep appending at.
 */
public class JawnLog {

    private static final long CAP_BYTES = 20L * 1024 * 1024;
    private static final long KEEP_BYTES = 5L * 1024 * 1024;
    private static final long CHECK_INTERVAL_MS = 60000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable trimmer;

    public static File file() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "manager.log");
    }

    /** Trim the log if it is over the cap, keeping the most recent part. */
    public static void capIfNeeded() {
        File f = file();
        long size = f.length();
        if (size <= CAP_BYTES) return;
        RandomAccessFile raf = null;
        try {
            byte[] tail = new byte[(int) KEEP_BYTES];
            raf = new RandomAccessFile(f, "rw");
            raf.seek(size - KEEP_BYTES);
            raf.readFully(tail);
            raf.seek(0);
            raf.write(tail);
            raf.setLength(KEEP_BYTES);
        } catch (Throwable t) {
            // leave the log as it is on any failure
        } finally {
            if (raf != null) try { raf.close(); } catch (Throwable t) { }
        }
    }

    /** Cap now, then every CHECK_INTERVAL_MS while jawn runs. */
    public static void scheduleTrims() {
        cancelTrims();
        trimmer = new Runnable() {
            public void run() {
                capIfNeeded();
                MAIN.postDelayed(this, CHECK_INTERVAL_MS);
            }
        };
        MAIN.postDelayed(trimmer, CHECK_INTERVAL_MS);
    }

    public static void cancelTrims() {
        if (trimmer != null) { MAIN.removeCallbacks(trimmer); trimmer = null; }
    }

    /** Last {@code bytes} bytes of the log, for the viewer. */
    public static String tail(int bytes) {
        File f = file();
        if (!f.exists()) return "(no log yet)";
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - bytes);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String s = new String(buf, "UTF-8");
            return (start > 0 ? "[showing last " + (bytes / 1024) + " KB]\n" : "") + s;
        } catch (Throwable t) {
            return "(could not read log: " + t + ")";
        } finally {
            if (raf != null) try { raf.close(); } catch (Throwable t) { }
        }
    }
}
