// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.security.MessageDigest

/** Starts our own service when BYD blocks a cold receiver; it never creates a window. */
class BydRecoveryActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    try {
      if (intent.action != "$packageName.BYD_RECOVER") return
      val token = intent.getStringExtra("token") ?: return
      if (!MessageDigest.isEqual(token.toByteArray(), BydPreferences.token(this).toByteArray()))
          return
      BydRecoveryReceiver.resumeVpn(this)
    } catch (e: Exception) {
      Log.w("Tailpark", "VPN recovery activity failed: ${e.javaClass.simpleName}")
    } finally {
      // Theme.NoDisplay requires finishing before onResume, including rejection/error paths.
      finish()
    }
  }
}
