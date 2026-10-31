// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd.update

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
import androidx.compose.ui.platform.LocalContext
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.view.Setting

@Composable
fun UpdateSettings() {
  val context = LocalContext.current
  val state by UpdateManager.state.collectAsState()
  var show by remember { mutableStateOf(false) }
  Lists.SectionDivider()
  Setting.Text(R.string.tailpark_updates, subtitle = state.message, onClick = { show = true })
  Lists.ItemDivider()
  Setting.Switch(
      R.string.tailpark_auto_update,
      subtitle =
          "Download and try installing stable updates only with fresh ACC-off and screen-off readings. Android may require confirmation. Updates briefly interrupt connectivity.",
      isOn = state.automatic,
      onToggle = { UpdateManager.setAutomatic(context, it) },
  )
  if (show) {
    AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("Tailpark updates") },
        text = {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(state.message)
            state.progress?.let { Text("Downloaded $it%") }
            state.release?.let {
              Text("\n${it.version}\n\n${it.notes.ifBlank { "No release notes provided." }}")
            }
          }
        },
        confirmButton = {
          Column {
            state.release?.let {
              TextButton(
                  enabled = !state.busy,
                  onClick = {
                    if (state.ready) UpdateManager.installManually(context)
                    else UpdateManager.download(context)
                  },
              ) {
                Text(if (state.ready) "Install update" else "Download update")
              }
            }
            TextButton(enabled = !state.busy, onClick = { UpdateManager.check(context) }) {
              Text("Check for updates")
            }
          }
        },
        dismissButton = { TextButton(onClick = { show = false }) { Text("Close") } },
    )
  }
}
