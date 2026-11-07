package com.jawnos.app;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
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
 * machine is connected, and the caller's flow is one the house screens (the
 * built-in "ivr" or "voicemail", or a flow of its own), the call is answered
 * without the in-call screen and its audio is handed to the
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
    /**
     * How long a "reject" flow is held answered before it is disconnected. The
     * network has to see a real answered call, or its voicemail takes a message
     * the owner never wanted - but the caller hears nothing the whole time,
     * because the microphone is muted for the window.
     */
    private static final long REJECT_HOLD_MS = 4_000;

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
        /** A "reject" flow: answered only to deny the carrier's voicemail, then ended. */
        boolean refused;
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
    /** The reject flow's delayed disconnect, so a competing call or teardown can cancel it. */
    private Runnable pendingReject;
    /** The call pendingReject would disconnect; a different call must never match it. */
    private Call pendingRejectCall;

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
                    // Declining hands the call back to the carrier, and the
                    // carrier's voicemail takes the message - which is how the
                    // owner's box filled up. Answer and end instead: the
                    // network sees an answered-then-ended call and records
                    // nothing. trip.refused keeps the log honest about it.
                    trip.refused = true;
                    refuseAtNetwork(call, trip);
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
                // A second call reaching ACTIVE means the person is talking on it:
                // the pending reject belongs to the old, refused call and must not
                // disconnect this one (or keep its microphone muted).
                if (pendingReject != null && pendingRejectCall != call) {
                    cancelPendingReject();
                }
                // reached ACTIVE: this is the state that means somebody answered,
                // whether the call came in or went out
                Trip trip = trips.get(call);
                if (trip != null) {
                    // a refused call is answered without anybody taking it, but
                    // that answer must not read as a call that was taken
                    if (!trip.refused) trip.answered = true;
                    else if (pendingReject == null) {
                        // the window was cancelled when another call was
                        // answered, and the network has now resumed this
                        // refused one: end it rather than let it reconnect a
                        // caller to a microphone that is live again
                        try { call.disconnect(); } catch (Throwable ignored) { }
                        return;
                    }
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
                if (pendingReject != null && call == pendingRejectCall) {
                    // the refused call ended before its window did; the timer
                    // must not fire into a later call
                    handler.removeCallbacks(pendingReject);
                    pendingReject = null;
                    pendingRejectCall = null;
                    unmuteAfterReject();
                }
                holdOn = false;
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
     * Refuse a call at the network instead of declining it.
     *
     * Call.reject() leaves the decision to the carrier, and the carrier's
     * decision is its voicemail, which then takes a message the owner never
     * wanted. Answering and holding the line makes the network see a call that
     * was really taken: it is answered, kept for REJECT_HOLD_MS, then ended.
     *
     * Nothing of the house reaches the caller in that window: the microphone is
     * muted the moment the call is answered, the gateway route is never applied
     * (a rejected call never sets trip.gateway, and a retry left over from an
     * earlier screened call is cancelled), and no prompt, tone or screen sound
     * is played. The trip stays marked refused, so it is still filed as an
     * ignored call and never counted as answered.
     */
    private void refuseAtNetwork(final Call call, final Trip trip) {
        try {
            call.answer(VideoProfile.STATE_AUDIO_ONLY);
        } catch (Throwable t) {
            // the answer was refused: declining is the only route left, and the
            // carrier behaves exactly as it did before
            try { call.reject(false, null); } catch (Throwable ignored) { }
            return;
        }
        // mute before anything can leave the phone, not after the hold starts
        muteForReject();
        // a route retry from an earlier screened call must not attach this one
        // to the gateway; nothing else may be routed either
        handler.removeCallbacks(routeLater);
        final Runnable end = new Runnable() {
            @Override public void run() {
                if (pendingReject != this) return;
                pendingReject = null;
                pendingRejectCall = null;
                // the call may already be gone, or a competing call may have
                // taken over the service's microphone: only this trip's call is
                // ever disconnected
                if (trips.get(call) != trip) { unmuteAfterReject(); return; }
                try { call.disconnect(); } catch (Throwable ignored) { }
                unmuteAfterReject();
            }
        };
        pendingReject = end;
        pendingRejectCall = call;
        handler.postDelayed(end, REJECT_HOLD_MS);
    }

    /**
     * Drop a pending reject without disconnecting anything. Used when somebody
     * answers a different call, and when the service goes away: the microphone
     * is ours (the reject muted it and no screen offered a mute), so it is put
     * back the way an ordinary call expects to find it.
     */
    private void cancelPendingReject() {
        if (pendingReject == null) return;
        handler.removeCallbacks(pendingReject);
        pendingReject = null;
        pendingRejectCall = null;
        unmuteAfterReject();
    }

    /** Mute the uplink for the reject window: Telecom first, AudioManager as the second layer. */
    private void muteForReject() {
        try { setMuted(true); } catch (Throwable ignored) { }
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.setMicrophoneMute(true);
        } catch (Throwable ignored) { }
    }

    private void unmuteAfterReject() {
        try { setMuted(false); } catch (Throwable ignored) { }
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.setMicrophoneMute(false);
        } catch (Throwable ignored) { }
    }

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
                        // the house may run a different plan per caller: name
                        // this one before the link is answered, so it is the
                        // flow the gateway starts with
                        postFlowToGateway(flow);
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
     * Name the caller's flow to the gateway before the call is answered.
     *
     * The gateway runs one step-plan per flow and holds the name until it
     * accepts the link, so the post has to leave before the answer - but it
     * must never hold the answer up. It is one fire-and-forget request on a
     * worker thread with a short timeout: the call is answered and routed
     * whatever the house says, and a gateway that heard nothing keeps its
     * unknown bucket's flow. "ivr" is the phone's older word for the built-in
     * receptionist; every other key is passed through as the house named it.
     */
    private void postFlowToGateway(final String flow) {
        if (flow == null || flow.isEmpty()) return;
        final String key = "ivr".equals(flow) ? "receptionist" : flow;
        new Thread(new Runnable() {
            @Override public void run() {
                String reply = JawnPhoneBridge.scoFlow(JawnInCallService.this, key);
                if (reply == null) return;
                try {
                    JSONObject answer = new JSONObject(reply);
                    if (answer.optInt("ok", 0) != 1) {
                        JawnLog.append("ivr: flow " + key + " refused: "
                                + answer.optString("error", "the gateway said no"));
                    }
                } catch (Throwable t) {
                    JawnLog.append("ivr: flow " + key + " reply could not be read");
                }
            }
        }, "jawn-ivr-flow").start();
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
        return JawnPhoneBridge.ivrScreened(flow);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        cancelPendingReject();
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
        holdOn = false;
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
        if (pendingReject != null && call == pendingRejectCall) {
            handler.removeCallbacks(pendingReject);
            pendingReject = null;
            pendingRejectCall = null;
            unmuteAfterReject();
        }
        Trip trip = trips.remove(call);
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(routeLater);
        holdOn = false;
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
            if (trip.refused) {
                // the door a blacklisted call uses: an ignored appointment,
                // cancelled at once. It was answered only to keep the carrier
                // from taking a message, so it is not filed as a call taken.
                JawnPhoneBridge.recordBlockedCall(this, trip.number);
                JawnLog.append("call " + trip.direction + " " + trip.number
                        + " refused (reject flow)");
                return;
            }
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
    /** The gateway is playing hold music to the caller (as far as the house said). */
    public static volatile boolean holdOn;

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
        /** ... and swap is the capability on offer inside an existing conference. */
        public boolean swap;
        public String conferenceWhy = "";
        /** The gateway is playing hold music to this caller right now. */
        public boolean hold;
        /** Hold can be offered: the gateway is carrying this call. */
        public boolean holdOffered;
        public String holdWhy = "";
        /** The reception flow answered this call: the house screened it. */
        public boolean screened;
        /** That flow gateway is carrying the call right now. */
        public boolean gatewayCarried;
        /** Voicemail can be sent: the same screened, gateway-carried case as Hold. */
        public boolean voicemailOffered;
        /** "Connect phone" brings the call home; false means the same control hands it back. */
        public boolean connectOffered;
        public String connectWhy = "";
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

        // Who is carrying the call is the trip record, not the audio route: a
        // screened call can read as the phone's own route while the gateway
        // really holds the line, and an ordinary Bluetooth headset reads
        // exactly like the gateway. Hold is offered for one case only - the
        // reception flow answered this call (trip.ivr) and its gateway is still
        // carrying it (trip.gateway). "Connect phone" is the same test from the
        // other side: it has nothing to do once the call is home.
        Trip trip = svc == null ? null : svc.trips.get(current);
        out.screened = trip != null && trip.ivr;
        out.gatewayCarried = out.screened && trip.gateway;
        out.hold = holdOn;
        if (!out.gatewayCarried) {
            if (trip == null) {
                out.holdWhy = "Hold is unavailable: this call is not a screened call.";
            }
            else if (!out.screened) {
                out.holdWhy = "Hold is unavailable: this call is not being screened by the house.";
            }
            else {
                out.holdWhy = "Hold is unavailable: the call is not on the house gateway.";
            }
        }
        else {
            out.holdOffered = true;
        }
        // The control is a toggle: on the gateway it brings the call home, and
        // once home the same button hands it back. The note line says which.
        out.connectOffered = out.gatewayCarried;
        if (out.screened) {
            out.connectWhy = out.gatewayCarried
                    ? "The house gateway is carrying this call - Connect phone brings it home."
                    : "The call is on this phone - Send to gateway hands it back to the house.";
        }

        // Voicemail follows the same rule as Hold: only a screened call the
        // gateway is still carrying can be sent to the house's box.
        out.voicemailOffered = out.gatewayCarried;

        // Conference is a property of the call, never of the audio path: who
        // carries the sound has nothing to do with whether calls can be
        // joined, so a call on the phone is exactly when it should be offered.
        // Telecom names merge/swap once it knows of a partner; a call it only
        // says can be held can still start that conference, and the press names
        // the missing second call instead of a dead button.
        Call callUp = current;
        Call.Details details = null;
        try { details = callUp == null ? null : callUp.getDetails(); } catch (Throwable ignored) { }
        if (details == null) {
            out.conferenceWhy = "Conference is unavailable: Telecom has not described this call yet.";
        }
        else {
            // Telecom's capability report for this call - the same test as
            // Call.can() names, read where this API keeps it. Neither the
            // audio route nor trip.gateway is consulted, so a call on the
            // phone offers conference exactly when the platform allows it.
            out.merge = details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE);
            out.swap = details.can(Call.Details.CAPABILITY_SWAP_CONFERENCE);
            boolean partner = false;
            try {
                List<Call> waiting = current == null ? null : current.getConferenceableCalls();
                partner = waiting != null && !waiting.isEmpty();
            } catch (Throwable ignored) { }
            boolean holdable = details.can(Call.Details.CAPABILITY_SUPPORT_HOLD)
                    && (state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING
                        || state == Call.STATE_DIALING || state == Call.STATE_CONNECTING);
            out.conference = out.merge || out.swap || partner || holdable;
            if (!out.conference) {
                out.conferenceWhy = "Conference is unavailable: Telecom says this call cannot be joined.";
            }
        }
        return out;
    }

    /**
     * Is a screened call still being carried by the house gateway?
     *
     * The trip record answers this, not the audio route: the route cannot tell
     * the house adapter from an ordinary headset, and a screened call that the
     * person connected to the phone must read as off the gateway even if
     * Bluetooth is still on.
     */
    public static boolean onGateway() {
        JawnInCallService svc = instance;
        if (svc == null) return false;
        Trip trip = svc.trips.get(current);
        return trip != null && trip.ivr && trip.gateway;
    }

    /**
     * Record who is carrying a screened call after the audio was moved by hand -
     * the in-call screen's Bluetooth control is the reverse of "Connect phone".
     * Only a call the reception flow answered has this to record: on an
     * ordinary call Bluetooth is just a headset, and Hold stays off.
     */
    private static void setGatewayCarried(boolean carried) {
        JawnInCallService svc = instance;
        if (svc == null) return;
        Trip trip = svc.trips.get(current);
        if (trip == null || !trip.ivr) return;
        trip.gateway = carried;
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
     *
     * This is a route picker, nothing more. A Bluetooth route cannot tell the
     * house adapter from any other headset, and it says nothing about whether
     * the gateway is holding the line - so this control never marks the call
     * gateway-carried. Only "Send to gateway" (the Connect-phone toggle) does,
     * and it proves the move before it says so. What the button reports here is
     * read back from the route Telecom actually reached, not from the press.
     */
    public static void toggleBluetooth(Context context) {
        final Context app = context.getApplicationContext();
        final JawnInCallService svc = instance;
        Audio now = audioState(app);
        if (now.bluetooth) {
            boolean wasGateway = now.gatewayCarried;
            // the SCO link first, then Telecom's route: clearing only one leaves
            // the call silent, the same pair restoreAudio() has to undo
            try { JawnIvrAudio.restore(app); } catch (Throwable ignored) { }
            int target = (now.mask & CallAudioState.ROUTE_WIRED_HEADSET) != 0
                    ? CallAudioState.ROUTE_WIRED_HEADSET : CallAudioState.ROUTE_EARPIECE;
            boolean home = false;
            try {
                if (svc != null) svc.setAudioRoute(target);
                home = true;
                say("audio back on this phone");
            } catch (Throwable t) {
                say("audio could not leave Bluetooth: " + t);
            }
            if (home) {
                // the call is home: it is no longer on the gateway, whatever
                // the route was, and a gateway playing hold music is told to
                // stop. The flag only ever goes off here, never on.
                setGatewayCarried(false);
                if (wasGateway) {
                    holdOn = false;
                    JawnPhoneBridge.cancelGateway(app);
                }
            }
            return;
        }
        if (!now.bluetoothOffered) {
            say("no Bluetooth device is connected to carry the call");
            return;
        }
        String gateway = JawnPhoneBridge.ivrBluetoothName(app);
        final boolean sco;
        if (svc == null) {
            sco = JawnIvrAudio.routeToSco(app, gateway);
            say(sco ? "audio on Bluetooth" : "the Bluetooth link would not come up");
            return;
        }
        try { svc.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH); } catch (Throwable ignored) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            BluetoothDevice device = JawnIvrAudio.gateway(app, gateway);
            if (device != null) {
                try { svc.requestBluetoothAudio(device); } catch (Throwable ignored) { }
            }
        }
        sco = JawnIvrAudio.routeToSco(app, gateway);
        say("moving the audio to Bluetooth...");
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                Audio after = audioState(app);
                if (after.bluetooth) {
                    say("audio on " + (after.bluetoothName.isEmpty() ? "Bluetooth" : after.bluetoothName));
                }
                else {
                    say("Bluetooth: Telecom would not move the audio"
                            + (sco ? "" : " (the house gateway is not connected)"));
                }
            }
        }, 600);
    }

    /**
     * Conference, only where Telecom allows it. A merge joins the call that is
     * waiting (Telecom names the candidates); a swap moves the call into a
     * conference that is already up. Neither is invented: where the platform
     * withholds every capability the press only says so.
     */
    public static void joinConference(Context context) {
        JawnInCallService svc = instance;
        if (svc == null || current == null) { say("no call is up"); return; }
        Audio now = audioState(context);
        if (!now.conference) { say(now.conferenceWhy); return; }
        if (!now.merge && now.swap) {
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
     * The one control between the house gateway and this phone, in both
     * directions.
     *
     * While the gateway carries a screened call it takes the call back: the
     * phone's audio first, then the gateway is told the call is no longer its
     * own so it stops playing into a link that is no longer carrying audio.
     * Once the call is home the same control hands it back to the adapter the
     * house named - the way back that Hold, Voicemail and the screening panel
     * all depend on.
     */
    public static void connectPhone(Context context) {
        Context app = context.getApplicationContext();
        if (!onGateway()) {
            Audio now = audioState(app);
            if (!now.screened) {
                // nothing to hand over: the house never screened this call
                say("this call is not being screened by the house");
                return;
            }
            sendToGateway(context);
            return;
        }
        try { JawnIvrAudio.restore(app); } catch (Throwable ignored) { }
        restoreAudio();
        JawnPhoneBridge.cancelGateway(app);
        // the music came through the gateway's link; it is gone with it
        holdOn = false;
        // the face belongs to the gateway: once the call is home it has
        // nothing to show, and the in-call screen is where the controls are
        JawnScreenFace.hide(app);
        say("the call is on this phone");
    }

    private static final long GATEWAY_WAIT_MS = 4_000;
    private static final long GATEWAY_POLL_MS = 300;

    /**
     * Hand a screened call that came home back to the gateway the house named.
     *
     * routeCallToGateway() is the same route answer time applies - Telecom's
     * Bluetooth route, the named Hands-Free device, the SCO link, and its
     * retries for a route Telecom resets as the call settles. The trip's
     * gateway flag is what Hold, Voicemail and the screening panel read, and it
     * is set only after two proofs: Telecom really put the call on the adapter
     * the house named, and the gateway itself confirms it has the call. A
     * Bluetooth route on its own is no proof - it looks exactly like any
     * headset - and a gateway that is not in a call would leave the caller on a
     * silent link, so a failed check brings the audio home and says why.
     */
    private static void sendToGateway(Context context) {
        final Context app = context.getApplicationContext();
        final JawnInCallService svc = instance;
        if (svc == null || current == null) { say("no call is up"); return; }
        Audio now = audioState(app);
        if (now.gatewayCarried) { say("the call is already on the house gateway"); return; }
        final String gateway = JawnPhoneBridge.ivrBluetoothName(app);
        if (gateway.isEmpty()) { say("the house has not named a gateway for this call"); return; }
        if (!now.bluetoothOffered) {
            say("no Bluetooth device is connected to carry the call");
            return;
        }
        // music would play into a link that is not carrying the call
        holdOn = false;
        say("handing the call back to " + gateway + "...");
        svc.routeCallToGateway();
        awaitGatewayRoute(app, svc, gateway, System.currentTimeMillis());
    }

    /** Wait for the named adapter, then let the gateway confirm the call. */
    private static void awaitGatewayRoute(final Context app, final JawnInCallService svc,
                                          final String gateway, final long started) {
        if (instance != svc || current == null || state == Call.STATE_DISCONNECTED) return;
        if (routeIsGateway(app, gateway)) {
            probeGateway(app, svc, gateway);
            return;
        }
        if (System.currentTimeMillis() - started >= GATEWAY_WAIT_MS) {
            say("the call did not move to " + gateway + " - it stays on this phone");
            return;
        }
        svc.handler.postDelayed(new Runnable() {
            @Override public void run() { awaitGatewayRoute(app, svc, gateway, started); }
        }, GATEWAY_POLL_MS);
    }

    /** Is Telecom's route on the exact adapter the house named? */
    private static boolean routeIsGateway(Context context, String gateway) {
        BluetoothDevice named = JawnIvrAudio.gateway(context, gateway);
        String address = "";
        try { address = named == null ? "" : named.getAddress(); } catch (Throwable ignored) { }
        JawnInCallService svc = instance;
        if (svc != null) {
            CallAudioState cas = null;
            try { cas = svc.getCallAudioState(); } catch (Throwable ignored) { }
            if (cas == null || cas.getRoute() != CallAudioState.ROUTE_BLUETOOTH) return false;
            BluetoothDevice active = cas.getActiveBluetoothDevice();
            if (active != null) {
                String activeAddress = "";
                try { activeAddress = active.getAddress(); } catch (Throwable ignored) { }
                if (!address.isEmpty() && !activeAddress.isEmpty()) {
                    return address.equalsIgnoreCase(activeAddress);
                }
                String activeName = deviceName(active);
                if (!activeName.isEmpty()) return activeName.equalsIgnoreCase(gateway);
                return false;
            }
            // Telecom named no device: fall through to AudioManager's answer
        }
        AudioManager am = JawnIvrAudio.manager(context);
        if (am == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioDeviceInfo device = am.getCommunicationDevice();
                if (device == null || device.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return false;
                if (!address.isEmpty()) return address.equalsIgnoreCase(device.getAddress());
                return true;
            }
            return am.isBluetoothScoOn();
        } catch (Throwable t) { return false; }
    }

    /** Ask the gateway itself; the answer, not the route, turns the flag on. */
    private static void probeGateway(final Context app, final JawnInCallService svc,
                                     final String gateway) {
        new Thread(new Runnable() {
            @Override public void run() {
                JawnPhoneBridge.ScoState probe = JawnPhoneBridge.scoState(app);
                final boolean carrying = probe != null && probe.carrying;
                final String why = probe == null
                        ? "the gateway did not answer"
                        : !probe.answered
                            ? "the gateway did not answer"
                            : !probe.carrying
                                ? "the gateway is not carrying a call ("
                                    + (probe.state.isEmpty() ? "idle" : probe.state) + ")"
                                : "";
                svc.handler.post(new Runnable() {
                    @Override public void run() {
                        if (instance != svc || current == null || state == Call.STATE_DISCONNECTED) return;
                        if (carrying) {
                            setGatewayCarried(true);
                            Trip trip = svc.trips.get(current);
                            JawnScreenFace.show(app, trip == null ? "" : trip.number);
                            say("the call is back on " + gateway);
                            return;
                        }
                        // the audio sits on the adapter but the house is not
                        // holding the line: keep the flag off and bring the
                        // call home, or the caller talks into a silent link
                        setGatewayCarried(false);
                        try { JawnIvrAudio.restore(app); } catch (Throwable ignored) { }
                        restoreAudio();
                        say(why + " - the call stays on this phone");
                    }
                });
            }
        }, "jawn-gateway-probe").start();
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

    /**
     * Start or stop the gateway's hold music for this call. The POST is the
     * whole action - the gateway owns the caller's line - and its reply is what
     * the screen paints: the music is on only when the house said ok.
     */
    public static void toggleHold(Context context) {
        final Context app = context.getApplicationContext();
        final boolean stop = holdOn;
        if (!stop) {
            Audio now = audioState(app);
            if (!now.holdOffered) { say(now.holdWhy); return; }
        }
        say(stop ? "stopping the hold music..." : "putting the caller on hold music...");
        new Thread(new Runnable() {
            @Override public void run() {
                String reply = JawnPhoneBridge.scoHold(app, stop ? "stop" : "start");
                if (reply == null) {
                    say("the house did not answer the hold request");
                    return;
                }
                try {
                    JSONObject answer = new JSONObject(reply);
                    if (answer.optInt("ok", 0) == 1) {
                        // a reply that lands after the call ended must not
                        // leave the next call painted as on hold
                        holdOn = current != null && state != Call.STATE_DISCONNECTED && !stop;
                        say(stop ? "hold music stopped" : "the caller is on hold music");
                    }
                    else {
                        say("Hold: " + answer.optString("error", "the gateway refused the hold"));
                    }
                } catch (Throwable t) {
                    say("Hold: the gateway's reply could not be read");
                }
            }
        }, "jawn-hold").start();
    }

    /**
     * Send this caller to the house's voicemail: the gateway plays its greeting
     * and records the message now. The call stays up on purpose - the gateway
     * owns the line and ends it when the recording does, so nothing here hangs
     * up and "Connect phone" is untouched.
     *
     * A second press is harmless: the gateway answers {ok:0} when no call is up
     * or it is already recording, that reason is painted in the note line, and
     * the app does not retry.
     */
    /**
     * Send this caller to the house's voicemail: the gateway plays its greeting
     * and records the message now. The call stays up on purpose - the gateway
     * owns the line and ends it when the recording does, so nothing here hangs
     * up and "Connect phone" is untouched.
     *
     * The house's own reply is shown verbatim. A refusal keeps its
     * {ok:0,error} words, and a request that never reached the house says
     * which half failed (timeout, a non-200, an unreadable body) instead of a
     * generic silence. Nothing is retried.
     */
    public static void sendToVoicemail(Context context) {
        final Context app = context.getApplicationContext();
        Audio now = audioState(app);
        if (!now.voicemailOffered) {
            say(now.screened
                    ? "Voicemail is unavailable: the call is not on the house gateway."
                    : "Voicemail is unavailable: this call is not being screened by the house.");
            return;
        }
        say("sending the caller to the house voicemail...");
        new Thread(new Runnable() {
            @Override public void run() {
                VoicemailResult result = voicemailResult(JawnPhoneBridge.scoVoicemail(app, null));
                // sending the caller to voicemail stops the hold music before
                // the greeting plays, so the screen must not keep painting
                // "Hold: on"
                if (result.accepted) holdOn = false;
                say(result.note);
            }
        }, "jawn-voicemail").start();
    }

    /** What the note line should say about a voicemail request, and whether it landed. */
    public static final class VoicemailResult {
        public final boolean accepted;
        public final String note;
        VoicemailResult(boolean accepted, String note) {
            this.accepted = accepted;
            this.note = note;
        }
    }

    /**
     * The house's own words when it answered, otherwise which part of the
     * request failed. A non-200 body is still read: the gateway may put its
     * refusal there, and that sentence is what the person needs to see.
     */
    public static VoicemailResult voicemailResult(JawnPhoneBridge.ScoReply reply) {
        if (reply == null) {
            return new VoicemailResult(false, "Voicemail: the house did not answer");
        }
        String body = reply.body;
        if (body != null && !body.trim().isEmpty()) {
            JSONObject answer = null;
            try { answer = new JSONObject(body); } catch (Throwable ignored) { }
            if (answer != null) {
                if (answer.optInt("ok", 0) == 1) {
                    return new VoicemailResult(true,
                            "the caller is on the house voicemail - recording now");
                }
                String error = answer.optString("error", "");
                if (!error.isEmpty()) return new VoicemailResult(false, "Voicemail: " + error);
                return new VoicemailResult(false,
                        "Voicemail: the house refused it without saying why"
                        + (reply.failure == null ? "" : " (" + reply.failure + ")"));
            }
            // the body is there but is not JSON: name that, and the status the
            // request itself got, instead of a generic silence
            return new VoicemailResult(false,
                    "Voicemail: the gateway's reply could not be read as JSON"
                    + (reply.failure == null ? "" : " (" + reply.failure + ")"));
        }
        return new VoicemailResult(false,
                "Voicemail: " + (reply.failure == null ? "the house did not answer" : reply.failure));
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
