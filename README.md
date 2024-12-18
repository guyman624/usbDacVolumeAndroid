# usbDacVolumeAndroid

Simple application to set the USB DAC volume on UNROOTED Android Devices

## This is a fork of the original app
I improved it so it detects when you connect a usb dac, automatically unlocks the volume and exits. You need to enable recording permission (is necessary on latest android versions) and enable auto exiting.
Works flawlessly with the Apple USB to Jack adapter :)
Go to releases for the latest alpha version.

# Why
The vast majority of high end android smartphones sold today do not contain a 3.5mm headphone jack. To remedy this, most people will use a USB-C to 3.5mm DAC. However, there are certian DACs (namely the Apple USB- C DAC) that do not default to their highest output setting. On most platforms (Windows, Linux, macOS, iOS), this isn't an issue because they either force the highest DAC volume and adjust their own mixer volume, or they control the DAC volume explicitly. Android does neither, so as a result, some DACs are quieter than they possibly can be.

## Tested Devices
 - OnePlus 12R/Android 14 (Working)
 - OnePlus 7T/Android10/12 (Working)
 - Pixel 3/Android 11 (Working)
 - Galaxy A34/Android 14 (Working)
 - Huawei P20/Android 10 (NOT WORKING... Unknown reason...)
 - Maybe more? Let me know.

# Usage
Launch the application, and connect your DAC. 
You should recieve a prompt asking to give USB Permission to the application. Press OK.
<img src="https://github.com/guyman624/usbDacVolumeAndroid/assets/82007920/48d92739-bc2a-406b-853c-a14bf6f1228a" width="512">

Once you have accepted, you should see this:<br>
<img src="https://github.com/guyman624/usbDacVolumeAndroid/assets/82007920/b9e5bdfe-7f91-4eb1-b846-b6d6fb4a7216" width="512">


- The top text input is the HEXADECIMAL value to send to the dongle. Leave this as 0000 for 100% volume.

- Auto Apply on Start means that the new value will be sent to the dongle immidiately upon detection.

- Apply will set the volume on the device.

- The bottom is the name of the detected USB Device.

You will temporarily lose sound during the setting of volume, however you should be able to restart playback after it has beens set. Please be aware that this setting is only saved until you unplug the headphones.


# Special thanks to:
[ibaiGorordo](https://github.com/ibaiGorordo/libusbAndroidTest) for most of the code :>
