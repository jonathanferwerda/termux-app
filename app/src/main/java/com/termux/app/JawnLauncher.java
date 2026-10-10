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

    public static boolean isAutostartEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTOSTART, true);
    }

    public static boolean isBleWithJawn(Context context) {
        return prefs(context).getBoolean(KEY_BLE_WITH_JAWN, true);
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
        return portOpen("127.0.0.1", getJawnPort(context), 400)
            || portOpen("127.0.0.1", 3000, 400);
    }

    /** The BLE bridge listens on loopback 45999. */
    public static boolean bleIsUp() {
        return portOpen("127.0.0.1", 45999, 400);
    }

    /** Start jawn in the foreground as a terminal session (dashboard button). */
    public static void startJawn(Context context) {
        startJawn(context, false);
    }

    /** Start jawn as a background task (boot autostart): the foreground service
     *  and the dot still run, but no console session is created, so jawn's
     *  output does not pop up in the console after a reboot. */
    public static void startJawnBackground(Context context) {
        startJawn(context, true);
    }

    private static void startJawn(Context context, boolean background) {
        // A Stop scheduled a service stop to fire once its killer has had its
        // few seconds. If the user starts again inside that window, cancel it,
        // or it tears the fresh start down.
        if (pendingStop != null) {
            MAIN.removeCallbacks(pendingStop);
            pendingStop = null;
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
        if (isBleWithJawn(context)) stopBle(context);
        stopJawnTree(context);
    }

    /** Restart the tree without stopping the service: kill what runs, then start
     *  again. startJawn() cancels the delayed service stop that stopJawnTree
     *  schedules, and its launch waits out the killer. */
    public static void restartJawn(final Context context) {
        stopJawnTree(context);
        MAIN.postDelayed(new Runnable() {
            public void run() { startJawn(context); }
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
        try {
            File f = new File(getJawnDirPath(context), "config.json");
            if (!f.exists()) return 3000;
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            int p = o.optInt("working_port", 0);
            if (p > 0) return p;
        } catch (Throwable t) {
            // fall through to the default
        }
        return 3000;
    }
}
