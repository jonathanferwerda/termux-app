  `reject` and manual-decline paths never post. Build 1301.

## Deltas round X+2 (who carries the call, and the screen at the ear)

- **Hold follows the screened call, not the audio route.**
  `JawnInCallService.audioState` now reads the call's own `Trip`: Hold is
  offered exactly when the reception flow answered the call (`trip.ivr`) and
  the gateway is still carrying it (`trip.gateway`). The route was never the
  right question - a screened call can read as the phone's route while the
  gateway holds the line, and an ordinary headset reads exactly like the
  gateway. `onGateway()` takes the same two facts. The note line says which of
  the three cases turned it off: no screened record, a call the house is not
  screening, or a screened call that is no longer on the gateway.
- **"Connect phone" is offered only while the gateway carries the call.**
  `Audio.connectOffered` is the same `trip.ivr && trip.gateway` test, and
  `connectPhone` now says "the call is already on this phone" instead of moving
  audio that is already home. On the in-call screen the button is hidden on a
  call the house never screened, and dimmed with `The call is already on this
  phone.` in the note line once the call is home. The Bluetooth control is the
  reverse move: taking the call home clears the gateway flag, stops hold music
  and cancels the gateway; handing it to the adapter the house named sets the
  flag again, so Hold and "Connect phone" come back. Another headset is not the
  gateway and does not set it.
- **The display goes dark at the ear.** `JawnInCallActivity` reads
  `Sensor.TYPE_PROXIMITY` (SENSOR_DELAY_UI) only while a call is up
  (`STATE_ACTIVE`/`DIALING`/`CONNECTING`; not while ringing), and holds a
  `PROXIMITY_SCREEN_OFF_WAKE_LOCK` plus a `PARTIAL_WAKE_LOCK` while the sensor
  reads near - the stock dialer pair, so the display sleeps against the face
  and the process still runs the 400 ms tick. `FLAG_KEEP_SCREEN_ON` is dropped
  for that window. Away from the face, and on call end or `onDestroy`, both
  locks are released, the sensor is unregistered and the flag comes back.
  `WAKE_LOCK` was already in the manifest; no permission was added. Build 1302.

## Deltas round X+3 (voicemail from the call screen)

- **The in-call screen can send a screened caller to the house's voicemail.**
  A `Voicemail` cell joins the call-up grid, under WebRTC/Hold, and shares
  Hold's shape: it posts `POST /manager/sco/voicemail` through
  `JawnPhoneBridge.scoVoicemail` (form-urlencoded `text`, the president cookie,
  the same 2 s connect / 6 s read timeouts as `scoHold`) from a worker thread,
  and it is enabled exactly when `Audio.gatewayCarried` - the same
  `trip.ivr && trip.gateway` test Hold uses. The gateway plays the greeting and
  records the message; the app never hangs up, moves no audio and leaves the
  gateway flag alone, so the call stays up as it was. A press on a call that is
  not on the gateway says why in the note line instead of posting. The reply
  paints the note line: `the caller is on the house voicemail - recording now`
  on `{ok:1}`, and `Voicemail: <the gateway's error>` on `{ok:0}` (no call up,
  already recording) - shown, not retried. A successful send also clears the
  screen's `holdOn`, because the gateway stops its music before the greeting.
  Build 1303.

## Deltas round X+4 (voicemail from the screening panel, and the toggle back)

- **The screening panel offers "Send to voicemail", not "Connect me".**
  `JawnScreenFace.Panel` no longer opens `/phone?connect=me`: the phone
  carrying a screened call must not connect the caller to itself. The
  full-width button now posts the same `POST /manager/sco/voicemail` the
  in-call screen's Voicemail control uses (`JawnPhoneBridge.scoVoicemail`,
  worker thread), and it follows the same rule - only a screened,
  gateway-carried call has one, computed from `JawnInCallService.audioState`
  on every repaint. The reply is painted in the panel's own note line
  (`the caller is on the house voicemail - recording now`, or the gateway's
  error) and outranks the transcript hint until the face goes; a successful
  send clears `holdOn` because the greeting replaces the music. The call is
  never hung up, and **Connect phone** and **Disconnect** are unchanged.
