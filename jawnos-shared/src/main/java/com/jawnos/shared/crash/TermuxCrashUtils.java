package com.jawnos.shared.crash;

import android.content.Context;

import androidx.annotation.NonNull;

import com.jawnos.shared.termux.TermuxConstants;
import com.jawnos.shared.termux.TermuxUtils;

/**
 * Installs the crash handler for the JawnOS app and tells it what to write.
 *
 * The original termux-shared file also carried the plugin-app assumptions;
 * this is the same adapter without them. CrashHandler itself is MIT.
 */
public class TermuxCrashUtils implements CrashHandler.CrashHandlerClient {

    public static void setCrashHandler(@NonNull final Context context) {
        CrashHandler.setCrashHandler(context, new TermuxCrashUtils());
    }

    @NonNull
    @Override
    public String getCrashLogFilePath(Context context) {
        return TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH;
    }

    @Override
    public String getAppInfoMarkdownString(Context context) {
        return TermuxUtils.getAppInfoMarkdownString(context, true);
    }
}
