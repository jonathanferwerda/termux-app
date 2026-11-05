package com.jawnos.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    /** How long a bonded-but-idle gateway gets to come up at ring time. */
    private static final long GATEWAY_CONNECT_MS = 1500;

    private JawnIvrAudio() {}

    static AudioManager manager(Context context) {
        try {
            return (AudioManager) context.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);
        } catch (Throwable t) { return null; }
    }

    /**
     * The phone's own record of the gateway: the bonded device whose name is the
     * one the house gave it. Its address is what a selection has to compare, so
     * an AudioDeviceInfo can be proven to be the gateway and not just a headset
     * that happens to be connected.
     */
    static BluetoothDevice gateway(Context context, String preferName) {
        String want = preferName == null ? "" : preferName.trim();
        if (want.isEmpty()) return null;
        try {
            BluetoothManager bm = (BluetoothManager) context.getApplicationContext()
                    .getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null) return null;
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = device.getName();
                if (name != null && name.trim().equalsIgnoreCase(want)) return device;
            }
        } catch (Throwable t) { }
        return null;
    }

    /**
     * The Hands-Free device to use: the one whose address is the gateway's.
     *
     * A named gateway is the only device a screened call may use, so when it is
     * not on offer this returns null instead of "the first SCO device". That
     * fallback cost a real call: with a JBL Tune 520BT connected as well, the
     * framework offered only the JBL, the call was routed to it, and the caller
     * heard silence while the gateway played a plan into a link carrying
     * nothing. An empty name (no gateway asked for) keeps the old behaviour.
     */
    static AudioDeviceInfo scoDevice(Context context, String preferName) {
        AudioManager am = manager(context);
        if (am == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        AudioDeviceInfo first = null;
        String want = preferName == null ? "" : preferName.trim();
        String address = "";
        if (!want.isEmpty()) {
            BluetoothDevice named = gateway(context, want);
            try {
                address = named == null || named.getAddress() == null ? "" : named.getAddress();
            } catch (Throwable t) { address = ""; }
        }
        try {
            for (AudioDeviceInfo device : am.getAvailableCommunicationDevices()) {
                if (device.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) continue;
                if (want.isEmpty()) {
                    if (first == null) first = device;
                    continue;
                }
                String addr = device.getAddress() == null ? "" : device.getAddress();
                if (!address.isEmpty() && addr.equalsIgnoreCase(address)) return device;
            }
        } catch (Throwable t) { }
        return want.isEmpty() ? first : null;
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

    /**
     * Can the named gateway actually carry a call right now?
     *
     * "" means yes. Anything else is the line to log when the call is NOT
     * screened: it names the adapter, or the piece that is missing, so a call
     * left to ring says why. This asks the phone to bring a bonded-but-idle
     * Hands-Free link up, so it blocks for at most GATEWAY_CONNECT_MS and
     * belongs on a worker thread, never the main one.
     */
    static String gatewayProblem(Context context, String preferName) {
        String want = preferName == null ? "" : preferName.trim();
        if (want.isEmpty()) want = "jawn-gateway";
        if (Build.VERSION.SDK_INT >= 31
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
            return want + " cannot be checked: Bluetooth connect permission is not granted";
        }
        BluetoothManager bm;
        BluetoothAdapter adapter;
        try {
            bm = (BluetoothManager) context.getApplicationContext()
                    .getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = bm == null ? null : bm.getAdapter();
        } catch (Throwable t) {
            return want + " cannot be checked: " + t;
        }
        if (adapter == null) return want + " cannot be checked: this phone has no Bluetooth adapter";
        try {
            if (!adapter.isEnabled()) return want + " is unreachable: Bluetooth is turned off";
        } catch (Throwable t) {
            return want + " cannot be checked: " + t;
        }
        BluetoothDevice gateway = null;
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = device.getName();
                if (name != null && name.trim().equalsIgnoreCase(want)) { gateway = device; break; }
            }
        } catch (Throwable t) {
            return want + " cannot be checked: " + t;
        }
        if (gateway == null) return want + " is not paired with this phone";
        if (headsetConnected(bm, adapter, gateway)) return "";
        long deadline = System.currentTimeMillis() + GATEWAY_CONNECT_MS;
        BluetoothHeadset headset = headsetProxy(context, adapter, deadline);
        if (headset == null) return want + " is paired but its Hands-Free profile did not open";
        try {
            String connect = connectHeadset(headset, gateway);
            if (!connect.isEmpty()) return want + " is paired but " + connect;
            while (System.currentTimeMillis() < deadline) {
                if (headsetConnected(bm, adapter, gateway)) return "";
                try { Thread.sleep(150); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Throwable t) {
            return want + " is paired but could not be connected: " + t;
        } finally {
            try { adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset); } catch (Throwable ignored) { }
        }
        return want + " is paired but not connected";
    }

    /**
     * Is this exact device carrying Hands-Free audio? The per-device state is
     * the answer; the connected list and the profile state are older, coarser
     * public calls, used only when the per-device one cannot be read - another
     * paired headset must not stand in for the gateway.
     */
    private static boolean headsetConnected(BluetoothManager bm, BluetoothAdapter adapter,
                                            BluetoothDevice gateway) {
        try {
            return bm.getConnectionState(gateway, BluetoothProfile.HEADSET)
                    == BluetoothProfile.STATE_CONNECTED;
        } catch (Throwable ignored) { }
        try {
            List<BluetoothDevice> connected = bm.getConnectedDevices(BluetoothProfile.HEADSET);
            for (BluetoothDevice device : connected) {
                if (gateway.equals(device)) return true;
            }
            return false;
        } catch (Throwable ignored) { }
        try {
            return adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
                    == BluetoothProfile.STATE_CONNECTED;
        } catch (Throwable t) { return false; }
    }

    /**
     * Ask the profile to bring the Hands-Free link up. There is no public
     * method for this (BluetoothHeadset.connect is hidden), so it is called
     * reflectively and a build that refuses it is reported, not fatal: the
     * caller then waits and gives up, and the call is left to ring.
     */
    private static String connectHeadset(BluetoothHeadset headset, BluetoothDevice gateway) {
        try {
            Method connect = BluetoothHeadset.class.getMethod("connect", BluetoothDevice.class);
            Object accepted = connect.invoke(headset, gateway);
            if (accepted instanceof Boolean && (Boolean) accepted) return "";
            return "the phone refused a Hands-Free connect";
        } catch (Throwable t) {
            return "this Android build does not let an app start a Hands-Free connect";
        }
    }

    /** Open the Hands-Free profile proxy, sharing the caller's deadline. */
    private static BluetoothHeadset headsetProxy(Context context, BluetoothAdapter adapter, long deadline) {
        final CountDownLatch ready = new CountDownLatch(1);
        final BluetoothHeadset[] box = new BluetoothHeadset[1];
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HEADSET && proxy instanceof BluetoothHeadset) {
                    box[0] = (BluetoothHeadset) proxy;
                }
                ready.countDown();
            }
            @Override public void onServiceDisconnected(int profile) { }
        };
        try {
            if (!adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET)) return null;
            long left = deadline - System.currentTimeMillis();
            if (left <= 0 || !ready.await(left, TimeUnit.MILLISECONDS)) return null;
            return box[0];
        } catch (Throwable t) {
            return null;
        }
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
