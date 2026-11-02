package com.jawnos.app;

import android.app.Application;
import android.webkit.WebView;

import com.jawnos.shared.crash.JawnCrashUtils;
import com.jawnos.shared.settings.preferences.JawnSharedPreferences;
import com.jawnos.shared.logger.Logger;


public class JawnApplication extends Application {
    public void onCreate() {
        super.onCreate();

        // A debuggable app (the release variant keeps it for `run-as`) gets
        // WebView remote debugging unless it is told otherwise. Set it once for
        // every process here; a shipped build must not expose the inspector.
        WebView.setWebContentsDebuggingEnabled(!com.jawnos.BuildConfig.SHIPPED);

        // Set crash handler for the app
        JawnCrashUtils.setCrashHandler(this);

        // Set log level for the app
        setLogLevel();

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
    }

    private void setLogLevel() {
        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        JawnSharedPreferences preferences = JawnSharedPreferences.build(getApplicationContext());
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
        Logger.logDebug("Starting Application");
    }
}

