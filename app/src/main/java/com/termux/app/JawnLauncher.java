package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

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

    /** Start a foreground terminal session that runs ./jawn in the jawnos directory. */
    public static void startJawn(Context context) {
        String dir = getJawnDirPath(context);
        Intent intent = new Intent(context, TermuxService.class);
        intent.setAction(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
        intent.setData(Uri.parse(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE + "://" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/bash"));
        intent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[] { "-lc", "cd " + dir + " && exec perl ./jawn" });
        intent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, dir);
        intent.putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false);
        intent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "jawn");
        context.startForegroundService(intent);
    }

    public static void stopJawn(Context context) {
        Intent intent = new Intent(context, TermuxService.class);
        intent.setAction(TERMUX_SERVICE.ACTION_STOP_SERVICE);
        context.startService(intent);
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
