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
      val token = intent.getStringExtra("token") ?: return
      if (!MessageDigest.isEqual(token.toByteArray(), BydPreferences.token(context).toByteArray()))
          return
    } else if (
        intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
    ) {
      return
    }
    if (!BydPreferences.shouldResume(context)) {
      BydHelperConnection.stopAsync(context)
      return
    }
    val app = UninitializedApp.get()
    if (app.isAbleToStartVPN() && VpnService.prepare(context) == null) app.startVPN()
    else {
      // Permission or login was revoked. Do not keep a power helper running without a usable VPN.
      BydPreferences.setWanted(context, false)
    }
  }
}
