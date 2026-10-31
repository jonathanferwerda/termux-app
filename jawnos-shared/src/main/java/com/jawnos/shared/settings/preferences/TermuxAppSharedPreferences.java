package com.jawnos.shared.settings.preferences;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jawnos.shared.logger.Logger;
import com.jawnos.shared.packages.PackageUtils;
import com.jawnos.shared.termux.TermuxConstants;

/**
 * The app's own settings, kept in the same preference file and under the same
 * keys as before so an update does not reset them.
 *
 * The original termux-shared file carried the terminal, soft-keyboard and plugin
 * preferences and asked another package for its context. JawnOS only reads the
 * log level and the crash-notification switch, so this is those two and the
 * packaged context lookup.
 */
public class TermuxAppSharedPreferences {

    private static final String KEY_LOG_LEVEL = "log_level";
    private static final String KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED = "crash_report_notifications_enabled";
    private static final boolean DEFAULT_CRASH_REPORT_NOTIFICATIONS_ENABLED = true;

    private final SharedPreferences preferences;

    private TermuxAppSharedPreferences(@NonNull Context context) {
        preferences = context.getSharedPreferences(
            TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION,
            Context.MODE_PRIVATE);
    }

    @Nullable
    public static TermuxAppSharedPreferences build(@NonNull final Context context) {
        Context packageContext = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_PACKAGE_NAME);
        return packageContext == null ? null : new TermuxAppSharedPreferences(packageContext);
    }

    public static TermuxAppSharedPreferences build(@NonNull final Context context, final boolean exitAppOnError) {
        Context packageContext = PackageUtils.getContextForPackageOrExitApp(
            context, TermuxConstants.TERMUX_PACKAGE_NAME, exitAppOnError);
        return packageContext == null ? null : new TermuxAppSharedPreferences(packageContext);
    }

    public int getLogLevel() {
        return preferences.getInt(KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
    }

    public void setLogLevel(Context context, int logLevel) {
        int applied = Logger.setLogLevel(context, logLevel);
        preferences.edit().putInt(KEY_LOG_LEVEL, applied).apply();
    }

    public boolean areCrashReportNotificationsEnabled() {
        return preferences.getBoolean(KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED,
            DEFAULT_CRASH_REPORT_NOTIFICATIONS_ENABLED);
    }

    public void setCrashReportNotificationsEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, enabled).apply();
    }
}
