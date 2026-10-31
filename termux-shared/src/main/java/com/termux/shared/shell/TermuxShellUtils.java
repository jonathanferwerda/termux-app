package com.termux.shared.shell;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import androidx.annotation.NonNull;

import com.termux.shared.android.SELinuxUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.models.errors.Error;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.packages.PackageUtils;
import com.termux.shared.termux.TermuxUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TermuxShellUtils {

    public static String TERMUX_VERSION_NAME;
    public static String TERMUX_IS_DEBUGGABLE_BUILD;
    public static String TERMUX_APP_PID;
    public static String TERMUX_APK_RELEASE;

    public static String TERMUX_API_VERSION_NAME;

    public static String getDefaultWorkingDirectoryPath() {
        return TermuxConstants.TERMUX_HOME_DIR_PATH;
    }

    public static String getDefaultBinPath() {
        return TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
    }

    public static String[] buildEnvironment(Context currentPackageContext, boolean isFailSafe, String workingDirectory) {
        TermuxConstants.TERMUX_HOME_DIR.mkdirs();

        if (workingDirectory == null || workingDirectory.isEmpty())
            workingDirectory = getDefaultWorkingDirectoryPath();

        List<String> environment = new ArrayList<>();

        loadTermuxEnvVariables(currentPackageContext);

        if (TERMUX_VERSION_NAME != null)
            environment.add("TERMUX_VERSION=" + TERMUX_VERSION_NAME);
        if (TERMUX_IS_DEBUGGABLE_BUILD != null)
            environment.add("TERMUX_IS_DEBUGGABLE_BUILD=" + TERMUX_IS_DEBUGGABLE_BUILD);
        if (TERMUX_APP_PID != null)
            environment.add("TERMUX_APP_PID=" + TERMUX_APP_PID);
        if (TERMUX_APK_RELEASE != null)
            environment.add("TERMUX_APK_RELEASE=" + TERMUX_APK_RELEASE);

        if (TERMUX_API_VERSION_NAME != null)
            environment.add("TERMUX_API_VERSION=" + TERMUX_API_VERSION_NAME);



        environment.add("TERM=xterm-256color");
        environment.add("COLORTERM=truecolor");

        try {
            ApplicationInfo applicationInfo = currentPackageContext.getPackageManager().getApplicationInfo(
                TermuxConstants.TERMUX_PACKAGE_NAME, 0);
            if (applicationInfo != null && !applicationInfo.enabled) {
                applicationInfo = null;
            }

            if (applicationInfo != null) {
                environment.add("TERMUX_APP__DATA_DIR=" + applicationInfo.dataDir);
                environment.add("TERMUX_APP__LEGACY_DATA_DIR=" + "/data/data/" + applicationInfo.packageName);

                environment.add("TERMUX_APP__SE_FILE_CONTEXT=" + SELinuxUtils.getFileContext(applicationInfo.dataDir));

                String seInfoUser = PackageUtils.getApplicationInfoSeInfoUserForPackage(applicationInfo);
                environment.add("TERMUX_APP__SE_INFO=" + PackageUtils.getApplicationInfoSeInfoForPackage(applicationInfo) +
                    (DataUtils.isNullOrEmpty(seInfoUser) ? "" : seInfoUser));
            }

        } catch (final Exception e) {
            // Ignore
        }

        environment.add("TERMUX__ROOTFS_DIR=" + TermuxConstants.TERMUX_FILES_DIR_PATH);
        environment.add("HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH);
        environment.add("TERMUX__HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH);
        environment.add("PREFIX=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        environment.add("TERMUX__PREFIX=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH);

        environment.add("TERMUX__SE_PROCESS_CONTEXT=" + SELinuxUtils.getContext());

        environment.add("BOOTCLASSPATH=" + System.getenv("BOOTCLASSPATH"));
        environment.add("ANDROID_ROOT=" + System.getenv("ANDROID_ROOT"));
        environment.add("ANDROID_DATA=" + System.getenv("ANDROID_DATA"));
        // EXTERNAL_STORAGE is needed for /system/bin/am to work on at least
        // Samsung S7 - see https://plus.google.com/110070148244138185604/posts/gp8Lk3aCGp3.
        environment.add("EXTERNAL_STORAGE=" + System.getenv("EXTERNAL_STORAGE"));

        // These variables are needed if running on Android 10 and higher.
        addToEnvIfPresent(environment, "ANDROID_ART_ROOT");
        addToEnvIfPresent(environment, "DEX2OATBOOTCLASSPATH");
        addToEnvIfPresent(environment, "ANDROID_I18N_ROOT");
        addToEnvIfPresent(environment, "ANDROID_RUNTIME_ROOT");
        addToEnvIfPresent(environment, "ANDROID_TZDATA_ROOT");

        environment.add("ANDROID__BUILD_VERSION_SDK=" + Build.VERSION.SDK_INT);

        if (isFailSafe) {
            // Keep the default path so that system binaries can be used in the failsafe session.
            environment.add("PATH= " + System.getenv("PATH"));
        } else {
            environment.add("LANG=en_US.UTF-8");
            environment.add("PATH=" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            // The runtime's binaries find their libraries here and nowhere else:
            // bin/login used to export this, and the app's own runtime has no
            // login. Without it every tool that links a prefix library (git and
            // libpcre2-8, curl and libcurl, ssh and libldns) fails to start.
            environment.add("LD_LIBRARY_PATH=" + TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
            addRuntimeEnvironment(environment);
            environment.add("PWD=" + workingDirectory);
            environment.add("TMPDIR=" + TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        }

        return environment.toArray(new String[0]);
    }

    /**
     * The variables the runtime's own binaries need when the prefix is not the
     * path they were compiled for. Every one of them is a path Termux baked into
     * a binary at build time: perl's @INC, git's exec path, and the CA bundle
     * libcurl was told to trust. Each is added only when the file it names is
     * really there, so a runtime built for this prefix needs none of them.
     */
    public static void addRuntimeEnvironment(List<String> environment) {
        java.io.File prefix = TermuxConstants.TERMUX_PREFIX_DIR;

        java.io.File gitCore = new java.io.File(prefix, "libexec/git-core");
        if (gitCore.isDirectory()) {
            environment.add("GIT_EXEC_PATH=" + gitCore.getAbsolutePath());
            java.io.File templates = new java.io.File(prefix, "share/git-core/templates");
            if (templates.isDirectory()) environment.add("GIT_TEMPLATE_DIR=" + templates.getAbsolutePath());
        }

        java.io.File ca = new java.io.File(prefix, "etc/tls/cert.pem");
        if (ca.isFile()) {
            environment.add("SSL_CERT_FILE=" + ca.getAbsolutePath());
            environment.add("GIT_SSL_CAINFO=" + ca.getAbsolutePath());
        }

        String perlLib = perlLibraryPath(new java.io.File(prefix, "lib"));
        if (perlLib != null) environment.add("PERL5LIB=" + perlLib);
    }

    /** Every versioned perl5 tree in the prefix, with its site_perl and arch
     *  directories, so perl finds the modules a moved prefix left behind. */
    private static String perlLibraryPath(java.io.File lib) {
        java.io.File perl5 = new java.io.File(lib, "perl5");
        java.io.File[] versions = perl5.listFiles();
        if (versions == null) return null;
        List<String> dirs = new ArrayList<>();
        for (java.io.File version : versions) {
            if (!version.isDirectory() || !version.getName().matches("\\d+\\.\\d+\\.\\d+")) continue;
            dirs.add(version.getAbsolutePath());
            addArchDirectories(dirs, version);
            java.io.File site = new java.io.File(perl5, "site_perl/" + version.getName());
            if (site.isDirectory()) {
                dirs.add(site.getAbsolutePath());
                addArchDirectories(dirs, site);
            }
        }
        return dirs.isEmpty() ? null : String.join(":", dirs);
    }

    private static void addArchDirectories(List<String> dirs, java.io.File parent) {
        java.io.File[] children = parent.listFiles();
        if (children == null) return;
        for (java.io.File child : children) {
            if (child.isDirectory() && child.getName().endsWith("-android")) dirs.add(child.getAbsolutePath());
        }
    }

    public static void addToEnvIfPresent(List<String> environment, String name) {
        String value = System.getenv(name);
        if (value != null) {
            environment.add(name + "=" + value);
        }
    }

    public static String[] setupProcessArgs(@NonNull String fileToExecute, String[] arguments) {
        // The file to execute may either be:
        // - An elf file, in which we execute it directly.
        // - A script file without shebang, which we execute with our standard shell $PREFIX/bin/sh instead of the
        //   system /system/bin/sh. The system shell may vary and may not work at all due to LD_LIBRARY_PATH.
        // - A file with shebang, which we try to handle with e.g. /bin/foo -> $PREFIX/bin/foo.
        String interpreter = null;
        try {
            File file = new File(fileToExecute);
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[256];
                int bytesRead = in.read(buffer);
                if (bytesRead > 4) {
                    if (buffer[0] == 0x7F && buffer[1] == 'E' && buffer[2] == 'L' && buffer[3] == 'F') {
                        // Elf file, do nothing.
                    } else if (buffer[0] == '#' && buffer[1] == '!') {
                        // Try to parse shebang.
                        StringBuilder builder = new StringBuilder();
                        for (int i = 2; i < bytesRead; i++) {
                            char c = (char) buffer[i];
                            if (c == ' ' || c == '\n') {
                                if (builder.length() == 0) {
                                    // Skip whitespace after shebang.
                                } else {
                                    // End of shebang.
                                    String executable = builder.toString();
                                    if (executable.startsWith("/usr") || executable.startsWith("/bin")) {
                                        String[] parts = executable.split("/");
                                        String binary = parts[parts.length - 1];
                                        interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + binary;
                                    }
                                    break;
                                }
                            } else {
                                builder.append(c);
                            }
                        }
                    } else {
                        // No shebang and no ELF, use standard shell.
                        interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh";
                    }
                }
            }
        } catch (IOException e) {
            // Ignore.
        }

        List<String> result = new ArrayList<>();
        if (interpreter != null) result.add(interpreter);
        result.add(fileToExecute);
        if (arguments != null) Collections.addAll(result, arguments);
        return result.toArray(new String[0]);
    }

    public static void clearTermuxTMPDIR(boolean onlyIfExists) {
        if(onlyIfExists && !FileUtils.directoryFileExists(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, false))
            return;

        Error error;
        error = FileUtils.clearDirectory("$TMPDIR", FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null));
        if (error != null) {
            Logger.logErrorExtended(error.toString());
        }
    }

    public static void loadTermuxEnvVariables(Context currentPackageContext) {
        String termuxAPKReleaseOld = TERMUX_APK_RELEASE;
        TERMUX_VERSION_NAME = TERMUX_IS_DEBUGGABLE_BUILD = TERMUX_APP_PID = TERMUX_APK_RELEASE = null;

        // Check if Termux app is installed and not disabled
        if (TermuxUtils.isTermuxAppInstalled(currentPackageContext) == null) {
            // This function may be called by a different package like a plugin, so we get version for Termux package via its context
            Context termuxPackageContext = TermuxUtils.getTermuxPackageContext(currentPackageContext);
            if (termuxPackageContext != null) {
                TERMUX_VERSION_NAME = PackageUtils.getVersionNameForPackage(termuxPackageContext);
                TERMUX_IS_DEBUGGABLE_BUILD = PackageUtils.isAppForPackageADebuggableBuild(termuxPackageContext) ? "1" : "0";

                TERMUX_APP_PID = TermuxUtils.getTermuxAppPID(currentPackageContext);

                // Getting APK signature is a slightly expensive operation, so do it only when needed
                if (termuxAPKReleaseOld == null) {
                    String signingCertificateSHA256Digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(termuxPackageContext);
                    if (signingCertificateSHA256Digest != null)
                        TERMUX_APK_RELEASE = TermuxUtils.getAPKRelease(signingCertificateSHA256Digest).replaceAll("[^a-zA-Z]", "_").toUpperCase();
                } else {
                    TERMUX_APK_RELEASE = termuxAPKReleaseOld;
                }
            }
        }


        TERMUX_API_VERSION_NAME = null;

        // Check if Termux:API app is installed and not disabled
        if (TermuxUtils.isTermuxAPIAppInstalled(currentPackageContext) == null) {
            // This function may be called by a different package like a plugin, so we get version for Termux:API package via its context
            Context termuxAPIPackageContext = TermuxUtils.getTermuxAPIPackageContext(currentPackageContext);
            if (termuxAPIPackageContext != null)
                TERMUX_API_VERSION_NAME = PackageUtils.getVersionNameForPackage(termuxAPIPackageContext);
        }
    }

}
