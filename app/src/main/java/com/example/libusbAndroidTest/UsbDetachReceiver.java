package com.example.libusbAndroidTest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Tracks physical unplug events. USB_DEVICE_DETACHED is a protected system
 * broadcast, so this receiver cannot be spoofed by other apps. Its only job is
 * to clear the apply cooldown after a real unplug so a replug applies the
 * volume immediately (a detach caused by the app's own volume reset is
 * ignored).
 */
public class UsbDetachReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
            return;
        }
        UsbDevice device = UsbController.getUsbDeviceExtra(intent);
        if (device == null) {
            return;
        }
        UsbController.clearRequested(device);
        UsbController.onDetach(context, device);

        // A real unplug can occur during the reset cooldown. Check again once
        // re-enumeration has settled, even when no settings activity is open.
        PendingResult pendingResult = goAsync();
        Context appContext = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (UsbController.findPresentDevice(appContext, device) == null) {
                    UsbController.forgetDevice(device);
                    EventLog.add(appContext, "unplugged: " + UsbController.describe(device));
                }
            } finally {
                pendingResult.finish();
            }
        }, UsbController.RESET_WINDOW_MS + 600);
    }
}
