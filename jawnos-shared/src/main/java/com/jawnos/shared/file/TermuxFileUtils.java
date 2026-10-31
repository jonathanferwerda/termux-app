package com.jawnos.shared.file;

import android.content.Context;
import android.system.Os;

import androidx.annotation.NonNull;

import com.jawnos.shared.logger.Logger;
import com.jawnos.shared.models.errors.Error;
import com.jawnos.shared.termux.TermuxConstants;

import java.io.File;

/**
 * Checks that the app's private directories exist and are usable.
 *
 * The original termux-shared file was a 377-line path/permission toolkit for a
 * Termux prefix that shared a uid with plugin apps. JawnOS only needs the three
 * directory checks the installer makes before the runtime is unpacked, so this
 * file is now those checks and nothing else.
 */
public class TermuxFileUtils {

    private static final String LOG_TAG = "TermuxFileUtils";

    public static Error isTermuxFilesDirectoryAccessible(@NonNull final Context context,
                                                         boolean createDirectoryIfMissing,
                                                         boolean setMissingPermissions) {
        // Ensure the app's private files directory exists first; the runtime
        // lives inside it.
        try { context.getFilesDir(); } catch (Exception e) {
            return new Error("FileUtils", 0, "Could not access the app files directory: " + e.getMessage());
        }
        return checkDirectory("termux files directory", TermuxConstants.TERMUX_FILES_DIR_PATH,
            createDirectoryIfMissing, setMissingPermissions);
    }

    public static Error isTermuxPrefixDirectoryAccessible(boolean createDirectoryIfMissing,
                                                          boolean setMissingPermissions) {
        return checkDirectory("termux prefix directory", TermuxConstants.TERMUX_PREFIX_DIR_PATH,
            createDirectoryIfMissing, setMissingPermissions);
    }

    public static Error isTermuxPrefixStagingDirectoryAccessible(boolean createDirectoryIfMissing,
                                                                 boolean setMissingPermissions) {
        return checkDirectory("termux prefix staging directory",
            TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH,
            createDirectoryIfMissing, setMissingPermissions);
    }

    private static Error checkDirectory(String label, String path,
                                        boolean createDirectoryIfMissing,
                                        boolean setMissingPermissions) {
        File directory = new File(path);
        if (!directory.exists()) {
            if (!createDirectoryIfMissing) {
                return new Error("FileUtils", 1, "The " + label + " does not exist at \"" + path + "\"");
            }
            if (!directory.mkdirs() && !directory.isDirectory()) {
                return new Error("FileUtils", 2, "Could not create the " + label + " at \"" + path + "\"");
            }
        }
        if (!directory.isDirectory()) {
            return new Error("FileUtils", 3, "The " + label + " is not a directory at \"" + path + "\"");
        }
        if (setMissingPermissions) {
            try {
                Os.chmod(path, 0700);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Could not set permissions on " + label + ": " + e.getMessage());
            }
        }
        if (!directory.canRead() || !directory.canWrite() || !directory.canExecute()) {
            return new Error("FileUtils", 4, "The " + label + " is not readable, writable and executable at \"" + path + "\"");
        }
        return null;
    }
}