- **Connect phone is a toggle, so the in-call screen is never a dead end.**
  `JawnInCallService.connectPhone` still takes a gateway-carried call home
  (`JawnIvrAudio.restore` + `restoreAudio`, flags cleared, gateway cancelled,
  hold music off) and now hides the screening face with it. Called on a call
  that is already home, the same method runs `sendToGateway`: it re-applies
  the answer-time route (`routeCallToGateway`, so the named Hands-Free
  adapter, Telecom's Bluetooth route, the SCO link and its retries), sets the
  trip's `gateway` flag again with `setGatewayCarried(true)`, keeps hold music
  off, and raises `JawnScreenFace` again - Hold and Voicemail come back with
  it. With no Bluetooth device the call stays put and the note line says why.
  `Audio.connectWhy` now carries the state sentence in both directions, so
  the screen's one note line reads "The house gateway is carrying this call -
  Connect phone brings it home." or "The call is on this phone - Send to
  gateway hands it back to the house."; the control reads **Connect phone** on
  the gateway and **Send to gateway** once home, and it is never dimmed. Hold
  and Voicemail stay lit while the gateway carries and dim (with that line as
  the reason) only while the call is home. Build 1304.

## Deltas round X+5 (the screen tells the truth about who has the call)

- **Conference follows the call, not the route.** `Audio.conference` is read
  from Telecom's `Call.Details` on every repaint: merge or swap when the
  platform names the capability, a non-empty `getConferenceableCalls()` when it
  names a partner, and `CAPABILITY_SUPPORT_HOLD` on a call that is up (active,
  holding, connecting or dialing). Nothing in it looks at the gateway,
  `trip.gateway` or the audio route, so a call on the phone - the case where a
  conference usually starts - offers it. `Audio.swap` is carried alongside
  `merge`, and `joinConference` swaps only when swap is the capability it has;
  otherwise a call with no partner says `no second call to merge yet - answer
  or place one first` instead of a dead or wrongly-dispatched button.
- **Bluetooth is a route picker and never claims the house.** `toggleBluetooth`
  no longer calls `setGatewayCarried(true)`; a Bluetooth route is indistinguishable
  from any other headset, so the control moves the audio, then reads the route
  back after 600 ms and paints `audio on <name>`, `Bluetooth: Telecom would not
  move the audio` (or `... (the house gateway is not connected)`). Moving off
  Bluetooth still brings the call home, clears the trip's gateway flag, stops
  hold music and cancels the gateway. Only **Send to gateway** establishes
  carriage.
- **Send to gateway proves the move before it lights Hold.** `sendToGateway`
  applies `routeCallToGateway()`, polls `CallAudioState` for up to 4 s until
  Telecom's route is Bluetooth on the exact adapter the house named (address
  from `JawnIvrAudio.gateway`, falling back to `AudioManager`'s communication
  device when Telecom names none), then probes the house with the new
  `JawnPhoneBridge.scoState` (`GET /manager/sco/status`, reading `state`/`flow`;
  carrying is `state == in_call`). Only `answered && carrying` sets the trip
  flag and raises the screening face. A gateway that does not answer, or answers
  with any other state, leaves the flag false, says
  `the gateway did not answer - the call stays on this phone` (or the state it
  did report) and brings the audio home instead of leaving the caller on a
  silent link.
- **Voicemail's reply is surfaced verbatim, including how the request failed.**
  `JawnPhoneBridge.scoVoicemail` returns a typed `ScoReply` (`body` + `failure`)
  instead of a bare string: a non-200 body is read too, because the gateway can
  put its refusal there. `JawnInCallService.voicemailResult` paints
  `Voicemail: <the gateway's error>` for `{ok:0,error}`, the success sentence
  for `{ok:1}`, `the gateway's reply could not be read as JSON (<why>)` for a
  body that is not JSON, `the house refused it without saying why (<status>)`
  for a JSON answer that names no error, and `the house did not answer
  (timeout)` / `the house answered <code>` when the request itself failed. Both
  the in-call screen and the screening panel use it. `Audio.voicemailOffered` is
  exactly `screened && trip.gateway` - the same rule as Hold - and enables the
  button there and on the panel. Build 1305.

### Round X+5, second pass (same three rules, two sharpenings)

- **Conference reads Telecom's capability report for the call.** Android keeps
  it on `Call.Details` (`CAPABILITY_MERGE_CONFERENCE` / `..._SWAP_CONFERENCE`;
  `Call` has no `can()` on this SDK), so `Audio.merge`/`swap` come from
  `details.can(...)` - the report `Call.can()` is named for. It is taken from
  one `current` snapshot and never consults the audio route or `trip.gateway`,
  so a call on the phone offers conference exactly when the platform allows it.
- **The voicemail note names the half that failed.** An unreadable body says
  `could not be read as JSON`, a JSON refusal with no `error` says so, and both
  carry the request's own status (`the house answered <code>`) when there was
  one. A `{ok:0,error}` still surfaces the gateway's words unchanged. Nothing
  is retried. Build 1307.

- **The tailnet control URL is an admin field, not a build constant.** The
  dashboard's Tailnet card stores `control_url` in the `jawn_tailnet` prefs;
  the service refuses to start without an `https://` URL and reports
  `needs_server`. The adb provisioning broadcast also accepts `control_url`.
  Build 1309.

- **The Gate fallback re-arms after a real manager load.** `JawnWebViewActivity`
  kept `gateFallback` set for the life of the activity after the first ticket
  handover, so the next lapsed session (the cookieless first request a woken
  WebView sends) went straight to the splash, with Try again as the only way
  out. A manager page that is not the Gate now clears the flag, so every lapse
  gets its own ticket handover and the splash stays the escape hatch for a
  ticket that can no longer sign in. Build 1310.

## Deltas round X+6 (the house's Silence is king)

- **One native authority, every audio path secondary.** `JawnSilence` keeps the
  state and the pre-silence snapshot in SharedPreferences. While it is on, every
  audio stream except `STREAM_VOICE_CALL` is at zero, the ringer is silent, and
  the interruption filter is `none` when the app holds DND access. The call
  stream is deliberately untouched: it is the Hands-Free link the receptionist's
  IVR travels on. `JawnApplication` attaches and re-applies at every process
  start, so a restart or an APK update cannot quietly undo it.
- **The page pushes the state, and the last state survives a closed page.**
  `JawnPhoneBridge.setFacts` reads `silence` (top level or under `ivr`) and
  applies it; `window.JawnPhone.setSilence` is the immediate path the manager's
  `silence.js` uses on a change, so a phone does not wait for the five-minute
  sync. The facts now carry `routes` as well as `silence`, and a change refreshes
  the phone facts at once.
- **No page needs to be open, in either direction.** The manager's alarm pass
  reconciles the house against the open silent appointment and calls the device
  bridge's `silence.set` (`DeviceService`). That method is the off switch as much
  as the on switch: while no appointment owns the house the pass keeps telling
  the phone off (throttled to once a minute), so a missed push or a cold start
  cannot leave the phone applying a stale silence snapshot forever. Build 1313.
