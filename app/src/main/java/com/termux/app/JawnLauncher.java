package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
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
    public static final String DEFAULT_DIR = "jawnos";

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
        String dir = getJawnDirPath(context);
        Intent intent = new Intent(context, TermuxService.class);
        intent.setAction(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
        // Run through Termux's login script rather than bash directly: login is
        // what exports LD_PRELOAD for termux-exec, so #!/usr/bin/perl-style
        // shebangs in user scripts resolve. Launching bash directly skips it
        // and ./President.pl dies with "bad interpreter: /usr/bin/perl".
        intent.setData(Uri.parse(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE + "://" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/login"));
        intent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[] { "-lc", "cd " + dir + " && exec perl ./jawn" });
        intent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, dir);
        intent.putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, background);
        intent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "jawn");
        context.startForegroundService(intent);
    }

    public static void stopJawn(Context context) {
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
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                Intent stop = new Intent(context, TermuxService.class);
                stop.setAction(TERMUX_SERVICE.ACTION_STOP_SERVICE);
                context.startService(stop);
            }
        }, 8000);
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
