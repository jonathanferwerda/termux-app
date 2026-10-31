package com.jawnos.app;

import android.app.Application;

import com.jawnos.shared.crash.JawnCrashUtils;
import com.jawnos.shared.settings.preferences.JawnSharedPreferences;
import com.jawnos.shared.logger.Logger;


public class JawnApplication extends Application {
    public void onCreate() {
        super.onCreate();

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

