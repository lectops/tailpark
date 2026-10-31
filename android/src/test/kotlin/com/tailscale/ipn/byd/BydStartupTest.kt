// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import android.content.Context
import android.content.SharedPreferences
import com.tailscale.ipn.UninitializedApp
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class BydStartupTest {
  private val values = mutableMapOf("wanted" to true, "enabled" to true)
  private val editor = mock<SharedPreferences.Editor>()
  private val prefs = mock<SharedPreferences>()
  private val context = mock<Context>()
  private val app = mock<UninitializedApp>()
  private var initialized = false
  private var helperStopped = false

  init {
    whenever(context.getSharedPreferences("byd_parked", Context.MODE_PRIVATE)).thenReturn(prefs)
    whenever(prefs.getBoolean(any(), any())).thenAnswer {
      values[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1)
    }
    whenever(prefs.edit()).thenReturn(editor)
    whenever(editor.putBoolean(any(), any())).thenAnswer {
      values[it.getArgument<String>(0)] = it.getArgument(1)
      editor
    }
    whenever(editor.commit()).thenReturn(true)
  }

  private fun recover(prepared: Boolean = true): String =
      BydRecoveryReceiver.resumeVpn(
          context,
          app,
          prepared,
          initialize = { initialized = true },
          stopHelper = { helperStopped = true },
      )

  @Test
  fun bootReconnectsEvenWhenParkedConnectivityIsOff() {
    values["enabled"] = false
    whenever(app.isAbleToStartVPN()).thenReturn(true)
    assertFalse(BydPreferences.shouldResume(context))
    assertEquals("RECOVERING", recover())
    verify(app).startVPN()
    assertFalse(initialized)
  }

  @Test
  fun cachedLoginNotReadyInitializesWithoutForgettingConnection() {
    whenever(app.isAbleToStartVPN()).thenReturn(false)
    assertEquals("WAITING_LOGIN", recover())
    assertTrue(initialized)
    assertTrue(BydPreferences.wantsVpn(context))
    verify(app, never()).startVPN()
  }

  @Test
  fun expiredParkedSessionDoesNotResumeOnBootOrBackendReadiness() {
    values["idle_expired"] = true
    assertEquals("IDLE_EXPIRED", recover())
    assertTrue(BydPreferences.wantsVpn(context))
    assertFalse(initialized)
    verifyNoInteractions(app)
  }

  @Test
  fun explicitDisconnectPreventsBootReconnectAndBackendInitialization() {
    values["wanted"] = false
    assertEquals("DISABLED", recover())
    assertTrue(helperStopped)
    assertFalse(initialized)
    verifyNoInteractions(app)
  }
}
