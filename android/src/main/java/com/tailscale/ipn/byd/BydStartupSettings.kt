// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.tailscale.ipn.R

object BydStartupSettings {
  fun open(context: Context) {
    val intent =
        Intent(Intent.ACTION_MAIN).apply {
          addCategory(Intent.CATEGORY_LAUNCHER)
          setPackage("com.byd.appstartmanagement")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    try {
      context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
      Toast.makeText(context, R.string.byd_startup_unavailable, Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
      Toast.makeText(context, R.string.byd_startup_unavailable, Toast.LENGTH_LONG).show()
    }
  }
}
