// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Owns app locks and negotiates the detached shell helper while a VPN session is requested. */
class BydPowerController(private val context: Context, private val vpnActive: () -> Boolean) {
  private val executor = Executors.newScheduledThreadPool(2)
  private val platform = BydPlatform(context)
  private val state = ParkedPowerState(platform)
  @Volatile private var running = false
  @Volatile private var helper: JSONObject? = null
  @Volatile private var setupMessage: String? = null
  private var stoppedHelper = false

  @Synchronized
  fun start() {
    if (running) return
    running = true
    executor.execute { poll() }
    executor.scheduleWithFixedDelay({ connectHelper() }, 0, 10, TimeUnit.SECONDS)
  }

  @Synchronized
  private fun poll() {
    if (!running) return
    tick()
    val delay = if (helper != null) 5000L else state.nextPollDelayMillis()
    if (running) executor.schedule({ poll() }, delay, TimeUnit.MILLISECONDS)
  }

  @Synchronized
  private fun tick() {
    if (!running) return
    try {
      if (!BydPreferences.shouldResume(context)) {
        platform.release()
        state.tick(false, false, null, null, SystemClock.elapsedRealtime())
        helper = null
        if (!stoppedHelper) BydHelperConnection.stopAsync(context)
        stoppedHelper = true
        BydPreferences.status.value = "Parked connectivity is off"
        return
      }
      stoppedHelper = false
      val keepWifi = BydPreferences.prefs(context).getBoolean("keep_wifi", true)
      val remote = helper
      if (remote != null) {
        platform.release()
        BydPreferences.status.value = describe(remote.optString("phase"), remote.optString("error"))
      } else {
        platform.holdLocks(keepWifi)
        state.tick(
            true,
            keepWifi,
            platform.powerLevel(),
            platform.screenOn(),
            SystemClock.elapsedRealtime(),
        )
        val error =
            state.error()
                ?: if (state.phase() == ParkedPowerState.Phase.UNKNOWN) platform.error() else null
        BydPreferences.status.value = setupMessage ?: describe(state.phase().name, error)
      }
    } catch (e: Exception) {
      BydPreferences.status.value = "Parked connectivity: ${e.message}"
      Log.w("Tailpark", "Parked controller", e)
    }
  }

  private fun connectHelper() {
    if (!running || !BydPreferences.shouldResume(context)) return
    val wifi = if (BydPreferences.prefs(context).getBoolean("keep_wifi", true)) "1" else "0"
    val health = if (vpnActive()) "1" else "0"
    val config = "CONFIG $wifi $health"
    try {
      val status = BydHelperConnection.request(context, "STATUS")
      if (
          status.optInt("protocol") != 2 ||
              status.optString("apkPath") != context.applicationInfo.sourceDir
      ) {
        BydHelperConnection.request(
            context,
            if (status.optInt("protocol") >= 2) "RESTART" else "STOP",
        )
        throw IllegalStateException("Reloading parked helper")
      }
      helper = BydHelperConnection.request(context, config)
      BydDiagnostics.observe(
          context,
          BydFlightRecorder.Code.HELPER,
          BydFlightRecorder.Value.AVAILABLE,
      )
      BydDiagnostics.syncHelper(context)
      setupMessage = null
      if (status.optBoolean("watchdogAlive")) return
    } catch (_: Exception) {
      BydDiagnostics.observe(
          context,
          BydFlightRecorder.Code.HELPER,
          BydFlightRecorder.Value.UNAVAILABLE,
      )
      helper = null
    }
    try {
      BydAdb.restoreListener(context)
      LocalAdb.launch(
          requireNotNull(BydAdb.key(context)),
          BydHelperConnection.launchCommand(context),
      )
      if (!BydPreferences.shouldResume(context)) {
        BydHelperConnection.stopAsync(context)
        return
      }
      // Launching app_process is asynchronous; the next attempt connects once it is listening.
      setupMessage = "Starting parked connectivity"
    } catch (e: Exception) {
      setupMessage =
          when (e) {
            is java.net.ConnectException ->
                "Enable Android debugging to finish parked connectivity setup"
            is java.net.SocketTimeoutException ->
                "Allow the Android debugging request on the car display"
            else -> e.message ?: "Parked connectivity setup is pending"
          }
    }
  }

  @Synchronized
  fun close() {
    running = false
    executor.shutdownNow()
    platform.release()
    if (!BydPreferences.shouldResume(context)) BydHelperConnection.stopAsync(context)
  }

  private fun describe(phase: String, error: String?): String {
    if (!error.isNullOrBlank() && error != "null") return "Parked connectivity: $error"
    return when (phase) {
      "PARKED" -> "Parked mode active · display kept dark"
      "WAITING",
      "WAKING" -> "Keeping the car connected after ACC off"
      "DRIVING" -> "Ready to stay connected when you park"
      "UNKNOWN" -> "Waiting for car power state"
      "TIMED_OUT" -> "Waiting for the car display to turn off"
      else -> "Parked connectivity ready"
    }
  }
}
