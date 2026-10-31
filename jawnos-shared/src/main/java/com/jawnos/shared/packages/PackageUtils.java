package com.jawnos.shared.packages;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Process;

import androidx.annotation.NonNull;

import com.jawnos.shared.logger.Logger;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Small, dependency-free helpers for reading this app's package information.
 *
 * This replaces the original termux-shared file (GPL-3.0-only). JawnOS ships as
 * one app, so "the package" is always the calling package; the context helpers
 * exist because the shared preferences classes were written to ask another
 * package for its context, and returning the caller's own context is the
 * correct answer now. The SELinux/SELinux-user reads are gone with the terminal
 * stack; they only fed the old debug report and are always empty here.
 */
public final class PackageUtils {

    private static final String LOG_TAG = "PackageUtils";

    private PackageUtils() { }

    /** The context for a package: this app's own context, or a package context. */
    public static Context getContextForPackage(@NonNull final Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()
            || packageName.equals(context.getPackageName())) return context;
        try {
            return context.createPackageContext(packageName, 0);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Could not get context for package \"" + packageName + "\": " + e.getMessage());
            return null;
        }
    }

    /** Like {@link #getContextForPackage}, but a missing package can end the app. */
    public static Context getContextForPackageOrExitApp(@NonNull Context context, String packageName,
                                                        final boolean exitAppOnError) {
        Context packageContext = getContextForPackage(context, packageName);
        if (packageContext == null && exitAppOnError) {
            Logger.logError(LOG_TAG, "Exiting because the context for package \"" + packageName + "\" is unavailable");
            System.exit(1);
        }
        return packageContext;
    }

    /** The SELinux info string is no longer read; empty keeps the report clean. */
    public static String getApplicationInfoSeInfoForPackage(@NonNull final ApplicationInfo applicationInfo) {
        return "";
    }

    /** The SELinux user string is no longer read; empty keeps the report clean. */
    public static String getApplicationInfoSeInfoUserForPackage(@NonNull final ApplicationInfo applicationInfo) {
        return "";
    }

    public static String getAppNameForPackage(@NonNull final Context context) {
        try {
            ApplicationInfo info = context.getApplicationInfo();
            CharSequence label = info.loadLabel(context.getPackageManager());
            return label == null ? "" : label.toString();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Could not read the app name: " + e.getMessage());
            return "";
        }
    }

    public static String getPackageNameForPackage(@NonNull final Context context) {
        return context.getPackageName();
    }

    public static int getTargetSDKForPackage(@NonNull final Context context) {
        try {
            return context.getApplicationInfo().targetSdkVersion;
        } catch (Exception e) {
            return -1;
        }
    }

    public static Boolean isAppForPackageADebuggableBuild(@NonNull final Context context) {
        try {
            return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static Boolean isAppInstalledOnExternalStorage(@NonNull final Context context) {
        try {
            return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static Integer getVersionCodeForPackage(@NonNull final Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Could not read the version code: " + e.getMessage());
            return null;
        }
    }

    public static String getVersionNameForPackage(@NonNull final Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Could not read the version name: " + e.getMessage());
            return null;
        }
    }

    /**
     * The SHA-256 of the signing certificate, uppercase hex, or null when the
     * certificate cannot be read. The old implementation needed reflection for
     * hidden API access; the public PackageManager path is enough on this app's
     * minSdk.
     */
    public static String getSigningCertificateSHA256DigestForPackage(@NonNull final Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            Signature[] signatures = info.signatures;
            if (signatures == null || signatures.length == 0) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signatures[0].toByteArray());
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format(Locale.US, "%02X", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Could not read the signing certificate: " + e.getMessage());
            return null;
        }
    }

    /** There is no profile concept in this app; kept for the debug report shape. */
    public static Long getSerialNumberForCurrentUser(@NonNull Context context) {
        return null;
    }

    /** The primary user is uid 0; an app in any other profile is not. */
    public static boolean isCurrentUserThePrimaryUser(@NonNull Context context) {
        return Process.myUid() / 100000 == 0;
    }

    /** There is no device/profile owner here; kept for the debug report shape. */
    public static String getProfileOwnerPackageNameForUser(@NonNull Context context) {
        return null;
    }
}
