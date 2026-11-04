package com.example.libusbAndroidTest;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResetWindowTest {
    @Test
    public void reEnumerationUsesPersistedCooldownAfterProcessRestart() {
        assertTrue(ResetWindow.isActive(1000, 7, 1000, 7, 6000));
        assertTrue(ResetWindow.isActive(1000, 7, 6999, 7, 6000));
    }

    @Test
    public void freshConnectionAfterCooldownCanApply() {
        assertFalse(ResetWindow.isActive(1000, 7, 7000, 7, 6000));
        assertFalse(ResetWindow.isActive(1000, 7, 9000, 7, 6000));
    }

    @Test
    public void rebootNeverInheritsPreviousCooldown() {
        assertFalse(ResetWindow.isActive(1000, 7, 1100, 8, 6000));
    }

    @Test
    public void missingStateDoesNotSuppressFirstApply() {
        assertFalse(ResetWindow.isActive(-1, -1, 1000, 7, 6000));
        assertFalse(ResetWindow.isActive(1000, -1, 1100, -1, 6000));
    }

    @Test
    public void olderElapsedTimeDoesNotCreateEndlessCooldown() {
        assertFalse(ResetWindow.isActive(1000, 7, 900, 7, 6000));
    }
}
