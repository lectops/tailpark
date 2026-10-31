// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd.update

import android.content.Context
import com.tailscale.ipn.byd.BydAdb
import com.tailscale.ipn.byd.BydUpdateTransfer
import com.tailscale.ipn.byd.BydUpdateVerifier
import com.tailscale.ipn.byd.LocalAdb
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

object ShellUpdateClient {
  private val deadlines =
      ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "tailpark-update-deadline").apply { isDaemon = true }
          }
          .apply { removeOnCancelPolicy = true }

  internal fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

  internal fun command(apk: String, packageName: String, port: Int, nonce: String): String {
    require(
        packageName == "dev.tailpark.byd" &&
            port in 1..65535 &&
            nonce.matches(Regex("[a-f0-9]{64}"))
    )
    return "nohup /system/bin/app_process -Djava.class.path=${quote(apk)} /system/bin " +
        "--nice-name=tailpark-update com.tailscale.ipn.byd.BydUpdateShell " +
        "${quote(packageName)} $port $nonce > /dev/null 2>&1 < /dev/null &"
  }

  /** A single-use local stream; private app files are never made globally readable. */
  fun install(
      context: Context,
      file: File,
      release: UpdateRelease,
      authorize: (() -> Unit) -> Boolean,
  ): String {
    val key = BydAdb.key(context, create = false) ?: return "CONFIRMATION_REQUIRED"
    val hash = BydUpdateVerifier.digest(file)
    return transfer(
        file,
        release,
        hash,
        { port, nonce ->
          LocalAdb.launch(
              key,
              command(context.applicationInfo.sourceDir, context.packageName, port, nonce),
              false,
          )
        },
        authorize,
    )
  }

  internal fun transfer(
      file: File,
      release: UpdateRelease,
      hash: String,
      launch: (Int, String) -> Unit,
      authorize: (() -> Unit) -> Boolean,
      transferTimeoutMs: Long = 60000,
  ): String {
    val nonce =
        ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
      server.soTimeout = 15000
      launch(server.localPort, nonce)
      val deadline = System.nanoTime() + 15_000_000_000L
      while (System.nanoTime() < deadline) {
        server.accept().use { socket ->
          socket.soTimeout = 3000
          val input = DataInputStream(socket.getInputStream())
          val output = DataOutputStream(socket.getOutputStream())
          val supplied = runCatching { input.readUTF() }.getOrNull() ?: return@use
          if (!MessageDigest.isEqual(nonce.toByteArray(), supplied.toByteArray())) return@use
          socket.soTimeout = 15000
          // SO_TIMEOUT covers reads only. Closing the socket also releases a blocked write.
          val deadline =
              deadlines.schedule(
                  { runCatching { socket.close() } },
                  transferTimeoutMs,
                  TimeUnit.MILLISECONDS,
              )
          val response =
              try {
                output.writeLong(file.length())
                output.writeUTF(hash)
                output.writeUTF(release.version)
                file.inputStream().use { BydUpdateTransfer.copy(it, output, file.length()) }
                output.flush()
                input.readUTF()
              } finally {
                deadline.cancel(false)
              }
          if (response != "READY") return "CONFIRMATION_REQUIRED"
          if (
              !authorize {
                output.writeUTF("COMMIT")
                output.flush()
              }
          ) {
            output.writeUTF("CANCEL")
            output.flush()
            return "DEFERRED"
          }
          socket.soTimeout = 130000
          return input.readUTF()
        }
      }
    }
    return "CONFIRMATION_REQUIRED"
  }
}
