package com.jawnos.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Package and clock helpers for {@link JawnHomeActivity}.
 *
 * Everything here is local and synchronous; the activity calls {@link #load}
 * on a worker thread because querying every launchable app and decoding its
 * icon is slow enough to be felt on the first frame of a boot.
 */
final class JawnHomeApps {

    private JawnHomeApps() {
    }

    /** One icon in the grid: the package's launcher activity. */
    static final class AppEntry {
        final String packageName;
        final String activityName;
        final String label;
        final Drawable icon;
        /** The shelf the app starts on: Communication, Games, System,
         *  Accessories or Other. A person can move it by dragging the tile
         *  onto another shelf; that choice is kept by the home. */
        final String category;
        /** True for a ROM package. The System shelf is a movable choice, so
         *  this flag is what the System apps box counts instead. */
        final boolean system;

        AppEntry(String packageName, String activityName, String label, Drawable icon,
                 String category, boolean system) {
            this.packageName = packageName;
            this.activityName = activityName;
            this.label = label;
            this.icon = icon;
            this.category = category;
            this.system = system;
        }

        /** Search matches the visible name and the package, so "com.termux" works. */
        boolean matches(String needle) {
            if (needle == null || needle.isEmpty()) return true;
            String lower = needle.toLowerCase(Locale.getDefault());
            return label.toLowerCase(Locale.getDefault()).contains(lower)
                || packageName.toLowerCase(Locale.getDefault()).contains(lower);
        }
    }

    /**
     * The shelf an app starts on. Android's own category is the best hint:
     * games, social/news, and the media/productivity group map straight onto
     * the home's shelves; an unclassified system package is System, and
     * everything else is Other. The home lets a person overrule this by
     * dragging the tile onto another shelf.
     */
    static String categoryOf(ActivityInfo ai) {
        int category = ApplicationInfo.CATEGORY_UNDEFINED;
        int flags = 0;
        try {
            if (ai.applicationInfo != null) {
                category = ai.applicationInfo.category;
                flags = ai.applicationInfo.flags;
            }
        } catch (Throwable ignored) {
            // older packages: no category, which is Other/System below
        }
        if (category == ApplicationInfo.CATEGORY_GAME) return "Games";
        if (category == ApplicationInfo.CATEGORY_SOCIAL
                || category == ApplicationInfo.CATEGORY_NEWS) return "Communication";
        if (category == ApplicationInfo.CATEGORY_AUDIO
                || category == ApplicationInfo.CATEGORY_VIDEO
                || category == ApplicationInfo.CATEGORY_IMAGE
                || category == ApplicationInfo.CATEGORY_PRODUCTIVITY) return "Accessories";
        if ((flags & ApplicationInfo.FLAG_SYSTEM) != 0) return "System";
        return "Other";
    }

    /**
     * Every MAIN/LAUNCHER activity, one per package, sorted by label.
     *
     * A package with several launcher aliases is shown once (its first alias),
     * which is what the OEM launchers do. Label and icon failures are per-app:
     * one broken package must not empty the whole grid.
     */
    static List<AppEntry> load(PackageManager pm) {
        List<AppEntry> entries = new ArrayList<>();
        if (pm == null) return entries;

        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(main, 0);
        } catch (Throwable t) {
            return entries;
        }
        if (found == null) return entries;

        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri == null ? null : ri.activityInfo;
            if (ai == null || ai.packageName == null || ai.name == null) continue;
            if (!seen.add(ai.packageName)) continue;

            CharSequence label = null;
            try {
                label = ri.loadLabel(pm);
            } catch (Throwable ignored) {
                // fall back to the package name
            }
            if (label == null || label.toString().trim().isEmpty()) label = ai.packageName;

            Drawable icon = null;
            try {
                icon = ri.loadIcon(pm);
            } catch (Throwable ignored) {
                // the adapter draws a default when this is null
            }

            boolean system = false;
            try {
                system = ai.applicationInfo != null
                    && (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            } catch (Throwable ignored) {
                // a package whose info cannot be read is treated as ordinary
            }

            entries.add(new AppEntry(ai.packageName, ai.name, label.toString().trim(), icon,
                categoryOf(ai), system));
        }

        Collections.sort(entries, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return entries;
    }

    /** The default handler for an intent, or null when nothing on the device takes it. */
    static ResolveInfo resolve(PackageManager pm, Intent intent) {
        if (pm == null || intent == null) return null;
        try {
            ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && !isResolver(ri)) return ri;
        } catch (Throwable ignored) {
            // fall through to the list form
        }
        try {
            List<ResolveInfo> list = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (!isResolver(ri)) return ri;
                }
            }
        } catch (Throwable ignored) {
            // nothing resolves
        }
        return null;
    }

    /** The system chooser is not an app: it answers with "Complete action". */
    static boolean isResolver(ResolveInfo ri) {
        String pkg = ri == null || ri.activityInfo == null ? "" : ri.activityInfo.packageName;
        if (pkg.isEmpty()) return true;
        return pkg.equals("android")
            || pkg.contains("intentresolver")
            || pkg.contains("android.internal.app");
    }

    /** Start the app's own launcher activity, not a home-screen intent. */
    static boolean launch(Context context, AppEntry entry) {
        if (context == null || entry == null) return false;
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(entry.packageName, entry.activityName));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            // an uninstalled or disabled alias: try the package's default launch
            try {
                Intent fallback = context.getPackageManager().getLaunchIntentForPackage(entry.packageName);
                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(fallback);
                    return true;
                }
            } catch (Throwable ignored) {
                // report the failure to the caller
            }
            return false;
        }
    }

}
