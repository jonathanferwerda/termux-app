package com.jawnos.app;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;

import com.jawnos.shared.shell.TermuxShellUtils;
import com.jawnos.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * First-run provisioning for a device that has no JawnOS yet.
 *
 * A fresh app has the terminal bootstrap but no house: no tree, no config.json
 * and no database. The mkosi image seeds its first boot from the tree it
 * carries; the phone fetches the same tree and runs the same setup, then opens
 * the manager. Every step is idempotent, so an interrupted run continues where
 * it stopped instead of starting over (and nothing is ever deleted).
 *
 * A device that already has the tree, config.json and database never enters
 * this path - see {@link JawnLauncher#isJawnInstalled(Context)}.
 */
final class JawnFirstRun {

    /** The canonical tree, the one docs/appliance-provisioning.md clones. */
    private static final String REPO = "https://github.com/jonathanferwerda/jawnos.git";
    private static final String BRANCH = "main";
    private static final String HOUSE_NAME = "JawnOS";
    private static final String UNLOCK_FILE = "UNLOCK-CODE.txt";

    private static final long GIT_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long CLONE_TIMEOUT_MS = 15 * 60 * 1000L;
    private static final long DEPS_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final long SEED_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final long TICKET_TIMEOUT_MS = 2 * 60 * 1000L;
    private static final long BOOT_TIMEOUT_MS = 3 * 60 * 1000L;

    interface Listener {
        /** A human-readable stage, shown on the splash. */
        void progress(String message);
        /** Setup finished; on success {@code message} is an optional warning. */
        void done(boolean ok, String message);
    }

    private JawnFirstRun() { }

    /** Set up the missing pieces in the background, then report once. */
    static void start(final Activity activity, final Listener listener) {
        final Handler ui = new Handler(Looper.getMainLooper());
        final Reporter report = new Reporter(activity, ui, listener);
        report.progress("Preparing the terminal...");
        // The bootstrap is Termux's own install path (it asks for the primary
        // user and shows its own progress and error dialogs). Everything after
        // it runs off the UI thread.
        TermuxInstaller.setupBootstrapIfNeeded(activity, () -> {
            final Context context = activity.getApplicationContext();
            new Thread(() -> {
                try {
                    String warning = provision(context, report);
                    report.done(true, warning);
                } catch (SetupException e) {
                    report.done(false, e.getMessage() + "\n\nTap Try again, or open the Admin Panel.");
                } catch (Throwable t) {
                    report.done(false, "Setup failed: " + t + "\n\nTap Try again, or open the Admin Panel.");
                }
            }, "jawn-first-run").start();
        });
    }

    /** Each stage continues an earlier attempt; nothing here deletes. */
    private static String provision(Context context, Reporter report) throws Exception {
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        File dir = new File(JawnLauncher.getJawnDirPath(context));

        if (!treeIsComplete(dir)) {
            boolean resume = isOurCheckout(dir);
            if (dir.exists() && !isEmptyDirectory(dir) && !resume) {
                throw new SetupException("The folder " + dir.getAbsolutePath() + " has files in it but is not a JawnOS install. Move it aside, then tap Try again.");
            }
            report.progress("Preparing the tree...");
            // The app's runtime (M3) carries git; there is no package manager to
            // install one from, so a missing git here means a broken runtime, not
            // something this step can repair.
            Result r = run(context, "command -v git >/dev/null 2>&1", home, GIT_TIMEOUT_MS);
            if (r.exitCode != 0) {
                throw new SetupException("This build's runtime has no git, so the JawnOS tree cannot be fetched. Reinstall the app.");
            }

            if (resume) {
                report.progress("Resuming the JawnOS download...");
                r = run(context, "export GIT_TERMINAL_PROMPT=0; git -C " + quote(dir.getAbsolutePath())
                        + " fetch --depth 1 origin " + BRANCH + " && git -C " + quote(dir.getAbsolutePath())
                        + " reset --hard FETCH_HEAD", home, CLONE_TIMEOUT_MS);
            } else {
                report.progress("Downloading JawnOS...");
                r = run(context, "export GIT_TERMINAL_PROMPT=0; git clone --depth 1 --branch " + BRANCH
                        + " " + quote(REPO) + " " + quote(dir.getAbsolutePath()), home, CLONE_TIMEOUT_MS);
            }
            if (r.exitCode != 0 || !treeIsComplete(dir)) {
                throw new SetupException("Could not fetch JawnOS." + detail(r));
            }
        }

        report.progress("Installing dependencies (this can take several minutes)...");
        Result r = run(context, "cd " + quote(dir.getAbsolutePath())
                + " && perl scripts/jawnos_deps.pl --install", dir, DEPS_TIMEOUT_MS);
        if (r.exitCode != 0) {
            throw new SetupException("Could not install JawnOS's dependencies." + detail(r));
        }

        File config = new File(dir, "config.json");
        File database = new File(dir, "database/jawnos.db");
        // The same test the activity uses for "already installed", so a config
        // that exists but has no signatorial is repaired here instead of
        // sending every launch back through first run.
        if (!JawnLauncher.isJawnInstalled(context)) {
            report.progress("Creating the house...");
            String code = nonEmpty(database) ? null : newUnlockCode();
            String command = "cd " + quote(dir.getAbsolutePath())
                    + " && perl scripts/jawnos_setup.pl --seed --name " + quote(HOUSE_NAME) + " --device mobile";
            if (code != null) command += " --code " + code;
            r = run(context, command, dir, SEED_TIMEOUT_MS);
            if (r.exitCode != 0 || !JawnLauncher.isJawnInstalled(context)) {
                throw new SetupException("Could not create the house's database." + detail(r));
            }
            if (code != null) writeUnlockCode(code);
        }

        // /sdcard is the house's file store and the settings point through
        // ~/storage/shared; termux-setup-storage used to make these links.
        linkExternalStorage(home);

        if (!JawnLauncher.jawnIsUp(context)) {
            report.progress("Starting JawnOS...");
            JawnLauncher.startJawn(context);
        }
        // The manager and the ticket desk are separate listeners; wait for
        // both so the ticketed URL cannot race a half-booted tree.
        int ticketPort = JawnLauncher.getJawnTicketPort(context);
        long deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS;
        while (!JawnLauncher.jawnIsUp(context)
                || !JawnLauncher.portOpen("127.0.0.1", ticketPort, 400)) {
            if (System.currentTimeMillis() > deadline) {
                throw new SetupException("JawnOS did not answer (manager port "
                        + JawnLauncher.getJawnPort(context) + ", ticket port " + ticketPort
                        + "). Open the Admin Panel to see the log, then try again.");
            }
            Thread.sleep(1000);
        }

        String warning = null;
        File ticket = new File(dir, ".shots/ticket.txt");
        if (!nonEmpty(ticket)) {
            report.progress("Signing this device in...");
            r = run(context, "cd " + quote(dir.getAbsolutePath())
                    + " && perl scripts/app_ticket.pl phone --save", dir, TICKET_TIMEOUT_MS);
            if (r.exitCode != 0 || !nonEmpty(ticket)) {
                warning = "The app ticket could not be minted. Sign in with the unlock code in ~/" + UNLOCK_FILE + ".";
            }
        }
        return warning;
    }

    /** Only a checkout of this repo may be reset to finish a download; a
     *  folder of the same name that is somebody else's work is left alone. */
    private static boolean isOurCheckout(File dir) {
        File config = new File(dir, ".git/config");
        if (!config.isFile()) return false;
        try (BufferedReader in = new BufferedReader(new java.io.FileReader(config))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.contains("jonathanferwerda/jawnos")) return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private static boolean treeIsComplete(File dir) {
        return new File(dir, "jawn").isFile()
            && new File(dir, "President.pl").isFile()
            && new File(dir, "scripts/jawnos_setup.pl").isFile();
    }

    private static boolean isEmptyDirectory(File dir) {
        File[] files = dir.isDirectory() ? dir.listFiles() : null;
        return files == null || files.length == 0;
    }

    private static boolean nonEmpty(File file) {
        return file.isFile() && file.length() > 0;
    }

    /** An 8-digit code, like the mkosi first boot generates. */
    private static String newUnlockCode() {
        return String.format(Locale.US, "%08d", new SecureRandom().nextInt(100000000));
    }

    /** The code is the only key to the database; leave it where a person can
     *  find it from the console, owner-readable only. */
    private static void writeUnlockCode(String code) {
        File file = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, UNLOCK_FILE);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write((code + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            JawnLog.append("first run: could not write " + file + ": " + t);
            return;
        }
        try { Os.chmod(file.getAbsolutePath(), 0600); } catch (Throwable ignored) { }
    }

    /** Run one shell command through Termux's login script, with the same
     *  environment the terminal uses, and wait for it off the UI thread. */
    private static Result run(Context context, String script, File workDir, long timeoutMs)
            throws IOException, InterruptedException {
        File cwd = workDir != null && workDir.isDirectory()
                ? workDir : new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        // The app's own runtime (M3) has no bin/login: it is Termux's session
        // wrapper, and it is not part of the diet. A plain $PREFIX/bin/sh -c is
        // all these steps need, with the environment built explicitly below.
        String shell = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh";
        String[] args = TermuxShellUtils.setupProcessArgs(shell, new String[] { "-c", script });
        String[] env = TermuxShellUtils.buildEnvironment(context, false, cwd.getAbsolutePath());
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(cwd);
        builder.redirectErrorStream(true);
        builder.environment().clear();
        for (String entry : env) {
            int split = entry.indexOf('=');
            if (split > 0) builder.environment().put(entry.substring(0, split), entry.substring(split + 1));
        }
        final Process process = builder.start();
        final StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                        if (output.length() > 128 * 1024) output.delete(0, output.length() - 128 * 1024);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "jawn-setup-output");
        reader.start();
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            reader.join(2000);
            return new Result(-1, tail(output));
        }
        reader.join(5000);
        return new Result(process.exitValue(), tail(output));
    }

    private static String tail(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
    }

    /** The last part of the output, enough to name the cause on the splash. */
    private static String detail(Result result) {
        String text = result == null || result.output == null ? "" : result.output.trim();
        if (text.isEmpty()) return "";
        if (text.length() > 400) text = text.substring(text.length() - 400);
        return "\n\n" + text;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static final class SetupException extends Exception {
        SetupException(String message) {
            super(message);
        }
    }

    private static final class Reporter {
        private final Activity activity;
        private final Handler ui;
        private final Listener listener;

        Reporter(Activity activity, Handler ui, Listener listener) {
            this.activity = activity;
            this.ui = ui;
            this.listener = listener;
        }

        void progress(String message) {
            JawnLog.append("first run: " + message);
            ui.post(() -> {
                if (isAlive()) listener.progress(message);
            });
        }

        void done(boolean ok, String message) {
            JawnLog.append("first run: " + (ok ? "ready" : "failed: " + message));
            ui.post(() -> {
                if (isAlive()) listener.done(ok, message);
            });
        }

        private boolean isAlive() {
            return !activity.isFinishing() && !activity.isDestroyed();
        }
    }
    /** The external-storage links a Termux session used to get from
     *  termux-setup-storage, made in the app's own home. A link whose target is
     *  not on this device is skipped rather than left dangling. */
    private static final String[][] STORAGE_LINKS = {
        { "shared", "/sdcard" },
        { "downloads", "/sdcard/Download" },
        { "dcim", "/sdcard/DCIM" },
        { "pictures", "/sdcard/Pictures" },
        { "music", "/sdcard/Music" },
        { "movies", "/sdcard/Movies" },
        { "documents", "/sdcard/Documents" },
        { "podcasts", "/sdcard/Podcasts" },
        { "alarms", "/sdcard/Alarms" },
        { "notifications", "/sdcard/Notifications" },
        { "ringtones", "/sdcard/Ringtones" },
        { "audiobooks", "/sdcard/Audiobooks" },
    };

    private static void linkExternalStorage(File home) {
        File storage = new File(home, "storage");
        if (!storage.isDirectory() && !storage.mkdirs()) return;
        for (String[] link : STORAGE_LINKS) {
            File target = new File(link[1]);
            if (!target.isDirectory()) continue;
            java.nio.file.Path path = new File(storage, link[0]).toPath();
            try {
                if (java.nio.file.Files.isSymbolicLink(path)) {
                    java.nio.file.Path current = java.nio.file.Files.readSymbolicLink(path);
                    if (link[1].equals(current.toString())) continue;
                    java.nio.file.Files.delete(path);
                }
                if (!java.nio.file.Files.exists(path)) {
                    java.nio.file.Files.createSymbolicLink(path, target.toPath());
                }
            } catch (Throwable ignored) {
                // a link this app may not make is not a provisioning failure
            }
        }
    }

}
