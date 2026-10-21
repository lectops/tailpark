// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import java.util.EnumMap;

/** The parked power sequence, driven by monotonic time instead of blocking sleeps. */
public final class ParkedPowerState {
    public interface Actions {
        void wake();
        void backlightOff();
        void enableWifi();
        boolean restoreDisplay();
    }

    public enum Phase { OFF, DRIVING, UNKNOWN, WAITING, WAKING, PARKED, TIMED_OUT }

    private final Actions actions;
    private Phase phase = Phase.OFF;
    private long waitStarted;
    private long darkenAt;
    private long wifiAt;
    private long lastDarken;
    private long nextWake;
    private boolean restorePending;
    private enum Operation { WAKE, DISPLAY, WIFI, RESTORE }
    private final EnumMap<Operation, String> errors = new EnumMap<>(Operation.class);

    public ParkedPowerState(Actions actions) { this.actions = actions; }

    public synchronized void tick(boolean enabled, boolean keepWifi, Integer power,
                                  Boolean screenOn, long now) {
        if (!enabled || power == null || power != 0) {
            phase = !enabled ? Phase.OFF : power == null ? Phase.UNKNOWN : Phase.DRIVING;
            errors.clear();
            if (restorePending) {
                try { restorePending = !actions.restoreDisplay(); }
                catch (RuntimeException e) { errors.put(Operation.RESTORE, message(e)); }
            }
            return;
        }
        if (phase == Phase.OFF || phase == Phase.DRIVING || phase == Phase.UNKNOWN) {
            phase = Phase.WAITING;
            waitStarted = now;
            nextWake = now;
            errors.clear();
        }
        if (phase == Phase.WAITING || phase == Phase.TIMED_OUT) {
            // A missing screen reading can proceed only after confirmed ACC off.
            if (!Boolean.TRUE.equals(screenOn) && now >= nextWake) {
                nextWake = now + 5000;
                if (!attempt(Operation.WAKE, actions::wake)) return;
                phase = Phase.WAKING;
                restorePending = true;
                darkenAt = now + 500;
                wifiAt = now + 3000;
            } else if (now - waitStarted >= 40000) {
                phase = Phase.TIMED_OUT;
                errors.put(Operation.DISPLAY, "Waiting for the car display to turn off");
            }
            return;
        }
        if (phase == Phase.WAKING && now >= darkenAt) {
            attempt(Operation.DISPLAY, actions::backlightOff);
            lastDarken = now;
            phase = Phase.PARKED;
        } else if (phase == Phase.PARKED && now - lastDarken >= 3000) {
            attempt(Operation.DISPLAY, actions::backlightOff);
            lastDarken = now;
        }
        if (keepWifi && phase == Phase.PARKED && now >= wifiAt) {
            wifiAt = now + 30000;
            attempt(Operation.WIFI, actions::enableWifi);
        }
        if (!keepWifi) errors.remove(Operation.WIFI);
    }

    private boolean attempt(Operation operation, Runnable action) {
        try {
            action.run();
            errors.remove(operation);
            return true;
        } catch (RuntimeException e) {
            errors.put(operation, message(e));
            return false;
        }
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    public synchronized Phase phase() { return phase; }
    public synchronized String error() {
        return errors.isEmpty() ? null : errors.values().iterator().next();
    }
}
