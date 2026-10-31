package com.jawnos.app;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import android.system.Os;

import com.jawnos.shared.termux.TermuxConstants;

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
 */
public final class JawnRuntime {

    private static final String MANIFEST = "jawnrt.json";
    private static final String BASE = "https://github.com/jonathanferwerda/jawnos/releases/download/releases/";

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

        File cache = new File(context.getCacheDir(), pinned.getString("file"));
        String want = pinned.getString("sha256");

        if (!cache.isFile() || !want.equalsIgnoreCase(sha256(cache))) {
            progress.step("Downloading the runtime");
            download(BASE + pinned.getString("file"), cache);
            String got = sha256(cache);
            if (!want.equalsIgnoreCase(got)) {
                // A corrupt or substituted download must not be unpacked.
                cache.delete();
                throw new RuntimeException("runtime download failed its checksum (want " + want + ", got " + got + ")");
            }
        }

        progress.step("Unpacking the runtime");
        File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        if (prefix.exists() && !delete(prefix)) {
            throw new RuntimeException("could not clear the existing prefix at " + prefix);
        }
        if (!prefix.mkdirs() && !prefix.isDirectory()) {
            throw new RuntimeException("could not create the prefix at " + prefix);
        }
        unpack(cache, prefix);

        // Tell the tree that this prefix is a finished runtime: scripts/
        // jawnos_deps.pl --install then reports and does nothing, because the
        // archive carries every module and tool and there is no package
        // manager here to install with.
        File etc = new File(prefix, "etc");
        if (!etc.isDirectory()) etc.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(etc, "jawnos-runtime.json"))) {
            out.write(("{\"product\":\"runtime\",\"version\":\"" + pinned.optString("version", "")
                + "\",\"abi\":\"" + abiName() + "\",\"sha256\":\"" + want + "\"}\n").getBytes("UTF-8"));
        }
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