- **Alarm, timer, media, speech and notification are gated.**
  `JawnClockSound.play` returns before creating a player, never raises the alarm
  stream, and re-asserts silence after restoring a stream;
  `DeviceService.media` and `speech` answer `{silent:true}` without starting a
  player or the TTS engine; `notificationPost`, `JawnNotifyReceiver`, and the
  alarm notifier use the quiet channel/path and no vibration. An alarm still
  draws its face and card; it just cannot make a sound.
- **The manager's volume directive is not above silence.** `DeviceService.volume`
  ignores a write to any stream but the call stream while the house is quiet.
  `JawnSilence.enter` stops the app's own clip and speech players, and a stopped
  alarm cannot restore a raised stream past the re-assert.
- **Calls route to the receptionist, whoever is calling.**
  `JawnPhoneBridge.ivrFlowFor` answers `ivr` before numbers or relationships, and
  `ivrEnabled` is true while silence is on, so `JawnInCallService` screens the
  call and routes it to the gateway. A gateway that is not there still leaves the
  call silent rather than ringing through. Build 1311.

## Deltas round X+7 (the alarm face is the overlay's, and a skipped day stays skipped)

- **The alarm face no longer depends on USE_FULL_SCREEN_INTENT.**
  `JawnClockNotifier` uses `Settings.canDrawOverlays` as the durable grant:
  Android 14+ resets the full-screen-intent special access on every APK update
  for a sideloaded build (the "alarms over lock screen" permission that had to
  be switched on again each time), while the overlay app-op persists. The
  overlay is also the background-activity-start exemption that lets the face
  open while the screen is on and unlocked, which a full-screen intent does not
  do. When the overlay is missing the ring notification offers it; the
  full-screen intent stays a logged bonus.
- **The face is asked for in every screen state.** `showAlarmFace` replaces
  `startOverLockScreen` and starts `JawnClockAlarmActivity` without the old
  "locked or asleep only" condition, so an alarm during ordinary use comes to
  the front instead of waiting in the notification drawer.
- **A queued wake is checked against the schedule before it rings.**
  `JawnClockReceiver.staleWake` compares the exact-alarm wake's `at`/`uuid`
  with `clock_alarm.json`, which jawn rewrites whenever the next alarm moves.
  A Skip, Dismiss, edit or another machine's change leaves the armed wake in
  AlarmManager; the stale one is now ignored, so a skipped day cannot still
  sound. The half-hour notice gets the same check.
- **Skip requests are queued.** `skipToday` writes one `clock_skip.d/<id>.json`
  per tap (renamed from `.tmp`) as well as the legacy `clock_skip.json`, so two
  Skips a moment apart cannot overwrite each other.
- **Exact alarms degrade, never vanish.** `JawnClockAlarm.setWake` checks
  `canScheduleExactAlarms()` on Android 12+ and falls back to
  `setAndAllowWhileIdle` (late but doze-surviving) instead of failing to arm.
  The permissions screen names overlay as the alarm grant, adds the
  "Alarms & reminders" page, and marks the full-screen intent optional.
- **One permission prompt per update.** `JawnDashboardActivity` compares the
  installed versionCode with `audited_version_code`; if the overlay or
  notifications were lost in an update it opens the grant screen once for that
  version. Build 1314.

## Deltas round X+8 (the alarm stays until it is answered)

- **A ring is answered, or stops after five minutes.** `clock_alarm_ring_length`
  is 300000 in jawn, so the alarm worker keeps the ring and its card that long
  and then clears both; the phone honours the guard it is sent. Dismiss or
  Snooze still ends it at once, and `JawnClockSound` records `ringing` for the
  face and closes that face when the local guard runs out.
- **Home cannot hide the ringing face.** `JawnClockAlarmActivity.reassertFace`
  asks for the screen back from `onUserLeaveHint` and `onStop` while the sound
  is still ringing and the display is interactive. The re-show carries the full
  ring payload and a `resume=1` extra, so `onNewIntent` keeps the math problem
  and an answer already given; a rebuilt face still knows its alarm.
- **Volume down is a snooze.** The activity consumes `KEYCODE_VOLUME_DOWN` and
  calls the same `snooze()` the tap does, including the math/NFC gate, so the
  key cannot walk around a gate.
- **The sound stops when the house says so, even if the broadcast was lost.**
  While an alarm plays, `JawnClockSound` polls `~/.president/clock_rings.json`
  every two seconds and stops when this alarm is marked stopped (the record
  must carry `stopped >= started`; an absent record is not a stop). This is the
  safety net for a frozen or missed `JAWN_CLOCK_DISMISSED`.
- **A dismiss cannot re-arm the moment that just ended.** `ACTION_DISMISSED`
  reads `clock_alarm.json` and only re-arms a schedule more than a minute in
  the future; otherwise it cancels the pending wake and lets `ACTION_SYNC`
  bring the real next alarm.

## Deltas round X+9 (the ring keeps its sound, and an alarm buzzes)

- **The ring no longer silences itself.** `JawnClockSound.play` started the
  ring-record watcher before it created the main-thread `Handler` the watcher
  posts through, so every real ring (loop + uuid) threw a
  `NullPointerException`, was caught as a failed play, and called `stop()`:
  the face showed and the alarm was mute. The handler now exists before the
  watcher, the ramp or the guard starts, and `startWatch` makes one if it is
  ever called without it.
