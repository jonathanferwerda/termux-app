# JawnOS fork of termux-app

Base: upstream tag v0.118.3, branch jawnos.
Purpose: host and supervise jawnos (the Perl app) on Android; see ../PLAN.md.
> **Agents: bump versionCode and publish on every change to the APK (see AGENTS.md).**

## Deltas from upstream (keep minimal; list every change here)

1. app/build.gradle: build arm64-v8a only.
   - defaultConfig: added ndk { abiFilters "arm64-v8a" }
   - downloadBootstraps: removed arm, i686 and x86_64 entries (aarch64 only)
2. Planned: branding, the JawnOS overlay dot service, boot autostart,
   a dashboard activity.

## Build (local)

    JAVA_HOME=<jdk-11 dir> ANDROID_SDK_ROOT=<android-sdk dir> \
    TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0 \
      ./gradlew -p . :app:assembleDebug --no-daemon --max-workers=4

TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0 makes Gradle emit one universal APK
instead of per-ABI split APKs; release builds default to a single APK.

Cap Gradle at 4 workers (--max-workers=4): this laptop is shared with other
work and a full-width build has crashed it (2026-10-10).

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

## Deltas round 5 (BLE bridge controls)

13. JawnDashboardActivity: "Start BLE bridge" / "Stop BLE bridge" buttons for
    JawnBleService (com.termux.api). Same shared UID, so the unexported service
    can be started from here; this is also the always-allowed foreground-
    service start path (the dashboard is visible).

## Deltas round 6 (logo1 art)

