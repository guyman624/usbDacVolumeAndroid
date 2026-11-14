#include <jni.h>
#include <string>
#include <android/log.h>

#include "libusb_utils.h"

// Patched: the original wrapped the fd twice, reset the device in between
// (which can invalidate the fd on newer Android), and never checked return
// codes, so a failed second wrap left `devh` uninitialised -> SIGSEGV.
// Now: init libusb once, wrap the fd once, keep that handle, check everything.

#define TAG "UsbDacVolume"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool g_initialised = false;
static libusb_device_handle *g_devh = nullptr;
static int g_fd = -1;

static void close_handle() {
    if (g_devh) {
        libusb_close(g_devh);
        g_devh = nullptr;
    }
    g_fd = -1;
}

static bool ensure_init() {
    if (g_initialised) return true;
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    int r = libusb_init(nullptr);
    if (r != LIBUSB_SUCCESS) {
        LOGE("libusb_init failed: %s", libusb_error_name(r));
        return false;
    }
    g_initialised = true;
    return true;
}

static bool open_fd(int fd) {
    if (g_devh && g_fd == fd) return true;
    close_handle();
    if (!ensure_init()) return false;
    libusb_device_handle *devh = nullptr;
    int r = libusb_wrap_sys_device(nullptr, (intptr_t) fd, &devh);
    if (r != LIBUSB_SUCCESS || devh == nullptr) {
        LOGE("libusb_wrap_sys_device(fd=%d) failed: %s", fd, libusb_error_name(r));
        return false;
    }
    g_devh = devh;
    g_fd = fd;
    return true;
}

static std::string connect_device(int fd) {
    if (!open_fd(fd)) return "";
    libusb_device *device = libusb_get_device(g_devh);
    if (!device) return "";
    print_device(device, g_devh);
    // No reset here any more: resetting made the fd unusable on Android 17.
    return get_device_name(device, g_devh);
}

// Returns 0 on success, otherwise a negative libusb error code.
static int set_volume(int fd, const unsigned char *data) {
    if (!open_fd(fd)) return LIBUSB_ERROR_NO_DEVICE;

    // Same request sequence the original app used (proven on other phones):
    // UAC1 SET_CUR, Volume control (0x02), channels 1 and 2, Feature Unit 2, interface 0.
    int r = libusb_detach_kernel_driver(g_devh, 0);
    if (r != LIBUSB_SUCCESS && r != LIBUSB_ERROR_NOT_FOUND)
        LOGI("detach_kernel_driver: %s (continuing)", libusb_error_name(r));

    r = libusb_claim_interface(g_devh, 0);
    if (r != LIBUSB_SUCCESS) LOGI("claim_interface: %s (continuing)", libusb_error_name(r));
    else libusb_release_interface(g_devh, 0);

    unsigned char buf[2] = {data[0], data[1]};
    int r1 = libusb_control_transfer(g_devh, 0x21, 0x01, 0x0201, 0x0200, buf, 2, 500);
    int r2 = libusb_control_transfer(g_devh, 0x21, 0x01, 0x0202, 0x0200, buf, 2, 500);
    LOGI("SET_CUR ch1=%d ch2=%d", r1, r2);

    // Hand the device back to the audio driver.
    libusb_reset_device(g_devh);
    close_handle();   // fd may be stale after the reset; reopen next time.

    if (r1 < 0) return r1;
    if (r2 < 0) return r2;
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_libusbAndroidTest_MainActivity_initializeNativeDevice(
        JNIEnv *env, jobject, jint fd) {
    std::string name = connect_device(fd);
    return env->NewStringUTF(name.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_libusbAndroidTest_MainActivity_setDeviceVolume(
        JNIEnv *env, jobject, jint fd, jbyteArray volume) {
    if (volume == nullptr || env->GetArrayLength(volume) < 2) return LIBUSB_ERROR_INVALID_PARAM;
    jbyte tmp[2];
    env->GetByteArrayRegion(volume, 0, 2, tmp);
    unsigned char data[2] = {(unsigned char) tmp[0], (unsigned char) tmp[1]};
    return set_volume(fd, data);
}