- **An alarm buzzes while it rings.** `startVibration` runs a repeating
  `USAGE_ALARM` waveform until `stop()`. A silent ringer suppresses the
  notification channel's vibration, and a routed-away audio stream can hide
  the sound, so the haptic is the alarm's own backstop. It is cancelled with
  the sound: Dismiss, Snooze, the guard, or the house's ring record.

## Deltas round X+10 (the phone's own low-latency audio path)

- **Studio can open the phone's native stream, and the page is told what it
  was granted.** `window.JawnAudioOboe` (`JawnAudioOboe.java`, registered in
  `JawnWebViewActivity`) is a thin JNI bridge to
  `app/src/main/cpp/JawnAudioOboe.cpp`. The native half opens an Oboe duplex
  at 48 kHz with `PerformanceMode::LowLatency`, an `Unprocessed` input preset
  and `Exclusive` sharing falling back to `Shared` per stream, then reports
  `getSharingMode()`, `getPerformanceMode()`, the sample rate, frames per
  burst, buffer size in frames, xrun count, the granted input/output channel
  counts and a running frame count as JSON. Oboe no longer exposes
  `Direction::FullDuplex`, so the duplex is its own recipe, followed here: an
  input stream read from the output stream's data callback, the input drained
  once at start, and a fixed input buffer allocated before the stream runs -
  no allocation or lock in the callback. The monitoring path applies the
  page's gain and nothing else; there is no audio policy in Java.
- **The microphone grant is the only permission step.** `start` asks for
  `RECORD_AUDIO` once through the activity when it is missing and answers
  `waiting:true`; the request resumes when the prompt is answered, and the
  page reads the outcome from `stats()`. A navigation or `onDestroy` stops the
  stream, so no page inherits a live microphone. `usb.host` is declared
  optional, because a class-compliant interface arrives through Android's
  audio HAL; the app never claims the device itself, so there is no USB
  permission prompt.
- **Oboe 1.11.0 arrives through prefab.** `com.google.oboe:oboe` in
  `app/build.gradle`, `prefab true`, `externalNativeBuild` at
  `src/main/cpp/CMakeLists.txt` with CMake 3.22.1, and the existing
  arm64-v8a-only `abiFilters` unchanged. The slice is monitoring and reporting
  only: no take is written to disk yet, and audio focus is not requested.
  Build 1333.
- **The monitor follows the media stream.** `window.JawnAudioOboe.volume('70')`
  reads or sets `STREAM_MUSIC`, and a 250 ms watch copies that level into the
  native stream. An exclusive (MMAP) output bypasses the system mixer, so the
  callback multiplies by the level itself; a shared stream already gets it from
  the mixer, and `stats()` says which path with `systemVolume` and
  `systemVolumeApplied`. The page's monitoring gain stays separate. Build 1335.

## Deltas round X+11 (each app's notice stands on its own)

- **A notice's app names its channel, group and icon.** `JawnNotify.java` maps
  the `app` jawn already sends (`mailbox`, `clock`, `market`, ...) to a channel
  named for the app ("Mail"), a group key (`jawn:mailbox`) and one of the new
  monochrome `ic_notify_*` drawables (`ic_notify_mail`, `ic_notify_clock`, ...),
  with `ic_new_session` as the house fallback. `DeviceService.notificationPost`
  and the `JawnNotifyReceiver` fallback both use it, so mail no longer shares
  the automatic JawnOS bundle and no longer wears the house mark in the status
  bar. Android still attributes the posting package, so the header and badge
  remain JawnOS.
- **A second waiting notice gets an app-named header.** `JawnNotify` posts a
  group summary titled with the app ("Mail" with "New notifications") while two or more of its
  notices are waiting; with one it removes the summary so the notice shows on
  its own. A dismissed notice carries a delete intent that prunes a summary
  left without children, and `notification.remove` prunes too. The per-app
  channels are new ids, so a person's existing `jawn_notifications` sound and
  importance apply to app-less notices only: the app channels start at their
  defaults and are theirs to tune.
- **Home tiles wear the count.** `JawnHomeActivity` reads the live notices per
  app from the house's own `notifications` table (read-only, the same rows the
  taskbar clears) and badges the JawnOS row. An app with notices that the start
  menu does not list gets its own tile, so an appointment app's start/stop
  stands with its own count, and the tile wears the newest notice's picture
  when it has one (read straight from the house's public tree). A shared stock
  picture or a sealed photo falls back to a distinct colour and initials, so
  every appointment app still has its own tile.
- **A sealed photo reaches the notification, not the disk.** `Notify.pm`
  decrypts the app's sealed `main_image` in memory (openssl with the passphrase
  in the environment, then magick through a pipe) and carries the JPEG to the
  app as `large_icon_b64`; `DeviceService.notificationPost` decodes it for the
  notification's large icon. The `.enc` stays sealed on disk: no `.thumbs`
  entry and no temporary output are written. Build 1349.

## Deltas round X+12 (the ranked IVR list)

- **A screened call is answered on the first gateway that is ready.**
  `JawnPhoneBridge.Gateway`, `ivrGateways()` and `ivrCandidates()` read the
  house's `phone.ivr_machines` list in its saved order; an absent list keeps the
  older single `ivr_machine`/`ivr_bluetooth` pair. `JawnInCallService.screenWhenGatewayReady`
  tries each enabled candidate in turn: it must be online and advertising the
  crew's `ivr` ability, be paired with this phone with its Hands-Free link open,
  and answer the status probe. The first that does is recorded on the trip
  (`gatewayMachine`/`gatewayBluetooth`), named the caller's flow, answered and
  routed. If none is ready the call rings through and the normal screen shows -
  it is never answered into silence. An older house that names no machine keeps
  its old behaviour: it is answered on its Bluetooth adapter alone, with no live
  probe, so a gateway the old code never armed cannot start ringing calls
  through. Build 1351.
