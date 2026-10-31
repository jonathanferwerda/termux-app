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
    }

    private void setLogLevel() {
        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        JawnSharedPreferences preferences = JawnSharedPreferences.build(getApplicationContext());
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
        Logger.logDebug("Starting Application");
    }
}

