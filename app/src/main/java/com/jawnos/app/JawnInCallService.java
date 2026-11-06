package com.jawnos.app;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import android.telecom.VideoProfile;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The dialer role's call side: while JawnOS is the default phone app, every call
 * is bound here and JawnInCallActivity is the screen.
 *
 * This is also where a call is recorded. It used to be written by the page (the
 * Phone app listened to the bridge's state events and posted each change), which
 * only worked while a page was up - so a call taken with the app in the
 * background left no appointment at all. The service sees every call whatever
 * else is running, so it keeps one record per call and leaves it in the app's own
 * store when the call ends (JawnPhoneBridge.recordCall): the page files it when
 * it is next there, and is nudged at once when it is up.
 *
 * The reception flow lives here too. When the house's IVR is enabled, a gateway
 * machine is connected, and the caller's flow is "ivr" or "voicemail", the call
 * is answered without the in-call screen and its audio is handed to the
 * Bluetooth gateway (JawnIvrAudio), which plays the house's prompts and records
 * the caller's name. A watchdog hangs up if the gateway never does, so a broken
 * link cannot leave the line open.
 */
public class JawnInCallService extends InCallService {

    public static volatile JawnInCallService instance;
    public static volatile int state = Call.STATE_DISCONNECTED;
    public static volatile Call current;

    /**
     * How long a screened call may stay up with nobody ending it. The caller
     * says a name in a few seconds and a message takes longer, so the two flows
     * get different ceilings; either way the line never stays open on its own.
     */
    private static final long IVR_LIMIT_MS = 40_000;
    private static final long VOICEMAIL_LIMIT_MS = 120_000;
    /**
     * A screened call the house is holding music on. The caller has heard the
     * thanks and is waiting for somebody to pick up, so the plan's own ceiling
     * would hang up mid-song. The gateway stops the music when it is told to;
     * this is the backstop for a hold nobody ever answers.
     */
    private static final long HOLD_LIMIT_MS = 10 * 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** The retries that re-apply the gateway route, so a call that ends can cancel them. */
    private final Runnable routeLater = new Runnable() {
        @Override public void run() { routeOnce(); }
    };
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            Call call = current;
            if (call == null) return;
            try { call.disconnect(); } catch (Throwable ignored) { }
        }
    };

    /** One call's own record, kept across its state changes. */
    private static final class Trip {
        final String number;
        final String direction;
        final long started;
        boolean answered;
        /** The reception flow answered this call: it is recorded as screened. */
        boolean ivr;
        /** The gateway is carrying the audio right now; false once the person
         *  takes the call back on the phone. */
        boolean gateway;
        String flow = "";

        Trip(String number, String direction, long started) {
            this.number = number == null ? "" : number;
            this.direction = direction;
            this.started = started;
        }
    }

    private final Map<Call, Trip> trips = new HashMap<>();

    private final Call.Callback callback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int newState) {
            current = call;
            state = newState;
            if (newState == Call.STATE_RINGING) {
                String number = numberOf(call);
                JawnPhoneState.incoming(number);
                Trip trip = new Trip(number, "incoming", System.currentTimeMillis());
                trips.put(call, trip);
                // the house decides before the phone does: refuse, answer for the
                // receptionist, or ring through with the screen
                String flow = JawnPhoneBridge.ivrFlowFor(JawnInCallService.this, number);
                trip.flow = flow;
                if ("reject".equals(flow)) {
                    try { call.reject(false, null); } catch (Throwable ignored) { }
                    return;
                }
                if (isScreened(flow)) {
                    // Answer only once the gateway can actually carry the call:
                    // answering first and routing after leaves the caller in
                    // silence whenever the phone is out of the adapter's range.
                    // The check may bring a bonded link up, so it runs off the
                    // main thread; a call no longer ringing is left alone.
                    screenWhenGatewayReady(call, trip, flow);
                    return;
                }
                showScreen();
            }
            else if (newState == Call.STATE_DIALING) {
                if (trips.get(call) == null) {
                    trips.put(call, new Trip(numberOf(call), "outgoing", System.currentTimeMillis()));
                }
                JawnPhoneState.incoming(numberOf(call));
                showScreen();
            }
            else if (newState == Call.STATE_ACTIVE) {
                // reached ACTIVE: this is the state that means somebody answered,
                // whether the call came in or went out
                Trip trip = trips.get(call);
                if (trip != null) {
                    trip.answered = true;
                    // Android may reset the communication device as the call
                    // settles, so the route is set again here
                    if (trip.gateway) {
                        routeCallToGateway();
                        // the house is screening this call: show the person who
                        // it is, what is being heard, and the two ways out
                        JawnScreenFace.show(JawnInCallService.this, trip.number);
                    }
                }
            }
            else if (newState == Call.STATE_DISCONNECTED) {
                Trip trip = trips.remove(call);
                handler.removeCallbacks(watchdog);
                handler.removeCallbacks(routeLater);
                if (trip != null && trip.ivr) {
                    JawnIvrAudio.restore(JawnInCallService.this);
                    JawnPhoneBridge.cancelGateway(JawnInCallService.this);
                    JawnScreenFace.hide(JawnInCallService.this);
                }
                if (trip != null) writeTrip(trip);
                JawnPhoneState.incoming("");
                showScreen(); // the screen closes itself once no call is up
            }
        }
    };

    /**
     * Answer an IVR/voicemail call once the gateway adapter is bonded,
     * connected and carrying Hands-Free audio. If it is not, the call is left
     * to ring and the normal screen is shown, exactly as the "ring" flow does:
     * a screened call must never be answered into silence.
     */
    private void screenWhenGatewayReady(final Call call, final Trip trip, final String flow) {
        final String gateway = JawnPhoneBridge.ivrBluetoothName(this);
        new Thread(new Runnable() {
            @Override public void run() {
                final String problem = JawnIvrAudio.gatewayProblem(
                        JawnInCallService.this, gateway);
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (trips.get(call) != trip) return;
                        if (!problem.isEmpty()) {
                            JawnLog.append("ivr: not screening " + trip.number + ": " + problem);
                            showScreen();
                            return;
                        }
                        int now = Call.STATE_DISCONNECTED;
                        try { now = call.getState(); } catch (Throwable ignored) { }
                        if (now != Call.STATE_RINGING) return;
                        trip.ivr = true;
                        trip.gateway = true;
                        routeCallToGateway();
                        try { call.answer(VideoProfile.STATE_AUDIO_ONLY); } catch (Throwable ignored) { }
                        handler.removeCallbacks(watchdog);
                        long limit = "voicemail".equals(flow) ? VOICEMAIL_LIMIT_MS
                                : (JawnPhoneBridge.ivrHoldOn(JawnInCallService.this) ? HOLD_LIMIT_MS : IVR_LIMIT_MS);
                        handler.postDelayed(watchdog, limit);
                    }
                });
            }
        }, "jawn-ivr-gateway").start();
    }

    /**
     * Put the call itself on the gateway. As the dialer this app owns the call's
     * audio route through Telecom: setting only the AudioManager's communication
     * device is enough for the prompts to leave the phone, but the caller's
     * voice stays on the handset and never reaches the Hands-Free side. The
     * Telecom route is the one that moves both directions.
     */
    private void routeCallToGateway() {
        handler.removeCallbacks(routeLater);
        routeOnce();
        // Telecom accepts the route once the call is really up, and the radio
        // needs a moment after that: one attempt at the ring and one at ACTIVE
        // was not always enough for the caller's voice to come back
        handler.postDelayed(routeLater, 800);
        handler.postDelayed(routeLater, 2200);
    }

    private void routeOnce() {
        // A retry left over from a call that has ended must not touch the
        // phone's audio: the number is still "fresh", so the flow would still
        // read as screened and the next call would be pinned to a gateway that
        // hung up. Only a call that is really up may move the route.
        int now = state;
        if (current == null || (now != Call.STATE_RINGING && now != Call.STATE_DIALING
                && now != Call.STATE_ACTIVE && now != Call.STATE_HOLDING)) {
            return;
        }
        if (!isScreened(JawnPhoneBridge.ivrFlowFor(this, JawnPhoneState.freshNumber(120_000)))) {
            return;
        }
        String gatewayName = JawnPhoneBridge.ivrBluetoothName(this);
        try { setAudioRoute(CallAudioState.ROUTE_BLUETOOTH); } catch (Throwable ignored) { }
        // Telecom carries the call on one of the Bluetooth devices it knows, and
        // with a second headset connected it picks the one it made active last.
        // Naming the gateway here is what keeps that choice ours.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            BluetoothDevice gateway = JawnIvrAudio.gateway(this, gatewayName);
            if (gateway != null) {
                try { requestBluetoothAudio(gateway); } catch (Throwable ignored) { }
            }
        }
        JawnIvrAudio.routeToSco(this, gatewayName);
        JawnLog.append("ivr audio: telecom route " + getCallAudioState());
    }

    private static boolean isScreened(String flow) {
        return "ivr".equals(flow) || "voicemail".equals(flow);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(routeLater);
        // The service can go away mid-call (a crash or a system kill). Nothing
        // else then puts the phone's audio back, so it is done here and said out
        // loud; recoverIfStuck() covers the case where even this never ran.
        if (current != null && state != Call.STATE_DISCONNECTED) {
            JawnLog.append("ivr audio: restoring the route because the call service went away");
            JawnIvrAudio.restore(this);
            // the face has no call to follow once the service is gone
            JawnScreenFace.hide(this);
        }
        instance = null;
        current = null;
        state = Call.STATE_DISCONNECTED;
        super.onDestroy();
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        call.registerCallback(callback);
        callback.onStateChanged(call, call.getState());
    }

    @Override
    public void onCallRemoved(Call call) {
        // a call that goes away without ever saying DISCONNECTED still happened
        Trip trip = trips.remove(call);
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(routeLater);
        if (trip != null && trip.ivr) {
            JawnIvrAudio.restore(this);
            JawnPhoneBridge.cancelGateway(this);
            JawnScreenFace.hide(this);
        }
        if (trip != null) writeTrip(trip);
        call.unregisterCallback(callback);
        super.onCallRemoved(call);
    }

    /**
     * Leave the call where the page can file it. A call happens whether or not a
     * page is up, so the record goes into the app's own store (and into jawn's
     * log, so it is never lost altogether); when a page is up it is nudged at
     * once, and otherwise the next page load picks it up.
     */
    private void writeTrip(Trip trip) {
        try {
            long ended = System.currentTimeMillis();
            JawnPhoneBridge.recordCall(this, trip.number, trip.direction, trip.started, ended,
                    trip.answered, trip.ivr);
            JawnLog.append("call " + trip.direction + " " + trip.number + " "
                    + (trip.answered ? "answered" : "unanswered")
                    + (trip.ivr ? " (reception " + trip.flow + ")" : "")
                    + " " + ((ended - trip.started) / 1000) + "s");
            if (JawnPhoneState.poster != null) {
                JawnPhoneState.poster.post("window.__jawnPhoneCalls && window.__jawnPhoneCalls();");
            }
        } catch (Throwable ignored) { }
    }

    // ---- what the screen asks for ----

    public static void answer() {
        if (instance == null || current == null) return;
        try { current.answer(VideoProfile.STATE_AUDIO_ONLY); } catch (Throwable t) {}
    }

    public static void reject() {
        if (instance == null || current == null) return;
        try { current.reject(false, null); } catch (Throwable t) {}
    }

    public static void hangup() {
        if (instance == null || current == null) return;
        try { current.disconnect(); } catch (Throwable t) {}
    }

    /**
     * Take a screened call back on this phone.
     *
     * Two halves have to come back: AudioManager's communication device (the
     * gateway's SCO link) and Telecom's route, which routeCallToGateway pointed
     * at the same Hands-Free device - clearing only the first leaves the call
     * silent even though the gateway is gone. The watchdog is cancelled too: a
     * call the person has taken over must not be hung up because the reception
     * flow's own limit expired.
     */
    public static void restoreAudio() {
        JawnInCallService svc = instance;
        if (svc == null) return;
        Trip trip = svc.trips.get(current);
        if (trip != null) trip.gateway = false;
        svc.handler.removeCallbacks(svc.routeLater);
        svc.handler.removeCallbacks(svc.watchdog);
        try {
            AudioManager am = (AudioManager) svc.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.clearCommunicationDevice();
                }
                else {
                    am.setBluetoothScoOn(false);
                    am.stopBluetoothSco();
                }
                am.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Throwable ignored) { }
        try {
            // a wired headset is the phone's own audio too; the earpiece is the
            // fallback when there is none
            CallAudioState cas = svc.getCallAudioState();
            int mask = cas == null ? 0 : cas.getSupportedRouteMask();
            svc.setAudioRoute((mask & CallAudioState.ROUTE_WIRED_HEADSET) != 0
                    ? CallAudioState.ROUTE_WIRED_HEADSET : CallAudioState.ROUTE_EARPIECE);
        } catch (Throwable ignored) { }
        JawnLog.append("ivr audio: the call is back on the phone");
    }

    // ---- the in-call screen's controls ----

    /** The last thing a control said, for the screen's one note line. */
    public static volatile String controlNote = "";
    public static volatile long controlNoteAt = 0;
    /** The gateway's WebRTC bridge was asked for and has not been stopped. */
    public static volatile boolean rtcOn;

    private static void say(String text) {
        controlNote = text;
        controlNoteAt = System.currentTimeMillis();
        JawnLog.append("call: " + text);
    }

    /** What the in-call screen needs to know about this call's audio, in one look. */
    public static final class Audio {
        public int route = CallAudioState.ROUTE_EARPIECE;
        public int mask;
        public boolean speaker;
        public boolean muted;
        public boolean bluetooth;
        public boolean bluetoothOffered;
        public String bluetoothName = "";
        /** Telecom says this call may join (or swap with) a conference. */
        public boolean conference;
        /** ... and merge is the capability that is actually on offer. */
        public boolean merge;
        public String conferenceWhy = "";
    }

    /**
     * Read the call's audio state from Telecom, which is the authority while this
     * service holds the call, and from AudioManager only when there is no service
     * to ask. Both halves are needed: Telecom owns the route, AudioManager owns
     * the mute flag a plain phone call would use.
     */
    public static Audio audioState(Context context) {
        Audio out = new Audio();
        JawnInCallService svc = instance;
        CallAudioState cas = null;
        if (svc != null) {
            try { cas = svc.getCallAudioState(); } catch (Throwable ignored) { }
        }
        if (cas != null) {
            out.route = cas.getRoute();
            out.mask = cas.getSupportedRouteMask();
            out.muted = cas.isMuted();
            out.speaker = out.route == CallAudioState.ROUTE_SPEAKER;
            out.bluetooth = out.route == CallAudioState.ROUTE_BLUETOOTH;
            if (out.bluetooth) out.bluetoothName = deviceName(cas.getActiveBluetoothDevice());
        }
        else {
            AudioManager am = JawnIvrAudio.manager(context);
            if (am != null) {
                out.mask = CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER;
                try {
                    out.muted = am.isMicrophoneMute();
                    out.speaker = am.isSpeakerphoneOn();
                    out.bluetooth = am.isBluetoothScoOn();
                    if (out.speaker) out.route = CallAudioState.ROUTE_SPEAKER;
                    if (out.bluetooth) out.route = CallAudioState.ROUTE_BLUETOOTH;
                    if (am.isBluetoothScoAvailableOffCall()) {
                        out.mask |= CallAudioState.ROUTE_BLUETOOTH;
                    }
                    if (am.isWiredHeadsetOn()) out.mask |= CallAudioState.ROUTE_WIRED_HEADSET;
                } catch (Throwable ignored) { }
            }
        }
        if (out.bluetoothName.isEmpty()) {
            out.bluetoothName = JawnPhoneBridge.ivrBluetoothName(context);
        }
        out.bluetoothOffered = (out.mask & CallAudioState.ROUTE_BLUETOOTH) != 0
                && JawnIvrAudio.scoAvailable(context);

        // Conference is only offered when Telecom says it is possible. A merge
        // needs a second call, which the press says out loud; a capability the
        // platform does not grant is named instead of faked.
        Call.Details details = null;
        try { details = current == null ? null : current.getDetails(); } catch (Throwable ignored) { }
        if (details == null) {
            out.conferenceWhy = "Conference is unavailable: Telecom has not described this call yet.";
        }
        else {
            out.merge = details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE);
            boolean swap = details.can(Call.Details.CAPABILITY_SWAP_CONFERENCE);
            out.conference = out.merge || swap;
            if (!out.conference) {
                out.conferenceWhy = "Conference is unavailable: Telecom says this call cannot be joined.";
            }
        }
        return out;
    }

    /** Is the call being carried by the Bluetooth gateway the house screened it on? */
    public static boolean onGateway() {
        JawnInCallService svc = instance;
        if (svc == null) return false;
        Trip trip = svc.trips.get(current);
        return trip != null && trip.gateway;
    }

    private static String deviceName(BluetoothDevice device) {
        if (device == null) return "";
        try {
            String name = device.getName();
            return name == null ? "" : name.trim();
        } catch (Throwable t) { return ""; }
    }

    /** Speakerphone on or off. Telecom's route is the switch; the handset is the way back. */
    public static void toggleSpeaker(Context context) {
        JawnInCallService svc = instance;
        Audio now = audioState(context);
        boolean on = now.speaker;
        if (svc == null) {
            AudioManager am = JawnIvrAudio.manager(context);
            if (am == null) { say("the speaker could not be switched: no audio service"); return; }
            try { am.setSpeakerphoneOn(!on); } catch (Throwable t) {
                say("the speaker could not be switched: " + t);
                return;
            }
            say(on ? "speakerphone off" : "speakerphone on");
            return;
        }
        int target;
        if (on) {
            target = (now.mask & CallAudioState.ROUTE_WIRED_HEADSET) != 0
                    ? CallAudioState.ROUTE_WIRED_HEADSET : CallAudioState.ROUTE_EARPIECE;
        }
        else {
            target = CallAudioState.ROUTE_SPEAKER;
        }
        try {
            svc.setAudioRoute(target);
            say(on ? "speakerphone off" : "speakerphone on");
        } catch (Throwable t) {
            say("Telecom would not move the audio: " + t);
        }
    }

    /**
     * The microphone. Telecom's mute is used when it is offered - it mutes the
     * call's own uplink, which is what the caller must not hear - and
     * AudioManager's microphone mute is the fallback.
     */
    public static void toggleMute(Context context) {
        boolean next = !audioState(context).muted;
        JawnInCallService svc = instance;
        if (svc != null) {
            try {
                svc.setMuted(next);
                say(next ? "microphone muted" : "microphone live");
                return;
            } catch (Throwable ignored) { }
        }
        AudioManager am = JawnIvrAudio.manager(context);
        if (am == null) { say("the microphone could not be muted: no audio service"); return; }
        try {
            am.setMicrophoneMute(next);
            say(next ? "microphone muted" : "microphone live");
        } catch (Throwable t) {
            say("the microphone could not be muted: " + t);
        }
    }

    /**
     * Move the call on to Bluetooth, or back to the phone when it is already
     * there. The gateway is the device named by the house's own setting, so a
     * second headset connected to the phone does not steal the route.
     */
    public static void toggleBluetooth(Context context) {
        Context app = context.getApplicationContext();
        JawnInCallService svc = instance;
        Audio now = audioState(app);
        if (now.bluetooth) {
            // the SCO link first, then Telecom's route: clearing only one leaves
            // the call silent, the same pair restoreAudio() has to undo
            try { JawnIvrAudio.restore(app); } catch (Throwable ignored) { }
            int target = (now.mask & CallAudioState.ROUTE_WIRED_HEADSET) != 0
                    ? CallAudioState.ROUTE_WIRED_HEADSET : CallAudioState.ROUTE_EARPIECE;
            try {
                if (svc != null) svc.setAudioRoute(target);
                say("audio back on this phone");
            } catch (Throwable t) {
                say("audio could not leave Bluetooth: " + t);
            }
            return;
        }
        if (!now.bluetoothOffered) {
            say("no Bluetooth device is connected to carry the call");
            return;
        }
        String gateway = JawnPhoneBridge.ivrBluetoothName(app);
        if (svc == null) {
            say(JawnIvrAudio.routeToSco(app, gateway)
                    ? "audio on " + now.bluetoothName
                    : "the Bluetooth link would not come up");
            return;
        }
        try { svc.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH); } catch (Throwable ignored) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            BluetoothDevice device = JawnIvrAudio.gateway(app, gateway);
            if (device != null) {
                try { svc.requestBluetoothAudio(device); } catch (Throwable ignored) { }
            }
        }
        JawnIvrAudio.routeToSco(app, gateway);
        say("audio on " + (now.bluetoothName.isEmpty() ? "Bluetooth" : now.bluetoothName));
    }

    /**
     * Conference, only where Telecom allows it. A merge joins the call that is
     * waiting (Telecom names the candidates); a swap moves the call into a
     * conference that is already up. Neither is invented: where the platform
     * withholds the capability the press only says so.
     */
    public static void joinConference(Context context) {
        JawnInCallService svc = instance;
        if (svc == null || current == null) { say("no call is up"); return; }
        Audio now = audioState(context);
        if (!now.conference) { say(now.conferenceWhy); return; }
        if (!now.merge) {
            try {
                current.swapConference();
                say("swapping into the conference");
            } catch (Throwable t) {
                say("Telecom would not swap the calls: " + t);
            }
            return;
        }
        Call other = null;
        try {
            List<Call> waiting = current.getConferenceableCalls();
            if (waiting != null) {
                for (Call candidate : waiting) {
                    if (candidate != current) { other = candidate; break; }
                }
            }
        } catch (Throwable ignored) { }
        if (other == null) {
            say("no second call to merge yet - answer or place one first");
            return;
        }
        try {
            current.conference(other);
            say("merging the calls");
        } catch (Throwable t) {
            say("Telecom would not merge the calls: " + t);
        }
    }

    /**
     * Take the call back on this phone and stay on the in-call screen, which is
     * where every control now lives. The gateway is told the call is no longer
     * its own so it stops playing into a link that is no longer carrying audio.
     */
    public static void connectPhone(Context context) {
        Context app = context.getApplicationContext();
        try { JawnIvrAudio.restore(app); } catch (Throwable ignored) { }
        restoreAudio();
        JawnPhoneBridge.cancelGateway(app);
        say("the call is on this phone");
    }

    /**
     * Start or stop the WebRTC bridge on the machine that is carrying the call.
     * The POST is the hand-over; the media rides on a window that can hold a
     * microphone (the Manager's screening panel), and the reply is what the
     * screen shows. A second press stops the bridge.
     */
    public static void toggleRtc(Context context) {
        final Context app = context.getApplicationContext();
        final boolean stop = rtcOn;
        rtcOn = !stop;
        say(stop ? "stopping the WebRTC bridge..." : "asking the gateway for the WebRTC bridge...");
        new Thread(new Runnable() {
            @Override public void run() {
                String reply = JawnPhoneBridge.scoRtc(app, stop ? "stop" : "start");
                if (reply == null) {
                    rtcOn = false;
                    say("the house did not answer the WebRTC request");
                    return;
                }
                try {
                    JSONObject answer = new JSONObject(reply);
                    if (answer.optInt("ok", 0) == 1) {
                        String state = answer.optString("state", "");
                        rtcOn = !stop;
                        if (stop) {
                            say("WebRTC bridge stopped");
                        }
                        else {
                            say("WebRTC bridge " + (state.isEmpty() ? "starting" : state)
                                    + " - carry the media from a Manager window");
                        }
                    }
                    else {
                        rtcOn = false;
                        say("WebRTC: " + answer.optString("error", "the gateway refused the bridge"));
                    }
                } catch (Throwable t) {
                    rtcOn = false;
                    say("WebRTC: the gateway's reply could not be read");
                }
            }
        }, "jawn-rtc").start();
    }

    private void showScreen() {
        openScreen(this);
    }

    /** Put the in-call screen up; safe to call without a live service. */
    public static void openScreen(Context context) {
        JawnInCallService svc = instance;
        if (svc != null) {
            try {
                Intent i = new Intent(svc, JawnInCallActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                svc.startActivity(i);
                return;
            } catch (Throwable ignored) { }
        }
        if (context == null) return;
        try {
            Intent i = new Intent(context.getApplicationContext(), JawnInCallActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.getApplicationContext().startActivity(i);
        } catch (Throwable ignored) { }
    }

    private String numberOf(Call call) {
        try {
            Uri u = call.getDetails().getHandle();
            return u == null ? "" : u.getSchemeSpecificPart();
        } catch (Throwable t) { return ""; }
    }
}
