// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import org.junit.Assert.*
import org.junit.Test

class BydRecoveryPolicyTest {
  @Test
  fun missingControllerTriggersRecoveryAfterThirtySeconds() {
    val policy = BydRecoveryPolicy(0)
    assertFalse(policy.shouldRecover(29999))
    assertTrue(policy.shouldRecover(30000))
    assertFalse(policy.shouldRecover(30001))
  }

  @Test
  fun liveControllerWithDeadVpnDoesNotMaskFailure() {
    val policy = BydRecoveryPolicy(0)
    for (now in 10000L..80000L step 10000) {
      policy.heartbeat(now, false)
      assertFalse(policy.shouldRecover(now))
    }
    policy.heartbeat(90000, false)
    assertTrue(policy.shouldRecover(90000))
  }

  @Test
  fun healthyVpnResetsRecoveryAndBackoff() {
    val policy = BydRecoveryPolicy(0)
    assertTrue(policy.shouldRecover(30000))
    assertTrue(policy.shouldRecover(60000))
    policy.heartbeat(61000, true)
    assertFalse(policy.shouldRecover(61000))
    assertTrue(policy.shouldRecover(91000))
    assertTrue(policy.shouldRecover(121000))
  }

  @Test
  fun retriesContinueWithFiveMinuteMaximumDelay() {
    val policy = BydRecoveryPolicy(0)
    for (now in listOf(30000L, 60000L, 120000L, 240000L, 480000L, 780000L, 1080000L)) {
      assertTrue(policy.shouldRecover(now))
      assertFalse(policy.shouldRecover(now + 1))
    }
  }
}
