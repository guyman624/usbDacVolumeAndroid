//
// Created by ibaig on 8/27/2021.
//

#ifndef NATIVE_ACTIVITY_LIBUSB_UTILS_H
#define NATIVE_ACTIVITY_LIBUSB_UTILS_H

#include <android/log.h>
#include <libusb.h>

#define LOG_TAG "libusbAndroidTest"
#define log(...) __android_log_print(ANDROID_LOG_INFO,LOG_TAG, __VA_ARGS__)
#define verbose 1
static void print_endpoint_comp(const struct libusb_ss_endpoint_companion_descriptor *ep_comp)
{
    log("      USB 3.0 Endpoint Companion:\n");
    log("        bMaxBurst:           %u\n", ep_comp->bMaxBurst);
    log("        bmAttributes:        %02xh\n", ep_comp->bmAttributes);
    log("        wBytesPerInterval:   %u\n", ep_comp->wBytesPerInterval);
}

static void print_endpoint(const struct libusb_endpoint_descriptor *endpoint)
{
    int i, ret;

    log("      Endpoint:\n");
    log("        bEndpointAddress:    %02xh\n", endpoint->bEndpointAddress);
    log("        bmAttributes:        %02xh\n", endpoint->bmAttributes);
    log("        wMaxPacketSize:      %u\n", endpoint->wMaxPacketSize);
    log("        bInterval:           %u\n", endpoint->bInterval);
    log("        bRefresh:            %u\n", endpoint->bRefresh);
    log("        bSynchAddress:       %u\n", endpoint->bSynchAddress);

    /* The extra bytes come from the (untrusted) USB device and may be
     * malformed. Each descriptor must be at least 2 bytes long; a zero
     * length would loop forever. Reading [i + 1] out of bounds must be
     * prevented as well. */
    i = 0;
    while (i + 1 < endpoint->extra_length) {
        uint8_t descLength = endpoint->extra[i];
        if (descLength < 2) {
            log("        malformed extra descriptor, aborting\n");
            break;
        }

        if (LIBUSB_DT_SS_ENDPOINT_COMPANION == endpoint->extra[i + 1]) {
            struct libusb_ss_endpoint_companion_descriptor *ep_comp;

            ret = libusb_get_ss_endpoint_companion_descriptor(nullptr, endpoint, &ep_comp);
            if (LIBUSB_SUCCESS == ret) {
                print_endpoint_comp(ep_comp);
                libusb_free_ss_endpoint_companion_descriptor(ep_comp);
            }
        }

        i += descLength;
    }
}

static void print_altsetting(const struct libusb_interface_descriptor *interface)
{
    uint8_t i;

    log("    Interface:\n");
    log("      bInterfaceNumber:      %u\n", interface->bInterfaceNumber);
    log("      bAlternateSetting:     %u\n", interface->bAlternateSetting);
    log("      bNumEndpoints:         %u\n", interface->bNumEndpoints);
    log("      bInterfaceClass:       %u\n", interface->bInterfaceClass);
    log("      bInterfaceSubClass:    %u\n", interface->bInterfaceSubClass);
    log("      bInterfaceProtocol:    %u\n", interface->bInterfaceProtocol);
    log("      iInterface:            %u\n", interface->iInterface);

    for (i = 0; i < interface->bNumEndpoints; i++)
        print_endpoint(&interface->endpoint[i]);
}

static void print_2_0_ext_cap(struct libusb_usb_2_0_extension_descriptor *usb_2_0_ext_cap)
{
    log("    USB 2.0 Extension Capabilities:\n");
    log("      bDevCapabilityType:    %u\n", usb_2_0_ext_cap->bDevCapabilityType);
    log("      bmAttributes:          %08xh\n", usb_2_0_ext_cap->bmAttributes);
}

static void print_ss_usb_cap(struct libusb_ss_usb_device_capability_descriptor *ss_usb_cap)
{
    log("    USB 3.0 Capabilities:\n");
    log("      bDevCapabilityType:    %u\n", ss_usb_cap->bDevCapabilityType);
    log("      bmAttributes:          %02xh\n", ss_usb_cap->bmAttributes);
    log("      wSpeedSupported:       %u\n", ss_usb_cap->wSpeedSupported);
    log("      bFunctionalitySupport: %u\n", ss_usb_cap->bFunctionalitySupport);
    log("      bU1devExitLat:         %u\n", ss_usb_cap->bU1DevExitLat);
    log("      bU2devExitLat:         %u\n", ss_usb_cap->bU2DevExitLat);
}


