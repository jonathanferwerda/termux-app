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
