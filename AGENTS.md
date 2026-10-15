# AGENTS.md — the JawnOS Android app (termux-app fork)

This repo (`~/jawnos-app/termux-app`, branch `jawnos`, remote `fork`) is the
Android app that hosts and supervises jawnos on a phone/tablet. `JAWNOS.md`
lists every fork delta. Read this first.

## Rule 1 — every shipping change gets a version bump AND a publish

If you change the app and want a device to take it, you MUST, in the same change:

1. **Bump `versionCode`** (integer, +1) **and the `versionName` patch** in
   `app/build.gradle`, and
2. **build, stage and publish** the APK.

Why: the whole update path compares an APK's `versionCode` against the installed
one — the in-app updater (the dashboard's "Update app"), the peer/GitHub sync
(`subs::app_release_sync`), and the published manifest (`scripts/app_publish.pl`).
A rebuild that reuses a `versionCode` reads as **"up to date"** and **no device
will take it** (this is exactly why a build once sat uninstalled while the app
kept saying it was current). Never reuse or re-ship a number; always step above
the last release (1027 / 0.118.28).

## The one-step way (preferred)

From the jawnos repo (`~/jawnos`):

    perl scripts/app_build.pl      # bumps versionCode + versionName, builds, stages into releases/
    perl scripts/app_publish.pl    # publishes the newest staged APK + manifest to GitHub

Then commit and push this repo (app_build.pl leaves it dirty on purpose — it only
builds and stages, so the bump is yours to commit):

    git -C ~/jawnos-app/termux-app add -A
    git -C ~/jawnos-app/termux-app commit -m "v<versionName> - <summary>"
    git -C ~/jawnos-app/termux-app push fork jawnos

Skipping the publish leaves peers and GitHub still advertising the previous
versionCode, so devices will not see the new build.

## Building by hand

If you build with gradlew directly, **bump `versionCode`/`versionName` in
`app/build.gradle` yourself first**, then stage and publish:

    JAVA_HOME=/home/jawn/jdk/jdk-11.0.32.1+1 \
    ANDROID_SDK_ROOT=/home/jawn/android-sdk \
    TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0 \
      ./gradlew -p . :app:assembleDebug --no-daemon --max-workers=4

    perl ~/jawnos/scripts/app_release.pl <the-universal-apk>
    perl ~/jawnos/scripts/app_publish.pl

## Conventions
- Cap Gradle at 4 workers (`--max-workers=4`) — this laptop is shared.
- Build arm64-v8a only; keep the fork's deltas minimal and log each one in
  `JAWNOS.md`.
- `scripts/app_build.pl` auto-detects the SDK (`~/android-sdk`) and a JDK
  (`~/jdk/jdk-*`); `scripts/app_publish.pl` needs an authenticated `gh`.
