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
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.webkit.CookieManager;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Self-update from the staged releases the mesh replicates into
 * &lt;jawn dir&gt;/releases.
 *
 * jawn (on this device, or on any peer via the app-release pull) drops the
 * newest jawnos-app-&lt;versionCode&gt;-&lt;versionName&gt;.apk and
 * jawnos-api-&lt;versionCode&gt;-&lt;versionName&gt;.apk there. This compares each
 * product's versionCode against the installed package and, when it is newer,
 * hands the file to the system installer - the same one-tap prompt a sideload
 * gives. Both APKs share this app's signature and FileProvider.
 */
public class JawnUpdater {

    public static final String PRODUCT_APP = "app";
    public static final String PRODUCT_API = "api";

    public static class Release {
        public final File file;
        public final int versionCode;
        public final String versionName;
        public final String product;
        Release(File file, int versionCode, String versionName, String product) {
            this.file = file;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.product = product;
        }
    }

    private static final Pattern APK = Pattern.compile("^jawnos-(app|api)-(\\d+)-(.+)\\.apk$");
    private static final String AUTHORITY_SUFFIX = ".jawnupdates";
    private static final String API_PACKAGE = "com.termux.api";

    public static File releasesDir(Context context) {
        // Build from getFilesDir() rather than TermuxConstants' /data/data path.
        // FileProvider's <files-path> root is getFilesDir(), and getUriForFile
        // matches path prefixes - getFilesDir() reports /data/user/0/... while
        // TermuxConstants uses /data/data/..., so the two must agree here.
        return new File(context.getFilesDir(),
            "home/" + JawnLauncher.getJawnDirName(context) + "/releases");
    }

    public static String packageForProduct(String product) {
        return PRODUCT_API.equals(product) ? API_PACKAGE : null;
    }

    public static String labelForProduct(String product) {
        return PRODUCT_API.equals(product) ? "api" : "app";
    }

    /** The newest staged APK of one product, or null if none is present. */
    public static Release newestRelease(Context context, String product) {
        File[] files = releasesDir(context).listFiles();
        if (files == null) return null;
        Release best = null;
        for (File f : files) {
            Matcher m = APK.matcher(f.getName());
            if (!m.matches()) continue;
            if (!m.group(1).equals(product)) continue;
            int vc;
            try { vc = Integer.parseInt(m.group(2)); } catch (NumberFormatException e) { continue; }
            if (best == null || vc > best.versionCode) best = new Release(f, vc, m.group(3), product);
        }
        return best;
    }

    /** The newest staged app APK (kept for the old call sites). */
    public static Release newestRelease(Context context) {
        return newestRelease(context, PRODUCT_APP);
    }

    /** The installed versionCode of this app (product "app"). */
    public static int installedVersionCode(Context context) {
        return installedVersionCode(context, PRODUCT_APP);
    }

    /** The installed versionCode of a product, or -1 when it is not installed. */
    public static int installedVersionCode(Context context, String product) {
        String pkg = packageForProduct(product);
        if (pkg == null) pkg = context.getPackageName();
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /** A staged build newer than the installed app, or null. */
    public static Release updateAvailable(Context context) {
        return updateAvailable(context, PRODUCT_APP);
    }

    /** A staged build newer than the installed package of a product, or null. */
    public static Release updateAvailable(Context context, String product) {
        Release r = newestRelease(context, product);
        int installed = installedVersionCode(context, product);
        if (r != null && r.versionCode > installed) return r;
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

    /** Ask jawn to pull the newest builds now (GitHub + peers), so the local
     *  releases/ dir is fresh before updateAvailable() scans it. Best-effort: a
     *  failed call just leaves the existing scan in place. */
    public static void sync(Context context) {
        int port = JawnLauncher.getJawnPort(context);
        String manager = JawnLauncher.getManagerUrl(context, port);
        HttpsURLConnection conn = null;
        try {
            conn = (HttpsURLConnection) new URL(manager + "/app_release_sync").openConnection();
            // the house serves a self-signed wildcard cert; trust it for this
            // loopback call only
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[] { new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String auth) {}
                public void checkServerTrusted(X509Certificate[] chain, String auth) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            } }, new java.security.SecureRandom());
            conn.setSSLSocketFactory(sc.getSocketFactory());
            conn.setHostnameVerifier((host, session) -> true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            String cookie = CookieManager.getInstance().getCookie(manager);
            if (cookie != null && cookie.length() > 0) {
                conn.setRequestProperty("Cookie", cookie);
            }
            conn.connect();
            conn.getResponseCode();
        } catch (Throwable t) {
            // fall through; updateAvailable() still re-scans locally
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable t) { } }
        }
    }
}
