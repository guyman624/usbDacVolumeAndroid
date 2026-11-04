package com.example.libusbAndroidTest;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.example.libusbAndroidTest.databinding.ActivityMainBinding;

import java.util.HashMap;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private UsbManager usbManager;

    private TextView tv;
    private TextView autoStatus;
    private Button shareReport;
    private EditText volInput;

    private CheckBox autoApply;
    private CheckBox automatic;

    private int deviceDescriptor = -1;

    // Keep strong references: if the connection is garbage collected the
    // file descriptor is closed and later native calls use a dead fd.
    private UsbDeviceConnection connection;
    private UsbDevice connectedDevice;

    private boolean receiverRegistered = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private UsbDevice pendingDetached;
    private boolean pendingReconnect = false;

    private static final String TAG = "USB DAC Volume Adjustment";
    private static final long VERIFY_DISCONNECT_DELAY_MS = 1000;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (UsbController.isInResetWindow(context)) {
                    // Attach caused by our own volume reset, keep the connection.
                    return;
                }
                UsbDevice device = UsbController.getUsbDeviceExtra(intent);
                if (device != null) {
                    UsbController.noteAttach(context, device);
                }
                if (device != null && isTargetDevice(device)) {
                    if (usbManager.hasPermission(device)) {
                        connectDevice(device, false);
                    } else {
                        tv.setText("Waiting for USB permission...");
                        tv.setBackgroundColor(Color.TRANSPARENT);
                        UsbController.requestPermission(MainActivity.this, device);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (UsbController.isInResetWindow(context)) {
                    // Detach caused by our own volume reset, keep the connection.
                    return;
                }
                UsbDevice device = UsbController.getUsbDeviceExtra(intent);
                if (device != null) {
                    pendingDetached = device;
                    handler.removeCallbacks(verifyDisconnect);
                    handler.postDelayed(verifyDisconnect, VERIFY_DISCONNECT_DELAY_MS);
                }
            }
        }
    };

    // Automatic apply while the UI is open waits for the DAC to settle, the
    // same way the background restore does.
    private final Runnable autoApplyAfterSettle = () -> applyVolume(false);

    private final Runnable reconnectAfterReset = () -> {
        if (!pendingReconnect) {
            return;
        }
        pendingReconnect = false;
        UsbDevice device = connectedDevice;
        if (device == null) {
            return;
        }
        UsbDevice present = UsbController.findPresentDevice(this, device);
        if (present != null) {
            connectDevice(present, true);
        }
    };

    private final Runnable verifyDisconnect = () -> {
        UsbDevice detached = pendingDetached;
        pendingDetached = null;
        if (detached == null) {
            return;
        }

        // A reset or a quick replug makes the DAC disappear and come back
        // within a moment. Verify it is really gone before telling the user.
        UsbDevice present = UsbController.findPresentDevice(this, detached);
        if (present != null) {
            if (connectedDevice != null && UsbController.isSameDevice(present, connectedDevice)) {
                connectDevice(present, true);
            }
            return;
        }

        if (connectedDevice != null && UsbController.isSameDevice(detached, connectedDevice)) {
            handler.removeCallbacks(reconnectAfterReset);
            handler.removeCallbacks(autoApplyAfterSettle);
            // The device is really gone: clear the once-per-connection guard
            // unconditionally, so the next replug applies the volume again
            // even if the detach event was swallowed by the reset window.
            UsbController.forgetDevice(detached);
            resetConnection();
            tv.setText("USB device disconnected");
            tv.setBackgroundColor(Color.TRANSPARENT);
        }
    };

    private final UsbController.PermissionListener permissionListener =
            (device, granted) -> {
                UsbController.clearRequested(device);
                if (granted) {
                    if (device != null) {
                        connectDevice(device, false);
                    }
                } else {
                    tv.setText("USB permission denied");
                    tv.setBackgroundColor(Color.RED);
                }
            };

    private boolean isTargetDevice(UsbDevice device) {
        if (UsbController.isAudioDevice(device)) {
            return true;
        }
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        return manager != null && manager.getDeviceList().size() == 1;
    }

    private void resetConnection() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
        connectedDevice = null;
        deviceDescriptor = -1;
    }

    private void scheduleReconnectAfterReset() {
        pendingReconnect = true;
        handler.removeCallbacks(reconnectAfterReset);
        handler.postDelayed(reconnectAfterReset, UsbController.RESET_WINDOW_MS + 600);
    }

    private void connectDevice(UsbDevice device, boolean afterReset)
    {
        if (device == null || !usbManager.hasPermission(device)) {
            return;
        }
        if (!afterReset && connectedDevice != null
                && connectedDevice.getDeviceId() == device.getDeviceId()
                && deviceDescriptor >= 0) {
            return;
        }
        if (device.getInterfaceCount() == 0) {
            Log.e(TAG, "Device has no interfaces");
            tv.setText("Device has no interfaces");
            tv.setBackgroundColor(Color.RED);
            return;
        }

        // Do not claim the interface here. The native layer only claims it
        // while setting the volume and re-attaches the kernel audio driver
        // afterwards.
        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) {
            Log.e(TAG, "Failed to open device connection");
            tv.setText("Failed to open device");
            tv.setBackgroundColor(Color.RED);
            return;
        }

        int fileDescriptor = conn.getFileDescriptor();
        if (fileDescriptor < 0) {
            Log.e(TAG, "Invalid file descriptor");
            conn.close();
            tv.setText("Failed to open device");
            tv.setBackgroundColor(Color.RED);
            return;
        }

        String deviceName;
        try {
            deviceName = UsbNative.initializeNativeDevice(fileDescriptor);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize native USB device", t);
            conn.close();
            tv.setText("Failed to open device");
            tv.setBackgroundColor(Color.RED);
            return;
        }

        resetConnection();
        connection = conn;
        connectedDevice = device;
        deviceDescriptor = fileDescriptor;

        tv.setText(deviceName != null && !deviceName.isEmpty() ? deviceName : "USB Device");
        tv.setBackgroundColor(Color.TRANSPARENT);

        // When automatic mode is on the background component normally performs
        // the apply; whoever runs first wins thanks to the shared guard.
        boolean shouldApply = UsbController.shouldAutoApply(this)
                && !afterReset
                && !UsbController.isInResetWindow(this)
                && UsbController.acquireApply(device);

        if (shouldApply) {
            handler.removeCallbacks(autoApplyAfterSettle);
            handler.postDelayed(autoApplyAfterSettle, UsbController.ATTACH_SETTLE_MS);
        }
    }

    private void applyVolume(boolean fromUser) {
        if (deviceDescriptor < 0 || connection == null) {
            if (fromUser) {
                tv.setText("No USB device connected");
                tv.setBackgroundColor(Color.RED);
            }
            return;
        }

        String volume = fromUser
                ? volInput.getText().toString() : UsbController.getVolumeHex(this);
        if (!volume.matches("[0-9A-Fa-f]{4}")) {
            volInput.setBackgroundColor(Color.RED);
            return;
        }

        // The native call resets the USB device, so everything that happens in
        // the next seconds is part of this write, not a real disconnect.
        UsbController.beginVolumeWrite(this);
        if (connectedDevice != null) {
            UsbController.acquireApply(connectedDevice);
        }

        String source = fromUser ? "Apply " : "app open: automatic apply ";
        try {
            if (!UsbNative.setDeviceVolume(deviceDescriptor,
                    UsbController.hexStringToByteArray(volume))) {
                EventLog.add(this, source + volume + " failed: headset rejected the volume");
                UsbController.forgetDevice(connectedDevice);
                volInput.setBackgroundColor(Color.RED);
                scheduleReconnectAfterReset();
                return;
            }
            EventLog.add(this, source + volume + " written");
            volInput.setBackgroundColor(Color.TRANSPARENT);
            scheduleReconnectAfterReset();
        } catch (Throwable t) {
            Log.e(TAG, "applyVolume failed", t);
            EventLog.add(this, source + volume + " failed: " + t);
            UsbController.forgetDevice(connectedDevice);
            volInput.setBackgroundColor(Color.RED);
            return;
        }

        if (!fromUser) {
            return;
        }

        UsbController.rememberVolume(this, volume);
        automatic.setChecked(true);
        refreshAutoStatus();
        Toast.makeText(getApplicationContext(),
                "Saved! Volume will restore when you reconnect.", Toast.LENGTH_LONG).show();
    }

    /** Shows whether, when and how the last background restore ran. */
    private void refreshAutoStatus() {
        String text;
        if (!UsbController.isAutoHandlingWanted(this)) {
            text = "Automatic restore is off.";
        } else if (!UsbController.hasSavedVolume(this)) {
            text = "Press Apply once to save your volume.";
        } else {
            long time = UsbController.getLastAutoTime(this);
            String result = UsbController.getLastAutoResult(this);
            if (time < 0 || result == null) {
                text = "Automatic restore: waiting for the next connect.";
            } else {
                text = "Last automatic restore: " + DateUtils.formatDateTime(this, time,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME)
                        + " (" + result + ")";
            }
        }
        autoStatus.setText(text);
    }

    /** Shows how many crashes and freezes Android still has on record. */
    private void refreshShareReport() {
        int problems = CrashReport.countProblems(this);
        shareReport.setText(problems > 0
                ? "Share crash report (" + problems + " recorded)"
                : "Share crash report");
    }

    private void checkUsbDevices()
    {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList.isEmpty()) {
            tv.setText("No USB device connected");
            tv.setBackgroundColor(Color.TRANSPARENT);
            return;
        }

        UsbDevice target = null;
        for (UsbDevice device : deviceList.values()) {
            if (UsbController.isAudioDevice(device)) {
                target = device;
                break;
            }
        }
        if (target == null && deviceList.size() == 1) {
            target = deviceList.values().iterator().next();
        }
        if (target == null) {
            tv.setText("No USB audio device detected");
            tv.setBackgroundColor(Color.TRANSPARENT);
            return;
        }

        if (usbManager.hasPermission(target)) {
            connectDevice(target, false);
        } else {
            tv.setText("Waiting for USB permission...");
            tv.setBackgroundColor(Color.TRANSPARENT);
            UsbController.requestPermission(this, target);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        tv = binding.sampleText;
        autoStatus = binding.autoStatus;
        shareReport = binding.shareReport;
        volInput = binding.volume;
        autoApply = binding.autoApply;
        automatic = binding.automatic;

        SharedPreferences settings = UsbController.prefs(this);
        volInput.setText(UsbController.getVolumeHex(this));
        autoApply.setChecked(settings.getBoolean("autoApply", false));
        automatic.setChecked(UsbController.isAutomatic(this));
        UsbController.syncAutomaticComponent(this);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, usbReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;

        UsbController.setPermissionListener(permissionListener);

        requestRecordAudioPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAutoStatus();
        refreshShareReport();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        UsbController.setPermissionListener(null);
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver);
            receiverRegistered = false;
        }
        resetConnection();
        super.onDestroy();
    }

    private void requestRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    UsbController.RECORD_AUDIO_PERMISSION_CODE);
        } else {
            checkUsbDevices();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == UsbController.RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "RECORD_AUDIO permission granted");
            } else {
                Log.d(TAG, "RECORD_AUDIO permission denied");
            }
            // Request USB access only after the microphone decision, so Android
            // can offer persistent access for headsets with a capture interface.
            checkUsbDevices();
        }
    }

    public void applyButtonPressed(View view) {
        applyVolume(true);
    }

    public void shareReportPressed(View view) {
        Context appContext = getApplicationContext();
        shareReport.setEnabled(false);
        // Reading the system's crash traces can take a moment: not on the UI thread.
        new Thread(() -> {
            String report;
            try {
                report = CrashReport.build(appContext);
            } catch (Throwable t) {
                Log.e(TAG, "building the crash report failed", t);
                report = "Building the crash report failed: " + t;
            }
            String text = report;
            handler.post(() -> {
                shareReport.setEnabled(true);
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "USB DAC Volume crash report")
                        .putExtra(Intent.EXTRA_TEXT, text);
                try {
                    startActivity(Intent.createChooser(send, "Share crash report"));
                } catch (RuntimeException e) {
                    Log.e(TAG, "sharing the crash report failed", e);
                    Toast.makeText(appContext, "Could not open the share menu",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "crash-report").start();
    }

    public void checkboxPressed(View view) {
        UsbController.prefs(this).edit()
                .putBoolean("autoApply", autoApply.isChecked())
                .apply();
        // Enable the invisible attach handler when "Auto Apply on Start" is on
        // so reconnecting the headphones applies the volume without the popup.
        UsbController.syncAutomaticComponent(this);
        refreshAutoStatus();
    }

    public void automaticCheckboxPressed(View view) {
        boolean enabled = automatic.isChecked();
        UsbController.setAutomatic(this, enabled);
        refreshAutoStatus();
        if (enabled) {
            // Ask for permission now so the "always use" option can be chosen;
            // from then on everything happens invisible in the background.
            checkUsbDevices();
        }
    }
}
