package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import org.json.JSONObject;

/**
 * Shared helpers for the JawnOS additions of this fork: preferences, starting and
 * stopping jawn, and reading its configured port.
 */
public class JawnLauncher {

    public static final String PREFS_NAME = "jawnos";
    public static final String KEY_DIR = "jawn_dir";
    public static final String KEY_AUTOSTART = "jawn_autostart";
    public static final String KEY_BLE_WITH_JAWN = "ble_with_jawn";
    public static final String KEY_RUNNING = "jawn_running";
    public static final String KEY_SYSTEM_BARS = "system_bars";
    public static final String DEFAULT_DIR = "jawnos";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable pendingStop;

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getJawnDirName(Context context) {
        String name = prefs(context).getString(KEY_DIR, DEFAULT_DIR);
        if (name == null || name.trim().isEmpty()) name = DEFAULT_DIR;
        return name.trim();
    }

    public static String getJawnDirPath(Context context) {
        return TermuxConstants.TERMUX_HOME_DIR_PATH + "/" + getJawnDirName(context);
    }

    /** True when the house is all there: the tree, a usable config.json (the
     *  server's own gate is {@code signatorial}) and the live database. A
     *  fresh device fails this and is provisioned on first launch; a device
     *  that has been set up keeps the everyday launch path untouched. */
    public static boolean isJawnInstalled(Context context) {
        File dir = new File(getJawnDirPath(context));
        return new File(dir, "jawn").isFile()
            && new File(dir, "President.pl").isFile()
            && configIsUsable(new File(dir, "config.json"))
            && nonEmptyFile(new File(dir, "database/jawnos.db"));
    }

    /** The same test the server makes before it skips its setup wizard. */
    private static boolean configIsUsable(File file) {
        if (!nonEmptyFile(file)) return false;
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new FileReader(file));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            return !o.optString("signatorial", "").isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean nonEmptyFile(File file) {
        return file.isFile() && file.length() > 0;
    }

    /** The human version from <jawn dir>/VERSION, or "unknown" if it is absent. */
    public static String getJawnVersion(Context context) {
        File version = new File(getJawnDirPath(context), "VERSION");
        try (BufferedReader reader = new BufferedReader(new FileReader(version))) {
            String line = reader.readLine();
            if (line != null && !line.trim().isEmpty()) return line.trim();
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    public static boolean isAutostartEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTOSTART, true);
    }

    public static boolean isBleWithJawn(Context context) {
        return prefs(context).getBoolean(KEY_BLE_WITH_JAWN, true);
    }

    /** Whether the user wants jawn kept running. A fresh install follows the
     *  boot setting; Stop clears it, Start and the boot receiver set it. */
    public static boolean shouldKeepRunning(Context context) {
        return prefs(context).getBoolean(KEY_RUNNING, isAutostartEnabled(context));
    }

