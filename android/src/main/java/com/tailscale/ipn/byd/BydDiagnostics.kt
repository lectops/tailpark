// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.byd.BydFlightRecorder.Code
import com.tailscale.ipn.byd.BydFlightRecorder.Event
import com.tailscale.ipn.byd.BydFlightRecorder.Source
import com.tailscale.ipn.byd.BydFlightRecorder.Value
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Local observations only; neither the recorder nor export includes other application logs. */
object BydDiagnostics {
  private val executor =
      ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          ArrayBlockingQueue(64),
          { task -> Thread(task, "tailpark-diagnostics").apply { isDaemon = true } },
          { task, pool ->
            storageFailed = true
            pool.queue.poll()
            if (!pool.isShutdown) pool.queue.offer(task)
          },
      )
  private var recorder: BydFlightRecorder? = null
  @Volatile private var storageFailed = false
  @Volatile private var clearedAtElapsed = -1L
  private val syncLock = Any()

  @Synchronized
  private fun journal(context: Context): BydFlightRecorder =
      recorder
          ?: BydFlightRecorder(
                  File(context.filesDir, "diagnostics/events.log"),
                  Source.APP,
                  BuildConfig.VERSION_NAME,
                  bootCount(context),
                  System.currentTimeMillis(),
                  SystemClock.elapsedRealtime(),
              )
              .also { recorder = it }

  @JvmStatic
  fun bootCount(context: Context): Long = runCatching {
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()
  }
      .getOrDefault(-1)

  fun initialize(context: Context) {
    val app = context.applicationContext
    executor.execute { safely { journal(app) } }
  }

  fun broadcast(context: Context, code: Code, finished: () -> Unit) {
    val app = context.applicationContext
    val wall = System.currentTimeMillis()
    val elapsed = SystemClock.elapsedRealtime()
    Thread(
            {
              try {
                safely { journal(app).record(code, Value.STARTED, wall, elapsed) }
              } finally {
                finished()
              }
            },
            "tailpark-diagnostics-broadcast",
        )
        .start()
  }

  fun observe(context: Context, code: Code, value: Value) {
    val app = context.applicationContext
    val wall = System.currentTimeMillis()
    val elapsed = SystemClock.elapsedRealtime()
    executor.execute {
      if (elapsed >= clearedAtElapsed) safely { journal(app).observe(code, value, wall, elapsed) }
    }
  }

  fun network(context: Context, network: Network?) {
    val value =
        if (network == null) Value.NO_NETWORK
        else {
          val caps = runCatching {
            context
                .getSystemService(ConnectivityManager::class.java)
                ?.getNetworkCapabilities(network)
          }
              .getOrNull()
          when {
            caps == null -> Value.UNKNOWN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Value.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Value.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Value.ETHERNET
            else -> Value.OTHER
          }
        }
    observe(context, Code.NETWORK, value)
  }

  /** Called by the existing helper exchange, never by a new background polling job. */
  @SuppressLint("UseKtx") // Checked, durable commits make imports idempotent after a process kill.
  fun syncHelper(context: Context) =
      synchronized(syncLock) {
        val prefs = context.getSharedPreferences("byd_diagnostics", Context.MODE_PRIVATE)
        try {
          if (prefs.getBoolean("clear_pending", false)) {
            val reply = BydHelperConnection.request(context, "CLEAR_DIAGNOSTICS")
            check(reply.optBoolean("cleared"))
            check(
                prefs.edit().putBoolean("clear_pending", false).putLong("helper_cursor", 0).commit()
            )
          }
          var cursor = prefs.getLong("helper_cursor", 0)
          var journalId = prefs.getString("helper_journal", "") ?: ""
          repeat(4) {
            val reply = BydHelperConnection.request(context, "DIAGNOSTICS $cursor")
            check(reply.optInt("diagnostics") == 1)
            check(!reply.optBoolean("unavailable"))
            val remoteId = reply.getString("journal")
            check(remoteId.matches(Regex("[a-f0-9]{32}")))
            if (remoteId != journalId) {
              journalId = remoteId
              cursor = 0
              return@repeat
            }
            val latest = reply.getLong("last")
            if (latest < cursor) {
              // Runtime storage was removed/recreated; session IDs still prevent duplicate events.
              cursor = 0
              return@repeat
            }
            val entries = reply.getJSONArray("events")
            check(entries.length() <= 10)
            for (i in 0 until entries.length()) {
              val event = Event.parse(entries.getString(i)) ?: error("Invalid diagnostics event")
              check(event.source == Source.HELPER && event.sequence > cursor)
              if (cursor > 0 && event.sequence > cursor + 1) {
                journal(context)
                    .observe(
                        Code.HISTORY_LIMITED,
                        Value.UNAVAILABLE,
                        System.currentTimeMillis(),
                        SystemClock.elapsedRealtime(),
                    )
              }
              journal(context).importEvent(event)
              cursor = event.sequence
            }
            check(
                prefs
                    .edit()
                    .putLong("helper_cursor", cursor)
                    .putString("helper_journal", journalId)
                    .commit()
            )
            if (!reply.optBoolean("more")) return@synchronized
          }
        } catch (_: Exception) {
          // Diagnostics must never affect connection recovery. A later exchange retries the cursor.
        }
      }

  data class Report(val events: List<Event>, val warning: String?, val clearPending: Boolean)

  fun read(context: Context): Report =
      synchronized(syncLock) {
        val events = runCatching {
          journal(context).snapshot()
        }
            .getOrElse {
              storageFailed = true
              emptyList()
            }
        Report(
            BydDiagnosticTimeline.ordered(events),
            if (storageFailed) "Some observations could not be saved on this device." else null,
            context
                .getSharedPreferences("byd_diagnostics", Context.MODE_PRIVATE)
                .getBoolean("clear_pending", false),
        )
      }

  /** Durable pending clear prevents disconnected helper history from reappearing on reconnect. */
  @SuppressLint("UseKtx") // Persist the clear marker before deleting data or contacting the helper.
  fun clear(context: Context) =
      synchronized(syncLock) {
        val prefs = context.getSharedPreferences("byd_diagnostics", Context.MODE_PRIVATE)
        check(prefs.edit().putBoolean("clear_pending", true).putLong("helper_cursor", 0).commit())
        clearedAtElapsed = SystemClock.elapsedRealtime()
        journal(context).clear(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        storageFailed = false
        syncHelper(context)
      }

  private fun safely(action: () -> Unit) {
    runCatching(action).onFailure { storageFailed = true }
  }
}
