// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class BydIdlePreferencesTest {
  private val values =
      mutableMapOf<String, Any>(
          "wanted" to true,
          "enabled" to true,
          "idle_timeout_minutes" to 120,
          "idle_generation" to 1L,
      )
  private val context = mock<Context>()
  private val prefs = mock<SharedPreferences>()
  private val editor = mock<SharedPreferences.Editor>()
  private var commitAction: () -> Boolean = { true }
  private var commits = 0
  private val timeout = 120 * 60000L

  init {
    whenever(context.getSharedPreferences("byd_parked", Context.MODE_PRIVATE)).thenReturn(prefs)
    whenever(prefs.getBoolean(any(), any())).thenAnswer {
      values[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1)
    }
    whenever(prefs.getInt(any(), any())).thenAnswer {
      values[it.getArgument<String>(0)] ?: it.getArgument<Int>(1)
    }
    whenever(prefs.getLong(any(), any())).thenAnswer {
      values[it.getArgument<String>(0)] ?: it.getArgument<Long>(1)
    }
    whenever(prefs.edit()).thenReturn(editor)
    whenever(editor.putBoolean(any(), any())).thenAnswer {
      values[it.getArgument<String>(0)] = it.getArgument<Boolean>(1)
      editor
    }
    whenever(editor.putLong(any(), any())).thenAnswer {
      values[it.getArgument<String>(0)] = it.getArgument<Long>(1)
      editor
    }
    whenever(editor.commit()).thenAnswer {
      commits++
      commitAction()
    }
  }

  private fun confirm(activity: () -> Long = { timeout }, power: () -> Int? = { 0 }): Boolean =
      BydPreferences.confirmIdleExpiry(context, 1, timeout, activity, power)

  @Test
  fun confirmedIdlePersistsTerminalStateAndRetainsWantedVpn() {
    assertTrue(confirm())
    assertTrue(BydPreferences.idleExpired(context))
    assertFalse(BydPreferences.shouldResume(context))
    assertTrue(BydPreferences.wantsVpn(context))
    assertEquals(1, commits)
  }

  @Test
  fun transferArrivingDuringDurableCommitRollsBackBeforeAcknowledgement() {
    var idle = timeout
    commitAction = {
      idle = 0
      true
    }
    assertFalse(confirm(activity = { idle }))
    assertFalse(BydPreferences.idleExpired(context))
    assertTrue(BydPreferences.shouldResume(context))
    assertTrue(BydPreferences.wantsVpn(context))
    assertEquals(2, commits)
  }

  @Test
  fun drivingDuringHandshakeOrUnknownReporterDefersSleep() {
    var powerReads = 0
    assertFalse(confirm(power = { if (powerReads++ == 0) 0 else 1 }))
    assertFalse(BydPreferences.idleExpired(context))
    assertFalse(confirm(activity = { -1 }))
    assertFalse(BydPreferences.idleExpired(context))
  }

  @Test
  fun failedCommitDoesNotStopInMemoryController() {
    commitAction = { false }
    assertFalse(confirm())
    assertFalse(BydPreferences.idleExpired(context))
    assertTrue(BydPreferences.shouldResume(context))
  }

  @Test
  fun staleTimerGenerationAndKeepAwakeCannotExpire() {
    values["idle_generation"] = 2L
    assertFalse(confirm())
    values["idle_generation"] = 1L
    values["idle_keep_awake"] = true
    assertFalse(confirm())
    assertEquals(0, commits)
  }

  @Test
  fun expirySurvivesParkedRestartAndOnlyConfirmedDrivingResetsIt() {
    values["idle_expired"] = true
    BydPreferences.resetIdleIfDriving(context) { null }
    assertTrue(BydPreferences.idleExpired(context))
    BydPreferences.resetIdleIfDriving(context) { 0 }
    assertTrue(BydPreferences.idleExpired(context))
    BydPreferences.resetIdleIfDriving(context) { 1 }
    assertFalse(BydPreferences.idleExpired(context))
    assertTrue(BydPreferences.wantsVpn(context))
    assertEquals(2L, values["idle_generation"])
  }
}
