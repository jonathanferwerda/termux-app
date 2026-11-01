package com.jawnos.app;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import android.system.Os;
import android.system.OsConstants;

import com.jawnos.shared.jawnos.JawnConstants;

/**
 * M3 (docs/leaving-termux.md): the runtime a clean install unpacks, instead of
 * Termux's bootstrap.
 *
 * The archive is built by scripts/runtime_build.pl and published beside the
 * APKs. It holds one Perl (with the XS modules the tree loads) and the tools
 * the diet kept; it is not a package manager, so nothing is ever installed
 * after this. The manifest baked into the APK pins each ABI's file and sha256,
 * so a download is verified before anything is unpacked.
 *
 * A zip, not a tar, because the app can unpack it without shipping a tar. Two
 * things the platform's own unzip paths drop and this must keep: the Unix mode
 * (Java's ZipEntry does not expose the external attributes, so the central
 * directory is parsed here) and symlinks (stored as a regular entry whose body
 * is the link target, with S_IFLNK set in that mode).
 *
 * The manifest is checked on every app start, not only on first run: see
 * {@link #syncInBackground}. A pin change must upgrade an existing install,
 * and it does so before the tree is exec'd, preserving files/home.
 */
public final class JawnRuntime {

    private static final String MANIFEST = "jawnrt.json";
    private static final String BASE = "https://github.com/jonathanferwerda/jawnos/releases/download/releases/";

    // Written into $PREFIX after a successful install. The installer used to
    // run only on first run, so without this record the app cannot tell "the
    // pinned runtime is already here" from "this install predates the pin".
    // The pin change on the phone (0.1.1697 -> 0.1.1706, libtermux-auth.so)
    // is exactly the update this marker makes possible.
    private static final String MARKER = ".jawnrt-version";
    private static final String UPDATE_LOCK = ".jawnrt-update.lock";
    private static final String PREVIOUS_PREFIX = "usr-previous";

    private static final long TREE_STOP_MS = 30000L;

    /** Set while the start-time pin check (and any re-install) runs. The tree's
     *  supervisor waits on this, so the prefix is never swapped under it. */
    private static volatile boolean sSyncing;
    private static volatile CountDownLatch sStartGate;

    /** Called between steps so the installer can update its progress text. */
    public interface Progress {
        void step(String what);
    }

    private JawnRuntime() { }

    /** The manifest's name for this device's ABI, or null when it is not one
     *  the runtime is built for (an x86 emulator of another family, say). */
    static String abiName() {
        String[] abis = Build.SUPPORTED_ABIS;
        for (String abi : abis) {
            if ("arm64-v8a".equals(abi)) return "aarch64";
            if ("x86_64".equals(abi)) return "x86_64";
        }
        return abis.length > 0 ? abis[0] : null;
    }

    /** The pinned entry for this ABI: { file, sha256 }, or null. */
    private static JSONObject entry(Context context) throws Exception {
        String abi = abiName();
        if (abi == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = context.getAssets().open(MANIFEST)) {
            copy(in, out);
        }
        JSONObject manifest = new JSONObject(out.toString("UTF-8"));
        JSONObject abis = manifest.optJSONObject("abi");
        return abis == null ? null : abis.optJSONObject(abi);
    }

