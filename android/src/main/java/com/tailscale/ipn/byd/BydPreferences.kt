// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow

object BydPreferences {
  val enabled = MutableStateFlow(true)
  val keepWifi = MutableStateFlow(true)
  val status = MutableStateFlow("Connect Tailscale to enable parked connectivity")

  fun prefs(context: Context): SharedPreferences =
      context.getSharedPreferences("byd_parked", Context.MODE_PRIVATE)

  fun initialize(context: Context) {
    enabled.value = prefs(context).getBoolean("enabled", true)
    keepWifi.value = prefs(context).getBoolean("keep_wifi", true)
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

  fun shouldResume(context: Context): Boolean =
      prefs(context).let { it.getBoolean("enabled", true) && it.getBoolean("wanted", false) }

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
