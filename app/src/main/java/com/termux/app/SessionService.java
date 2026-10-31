package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/**
 * M2 supervisor (docs/leaving-termux.md): runs jawn without Termux's
 * bin/login, bash or termux-exec.
 *
 * The tree needs no tty (Spike A), so this is a pipe, not a console: the
 * process is forked with an explicit environment, its stdout and stderr are one
 * stream appended to ~/manager.log, and a plain thread owns its life. There is
 * exactly one master; a restart is the master's own status-file loop, so this
 * service never touches ~/.president/status - it only replaces a master that
 * died, and refuses to start a second one while the live master's pid still
 * answers.
 *
 * What it replaces: TermuxService's session for jawn (bin/login + bash +
 * termux-exec). TermuxService stays for the Console until M3 deletes it.
 */
public final class SessionService extends Service {

    public static final String ACTION_START = "com.termux.app.SESSION_START";
    public static final String ACTION_STOP = "com.termux.app.SESSION_STOP";
    public static final String ACTION_RESTART = "com.termux.app.SESSION_RESTART";

    private static final String CHANNEL_ID = "jawn-session";
    private static final int NOTIFICATION_ID = 46100;

    /** How long a run must last before its exit counts as a fresh start, so a
     *  tree that dies in a boot loop backs off instead of spinning. */
    private static final long STABLE_MS = 60000;

    private static volatile boolean sRunning;
    private static volatile int sRestarts;
    private static volatile long sSince;
    private static volatile int sMasterPid;

    private final Object lock = new Object();
    private Supervisor supervisor;
    private volatile boolean wantRunning;
    private volatile Process process;
    private volatile boolean restarting;
    private volatile long spawnedAt;
    private volatile boolean tornDown;

    // ---------------------------------------------------------------- static API

    /** Ask the supervisor to run jawn; harmless if it already is. */
    public static void start(Context context) {
        start(context, new Intent(context, SessionService.class).setAction(ACTION_START));
    }

    /** Ask it to stop jawn and then itself. */
    public static void stop(Context context) {
        start(context, new Intent(context, SessionService.class).setAction(ACTION_STOP));
    }

    /** Replace the running master while keeping the service (dashboard Restart). */
    public static void restart(Context context) {
        start(context, new Intent(context, SessionService.class).setAction(ACTION_RESTART));
    }

