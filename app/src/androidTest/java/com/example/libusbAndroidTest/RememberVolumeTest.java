package com.example.libusbAndroidTest;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class RememberVolumeTest {
    @Test
    public void attachHandlerIsDeclaredEnabledSoAndroidCanOfferAlwaysOpen() throws Exception {
        // Android only shows "Always open ... when connected" (the persistent USB
        // grant) when the app has an enabled USB_DEVICE_ATTACHED activity at the
        // moment the permission dialog is shown, i.e. on the very first launch.
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ComponentName attach = new ComponentName(context, UsbAttachActivity.class);
        assertTrue(context.getPackageManager()
                .getActivityInfo(attach, PackageManager.MATCH_DISABLED_COMPONENTS).enabled);
    }

    @Test
    public void freshInstallWantsAutomaticHandlingButWritesNothingBeforeApply() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = UsbController.prefs(context);
        String originalVolume = prefs.getString("volume", null);
        boolean hadAutomatic = prefs.contains("automatic");
        boolean originalAutomatic = prefs.getBoolean("automatic", false);
        boolean hadAutoApply = prefs.contains("autoApply");
        boolean originalAutoApply = prefs.getBoolean("autoApply", false);

        try {
            prefs.edit().remove("volume").remove("automatic").remove("autoApply").commit();
            assertTrue(UsbController.isAutomatic(context));
            assertTrue(UsbController.isAutoHandlingWanted(context));
            assertFalse(UsbController.hasSavedVolume(context));
            assertFalse(UsbController.shouldAutoApply(context));
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            if (originalVolume == null) {
                editor.remove("volume");
            } else {
                editor.putString("volume", originalVolume);
            }
            if (hadAutomatic) {
                editor.putBoolean("automatic", originalAutomatic);
            } else {
                editor.remove("automatic");
            }
            if (hadAutoApply) {
                editor.putBoolean("autoApply", originalAutoApply);
            } else {
                editor.remove("autoApply");
            }
            editor.commit();
        }
    }

    @Test
    public void savingVolumeEnablesReconnectHandlerWithoutOpeningUi() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = UsbController.prefs(context);
        String originalVolume = prefs.getString("volume", null);
        boolean hadAutomatic = prefs.contains("automatic");
        boolean originalAutomatic = prefs.getBoolean("automatic", false);
        ComponentName attach = new ComponentName(context, UsbAttachActivity.class);
        PackageManager manager = context.getPackageManager();
        int originalState = manager.getComponentEnabledSetting(attach);

        try {
            UsbController.rememberVolume(context, "F000");
            assertEquals("F000", UsbController.getVolumeHex(context));
            assertTrue(UsbController.isAutomatic(context));
            assertTrue(UsbController.shouldAutoApply(context));
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    manager.getComponentEnabledSetting(attach));
        } finally {
            // Preserve the device's existing configuration after the test.
            SharedPreferences.Editor editor = prefs.edit();
            if (originalVolume == null) {
                editor.remove("volume");
            } else {
                editor.putString("volume", originalVolume);
            }
            if (hadAutomatic) {
                editor.putBoolean("automatic", originalAutomatic);
            } else {
                editor.remove("automatic");
            }
            editor.commit();
            manager.setComponentEnabledSetting(attach, originalState, PackageManager.DONT_KILL_APP);
        }
    }
}
