// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.json.JSONObject

object BydHelperConnection {
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
    Thread({ runCatching { request(context, "STOP") } }, "byd-stop").apply {
      isDaemon = true
      start()
    }
  }

  fun launchCommand(context: Context): String {
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    return "nohup /system/bin/app_process -Djava.class.path=" +
        quote(context.applicationInfo.sourceDir) +
        " /system/bin --nice-name=tsbyd-helper com.tailscale.ipn.byd.BydPowerHelper " +
        quote(context.packageName) +
        " " +
        quote(BydPreferences.token(context)) +
        " > /dev/null 2>&1 < /dev/null &"
  }
}
