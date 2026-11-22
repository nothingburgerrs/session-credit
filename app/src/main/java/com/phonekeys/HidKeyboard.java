package com.phonekeys;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.util.concurrent.Executor;

/**
 * Makes the phone act as a Bluetooth keyboard using Android's HID Device profile.
 * All Bluetooth calls assume the caller already holds BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
final class HidKeyboard {

    interface Listener {
        void onReadyChanged(boolean registered);
        void onConnectionChanged(BluetoothDevice device, int state);
    }

    private static final int REPORT_ID = 1;

    /** Standard boot-keyboard report descriptor with report ID 1. */
    private static final byte[] DESCRIPTOR = {
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x06,             // Usage (Keyboard)
            (byte) 0xA1, 0x01,      // Collection (Application)
            (byte) 0x85, REPORT_ID, //   Report ID (1)
            0x05, 0x07,             //   Usage Page (Key Codes)
            0x19, (byte) 0xE0,      //   Usage Minimum (224)
            0x29, (byte) 0xE7,      //   Usage Maximum (231)
            0x15, 0x00,             //   Logical Minimum (0)
            0x25, 0x01,             //   Logical Maximum (1)
            0x75, 0x01,             //   Report Size (1)
            (byte) 0x95, 0x08,      //   Report Count (8)
            (byte) 0x81, 0x02,      //   Input (Data, Variable, Absolute) - modifiers
            (byte) 0x95, 0x01,      //   Report Count (1)
            0x75, 0x08,             //   Report Size (8)
            (byte) 0x81, 0x01,      //   Input (Constant) - reserved byte
            (byte) 0x95, 0x05,      //   Report Count (5)
            0x75, 0x01,             //   Report Size (1)
            0x05, 0x08,             //   Usage Page (LEDs)
            0x19, 0x01,             //   Usage Minimum (1)
            0x29, 0x05,             //   Usage Maximum (5)
            (byte) 0x91, 0x02,      //   Output (Data, Variable, Absolute) - LEDs
            (byte) 0x95, 0x01,      //   Report Count (1)
            0x75, 0x03,             //   Report Size (3)
            (byte) 0x91, 0x01,      //   Output (Constant) - padding
            (byte) 0x95, 0x06,      //   Report Count (6)
            0x75, 0x08,             //   Report Size (8)
            0x15, 0x00,             //   Logical Minimum (0)
            0x25, 0x65,             //   Logical Maximum (101)
            0x05, 0x07,             //   Usage Page (Key Codes)
            0x19, 0x00,             //   Usage Minimum (0)
            0x29, 0x65,             //   Usage Maximum (101)
            (byte) 0x81, 0x00,      //   Input (Data, Array) - keys
            (byte) 0xC0             // End Collection
    };

    private final Context context;
    private final Listener listener;
    private final Executor mainExecutor;
    private final HandlerThread sendThread = new HandlerThread("hid-send");
    private final Handler sendHandler;

    private BluetoothHidDevice hid;
    private volatile BluetoothDevice connected;
    private boolean registered;

    HidKeyboard(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.mainExecutor = context.getMainExecutor();
        sendThread.start();
        sendHandler = new Handler(sendThread.getLooper());
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
            registered = isRegistered;
            listener.onReadyChanged(isRegistered);
            // Reconnect automatically to the last host if Android hands it to us.
            if (isRegistered && pluggedDevice != null && connected == null) {
                hid.connect(pluggedDevice);
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connected = device;
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && device.equals(connected)) {
                connected = null;
            }
            listener.onConnectionChanged(device, state);
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            hid.replyReport(device, type, id, new byte[8]);
        }
    };

    /** Registers the keyboard with the Bluetooth stack. Returns false if unsupported. */
    boolean start() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return false;
        return adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile != BluetoothProfile.HID_DEVICE) return;
                hid = (BluetoothHidDevice) proxy;
                BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                        "PhoneKeys Keyboard", "Phone as a keyboard", "PhoneKeys",
                        BluetoothHidDevice.SUBCLASS1_KEYBOARD, DESCRIPTOR);
                hid.registerApp(sdp, null, null, mainExecutor, callback);
            }

            @Override
            public void onServiceDisconnected(int profile) {
                hid = null;
                registered = false;
                connected = null;
                listener.onReadyChanged(false);
            }
        }, BluetoothProfile.HID_DEVICE);
    }

    void stop() {
        BluetoothDevice d = connected;
        if (hid != null) {
            if (d != null) hid.disconnect(d);
            hid.unregisterApp();
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            hid = null;
        }
        sendThread.quitSafely();
    }

    boolean isRegistered() { return registered; }
    boolean isConnected() { return connected != null; }
    BluetoothDevice connectedDevice() { return connected; }

    boolean connect(BluetoothDevice device) {
        return hid != null && registered && hid.connect(device);
    }

    void disconnect() {
        BluetoothDevice d = connected;
        if (hid != null && d != null) hid.disconnect(d);
    }

    /** Types a string. Characters that a US keyboard can't produce are skipped. */
    void type(String text) {
        String s = KeyMap.normalize(text);
        for (int i = 0; i < s.length(); i++) {
            KeyMap.Stroke k = KeyMap.forChar(s.charAt(i));
            if (k != null) press(k.modifiers, k.key);
        }
    }

    void backspace(int count) {
        for (int i = 0; i < count; i++) press(KeyMap.MOD_NONE, KeyMap.KEY_BACKSPACE);
    }

    /** Queues one key press + release. Runs on a background thread to keep the UI smooth. */
    void press(int modifiers, int key) {
        sendHandler.post(() -> {
            BluetoothDevice d = connected;
            BluetoothHidDevice h = hid;
            if (d == null || h == null) return;
            byte[] down = new byte[8];
            down[0] = (byte) modifiers;
            down[2] = (byte) key;
            h.sendReport(d, REPORT_ID, down);
            SystemClock.sleep(8);
            h.sendReport(d, REPORT_ID, new byte[8]);
            SystemClock.sleep(8);
        });
    }
}
