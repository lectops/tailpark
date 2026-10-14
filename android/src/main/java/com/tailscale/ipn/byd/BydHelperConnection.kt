// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import org.json.JSONObject

object BydHelperConnection {
  private val stopExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "byd-stop").apply { isDaemon = true }
  }

  fun request(context: Context, command: String): JSONObject =
      Socket().use { socket ->
        socket.soTimeout = 1500
        socket.connect(InetSocketAddress("127.0.0.1", BydPowerHelper.PORT), 1000)
        socket.outputStream.write(
            "${BydPreferences.token(context)} $command\n".toByteArray(StandardCharsets.UTF_8)
        )
        socket.outputStream.flush()
        val response = BydPowerHelper.readLine(socket.inputStream)
        JSONObject(response)
      }

  fun stopAsync(context: Context) {
    val appContext = context.applicationContext
    stopExecutor.execute {
      // A reconnect may have superseded the queued stop.
      if (BydPreferences.shouldResume(appContext)) return@execute
      if (runCatching { request(appContext, "STOP") }.isSuccess) return@execute
      runCatching {
        val key = BydAdb.key(appContext, create = false) ?: return@runCatching
        val directory = BydRuntimeFiles(appContext.packageName).directory.path
        val command =
            "umask 077; mkdir -p " +
                quote(directory) +
                " && chmod 700 " +
                quote(directory) +
                " && : > " +
                quote("$directory/disabled")
        LocalAdb.launch(key, command, false)
      }
    }
  }

  private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

  fun launchCommand(context: Context): String {
    return "nohup /system/bin/app_process -Djava.class.path=" +
        quote(context.applicationInfo.sourceDir) +
        " /system/bin --nice-name=tsbyd-setup com.tailscale.ipn.byd.BydHelperInstaller " +
        quote(context.packageName) +
        " " +
        quote(BydPreferences.token(context)) +
        " > /dev/null 2>&1 < /dev/null &"
  }
}