- **Every gateway request names the machine.** `scoFlow`, `scoStatus`,
  `scoState`, `scoHold`, `scoVoicemail`, `scoRtc` and `cancelGateway` carry the
  chosen signatorial, so the screening face, hold, the WebRTC bridge, voicemail
  and the hand-back ask the machine that answered rather than the house's older
  default. The call record carries `gateway`, and the page posts it to
  `/manager/phone/call`, so the transcript is filed from that machine's live
  status; a local `.jsonl` is read only when the gateway is this machine.

## Deltas round X+13 (silence rings a call through without a sound)

- **A screened call no gateway can take still rings through silently.**
  `JawnSilence.quietRing(Context)` re-applies the live silence under the same
  lock as `set()`/`reassert()` - every stream but the call at zero, the ringer
  silent, and the interruption filter `none` when DND access is held, which is
  also what stops the call's vibration - at the moment the call is left
  ringing, so a ringer or a volume restored elsewhere in the meantime cannot
  make it audible or shake the phone. It acts only while silence is on, never
  turns it on, never touches `STREAM_VOICE_CALL`, and returns whether it acted.
  `JawnInCallService.screenWhenGatewayReady` calls it in the no-candidate
  branch, before `showScreen()`, and logs
  `ivr: no gateway can answer; ringing through silently` only when it acted:
  with silence off the ring path is unchanged. Build 1352.

## Deltas round X+14 (the Studio take's Rust Oboe engine)

- **The Rust engine reaches Java through a shim, not JNI symbols.**
  `engine/src/oboe_device.rs` exports a plain C ABI
  (`jawn_audio_start/stop/status/free`), so `app/src/main/java/com/jawnos/app/JawnAudioEngine.java`
  cannot bind to it with `native` methods. The new
  `app/src/main/cpp/JawnAudioEngineJni.cpp` is compiled into the existing
  `libjawnaudio.so` (the library `JawnAudioOboe` already loads), `dlopen()`s
  `libjawn_audio.so`, resolves the four functions with `dlsym`, and exports
  `Java_com_jawnos_app_JawnAudioEngine_*`. `JawnAudioEngine` therefore loads
  `jawnaudio` (the shim), never `jawn_audio`, and a build missing either
  library answers `{"ok":0,"error":...}` instead of throwing. The engine ABI
  and its default (no-C++) feature set are untouched.
- **A take survives the page.** `app/src/main/java/com/jawnos/app/JawnAudioService.java`
  is the process anchor: a foreground service typed
  `microphone|connectedDevice` that starts the Rust take, holds a
  `PARTIAL_WAKE_LOCK` (released in `onDestroy`) and closes the WAV by stopping
  the engine, which joins its worker. `RECORD_AUDIO` is checked before the
  microphone type is claimed; without it the service refuses with a sentence
  and never goes foreground. Capture alone needs no audio focus, so there is
  no `AudioFocusRequest`; the monitoring half will add one. The manifest entry
  is `.app.JawnAudioService`, `exported=false`, `stopWithTask=false`.
- **The Rust library is staged, never built by Gradle.**
  `perl scripts/build_engine.pl --android --out-dir .../app/src/main/jniLibs`
  writes `app/src/main/jniLibs/arm64-v8a/libjawn_audio.so` (gitignored);
  Gradle's default `jniLibs` source set packages it beside `libjawnaudio.so`.
  `app/build.gradle` keeps `useLegacyPackaging true` and gains only a comment:
  no cargo task, no linked dependency on the Rust library. Build 1353.

## Deltas round X+15 (the native take's page bridge)

- **The page can start, watch and stop a native take.**
  `app/src/main/java/com/jawnos/app/JawnAudioEngineBridge.java` is
  `window.JawnAudioEngine`: `load()` reports whether the engine is in the
  build plus its backends JSON and best-effort writes that JSON to
  `/sdcard/Download/jawnos-native-backends.json`; `start(deviceId, seconds,
  out)` parses the page's strings defensively (falling back to the HAL's input
  and 600 s) and hands them to `JawnAudioService`; `stop()` joins the engine,
  returns its final report, then tears the service down; `status()`,
  `devices()` (every input, USB flagged) and `backends()` answer the engine's
  own JSON. A missing library or a bad argument is `{"ok":0,"error":...}`,
  never an exception in the page.
- **The backends report is an optional JNI symbol.**
  `JawnAudioEngineJni.cpp` adds `jawn_audio_backends` to its `dlopen`ed
  engine, outside the required-symbol check: an older `libjawn_audio.so`
  still answers `start`/`stop`/`status`, and `nativeBackends` says
  `this engine build has no backends report` instead of failing to load.
  `JawnAudioEngine.backends()` follows the same fail-closed rule as the rest.
- **A take leaves its report beside the WAV.**
  `JawnAudioService.endTake()` writes the final JSON to `<take>.json` with a
  `FileWriter` before it clears the path, so the build gate can pull the
  report over adb without the page. The manifest entry and `exported=false`
  are unchanged.
- **The control is injected, not baked into Studio.**
  `JawnWebViewActivity` registers the bridge beside `JawnAudioOboe` and, from
  the existing `onPageFinished`/`evaluateJavascript` path (no second
  `WebViewClient`), puts a small bottom-right panel on `/manager/studio`: an
  input picker (USB marked), a seconds field, Start/Stop and a status line.
  It is idempotent, guarded by pathname and bridge presence, labels itself
  through `window.jawnosT` when available, and prints the backends JSON and
  the final report (`sharing`, `performance`, `rate`, `frames`, `buffer`,
  `xruns`, `frames_captured`, `peak`, `take`). It polls `status()` every
  second and calls `stop()` exactly once when the take ends or fails.
