package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-update from the staged releases the mesh replicates into
 * &lt;jawn dir&gt;/releases.
 *
 * jawn (on this device, or on any peer via the app-release pull) drops the
 * newest jawnos-app-&lt;versionCode&gt;-&lt;versionName&gt;.apk there. This compares that
 * versionCode against the installed app and, when it is newer, hands the file
 * to the system installer - the same one-tap prompt a sideload gives.
 */
public class JawnUpdater {

    public static class Release {
        public final File file;
        public final int versionCode;
        public final String versionName;
        Release(File file, int versionCode, String versionName) {
            this.file = file;
            this.versionCode = versionCode;
            this.versionName = versionName;
        }
    }

    private static final Pattern APP_APK = Pattern.compile("^jawnos-app-(\\d+)-(.+)\\.apk$");
    private static final String AUTHORITY_SUFFIX = ".jawnupdates";

    public static File releasesDir(Context context) {
        // Build from getFilesDir() rather than TermuxConstants' /data/data path.
        // FileProvider's <files-path> root is getFilesDir(), and getUriForFile
        // matches path prefixes - getFilesDir() reports /data/user/0/... while
        // TermuxConstants uses /data/data/..., so the two must agree here.
        return new File(context.getFilesDir(),
            "home/" + JawnLauncher.getJawnDirName(context) + "/releases");
    }

    /** The newest staged JawnOS app APK, or null if none is present. */
    public static Release newestRelease(Context context) {
        File[] files = releasesDir(context).listFiles();
        if (files == null) return null;
        Release best = null;
        for (File f : files) {
            Matcher m = APP_APK.matcher(f.getName());
            if (!m.matches()) continue;
            int vc;
            try { vc = Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { continue; }
            if (best == null || vc > best.versionCode) best = new Release(f, vc, m.group(2));
        }
        return best;
    }

    public static int installedVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /** A staged build newer than the installed app, or null. */
    public static Release updateAvailable(Context context) {
        Release r = newestRelease(context);
        if (r != null && r.versionCode > installedVersionCode(context)) return r;
        return null;
    }

    /** Whether the "install unknown apps" grant is in place (Android 8+). */
    public static boolean canInstall(Context context) {
        if (Build.VERSION.SDK_INT >= 26) return context.getPackageManager().canRequestPackageInstalls();
        return true;
    }

    /** Open the per-app "install unknown apps" screen. */
    public static void openInstallSettings(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        }
    }

    /** Hand the staged APK to the system installer (one tap). */
    public static String install(Context context, Release release) {
        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + AUTHORITY_SUFFIX, release.file);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return "installer opened";
        } catch (Throwable t) {
            return "install failed: " + t;
        }
    }
}
