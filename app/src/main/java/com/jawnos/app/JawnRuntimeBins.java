package com.jawnos.app;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import com.jawnos.shared.jawnos.JawnConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * M5 (docs/leaving-termux.md): targetSdk 36 stops the app exec'ing a file it
 * wrote into its own data directory, so the runtime's executables ship as
 * native libraries instead. One library is one executable, named
 * {@code nativeLibraryDir/lib<slug>.so}; the manifest packed into
 * {@code assets/runtime-bins.txt} says which prefix path wants which library
 * and which prefix links point at which.
 *
 * The prefix is {@link JawnConstants#TERMUX_PREFIX_DIR_PATH} and the only
 * directories this touches are its {@code bin} and {@code libexec}. A regular
 * file there is replaced (that is the repair); anything outside them is left
 * alone, whatever the manifest says.
 *
 * {@code nativeLibraryDir} is version-scoped and changes on every APK update,
 * so every link made here is stale after an update. {@link #ensure} is cheap
 * (a stat and a readlink per entry), idempotent, and is called on app start
 * and again right before the tree is exec'd.
 */
public final class JawnRuntimeBins {

    private static final String ASSET = "runtime-bins.txt";

    private static final int UNCHANGED = 0;
    private static final int CREATED = 1;
    private static final int REPOINTED = 2;
    private static final int SKIPPED = 3;

    private JawnRuntimeBins() { }

    /**
     * Repair the runtime's prefix links, if this APK carries a manifest.
     * Never throws: a broken manifest must not stop the app from starting.
     *
     * @return a short summary ("created=N repointed=N skipped=N failed=N"),
     *         also written to {@link JawnLog}.
     */
    public static synchronized String ensure(Context context) {
        String summary;
        try {
            summary = run(context);
        } catch (Throwable t) {
            summary = "failed: " + t;
        }
        try { JawnLog.append("runtime-bins: " + summary); } catch (Throwable ignored) { }
        return summary;
    }

    private static String run(Context context) {
        if (context == null) return "no context";

        String text;
        try {
            InputStream in;
            try {
                in = context.getAssets().open(ASSET);
            } catch (FileNotFoundException e) {
                // An older APK has no manifest and needs no repair.
                return "no " + ASSET + " (nothing to do)";
            }
            text = readAll(in);
        } catch (Throwable t) {
            return "could not read " + ASSET + ": " + t;
        }

        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        if (nativeDir == null || nativeDir.length() == 0) return "no nativeLibraryDir";
        String prefix = normalize(JawnConstants.TERMUX_PREFIX_DIR_PATH);

        List<String[]> entries = new ArrayList<String[]>();
        int skipped = 0, failed = 0;
        List<String> problems = new ArrayList<String>();

        for (String raw : text.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.trim().length() == 0) continue;

            String[] fields = line.split("\t", -1);
            if (fields.length != 3) {
                skipped++;
                note(problems, "malformed line: " + line);
                continue;
            }
            fields[0] = fields[0].trim();
            fields[1] = fields[1].trim();
            fields[2] = fields[2].trim();
            if (!"file".equals(fields[1]) && !"link".equals(fields[1])) {
                skipped++;
                note(problems, "unknown kind '" + fields[1] + "' for " + fields[0]);
                continue;
            }
            entries.add(fields);
        }

        int created = 0, repointed = 0;
        // File entries first: a link may name a path that another entry
        // creates (bin/awk -> gawk sorts before bin/gawk), and a link whose
        // target does not exist *yet* must not be skipped.
        for (int pass = 0; pass < 2; pass++) {
            String want = pass == 0 ? "file" : "link";
            for (String[] fields : entries) {
                if (!want.equals(fields[1])) continue;
                try {
                    int outcome = pass == 0
                            ? linkFile(prefix, nativeDir, fields[0], fields[2], problems)
                            : linkPrefix(prefix, fields[0], fields[2], problems);
                    if (outcome == CREATED) created++;
                    else if (outcome == REPOINTED) repointed++;
                    else if (outcome == SKIPPED) skipped++;
                } catch (Throwable t) {
                    failed++;
                    note(problems, fields[0] + ": " + t);
                }
            }
        }

        for (String problem : problems) {
            try { JawnLog.append("runtime-bins: " + problem); } catch (Throwable ignored) { }
        }

        return "created=" + created + " repointed=" + repointed
                + " skipped=" + skipped + " failed=" + failed;
    }

    /** {@code <path> file <slug>}: link to {@code nativeLibraryDir/lib<slug>.so}. */
    private static int linkFile(String prefix, String nativeDir, String path, String slug,
                                List<String> problems) throws Exception {
        if (slug.length() == 0 || slug.indexOf('/') >= 0 || slug.indexOf('\\') >= 0
                || slug.indexOf("..") >= 0 || slug.indexOf('\0') >= 0) {
            note(problems, "unsafe slug '" + slug + "' for " + path);
            return SKIPPED;
        }
        String linkPath = prefixPath(prefix, path);
        if (linkPath == null || !allowed(prefix, linkPath)) {
            note(problems, "refusing to link outside $PREFIX/bin or $PREFIX/libexec: " + path);
            return SKIPPED;
        }

        String targetPath = new File(nativeDir, "lib" + slug + ".so").getAbsolutePath();
        if (!new File(targetPath).isFile()) {
            note(problems, linkPath + ": " + targetPath + " is not in this APK");
            return SKIPPED;
        }
        return pointAt(linkPath, targetPath, problems);
    }

    /** {@code <path> link <target>}: link to another path under the prefix. */
    private static int linkPrefix(String prefix, String path, String target,
                                  List<String> problems) throws Exception {
        String linkPath = prefixPath(prefix, path);
        if (linkPath == null || !allowed(prefix, linkPath)) {
            note(problems, "refusing to link outside $PREFIX/bin or $PREFIX/libexec: " + path);
            return SKIPPED;
        }

        String targetPath = target.startsWith("/")
                ? normalize(target)
                : prefixPath(prefix, target);
        if (targetPath == null) {
            note(problems, "bad link target '" + target + "' for " + path);
            return SKIPPED;
        }
        // A link whose target is gone is worse than no link: perl (or git)
        // would fail on it. Leaving an existing link alone is the safe skip.
        if (!new File(targetPath).exists()) {
            note(problems, linkPath + " -> " + targetPath + ": target missing; skipping");
            return SKIPPED;
        }
        return pointAt(linkPath, targetPath, problems);
    }

    /** Make {@code linkPath} a symlink to {@code targetPath}. */
    private static int pointAt(String linkPath, String targetPath, List<String> problems)
            throws Exception {
        File link = new File(linkPath);
        if (isSymlink(link)) {
            if (targetPath.equals(readlink(link))) return UNCHANGED;
            unlink(link);
            makeParent(link);
            Os.symlink(targetPath, linkPath);
            return REPOINTED;
        }
        if (link.exists()) {
            if (link.isDirectory()) {
                note(problems, linkPath + " is a directory; leaving it");
                return SKIPPED;
            }
            // Only $PREFIX/bin and $PREFIX/libexec get here (see allowed()),
            // so replacing the stale regular file is the intended repair.
            unlink(link);
        }
        makeParent(link);
        Os.symlink(targetPath, linkPath);
        return CREATED;
    }

    private static void makeParent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("could not create " + parent);
        }
    }

    private static void unlink(File file) throws IOException {
        try {
            Os.remove(file.getAbsolutePath());
        } catch (Throwable t) {
            if (!file.delete()) throw new IOException("could not remove " + file);
        }
    }

    private static boolean isSymlink(File file) {
        try {
            StructStat st = Os.lstat(file.getAbsolutePath());
            return (st.st_mode & OsConstants.S_IFMT) == OsConstants.S_IFLNK;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readlink(File file) {
        try {
            return Os.readlink(file.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Only the prefix's bin and libexec are ours to rewrite. */
    private static boolean allowed(String prefix, String path) {
        return path.startsWith(prefix + "/bin/") || path.startsWith(prefix + "/libexec/");
    }

    /** Resolve a manifest path against the prefix, refusing to escape it. */
    private static String prefixPath(String prefix, String relative) {
        if (relative == null) return null;
        String rel = relative.trim().replace('\\', '/');
        if (rel.length() == 0 || rel.startsWith("/")) return null;
        for (String segment : rel.split("/")) {
            if ("..".equals(segment)) return null;
        }
        String absolute = normalize(prefix + "/" + rel);
        return absolute.startsWith(prefix + "/") ? absolute : null;
    }

    /** Collapse ".", ".." and empty segments; the result is absolute. */
    private static String normalize(String path) {
        String[] parts = path.replace('\\', '/').split("/");
        ArrayDeque<String> out = new ArrayDeque<String>();
        for (String part : parts) {
            if (part.length() == 0 || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!out.isEmpty()) out.removeLast();
                continue;
            }
            out.addLast(part);
        }
        StringBuilder builder = new StringBuilder();
        for (String part : out) builder.append('/').append(part);
        return builder.length() == 0 ? "/" : builder.toString();
    }

    private static void note(List<String> problems, String text) {
        if (problems.size() < 20) problems.add(text);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
        return out.toString("UTF-8");
    }
}