- **Only null means "no WAV".** `JawnAudioEngine.start` now passes `out`
  through unchanged; the engine treats a null pointer as "no WAV", so the
  previous empty-string substitution was a path, not silence. Build 1354.

## Deltas round X+16 (the native take reaches the real Studio window)

- **The engine's C++ runtime is promoted before it is opened.**
  `JawnAudioEngineJni.cpp` `engine()` preloads `libc++_shared.so` with
  `RTLD_NOW | RTLD_GLOBAL` (logging, not failing, if it is absent) before
  `dlopen("libjawn_audio.so")`. The Rust cdylib statically links Oboe's C++
  and leaves `__cxa_pure_virtual`, `_Znwm`, `__cxa_throw` and the rest
  undefined; `System.loadLibrary` had loaded the STL outside the global lookup
  scope, so the engine's `dlopen` failed with
  `cannot locate symbol "__cxa_pure_virtual"`. The optional
  `jawn_audio_backends` handling is unchanged.
- **The panel follows the Studio window, not a URL.** The manager serves
  `/manager/studio` as the window JSON (`{"html":...}`), so the panel now
  returns on that route and on `/manager/studio/…`, and instead runs on the
  manager shell. A `MutationObserver` watches for `#studio` (or
  `.window[data-app="studio"]`) and shows the fixed panel only while that
  window is visible, hiding it when it goes away. The idempotent guard and the
  take timer are unchanged; a take keeps running while the panel is hidden.
- **The gate always gets a backends file.** `JawnAudioEngineBridge.load()`
  writes the error JSON to `/sdcard/Download/jawnos-native-backends.json` when
  even the shim is missing, not only when the engine loads. Build 1355.

## Deltas round X+17 (the engine carries its own C++ runtime)

- **The Android engine links libc++_shared.** The 1355 global preload was not
  enough: Android does not promote an already-loaded STL into the global
  lookup scope, so `dlopen("libjawn_audio.so")` still failed on
  `__cxa_pure_virtual`. `scripts/build_engine.pl --android` in the jawnos tree
  now sets `CARGO_ENCODED_RUSTFLAGS` with `-Wl,--no-as-needed -lc++_shared`, so
  the rebuilt cdylib lists `libc++_shared.so` in `NEEDED` and resolves its C++
  symbols from its own dependency graph. `t/428-oboe-backend.t` pins both
  flags. The staged arm64 library keeps `jawn_audio_backends`, and the shim's
  global preload stays as a harmless best-effort guard. Build 1356.

## Deltas round X+18 (the take's Channel plan and the fixture)

- **A take can run one Channel.** `JawnAudioEngine.startPlan(deviceId,
  seconds, out, plan)` calls the engine's additive `jawn_audio_start_plan`;
  `fixturePlan()` returns the engine's own `jawn_audio_fixture_plan` bytes.
  A null/empty plan is the unchanged raw path, and the JNI shim resolves both
  symbols as optional so an older engine still starts a raw take.
  `JawnAudioService` carries `EXTRA_PLAN`; the four-argument `start` delegates
  with an empty plan.
- **The native panel can choose the strip.** *Take through* offers *Raw (no
  strip)* and *Tone + delay fixture*; the fixture option is disabled with a
  sentence when the engine does not carry the plan. The status block lists the
  report's `plan`, so the sidecar says `tone+delay` when the fixture ran.
  Build 1358.

## Deltas round X+19 (the round-trip probe reaches the panel)

- **The duplex probe is exported and reaches the page.**
  `JawnAudioEngine.roundTrip(deviceIn, deviceOut)` calls the engine's additive
  `jawn_audio_roundtrip`; the JNI shim resolves it as optional like
  `jawn_audio_backends`, so an older engine answers a sentence instead of
  failing to load. Device ids below zero mean the HAL's default.
- **The probe runs off the WebView's thread.**
  `JawnAudioEngineBridge.roundtrip()` starts a daemon worker thread and returns
  the started sentence; `roundtripStatus()` is polled until the engine's report
  replaces it. The bridge also writes the result to
  `/sdcard/Download/jawnos-native-roundtrip-<stamp>.json` (best effort) and
  adds its `file` key to the returned JSON.
- **The panel has a Round trip section.** `outputs()` lists
  `AudioManager.GET_DEVICES_OUTPUTS` with USB outputs flagged, beside the
  existing `devices()` inputs. *Round trip input*, *Round trip output*,
  *Measure round trip* and a result line show `round_trip_ms` /
  `round_trip_frames`, both granted sharing/performance modes, buffers, xruns
  and both device ids, so the result names what produced it. Build 1359.

## Deltas round X+20 (the direct-USB P0 gate)

- **A Java-only probe asks whether the app can own the UM2.** `JawnUsbProbe`
  (`probe()` / `status()`) finds vendor `0x08bb` / product `0x2902` (falling
  back to any attached device with a `USB_CLASS_AUDIO` interface), asks for USB
  permission through a dynamic, `RECEIVER_NOT_EXPORTED` receiver and a
  package-scoped `FLAG_IMMUTABLE` `PendingIntent`, then opens the device. It
  force-claims each AudioControl and AudioStreaming interface
  (`claimInterface(iface, true)` - the `USBDEVFS_DISCONNECT_CLAIM` that must
  detach `snd-usb-audio` / the Android USB audio HAL), selects every
  isochronous alternate setting >= 1 with `setInterface`, and sends the UAC1
  48000 Hz `SET_CUR` / `GET_CUR` SAMPLING_FREQ_CONTROL to each isochronous
  endpoint, with the interface recipient as the fallback. Every claim is
  released and the connection closed in `finally`, so the tablet's own audio
  returns.
