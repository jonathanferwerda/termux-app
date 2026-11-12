package com.jawnos.app;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.webkit.WebView;

import com.jawnos.shared.crash.JawnCrashUtils;
import com.jawnos.shared.settings.preferences.JawnSharedPreferences;
import com.jawnos.shared.logger.Logger;


public class JawnApplication extends Application {
    public void onCreate() {
        super.onCreate();

        // A WebView in a secondary process must use its own data directory, and
        // the suffix has to be set before the provider loads. This is the one
        // call that loads it, so the browser takes its directory first; setting
        // it inside the activity afterwards is too late and leaves both
        // processes sharing a directory, which the WebView refuses:
        // "Using WebView from more than one process at once with the same data
        // directory is not supported."
        if (isBrowserProcess() && Build.VERSION.SDK_INT >= 28) {
            try { WebView.setDataDirectorySuffix(JawnBrowserActivity.WEBVIEW_DATA_DIR); } catch (Throwable ignored) { }
        }

        // A debuggable app (the release variant keeps it for `run-as`) gets
        // WebView remote debugging unless it is told otherwise. Set it once for
        // every process here; a shipped build must not expose the inspector.
        WebView.setWebContentsDebuggingEnabled(!com.jawnos.BuildConfig.SHIPPED);

        // Set crash handler for the app
        JawnCrashUtils.setCrashHandler(this);

        // Set log level for the app
        setLogLevel();

        // A process start while the house is quiet must not undo the silence.
        JawnSilence.attach(this);
        JawnSilence.ensure();

        // M5: targetSdk 36 stops us exec'ing a file the app wrote into its
        // data dir, so the runtime's executables arrive as native libraries.
        // nativeLibraryDir moves on every APK update, so re-point the links
        // here, before anything starts the tree. It is a no-op when the APK
        // carries no runtime-bins.txt.
        JawnRuntimeBins.ensure(this);

        // The pin in assets/jawnrt.json is what an install should run, but the
        // installer only ever ran on first run. Compare it with the marker the
        // last install wrote and unpack the pinned runtime if it moved. This is
        // on a background thread so the splash stays responsive; the tree's
        // supervisor waits for it (JawnRuntime.awaitForStart) before it execs
        // perl, so the prefix is never swapped under a live tree. A matching
        // pin is one small file read.
        JawnRuntime.syncInBackground(this);

        // The device bridge and the BLE daemon are services in this process.
        // A package update kills the app while the exec'd perl tree survives,
        // so the tree stays "good" but every bridged device call is gone. An
        // app start is the one event both the update path and a person's tap
        // always produce, so heal them here; both starts are idempotent.
        JawnLauncher.startDeviceService(this);
        if (JawnLauncher.isBleWithJawn(this)) JawnLauncher.startBle(this);

        // A process start is the one event every path shares: an APK update, a
        // wake, a person opening the app. If the tailnet should be up, ask the
        // service to check itself - it resumes only what is down, so a healthy
        // tunnel is never torn down here.
        if (JawnTailnetController.isEnabled(this) && JawnTailnetController.isAutoconnect(this)) {
            JawnTailnetController.supervise(this);
        }
    }

    /** True in the ":browser" activity's own process (AndroidManifest.xml). */
    private boolean isBrowserProcess() {
        String name = processName();
        return name != null && name.endsWith(JawnBrowserActivity.PROCESS);
    }

    /** The name of the process this Application was created in. */
    private String processName() {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                String name = getProcessName();
                if (name != null) return name;
            } catch (Throwable ignored) { }
        }
        int pid = android.os.Process.myPid();
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
                    if (info.pid == pid) return info.processName;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private void setLogLevel() {
        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        JawnSharedPreferences preferences = JawnSharedPreferences.build(getApplicationContext());
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
        Logger.logDebug("Starting Application");
    }
}

