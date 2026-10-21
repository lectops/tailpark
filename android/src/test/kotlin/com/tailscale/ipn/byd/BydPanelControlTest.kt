// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import org.junit.Assert.*
import org.junit.Test

class BydPanelControlTest {
  private class Device : BydPanelControl.Device {
    var power: Int? = 0
    var screen: Int? = 1
    var ignorePlain = false
    var supportsLock = true
    var failAcquire = false
    var failRelease = false
    val calls = mutableListOf<String>()

    override fun powerLevel() = power

    override fun screenStatus() = screen

    override fun supportsDisplayLock() = supportsLock

    override fun plainBacklight(on: Boolean): Boolean {
      calls += if (on) "plain-on" else "plain-off"
      if (!ignorePlain && screen != null) screen = if (on) 1 else 0
      return true
    }

    override fun lockedBacklight(on: Boolean) {
      calls += if (on) "unlock" else "lock"
      if (on && failRelease) throw IllegalStateException("release failed")
      if (!on && failAcquire) throw IllegalStateException("Binder reply lost")
      screen = if (on) 1 else 0
    }
  }

  @Test
  fun alreadyDarkDoesNotWriteOrAcquireALock() {
    val device = Device().apply { screen = 0 }
    BydPanelControl(device).darken()
    assertTrue(device.calls.isEmpty())
  }

  @Test
  fun ordinaryBacklightIsVerifiedAndRestoredOnAccOn() {
    val device = Device()
    val panel = BydPanelControl(device)
    panel.darken()
    panel.darken()
    device.power = 2
    assertTrue(panel.restore(false))
    assertEquals(listOf("plain-off", "plain-on"), device.calls)
    assertEquals(1, device.screen)
  }

  @Test
  fun unknownOrDrivingAccNeverDarkens() {
    val device = Device()
    val panel = BydPanelControl(device)
    device.power = null
    panel.darken()
    device.power = 2
    panel.darken()
    assertTrue(device.calls.isEmpty())
  }

  @Test
  fun verifiedFailureEscalatesOnceAndAlwaysReleasesOwnedLock() {
    val device = Device().apply { ignorePlain = true }
    val panel = BydPanelControl(device)
    panel.darken()
    device.screen = 1
    runCatching { panel.darken() }
    device.power = 2
    assertTrue(panel.restore(false))
    assertEquals(1, device.calls.count { it == "lock" })
    assertEquals(1, device.calls.count { it == "unlock" })
    assertEquals(1, device.screen)
  }

  @Test
  fun missingStatusNeverJustifiesVendorLockEscalation() {
    val device = Device().apply { screen = null }
    BydPanelControl(device).darken()
    assertEquals(listOf("plain-off"), device.calls)
  }

  @Test
  fun ambiguousAcquireFailureKeepsReleaseReachableWithoutStackingLocks() {
    val device =
        Device().apply {
          ignorePlain = true
          failAcquire = true
        }
    val panel = BydPanelControl(device)
    assertTrue(runCatching { panel.darken() }.isFailure)
    assertTrue(runCatching { panel.darken() }.isFailure)
    device.power = 2
    panel.restore(false)
    assertEquals(1, device.calls.count { it == "lock" })
    assertEquals(1, device.calls.count { it == "unlock" })
  }

  @Test
  fun failedReleaseCanBeRetried() {
    val device = Device().apply { ignorePlain = true }
    val panel = BydPanelControl(device)
    panel.darken()
    device.power = 2
    device.failRelease = true
    assertTrue(runCatching { panel.restore(false) }.isFailure)
    device.failRelease = false
    assertTrue(panel.restore(false))
    assertEquals(2, device.calls.count { it == "unlock" })
  }

  @Test
  fun unknownAccDefersPlainRestorationUntilItIsKnown() {
    val device = Device()
    val panel = BydPanelControl(device)
    panel.darken()
    device.power = null
    assertFalse(panel.restore(false))
    device.power = 2
    assertTrue(panel.restore(false))
    assertEquals(listOf("plain-off", "plain-on"), device.calls)
  }

  @Test
  fun finishingWhileParkedDoesNotWakeThePanel() {
    val device = Device()
    val panel = BydPanelControl(device)
    panel.darken()
    assertTrue(panel.restore(true))
    assertEquals(listOf("plain-off"), device.calls)
  }
}
