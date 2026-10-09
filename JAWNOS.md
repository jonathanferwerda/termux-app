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
   TERMUX_API_APP_NAME = "JawnOS API". Launcher icon generated from the
   GitHub logo (~/Downloads/logo.jpg; crop-detected yellow doodle on white)
   into all mipmap densities (legacy + round + adaptive foreground
   ic_launcher_fg); both mipmap-anydpi-v26 XMLs point at it.
4. New classes in app/src/main/java/com/termux/app/:
   - JawnLauncher  prefs; start/stop jawn via TermuxService
                   ACTION_SERVICE_EXECUTE. Executes through $PREFIX/bin/login
                   rather than bash directly (login exports the termux-exec
                   LD_PRELOAD shim; see delta 10). Reads working_port from
                   config.json.
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

## Deltas round 3 (icons + dashboard theme + login launch)

8. Launcher icon source is now the GitHub logo (~/Downloads/logo.jpg).
   Tools: ../tools/gen-icons.py (wrapped by gen-icons.sh) crop-detects the
   doodle and writes every mipmap density. The adaptive icon background in
   mipmap-anydpi-v26/*.xml is white to match the logo background.
9. values/styles.xml: new Theme.Termux.JawnDashboard (dark Material,
   windowBackground #FF1E1E1E); AndroidManifest sets it on
   JawnDashboardActivity. Reason: Theme.Termux is a *Light* theme with a
   black windowBackground (made for the terminal), so the dashboard's plain
   text views rendered black-on-black and only buttons were visible.
10. JawnLauncher executes $PREFIX/bin/login instead of bash. Termux's login
    script exports LD_PRELOAD=$PREFIX/lib/libtermux-exec-ld-preload.so, and
    the RUN_COMMAND path the dashboard uses does not go through login on its
    own; without the shim, jawn's children died with
    "bad interpreter: /usr/bin/perl" (President.pl has a #!/usr/bin/perl
    shebang; the console worked only because the app spawns it via login).

## Deltas round 4 (boot quiet + dashboard logo)

11. JawnLauncher.startJawnBackground(): boot autostart now runs jawn as a
    background task (EXTRA_BACKGROUND=true) instead of a foreground session,
    so a reboot no longer opens a console session with jawn's output. The
    foreground service and dot are unaffected (TermuxService.onCreate and
    onStartCommand call runStartForeground() in every mode). The dashboard
    Start button still uses a foreground session.
12. JawnDashboardActivity: the logo (res/drawable-nodpi/jawn_logo.png, from
    the same logo.jpg as the launcher icons) is shown centred at the top.
