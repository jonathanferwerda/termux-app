package com.jawnos.app;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import android.telecom.VideoProfile;

import java.util.HashMap;
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

    private final Handler handler = new Handler(Looper.getMainLooper());
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
        boolean ivr;
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
                    if (trip.ivr) routeCallToGateway();
                }
            }
            else if (newState == Call.STATE_DISCONNECTED) {
                Trip trip = trips.remove(call);
                handler.removeCallbacks(watchdog);
                if (trip != null && trip.ivr) {
                    JawnIvrAudio.restore(JawnInCallService.this);
                    JawnPhoneBridge.cancelGateway(JawnInCallService.this);
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
                        routeCallToGateway();
                        try { call.answer(VideoProfile.STATE_AUDIO_ONLY); } catch (Throwable ignored) { }
                        handler.removeCallbacks(watchdog);
                        handler.postDelayed(watchdog,
                                "voicemail".equals(flow) ? VOICEMAIL_LIMIT_MS : IVR_LIMIT_MS);
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
        routeOnce();
        // Telecom accepts the route once the call is really up, and the radio
        // needs a moment after that: one attempt at the ring and one at ACTIVE
        // was not always enough for the caller's voice to come back
        handler.postDelayed(this::routeOnce, 800);
        handler.postDelayed(this::routeOnce, 2200);
    }

    private void routeOnce() {
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
        if (trip != null && trip.ivr) {
            JawnIvrAudio.restore(this);
            JawnPhoneBridge.cancelGateway(this);
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

    private void showScreen() {
        try {
            Intent i = new Intent(this, JawnInCallActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {}
    }

    private String numberOf(Call call) {
        try {
            Uri u = call.getDetails().getHandle();
            return u == null ? "" : u.getSchemeSpecificPart();
        } catch (Throwable t) { return ""; }
    }
}