14. Launcher icons + the dashboard logo now come from ~/Downloads/logo1.png
    (yellow doodle on black, the inverted variant of the original). The
    generator (../tools/gen-icons.py) extracts the doodle with an alpha mask
    from yellowness, so: adaptive background back to black (matches the logo),
    legacy/round tiles are black with the doodle, and
    res/drawable-nodpi/jawn_logo.png is the transparent doodle (sits on the
    dashboard's dark panel without a white box).

## Deltas round 7 (hands-free BLE at boot)

15. JawnBootReceiver also starts JawnBleService at boot (same shared UID;
    BOOT_COMPLETED allows the FGS start), so after a reboot jawn's listener
    finds the daemon already running. Fully hands-free: boot -> daemon + jawn
    -> perl listener dials the bridge.

## Deltas round 8 (the Stop button takes the tree down)

16. JawnLauncher.stopJawn(): runs scripts/manager_killer.pl master through
    login as a background task first, then sends ACTION_STOP_SERVICE 8 s
    later. ACTION_STOP_SERVICE alone only SIGKILLs the terminal sessions --
    the master's Ctrl+C handler never runs, so President.pl, the workers, the
    BLE client and ollama all survived, the web UI stayed up after Stop, and
    the master left over kept ~/.process_watch answering so the next Start
    stood down (the phone ran hot for an hour, 2026-10-10). The killer task
    is not killed by the service stop, so its sweeps always finish. Needs the
    jawnos tree on the device to carry the matching manager_killer.pl (jawnos
    commit 895847c: the "master" argument and the ollama service handling).

## Deltas round 9 (in-app updater)

17. JawnUpdater: the dashboard's status line reads
    "app: <installed> (up to date | -> <n> available)" and an "Update app"
    button installs the newest staged build with one tap. It reads the releases
    under <jawn dir>/releases - the jawnos-app-<versionCode>-<versionName>.apk
    files that scripts/app_release.pl drops and the mesh replicates to every
    device. versionCode is bumped per release so an update is detected.
    - new: JawnUpdater.java, res/xml/jawn_file_paths.xml
    - a FileProvider (authority <pkg>.jawnupdates) in AndroidManifest.xml;
      releaseDir() is built from getFilesDir() (not TermuxConstants' /data/data)
      so the file matches the <files-path> root, which FileProvider compares by
      path prefix.
    - needs the "install unknown apps" grant; the updater opens that screen
      (ACTION_MANAGE_UNKNOWN_APP_SOURCES) when it is missing, and
      REQUEST_INSTALL_PACKAGES is already declared.

## Deltas round 10 (the Open UI button signs in)

18. JawnLauncher.getUiUrl() + JawnDashboardActivity.openUi(): "Open JawnOS UI"
    loads the saved login ticket first (./.shots/ticket.txt, written once per
    device with `perl scripts/app_ticket.pl <name> --save`), so the tap lands in
    the installed full-screen PWA already signed in; without a ticket it opens
    the manager on config.json's `domain`, and only then loopback. The button
    used to open https://127.0.0.1:<working_port>/manager - a different origin
    from the fqdn PWA, with no session, so it always showed the login gate.

## Deltas round 11 (the full screen view lives in the app)

19. JawnWebViewActivity + JawnNfcBridge: the app grows its own full screen view
    of the manager, so the home screen icon no longer depends on Chrome, a
    WebAPK, or the browser's trust of the house certificate. This is the fix for
    the PWA that silently stopped being a PWA after a certificate rotation
    (getting-a-new-cert renews by hand every 90 days; the server also reads
    ./server/*.pem only at boot). The view:
    - loads JawnLauncher.getUiUrl (the saved ticket first, then the manager on
      config.json's domain, then loopback), so it signs in the same way;
    - getUiUrl now rewrites a ticket baked with a loopback host (127.0.0.1) to
      config.json's domain: the wildcard cert covers the name, not 127.0.0.1,
      so a loopback ticket took a TLS error whose session never became a secure
      context - the app landed on the login gate;
    - a re-tap of the home screen icon reloads it (singleTask brings the
      activity forward without recreating it; onNewIntent loads again), so a
      fresh ticket or a restarted jawn is picked up rather than a stale page;
    - it goes straight to the manager when the view already holds a session
      (the ticket splash and its animation are skipped and the view is kept),
      and the ticket only when it does not; a re-tap while on the manager does
      not reload it. It is the launcher now (versionCode 1014): the dashboard
      is exported=false/singleTop, reached by the view's admin gear or a failed
      load, so returning to the app lands on the destination, not the panel;

## Deltas round 12 (the icon badges the program's notifications)

20. JawnNotifyReceiver + NotificationUtils: the app's own notifications (the
    service, run-command, crash and plugin channels) are made badge-less
    (showBadge(false); a badge-able channel is recreated, since showBadge cannot
    change after creation), and jawn's notification_sender now posts through the
    app - `am broadcast -n com.termux/.app.JawnNotifyReceiver` with title/message/
    tag - so the badge lands on the app's own launcher icon. termux-notification
    (the API app, which has no icon to badge) stays only as the fallback when the
    app is absent. JAWNOS.md deltas/round 12.
    - goes immersive full screen, keeps navigation in the house, opens outside
      links in the browser, and proceeds past the house's own TLS cert
      (onReceivedSslError for the house host / loopback);
    - is the target of a new launcher alias (JawnUiLauncher, label "JawnOS UI",
      the jawn logo, @string/jawn_ui_label), and of the dashboard's "Open JawnOS
      UI" button; the old ACTION_VIEW path stays as "Open JawnOS UI in browser".
    - NFC: Android WebView has no Web NFC ('NDEFReader' is absent), so
      JawnNfcBridge exposes window.JawnNFC and drives the NfcAdapter (reader
      mode). The web app presents the same NDEFReader surface over it
      (public/js/manager/manager.js, jawnNfcAdapter()), so scan and write are
      unchanged. New permission android.permission.NFC; android.hardware.nfc
      is required="false". JAWNOS.md deltas/round 11.

## Deltas round 13 (native contacts + call state)

21. JawnContactsBridge: exposes window.JawnContacts to the manager WebView -
    read-only contacts (list/search via ContactsContract), the call log
    (CallLog.Calls), and live call-state events (RINGING/OFFHOOK/IDLE through
    window.__jawnPhoneEvent). No system role; the three READ_* runtime
    permissions are asked for when the page calls request() (or granted with
    `pm grant` on a sideloaded device). Wired in JawnWebViewActivity alongside
    JawnNFC/JawnCamera, with onResume/onPause lifecycle and an
    onRequestPermissionsResult branch (REQ_PERMS = 7004). Android 12+ withholds
    the incoming number from a plain PhoneStateListener (dialer / call-screening
    role needed), so ring and hangup still fire the event and the number is
    filled from the call log right after.
