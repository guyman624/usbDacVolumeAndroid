package com.example.libusbAndroidTest;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
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

    // Used to load the 'lib' library on application startup.
    static {
        System.loadLibrary("libusbAndroidTest");
    }

    private ActivityMainBinding binding;
    private UsbManager usbManager;

    private TextView tv;
    private EditText volInput;

    private CheckBox autoApply;
    private CheckBox quitAfterApply;

    private Button recordPermissionButton;

    private int deviceDescriptor = -1;
    private static String deviceName;

    private static final String TAG = "USB DAC Volume Adjustment" ;
    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if(device != null){
                            connectDevice(device);
                        }
                    }
                    else {
                        Log.d(TAG, "permission denied for device " + device);
                    }
                }
            }
        }
    };

    protected void connectDevice(UsbDevice device)
    {
        UsbInterface intf = device.getInterface(0);
        UsbEndpoint endpoint = intf.getEndpoint(0);
        UsbDeviceConnection connection = usbManager.openDevice(device);
        connection.claimInterface(intf, true);
        int fileDescriptor = connection.getFileDescriptor();

        deviceName = initializeNativeDevice(fileDescriptor);
        deviceDescriptor = fileDescriptor;

        if(autoApply.isChecked() && quitAfterApply.isChecked()){
            setDeviceVolume(fileDescriptor);
            finishAndRemoveTask();
        }
    }

    protected void checkUsbDevices()
    {
        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if(usbManager.hasPermission(device))
            {
                connectDevice(device);
            }
            else {
                usbManager.requestPermission(device, permissionIntent);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        tv = binding.sampleText;
        volInput = binding.volume;
        autoApply = binding.autoApply;
        quitAfterApply = binding.quitAfterApply;
        recordPermissionButton = binding.recordPermissionButton;

        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
        volInput.setText(settings.getString("volume", "0000"));
        autoApply.setChecked(settings.getBoolean("autoApply", false));
        quitAfterApply.setChecked(settings.getBoolean("quitAfterApply", false));

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
                recordPermissionButton.setEnabled(false);
        }

        // Initialize UsbManager
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Initialize the receiver for getting the device permission
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);

        checkUsbDevices();
    }

    public void applyButtonPressed(View view){
        String volume = volInput.getText().toString();

        if(deviceDescriptor < 0){
            tv.setBackgroundColor(Color.RED);
            return;
        }

        try {
            setDeviceVolume(deviceDescriptor);
            volInput.setBackgroundColor(Color.TRANSPARENT);
        } catch (IllegalArgumentException e){
            volInput.setText("");
            volInput.setBackgroundColor(Color.RED);
            return;
        }

        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
        if(!settings.getString("volume", "").equals(volume)) {
            SharedPreferences.Editor editor = settings.edit();
            editor.putString("volume", volume);
            editor.apply();
        }
    }
    public void autoApplyCheckboxPressed(View view){
        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putBoolean("autoApply", autoApply.isChecked());
        editor.apply();
    }

    public void quitAfterApplyCheckboxPressed(View view){
        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putBoolean("quitAfterApply", quitAfterApply.isChecked());
        editor.apply();
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    /**
     * A native method that is implemented by the 'lib' native library,
     * which is packaged with this application.
     */
    public native String initializeNativeDevice(int fileDescriptor);
    public native void setDeviceVolume(int fileDescriptor, byte[] volume);

    public void setDeviceVolume(int fileDescriptor){
        String volume = volInput.getText().toString();

        if(!volume.matches("[0-9A-Fa-f]{4}")){
            throw new IllegalArgumentException();
        }

        setDeviceVolume(fileDescriptor, hexStringToByteArray(volume));
        Toast.makeText(getApplicationContext(), "Volume set for DAC!", Toast.LENGTH_LONG).show();
    }

    // Trigger the permission popup

    private final int CALLBACK_PERMISSION = 1;
    public void recordPermissionButtonPressed(View view) {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, CALLBACK_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CALLBACK_PERMISSION) {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    recordPermissionButton.setEnabled(false);
                return;
            }
        }
    }
}