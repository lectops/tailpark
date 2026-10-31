// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd.update

import com.tailscale.ipn.byd.BydUpdateInstallGate
import org.junit.Assert.*
import org.junit.Test

class UpdateInstallGateTest {
  @Test
  fun accTurningOnDuringTheFinalScreenReadDefersInstallation() {
    var power = 0
    var installed = false
    val result =
        BydUpdateInstallGate.execute(
            "COMMIT",
            { false },
            { power },
            {
              power = 1
              0
            },
            {
              installed = true
              true
            },
        )
    assertEquals("DEFERRED", result)
    assertFalse(installed)
  }

  @Test
  fun everyUnknownOrInUseStateAndOptOutDefersWithoutInvokingInstaller() {
    val states = listOf(0 to null, 0 to 1, null to 0, 1 to 0)
    for ((power, screen) in states) {
      assertEquals(
          "DEFERRED",
          BydUpdateInstallGate.execute(
              "COMMIT",
              { false },
              { power },
              { screen },
              {
                fail("must not install")
                false
              },
          ),
      )
    }
    assertEquals(
        "DEFERRED",
        BydUpdateInstallGate.execute(
            "CANCEL",
            { false },
            { 0 },
            { 0 },
            {
              fail("must not install")
              false
            },
        ),
    )
    assertEquals(
        "DEFERRED",
        BydUpdateInstallGate.execute(
            "COMMIT",
            { true },
            { 0 },
            { 0 },
            {
              fail("must not install")
              false
            },
        ),
    )
  }

  @Test
  fun stoppedSessionJustBeforeInstallAndFirmwareDenialHaveDistinctResults() {
    var stopped = false
    assertEquals(
        "DEFERRED",
        BydUpdateInstallGate.execute(
            "COMMIT",
            { stopped },
            { 0 },
            {
              stopped = true
              0
            },
            {
              fail("must not install")
              false
            },
        ),
    )
    assertEquals(
        "CONFIRMATION_REQUIRED",
        BydUpdateInstallGate.execute("COMMIT", { false }, { 0 }, { 0 }, { false }),
    )
    assertEquals(
        "INSTALLED",
        BydUpdateInstallGate.execute("COMMIT", { false }, { 0 }, { 0 }, { true }),
    )
  }
}
