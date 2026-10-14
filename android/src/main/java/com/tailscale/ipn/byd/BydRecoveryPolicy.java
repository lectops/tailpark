// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

/** Distinguishes a live controller from an active VPN and bounds repeated recovery attempts. */
public final class BydRecoveryPolicy {
    private long controllerAt;
    private long vpnAt;
    private long nextAttempt;
    private int failures;

    public BydRecoveryPolicy(long now) { controllerAt = now; vpnAt = now; }

    public synchronized void heartbeat(long now, boolean vpnActive) {
        controllerAt = now;
        if (vpnActive) {
            vpnAt = now;
            failures = 0;
            nextAttempt = 0;
        }
    }

    public synchronized boolean shouldRecover(long now) {
        if (now - controllerAt < 30000 && now - vpnAt < 90000) return false;
        if (now < nextAttempt) return false;
        long delay = Math.min(300000L, 30000L << Math.min(failures, 4));
        nextAttempt = now + delay;
        failures++;
        return true;
    }
}
