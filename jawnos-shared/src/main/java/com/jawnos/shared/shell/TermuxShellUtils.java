package com.jawnos.shared.shell;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;

import androidx.annotation.NonNull;

import com.jawnos.shared.packages.PackageUtils;
import com.jawnos.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the environment the app's own runtime is started with.
 *
 * The original termux-shared file was written for a terminal session that
 * inherited the Termux app's environment and for plugin packages. JawnOS forks
 * the runtime directly, so this keeps the same variable set (the tools and the
 * moved prefix depend on it) without the terminal/session half.
 */
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

    public static String[] buildEnvironment(Context context, boolean isFailSafe, String workingDirectory) {
        TermuxConstants.TERMUX_HOME_DIR.mkdirs();
        if (workingDirectory == null || workingDirectory.isEmpty()) {
            workingDirectory = getDefaultWorkingDirectoryPath();
        }

        List<String> environment = new ArrayList<>();
        loadTermuxEnvVariables(context);

        addIfSet(environment, "TERMUX_VERSION", TERMUX_VERSION_NAME);
        addIfSet(environment, "TERMUX_IS_DEBUGGABLE_BUILD", TERMUX_IS_DEBUGGABLE_BUILD);
        addIfSet(environment, "TERMUX_APP_PID", TERMUX_APP_PID);
        addIfSet(environment, "TERMUX_APK_RELEASE", TERMUX_APK_RELEASE);
        addIfSet(environment, "TERMUX_API_VERSION", TERMUX_API_VERSION_NAME);

        environment.add("TERM=xterm-256color");
        environment.add("COLORTERM=truecolor");

        try {
            ApplicationInfo info = context.getApplicationInfo();
            if (info != null) {
                environment.add("TERMUX_APP__DATA_DIR=" + info.dataDir);
                environment.add("TERMUX_APP__LEGACY_DATA_DIR=/data/data/" + info.packageName);
            }
        } catch (Exception ignored) { }

        environment.add("TERMUX__ROOTFS_DIR=" + TermuxConstants.TERMUX_FILES_DIR_PATH);
        environment.add("HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH);
        environment.add("TERMUX__HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH);
        environment.add("PREFIX=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        environment.add("TERMUX__PREFIX=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH);

        for (String name : new String[] {
            "BOOTCLASSPATH", "ANDROID_ROOT", "ANDROID_DATA", "EXTERNAL_STORAGE",
            "ANDROID_ART_ROOT", "DEX2OATBOOTCLASSPATH", "ANDROID_I18N_ROOT",
            "ANDROID_RUNTIME_ROOT", "ANDROID_TZDATA_ROOT" }) {
            addToEnvIfPresent(environment, name);
        }

        environment.add("ANDROID__BUILD_VERSION_SDK=" + Build.VERSION.SDK_INT);

        if (isFailSafe) {
            environment.add("PATH= " + System.getenv("PATH"));
        } else {
            environment.add("LANG=en_US.UTF-8");
            environment.add("PATH=" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            // The runtime's tools link prefix libraries (git/libpcre2, curl,
            // ssh/libldns); bin/login used to export this and JawnOS has none.
            environment.add("LD_LIBRARY_PATH=" + TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
            addRuntimeEnvironment(environment);
            environment.add("PWD=" + workingDirectory);
            environment.add("TMPDIR=" + TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        }

        return environment.toArray(new String[0]);
    }

    /** The path variables a runtime built for another prefix still needs. */
    public static void addRuntimeEnvironment(List<String> environment) {
        File prefix = TermuxConstants.TERMUX_PREFIX_DIR;

        File gitCore = new File(prefix, "libexec/git-core");
        if (gitCore.isDirectory()) {
            environment.add("GIT_EXEC_PATH=" + gitCore.getAbsolutePath());
            File templates = new File(prefix, "share/git-core/templates");
            if (templates.isDirectory()) environment.add("GIT_TEMPLATE_DIR=" + templates.getAbsolutePath());
        }

        File ca = new File(prefix, "etc/tls/cert.pem");
        if (ca.isFile()) {
            environment.add("SSL_CERT_FILE=" + ca.getAbsolutePath());
            environment.add("GIT_SSL_CAINFO=" + ca.getAbsolutePath());
        }

        String perlLib = perlLibraryPath(new File(prefix, "lib"));
        if (perlLib != null) environment.add("PERL5LIB=" + perlLib);
    }

    /** Every versioned perl5 tree in the prefix, including its arch dirs. */
    private static String perlLibraryPath(File lib) {
        File perl5 = new File(lib, "perl5");
        File[] versions = perl5.listFiles();
        if (versions == null) return null;
        List<String> dirs = new ArrayList<>();
        for (File version : versions) {
            if (!version.isDirectory() || !version.getName().matches("\\d+\\.\\d+\\.\\d+")) continue;
            dirs.add(version.getAbsolutePath());
            addArchDirectories(dirs, version);
            File site = new File(perl5, "site_perl/" + version.getName());
            if (site.isDirectory()) {
                dirs.add(site.getAbsolutePath());
                addArchDirectories(dirs, site);
            }
        }
        return dirs.isEmpty() ? null : String.join(":", dirs);
    }

    private static void addArchDirectories(List<String> dirs, File parent) {
        File[] children = parent.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && child.getName().endsWith("-android")) dirs.add(child.getAbsolutePath());
        }
    }

    public static void addToEnvIfPresent(List<String> environment, String name) {
        String value = System.getenv(name);
        if (value != null) environment.add(name + "=" + value);
    }

    private static void addIfSet(List<String> environment, String name, String value) {
        if (value != null) environment.add(name + "=" + value);
    }

    /**
     * Resolve how to run a file: an ELF runs directly, a shebang naming /bin or
     * /usr is mapped into the prefix, and a script with no shebang runs under
     * the prefix's own sh (the system sh does not know the runtime's libraries).
     */
    public static String[] setupProcessArgs(@NonNull String fileToExecute, String[] arguments) {
        String interpreter = null;
        try {
            File file = new File(fileToExecute);
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[256];
                int read = in.read(buffer);
                if (read > 4) {
                    boolean elf = buffer[0] == 0x7F && buffer[1] == 'E' && buffer[2] == 'L' && buffer[3] == 'F';
                    if (!elf) {
                        if (buffer[0] == '#' && buffer[1] == '!') {
                            StringBuilder shebang = new StringBuilder();
                            for (int i = 2; i < read; i++) {
                                char c = (char) buffer[i];
                                if (c == ' ' || c == '\n') {
                                    if (shebang.length() > 0) {
                                        String executable = shebang.toString();
                                        if (executable.startsWith("/usr") || executable.startsWith("/bin")) {
                                            String[] parts = executable.split("/");
                                            interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                                                + "/" + parts[parts.length - 1];
                                        }
                                        break;
                                    }
                                } else {
                                    shebang.append(c);
                                }
                            }
                        } else {
                            interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh";
                        }
                    }
                }
            }
        } catch (Exception ignored) { }

        List<String> result = new ArrayList<>();
        if (interpreter != null) result.add(interpreter);
        result.add(fileToExecute);
        if (arguments != null) Collections.addAll(result, arguments);
        return result.toArray(new String[0]);
    }

    /** The version and release markers the runtime's scripts read. */
    public static void loadTermuxEnvVariables(Context context) {
        TERMUX_VERSION_NAME = PackageUtils.getVersionNameForPackage(context);
        TERMUX_IS_DEBUGGABLE_BUILD =
            Boolean.TRUE.equals(PackageUtils.isAppForPackageADebuggableBuild(context)) ? "1" : "0";
        TERMUX_APP_PID = String.valueOf(Process.myPid());
        TERMUX_APK_RELEASE = "JAWNOS";
        TERMUX_API_VERSION_NAME = null;
    }
}
