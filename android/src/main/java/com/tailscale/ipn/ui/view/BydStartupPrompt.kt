// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.view

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.R
import com.tailscale.ipn.byd.BydPreferences
import com.tailscale.ipn.byd.BydStartupSettings
import kotlinx.coroutines.awaitCancellation

/**
 * BYD exposes no confirmed readable startup-status API; confirmation is explicitly user supplied.
 */
@Composable
fun BydStartupPrompt(loggedIn: Boolean) {
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var visible by remember { mutableStateOf(false) }
  val prefs = remember { BydPreferences.prefs(context) }
  var confirmedVersion by remember { mutableStateOf(prefs.getInt("startup_confirmed_version", -1)) }
  LaunchedEffect(loggedIn, lifecycle) {
    if (!loggedIn) {
      visible = false
      return@LaunchedEffect
    }
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      // Ask once each time the app returns to the foreground, including after BYD settings.
      visible = true
      awaitCancellation()
    }
  }
  if (!visible || !loggedIn) return
  AlertDialog(
      onDismissRequest = { visible = false },
      title = { Text(stringResource(R.string.byd_startup_prompt_title)) },
      text = {
        Text(
            stringResource(
                if (confirmedVersion == BuildConfig.VERSION_CODE)
                    R.string.byd_startup_prompt_recheck
                else if (confirmedVersion >= 0) R.string.byd_startup_prompt_updated
                else R.string.byd_startup_prompt_first,
            )
        )
      },
      confirmButton = {
        TextButton(onClick = { BydStartupSettings.open(context) }) {
          Text(stringResource(R.string.byd_startup_open_settings))
        }
      },
      dismissButton = {
        TextButton(
            onClick = {
              prefs.edit().putInt("startup_confirmed_version", BuildConfig.VERSION_CODE).apply()
              confirmedVersion = BuildConfig.VERSION_CODE
              visible = false
            }
        ) {
          Text(stringResource(R.string.byd_startup_confirm_off))
        }
      },
  )
}
