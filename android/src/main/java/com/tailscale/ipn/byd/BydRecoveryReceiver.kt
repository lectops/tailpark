// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Base64
import com.tailscale.ipn.UninitializedApp
import java.security.MessageDigest

/** Boot/update recovery plus a DUMP-protected entrypoint for our shell helper. */
class BydRecoveryReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == "${context.packageName}.BYD_HELPER_CHECK") {
      val token = intent.getStringExtra("token")
      resultData =
          if (
              token == null ||
                  !MessageDigest.isEqual(
                      token.toByteArray(),
                      BydPreferences.token(context).toByteArray(),
                  )
          ) {
            "REJECTED"
          } else if (!BydPreferences.shouldResume(context)) {
            "DISABLED"
          } else if (BydPreferences.prefs(context).getBoolean("keep_wifi", true)) {
            "READY_WIFI"
          } else {
            "READY_CELLULAR"
          }
      resultCode = -1
      return
    }
    if (intent.action == "${context.packageName}.BYD_BOOTSTRAP") {
      if (BydPreferences.shouldResume(context)) {
        resultData =
            Base64.encodeToString(
                BydHelperConnection.launchCommand(context).toByteArray(),
                Base64.NO_WRAP,
            )
        resultCode = -1
      } else {
        resultData = "CONNECT_FIRST"
        resultCode = 0
      }
      return
    }
    if (intent.action == "${context.packageName}.BYD_RECOVER") {
      val token = intent.getStringExtra("token")
      if (
          token == null ||
              !MessageDigest.isEqual(
                  token.toByteArray(),
                  BydPreferences.token(context).toByteArray(),
              )
      ) {
        resultData = "REJECTED"
        resultCode = -1
        return
      }
    } else if (
        intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
    ) {
      return
    }
    resultData = resumeVpn(context)
    resultCode = -1
  }

  companion object {
    internal fun resumeVpn(context: Context): String {
      if (!BydPreferences.shouldResume(context)) {
        BydHelperConnection.stopAsync(context)
        return "DISABLED"
      }
      val app = UninitializedApp.get()
      if (app.isAbleToStartVPN() && VpnService.prepare(context) == null) {
        app.startVPN()
        return "RECOVERING"
      }
      // Permission or login was revoked. Do not keep a power helper running without a usable VPN.
      BydPreferences.setWanted(context, false)
      return "UNAVAILABLE"
    }
  }
}
