package com.jawnos.app.models;

/** The user actions that can name a report the app writes or shares. */
public enum UserAction {

    ABOUT("about"),
    CRASH_REPORT("crash report");

    private final String name;

    UserAction(final String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
