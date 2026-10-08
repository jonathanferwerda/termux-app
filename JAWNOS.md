# JawnOS fork of termux-app

Base: upstream tag v0.118.3, branch jawnos.
Purpose: host and supervise jawnos (the Perl app) on Android; see ../PLAN.md.

## Deltas from upstream (keep minimal; list every change here)

1. app/build.gradle: build arm64-v8a only.
   - defaultConfig: added ndk { abiFilters "arm64-v8a" }
   - downloadBootstraps: removed arm, i686 and x86_64 entries (aarch64 only)
2. Planned: branding, the JawnOS overlay dot service, boot autostart,
   a dashboard activity.

## Build (local)

    JAVA_HOME=<jdk-11 dir> ANDROID_SDK_ROOT=<android-sdk dir> \
    TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0 \
      ./gradlew -p . :app:assembleDebug --no-daemon

TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0 makes Gradle emit one universal APK
instead of per-ABI split APKs; release builds default to a single APK.

## Deltas round 2 (branding + dot + autostart + dashboard)

3. Branding: manifestPlaceholders TERMUX_APP_NAME = "JawnOS",
   TERMUX_API_APP_NAME = "JawnOS API". Launcher icon regenerated from
   jawnos neon/crown.svg into all mipmap densities (legacy + round + adaptive
   foreground ic_launcher_fg); both mipmap-anydpi-v26 XMLs point at it.
4. New classes in app/src/main/java/com/termux/app/:
   - JawnLauncher  prefs; start/stop jawn via TermuxService
                   ACTION_SERVICE_EXECUTE (bash -lc 'cd <dir> && exec perl
                   ./jawn'); reads working_port from config.json.
   - JawnDot       12 dp TYPE_APPLICATION_OVERLAY circle, bottom-left, tap opens
                   the dashboard. Shown while TermuxService runs; keeps the app
                   (and the whole UID, incl. jawn workers) out of the restricted
                   "moderate" cpuset Samsung phones apply to hidden apps.
   - JawnBootReceiver  BOOT_COMPLETED -> start jawn (autostart preference).
   - JawnDashboardActivity  start/stop, open UI (config.json port) and console,
                   overlay permission button, autostart checkbox, folder name.
5. AndroidManifest: RECEIVE_BOOT_COMPLETED; JawnBootReceiver;
   JawnDashboardActivity (exported, for adb testing).
6. TermuxService: JawnDot.show() in runStartForeground(), JawnDot.hide() before
   runStopForeground().
7. Tools live in ../tools: apply-jawnos-edits.pl (generates edits 3,5,6 from
   upstream state), gen-icons.sh.