    /** True when this build carries a runtime for this device. */
    public static boolean isSupported(Context context) {
        try {
            return entry(context) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Fetch (if needed), verify and unpack the runtime into the Termux prefix.
     * The prefix must not exist: the installer deletes it first, exactly as it
     * did for the bootstrap, so a half-written tree is never mistaken for an
     * installed one.
     */
    public static void install(Context context, Progress progress) throws Exception {
        JSONObject pinned = entry(context);
        if (pinned == null) throw new RuntimeException("no runtime for this device's ABI (" + abiName() + ")");

        String want = pinned.getString("sha256");
        File archive = verifiedArchive(context, pinned, want, progress);

        step(progress, "Unpacking the runtime");
        File prefix = new File(JawnConstants.TERMUX_PREFIX_DIR_PATH);
        if (prefix.exists() && !delete(prefix)) {
            throw new RuntimeException("could not clear the existing prefix at " + prefix);
        }
        if (!prefix.mkdirs() && !prefix.isDirectory()) {
            throw new RuntimeException("could not create the prefix at " + prefix);
        }
        unpack(archive, prefix);
        writeRuntimeInfo(prefix, pinned.optString("version", ""), want);
        // A first run is also an install: record the pin so the next app start
        // does not re-download a runtime it already has.
        writeMarker(prefix, pinned.optString("version", ""), want);
        // Optional parts are separate downloads; start them once the core is
        // in place and let the tree come up without waiting for them.
        syncPartsInBackground(context);
    }

    // ---------------------------------------------------- start-time pin check

    /** True while the pin check or a re-install is running. */
    public static boolean isSyncing() { return sSyncing; }

    /**
     * Check the APK's pinned runtime against the prefix in the background.
     * Called from {@link JawnApplication#onCreate}. A matching marker costs one
     * small file read; a mismatch is handled by {@link #sync}, which the tree's
     * supervisor waits for before exec'ing perl.
     */
    public static void syncInBackground(final Context context) {
        final Context app = context.getApplicationContext();
        synchronized (JawnRuntime.class) {
            if (sStartGate != null) return;
            sStartGate = new CountDownLatch(1);
            Thread t = new Thread(new Runnable() {
                public void run() {
                    sSyncing = true;
                    try {
                        sync(app, null);
                    } catch (Throwable e) {
                        JawnLog.append("runtime: pin check/update failed: " + e);
                    } finally {
                        sSyncing = false;
                        sStartGate.countDown();
                    }
                    // Parts are optional and must never hold up the tree's start:
                    // the gate above is already open, and a failed download only
                    // means the feature keeps its fallback until the next start.
                    syncPartsInBackground(app);
                }
            }, "jawnrt-sync");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Block until the start-time check has finished. Only the tree's supervisor
     * calls this, on its own thread, immediately before exec'ing perl. An
     * interrupt (a stop or restart) does not release it early: a tree must not
     * start on a prefix that is being renamed.
     */
    public static void awaitForStart(Context context) {
        syncInBackground(context);
        CountDownLatch gate = sStartGate;
        if (gate == null) return;
        boolean interrupted = false;
        while (true) {
            try {
                gate.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /**
     * Bring the installed runtime up to the APK's pin. Returns true when a new
     * runtime was unpacked.
     *
     * Follows the order app_migrate.pl --restore-only uses for data: stop the
     * tree, download to a temp file, verify the sha256, unpack into a staging
     * prefix, and only then swap. {@code files/home} is never touched - the
     * swap renames {@code files/usr} alone, and the old prefix stays until the
     * new one has been renamed in.
     */
    public static boolean sync(Context context, Progress progress) throws Exception {
        final JSONObject pinned = entry(context);
        if (pinned == null) return false;
        final String version = pinned.optString("version", "");
        final String want = pinned.getString("sha256");
        final File prefix = new File(JawnConstants.TERMUX_PREFIX_DIR_PATH);

        if (installedMatches(prefix, version, want)) return false;

        final File files = new File(JawnConstants.TERMUX_FILES_DIR_PATH);
        final File previous = new File(files, PREVIOUS_PREFIX);
        // No prefix and nothing set aside: first run installs it through
        // JawnInstaller, which writes the marker at the end.
        if (!prefix.isDirectory() && !previous.isDirectory()) return false;
        if (prefix.isDirectory() && !hasRuntime(prefix) && !previous.isDirectory()) return false;

        // One updater at a time, even across the app's processes (`:browser`).
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(files, UPDATE_LOCK), "rw");
             FileChannel channel = lockFile.getChannel();
             FileLock lock = channel.lock()) {
            // A crash between the two swap renames leaves the old prefix aside.
            recoverInterruptedSwap(prefix, previous);
            // Another process may have finished while we waited for the lock.
            if (installedMatches(prefix, version, want)) return false;
            if (!prefix.isDirectory() || !hasRuntime(prefix)) return false;

            final boolean restart = JawnLauncher.shouldKeepRunning(context);
            step(progress, "Stopping the house to update its runtime");
            stopTree(context);

            File archive = verifiedArchive(context, pinned, want, progress);

            step(progress, "Unpacking the runtime");
            File staging = new File(JawnConstants.TERMUX_STAGING_PREFIX_DIR_PATH);
            if (staging.exists() && !delete(staging)) {
                throw new RuntimeException("could not clear the staging prefix at " + staging);
            }
            if (!staging.mkdirs() && !staging.isDirectory()) {
                throw new RuntimeException("could not create the staging prefix at " + staging);
            }
            unpack(archive, staging);
            if (!hasRuntime(staging)) {
                delete(staging);
                throw new RuntimeException("the unpacked runtime is incomplete");
            }
            writeRuntimeInfo(staging, version, want);
            writeMarker(staging, version, want);

            step(progress, "Switching the runtime in");
            swap(prefix, staging, previous);

            // The prefix's executables are links into nativeLibraryDir; a fresh
            // prefix has none yet, so re-point them before the tree starts.
            JawnRuntimeBins.ensure(context);

            JawnLog.append("runtime: installed " + version + " ("
                + want.substring(0, Math.min(12, want.length())) + "...)");
            // 100+ MB of old prefix is no longer needed, and deleting it must
            // not hold up the tree. It is outside the live prefix.
            Thread cleanup = new Thread(new Runnable() {
                public void run() { delete(previous); }
            }, "jawnrt-cleanup");
            cleanup.setDaemon(true);
            cleanup.start();

            if (restart && JawnLauncher.shouldKeepRunning(context)) {
                JawnLauncher.startJawn(context);
            }
            return true;
        }
    }

    // ------------------------------------------------------ optional parts
    // A part is a second archive in the same pin ("parts": {abi: {name: {...}}}).
    // It carries libraries and data only; its executables ride in the APK as
    // jniLibs like the core's. The marker lists the paths the part owns, so an
    // update can remove what the new version no longer carries without ever
    // touching a core file.

    private static final String PART_MARKER_PREFIX = ".jawnrt-part-";
    private static volatile boolean sPartsSyncing;

    /** The parts offered for this device's ABI, or null when the pin has none. */
    private static JSONObject parts(Context context) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = context.getAssets().open(MANIFEST)) {
                copy(in, out);
            }
            JSONObject manifest = new JSONObject(out.toString("UTF-8"));
            JSONObject parts = manifest.optJSONObject("parts");
            if (parts == null) return null;
            return parts.optJSONObject(abiName());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Install any missing or updated part on a daemon thread. Never awaited by
     * the tree's supervisor: an optional feature that is not downloaded yet
     * keeps its fallback, and the next start tries again.
     */
    public static void syncPartsInBackground(final Context context) {
        final Context app = context.getApplicationContext();
        synchronized (JawnRuntime.class) {
            if (sPartsSyncing) return;
            sPartsSyncing = true;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    syncParts(app, null);
                } catch (Throwable e) {
                    JawnLog.append("runtime part: failed: " + e);
                } finally {
                    synchronized (JawnRuntime.class) { sPartsSyncing = false; }
                }
            }
        }, "jawnrt-parts");
        t.setDaemon(true);
        t.start();
    }

    /** Install every part whose marker does not match the pin. Returns true
     *  when at least one archive was unpacked. */
    public static boolean syncParts(Context context, Progress progress) throws Exception {
        JSONObject parts = parts(context);
        if (parts == null) return false;
        File prefix = new File(JawnConstants.TERMUX_PREFIX_DIR_PATH);
        if (!prefix.isDirectory() || !hasRuntime(prefix)) return false;
        String core = installedVersion(prefix);
        boolean changed = false;
        java.util.Iterator<String> names = parts.keys();
        while (names.hasNext()) {
            String name = names.next();
            JSONObject pin = parts.optJSONObject(name);
            if (pin == null) continue;
            String minCore = pin.optString("min_core", "");
            if (minCore.length() > 0 && core.length() > 0 && versionOlder(core, minCore)) {
                JawnLog.append("runtime part " + name + ": needs core " + minCore + ", have " + core);
                continue;
            }
            try {
                if (installPart(context, prefix, name, pin, progress)) changed = true;
            } catch (Throwable e) {
                // One optional part must not stop the other, and must not fail
                // the app: its feature simply keeps its fallback.
                JawnLog.append("runtime part " + name + ": " + e);
            }
        }
        if (changed) JawnRuntimeBins.ensure(context);
        return changed;
    }

    private static boolean installPart(Context context, File prefix, String name,
                                       JSONObject pin, Progress progress) throws Exception {
        String file = pin.getString("file");
        String want = pin.getString("sha256");
        String version = pin.optString("version", "");
        File marker = new File(prefix, PART_MARKER_PREFIX + name);
        if (partMatches(marker, version, want)) return false;

        File cache = new File(context.getCacheDir(), file);
        if (!cache.isFile() || !want.equalsIgnoreCase(sha256(cache))) {
            step(progress, "Downloading the " + name + " runtime");
            download(BASE + file, cache);
            String got = sha256(cache);
            if (!want.equalsIgnoreCase(got)) {
                cache.delete();
                throw new RuntimeException("download failed its checksum (want "
                        + want + ", got " + got + ")");
            }
        }

        // The old marker's path list is this part's ownership record. A path the
        // new archive drops is removed after the new files land; core paths are
        // never in the list.
        List<String> stale = new ArrayList<>();
        if (marker.isFile()) {
            String old = read(marker);
            if (old != null) {
                for (String line : old.split("\n")) {
                    if (line.startsWith("path=")) stale.add(line.substring(5));
                }
            }
        }

        step(progress, "Unpacking the " + name + " runtime");
        unpack(cache, prefix);

        List<String> keep = new ArrayList<>();
        JSONArray provides = pin.optJSONArray("provides");
        if (provides != null) {
            for (int i = 0; i < provides.length(); i++) keep.add(provides.optString(i));
        }
        for (String path : stale) {
            if (path.length() == 0 || keep.contains(path)) continue;
            File old = new File(JawnConstants.TERMUX_FILES_DIR_PATH, path);
            if (old.isFile() || java.nio.file.Files.isSymbolicLink(old.toPath())) old.delete();
        }
        writePartMarker(marker, version, want, keep);
        JawnLog.append("runtime part: installed " + name + " " + version);
        return true;
    }

    private static boolean partMatches(File marker, String version, String sha) {
        if (!marker.isFile()) return false;
        String text = read(marker);
        if (text == null) return false;
        String installedVersion = field(text, "version");
        String installedSha = field(text, "sha256");
        return (version.isEmpty() || version.equals(installedVersion))
            && installedSha != null && sha.equalsIgnoreCase(installedSha);
    }

    private static void writePartMarker(File marker, String version, String sha,
                                        List<String> provides) throws Exception {
        File tmp = new File(marker.getPath() + ".tmp");
        StringBuilder text = new StringBuilder();
        text.append("version=").append(version).append("\n");
        text.append("sha256=").append(sha).append("\n");
        for (String path : provides) text.append("path=").append(path).append("\n");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.toString().getBytes("UTF-8"));
            out.getFD().sync();
        }
        if (!tmp.renameTo(marker)) {
            marker.delete();
            if (!tmp.renameTo(marker)) throw new RuntimeException("could not write " + marker);
        }
    }

    /** The core version recorded by the last successful install, or "". */
    private static String installedVersion(File prefix) {
        String text = read(new File(prefix, MARKER));
        if (text == null) return "";
        String version = field(text, "version");
        return version == null ? "" : version;
    }

    /** Numeric-segment comparison; a missing segment is zero. */
    private static boolean versionOlder(String have, String want) {
        String[] a = have.split("[^0-9]+");
        String[] b = want.split("[^0-9]+");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = 0;
            int y = 0;
            try { if (i < a.length && a[i].length() > 0) x = Integer.parseInt(a[i]); } catch (Throwable ignored) { }
            try { if (i < b.length && b[i].length() > 0) y = Integer.parseInt(b[i]); } catch (Throwable ignored) { }
            if (x != y) return x < y;
        }
        return false;
    }

    private static File verifiedArchive(Context context, JSONObject pinned, String want, Progress progress) throws Exception {
        File cache = new File(context.getCacheDir(), pinned.getString("file"));
        if (!cache.isFile() || !want.equalsIgnoreCase(sha256(cache))) {
            step(progress, "Downloading the runtime");
            download(BASE + pinned.getString("file"), cache);
            String got = sha256(cache);
            if (!want.equalsIgnoreCase(got)) {
                // A corrupt or substituted download must not be unpacked.
                cache.delete();
                throw new RuntimeException("runtime download failed its checksum (want " + want + ", got " + got + ")");
            }
        }
        return cache;
    }

    /** The marker records what was actually unpacked. Version and sha are both
     *  checked because a rebuilt archive can keep the version string. */
    private static boolean installedMatches(File prefix, String version, String sha) {
        File marker = new File(prefix, MARKER);
        if (!marker.isFile()) return false;   // an install that predates the marker
        String text = read(marker);
        if (text == null) return false;
        String installedVersion = field(text, "version");
        String installedSha = field(text, "sha256");
        return (version.isEmpty() || version.equals(installedVersion))
            && installedSha != null && sha.equalsIgnoreCase(installedSha);
    }

    private static boolean hasRuntime(File dir) {
        return new File(dir, "lib/perl5").isDirectory()
            && new File(dir, "etc").isDirectory();
    }

    /**
     * Record the installed pin atomically: a temp file is fsynced and renamed,
     * so a crash never leaves a half marker claiming a runtime that is not
     * actually in place. This is what makes a pin change reach an existing
     * install instead of only a fresh one.
     */
    private static void writeMarker(File prefix, String version, String sha) throws Exception {
        File marker = new File(prefix, MARKER);
        File tmp = new File(prefix, MARKER + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(("version=" + version + "\nsha256=" + sha + "\n").getBytes("UTF-8"));
            out.getFD().sync();
        }
        if (!tmp.renameTo(marker)) {
            marker.delete();
            if (!tmp.renameTo(marker)) throw new RuntimeException("could not write " + marker);
        }
    }

    /** A process death between swap's two renames leaves files/usr absent and
     *  files/usr-previous holding the old runtime. Put the old prefix back, so
     *  a crash never leaves the device with no runtime to start. */
    private static void recoverInterruptedSwap(File prefix, File previous) {
        if (prefix.isDirectory() || !previous.isDirectory()) return;
        if (previous.renameTo(prefix)) {
            JawnLog.append("runtime: restored the previous prefix after an interrupted swap");
        } else {
            JawnLog.append("runtime: could not restore " + previous);
        }
    }

    private static void writeRuntimeInfo(File prefix, String version, String sha) throws Exception {
        // Tell the tree that this prefix is a finished runtime: scripts/
        // jawnos_deps.pl --install then reports and does nothing, because the
        // archive carries every module and tool and there is no package
        // manager here to install with.
        File etc = new File(prefix, "etc");
        if (!etc.isDirectory() && !etc.mkdirs()) {
            throw new RuntimeException("could not create " + etc);
        }
        try (FileOutputStream out = new FileOutputStream(new File(etc, "jawnos-runtime.json"))) {
            out.write(("{\"product\":\"runtime\",\"version\":\"" + version
                + "\",\"abi\":\"" + abiName() + "\",\"sha256\":\"" + sha + "\"}\n").getBytes("UTF-8"));
        }
    }

    /**
     * Put the staged prefix in place. The old prefix is renamed aside first and
     * restored if the new one cannot be moved in, so {@code files/usr} is never
     * missing or half written. {@code files/home} is not involved.
     */
    private static void swap(File prefix, File staging, File previous) throws Exception {
        if (previous.exists() && !delete(previous)) {
            throw new RuntimeException("could not clear " + previous);
        }
        if (prefix.exists() && !prefix.renameTo(previous)) {
            throw new RuntimeException("could not move the old runtime aside");
        }
        if (!staging.renameTo(prefix)) {
            if (previous.exists()) previous.renameTo(prefix);
            throw new RuntimeException("could not move the new runtime into place");
        }
    }

    /**
     * Stop the tree before the swap. The app's supervisor is asked first, so
     * the master gets its usual INT and the tree writes out its WAL. An APK
     * update can leave a tree whose app process is gone, so whatever is still
     * running after the grace period is SIGKILLed - the same order
     * app_migrate.pl uses before it touches a prefix or a database.
     */
    private static void stopTree(Context context) throws Exception {
        try {
            JawnLauncher.stopJawnTree(context);
        } catch (Throwable t) {
            JawnLog.append("runtime: could not ask the supervisor to stop: " + t);
        }
        long deadline = System.currentTimeMillis() + TREE_STOP_MS;
        while (treeIsRunning() && System.currentTimeMillis() < deadline) sleep(250);
        if (!treeIsRunning()) return;

        JawnLog.append("runtime: the tree is still up; killing it before the swap");
        long hardStop = deadline + 15000L;
        while (treeIsRunning() && System.currentTimeMillis() < hardStop) {
            killTreeProcesses();
            sleep(500);
        }
        if (treeIsRunning()) {
            throw new RuntimeException("the jawn tree is still running; not swapping the runtime");
        }
    }

    /** pids whose command line names one of the tree's programs. The app and
     *  the tree share a uid, so /proc is readable; this needs no external pkill
     *  and works even after an APK update moved nativeLibraryDir. */
    private static List<Integer> treePids() {
        List<Integer> pids = new ArrayList<>();
        File[] all = new File("/proc").listFiles();
        if (all == null) return pids;
        for (File dir : all) {
            String name = dir.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            FileInputStream in = null;
            try {
                byte[] buf = new byte[4096];
                in = new FileInputStream(new File(dir, "cmdline"));
                int n = in.read(buf);
                if (n <= 0) continue;
                if (isTreeCommand(new String(buf, 0, n, "UTF-8"))) pids.add(Integer.parseInt(name));
            } catch (Throwable ignored) {
                // a process that exited between listing and reading
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignored) { }
            }
        }
        return pids;
    }

    private static boolean treeIsRunning() { return !treePids().isEmpty(); }

    private static boolean isTreeCommand(String cmd) {
        return cmd.contains("President.pl") || cmd.contains("Manager.pl")
            || cmd.contains("Websocket.pl") || cmd.contains("Alarm.pl")
            || cmd.contains("Music.pl") || cmd.contains("teletype.pl")
            || cmd.contains("watch.pl") || cmd.contains("pen.pl")
            || cmd.contains("./jawn");
    }

    private static void killTreeProcesses() {
        for (int pid : treePids()) {
            try { Os.kill(pid, OsConstants.SIGKILL); } catch (Throwable ignored) { }
        }
    }

    private static void step(Progress progress, String what) {
        if (progress != null) progress.step(what);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String read(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            return new String(readAll(in), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String field(String text, String name) {
        for (String line : text.split("\n")) {
            int at = line.indexOf('=');
            if (at > 0 && line.substring(0, at).trim().equals(name)) return line.substring(at + 1).trim();
        }
        return null;
    }

    // ------------------------------------------------------------------ unpack

    private static void unpack(File zip, File dest) throws Exception {
        Map<String, Integer> modes = centralDirectoryModes(zip);
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("/") || name.contains(".." + File.separator) || name.contains("../")) {
                    throw new RuntimeException("refusing an unsafe path in the runtime: " + name);
                }
                File target = new File(dest, name);
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new RuntimeException("could not create " + parent);
                }
                Integer mode = modes.get(name);
                int m = mode == null ? 0100644 : mode;
                if (entry.isDirectory()) continue;

                if ((m & 0170000) == 0120000) {
                    // A symlink: the entry's bytes are the target path.
                    String linkTo = new String(readAll(zf.getInputStream(entry)), "UTF-8");
                    if (target.exists()) target.delete();
                    Os.symlink(linkTo, target.getAbsolutePath());
                }
                else {
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        copy(zf.getInputStream(entry), out);
                    }
                    // Scoped to the mode we stored: no file keeps the umask's bits.
                    Os.chmod(target.getAbsolutePath(), m & 07777);
                }
            }
        }
    }

    /**
     * The external attributes of every entry, keyed by name. A zip's central
     * directory is the only place a Unix mode survives, and Java's ZipEntry
     * does not expose it, so the record is read here: record signature
     * (0x02014b50), name length at +28, external attributes at +38, name at +46.
     */
    static Map<String, Integer> centralDirectoryModes(File zip) throws Exception {
        Map<String, Integer> modes = new HashMap<>();
        try (RandomAccessFile raf = new RandomAccessFile(zip, "r")) {
            long len = raf.length();
            int tail = (int) Math.min(len, 66000);
            byte[] end = new byte[tail];
            raf.seek(len - tail);
            raf.readFully(end);
            int eocd = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if ((end[i] & 0xFF) == 0x50 && (end[i + 1] & 0xFF) == 0x4b
                    && (end[i + 2] & 0xFF) == 0x05 && (end[i + 3] & 0xFF) == 0x06) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) throw new RuntimeException("not a zip: no end-of-central-directory record");
            int count = le16(end, eocd + 10);
            long cdOffset = le32(end, eocd + 16);
            raf.seek(cdOffset);
            byte[] buf = new byte[46];
            for (int i = 0; i < count; i++) {
                raf.readFully(buf);
                if (le32(buf, 0) != 0x02014b50) throw new RuntimeException("bad central directory record " + i);
                int nameLen = le16(buf, 28);
                int extraLen = le16(buf, 30);
                int commentLen = le16(buf, 32);
                int mode = (int) ((le32(buf, 38) >>> 16) & 0xFFFF);
                byte[] name = new byte[nameLen];
                raf.readFully(name);
                modes.put(new String(name, "UTF-8"), mode);
                raf.seek(raf.getFilePointer() + extraLen + commentLen);
            }
        }
        return modes;
    }

    // ----------------------------------------------------------------- helpers

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static long le32(byte[] b, int at) {
        return (b[at] & 0xFFL) | ((b[at + 1] & 0xFFL) << 8)
            | ((b[at + 2] & 0xFFL) << 16) | ((b[at + 3] & 0xFFL) << 24);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(InputStream in, java.io.OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
        out.flush();
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) != -1) digest.update(buf, 0, read);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void download(String url, File to) throws Exception {
        File part = new File(to.getParentFile(), to.getName() + ".part");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new RuntimeException("runtime download answered " + code);
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                copy(in, out);
            }
        } finally {
            conn.disconnect();
        }
        if (!part.renameTo(to)) throw new RuntimeException("could not move the download into place");
    }

    private static boolean delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        return file.delete();
    }
}
