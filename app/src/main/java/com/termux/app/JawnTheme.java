package com.termux.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;

/**
 * The paint the house is wearing, as the native surfaces read it.
 *
 * The manager records the effective theme bundle (a running appointment's theme
 * over the Misc choice) in {@code ~/.president/native_theme.json}, because the
 * launcher and the lock screen hold no manager session and cannot be sent the
 * WebSocket push the open windows get. A file rather than a setting: a theme is
 * this device's own, and a shared settings row let the newest sync win on every
 * machine.
 */
public final class JawnTheme {

    public static final int DEFAULT_BACKGROUND = 0xFF101010;
    public static final int DEFAULT_ACCENT = 0xFFE8C547;

    public final int background;
    public final int ink;
    public final int muted;
    public final int accent;
    public final boolean dark;
    public final boolean appointment;
    public final String name;

    private JawnTheme(int background, int ink, int muted, int accent,
                      boolean dark, boolean appointment, String name) {
        this.background = background;
        this.ink = ink;
        this.muted = muted;
        this.accent = accent;
        this.dark = dark;
        this.appointment = appointment;
        this.name = name == null ? "" : name;
    }

    /** The current theme: the recorded bundle, else the Misc manager colour. */
    public static JawnTheme current(Context context) {
        JawnTheme recorded = fromFile(context);
        if (recorded != null) return recorded;
        String miscColour = miscManagerColour(context);
        if (miscColour != null && !miscColour.trim().isEmpty()) {
            return from(miscColour, "", false, "");
        }
        return defaults();
    }

    /** The bundle the manager wrote for native surfaces, or null if absent. */
    public static JawnTheme fromFile(Context context) {
        File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".president");
        File file = new File(dir, "native_theme.json");
        if (!file.isFile()) return null;
        FileInputStream in = null;
        try {
            byte[] data = new byte[(int) file.length()];
            in = new FileInputStream(file);
            int read = in.read(data);
            if (read <= 0) return null;
            return parse(new String(data, 0, read, "UTF-8"));
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) { } }
        }
    }

    /** The recorded bundle, or null when the text is absent or unreadable. */
    public static JawnTheme parse(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            // Perl writes 1/0 here, not a JSON boolean.
            boolean appointment = o.optInt("appointment", 0) != 0
                || o.optBoolean("appointment", false);
            return from(o.optString("background", ""), o.optString("accent", ""),
                appointment, o.optString("theme", ""));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String miscManagerColour(Context context) {
        File dbFile = new File(JawnLauncher.getJawnDirPath(context), "database/jawnos.db");
        if (!dbFile.isFile()) return "";
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY);
            cursor = db.rawQuery(
                "select value from settings where app = 'misc'"
                    + " and setting = 'manager_background_colour'"
                    + " order by server_time desc limit 1", null);
            if (cursor.moveToFirst()) return cursor.getString(0);
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) { try { cursor.close(); } catch (Throwable ignored) { } }
            if (db != null) { try { db.close(); } catch (Throwable ignored) { } }
        }
        return "";
    }

    private static JawnTheme from(String bg, String accent, boolean appointment, String name) {
        int background = colour(bg, DEFAULT_BACKGROUND);
        double lum = (0.2126 * Color.red(background) + 0.7152 * Color.green(background)
            + 0.0722 * Color.blue(background)) / 255.0;
        boolean dark = lum < 0.5;
        int ink = dark ? 0xFFFFFFFF : 0xFF111111;
        int muted = dark ? 0xFFC9C9C9 : 0xFF3A3A3A;
        return new JawnTheme(background, ink, muted, colour(accent, DEFAULT_ACCENT),
            dark, appointment, name);
    }

    private static JawnTheme defaults() {
        return from("", "", false, "");
    }

    private static int colour(String value, int fallback) {
        if (value == null) return fallback;
        String s = value.trim();
        if (s.isEmpty()) return fallback;
        if (!s.startsWith("#")) s = "#" + s;
        try { return Color.parseColor(s); } catch (Throwable t) { return fallback; }
    }
}
