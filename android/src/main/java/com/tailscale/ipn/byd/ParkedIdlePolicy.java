// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

/** Conservative inactivity lease. Monotonic timestamps are valid only within one boot. */
public final class ParkedIdlePolicy {
    private long timeoutMs;
    private long generation = -1;
    private boolean keepAwake;
    private boolean parked;
    private boolean expired;
    private long lastActivityAt;
    private long lastReportAt = -1;
    private long pendingAt = -1;
    private boolean reporterHealthy;
    private long reportedIdleMs = -1;

    public synchronized void configure(long timeout, boolean override, long version, long now) {
        if (timeout < 0 || timeout > 24 * 60 * 60 * 1000L) throw new IllegalArgumentException("Invalid timeout");
        if (generation != version || timeoutMs != timeout || keepAwake != override) {
            timeoutMs = timeout;
            generation = version;
            keepAwake = override;
            lastActivityAt = now;
            lastReportAt = -1;
            pendingAt = -1;
            expired = false;
        }
    }

    public synchronized void observe(Integer power, long now) {
        if (power == null) { pendingAt = -1; return; }
        if (power != 0) {
            parked = false;
            expired = false;
            keepAwake = false;
            pendingAt = -1;
            return;
        }
        if (!parked) {
            parked = true;
            lastActivityAt = now;
            lastReportAt = -1;
            pendingAt = -1;
        }
    }

    public synchronized void report(long idleMs, boolean healthy, long now) {
        reporterHealthy = healthy && idleMs >= 0;
        if (!reporterHealthy) { lastReportAt = -1; pendingAt = -1; return; }
        lastReportAt = now;
        reportedIdleMs = idleMs;
        // A reporter restarted after a crash starts at idle=0 and grants a fresh
        // full window. A stale reporter can never establish quiescence.
        long observedActivityAt = now - Math.min(now, idleMs);
        if (observedActivityAt > lastActivityAt + 1000) {
            lastActivityAt = observedActivityAt;
            pendingAt = -1;
        }
    }

    /** Requires two fresh reports at least ten seconds apart and final app confirmation. */
    public synchronized boolean shouldConfirm(Integer power, long now) {
        if (expired || !parked || power == null || power != 0 || timeoutMs == 0 || keepAwake
                || !reporterHealthy || lastReportAt < 0 || now - lastReportAt > 15000
                || now - lastActivityAt < timeoutMs || reportedIdleMs < timeoutMs) {
            pendingAt = -1;
            return false;
        }
        if (pendingAt < 0) { pendingAt = now; return false; }
        return now - pendingAt >= 10000 && lastReportAt >= pendingAt + 10000;
    }

    public synchronized void expire() { expired = true; }
    public synchronized void defer(long now) { pendingAt = -1; lastActivityAt = now; }
    public synchronized boolean expired() { return expired; }
    public synchronized long timeoutMs() { return timeoutMs; }
    public synchronized long generation() { return generation; }
    public synchronized long lastActivityAt() { return lastActivityAt; }
    public synchronized boolean parked() { return parked; }
    public synchronized void restore(long version, long activityAt, boolean wasParked, boolean wasExpired, long now) {
        // Never import a future elapsed timestamp (clock reset/reboot/corrupt state).
        if (version == generation && activityAt >= 0 && activityAt <= now) {
            lastActivityAt = activityAt;
            parked = wasParked;
            expired = wasExpired;
        }
    }
    public synchronized long remainingMs(long now) { return Math.max(0, timeoutMs - (now - lastActivityAt)); }
    public synchronized String state(Integer power, long now) {
        if (expired) return "expired";
        if (timeoutMs == 0) return "disabled";
        if (power == null) return "unknown";
        if (power != 0) return "driving";
        if (keepAwake) return "keep_awake";
        if (pendingAt >= 0) return "sleep_pending";
        if (!reporterHealthy || lastReportAt < 0 || now - lastReportAt > 15000) return "activity_unknown";
        return now - lastActivityAt < 15000 ? "activity" : "tracking";
    }
}