- **The result is a file the gate can read.** The probe writes
  `/sdcard/Download/jawnos-usb-probe-<yyyyMMdd-HHmmss>.json` with `ok`, `state`,
  `device`, `permission`, `interfaces`, `claims`, `alt_sets`, `set_cur`,
  `get_cur`, `released` and `errors`; `ok` is true only when a force-claim and
  a 48000 `GET_CUR` both succeeded. It is a claim/alt/control gate, not an
  audio path: `UsbRequest` cannot queue the UM2's isochronous transfers.
- **The bridge and the Studio panel carry it.**
  `JawnAudioEngineBridge.usbProbe()` / `usbProbeStatus()` back a USB probe
  section below Round trip in the injected panel; the button starts the probe
  and its result line polls while the permission dialog is up. `androidx.core`
  moves to 1.13.1 for
  `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`. Build 1361.

## Deltas round X+21 (the rate control at the 48 kHz alt)

- **The probe selects one alternate, not the last one enumerated.** The P0 run
  walked every AudioStreaming alt and left OUT alt 6 / IN alt 18 active; the
  UAC1 rate request was refused there (`SET_CUR -1`; OUT read 32000 Hz, IN read
  a shifted 12,288,000). `JawnUsbProbe` now picks the alt whose isochronous
  endpoint carries the 48 kHz / 16-bit / 2-channel packet - 192 bytes out, 196
  in on the UM2's full-speed 1 ms frame, i.e. alt 1 on both interfaces - calls
  `setInterface` once, and leaves it active. `selected` records each streaming
  interface's chosen alt and endpoint addresses.
- **Three ordered attempts, raw bytes kept.** At that alt the probe runs, per
  isochronous endpoint: the endpoint recipient (`0x22` SET_CUR / `0xA2`
  GET_CUR, `wIndex` = endpoint), then the interface recipient (`0x21`/`0xA1`,
  `wIndex` = interface id), then the alt-0 endpoint fallback (switching the
  interface back to alt 0 and restoring the chosen alt afterwards). Every
  attempt lands in `rate_attempts` with its alt, recipient, SET_CUR return,
  GET_CUR return and the raw 3 bytes as hex - `hz` alone was ambiguous.
  `ok` is true when a claim held and any GET_CUR decodes to 48000; the old
  `set_cur` / `get_cur` arrays carry the same rows. Build 1362.

## Deltas round X+22 (the USB take is anchored)

- **The take now has a foreground service and a wake lock.** A direct-USB take
  keeps a `UsbDeviceConnection` and hands its fd to the Rust engine, but the
  process could be backgrounded or the screen could sleep and end it.
  `JawnUsbAudioService` starts foreground with
  `microphone|connectedDevice` (falling back to `connectedDevice` alone when
  RECORD_AUDIO is missing, so a USB-only take still anchors), holds a
  `PARTIAL_WAKE_LOCK`, and offers the same Stop action as the HAL take. It
  never touches `JawnAudioEngine` itself.
- **A separate service, not a USB mode** in `JawnAudioService`: the HAL
  service's `onDestroy` stops the engine and owns the HAL take's fields, so
  merging the two would make every lifecycle branch mode-dependent. With two
  services, each take's teardown is isolated; `JawnAudioService.onDestroy`
  now skips its engine stop when the USB holder is live.
- **One holder, one connection.** `JawnUsbAudio.shared(Context)` is the
  process-wide singleton; both `JawnAudioEngineBridge` and the anchor service
  reach it, so the service can never open a second connection. The open path
  is unchanged: permission → open → force-claim → alt selection → `usbStart`;
  stop is still engine-first (`JawnAudioEngine.stop()` then release/close).
  A setup that fails before holding drops the anchor (re-checked briefly so
  it cannot race a take that starts in the same instant).
- **The panel summary** now also shows `underruns_out_start`, `monitor_ms` and
  `out_rate` from the engine's new report keys; the raw JSON is unchanged.

## Deltas round X+23 (the USB take is first, and its deadline finalizes)

- **The direct-USB take is the first thing in the panel.** The section order is
  now: *Studio audio* (main) → *USB take (direct)* → *Native take (Android
  audio)* → Round trip → USB probe → HAL status. The HAL block gains the hint
  "Uses Android's audio path, not the UM2 direct path." This is the fix for a
  session that started the HAL take three times because the USB Start sat below
  the fold.
- **The USB section names the device.** A line `id="jawn_usb_take_device"` and a
  small Refresh button show the attached USB audio device (vendor/product and
  whether USB permission is granted) or "no USB audio input is attached". It is
  fed by `JawnUsbAudio.describe()` / `JawnAudioEngineBridge.usbAudioDevice()`,
  which enumerate exactly as `findDevice()` does (an AudioStreaming interface
  with an isochronous IN endpoint) and open/claim nothing.
- **The HAL input list re-enumerates on appearance.** `syncPanel` detects the
  hidden→visible transition of the Studio window and refills the HAL input
  select (preserving the chosen id), so a device plugged after page load
  appears. The USB device line is refreshed at the same moment.
