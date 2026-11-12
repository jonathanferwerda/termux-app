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
