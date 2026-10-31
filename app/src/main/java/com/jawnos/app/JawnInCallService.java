package com.jawnos.app;

import android.content.Intent;
import android.net.Uri;
import android.telecom.Call;
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
 */
public class JawnInCallService extends InCallService {

    public static volatile JawnInCallService instance;
    public static volatile int state = Call.STATE_DISCONNECTED;
    public static volatile Call current;

    /** One call's own record, kept across its state changes. */
    private static final class Trip {
        final String number;
        final String direction;
        final long started;
        boolean answered;

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
                JawnPhoneState.incoming(numberOf(call));
                trips.put(call, new Trip(numberOf(call), "incoming", System.currentTimeMillis()));
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
                if (trip != null) trip.answered = true;
            }
            else if (newState == Call.STATE_DISCONNECTED) {
                Trip trip = trips.remove(call);
                if (trip != null) writeTrip(trip);
                JawnPhoneState.incoming("");
                showScreen(); // the screen closes itself once no call is up
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
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
            JawnPhoneBridge.recordCall(this, trip.number, trip.direction, trip.started, ended, trip.answered);
            JawnLog.append("call " + trip.direction + " " + trip.number + " "
                    + (trip.answered ? "answered" : "unanswered") + " " + ((ended - trip.started) / 1000) + "s");
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
