# usbDacVolumeAndroid

Simple application to set the USB DAC volume on UNROOTED Android Devices

[DOWNLOAD](https://github.com/abhrajyoti-01/usbDacVolumeAndroid/releases/download/release-1.10/app-release.apk)

> **This fork** fixes Samsung Galaxy S24 Ultra / Android 15-16 (One UI 7/8) compatibility, including the permission/relaunch crash loop and the "no sound after Apply" problem (the kernel `snd-usb-audio` driver is now re-attached by resetting the DAC after the volume is set). Version 1.8 also remembers your volume: press Apply once and it is restored automatically every time you reconnect the headset.

# Why
The vast majority of high end android smartphones sold today do not contain a 3.5mm headphone jack. To remedy this, most people will use a USB-C to 3.5mm DAC. However, there are certian DACs (namely the Apple USB- C DAC) that do not default to their highest output setting. On most platforms (Windows, Linux, macOS, iOS), this isn't an issue because they either force the highest DAC volume and adjust their own mixer volume, or they control the DAC volume explicitly. Android does neither, so as a result, some DACs are quieter than they possibly can be.

## Tested Devices
 - OnePlus 12R/Android 14 (Working)
 - OnePlus 7T/Android10/12 (Working)
 - Pixel 3/Android 11 (Working)
 - Galaxy A34/Android 14 (Working)
 - Galaxy S24 Ultra/Android 16/One UI 8 (Working - this fork)
 - Samsung Galaxy A56/Android 15 (Working - this fork)
 - Huawei P20/Android 10 (NOT WORKING... Unknown reason...)
 - Maybe more? Let me know.

# What was fixed in this fork
 - Galaxy S24 Ultra / Android 15-16 (One UI 7/8) support (targetSdk 36, 16 KB page size, modern NDK).
 - USB permission prompt no longer loops or crashes the app (explicit mutable `PendingIntent`, proper receiver export flags, `singleTask` launch mode).
 - "No sound after Apply" fixed: the DAC is reset after the volume is written so the kernel audio driver is re-attached and playback keeps working.
 - Null/endpointless device, invalid file descriptor and permission-denied crashes guarded.
 - Crash-free when other USB devices (keyboards, etc.) are attached while a DAC is connected.
 - No more "app is crashing frequently" (Device care): all native USB calls now run on a single serialized background thread instead of the UI thread, and no connection/file descriptor is kept open between operations.
 - The app clears its own cache automatically on every launch.
 - The permission dialog is not shown again after the app's own USB reset.

# Security hardening
 - **Malformed USB descriptors** can no longer hang or crash the app (bounds-checked descriptor walking in the native helper).
 - **No component accepts untrusted input**: the invisible attach handler validates the device against the live USB device list and the system-granted USB permission, so a crafted intent from another app cannot trigger any USB operation.
 - **Backups disabled** (`allowBackup=false`), so app data cannot be extracted via `adb backup`.
 - **Supply chain locked**:
   - `gradle/verification-metadata.xml` pins SHA-256 checksums of every dependency; the build fails if any artifact changes.
   - The Gradle wrapper script and JAR are the official Gradle 8.11.1 releases (verified by hash) and the wrapper distribution is pinned with `distributionSha256Sum`.
   - The `libusb` submodule is pinned to a specific upstream commit of `libusb/libusb`.
   - Release builds are minified/obfuscated with R8 and are not debuggable.
 - **No permissions** are declared except `RECORD_AUDIO` (used only to suppress the USB audio-capture warning) and USB host. The app never accesses the network, storage, contacts, location or any other data.

# Automatic (invisible) mode
One-time setup:
 1. Plug in the headset and open the app. Allow the microphone permission (Android hides the next checkbox for headsets with a mic otherwise).
 2. In Android's USB dialog, tick **"Always open USB DAC Volume when ... is connected"** and tap OK.
 3. Set your volume and press **Apply**.

The "Always open" choice is the only USB access Android keeps after an unplug. Up to version 1.8 the app's background handler was still disabled when this dialog first appeared, so Android never offered the checkbox and the volume could not be restored without reopening the app. Since 1.9 it is offered on the first prompt (if you missed it, Android offers it again on the next connect).

From then on:
 - You do not need to reopen the app or press Apply each time you connect the headset.
 - The volume is restored silently in the background, even when the app UI is closed or its process has been stopped by Android.
 - The volume is applied exactly once per connection, so the USB reset cannot cause a connect/disconnect loop ("blinking" sound).
 - Settings survive a normal phone restart. Clearing app data, uninstalling the app, or a factory reset removes them (app backup is disabled, so they are not restored from a cloud backup either).
 - The app shows when the last automatic restore ran and whether it succeeded.
 - Nothing is written to the headset automatically until you have pressed Apply once.
 - To stop restoring settings automatically, untick both automatic options.

If the status says "no USB permission", the "Always open" choice is missing: in Android Settings > Apps > USB DAC Volume > Open by default (or "Set as default"), clear the defaults, unplug, plug in again and tick "Always open" in the dialog. Microphone access must also not be blocked in quick settings, because Android refuses USB access to headsets with a mic while it is.

Android may still ask which app to use on the next connection if you have not selected this app as the default USB handler. Force-stopping the app in Android Settings can prevent automatic handling until it is opened again. Some phone vendors may also require allowing background activity.

Version 1.8 fixed the invisible handler's activity lifecycle, kept permission/boot receivers alive until their USB work finished, and persisted the USB-reset cooldown across process recreation.

Version 1.9 fixes why the restore still did not happen without opening the app: the background handler was declared disabled, so Android never offered the "Always open" checkbox in the USB dialog and the USB grant was dropped on every unplug. The handler is now enabled from the first launch, automatic restore is on by default, the background write waits for the freshly attached DAC to settle (the same timing a manual Apply has), and the app shows when the last automatic restore ran and whether it succeeded.

**If the volume suddenly stops being restored on a Samsung phone:** Device care puts an app that crashed several times into *Deep sleeping apps*. A deep-sleeping app is not started when the headset is plugged in, so nothing is restored and no dialog appears. Remove it in Settings > Battery > Background usage limits > Deep sleeping apps, and set Settings > Apps > USB DAC Volume > Battery to *Unrestricted*.

**Crash report (since 1.10):** the *Share crash report* button sends Android's own records of the app's recent crashes and freezes (with the stack traces Android kept), the phone model and Android version, and a short log of what the app did on each connect. Nothing is sent automatically; you choose where to share it.

**Why the USB dialog still shows up once per device model:** Android only remembers the USB permission per *device* (vendor/product ID), tied to the "Always allow" checkbox. This is an OS security boundary - no app can grant itself this permission or skip the dialog. Tick "Always allow" and it will not appear again for that device. Wired headphones/IEMs that report a different model each get their own one-time prompt. The checkbox is only offered when the app holds the microphone permission, so the app asks for it before requesting USB access (needed because these headsets report an audio capture interface).

Note: while a DAC is plugged in, Android may show its normal "USB device connected" notification; that is a system notification, not this app.

# Usage without automatic mode
 - The top text input is the HEXADECIMAL value to send to the dongle. Leave this as 0000 for 100% volume.
 - Auto Apply on Connect means that the saved value will be sent to the dongle immediately upon detection.
 - Apply sets and saves the volume, and enables automatic restoration. Untick both automatic options afterwards if you only want a one-time adjustment.
 - The bottom is the name of the detected USB Device.

On first launch, connect your DAC and accept the USB permission prompt:
<img src="https://github.com/guyman624/usbDacVolumeAndroid/assets/82007920/48d92739-bc2a-406b-853c-a14bf6f1228a" width="512">

Once you have accepted, you should see this:<br>
<img src="https://github.com/guyman624/usbDacVolumeAndroid/assets/82007920/b9e5bdfe-7f91-4eb1-b846-b6d6fb4a7216" width="512">

You may temporarily lose sound while setting the volume. The headset's hardware setting resets when unplugged, but the app now remembers your chosen value and restores it on the next connection when automatic handling is enabled.

# Building
```
git submodule update --init --recursive
./gradlew assembleRelease
```
The APK is written to `app/build/outputs/apk/release/app-release.apk` (or `app/build/outputs/apk/debug/app-debug.apk` for `assembleDebug`).
Requires JDK 17+, Android SDK with API 36 platform, NDK 27.1 and CMake.

# Special thanks to:
[ibaiGorordo](https://github.com/ibaiGorordo/libusbAndroidTest) for most of the code :>

[polhdez](https://github.com/polhdez) for the improved permission handling, the auto-quit toggle, the settings saving fix and the app icon.
