package com.example.libusbAndroidTest;

public final class UsbNative {

    static {
        System.loadLibrary("libusbAndroidTest");
    }

    private UsbNative() {
    }

    public static native String initializeNativeDevice(int fileDescriptor);

    public static native boolean setDeviceVolume(int fileDescriptor, byte[] volume);
}
