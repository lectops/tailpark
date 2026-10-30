// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import org.junit.Assert.*
import org.junit.Test

class ParkedPowerStateTest {
  private class Actions : ParkedPowerState.Actions {
    val calls = mutableListOf<String>()
    var denyWake = false
    var denyWifi = false
    var restores = 0

    override fun wake() {
      calls += "wake"
      if (denyWake) throw IllegalStateException("DEVICE_POWER denied")
    }

    override fun backlightOff() {
      calls += "dark"
    }

    override fun enableWifi() {
      calls += "wifi"
      if (denyWifi) throw IllegalStateException("Wi-Fi denied")
    }

    override fun restoreDisplay(): Boolean {
      restores++
      return true
    }
  }

  @Test
  fun slowPollingKeepsWakeToDarkFastAndRestoresDisplayOnNextAccCheck() {
    val actions = Actions()
    val state = ParkedPowerState(actions)
    assertEquals(5000L, state.nextPollDelayMillis())
    state.tick(true, true, 2, true, 0)
    assertEquals(5000L, state.nextPollDelayMillis())
    state.tick(true, true, 0, true, 5000)
    assertEquals(500L, state.nextPollDelayMillis())
    state.tick(true, true, 0, false, 5500)
    assertEquals(500L, state.nextPollDelayMillis())
    state.tick(true, true, 0, true, 6000)
    assertEquals(listOf("wake", "dark"), actions.calls)
    assertEquals(5000L, state.nextPollDelayMillis())
    state.tick(true, true, 0, true, 11000)
    assertTrue(actions.calls.contains("wifi"))
    state.tick(true, true, 2, true, 16000)
    assertEquals(1, actions.restores)
    assertEquals(ParkedPowerState.Phase.DRIVING, state.phase())
  }

  @Test
  fun parkedSequenceWaitsThenWakesAndDarkens() {
    val actions = Actions()
    val state = ParkedPowerState(actions)
    state.tick(true, true, 0, true, 0)
    state.tick(true, true, 0, true, 500)
    assertTrue(actions.calls.isEmpty())
    state.tick(true, true, 0, false, 1000)
    assertEquals(listOf("wake"), actions.calls)
    state.tick(true, true, 0, true, 1499)
    assertEquals(listOf("wake"), actions.calls)
    state.tick(true, true, 0, true, 1500)
    assertEquals(listOf("wake", "dark"), actions.calls)
    state.tick(true, true, 0, true, 4000)
    assertEquals(listOf("wake", "dark", "wifi"), actions.calls)
    state.tick(true, true, 0, true, 4500)
    assertEquals(listOf("wake", "dark", "wifi", "dark"), actions.calls)
  }

  @Test
  fun accOnCancelsDelayedBacklightOff() {
    val actions = Actions()
    val state = ParkedPowerState(actions)
    state.tick(true, true, 0, false, 0)
    state.tick(true, true, 2, true, 500)
    state.tick(true, true, 2, true, 10000)
    assertEquals(listOf("wake"), actions.calls)
    assertEquals(ParkedPowerState.Phase.DRIVING, state.phase())
    assertEquals(1, actions.restores)
  }

  @Test
  fun disconnectCancelsParkedLoop() {
    val actions = Actions()
    val state = ParkedPowerState(actions)
    state.tick(true, true, 0, false, 0)
    state.tick(true, true, 0, true, 500)
    state.tick(false, true, 0, true, 4000)
    assertEquals(listOf("wake", "dark"), actions.calls)
    assertEquals(ParkedPowerState.Phase.OFF, state.phase())
    assertEquals(1, actions.restores)
  }

  @Test
  fun unavailablePowerNeverControlsTheDisplay() {
    val actions = Actions()
    val state = ParkedPowerState(actions)
    state.tick(true, true, null, false, 0)
    state.tick(true, true, null, false, 5000)
    assertTrue(actions.calls.isEmpty())
    state.tick(true, true, 0, false, 6000)
    state.tick(true, true, null, true, 6500)
    assertEquals(listOf("wake"), actions.calls)
  }

  @Test
  fun cellularModeNeverEnablesWifi() {
    val actions = Actions()
    val state = ParkedPowerState(actions)
    state.tick(true, false, 0, false, 0)
    state.tick(true, false, 0, true, 500)
    state.tick(true, false, 0, true, 5000)
    assertEquals(listOf("wake", "dark", "dark"), actions.calls)
  }

  @Test
  fun screenTimeoutDoesNotWakeUntilScreenActuallySleeps() {
    val actions = Actions()
    val state = ParkedPowerState(actions)
    state.tick(true, true, 0, true, 0)
    state.tick(true, true, 0, true, 40000)
    assertTrue(actions.calls.isEmpty())
    assertEquals(ParkedPowerState.Phase.TIMED_OUT, state.phase())
    state.tick(true, true, 0, false, 45000)
    assertEquals(listOf("wake"), actions.calls)
  }

  @Test
  fun permissionFailureRemainsVisible() {
    val actions = Actions().apply { denyWake = true }
    val state = ParkedPowerState(actions)
    state.tick(true, true, 0, false, 0)
    state.tick(true, true, 0, true, 500)
    assertEquals("DEVICE_POWER denied", state.error())
    assertEquals(listOf("wake"), actions.calls)
    assertEquals(ParkedPowerState.Phase.WAITING, state.phase())
  }

  @Test
  fun nextParkingCycleRunsTheSequenceAgain() {
    val actions = Actions()
    val state = ParkedPowerState(actions)
    state.tick(true, true, 0, false, 0)
    state.tick(true, true, 0, true, 500)
    state.tick(true, true, 2, true, 1000)
    state.tick(true, true, 0, false, 2000)
    assertEquals(listOf("wake", "dark", "wake"), actions.calls)
  }

  @Test
  fun wifiRecoveryContinuesThroughoutParking() {
    val actions = Actions()
    val state = ParkedPowerState(actions)
    state.tick(true, true, 0, false, 0)
    state.tick(true, true, 0, true, 3000)
    state.tick(true, true, 0, true, 32999)
    assertEquals(1, actions.calls.count { it == "wifi" })
    state.tick(true, true, 0, true, 33000)
    state.tick(true, true, 0, true, 63000)
    assertEquals(3, actions.calls.count { it == "wifi" })
  }

  @Test
  fun wifiFailureIsRetriedWithoutAHotLoopAndClearsAfterRecovery() {
    val actions = Actions().apply { denyWifi = true }
    val state = ParkedPowerState(actions)
    state.tick(true, true, 0, false, 0)
    state.tick(true, true, 0, true, 3000)
    state.tick(true, true, 0, true, 3500)
    assertEquals(1, actions.calls.count { it == "wifi" })
    assertEquals("Wi-Fi denied", state.error())
    actions.denyWifi = false
    state.tick(true, true, 0, true, 33000)
    assertNull(state.error())
    state.tick(true, false, 0, true, 63000)
    assertEquals(2, actions.calls.count { it == "wifi" })
  }

  @Test
  fun failedWakeDoesNotDarkenAndCanRecoverLater() {
    val actions = Actions().apply { denyWake = true }
    val state = ParkedPowerState(actions)
    state.tick(true, true, 0, false, 0)
    state.tick(true, true, 0, false, 4999)
    assertEquals(listOf("wake"), actions.calls)
    actions.denyWake = false
    state.tick(true, true, 0, false, 5000)
    state.tick(true, true, 0, true, 5500)
    assertEquals(listOf("wake", "wake", "dark"), actions.calls)
    assertNull(state.error())
  }
}
