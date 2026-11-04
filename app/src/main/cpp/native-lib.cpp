#include <jni.h>
#include <string>
#include <mutex>

#include "libusb_utils.h"
#include <assert.h>

static bool g_libusbInitialized = false;

// Serializes all native USB operations. The UI and the invisible background
// handler could otherwise access the same file descriptor concurrently, which
// crashes inside libusb.
static std::mutex g_usbMutex;

static int ensureLibusbInitialized() {
    if (g_libusbInitialized) {
        return 0;
    }

    // Required on Android: the app cannot scan /dev/bus/usb, the device is
    // accessed exclusively through the file descriptor wrapped below.
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, NULL);

    int r = libusb_init(nullptr);
    if (r < 0) {
        log("libusb_init failed: %s", libusb_strerror(r));
        return r;
    }

    g_libusbInitialized = true;
    return 0;
}

std::string connect_device(int fileDescriptor) {
    if (ensureLibusbInitialized() < 0) {
        return "";
    }

    libusb_device_handle *devh = nullptr;
    int r = libusb_wrap_sys_device(nullptr, (intptr_t) fileDescriptor, &devh);
    if (r < 0 || devh == nullptr) {
        log("libusb_wrap_sys_device failed: %s", libusb_strerror(r));
        return "";
    }

    libusb_device *device = libusb_get_device(devh);
    if (device == nullptr) {
        log("libusb_get_device failed");
        libusb_close(devh);
        return "";
    }

    print_device(device, devh);

    // Deliberately no libusb_reset_device() here. A reset re-enumerates the
    // DAC, which triggers USB_DEVICE_ATTACHED again and relaunches the app in
    // a loop on Samsung devices, and it also drops the audio routing.

    std::string deviceName = get_device_name(device, devh);

    libusb_close(devh);
    return deviceName;
}

bool setVolume(int fileDescriptor, unsigned char *data, int length) {
    if (ensureLibusbInitialized() < 0) {
        return false;
    }

    libusb_device_handle *devh = nullptr;
    int r = libusb_wrap_sys_device(nullptr, (intptr_t) fileDescriptor, &devh);
    if (r < 0 || devh == nullptr) {
        log("setVolume: libusb_wrap_sys_device failed: %s", libusb_strerror(r));
        return false;
    }

    // Detach the kernel audio driver (snd-usb-audio) so the interface can be
    // claimed for the control transfer.
    r = libusb_detach_kernel_driver(devh, 0);
    if (r < 0 && r != LIBUSB_ERROR_NOT_FOUND) {
        log("setVolume: libusb_detach_kernel_driver failed: %s", libusb_strerror(r));
    }

    r = libusb_claim_interface(devh, 0);
    if (r < 0) {
        log("setVolume: libusb_claim_interface failed: %s", libusb_strerror(r));
    }

    // USB Audio Class 1.0 SET_CUR on the Feature Unit:
    // wValue = (control selector << 8) | channel.
    // Selector 2 is volume, channels 1 and 2 are left/right.
    r = libusb_control_transfer(devh, 0x21, 0x01, 0x0201, 0x0200, data, (uint16_t) length, 500);
    bool volumeWritten = r == length;
    if (r < 0) {
        log("setVolume: control transfer (channel 1) failed: %s", libusb_strerror(r));
    }

    r = libusb_control_transfer(devh, 0x21, 0x01, 0x0202, 0x0200, data, (uint16_t) length, 500);
    volumeWritten = volumeWritten && r == length;
    if (r < 0) {
        log("setVolume: control transfer (channel 2) failed: %s", libusb_strerror(r));
    }

    libusb_release_interface(devh, 0);

    // Important: reset the device after releasing the interface. This forces
    // the kernel to re-probe and rebind snd-usb-audio, which restores audio
    // playback through the DAC (Android does not do this on its own).
    r = libusb_reset_device(devh);
    if (r < 0) {
        log("setVolume: libusb_reset_device failed: %s", libusb_strerror(r));
    }

    libusb_close(devh);
    return volumeWritten;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_libusbAndroidTest_UsbNative_initializeNativeDevice(
        JNIEnv *env,
        jclass /* clazz */,
        jint fileDescriptor) {

    std::lock_guard<std::mutex> lock(g_usbMutex);

    std::string deviceName = connect_device(fileDescriptor);

    return env->NewStringUTF(deviceName.c_str());
}


extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_libusbAndroidTest_UsbNative_setDeviceVolume(
        JNIEnv *env,
        jclass /* clazz */,
        jint fileDescriptor,
        jbyteArray volume) {

    std::lock_guard<std::mutex> lock(g_usbMutex);

    jsize length = env->GetArrayLength(volume);
    if (length <= 0 || length > 8) {
        return JNI_FALSE;
    }

    unsigned char data[8];
    env->GetByteArrayRegion(volume, 0, length, reinterpret_cast<jbyte*>(data));

    return setVolume(fileDescriptor, data, (int) length) ? JNI_TRUE : JNI_FALSE;
}
