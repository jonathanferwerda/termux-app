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

## Deltas round 14 (call screening = caller id)

22. JawnCallScreeningService + JawnPhoneState: with the Call screening role
    granted, Android hands each incoming call's number to onScreenCall (which a
    plain PhoneStateListener no longer gets on Android 12+). The number is left
    in JawnPhoneState, and the WebView bridge (JawnContactsBridge) reads it - the
    RINGING state event carries the caller, incomingNumber() exposes it to the
    phone app, and screeningRole()/requestScreening() read and ask for the role.
    The service used to never block a call; round Q gives it the desk's
    blacklist to refuse. Manifest: the service declared with
    BIND_SCREENING_SERVICE, alongside READ_CONTACTS/READ_CALL_LOG/READ_PHONE_STATE
    (round 13) for the rest of the phone surface.

## Deltas round N (native sms + mms bridge)

- JawnSms.java: window.JawnSms - SMS via SmsManager (multipart, sent
  broadcast answers via window.__jawnSmsResult), MMS via the klinkerapps
  android-smsmms library when JawnOS is the default sms app, and a
  share() fallback that hands the OS messaging app the file otherwise.
  Registered in JawnWebViewActivity beside the camera and contacts
  bridges. SEND_SMS permission added to the manifest.

## Deltas round O (sms + dialer role eligibility)

- JawnSmsReceiver: the SMS_DELIVERED receiver the default-sms-app role asks
  for - when JawnOS is the default sms app, delivered texts land here first;
  they are logged and the newest is held in JawnPhoneState for the webview.
- JawnComposeSmsActivity / JawnDialerActivity: the role's faces - the
  system's sms send and dial intents open the house (the number and a
  pre-filled sms body ride along as jawn_sms_number / jawn_sms_body /
  jawn_dial_number extras for the web side to read).
- JawnInCallService + JawnInCallActivity: the default-dialer role's
  InCallService, with a full-screen answer / decline / hang-up screen; the
  ringing number still feeds JawnPhoneState so the call appointment keeps
  stamping itself. RECEIVE_SMS, READ_SMS and CALL_PHONE permissions added.

## Deltas round P (launch extras into the page)

- JawnWebViewActivity.injectLaunchExtras: the sms send and dial faces pass
  jawn_sms_number / jawn_sms_body / jawn_dial_number along; on page finish
  they are handed once to window.__jawnLaunchExtras (the chat opens on the
  number with the body prefilled; the phone pad fills).

## Deltas round Q (the desk's blacklist, and a named call)

- JawnPhoneBridge.java: window.JawnPhone - the house's phone facts, handed to the
  native side because a call arrives when no page is open. The page
  (public/js/manager/manager.js, jawnPhoneSync) fetches GET /manager/phone/sync
  and calls setFacts({blocked:[…], names:{…}}); the blob lives in
  SharedPreferences ("jawn_phone") rather than a static, because call screening
  often starts into a process with no WebView up. takeBlockedCalls() hands the
  refused calls back once, for the page to file as cancelled ones. Numbers are
  compared on their last ten digits (JawnPhoneBridge.lastTen), the way the house
  matches them.
- JawnCallScreeningService: no longer always-allows. A number on the desk's
  blacklist (the Box Office list, seen from the phone) is disallowed and rejected
  before the phone can ring - setDisallowCall/setRejectCall, skipCallLog false so
  the house still files what was ignored, skipNotification true; any other call
  is allowed through unchanged.
- JawnInCallActivity: the lock-screen wake names the caller from the same synced
  numbers (JawnPhoneBridge.nameFor) above the raw number, with rounded
  answer/decline buttons on the house's dark ground.
- Wired in JawnWebViewActivity beside JawnNFC/JawnCamera/JawnContacts/JawnSms
  ("JawnPhone").

## Deltas round R (the call screen wears a face)

- JawnInCallActivity: the house's mark (JawnOS) sits at the top of the screen and
  the caller gets a round picture - the person's own image when the house sent
  one, the app's own logo (drawable-nodpi/jawn_logo.png) when it did not. The
  picture arrives as a data URI, is decoded and centre-cropped through a circular
  shader, and is repainted once per caller rather than once per tick.
- JawnPhoneBridge: the synced blob carries avatars as well now (last-ten digits ->
  data URI), so the call screen needs no network of its own: the page fetches the
  whole directory at once (GET /manager/phone/sync) and the activity only decodes
  what it already has when the phone rings.

## Deltas round S (the phone and sms roles)

