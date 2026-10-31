package com.jawnos.shared.crash;

import android.content.Context;

import androidx.annotation.NonNull;

import com.jawnos.shared.jawnos.JawnConstants;
import com.jawnos.shared.jawnos.JawnUtils;

/**
 * Installs the crash handler for the JawnOS app and tells it what to write.
 *
 * The original termux-shared file also carried the plugin-app assumptions;
 * this is the same adapter without them. CrashHandler itself is MIT.
 */
public class JawnCrashUtils implements CrashHandler.CrashHandlerClient {

    public static void setCrashHandler(@NonNull final Context context) {
        CrashHandler.setCrashHandler(context, new JawnCrashUtils());
    }

    @NonNull
    @Override
    public String getCrashLogFilePath(Context context) {
        return JawnConstants.TERMUX_CRASH_LOG_FILE_PATH;
    }

    @Override
    public String getAppInfoMarkdownString(Context context) {
        return JawnUtils.getAppInfoMarkdownString(context, true);
    }
}
