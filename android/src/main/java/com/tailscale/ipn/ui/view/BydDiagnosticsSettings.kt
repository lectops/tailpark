// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.view

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.byd.BydDiagnosticTimeline
import com.tailscale.ipn.byd.BydDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BydDiagnosticsSettings(startupSummary: Boolean = false) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var visible by remember { mutableStateOf(false) }
  var report by remember { mutableStateOf<BydDiagnostics.Report?>(null) }
  var busy by remember { mutableStateOf(false) }
  var confirmClear by remember { mutableStateOf(false) }
  val exportLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri
        ->
        val snapshot = report
        if (uri != null && snapshot != null) {
          scope.launch {
            val saved =
                withContext(Dispatchers.IO) {
                  runCatching {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                      it.write(
                          BydDiagnosticTimeline.export(snapshot.events).toByteArray(Charsets.UTF_8)
                      )
                    }
                  }
                }
            Toast.makeText(
                    context,
                    if (saved.isSuccess) R.string.byd_diagnostics_saved
                    else R.string.byd_diagnostics_export_failed,
                    Toast.LENGTH_LONG,
                )
                .show()
          }
        }
      }

  suspend fun refresh() {
    busy = true
    report =
        withContext(Dispatchers.IO) {
          BydDiagnostics.syncHelper(context)
          BydDiagnostics.read(context)
        }
    busy = false
  }

  if (startupSummary) {
    LaunchedEffect(Unit) { refresh() }
  }
  if (
      !startupSummary ||
          report?.events?.let { BydDiagnosticTimeline.hasPreviousObservations(it) } == true
  ) {
    Setting.Text(
        R.string.byd_diagnostics_title,
        subtitle =
            if (startupSummary) BydDiagnosticTimeline.summary(report?.events ?: emptyList())
            else stringResource(R.string.byd_diagnostics_subtitle),
        onClick = { visible = true },
    )
  }

  if (visible) {
    LaunchedEffect(Unit) { refresh() }
    AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text(stringResource(R.string.byd_diagnostics_title)) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(BydDiagnosticTimeline.summary(report?.events ?: emptyList()))
            report?.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (report?.clearPending == true) {
              Text(stringResource(R.string.byd_diagnostics_clear_pending))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              TextButton(onClick = { scope.launch { refresh() } }, enabled = !busy) {
                Text(stringResource(R.string.byd_diagnostics_refresh))
              }
              TextButton(
                  onClick = { exportLauncher.launch("tailpark-diagnostics.txt") },
                  enabled = !busy && report != null,
              ) {
                Text(stringResource(R.string.byd_diagnostics_export))
              }
              TextButton(onClick = { confirmClear = true }, enabled = !busy) {
                Text(stringResource(R.string.byd_diagnostics_clear))
              }
            }
            val events = report?.events ?: emptyList()
            val interrupted = BydDiagnosticTimeline.interruptedSessions(events)
            val ends = events.groupBy { it.session }.mapValues { it.value.last().identity() }
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              items(events.reversed(), key = { it.identity() }) { event ->
                Column {
                  Text(
                      BydDiagnosticTimeline.describe(event),
                      style = MaterialTheme.typography.bodyMedium,
                  )
                  Text(
                      "${BydDiagnosticTimeline.timestamp(event)} · ${event.source.name.lowercase()} · " +
                          "boot ${if (event.boot < 0) "unknown" else event.boot} · +${event.elapsedMs / 1000}s",
                      style = MaterialTheme.typography.bodySmall,
                  )
                  if (event.session in interrupted && ends[event.session] == event.identity()) {
                    Text(
                        stringResource(R.string.byd_diagnostics_gap),
                        style = MaterialTheme.typography.bodySmall,
                    )
                  }
                }
              }
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { visible = false }) {
            Text(stringResource(R.string.byd_diagnostics_close))
          }
        },
    )
  }

  if (confirmClear) {
    AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.byd_diagnostics_clear_title)) },
        text = { Text(stringResource(R.string.byd_diagnostics_clear_message)) },
        confirmButton = {
          TextButton(
              onClick = {
                confirmClear = false
                scope.launch {
                  busy = true
                  val cleared =
                      withContext(Dispatchers.IO) { runCatching { BydDiagnostics.clear(context) } }
                  if (cleared.isFailure) {
                    Toast.makeText(
                            context,
                            R.string.byd_diagnostics_clear_failed,
                            Toast.LENGTH_LONG,
                        )
                        .show()
                  }
                  refresh()
                }
              }
          ) {
            Text(stringResource(R.string.byd_diagnostics_clear))
          }
        },
        dismissButton = {
          TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
        },
    )
  }
}
