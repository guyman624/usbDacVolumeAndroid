package com.example.libusbAndroidTest;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class UsbController {

    public static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";

    public static final long RESET_WINDOW_MS = 6000;
    public static final int RECORD_AUDIO_PERMISSION_CODE = 1;

    private static final String TAG = "USB DAC Volume Adjustment";
    private static final String PREFS = "myPrefs";

    // How long a permission request stays pending before it can be retried.
    private static final long PERMISSION_RETRY_MS = 30000;

    // Android's audio stack opens a freshly attached DAC at the same moment the
    // attach event reaches us. A manual Apply (which always worked) runs on a
    // settled device, so the background restore waits briefly to match that.
    static final long ATTACH_SETTLE_MS = 1200;

    private static final String KEY_VOLUME = "volume";
    private static final String KEY_AUTOMATIC = "automatic";
    private static final String KEY_LAST_AUTO_TIME = "lastAutoTime";
    private static final String KEY_LAST_AUTO_RESULT = "lastAutoResult";

    private static final Map<Integer, Long> pendingPermissionIds = new HashMap<>();

    // Automatic volume writes happen exactly once per physical connection. A
    // reset caused by the write re-enumerates the DAC and fires attach events
    // again; without this guard those events would apply -> reset -> apply in
    // an endless loop (the "blinking" sound / USB toast).
    private static boolean applied = false;
    private static int appliedVendorId = -1;
    private static int appliedProductId = -1;

    private static volatile PermissionListener permissionListener;

    private UsbController() {
    }

    public interface PermissionListener {
        void onPermissionResult(UsbDevice device, boolean granted);
    }

    public static void setPermissionListener(PermissionListener listener) {
        permissionListener = listener;
    }

    public static PermissionListener getPermissionListener() {
        return permissionListener;
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Automatic restore is on unless the user switched it off. Defaulting to
     * on keeps the attach handler enabled from the first launch, which is what
     * lets Android offer "Always open" in the very first USB permission prompt.
     */
    public static boolean isAutomatic(Context context) {
        return prefs(context).getBoolean(KEY_AUTOMATIC, true);
    }

    /** True once a volume has been saved by a successful manual Apply. */
    public static boolean hasSavedVolume(Context context) {
        return prefs(context).contains(KEY_VOLUME);
    }

    public static boolean isAutoApply(Context context) {
        return prefs(context).getBoolean("autoApply", false);
    }

    /** True when the app should work invisibly in the background. */
    public static boolean isSilentMode(Context context) {
        return isAutomatic(context);
    }

    /** True when automatic handling of a connect is wanted at all. */
    public static boolean isAutoHandlingWanted(Context context) {
        return isAutomatic(context) || isAutoApply(context);
    }

    /**
     * True when the volume should be applied automatically on connect. Nothing
     * is written before the user has saved a volume, so a fresh install never
     * pushes a default value to the headset on its own.
     */
    public static boolean shouldAutoApply(Context context) {
        return isAutoHandlingWanted(context) && hasSavedVolume(context);
    }

    /**
     * Enables/disables the automatic (invisible) mode. The attach activity is
     * a manifest component that is only enabled while automatic mode is on, so
     * with it disabled plugging a DAC cannot launch anything at all.
     */
    public static void setAutomatic(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTOMATIC, enabled).apply();
        syncAutomaticComponent(context);
    }

    /**
     * Re-applies the attach component state from the stored preferences. The
     * component is enabled whenever any automatic handling is wanted, even
     * before a volume is saved: Android only offers "Always open this app when
     * the device is connected" (the persistent USB grant that survives replugs
     * and reboots) while the app has an enabled USB_DEVICE_ATTACHED activity.
     */
    public static void syncAutomaticComponent(Context context) {
        toggleAttachActivity(context, isAutoHandlingWanted(context));
    }

    private static void toggleAttachActivity(Context context, boolean enabled) {
        Context appContext = context.getApplicationContext();
        int state = enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        try {
            appContext.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(appContext, UsbAttachActivity.class),
                    state, PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.e(TAG, "setAutomatic: failed to toggle attach activity", e);
        }
    }

    public static String getVolumeHex(Context context) {
        String volume = prefs(context).getString(KEY_VOLUME, "0000");
        return (volume != null && volume.matches("[0-9A-Fa-f]{4}")) ? volume : "0000";
    }

    /** A successful manual Apply also opts into restoring the saved setting. */
    public static void rememberVolume(Context context, String volume) {
        // commit(): the value must be on disk before the process can be killed.
        prefs(context).edit()
                .putString(KEY_VOLUME, volume)
                .putBoolean(KEY_AUTOMATIC, true)
                .commit();
        syncAutomaticComponent(context);
    }

    /** Records the outcome of a background restore so the UI can show it. */
    static void recordAutoResult(Context context, String result) {
        prefs(context).edit()
                .putLong(KEY_LAST_AUTO_TIME, System.currentTimeMillis())
                .putString(KEY_LAST_AUTO_RESULT, result)
                .apply();
        EventLog.add(context, "automatic restore: " + result);
    }

    /** Wall-clock time of the last background restore attempt, or -1. */
    public static long getLastAutoTime(Context context) {
        return prefs(context).getLong(KEY_LAST_AUTO_TIME, -1);
    }

    public static String getLastAutoResult(Context context) {
        return prefs(context).getString(KEY_LAST_AUTO_RESULT, null);
    }

    public static UsbDevice getUsbDeviceExtra(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        //noinspection deprecation
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    public static boolean isAudioDevice(UsbDevice device) {
        if (device == null) {
            return false;
        }
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_AUDIO) {
            return true;
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) {
                return true;
            }
        }
        return false;
    }

    /** Vendor/product id and name of a device, for the event log. */
    static String describe(UsbDevice device) {
        String name = device.getProductName();
        return String.format(Locale.ROOT, "%04x:%04x", device.getVendorId(), device.getProductId())
                + (name != null ? " " + name : "");
    }

    public static boolean isSingleAttachedDevice(Context context, UsbDevice device) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null || device == null) {
            return false;
        }
        return usbManager.getDeviceList().size() == 1;
    }

    public static boolean isSameDevice(UsbDevice first, UsbDevice second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getDeviceId() == second.getDeviceId()) {
            return true;
        }
        return first.getVendorId() == second.getVendorId()
                && first.getProductId() == second.getProductId();
    }

    public static UsbDevice findPresentDevice(Context context, UsbDevice device) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null || device == null) {
            return null;
        }
        for (UsbDevice attached : usbManager.getDeviceList().values()) {
            if (attached.getVendorId() == device.getVendorId()
                    && attached.getProductId() == device.getProductId()) {
                return attached;
            }
        }
        return null;
    }

    public static boolean hasPermission(Context context, UsbDevice device) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        return usbManager != null && device != null && usbManager.hasPermission(device);
    }

    public static synchronized void requestPermission(Context context, UsbDevice device) {
        if (device == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long pending = pendingPermissionIds.get(device.getDeviceId());
        if (pending != null && now - pending < PERMISSION_RETRY_MS) {
            return;
        }

        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            return;
        }

        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(appContext, UsbPermissionReceiver.class);
        intent.setAction(ACTION_USB_PERMISSION);

        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system fills in the result extras through this PendingIntent.
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent permissionIntent =
                PendingIntent.getBroadcast(appContext, device.getDeviceId(), intent, flags);

        pendingPermissionIds.put(device.getDeviceId(), now);
        usbManager.requestPermission(device, permissionIntent);
    }

    public static synchronized void clearRequested(UsbDevice device) {
        if (device != null) {
            pendingPermissionIds.remove(device.getDeviceId());
        }
    }

    /**
     * Called for every USB detach event. Only a real unplug resets the
     * "already applied" state; a detach caused by our own volume write is
     * ignored so it cannot lead to repeated applies (blinking).
     */
    public static synchronized void onDetach(Context context, UsbDevice device) {
        if (device == null) {
            return;
        }
        if (isInResetWindow(context)) {
            Log.d(TAG, "detach during reset window ignored");
            return;
        }
        if (device.getVendorId() == appliedVendorId && device.getProductId() == appliedProductId) {
            applied = false;
            appliedVendorId = -1;
            appliedProductId = -1;
        }
    }

    /**
     * Clears the "already applied" mark for a device without any timing
     * conditions. Called only when a detach has been verified: the device is
     * really absent from the USB device list, so the next attach (a replug)
     * must apply the volume again even if the detach itself happened while the
     * reset window was open.
     */
    public static synchronized void forgetDevice(UsbDevice device) {
        if (device == null) {
            return;
        }
        if (device.getVendorId() == appliedVendorId && device.getProductId() == appliedProductId) {
            applied = false;
            appliedVendorId = -1;
            appliedProductId = -1;
        }
    }

    /**
     * Called for every USB attach event. An attach that arrives while our own
     * reset window is open is the re-enumeration caused by the volume write;
     * anything else is a fresh physical connection.
     */
    public static synchronized boolean noteAttach(Context context, UsbDevice device) {
        if (device == null) {
            return false;
        }
        if (applied
                && device.getVendorId() == appliedVendorId
                && device.getProductId() == appliedProductId
                && !isInResetWindow(context)) {
            // Fresh connection after a very quick unplug/replug whose detach
            // event was swallowed by the reset window.
            applied = false;
            appliedVendorId = -1;
            appliedProductId = -1;
            return true;
        }
        return false;
    }

    /**
     * Claims the single automatic apply for the current physical connection.
     * Returns false if the DAC was already handled since it was plugged in.
     */
    public static synchronized boolean acquireApply(UsbDevice device) {
        if (device == null) {
            return false;
        }
        if (applied
                && device.getVendorId() == appliedVendorId
                && device.getProductId() == appliedProductId) {
            return false;
        }
        applied = true;
        appliedVendorId = device.getVendorId();
        appliedProductId = device.getProductId();
        return true;
    }

    public static synchronized void beginVolumeWrite(Context context) {
        // Persist before the USB reset: Android may recreate our process when
        // the device enumerates again. Elapsed time plus boot count also keeps
        // a clock change or a phone reboot from creating a false cooldown.
        prefs(context).edit()
                .putLong("lastVolumeWrite", SystemClock.elapsedRealtime())
                .putInt("lastVolumeWriteBoot", bootCount(context))
                .commit();
    }

    private static int bootCount(Context context) {
        // Some builds do not expose BOOT_COUNT. Falling back to -1 would disable
        // the reset cooldown entirely and let the post-write re-attach trigger
        // another write (an apply/reset loop). Use a constant instead; the
        // elapsed-time check still rejects stale values from an earlier boot.
        int count = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.BOOT_COUNT, -1);
        return count >= 0 ? count : 0;
    }

    public static synchronized boolean isInResetWindow(Context context) {
        SharedPreferences settings = prefs(context);
        return ResetWindow.isActive(settings.getLong("lastVolumeWrite", -1),
                settings.getInt("lastVolumeWriteBoot", -1),
                SystemClock.elapsedRealtime(), bootCount(context), RESET_WINDOW_MS);
    }

    /**
     * Opens the DAC, writes the saved volume and closes it again. Used by the
     * background components when automatic mode is enabled, so no user
     * interface has to be shown. The callback runs on the main thread.
     */
    public static void silentApply(Context context, UsbDevice device, Runnable onDone) {
        if (device == null || !shouldAutoApply(context)) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                silentApplyInternal(appContext, device);
            } finally {
                if (onDone != null) {
                    new Handler(Looper.getMainLooper()).post(onDone);
                }
            }
        }, "usb-silent-apply").start();
    }

    private static void silentApplyInternal(Context context, UsbDevice device) {
        if (isInResetWindow(context)) {
            EventLog.add(context, "restore skipped: the app's own volume write is in progress");
            return;
        }
        if (!hasPermission(context, device)) {
            recordAutoResult(context, "skipped: no USB permission");
            return;
        }
        if (!acquireApply(device)) {
            EventLog.add(context, "restore skipped: already applied for this connection");
            return;
        }

        // Let the audio stack finish opening the freshly attached DAC, then
        // make sure it is still the same, still-permitted device.
        SystemClock.sleep(ATTACH_SETTLE_MS);
        UsbDevice present = findPresentDevice(context, device);
        if (present == null) {
            forgetDevice(device);
            recordAutoResult(context, "skipped: unplugged before restore");
            return;
        }
        if (!hasPermission(context, present)) {
            forgetDevice(device);
            recordAutoResult(context, "skipped: no USB permission");
            return;
        }
        if (isInResetWindow(context)) {
            // Another component wrote the volume while we were waiting.
            EventLog.add(context, "restore skipped: volume was written meanwhile");
            return;
        }

        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            forgetDevice(device);
            return;
        }

        UsbDeviceConnection connection = null;
        try {
            connection = usbManager.openDevice(present);
            if (connection == null || connection.getFileDescriptor() < 0) {
                forgetDevice(device);
                recordAutoResult(context, "failed: could not open the headset");
                return;
            }
            String hex = getVolumeHex(context);
            byte[] volume = hexStringToByteArray(hex);
            beginVolumeWrite(context);
            if (!UsbNative.setDeviceVolume(connection.getFileDescriptor(), volume)) {
                forgetDevice(device);
                recordAutoResult(context, "failed: headset rejected the volume");
                return;
            }
            recordAutoResult(context, "restored " + hex.toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            forgetDevice(device);
            Log.e(TAG, "silentApply failed", t);
            recordAutoResult(context, "failed: " + t.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
}
