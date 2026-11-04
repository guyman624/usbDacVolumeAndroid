package com.example.libusbAndroidTest;

/** Evaluates a persisted USB reset cooldown without depending on Android. */
final class ResetWindow {
    private ResetWindow() {
    }

    static boolean isActive(long lastWrite, int lastBoot, long now, int boot, long duration) {
        return lastWrite >= 0 && lastBoot >= 0 && lastBoot == boot
                && now >= lastWrite && now - lastWrite < duration;
    }
}
