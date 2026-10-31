// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tailscale.ipn.App
import com.tailscale.ipn.byd.BydPreferences
import com.tailscale.ipn.ui.util.Lists

@Composable
fun BydIdleSettings(enabled: Boolean) {
  val minutes by BydPreferences.idleTimeoutMinutes.collectAsState()
  val keepAwake by BydPreferences.keepAwake.collectAsState()
  val status by BydPreferences.idleStatus.collectAsState()
  var choose by remember { mutableStateOf(false) }
  Lists.ItemDivider()
  Setting.Text(
      title = "Let the car sleep after inactivity",
      subtitle =
          if (minutes == 0) "Never · parked access has no timeout"
          else "$minutes minutes without remote activity · $status",
      enabled = enabled,
      onClick = { choose = true },
  )
  if (minutes > 0) {
    Lists.ItemDivider()
    Setting.Switch(
        title = "Keep awake for this session",
        subtitle =
            "For quiet sessions and slow transfers. Clears when driving resumes; remote wake after sleep is not guaranteed.",
        isOn = keepAwake,
        enabled = enabled,
        onToggle = {
          val app = App.get()
          BydPreferences.setKeepAwake(app, it)
          if (BydPreferences.shouldResume(app)) app.startVPN()
        },
    )
  }
  if (choose) {
    AlertDialog(
        onDismissRequest = { choose = false },
        title = { Text("Inactivity timeout") },
        text = {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                "Application traffic renews the timer. Quiet sessions, small probes and very slow transfers may not count. This releases Tailpark's locks so the car can sleep; it is not a battery cutoff."
            )
            for (value in listOf(0, 30, 60, 120, 240, 480)) {
              TextButton(
                  onClick = {
                    val app = App.get()
                    BydPreferences.setIdleTimeout(app, value)
                    if (BydPreferences.shouldResume(app)) app.startVPN()
                    choose = false
                  }
              ) {
                Text(
                    if (value == 0) "Never"
                    else if (value < 60) "$value minutes"
                    else if (value == 60) "1 hour" else "${value / 60} hours"
                )
              }
            }
          }
        },
        confirmButton = { TextButton(onClick = { choose = false }) { Text("Cancel") } },
    )
  }
}
