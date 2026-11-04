package com.example.libusbAndroidTest;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * Invisible entry point for USB device attach events. The system starts this
 * activity when a DAC is plugged in; it applies the saved volume silently and
 * finishes without ever showing a window, so the user interface never opens on
 * connect. The component is only enabled while automatic handling is wanted.
 *
 * Once the user ticked "Always open" in Android's USB dialog, the system grants
 * USB access and starts this activity directly on every connect (also after a
 * reboot), without any dialog.
 */
public class UsbAttachActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The activity stays alive (invisibly) until the USB write is done.
        // Never take touches or focus from the app the user is in meanwhile.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

        try {
            handleIntent();
        } catch (Throwable t) {
            // This activity is invisible; an exception must never surface as a
            // crash dialog.
            android.util.Log.e("USB DAC Volume Adjustment", "attach handling failed", t);
            EventLog.add(this, "connect handling failed: " + t);
            finish();
        }
    }

    private void handleIntent() {
        if (!UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) {
            finish();
            return;
        }
        UsbDevice device = UsbController.getUsbDeviceExtra(getIntent());
        // Use only the current system device, never a stale or supplied handle.
        device = UsbController.findPresentDevice(this, device);
        if (device == null) {
            EventLog.add(this, "connect: device already gone");
            finish();
            return;
        }
        EventLog.add(this, "connect: " + UsbController.describe(device));

        // Only audio devices (or a single attached device) are interesting.
        // Everything else is ignored invisibly.
        if (!UsbController.isAudioDevice(device)
                && !UsbController.isSingleAttachedDevice(this, device)) {
            EventLog.add(this, "ignored: not an audio device");
            finish();
            return;
        }

        if (!UsbController.isAutoHandlingWanted(this)) {
            EventLog.add(this, "ignored: automatic restore is off");
            finish();
            return;
        }

        if (!UsbController.hasSavedVolume(this)) {
            EventLog.add(this, "no saved volume yet: opening the app");
            // Nothing saved yet: open the app once so the volume can be set.
            // Every later connect is handled invisibly.
            Intent open = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(open);
            finish();
            return;
        }

        if (!UsbController.hasPermission(this, device)) {
            // Only the invisible mode asks for the permission dialog; with
            // "Auto Apply on Start" the volume is applied when permission was
            // already granted earlier, without any popup.
            if (UsbController.isAutomatic(this)) {
                EventLog.add(this, "no USB permission: asking");
                UsbController.requestPermission(this, device);
            } else {
                EventLog.add(this, "no USB permission");
            }
            finish();
            return;
        }

        UsbController.noteAttach(this, device);

        if (UsbController.isInResetWindow(this)) {
            // Re-attach caused by our own volume write, nothing to do.
            EventLog.add(this, "re-connect caused by the app's own volume write: ignored");
            finish();
            return;
        }

        // Apply in the background and finish as soon as it is done. The
        // activity itself never has a visible window.
        UsbController.silentApply(this, device, this::finish);
    }

}
