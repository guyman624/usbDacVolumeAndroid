package com.example.libusbAndroidTest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Receives the result of the USB permission dialog. When automatic mode is
 * enabled the volume is applied right here, in the background, and no user
 * interface is ever shown.
 */
public class UsbPermissionReceiver extends BroadcastReceiver {

    private static final String TAG = "USB DAC Volume Adjustment";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !UsbController.ACTION_USB_PERMISSION.equals(intent.getAction())) {
            return;
        }

        final UsbDevice device = UsbController.getUsbDeviceExtra(intent);
        UsbController.clearRequested(device);

        boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
        if (device == null) {
            Log.d(TAG, "permission result without a device");
            return;
        }
        EventLog.add(context, "USB permission " + (granted ? "granted" : "denied")
                + " for " + UsbController.describe(device));

        final Context appContext = context.getApplicationContext();
        UsbController.PermissionListener listener = UsbController.getPermissionListener();
        if (listener != null) {
            new Handler(Looper.getMainLooper())
                    .post(() -> listener.onPermissionResult(device, granted));
        } else if (granted) {
            // App UI is not open: apply silently in the background.
            PendingResult pendingResult = goAsync();
            UsbController.silentApply(appContext, device, pendingResult::finish);
        }
    }
}
