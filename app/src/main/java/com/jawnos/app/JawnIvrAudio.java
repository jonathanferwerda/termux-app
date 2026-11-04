package com.jawnos.app;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

/**
 * Put a call's audio on the Bluetooth link the gateway is listening on.
 *
 * Android keeps two ways to do this, and the phone spans both: from API 31 the
 * communication device is chosen by name (setCommunicationDevice), and before
 * that the only way was the SCO switch (startBluetoothSco + setBluetoothScoOn).
 * The old path is kept for minSdk 26 so an older phone still works.
 *
 * The route is set when the call is answered and again when it goes ACTIVE,
 * because Android may reset the communication device as the call moves state.
 * restore() is what stops the call being stuck on a dead headset afterwards.
 */
final class JawnIvrAudio {

    private JawnIvrAudio() {}

    static AudioManager manager(Context context) {
        try {
            return (AudioManager) context.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);
        } catch (Throwable t) { return null; }
    }

    /**
     * The Hands-Free device to use: the one whose Bluetooth name matches the
     * gateway's, falling back to the only one there is.
     */
    static AudioDeviceInfo scoDevice(Context context, String preferName) {
        AudioManager am = manager(context);
        if (am == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        AudioDeviceInfo first = null;
        String want = preferName == null ? "" : preferName.trim().toLowerCase();
        try {
            for (AudioDeviceInfo device : am.getAvailableCommunicationDevices()) {
                if (device.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) continue;
                if (first == null) first = device;
                String name = device.getProductName() == null ? "" : device.getProductName().toString().toLowerCase();
                if (!want.isEmpty() && name.equals(want)) return device;
            }
            if (!want.isEmpty() && first != null) {
                for (AudioDeviceInfo device : am.getAvailableCommunicationDevices()) {
                    if (device.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) continue;
                    String name = device.getProductName() == null ? "" : device.getProductName().toString().toLowerCase();
                    if (name.contains(want)) return device;
                }
            }
        } catch (Throwable t) { }
        return first;
    }

    /** Is a Bluetooth Hands-Free device (the gateway) connected right now? */
    static boolean scoAvailable(Context context) {
        AudioManager am = manager(context);
        if (am == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                for (AudioDeviceInfo device : am.getAvailableCommunicationDevices()) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true;
                }
                return false;
            }
            return am.isBluetoothScoAvailableOffCall();
        } catch (Throwable t) { return false; }
    }

    /**
     * Send the call to the Bluetooth gateway. The audio travels as CVSD over
     * the SCO/eSCO link the helper holds, so the phone's own earpiece and mic
     * are out of the path while this is on.
     */
    static boolean routeToSco(Context context) {
        return routeToSco(context, "");
    }

    /**
     * Send the call to the named gateway adapter. A phone can have a headset of
     * its own connected at the same time - and Android prefers whichever it made
     * active last - so "the first SCO device" is not good enough: the audio has
     * to go to the adapter the house gateway is listening on.
     */
    static boolean routeToSco(Context context, String preferName) {
        AudioManager am = manager(context);
        if (am == null) return false;
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioDeviceInfo pick = scoDevice(context, preferName);
                if (pick == null) {
                    JawnLog.append("ivr audio: no SCO device (wanted " + preferName + ")");
                    return false;
                }
                boolean ok = am.setCommunicationDevice(pick);
                JawnLog.append("ivr audio: " + (ok ? "routed to " : "could not route to ")
                        + pick.getProductName() + " [" + pick.getAddress() + "] wanted " + preferName);
                return ok;
            }
            am.startBluetoothSco();
            am.setBluetoothScoOn(true);
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Hand the audio back to the phone once the call is over. */
    static void restore(Context context) {
        AudioManager am = manager(context);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice();
            }
            else {
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
            }
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Throwable ignored) { }
    }
}
