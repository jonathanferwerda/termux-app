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
