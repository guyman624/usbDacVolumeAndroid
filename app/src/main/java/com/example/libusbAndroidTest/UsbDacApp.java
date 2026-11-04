package com.example.libusbAndroidTest;

import android.app.Application;

/**
 * Only records diagnostics for the crash report. Crashes are not caught or
 * hidden: Android still handles every one of them exactly as before.
 */
public class UsbDacApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashReport.installJavaCrashRecorder(this);
        // Shows in the report whether Android started the app at all on a
        // connect (it does not while the app is in deep sleep).
        EventLog.add(this, "app process started");
    }
}