static void print_bos(libusb_device_handle *handle)
{
    struct libusb_bos_descriptor *bos;
    uint8_t i;
    int ret;

    ret = libusb_get_bos_descriptor(handle, &bos);
    if (ret < 0)
        return;

    log("  Binary Object Store (BOS):\n");
    log("    wTotalLength:            %u\n", bos->wTotalLength);
    log("    bNumDeviceCaps:          %u\n", bos->bNumDeviceCaps);

    for (i = 0; i < bos->bNumDeviceCaps; i++) {
        struct libusb_bos_dev_capability_descriptor *dev_cap = bos->dev_capability[i];

        if (dev_cap->bDevCapabilityType == LIBUSB_BT_USB_2_0_EXTENSION) {
            struct libusb_usb_2_0_extension_descriptor *usb_2_0_extension;

            ret = libusb_get_usb_2_0_extension_descriptor(nullptr, dev_cap, &usb_2_0_extension);
            if (ret < 0)
                return;

            print_2_0_ext_cap(usb_2_0_extension);
            libusb_free_usb_2_0_extension_descriptor(usb_2_0_extension);
        } else if (dev_cap->bDevCapabilityType == LIBUSB_BT_SS_USB_DEVICE_CAPABILITY) {
            struct libusb_ss_usb_device_capability_descriptor *ss_dev_cap;

            ret = libusb_get_ss_usb_device_capability_descriptor(nullptr, dev_cap, &ss_dev_cap);
            if (ret < 0)
                return;

            print_ss_usb_cap(ss_dev_cap);
            libusb_free_ss_usb_device_capability_descriptor(ss_dev_cap);
        }
    }

    libusb_free_bos_descriptor(bos);
}

static void print_interface(const struct libusb_interface *interface)
{
    int i;

    for (i = 0; i < interface->num_altsetting; i++)
        print_altsetting(&interface->altsetting[i]);
}

static void print_configuration(struct libusb_config_descriptor *config)
{
    uint8_t i;

    log("  Configuration:\n");
    log("    wTotalLength:            %u\n", config->wTotalLength);
    log("    bNumInterfaces:          %u\n", config->bNumInterfaces);
    log("    bConfigurationValue:     %u\n", config->bConfigurationValue);
    log("    iConfiguration:          %u\n", config->iConfiguration);
    log("    bmAttributes:            %02xh\n", config->bmAttributes);
    log("    MaxPower:                %u\n", config->MaxPower);

    for (i = 0; i < config->bNumInterfaces; i++)
        print_interface(&config->interface[i]);
}

static std::string get_device_name(libusb_device *dev, libusb_device_handle *handle)
{
    struct libusb_device_descriptor desc{};
    unsigned char productString[50] = {};

    int ret = libusb_get_device_descriptor(dev, &desc);
    if (ret < 0 || desc.iProduct == 0) {
        return "USB Device";
    }

    ret = libusb_get_string_descriptor_ascii(handle, desc.iProduct, productString, sizeof(productString) - 1);
    if (ret <= 0) {
        return "USB Device";
    }

    return std::string(reinterpret_cast<char*>(productString), (size_t) ret);
}

static void print_device(libusb_device *dev, libusb_device_handle *handle)
{
    struct libusb_device_descriptor desc{};
    unsigned char string[256];
    const char *speed;
    int ret;
    uint8_t i;
    switch (libusb_get_device_speed(dev)) {
        case LIBUSB_SPEED_LOW:		speed = "1.5M"; break;
        case LIBUSB_SPEED_FULL:		speed = "12M"; break;
        case LIBUSB_SPEED_HIGH:		speed = "480M"; break;
        case LIBUSB_SPEED_SUPER:	speed = "5G"; break;
        case LIBUSB_SPEED_SUPER_PLUS:	speed = "10G"; break;
        default:			speed = "Unknown";
    }

    ret = libusb_get_device_descriptor(dev, &desc);
    if (ret < 0) {
        log("failed to get device descriptor");
        return;
    }

    log("Dev (bus %u, device %u): %04X - %04X speed: %s\n",
        libusb_get_bus_number(dev), libusb_get_device_address(dev),
        desc.idVendor, desc.idProduct, speed);

    if (handle) {
        if (desc.iManufacturer) {
            ret = libusb_get_string_descriptor_ascii(handle, desc.iManufacturer, string, sizeof(string));
            if (ret > 0)
                log("  Manufacturer:              %s\n", (char *)string);
        }

        if (desc.iProduct) {
            ret = libusb_get_string_descriptor_ascii(handle, desc.iProduct, string, sizeof(string));
            if (ret > 0)
                log("  Product:                   %s\n", (char *)string);
        }

        if (desc.iSerialNumber && verbose) {
            ret = libusb_get_string_descriptor_ascii(handle, desc.iSerialNumber, string, sizeof(string));
            if (ret > 0)
                log("  Serial Number:             %s\n", (char *)string);
        }
    }

    if (verbose) {
        for (i = 0; i < desc.bNumConfigurations; i++) {
            struct libusb_config_descriptor *config;

            ret = libusb_get_config_descriptor(dev, i, &config);
            if (LIBUSB_SUCCESS != ret) {
                log("  Couldn't retrieve descriptors\n");
                continue;
            }

            print_configuration(config);

            libusb_free_config_descriptor(config);
        }

        if (handle && desc.bcdUSB >= 0x0201)
            print_bos(handle);
    }
}

#endif //NATIVE_ACTIVITY_LIBUSB_UTILS_H
