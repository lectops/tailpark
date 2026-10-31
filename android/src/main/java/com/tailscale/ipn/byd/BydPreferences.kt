// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow

object BydPreferences {
  val enabled = MutableStateFlow(true)
  val keepWifi = MutableStateFlow(true)
  val idleTimeoutMinutes = MutableStateFlow(0)
  val keepAwake = MutableStateFlow(false)
  val idleStatus = MutableStateFlow("No inactivity timeout")
  val status = MutableStateFlow("Connect Tailscale to enable parked connectivity")

  fun prefs(context: Context): SharedPreferences =
      context.getSharedPreferences("byd_parked", Context.MODE_PRIVATE)

  fun initialize(context: Context) {
    enabled.value = prefs(context).getBoolean("enabled", true)
    keepWifi.value = prefs(context).getBoolean("keep_wifi", true)
    idleTimeoutMinutes.value = prefs(context).getInt("idle_timeout_minutes", 0)
    keepAwake.value = prefs(context).getBoolean("idle_keep_awake", false)
    if (idleExpired(context)) {
      idleStatus.value = "Sleep eligible · remote wake is not guaranteed"
      status.value = "Parked access ended after inactivity"
    }
  }

  fun setEnabled(context: Context, value: Boolean) {
    prefs(context).edit().putBoolean("enabled", value).commit()
    enabled.value = value
    if (!value) BydHelperConnection.stopAsync(context)
  }

  fun setKeepWifi(context: Context, value: Boolean) {
    prefs(context).edit().putBoolean("keep_wifi", value).commit()
    keepWifi.value = value
  }

  fun setWanted(context: Context, value: Boolean) {
    prefs(context).edit().putBoolean("wanted", value).commit()
    if (!value) BydHelperConnection.stopAsync(context)
  }

  fun wantsVpn(context: Context): Boolean = prefs(context).getBoolean("wanted", false)

  @Synchronized
  fun shouldResume(context: Context): Boolean =
      prefs(context).getBoolean("enabled", true) && wantsVpn(context) && !idleExpired(context)

  @Synchronized
  fun idleExpired(context: Context): Boolean = prefs(context).getBoolean("idle_expired", false)

  @Synchronized
  fun setIdleTimeout(context: Context, minutes: Int) {
    require(minutes in listOf(0, 30, 60, 120, 240, 480))
    check(prefs(context).edit().putInt("idle_timeout_minutes", minutes).commit())
    idleTimeoutMinutes.value = minutes
    resetIdle(context)
  }

  @Synchronized
  fun setKeepAwake(context: Context, value: Boolean) {
    check(prefs(context).edit().putBoolean("idle_keep_awake", value).commit())
    keepAwake.value = value
    resetIdle(context)
  }

  @Synchronized
  fun resetIdle(context: Context) {
    val preferences = prefs(context)
    check(
        preferences
            .edit()
            .putBoolean("idle_expired", false)
            .putLong("idle_generation", preferences.getLong("idle_generation", 0) + 1)
            .commit()
    )
  }

  /** An expired session never revives merely because the process or device restarted. */
  fun resetIdleIfDriving(
      context: Context,
      readPower: () -> Int? = { BydPlatform(context).powerLevel() },
  ) {
    val preferences = prefs(context)
    if (!idleExpired(context) && !preferences.getBoolean("idle_keep_awake", false)) return
    val power = runCatching { readPower() }.getOrNull()
    if (power != null && power > 0) {
      synchronized(this) {
        check(preferences.edit().putBoolean("idle_keep_awake", false).commit())
        keepAwake.value = false
        resetIdle(context)
      }
    }
  }

  @Synchronized
  fun confirmIdleExpiry(
      context: Context,
      generation: Long,
      timeoutMs: Long,
      readActivity: () -> Long,
      readPower: () -> Int? = { BydPlatform(context).powerLevel() },
  ): Boolean {
    val preferences = prefs(context)
    if (
        generation != preferences.getLong("idle_generation", 0) ||
            timeoutMs <= 0 ||
            timeoutMs != preferences.getInt("idle_timeout_minutes", 0) * 60000L ||
            preferences.getBoolean("idle_keep_awake", false) ||
            !shouldResume(context) ||
            runCatching { readActivity() }.getOrDefault(-1L) < timeoutMs ||
            runCatching { readPower() }.getOrNull() != 0
    )
        return false

    // Readers share this monitor: no controller can stop on a transient terminal
    // value while the post-commit activity/power check is still in progress.
    val committed = preferences.edit().putBoolean("idle_expired", true).commit()
    val stillIdle =
        committed &&
            runCatching { readPower() }.getOrNull() == 0 &&
            runCatching { readActivity() }.getOrDefault(-1L) >= timeoutMs
    if (!stillIdle) {
      if (generation == preferences.getLong("idle_generation", 0)) {
        preferences.edit().putBoolean("idle_expired", false).commit()
      }
      return false
    }
    return true
  }

  @Synchronized
  fun token(context: Context): String {
    val prefs = prefs(context)
    prefs.getString("helper_token", null)?.let {
      return it
    }
    val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    check(prefs.edit().putString("helper_token", token).commit())
    return token
  }
}