    public static void setKeepRunning(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_RUNNING, on).apply();
    }

    /** Check the pieces jawn needs and bring back anything that is down when the
     *  user asked it to keep running. Returns the report the dashboard shows. */
    public static String selfTest(Context context) {
        boolean jawn = jawnIsUp(context);
        boolean master = jawnMasterAlive(context);
        boolean ble = bleIsUp();
        boolean want = shouldKeepRunning(context);
        StringBuilder r = new StringBuilder();
        r.append("jawn ").append(jawn ? "up" : "down")
         .append(", master ").append(master ? "alive" : "gone")
         .append(", BLE ").append(ble ? "up" : "down")
         .append(", keep-alive ").append(want ? "on" : "off");
        if (want && !jawn && !master) {
            startJawnBackground(context);
            r.append(" - starting jawn");
        }
        if (want && isBleWithJawn(context) && !ble) {
            startBle(context);
            r.append(" - starting BLE");
        }
        JawnLog.append("self test: " + r);
        return r.toString();
    }

    /** Whether the full screen view keeps the system bars (the clock/battery
     *  and notification bar, and the home/back buttons) on screen. Off by
     *  default - the view fills the screen; the admin panel turns it on. */
    public static boolean areSystemBarsShown(Context context) {
        return prefs(context).getBoolean(KEY_SYSTEM_BARS, false);
    }

    /** Start (or nudge) the JawnOS API app's BLE bridge; safe if it already runs. */
    public static void startBle(Context context) {
        try {
            Intent i = new Intent();
            i.setClassName("com.termux.api", "com.termux.api.JawnBleService");
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
        } catch (Throwable t) {
            // JawnOS API app not installed
        }
    }

    public static void stopBle(Context context) {
        try {
            Intent i = new Intent();
            i.setClassName("com.termux.api", "com.termux.api.JawnBleService");
            i.setAction("com.termux.api.action.JAWN_BLE_STOP");
            context.startService(i);
        } catch (Throwable t) {
            // JawnOS API app not installed
        }
    }

    /** True if something is listening on the loopback port (used for live status). */
    public static boolean portOpen(String host, int port, int timeoutMs) {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (s != null) { try { s.close(); } catch (Throwable t) { } }
        }
    }

    /** jawn's web server answers on the configured port, and always on the dock 3000. */
    public static boolean jawnIsUp(Context context) {
        return jawnIsUp(context, 400);
    }

    /** The same question with the caller's own patience. A phone thawing from a
     *  freeze, or a tree whose workers are momentarily all busy, can miss a
     *  400 ms connect to a tree that is in fact serving - the launch path asks
     *  again with a longer window before it decides the house is down. */
    public static boolean jawnIsUp(Context context, int timeoutMs) {
        return portOpen("127.0.0.1", getJawnPort(context), timeoutMs)
            || portOpen("127.0.0.1", 3000, timeoutMs);
    }

    /** True if the jawn *master* process is alive, even while the tree is booting.
     *  The port is down during a restart, so the dashboard can say "restarting"
     *  rather than "not running" while the new tree comes up.
     *
     *  The pid file is checked first: it names the boot master, and a live
     *  process whose command line still names jawn is a tree even when the port
     *  probe fails while the phone thaws from a freeze (a 400 ms connect to a
     *  swapped-in Perl worker can lose). pgrep is the fallback. */
    public static boolean jawnMasterAlive(Context context) {
        if (masterPid(context) > 0) return true;
        try {
            Process p = new ProcessBuilder("sh", "-c", "pgrep -f 'perl [.]/jawn' >/dev/null").start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The pid in {@code ~/.president/pid} when it still names a live jawn
     *  process, else 0. The command line is checked so a pid recycled after a
     *  reboot (or a stale file) never passes for a running master. */
    private static int masterPid(Context context) {
        try {
            File f = new File(new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".president"), "pid");
            if (!f.isFile()) return 0;
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            String s = sb.toString().replaceAll("\\s+", "");
            if (!s.matches("[0-9]+")) return 0;
            int pid = Integer.parseInt(s);
            return pid > 0 && procIsJawn(pid) ? pid : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** True when /proc/<pid>'s command line still names a jawn program. The app
     *  and the tree share one uid, so the tree's own processes are readable. */
    private static boolean procIsJawn(int pid) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(new File("/proc/" + pid + "/cmdline"));
            byte[] buf = new byte[512];
            int read = in.read(buf);
            if (read <= 0) return false;
            String cmd = new String(buf, 0, read, "UTF-8").replace('\0', ' ');
            return cmd.contains("President.pl") || cmd.contains("Manager.pl")
                || cmd.contains("Websocket.pl") || cmd.contains("teletype.pl")
                || cmd.contains("watch.pl");
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /** The BLE bridge listens on loopback 45999. */
    public static boolean bleIsUp() {
        return portOpen("127.0.0.1", 45999, 400);
    }

    /** Start jawn as a background task (dashboard button).
     *
     *  A foreground terminal *session* does not actually run until a terminal
     *  view is attached, so with the app in the background (the normal case --
     *  dot only) the session sat idle and jawn never came up: the web app did
     *  not load until the console was opened, which attached a view and let the
     *  session finally run. The boot path has always used a background task and
     *  runs headless, so use that here too. */
    public static void startJawn(Context context) {
        startJawn(context, true, false);
    }

    /** Start jawn as a background task (boot autostart): the foreground service
     *  and the dot still run, but no console session is created, so jawn's
     *  output does not pop up in the console after a reboot. */
    public static void startJawnBackground(Context context) {
        startJawn(context, true, false);
    }

    private static void startJawn(Context context, boolean background, boolean afterKill) {
        setKeepRunning(context, true);
        // A Stop scheduled a service stop to fire once its killer has had its
        // few seconds. If the user starts again inside that window, cancel it,
        // or it tears the fresh start down.
        if (pendingStop != null) {
            MAIN.removeCallbacks(pendingStop);
            pendingStop = null;
        }

        // One master per machine. A second tree wipes ~/.president under the
        // first, both take the ports and they cycle - which is the "black
        // screen for a minute or two" after picking the phone up. The live
        // master's own watchdog recovers a dead tree, so a start is only owed
        // when no master is alive at all. A restart has already killed it, and
        // its launcher waits the killer out, so that one is exempt.
        if (!afterKill && jawnMasterAlive(context)) {
            JawnLog.append("start: a jawn master is already alive; not starting another");
            return;
        }

        String dir = getJawnDirPath(context);
        Intent intent = new Intent(context, TermuxService.class);
        intent.setAction(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
        // Run through Termux's login script rather than bash directly: login is
        // what exports LD_PRELOAD for termux-exec, so #!/usr/bin/perl-style
        // shebangs in user scripts resolve. Launching bash directly skips it
        // and ./President.pl dies with "bad interpreter: /usr/bin/perl".
        intent.setData(Uri.parse(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE + "://" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/login"));
        // Wait out a manager_killer still sweeping from a recent Stop before
        // starting. The killer matches "perl ./jawn" on its targets' command
        // lines, so a session started while it runs is killed before jawn can
        // come up (a quick Stop then Start used to leave nothing running). The
        // program name is built from a variable so this waiting shell does not
        // itself match the killer's "perl ./jawn" pattern.
        intent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[] { "-lc",
            "cd " + dir + " && p='./jawn'; n=0; "
            + "while pgrep -f 'manager_killer[.]pl' >/dev/null 2>&1 && [ $n -lt 30 ]; "
            + "do sleep 1; n=$((n+1)); done; exec perl \"$p\"" });
        intent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, dir);
        intent.putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, background);
        // Do not let the new session drag the Termux console to the front. A
        // foreground execution otherwise defaults to
        // SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY, which calls
        // startTermuxActivity() and throws the user out of the dashboard and into
        // the console the moment jawn starts. The session is still created and
        // selected, so "Open console" still lands on jawn's output.
        intent.putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION, String.valueOf(
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY));
        intent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "jawn");
        // Bring the BLE bridge up first so jawn's listener has a daemon to dial
        // the moment it starts (and take it down again on Stop when tied).
        if (isBleWithJawn(context)) startBle(context);
        context.startForegroundService(intent);
    }

    /** Stop jawn (and the BLE bridge when it rides along with it). */
    public static void stopJawn(Context context) {
        setKeepRunning(context, false);
        if (isBleWithJawn(context)) stopBle(context);
        stopJawnTree(context);
    }

    /** Restart the tree without stopping the service: kill what runs, then start
     *  again. startJawn() cancels the delayed service stop that stopJawnTree
     *  schedules, and its launch waits out the killer. */
    public static void restartJawn(final Context context) {
        stopJawnTree(context);
        MAIN.postDelayed(new Runnable() {
            public void run() { startJawn(context, true, true); }
        }, 800);
    }

    /** Tear down the running tree and stop the service, but leave BLE alone. */
    private static void stopJawnTree(Context context) {
        // Take the tree down before stopping the service: ACTION_STOP_SERVICE
        // only SIGKILLs the terminal sessions, which the master's Ctrl+C
        // handler never sees, so President.pl, the workers, the BLE client and
        // ollama all survived -- and a master left behind keeps
        // ~/.process_watch answering, so the next Start stands down (2026-10-10:
        // after Stop the web UI was still reachable and the phone ran hot).
        // The killer rides the foreground service as a background task, which
        // the service stop below does not kill, so its sweeps always finish.
        final String dir = getJawnDirPath(context);
        Intent killer = new Intent(context, TermuxService.class);
        killer.setAction(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
        killer.setData(Uri.parse(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE + "://" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/login"));
        killer.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[] { "-lc", "cd " + dir + " && perl ./scripts/manager_killer.pl master" });
        killer.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, dir);
        killer.putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, true);
        killer.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "jawn-killer");
        context.startForegroundService(killer);
        // then put the service (and the dot) away; the killer has had its few
        // seconds by now and finishes even after the service stops
        pendingStop = new Runnable() {
            @Override
            public void run() {
                pendingStop = null;
                Intent stop = new Intent(context, TermuxService.class);
                stop.setAction(TERMUX_SERVICE.ACTION_STOP_SERVICE);
                context.startService(stop);
            }
        };
        MAIN.postDelayed(pendingStop, 8000);
    }

    /** Read working_port from the jawn config.json, defaulting to 3000. */
    public static int getJawnPort(Context context) {
        return readConfigPort(context, "working_port", 3000);
    }

    /** The port the login ticket is served on: config.json's "port". */
    public static int getJawnTicketPort(Context context) {
        return readConfigPort(context, "port", 3000);
    }

    private static int readConfigPort(Context context, String key, int fallback) {
        try {
            File f = new File(getJawnDirPath(context), "config.json");
            if (!f.exists()) return fallback;
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            int p = o.optInt(key, 0);
            if (p > 0) return p;
        } catch (Throwable t) {
            // fall through to the default
        }
        return fallback;
    }

    /** Whether the view opens this machine's manager at loopback.
     *
     *  config.json's "loopback_manager" opts in. The view then needs no DNS and
     *  no VPN to reach itself on any network, which is what makes a phone or
     *  tablet answer the moment it joins a new network. universal.js restores
     *  the paired device id from JawnAppStorage, carrying it across the origin
     *  change, and the ticket is addressed at loopback too. */
    public static boolean getJawnLoopback(Context context) {
        try {
            File f = new File(getJawnDirPath(context), "config.json");
            if (f.exists()) {
                StringBuilder sb = new StringBuilder();
                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                JSONObject o = new JSONObject(sb.toString());
                return o.optBoolean("loopback_manager", false);
            }
        } catch (Throwable ignored) {
            // no config, no opt-in
        }
        return false;
    }

    /** The host the view opens: loopback when opted in, else the configured
     *  domain, else loopback. */
    public static String managerHost(Context context) {
        if (getJawnLoopback(context)) return "127.0.0.1";
        String domain = getJawnDomain(context);
        return domain.isEmpty() ? "127.0.0.1" : domain;
    }

    /** The URL the dashboard's Open UI loads: the saved ticket when there is
     *  one (its host is swapped for the one the view opens on), else the manager
     *  on that host. */
    public static String getUiUrl(Context context, int port) {
        return uiUrlForHost(context, managerHost(context), port);
    }

    /** The domain form, used once to carry the paired device id into loopback. */
    public static String getDomainUiUrl(Context context, int port) {
        String domain = getJawnDomain(context);
        return uiUrlForHost(context, domain.isEmpty() ? "127.0.0.1" : domain, port);
    }

    private static String uiUrlForHost(Context context, String host, int port) {
        try {
            File tf = new File(getJawnDirPath(context), ".shots/ticket.txt");
            if (tf.exists()) {
                StringBuilder sb = new StringBuilder();
                BufferedReader r = new BufferedReader(new FileReader(tf));
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                String s = sb.toString().trim();
                if (s.startsWith("https://")) return withDomain(s, host);
            }
        } catch (Throwable ignored) {
            // fall through to the manager URL
        }
        return "https://" + host + ":" + port + "/manager";
    }

    /** The manager - the ticketed destination - on the host the view opens. */
    public static String getManagerUrl(Context context, int port) {
        return "https://" + managerHost(context) + ":" + port + "/manager";
    }

    /** The manager on the configured domain, for the one-time id migration. */
    public static String getDomainManagerUrl(Context context, int port) {
        String domain = getJawnDomain(context);
        return "https://" + (domain.isEmpty() ? "127.0.0.1" : domain) + ":" + port + "/manager";
    }

    /** The device id mirrored by the app bridge; empty when none was seen. */
    public static String getStoredDeviceUuid(Context context) {
        return context.getSharedPreferences("jawn_app_state", Context.MODE_PRIVATE)
                .getString("device_uuid", "");
    }

    /** config.json's domain, or "" when it is unset. */
    public static String getJawnDomain(Context context) {
        try {
            File f = new File(getJawnDirPath(context), "config.json");
            if (f.exists()) {
                StringBuilder sb = new StringBuilder();
                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                JSONObject o = new JSONObject(sb.toString());
                String d = o.optString("domain", "");
                if (!d.isEmpty()) return d;
            }
        } catch (Throwable ignored) {
            // no domain
        }
        return "";
    }

    /** Swap a URL's host for the house's name, keeping its port, path and query
     *  exactly (the ?s= token is url-escaped; a string edit leaves it alone). */
    private static String withDomain(String url, String domain) {
        if (domain == null || domain.isEmpty()) return url;
        String prefix = "https://";
        if (!url.startsWith(prefix)) return url;
        String rest = url.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash < 0) return url;
        String authority = rest.substring(0, slash);
        String portPart = "";
        int colon = authority.indexOf(':');
        if (colon >= 0) portPart = authority.substring(colon);
        if (authority.equals(domain + portPart)) return url;
        return prefix + domain + portPart + rest.substring(slash);
    }
}