    private static void start(Context context, Intent i) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
        } catch (Throwable t) {
            JawnLog.append("session service start failed: " + t);
        }
    }

    public static boolean isRunning() { return sRunning; }

    public static int restarts() { return sRestarts; }

    public static long since() { return sSince; }

    public static int masterPid() { return sMasterPid; }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        sRestarts = 0;
        tornDown = false;
        createChannel();
        startForeground(NOTIFICATION_ID, notification("starting"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            wantRunning = false;
            stopSupervisorAndSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            restarting = true;
            wantRunning = true;
            signalMaster();
            ensureSupervisor();
            return START_STICKY;
        }
        wantRunning = true;
        ensureSupervisor();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopSupervisorAndSelf();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- supervisor

    private void ensureSupervisor() {
        synchronized (lock) {
            if (supervisor != null && supervisor.isAlive()) return;
            supervisor = new Supervisor();
            supervisor.start();
        }
    }

    private final class Supervisor extends Thread {
        Supervisor() { super("jawn-session"); }

        @Override
        public void run() {
            int failures = 0;
            while (wantRunning) {
                if (JawnLauncher.jawnMasterAlive(SessionService.this)) {
                    // Another master is up (a tree started outside the app, or a
                    // restart the previous run left behind). Never run a second.
                    JawnLog.append("supervisor: a jawn master is already alive; waiting");
                    sleepQuietly(5000);
                    continue;
                }
                Process p = spawn();
                if (p == null) {
                    JawnLog.append("supervisor: could not start perl; giving up");
                    wantRunning = false;
                    break;
                }
                process = p;
                spawnedAt = System.currentTimeMillis();
                sSince = spawnedAt;
                sRunning = true;
                sMasterPid = childPid(p);
                if (sMasterPid <= 0) sMasterPid = readMasterPid();
                JawnDot.show(SessionService.this);
                JawnLockScreen.apply(SessionService.this);
                notifyNotice(statusText());
                JawnLog.append("supervisor: jawn started (pid " + sMasterPid + ")");

                int rc = waitFor(p);
                process = null;
                sRunning = false;
                sMasterPid = 0;
                JawnDot.hide(SessionService.this);

                if (!wantRunning) break;
                long alive = System.currentTimeMillis() - spawnedAt;
                if (alive >= STABLE_MS) failures = 0;
                failures++;
                sRestarts++;
                long delay = backoff(failures);
                notifyNotice("restarting after " + (delay / 1000) + "s");
                JawnLog.append("supervisor: jawn exited (status " + rc + " after "
                    + (alive / 1000) + "s); restarting in " + (delay / 1000) + "s");
                sleepQuietly(delay);
                restarting = false;
            }
            sRunning = false;
            sMasterPid = 0;
            JawnDot.hide(SessionService.this);
            if (!wantRunning) {
                notifyNotice("stopped");
                // Leave foreground mode so the service can be collected.
                stopForeground(true);
                stopSelf();
            }
        }
    }

    /** The runtime the app unpacks for itself (M3): ~/.jawnos/runtime. It is a
     *  plain prefix with our own perl and modules, so choosing it is just
     *  picking a different interpreter - nothing else moves. Empty when it is
     *  absent, which is the case on every device that still runs Termux's. */
    private static File runtimeDir() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".jawnos/runtime");
    }

    /** The aarch64 CORE directory inside a runtime, or null. The perl version
     *  in the path is whatever the archive was built with. */
    private static File runtimeCore(File runtime) {
        File perl5 = new File(runtime, "lib/perl5");
        File[] versions = perl5.listFiles();
        if (versions == null) return null;
        for (File v : versions) {
            File[] arches = v.listFiles();
            if (arches == null) continue;
            for (File a : arches) {
                File core = new File(a, "CORE");
                if (new File(core, "libperl.so").isFile()) return core;
            }
        }
        return null;
    }

    private Process spawn() {
        File dir = new File(JawnLauncher.getJawnDirPath(this));
        File perl = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/perl");
        File runtime = runtimeDir();
        File runtimePerl = new File(runtime, "bin/perl");
        File core = runtimeCore(runtime);
        // Our own runtime wins when it is there: that is the whole point of
        // shipping one. Termux's perl is the fallback until the app unpacks it.
        boolean ownRuntime = runtimePerl.canExecute() && core != null;
        if (ownRuntime) perl = runtimePerl;
        if (!perl.canExecute()) {
            JawnLog.append("supervisor: no perl at " + perl);
            return null;
        }
        ProcessBuilder pb = new ProcessBuilder(Arrays.asList(perl.getAbsolutePath(), "./jawn"));
        pb.directory(dir);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(JawnLog.file()));
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));

        Map<String, String> env = pb.environment();
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        env.put("HOME", home);
        env.put("PREFIX", prefix);
        env.put("PATH", prefix + "/bin:" + prefix + "/bin/applets:/system/bin:/system/xbin");
        env.put("TMPDIR", prefix + "/tmp");
        env.put("LANG", "en_US.UTF-8");
        env.put("TERM", "xterm-256color");
        env.put("PWD", dir.getAbsolutePath());
        if (env.get("SHELL") == null) env.put("SHELL", prefix + "/bin/bash");
        if (env.get("TERMUX_VERSION") == null) env.put("TERMUX_VERSION", "0.118");
        // The runtime's binaries were compiled for another package's prefix, so
        // the loader has to be told where its libraries now live. The shims below
        // name the other paths baked into those binaries: perl's module
        // directories, git's exec path and CA bundle, and the CA libcurl trusts.
        if (ownRuntime) {
            String rt = runtime.getAbsolutePath();
            env.put("LD_LIBRARY_PATH", rt + "/lib:" + core.getAbsolutePath());
            JawnLog.append("supervisor: using the app's own runtime at " + rt);
        }
        else {
            env.put("LD_LIBRARY_PATH", prefix + "/lib");
        }
        java.util.List<String> shims = new java.util.ArrayList<>();
        com.termux.shared.shell.TermuxShellUtils.addRuntimeEnvironment(shims);
        for (String shim : shims) {
            int at = shim.indexOf('=');
            if (at > 0) env.put(shim.substring(0, at), shim.substring(at + 1));
        }
        // M2 drops termux-exec: no #!/usr/bin/perl rewriting. jawn execs the
        // interpreter it is already running under ($^X), so nothing needs it.
        env.remove("LD_PRELOAD");

        try {
            return pb.start();
        } catch (IOException e) {
            JawnLog.append("supervisor: start failed: " + e);
            return null;
        }
    }

    private int waitFor(Process p) {
        while (true) {
            try {
                return p.waitFor();
            } catch (InterruptedException e) {
                if (!wantRunning && !restarting) {
                    p.destroy();
                }
                // a spurious interrupt (a restart) keeps watching the process
            }
        }
    }

    /** SIGINT the master itself, exactly as its own Ctrl+C handler expects.
     *  The pid file names President.pl, not the master, so signalling *that* would
     *  kill the tree and let the master restart it; the master is our child. */
    private void signalMaster() {
        Process p = process;
        int pid = childPid(p);
        try {
            if (pid > 0) {
                new ProcessBuilder(Arrays.asList("/system/bin/kill", "-INT", String.valueOf(pid)))
                    .redirectErrorStream(true)
                    .start();
            } else if (p != null) {
                // No pid exposed: SIGTERM is the next best and the killer mops up.
                p.destroy();
            }
        } catch (Throwable t) {
            JawnLog.append("supervisor: signal failed: " + t);
            if (p != null) p.destroy();
        }
    }

    private void stopSupervisorAndSelf() {
        if (tornDown) return;
        tornDown = true;
        wantRunning = false;
        signalMaster();
        process = null;
        Supervisor s;
        synchronized (lock) { s = supervisor; supervisor = null; }
        if (s != null) s.interrupt();
        // The master's INT handler tears the tree down; the killer is the
        // mop-up the app has always used for anything it missed.
        try {
            File dir = new File(JawnLauncher.getJawnDirPath(this));
            new ProcessBuilder(Arrays.asList(
                    TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/perl",
                    "./scripts/manager_killer.pl", "master"))
                .directory(dir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(JawnLog.file()))
                .start();
        } catch (Throwable t) {
            JawnLog.append("supervisor: killer failed: " + t);
        }
        sRunning = false;
        sMasterPid = 0;
        JawnDot.hide(this);
        JawnLockScreen.release();
        stopForeground(true);
        stopSelf();
    }

    private static long backoff(int failures) {
        switch (failures) {
            case 1: return 2000;
            case 2: return 5000;
            case 3: return 10000;
            case 4: return 30000;
            default: return 60000;
        }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /** A live master named by the tree's own pid file, or 0. */
    private int readMasterPid() {
        File f = new File(new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".president"), "pid");
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s = r.readLine();
            if (s == null) return 0;
            s = s.replaceAll("\\s+", "");
            return s.matches("[0-9]+") ? Integer.parseInt(s) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The pid of the child we spawned, when Android exposes it (API 26's
     *  Process.pid(), or the older implementation's field). */
    private static int childPid(Process p) {
        if (p == null) return 0;
        try {
            Object v = Process.class.getMethod("pid").invoke(p);
            if (v instanceof Long) return (int) (long) (Long) v;
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            Object v = f.get(p);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private String statusText() {
        String u = JawnLauncher.getJawnVersion(this);
        return "v" + u + " - pid " + sMasterPid
            + (sRestarts > 0 ? ", " + sRestarts + " restart" + (sRestarts == 1 ? "" : "s") : "");
    }

    // -------------------------------------------------------------- notification

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "jawn supervision", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("jawn is running");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, JawnDashboardActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, SessionService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("jawn")
            .setContentText(text)
            .setSmallIcon(com.termux.R.drawable.ic_service_notification)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
            .build();
    }

    private void notifyNotice(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, notification(text));
        } catch (Throwable ignored) {
        }
    }
}
