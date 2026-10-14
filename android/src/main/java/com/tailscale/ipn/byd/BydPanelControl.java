// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

/** Owns only the display changes made by this process. */
public final class BydPanelControl {
    public interface Device {
        Integer powerLevel();
        Integer screenStatus();
        boolean plainBacklight(boolean on);
        boolean supportsDisplayLock();
        void lockedBacklight(boolean on);
    }

    private final Device device;
    private boolean changedDisplay;
    private boolean lockMayBeHeld;

    public BydPanelControl(Device device) { this.device = device; }

    public synchronized void darken() {
        // Re-read ACC immediately before writing, including delayed callers.
        if (!Integer.valueOf(0).equals(device.powerLevel())) return;
        Integer before = device.screenStatus();
        if (Integer.valueOf(0).equals(before)) return;
        boolean wrote = lockMayBeHeld;
        if (!lockMayBeHeld) {
            wrote = device.plainBacklight(false);
            changedDisplay |= wrote;
        }
        Integer after = device.screenStatus();
        // A missing status API cannot justify acquiring a vendor display lock.
        if (Integer.valueOf(1).equals(after) && !lockMayBeHeld
                && device.supportsDisplayLock()
                && Integer.valueOf(0).equals(device.powerLevel())) {
            // A failed Binder reply may follow a successful write: keep release reachable.
            lockMayBeHeld = true;
            changedDisplay = true;
            device.lockedBacklight(false);
            wrote = true;
            after = device.screenStatus();
        }
        if (!wrote) throw new IllegalStateException("Backlight control unavailable on this firmware");
        if (Integer.valueOf(1).equals(after)) {
            throw new IllegalStateException("Car display did not turn off");
        }
    }

    /** False means ACC is unknown and restoring our plain backlight change must wait. */
    public synchronized boolean restore(boolean finishing) {
        boolean releasedLock = lockMayBeHeld;
        if (lockMayBeHeld) {
            // Release even if the screen currently looks on; illumination is not lock ownership.
            device.lockedBacklight(true);
            lockMayBeHeld = false;
        }
        if (!changedDisplay) return true;
        Integer power = device.powerLevel();
        if (releasedLock || (power != null && power > 0)) {
            boolean wrote = device.plainBacklight(true);
            Integer status = device.screenStatus();
            if (Integer.valueOf(0).equals(status)) {
                throw new IllegalStateException("Car display did not restore");
            }
            if (!wrote && !releasedLock) {
                throw new IllegalStateException("Backlight restoration unavailable");
            }
            changedDisplay = false;
            return true;
        }
        // An intentional stop while parked returns display policy to the firmware.
        if (finishing || Integer.valueOf(0).equals(power)) {
            changedDisplay = false;
            return true;
        }
        return false;
    }
}
