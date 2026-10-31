package com.jawnos.shared.shell;

import java.lang.reflect.Field;

public class ShellUtils {

    public static int getPid(Process p) {
        try {
            Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            try {
                return f.getInt(p);
            } finally {
                f.setAccessible(false);
            }
        } catch (Throwable e) {
            return -1;
        }
    }

    public static String getExecutableBasename(String executable) {
        if (executable == null) return null;
        int lastSlash = executable.lastIndexOf('/');
        return (lastSlash == -1) ? executable : executable.substring(lastSlash + 1);
    }

}
