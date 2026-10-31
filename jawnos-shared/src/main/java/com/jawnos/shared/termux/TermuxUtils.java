package com.jawnos.shared.termux;

import android.content.Context;
import android.os.Process;

import androidx.annotation.NonNull;

import com.jawnos.shared.packages.PackageUtils;

/**
 * The About and crash-report text for the JawnOS app.
 *
 * This replaces the original termux-shared file (GPL-3.0-only). That file
 * described Termux, its plugin apps, its wiki, Reddit and donation page, and
 * looked up the Termux package contexts for a separate API app. JawnOS is one
 * app: there are no plugin packages, so the package lookups answer with this
 * app's own context and the "installed" checks answer null (installed), which
 * is what the surviving shared helpers use them for.
 */
public final class TermuxUtils {

    public static final String SOURCE_URL = "https://github.com/jonathanferwerda/termux-app";
    public static final String RELEASES_URL = "https://github.com/jonathanferwerda/jawnos/releases";
    public static final String ISSUES_URL = SOURCE_URL + "/issues";
    public static final String DOCS_URL = "https://github.com/jonathanferwerda/jawnos/blob/main/docs/leaving-termux.md";

    private TermuxUtils() { }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    public static Context getTermuxPackageContext(@NonNull Context context) {
        return context;
    }

    public static Context getTermuxAPIPackageContext(@NonNull Context context) {
        return context;
    }

    /** Null means "installed", the convention the shared shell helper expects. */
    public static String isTermuxAppInstalled(@NonNull final Context context) {
        return null;
    }

    /** Null means "installed"; there is no separate API app any more. */
    public static String isTermuxAPIAppInstalled(@NonNull final Context context) {
        return null;
    }

    public static String getTermuxPluginAppsInfoMarkdownString(final Context currentPackageContext) {
        return null;
    }

    public static String getAppInfoMarkdownString(final Context currentPackageContext,
                                                  final boolean returnTermuxPackageInfoToo) {
        StringBuilder markdown = new StringBuilder("## App Info");

        markdown.append("\n\n**APP_NAME**: ").append(text(PackageUtils.getAppNameForPackage(currentPackageContext)));
        markdown.append("\n\n**PACKAGE_NAME**: ").append(text(PackageUtils.getPackageNameForPackage(currentPackageContext)));
        markdown.append("\n\n**VERSION_NAME**: ").append(text(PackageUtils.getVersionNameForPackage(currentPackageContext)));

        Integer versionCode = PackageUtils.getVersionCodeForPackage(currentPackageContext);
        markdown.append("\n\n**VERSION_CODE**: ").append(versionCode == null ? "unknown" : String.valueOf(versionCode));
        markdown.append("\n\n**TARGET_SDK**: ").append(PackageUtils.getTargetSDKForPackage(currentPackageContext));
        markdown.append("\n\n**IS_DEBUGGABLE_BUILD**: ")
            .append(Boolean.TRUE.equals(PackageUtils.isAppForPackageADebuggableBuild(currentPackageContext)));
        markdown.append("\n\n**APK_RELEASE**: jawnos");

        if (returnTermuxPackageInfoToo) {
            markdown.append("\n\n**SOURCE**: ").append(SOURCE_URL);
        }
        return markdown.toString();
    }

    public static String getReportIssueMarkdownString(@NonNull final Context context) {
        return "## Report an issue\n\n"
            + "Open an issue at [" + ISSUES_URL + "](" + ISSUES_URL + ") and include the report above.";
    }

    public static String getImportantLinksMarkdownString(@NonNull final Context context) {
        StringBuilder markdown = new StringBuilder("## Important Links");
        markdown.append("\n\n### Source\n");
        markdown.append("\n[" + SOURCE_URL + "](" + SOURCE_URL + ")  ");
        markdown.append("\n\n### Releases\n");
        markdown.append("\n[" + RELEASES_URL + "](" + RELEASES_URL + ")  ");
        markdown.append("\n\n### Issues\n");
        markdown.append("\n[" + ISSUES_URL + "](" + ISSUES_URL + ")  ");
        markdown.append("\n\n### Documentation\n");
        markdown.append("\n[" + DOCS_URL + "](" + DOCS_URL + ")  ");
        return markdown.toString();
    }

    public static String getTermuxDebugMarkdownString(@NonNull final Context context) {
        return getAppInfoMarkdownString(context, true) + "\n\n"
            + AndroidUtils.getDeviceInfoMarkdownString(context);
    }

    /** The release name shown in the debug report; there are no store builds. */
    public static String getAPKRelease(String signingCertificateSHA256Digest) {
        return "jawnos";
    }

    public static String getTermuxAppPID(final Context context) {
        return String.valueOf(Process.myPid());
    }
}
