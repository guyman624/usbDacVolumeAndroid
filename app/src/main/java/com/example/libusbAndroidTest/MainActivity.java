package com.example.libusbAndroidTest;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.example.libusbAndroidTest.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    static {
        System.loadLibrary("libusbAndroidTest");
    }

    private static final String TAG = "UsbDacVolume";
    private static final String ACTION_USB_PERMISSION = "com.example.libusbAndroidTest.USB_PERMISSION";
    private static final int APPLE_VID = 0x05AC;
    // Applying resets the DAC, which can re-trigger USB_DEVICE_ATTACHED; avoid an apply loop.
    private static final long AUTO_APPLY_COOLDOWN_MS = 15000;
    private static long lastAutoApply = 0;

    private UsbManager usbManager;
    private TextView tv;
    private EditText volInput;
    private CheckBox autoApply;

    private UsbDevice currentDevice;
    private UsbDeviceConnection connection;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                connectDevice(device, true);
            } else {
                status("USB permission denied", Color.RED);
            }
        }
    };

    private void status(String msg, int color) {
        Log.i(TAG, msg);
        tv.setText(msg);
        tv.setBackgroundColor(color);
    }

    private static boolean isAudioDevice(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) return true;
        }
        return false;
    }

    private UsbDevice findDac() {
        UsbDevice fallback = null;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (d.getVendorId() == APPLE_VID && isAudioDevice(d)) return d;
            if (fallback == null && isAudioDevice(d)) fallback = d;
        }
        return fallback;
    }

    private void closeConnection() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    /** Opens the device; returns the fd or -1. */
    private int openDevice(UsbDevice device) {
        closeConnection();
        if (device.getInterfaceCount() == 0) return -1;
        UsbDeviceConnection c = usbManager.openDevice(device);
        if (c == null) return -1;
        c.claimInterface(device.getInterface(0), true);
        connection = c;
        currentDevice = device;
        return c.getFileDescriptor();
    }

    protected void connectDevice(UsbDevice device, boolean allowAutoApply) {
        // Do NOT open/claim the device here: claimInterface(force=true) detaches the
        // kernel audio driver, which silences the DAC for as long as we hold it.
        // The device is only opened briefly inside applyVolume().
        currentDevice = device;
        String name = device.getProductName();
        if (name == null || name.isEmpty()) name = String.format("USB device %04X:%04X", device.getVendorId(), device.getProductId());
        status(name, Color.TRANSPARENT);

        long now = SystemClock.elapsedRealtime();
        if (allowAutoApply && autoApply.isChecked() && now - lastAutoApply > AUTO_APPLY_COOLDOWN_MS) {
            lastAutoApply = now;
            applyVolume();
        }
    }

    protected void checkUsbDevices() {
        UsbDevice dac = findDac();
        if (dac == null) {
            status("No USB audio device found. Plug in the DAC.", Color.TRANSPARENT);
            return;
        }
        if (usbManager.hasPermission(dac)) {
            connectDevice(dac, true);
        } else {
            Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usbManager.requestPermission(dac, PendingIntent.getBroadcast(this, 0, i, flags));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        tv = binding.sampleText;
        volInput = binding.volume;
        autoApply = binding.autoApply;

        SharedPreferences settings = getSharedPreferences("myPrefs", 0);
        volInput.setText(settings.getString("volume", "0000"));
        autoApply.setChecked(settings.getBoolean("autoApply", false));

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        ContextCompat.registerReceiver(this, usbReceiver, new IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        checkUsbDevices();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbReceiver); } catch (IllegalArgumentException ignored) {}
        closeConnection();
    }

    private void applyVolume() {
        String volume = volInput.getText().toString();
        if (!volume.matches("[0-9A-Fa-f]{4}")) {
            volInput.setBackgroundColor(Color.RED);
            return;
        }
        volInput.setBackgroundColor(Color.TRANSPARENT);

        UsbDevice dac = currentDevice != null ? currentDevice : findDac();
        if (dac == null || !usbManager.hasPermission(dac)) {
            checkUsbDevices();
            return;
        }
        // Open only for the duration of the apply, then release so audio comes back.
        int fd = openDevice(dac);
        if (fd < 0) {
            status("Could not open USB device", Color.RED);
            return;
        }
        int r = setDeviceVolume(fd, hexStringToByteArray(volume));
        closeConnection();
        currentDevice = null;
        if (r == 0) status("Volume set to " + volume.toUpperCase() + ". Restart playback.", Color.TRANSPARENT);
        else status("Setting volume failed (libusb error " + r + ")", Color.RED);

        SharedPreferences settings = getSharedPreferences("myPrefs", 0);
        settings.edit().putString("volume", volume).apply();
    }

    public void applyButtonPressed(View view) {
        applyVolume();
    }

    public void checkboxPressed(View view) {
        getSharedPreferences("myPrefs", 0).edit().putBoolean("autoApply", autoApply.isChecked()).apply();
    }

    public static byte[] hexStringToByteArray(String s) {
        byte[] data = new byte[s.length() / 2];
        for (int i = 0; i < s.length(); i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    public native String initializeNativeDevice(int fileDescriptor);
    public native int setDeviceVolume(int fileDescriptor, byte[] volume);
}