- The roles were not offered because the components the role manager asks for
  were not there. Checked on the device with `cmd package query-activities`:
  a bare ACTION_DIAL (no data) matched only Samsung's dialer - our filter carried
  the tel scheme, and a filter with data never matches an intent without it - and
  `SENDTO -d mms:` matched only Samsung, and no SMS_DELIVER receiver existed at
  all. So now:
  - JawnDialerActivity answers a bare ACTION_DIAL as well as DIAL+tel (and
    ACTION_CALL), and JawnInCallService is exported with the DEFAULT category, so
    the system can find and bind it.
  - JawnComposeSmsActivity declares all four schemes (sms, smsto, mms, mmsto) on
    SENDTO and VIEW.
  - JawnSmsDeliverReceiver (new, android.provider.Telephony.SMS_DELIVER, guarded
    by BROADCAST_SMS): with the sms role, delivered texts come here alone - the
    framework stops filing them - so it writes the message into the system's
    message store (which is what the house's own termux poll reads), joins
    multi-part pdus per sender, leaves it in JawnPhoneState, and appends it to
    jawn's log so a text is never lost to a failed write.
  - The klinkerapps mms machinery is declared at last: its transaction service,
    its PushReceiver on WAP_PUSH_DELIVER (application/vnd.wap.mms-message) and
    its status receivers, so an incoming mms can be downloaded and a send can
    ride a transaction. RECEIVE_MMS added.
  - JawnLog.append: one line, shared by both receivers.

## Deltas round T (the sms role at last, and a call that files itself)

- The sms role was still refused after round S, and the system said why in one
  line: "com.termux not qualified for android.app.role.SMS due to missing
  RequiredComponent ... RESPOND_VIA_MESSAGE (smsto) SEND_RESPOND_VIA_MESSAGE".
  JawnRespondViaMessageService answers that: a service on
  android.intent.action.RESPOND_VIA_MESSAGE for the smsto scheme, guarded by
  SEND_RESPOND_VIA_MESSAGE (the permission is signature|privileged, so what
  matters is the component declaring it - exactly how Google Messages does it).
  It is also a real face: the call screen's quick reply sends the text through
  SmsManager and logs it. With it in place the role is granted.
- JawnInCallService keeps its own record of every call now - number, direction,
  when it started, whether it ever reached STATE_ACTIVE (somebody answered), when
  it ended - and leaves it in the app's store (JawnPhoneBridge.recordCall) as the
  call ends, logging it too. The appointment used to be written by the page's
  state listener alone, so a call taken with the app in the background wrote
  nothing at all; the house files it from this record whichever way it arrives.
- JawnPhoneState.poster + a JawnWebViewActivity onDestroy: the page lends the
  native half a way to run a snippet, so a call that just ended is filed at once
  when a page is up.
- JawnPhoneBridge: recordCall/takeCalls, beside the blocked-call queue.

## Deltas round U (the barcode scanner)

- JawnScan (new): window.JawnScan beside JawnNFC/JawnCamera/JawnContacts/JawnSms.
  One method scan() hands the read to Google Play services' "code scanner"
  (GmsBarcodeScanning.getClient on the activity, FORMAT_ALL_FORMATS +
  enableAutoZoom), so the phone draws the on-device scan sheet with no camera
  permission of ours, and reports the raw value over window.jawnScanResult(code)
  via JSONObject.quote, the way the other bridges report. A failure or a cancel
  reports an empty string, and the web side (public/js/manager/market.js) falls
  back to its BarcodeDetector camera or a typed code.
- app/build.gradle: implementation "com.google.android.gms:play-services-code-scanner:16.1.0"
  (the bridge and the sheet live in Play services/ML Kit; the app only asks).
- Wired in JawnWebViewActivity beside the other bridges ("JawnScan").

## Deltas round V (the scan sheet comes down on Done/Cancel)

- JawnScan: close(), plus a com.termux.app.SCAN_STOP broadcast receiver registered
  while a scan is open. Google's sheet runs in our task, one activity above ML
  Kit's invisible delegate, so re-fronting the delegate with CLEAR_TOP |
  SINGLE_TOP finishes the sheet. The WebView is paused behind it, so the receiver
  is the channel that works; the manager fires the broadcast
  (`am broadcast -a com.termux.app.SCAN_STOP -p com.termux`) when the run is Done
  or Cancelled, and the page's marketPhoneScanStop() also calls close().

## Deltas round W (self-heal and a wake trigger)

- TermuxService: the JawnOS host is sticky while jawn is meant to be up. A
  system kill now restarts the service with a null intent, which starts jawn
  again (JawnLauncher.shouldKeepRunning); an explicit Stop clears the
  preference, so it stays stopped. This is the fix for the app disappearing
  overnight on 2026-10-17 (the log stopped at 02:55 and resumed at 09:18 only
  because the dashboard was opened; TermuxService had returned
  START_NOT_STICKY, so Android had nothing to restart).
- JawnLauncher: KEY_RUNNING (the user's intent), shouldKeepRunning(),
  setKeepRunning(), and selfTest() - a one-line check of jawn, the master and
  the BLE bridge that starts whatever is down when keep-alive is on. Start,
  Restart and the boot receiver set it; Stop clears it.
- JawnDashboardActivity: runs selfTest() on resume and offers a "Self test"
  button beside Start/Restart/Stop. Reopening after Stop no longer restarts
  jawn (Stop is now a real stop); press Start or Self test to bring it back.
- JawnWakeReceiver (new, exported): `com.termux.app.JAWN_WAKE` starts jawn from
  outside the app; only the shell UID (adb) and the app itself are accepted.
  `scripts/phone_wake.pl` in the jawnos tree sends it over adb.

## Deltas round 12 (native fingerprint)

- JawnBiometric: `window.JawnBiometric.confirm(title)` uses the platform
  `BiometricPrompt` (API 28+) and answers through
  `window.jawnBiometricResult(true|false|'unavailable')`. Registered on the
  WebView in JawnWebViewActivity. The padlock unlock page prefers it and falls
  back to WebAuthn or the tap when the phone has no fingerprint.

## Deltas round 13 (biometric unlock key)

- JawnBiometricKey (new): `window.JawnBiometricKey` creates a non-exportable
  EC P-256 key in Android Keystore with `setUserAuthenticationRequired(true)`
  and `setInvalidatedByBiometricEnrollment(true)`. `enroll(title)` returns its
  SPKI public key; `sign(msgBase64,title)` signs only after the platform
  BiometricPrompt releases the key, and `hasKey()` says whether one exists.
  Results arrive through `window.jawnosBiometricKeyResult(...)`. Registered on
  the WebView in JawnWebViewActivity beside JawnBiometric.
- AndroidManifest: `USE_BIOMETRIC` permission and an optional
  `android.hardware.fingerprint` feature. The old `JawnBiometric.confirm`
  prompt needed the permission too.
- The JawnOS house verifies the key's ECDSA signature (`subs::ecdsa_verify`)
  and lets it unlock the phone's own locked screen. Enrollment is bound to the
  device's existing pairing key: the page signs the new key with the Ed25519
  key it was paired with, so a page that merely knows a UUID cannot register a
  key. `Security -> Padlock -> Enroll this phone's fingerprint` sets it up, and
  the locked screen offers `Use this phone's fingerprint` once a key is enrolled.
  Enrollment is refused while the screen is locked, so set it up before locking.

## Deltas round 14 (embedded house tailnet)

20. Tailscale client embedded in the JawnOS app as a `VpnService`, so no
    separately branded app is installed.
    - `app/libs/libtailscale.aar` (61 MB, gitignored) is built from
      `tailscale-android` by `tools/build_tailscale_aar.sh` (Go + SDK +
      NDK 23.1.7779620; the AAR ships the arm64 `libgojni.so`).
    - New classes in `app/src/main/java/com/termux/app/`:
      `JawnTailnetService` (VpnService + `libtailscale.IPNService`),
      `JawnTailnetController` (ControlURL/Hostname/auth-key join),
      `JawnTailnetAppContext` (interfaces/DNS/prefs), `JawnTailnetBuilder`
      and `JawnTailnetParcel` (the TUN builder).
    - AndroidManifest: the service with `BIND_VPN_SERVICE` and the
      `android.net.VpnService` filter; `POST_NOTIFICATIONS`.
    - Dashboard: a Tailnet card (Join/connect, Approve device, Stop) with a
      status line. `JawnWebViewActivity` accepts an `EXTRA_URL` for the
      Headscale approval page and keeps `thejunderground.com` in the view.
      `JawnBootReceiver` starts the service after boot when it is enabled.
    - `minSdkVersion` 24 -> 26 (the AAR's minSdk).
    - Join defaults to the node name in `config.json` (`phone`, `tablet`),
      ControlURL `https://thejunderground.com`, and an optional reusable
      Headscale preauth key; blank means the browser approval path.


## Deltas round 15 (version-only rebuild)

23. No Java behaviour changed in this build. The tailnet internet fix is on the
    Headscale server (its DNS proxy had no upstreams, so external lookups timed
    out), and a machine reaches its own manager through its own configured
    domain: the router's DNS answers that name with the machine's LAN address,
    and the laptop's hosts file answers it with `127.0.0.1`. Keeping that origin
    also keeps the paired-device identity and session cookie intact. The build
    number moved to 1061 only so the staged APK stays above the installed 1059.

## Deltas round 16 (loopback-first view)

24. `loopback_manager: true` in `config.json` makes the app open its own
    manager at `https://127.0.0.1:<port>` instead of the configured domain.
    Loopback needs no DNS, no router and no tailnet, so the view answers the
    moment the device joins any network. The wildcard certificate is still the
    house's; the WebView already proceeds on a loopback TLS error.
25. The paired-device id lived only in `localStorage`, which is per origin, so
    moving to loopback looked like an unpaired browser. `JawnAppStorage`
    (SharedPreferences `jawn_app_state`) mirrors `device_uuid`; `universal.js`
    restores it on the new origin. On the first launch after the switch, the
    app opens the domain form once, copies the id into the bridge (and copies
    the domain session cookie), then moves to loopback for good.
26. The ticket is addressed at loopback too (`JawnLauncher.getUiUrl` swaps the
    host); `/box_office` accepts a loopback caller and mints the session there.

## Deltas round 17 (keep the device's DNS)

27. The embedded tailnet no longer takes over DNS (`CorpDNS=false`). A
    Headscale MagicDNS proxy whose request queue fills (`dns udp query:
    request queue full`) makes every lookup time out, which reads as "no
    internet" even though IP traffic still works. The app reaches itself at
    127.0.0.1 and peers by their `100.64.x.x` addresses, so the device's own
    resolver stays in charge and the tailnet is only a route.

## Deltas round 18 (a splash that leaves, and the gate it can see)

28. `loadWhenReady` used a port probe to decide whether to show the splash.
    A busy or just-restarted tree made the probe disagree with the dashboard
    ("running" while the view sat on the splash forever). The view now loads
    the page first and only starts the tree when the port really is closed;
    a dead page reports itself and opens the dashboard.
29. Gate detection used `welcome_logo` / `admin_panel_button`, which also exist
    on the manager, so the manager itself looked like the Gate and the view
    reloaded the splash in a loop. It now reads `document.title === 'gate'`.

## Deltas round 19 (the exit node keeps its resolver)

30. The embedded tailnet takes DNS again (`CorpDNS=true`). With `CorpDNS=false`
    the VPN network carried no DNS servers; while the tunnel was the default
    network the Go client had no resolver. An exit node changes the routes and
    forces a reconnect, and Headscale and DERP then could not be resolved, so
    the device dropped off the tailnet and all traffic into the exit stopped.
    Headscale now carries 1.1.1.1 and 8.8.8.8, so MagicDNS forwards external
    names. This supersedes round 17 item 27; the queue-full symptom was the
    missing upstreams, not MagicDNS itself.
31. `getPlatformDNSConfig` reads the physical network underneath the VPN, not
    `getActiveNetwork` (which is the tunnel while it is up). The base config
    must not name MagicDNS as its own upstream.

## Deltas round 20 (battery: the view sleeps and runs at 60 Hz)

32. No `FLAG_KEEP_SCREEN_ON`. The view is a UI, not a kiosk; holding the screen
    awake while it idled was the phone's biggest heat and battery cost. The
    system screen timeout now applies normally (observed: `mHoldScreenWindow`
    was the JawnOS window, with the panel at 120 Hz and JS otherwise idle).
33. `preferredRefreshRate = 60f` on the view's window. The panel's default mode
    is 120 Hz, so an idle manager page was redrawn 120 times a second for no
    visible gain. The page still animates smoothly at 60.
34. `web.pauseTimers()` on pause and `resumeTimers()` on resume. The house work
    runs in Termux, not in the page; the WebView no longer keeps its timers
    warm while the app is off-screen. The page reconnects its sockets on
    visibilitychange/resume, as before.

## Deltas round 21 (music survives the screen going off)

35. A WebView `onPause()` paused the page's timers (and, in practice, the
    player) whenever the screen turned off or the app was backgrounded, so a
    track stopped and the queue never reached the next one. `JawnMedia`
    (`window.JawnMedia.playing`) lets the manager's player say when a track is
    actually playing; while it is, `onPause` leaves the WebView and its timers
    running. A real stop releases it a few seconds later and the page pauses
    as before.

## Deltas round 22 (one tunnel, not a restart loop)

36. The network watcher restarted the tunnel on every ConnectivityManager
    callback. A restart tears the VPN down and re-establishes it, so its own
    transition fed the watcher and the tunnel restarted about every four
    seconds - the key icon flicker, peers answering "no route", and (twice on
    2026-10-22) a SIGABRT inside `libgojni.so` that killed the whole app. The
    new `JawnTailnetNetwork` picks the non-VPN network underneath; the service
    passes it to `setUnderlyingNetworks`, names that interface to the Go
    client's netmon (`onDNSConfigChanged`, `onGatewayChanged`), and restarts
    only when the underlying network really changes. A restart is single-flight
    and holds `restartPending` until it finishes.
37. `bindSocketToNetwork` used `getActiveNetwork()` - the tunnel itself while it
    is up - so the client's own sockets could be bound into the tunnel. It now
    binds to the cached physical network.
38. `JawnTailnetController.start/stop/restart/resume` are serialized; overlapping
    start and stop was the shape of the SIGABRT. A system restart of the service
    (`android.net.VpnService`, or a sticky re-start) now only re-requests the
    TUN and honours the stored `enabled` choice instead of re-running the join.
39. One approval notification per distinct login URL; every restart used to
    re-post the same one.

## Deltas round 23 (the keyboard's microphone)

40. `JawnSpeech` (new): window.JawnSpeech beside the other bridges. A WebView
    has no Web Speech API, so the on-screen keyboard's microphone asks the
    phone's own `SpeechRecognizer` instead. `start(language)` begins listening
    (asking for RECORD_AUDIO the first time), final phrases arrive at
    `window.jawnSpeechResult(text, true)` and interim ones with false, and an
    error is named over `window.jawnSpeechState('error', reason)`. The page
    restarts it after each phrase while the microphone is on. Registered in
    `JawnWebViewActivity` beside the camera and contacts, with the permission
    result routed to it and `destroy()` on the activity's own destroy.

## Deltas round 24 (one GPS, and it is really GPS)

41. `JawnLocationService` (new): a `foregroundServiceType="location"` service
    that reads `LocationManager.GPS_PROVIDER` and nothing else, writes each fix
    to `~/.president/location.json`, and speaks the page through
    `window.JawnLocation` (`latest()`, `status()`, `start()`, `startAt()`, `stop()`).
    A WebView's `navigator.geolocation` (and `termux-location -p network`) can
    answer from Wi-Fi or a cell tower while reporting a plausible accuracy, which
    put the phone in places it had never been. Every record this service emits
    names `provider: "gps"`; the fork disables WebView geolocation in favour of
    it. The interval is the navigation pace (four minutes by default) and is
    retuned by the page while a running appointment asks for faster fixes.
42. `JawnLocationBridge` (new): the permission prompt, start/stop, and the JSON
    the page and the Perl tree both read. Added as `JawnLocation` beside the
    other bridges; `JawnWebViewActivity.ensureLocationPermission()` asks for
    ACCESS_FINE_LOCATION once and then starts the service.
43. Manifest: `FOREGROUND_SERVICE_LOCATION` and the service entry. No held wake
    lock: a location foreground service wakes the process when a fix arrives.
44. A start or stop in the manager asks for a fresh fix now, not the last beat:
    `JawnLocation.sample()` / `ACTION_SAMPLE` calls `getCurrentLocation` (API 30+)
    or a single update, and the page waits up to twenty seconds for a fix newer
    than the press. A cached fix is only the fallback, and its own timestamp is
    kept rather than restamped as today's.
45. The service also asks for one fresh fix when it starts, so a cold start does
    not have to wait for the first periodic slot. A `once` appointment whose
    start predates the first fix still takes the next real fix from the headless
    navigation run, so a cold GPS does not lose the point.
46. `JawnScheduledWakeReceiver` (new): the scheduled wake that replaces the
    permanently held Termux wake lock. An exact/allow-while-idle alarm every
    navigation interval takes a CPU-only partial wake lock for 45 s, touches
    `~/.president/wake_ready`, re-arms itself, and lets the timeout release. The
    Perl tree releases its permanent `termux-wake-lock` once that file is fresh
    and no track is playing, and takes it back if the scheduler stops or the
    person switches the schedule off. `JawnLocationService` arms and cancels the
    alarm on start/stop and on every pace change; a stale `wake_ready` is the
    dead-man switch. Kept separate from the existing exported `JawnWakeReceiver`
    (the `JAWN_WAKE` adb hook), whose behaviour is unchanged.

## Deltas round 25 (wake only as long as the work)

47. `JawnScheduledWakeReceiver` releases its partial wake lock as soon as the
    wake has nothing left to do, instead of always holding the CPU for 45 s. It
    polls `~/.president/utility_idle` (written by the manager after a utility
    tick that ran nothing, removed when a job starts) and releases when the
    marker is newer than the wake's own start, the screen is off, and no music
    track holds its lock. A generation counter keeps a re-armed alarm from being
    released by the wake it replaced; the 45 s timeout stays the backstop. The
    Perl side marks the work done because the app cannot see the runner.
48. The navigation pace is one number, not two. The manager writes the
    navigation pool's seconds (including a running appointment's faster request)
    to `~/.president/gps_pace` whenever it changes, and
    `JawnLocationService.restingPaceMs()` reads it for both the GPS sample
    interval and the scheduled wake. The receiver retunes during a wake, so
    editing the navigation pool in Configure → Sys moves the phone's own pace
    without restarting the service; the stored setting is the fallback.
49. A wake is renewed while a peer pulls. The manager touches
    `~/.president/sync_pull` for every row batch a `/manager/sync_rows`,
    `/manager/sync_buckets` or `/manager/backup_serve` request serves, and
    `JawnScheduledWakeReceiver` renews its own partial lock (15 s at a time)
    while that marker is fresh instead of releasing at the 45 s window. The
    phone therefore answers a delta pull to the end; a five-minute hard cap
    keeps a runaway peer from holding the CPU, and the ordinary early release
    resumes as soon as the marker ages out.

## Deltas round 26 (GPS rests between wakes)

50. At the resting pace the service holds no location listener. The scheduled
    wake now asks for one one-shot fix (`getCurrentLocation`) instead of a
    standing `requestLocationUpdates` that kept the GPS provider in
    high-accuracy mode all day. `FAST_PACE_MS` (two minutes) is the switch: a
    running appointment that asks for fixes faster than that - the navigation
    pool's own value is already folded into `~/.president/gps_pace` by
    `subs::navigation_pace` - still gets the standing listener, and
    `retuneUpdates()` moves between the two when the pace changes. The
    notification reads "Checks every N min" while resting rather than waiting
    on a fix that is not being requested.

## Deltas round 27 (an alarm wakes the phone and shows on the lock screen)

51. `JawnClockAlarm` (new): reads `~/.president/clock_alarm.json` (written by
    jawn's Clock) and sets an exact, allow-while-idle AlarmManager alarm five
    seconds before the named time. `JawnClockReceiver` handles
    `JAWN_CLOCK_SYNC` / `JAWN_CLOCK_RING` / `JAWN_CLOCK_DISMISSED` and the exact
    `CLOCK_ALARM`; `JawnClockNotifier` posts a high-importance alarm-channel
    notification with a full-screen intent and holds a partial wake lock across
    the ring. The navigation wake and the boot receiver re-arm it, and the
    activity shows over the lock screen when the full-screen intent names the
    alarm.
52. `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` and `USE_FULL_SCREEN_INTENT` added.
    The sound stays on jawn's Perl side (sox `play`); the app's notification is
    silent so a long track is not talked over.

## Deltas round 28 (answer the alarm without opening the UI)

53. `JawnClockAlarmActivity` (new): the ring's full-screen face on a locked
    phone. A dark theme gradient, a filled circle with the app logo at the
    bottom and an empty ring near the top. Tap the logo to snooze; drag it to
    the top ring to dismiss. It uses `setShowWhenLocked` / `setTurnScreenOn` on
    API 27+ (legacy window flags below), keeps the screen on for the ring, and
    finishes with a fade. The notification's content and full-screen intents
    now open this activity instead of the manager WebView.
54. `JAWN_CLOCK_DISMISS` / `JAWN_CLOCK_SNOOZE` (new receiver actions): the ring
    notification carries Dismiss and Snooze buttons. Both stop the sound,
    cancel the ring notification and write `~/.president/clock_control.json`
    for jawn's alarm worker to apply to the shared alarm. Neither re-reads the
    old schedule (that armed the moment that had just rung), so the Perl side
    writes the snooze or the next alarm and broadcasts `JAWN_CLOCK_SYNC`.
55. Every alarm sound loops until Dismiss, Snooze or the five-minute guard; a
    missing `loop` extra on an alarm ring now means loop, and a long recording
    no longer plays once and stops.
56. The alarm's own **Snooze length** rides the ring and exact-wake intents, so
    the notification button and the lock-screen tap snooze for that alarm's
    configured minutes rather than a fixed five.

## Deltas round 29 (a peer pull holds the CPU)

57. `JawnScheduledWakeReceiver`: a new `~/.president/wake_hold` lease. jawn
    touches the file when a peer is about to pull or while a row batch is being
    served; the receiver renews its partial wake lock while a touch younger than
    60s belongs to this wake. This keeps the 45-second scheduled window from
    cutting a pull in half when the peer is slow between batches. The existing
    five-minute cap still bounds a runaway peer.
57. Each ring posts under a fresh notification id (a small rotating block,
    swept by `cancel`). Re-using one id made Android treat a second ring as an
    update and skip the full-screen intent, leaving only a heads-up. The
    activity and the receiver also log the gesture (`snooze`/`dismiss`) and the
    written control action, so a device test can be read from `logcat`.
58. The last ring id is kept in `jawn_clock_alarm` preferences, so a process
    restart cancels the previous ring instead of leaving it; `cancel` touches
    only that id plus the three legacy ids, staying under Android's cancel rate
    limit (the previous wide sweep tripped it and the current notification
    survived). The receiver calls `JawnClockAlarmActivity.closeIfShowing()` on a
    notification action or a peer dismiss, so the full-screen face does not stay
    over the keyguard after the alarm is answered elsewhere.

## Deltas round 30 (the Network app's native half)

59. `JawnNetwork` (new): `window.JawnNetwork`, registered by
    `JawnWebViewActivity`. `status()` reports connected/type/ssid/signal(dBm)/ip/
    vpn/interfaces (`{name,type,ip,up}`); `scan()` lists visible networks
    (`{ssid,signal,secure}`, strongest first, duplicates collapsed);
    `vpn()` reports the VPN transport and its interface; `openSettings('wifi'|
    'vpn')` opens `Settings.Panel.ACTION_WIFI` (API 29+) or the full settings
    screen.
60. Connecting is Android's decision. API 29+ hands a password over as a
    `WifiNetworkSuggestion` (`mode:'suggested'`) — WPA2, WPA3 or Enhanced Open
    chosen from the scan's capabilities — and falls back to the Wi-Fi panel
    (`mode:'settings'`) for enterprise networks, Wi-Fi off, or a refused
    suggestion. Below 29 the panel is the only path. It never reports a
    connection it did not make; `status()` is the truth. `disconnect()` is
    honest too: Android 10+ does not let an app drop Wi-Fi, so it returns
    `ok:false` with that message.
61. Only the local JawnOS origin may use it. `onPageStarted` adds the JS
    interface for the house's host (configured domain or loopback) and removes
    it for any other page (including the one allowed sponsor link); the bridge
    also checks the same flag on every call. Scan needs `ACCESS_FINE_LOCATION`
    granted and location services on (API 29+); without them it returns
    `{error}`. Manifest adds `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` and
    `CHANGE_NETWORK_STATE` (`ACCESS_NETWORK_STATE` and the location permissions
    were already there).

## Deltas round 31 (the keyboard is a web page)

62. `JawnImeService` is now an HTML keyboard, not a native view tree: a
    `WebView` loads `assets/ime/keyboard.html` — the manager's light panel,
    white keys with a soft edge, grey specials and the yellow return — and a
    `JawnIme` JavaScript bridge commits each key through the
    `InputConnection` (`send`, `backspace`, `enter`, `hide`). The abc / 123 /
    #+= chips switch pages; `imeConfigure(numeric)` opens the number page for
    number and phone fields; back hides the keyboard.
63. An input method's window does not measure like an activity's. The service
    wraps `onCreateInputView`'s result in `WRAP_CONTENT`, so a WebView left to
    itself measures its content (whose `height:100%` has nothing to resolve)
    and collapses to a strip. The keyboard therefore returns a `FrameLayout`
    with `setMinimumHeight` plus an exact-height child; that fixes both the
    window (300dp, capped at 55% of the short side) and the page's viewport
    (880x300 CSS px at density 1.5). The service window also gets
    `FLAG_HARDWARE_ACCELERATED`, which a WebView needs.
64. The manager dock still serves JawnOS app views (field-aware time chips,
    units, conversions). This IME is the keyboard for every other Android app
    and for views that raise a native editor.

## Deltas round 32 (the input method is the manager's own keyboard)

65. The IME is no longer a simplified copy. `JawnImeService` loads
    `https://<manager host>:<working_port>/manager/keyboard/ime`, a page served
    by the manager that includes the very same `soft_keyboard` fragment and
    `soft_keyboard.js` the dock uses. So the layouts (Samsung / iOS / Gboard /
    the language keyboards), shift, the calculator and its history, the symbol
    page, the magnified key, hold-to-delete and the unit/currency conversion row
    are the same code, with the same units and live rates — not a parallel
    implementation that can drift.
66. `window.jawnSoftIme` is set before `soft_keyboard.js` parses, and
    `window.JawnIme` is the bridge. In that mode the keys call
    `send` / `backspace` / `deleteBack` / `enter` / `replaceAll` on the
    `InputConnection` instead of a DOM field; the service reports
    `getTextBeforeCursor` back through `imeText()` after every edit, which is
    what the conversion and recommendation rows read. `imeConfigure(numeric,
    context, text)` opens the calculator for number/phone editors (seeding it
    with the field's own text) and names the context a hint or a datetime editor
    reveals.
67. The keyboard page needs the manager, so it uses the app's own session:
    `JawnImeService` checks the shared `CookieManager` for `president=`, and
    without one loads the saved ticket (`.shots/ticket.txt`) to mint it,
    intercepting that page's redirect to `/manager` and going to the keyboard
    page instead. Only the manager host, `127.0.0.1` and `localhost` may load;
    its self-signed certificate is accepted for those hosts only. A load error
    or a four-second timeout falls back to `assets/ime/keyboard.html`, so a
    keyboard is still there when the house is unreachable. That fallback is
    retried on the next field (unless there was no session at all), so a load
    that raced the manager's own restart does not leave the plain keyboard in
    place.
68. `soft_keyboard.js` reports the dock's height through `JawnIme.resize()`; the
    service sizes the input-method window to it plus the system navigation bar
    (the conversion row and the layout chooser grow the window, up to 75% of
    the screen height) and gives the WebView the height above that strip, so
    the last key row - and the bundled fallback keyboard - is never under the
    gesture bar. The page also pins `window.scrollTo(0, 0)`, because an input
    method's WebView can otherwise be scrolled by the focus machinery and the
    keys stop matching the taps.

## Deltas round 33 (the vault, the microphone, and a quiet keyboard)

69. The input method's window no longer adds the navigation bar itself. Android
    already wraps a WRAP_CONTENT IME window around the view and adds that strip;
    doing it twice left the blank band below the space bar (about two key rows
    on a phone in three-button navigation). The WebView is now exactly the
    dock's height and the system owns the strip.
70. The microphone is offered in the input method, not only the dock. The
    keyboard page is the manager's own origin, so its dictation uses the same
    `getUserMedia` + `/manager/dictate` pipeline; the service grants capture to
    the manager's origin in `WebChromeClient.onPermissionRequest` (the app
    already holds RECORD_AUDIO). A phrase joins the focused Android editor
    through `softKbInsert`, the same bridge the keys use.
71. The top bar no longer prints the layout's name. The page chips, the vault,
    the layout chooser, the microphone and the hide key are the bar; the chosen
    layout is still highlighted in the chooser.
72. A vault for the keyboard: the `credentials` table (created by
    `app_schema_ensure`, so existing houses gain it on the next start) holds the
    non-secret scope (`app`, `origin`, `kind`, `label`, `hints`, `has_user`,
    `has_password`) and an encrypted `payload` (`subs::note_encrypter` with the
    house key). The routes are `/manager/credentials/{status,save,fill,forget}`;
    they refuse while the house is padlocked (`padlock_locked_now`), and an entry
    with an `origin` is only offered to that site while one without it is only
    offered to that app package. A browser package may not save a password
    without a site: the input method cannot see the address bar, and a
    package-wide Chrome password would be offered on every site.
73. The bar's key fills the focused field from a match; the lock (password) or
    disk (any other field) opens the save drawer. The drawer is typed into with
    the keyboard's own keys (`softKbInsert` writes the page's input, never the
    app's editor), so no second keyboard is raised. The Manager dock fills every
    field in the same form from a saved entry; the input method fills the field
    it has.
74. `JawnAutofillService` is the other half: Android hands it the whole screen's
    form and a web page's domain, so one dataset fills the username and password
    together in Chrome and other apps (and Android's save prompt files a new
    password with the real site). It talks to the manager over loopback with the
    app's session cookie (minting one from the saved ticket when needed), trusts
    the house certificate for that host only, and never logs a secret. The
    dashboard's Keyboard card has "Choose JawnOS autofill" for the one-time
    enable.
75. The vault rows carry `server_time`, so `sync_table_list` includes the table
    automatically: the ordinary v3 row sync, `remote_upgrade` and remote backups
    carry it between the machines of the house. The payload stays sealed with the
    house key end to end; the metadata is what makes the match.

76. The keyboard page sits behind the manager app gate. A ticket approves the
    login but not the app window, so on a house with app access enforced the
    input method falls back to its bundled keyboard until the JawnOS app itself
    has signed in. The app 12-hour session lives in the same cookie jar the
    input method reads, so opening the app once restores the manager dock; the
    fallback keeps typing possible in the meantime.

77. The autofill response now carries SaveInfo even when the vault has no
    matching entry. The fill request used to return before setting it, so a
    new password could only be saved through the keyboard sheet; the system
    save prompt now appears for a WebView app submit (Chrome keeps its own
    password manager and never asks the service).
78. When a screen names a web domain, the service only fills fields that
    belong to that page. A browser toolbar (address bar, back, reload) is
    native, has no web domain, and was treated as a form field; one dataset
    put the password in the address bar. The page fields inherit the domain
    and are the only ones offered or saved.

79. Home's clothesline now carries icons. It reads the clothesline rows from
    the settings table, then fetches each app's picture from `/apps/icon`, the
    same choice `main_icon_maker` makes for the start menu and taskbar (the
    person's main image, else the active icon set's glyph), cached for a minute
    so the ten-second poll does not refetch. The JawnOS row's `/apps/start`
    icons use the same route: it serves a PNG because Android's BitmapFactory
    cannot decode the icon sets' SVG, and the row previously mixed hand-drawn
    PNGs with a logo placeholder wherever an SVG failed. An app with no icon
    at all still falls back to the initial-in-a-colour-circle tile.

80. The launcher's pictures are upright. A camera photo keeps its pixels in
    the frame they were stored in and carries an EXIF orientation tag instead;
    BitmapFactory ignores that tag, so a portrait picture sat on its side in
    the clothesline. `fetchJawnIcon` now reads the answer into memory, applies
    the tag's rotation (and any mirror) with `android.media.ExifInterface` and
    a Matrix, and decodes at a quarter size first - a 46dp tile cannot show
    more than a couple of hundred pixels. The single-app view also keeps the
    window's own title bar now: it is the app's icon and name, the thing that
    tells one custom app from another. Its window buttons are left out -
    closing the only window would strand the view on the desktop it hides, and
    Android's Back is the way out.
