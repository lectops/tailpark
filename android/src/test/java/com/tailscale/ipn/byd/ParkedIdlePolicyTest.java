// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import static org.junit.Assert.*;
import org.junit.Test;

public class ParkedIdlePolicyTest {
    private static final long HOUR = 3600000;
    private ParkedIdlePolicy parked() {
        ParkedIdlePolicy policy = new ParkedIdlePolicy();
        policy.configure(2 * HOUR, false, 1, 0);
        policy.observe(0, 0);
        return policy;
    }
    @Test public void activeFourHourTransferAlwaysRenews() {
        ParkedIdlePolicy p = parked();
        for (long now = 10000; now < 4 * HOUR; now += 10000) {
            p.report(0, true, now);
            assertFalse(p.shouldConfirm(0, now));
        }
    }
    @Test public void expiryNeedsTwoFreshReportsAndFinalConfirmation() {
        ParkedIdlePolicy p = parked();
        p.report(2 * HOUR, true, 2 * HOUR);
        assertFalse(p.shouldConfirm(0, 2 * HOUR));
        p.report(2 * HOUR + 10000, true, 2 * HOUR + 10000);
        assertTrue(p.shouldConfirm(0, 2 * HOUR + 10000));
        assertFalse(p.expired());
        p.expire();
        assertTrue(p.expired());
    }
    @Test public void payloadJustBeforeExpiryCancelsPending() {
        ParkedIdlePolicy p = parked();
        p.report(2 * HOUR, true, 2 * HOUR);
        assertFalse(p.shouldConfirm(0, 2 * HOUR));
        p.report(0, true, 2 * HOUR + 10000);
        assertFalse(p.shouldConfirm(0, 2 * HOUR + 10000));
        assertEquals(2 * HOUR, p.remainingMs(2 * HOUR + 10000));
    }
    @Test public void staleUnknownOrCrashedReporterKeepsAccess() {
        ParkedIdlePolicy p = parked();
        p.report(2 * HOUR, true, 2 * HOUR);
        assertFalse(p.shouldConfirm(0, 2 * HOUR));
        assertFalse(p.shouldConfirm(0, 2 * HOUR + 16000));
        p.report(-1, true, 3 * HOUR);
        assertFalse(p.shouldConfirm(0, 3 * HOUR));
        p.report(3 * HOUR, false, 3 * HOUR);
        assertFalse(p.shouldConfirm(0, 3 * HOUR));
        p.report(0, true, 4 * HOUR); // process restart
        assertFalse(p.shouldConfirm(0, 4 * HOUR));
    }
    @Test public void unknownPowerDefersDrivingResetsAndOverrideProtects() {
        ParkedIdlePolicy p = parked();
        p.report(2 * HOUR, true, 2 * HOUR);
        assertFalse(p.shouldConfirm(null, 2 * HOUR));
        p.configure(2 * HOUR, true, 2, 2 * HOUR);
        p.report(4 * HOUR, true, 4 * HOUR);
        assertFalse(p.shouldConfirm(0, 4 * HOUR));
        p.expire();
        p.observe(1, 4 * HOUR);
        assertFalse(p.expired());
        p.observe(0, 4 * HOUR + 1);
        assertEquals(2 * HOUR, p.remainingMs(4 * HOUR + 1));
    }
    @Test public void restoredExpiryIsTerminalAndClockResetIsNotImported() {
        ParkedIdlePolicy p = parked();
        p.restore(1, HOUR, true, true, 3 * HOUR);
        assertTrue(p.expired());
        p.report(0, true, 3 * HOUR);
        assertTrue(p.expired());
        p.configure(2 * HOUR, false, 2, 3 * HOUR); // explicit user reset
        assertFalse(p.expired());
        p.restore(2, 4 * HOUR, true, true, 3 * HOUR);
        assertFalse(p.expired());
    }
}
