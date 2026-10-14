// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Owns app locks and negotiates the detached shell helper while a VPN session is requested. */
class BydPowerController(private val context: Context) {
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
    executor.scheduleWithFixedDelay({ tick() }, 0, 500, TimeUnit.MILLISECONDS)
    executor.scheduleWithFixedDelay({ connectHelper() }, 0, 10, TimeUnit.SECONDS)
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
        BydPreferences.status.value =
            setupMessage ?: describe(state.phase().name, state.error() ?: platform.error())
      }
    } catch (e: Exception) {
      BydPreferences.status.value = "Parked connectivity: ${e.message}"
      Log.w("TailscaleBYD", "Parked controller", e)
    }
  }

  private fun connectHelper() {
    if (!running || !BydPreferences.shouldResume(context)) return
    val config =
        if (BydPreferences.prefs(context).getBoolean("keep_wifi", true)) "CONFIG 1" else "CONFIG 0"
    try {
      helper = BydHelperConnection.request(context, config)
      setupMessage = null
      return
    } catch (_: Exception) {
      helper = null
    }
    try {
      LocalAdb.launch(adbKey(), BydHelperConnection.launchCommand(context))
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

  private fun adbKey(): AdbKey {
    val prefs = BydPreferences.prefs(context)
    val privateKey = prefs.getString("adb_private", null)
    val publicKey = prefs.getString("adb_public", null)
    val pair =
        if (privateKey != null && publicKey != null) {
          val factory = KeyFactory.getInstance("RSA")
          KeyPair(
              factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))),
              factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey))),
          )
        } else {
          KeyPairGenerator.getInstance("RSA")
              .apply { initialize(2048) }
              .generateKeyPair()
              .also {
                check(
                    prefs
                        .edit()
                        .putString(
                            "adb_private",
                            Base64.getEncoder().encodeToString(it.private.encoded),
                        )
                        .putString(
                            "adb_public",
                            Base64.getEncoder().encodeToString(it.public.encoded),
                        )
                        .commit()
                )
              }
        }
    return AdbKey(pair)
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