- **A take that reaches its `seconds` deadline now finalizes.** While a take is
  held, a worker (`jawn-usb-expiry`) polls the engine status every 500 ms. When
  the status is a final report (no `state`, or any state other than
  started/detecting/opening/requesting), it runs the same release/close/report
  path as a user stop, then drops the foreground anchor. A one-shot `finalized`
  flag claimed under the existing locks makes a Stop tap at the same instant
  return the first finalizer's report instead of finalizing twice. The
  engine-first order on a user stop is unchanged.

## Deltas round X+24 (USB monitor depth and the direct round trip)

- **The engine's two new entry points reach Java and the panel.**
  `JawnAudioEngine.usbDepth(...)` / `roundTripUsb(...)` wrap
  `jawn_audio_set_usb_depth` and `jawn_audio_roundtrip_usb`; the JNI shim
  resolves both with `dlsym` as optional, so an older engine still loads and
  answers a sentence. The JS bridge exposes `usbDepth(...)` for the panel.
- **The USB probe now measures the direct round trip.** After the existing
  `usb_iso` gate, `JawnUsbProbe` picks the first chosen isochronous OUT and IN
  endpoints (from the chosen alts, never a hardcoded number), calls
  `roundTripUsb(..., 1500)` on the open connection's fd, and stores the parsed
  JSON under `usb_roundtrip`. No OUT endpoint is
  `{"ok":false,"error":"no isochronous audio output on the chosen alt"}`. It
  needs a patch cable OUT→IN; failures stay in the report and never abort it.
- **"USB take (direct)" gets a monitor buffer.** A `Monitor buffer (ms)` select
  (`jawn_usb_take_buffer`, 8/4/2/1, default 8) sets the depth immediately before
  `usbTake`: `inPackets = outPackets = ms`, `outUrbs = 1`,
  `inUrbs = clamp(round(32/ms), 1, 8)` — so 8→4×8, 4→8×4, 2→8×2, 1→8×1. A
  refusal is printed in the result line and the take does not start. The take
  summary also shows `in_delivery_ms` beside `monitor_ms`.

## Deltas round X+25 (the monitor pacing fields)

- **The take summary shows the monitor's headroom.** `describeUsbTake` adds
  `fill_max` (`monitor_fill_max_ms`) and `fifo` (`high_water_ms`) beside
  `monitor_ms`/`in_delivery_ms`/`underruns_out_start`/`out_rate`; the raw JSON
  is unchanged. Engine v0.1.3846 carries the pacing fix; the APK stages that
  `.so`.

## Deltas round X+26 (OUT has a two-URB floor)

- **The monitor label is the true runway.** The engine clamps OUT URBs to 2, so
  the panel now sends `outUrbs=2`, `outPackets=max(1,round(ms/2))`: 8 ms →
  in 4×8 / out 2×4, 4 → 8×4 / 2×2, 2 → 8×2 / 2×1. The 1 ms option is gone
  (the floor is 2 ms); `monitor_ms` in the report is authoritative. `inPackets`
  stays `ms`, `inUrbs` stays `clamp(round(32/ms),1,8)`.
- **The summary shows the send side.** `sent` (`out_ms`) and `lag`
  (`out_lag_ms`) join `monitor_ms`/`in_delivery_ms`/`fill_max`/`fifo`/
  `underruns_out_start`/`out_rate`; raw JSON unchanged.

## Deltas round X+27 (the keyboard's own recogniser)

- **The input method is given `window.JawnSpeech`.** `JawnImeService` adds the
  same bridge the dock and the Guide have, at the `JawnIme` interface
  (`onCreateInputView`, before the page parses), and releases it in `onDestroy`
  beside the WebView. The manager already renders `data-transcribe-service` on
  the keyboard page, so the attribute that once resolved to no bridge now
  reaches the phone's own recogniser; the bridge's state and result callbacks
  feed the page's existing dispatcher.
- **`JawnSpeech` works from a Service.** It takes a `Context` instead of an
  `Activity`: work is posted through a main-thread
  `Handler(Looper.getMainLooper())` rather than `Activity.runOnUiThread`, and
  the recognizer is created with that Context. The activity path's permission
  ask is unchanged; a Service - which cannot raise the system dialog - reports
  `window.jawnSpeechState('error','permission')` at start so the page falls back
  to its recorder. `onPermissionResult` still answers the activity's ask.
  Build 1372.

## Deltas round X+28 (native TTS to a file)

- **The device bridge can synthesise speech to a WAV file.** `DeviceService`
  adds `speech.synth` beside `speech.speak` (`text`, an absolute `.wav`
  `path`, optional `language`/`rate`/`pitch`/`voice`): it shares the one
  TextToSpeech engine through `ensureTts`, calls `synthesizeToFile` under a
  unique utterance id and waits on an `UtteranceProgressListener` with a 15 s
  `CountDownLatch` bound - engine error, a stop and the timeout each throw,
  and a failed synthesis deletes its partial file. The path is accepted only
  inside `getFilesDir()`, canonicalised, so `/data/data/...` and
  `/data/user/0/...` agree; any other absolute path or a `..` traversal is
  refused and parent directories are created. Success answers
  `{ok:true, path, bytes}` for the caller to read and transcode. It is
  deliberately not gated on Silence: it writes bytes rather than making a
  sound, and the speech plays on the requesting device (the watch's homebase
  phone cannot run piper). A `synthLock` serialises concurrent syntheses,
  `ttsLock` is held only to mutate the engine and submit - never while
  waiting, so `silenceNow()` cannot block behind a synthesis - and
  `speech()` now takes the same lock around its mutations and `speak`, so
  speak and synth cannot race. Build 1375.
