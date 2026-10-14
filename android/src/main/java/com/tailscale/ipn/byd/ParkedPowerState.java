// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

/** The parked power sequence, driven by monotonic time instead of blocking sleeps. */
public final class ParkedPowerState {
    public interface Actions {
        void wake();
        void backlightOff();
        void enableWifi();
    }

    public enum Phase { OFF, DRIVING, UNKNOWN, WAITING, WAKING, PARKED, TIMED_OUT }

    private final Actions actions;
    private Phase phase = Phase.OFF;
    private long waitStarted;
    private long darkenAt;
    private long wifiAt;
    private long lastDarken;
    private boolean wifiRestored;
    private String error;

    public ParkedPowerState(Actions actions) { this.actions = actions; }

    public synchronized void tick(boolean enabled, boolean keepWifi, Integer power,
                                  Boolean screenOn, long now) {
        if (!enabled || power == null || power != 0) {
            phase = !enabled ? Phase.OFF : power == null ? Phase.UNKNOWN : Phase.DRIVING;
            wifiRestored = false;
            error = null;
            return;
        }
        if (phase == Phase.OFF || phase == Phase.DRIVING || phase == Phase.UNKNOWN) {
            phase = Phase.WAITING;
            waitStarted = now;
            wifiRestored = false;
            error = null;
        }
        if (phase == Phase.WAITING || phase == Phase.TIMED_OUT) {
            // A missing screen reading can proceed only after confirmed ACC off.
            if (!Boolean.TRUE.equals(screenOn)) {
                attempt(actions::wake);
                phase = Phase.WAKING;
                darkenAt = now + 500;
                wifiAt = now + 3000;
            } else if (now - waitStarted >= 40000) {
                phase = Phase.TIMED_OUT;
                error = "Waiting for the car display to turn off";
            }
            return;
        }
        if (phase == Phase.WAKING && now >= darkenAt) {
            attempt(actions::backlightOff);
            lastDarken = now;
            phase = Phase.PARKED;
        } else if (phase == Phase.PARKED && now - lastDarken >= 3000) {
            attempt(actions::backlightOff);
            lastDarken = now;
        }
        if (keepWifi && phase == Phase.PARKED && now >= wifiAt && !wifiRestored) {
            wifiRestored = attempt(actions::enableWifi);
        }
    }

    private boolean attempt(Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return false;
        }
    }

    public synchronized Phase phase() { return phase; }
    public synchronized String error() { return error; }
}
