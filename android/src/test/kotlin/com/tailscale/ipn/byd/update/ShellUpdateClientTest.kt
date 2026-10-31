// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd.update

import com.tailscale.ipn.byd.BydUpdateTransfer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ShellUpdateClientTest {
  @Test
  fun deadlineClosesSocketWhenAuthenticatedPeerStopsReadingTheApk() {
    val file = File.createTempFile("tailpark-stalled-stream", ".apk")
    java.io.RandomAccessFile(file, "rw").use { it.setLength(32 * 1024 * 1024L) }
    val release = UpdateRelease("1.104.4-byd.7", "", "app.apk", "", "", file.length())
    val finished = CountDownLatch(1)
    val peer = CompletableFuture<Unit>()
    val started = System.nanoTime()
    try {
      assertThrows(java.io.IOException::class.java) {
        ShellUpdateClient.transfer(
            file,
            release,
            "ab".repeat(32),
            { port, nonce ->
              Thread {
                try {
                  Socket("127.0.0.1", port).use { socket ->
                    socket.receiveBufferSize = 1024
                    DataOutputStream(socket.getOutputStream()).apply {
                      writeUTF(nonce)
                      flush()
                    }
                    // Authenticated, but never reads even the transfer header.
                    finished.await(5, TimeUnit.SECONDS)
                  }
                  peer.complete(Unit)
                } catch (error: Throwable) {
                  peer.completeExceptionally(error)
                }
              }
                  .start()
            },
            {
              fail("stalled staging must never reach commit")
              false
            },
            transferTimeoutMs = 100,
        )
      }
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000)
    } finally {
      finished.countDown()
      peer.get(5, TimeUnit.SECONDS)
      file.delete()
    }
  }

  @Test
  fun onlyAuthenticatesAndCommitsAfterCompleteStagingAndCurrentAuthorization() {
    exchange(allowed = true, wrongNonceFirst = true)
  }

  @Test
  fun optingOutDuringTransferCancelsBeforeCommitAndRetainsApk() {
    exchange(allowed = false)
  }

  private fun exchange(allowed: Boolean, wrongNonceFirst: Boolean = false) {
    val file =
        File.createTempFile("tailpark-stream", ".apk").apply {
          writeBytes(ByteArray(65540) { it.toByte() })
        }
    val received = CompletableFuture<String>()
    var staged = false
    val release = UpdateRelease("1.104.4-byd.7", "", "app.apk", "", "", file.length())
    try {
      val result =
          ShellUpdateClient.transfer(
              file,
              release,
              "ab".repeat(32),
              { port, nonce ->
                Thread {
                  try {
                    if (wrongNonceFirst)
                        Socket("127.0.0.1", port).use { socket ->
                          DataOutputStream(socket.getOutputStream()).apply {
                            writeUTF("wrong nonce")
                            flush()
                          }
                          assertEquals(-1, socket.getInputStream().read())
                        }
                    Socket("127.0.0.1", port).use { socket ->
                      socket.soTimeout = 5000
                      val input = DataInputStream(socket.getInputStream())
                      val output = DataOutputStream(socket.getOutputStream())
                      output.writeUTF(nonce)
                      output.flush()
                      val length = input.readLong()
                      assertEquals("ab".repeat(32), input.readUTF())
                      assertEquals(release.version, input.readUTF())
                      val bytes = ByteArrayOutputStream()
                      BydUpdateTransfer.copy(input, bytes, length)
                      assertArrayEquals(file.readBytes(), bytes.toByteArray())
                      staged = true
                      output.writeUTF("READY")
                      output.flush()
                      val command = input.readUTF()
                      received.complete(command)
                      if (command == "COMMIT") {
                        output.writeUTF("CONFIRMATION_REQUIRED")
                        output.flush()
                      }
                    }
                  } catch (error: Throwable) {
                    received.completeExceptionally(error)
                  }
                }
                    .start()
              },
              { commit ->
                assertTrue(staged)
                if (allowed) commit()
                allowed
              },
          )
      assertEquals(if (allowed) "CONFIRMATION_REQUIRED" else "DEFERRED", result)
      assertEquals(if (allowed) "COMMIT" else "CANCEL", received.get(5, TimeUnit.SECONDS))
      assertTrue(file.isFile)
    } finally {
      file.delete()
    }
  }

  @Test
  fun rejectsOversizedTruncatedOrInterruptedTransfersAndPreservesNextFrame() {
    val bytes = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
    val output = ByteArrayOutputStream()
    BydUpdateTransfer.copy(bytes, output, 3)
    assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
    assertEquals(4, bytes.read())
    assertThrows(java.io.IOException::class.java) {
      BydUpdateTransfer.copy(
          ByteArrayInputStream(byteArrayOf(1)),
          ByteArrayOutputStream(),
          1,
          Long.MIN_VALUE,
      )
    }
    for (size in listOf(0L, -1L, UpdatePolicy.MAX_APK_BYTES + 1, 5L)) {
      assertThrows(java.io.IOException::class.java) {
        BydUpdateTransfer.copy(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), size)
      }
    }
    Thread.currentThread().interrupt()
    try {
      assertThrows(java.io.IOException::class.java) {
        BydUpdateTransfer.copy(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 1)
      }
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun commandHasFixedEntrypointAndSafelyQuotesActualApkPath() {
    val command =
        ShellUpdateClient.command(
            "/data/app/odd'path/base.apk",
            "dev.tailpark.byd",
            1234,
            "ab".repeat(32),
        )
    assertTrue(command.contains("-Djava.class.path='/data/app/odd'\\''path/base.apk'"))
    assertTrue(command.contains("com.tailscale.ipn.byd.BydUpdateShell"))
    assertThrows(IllegalArgumentException::class.java) {
      ShellUpdateClient.command("base.apk", "dev.other.app", 1234, "ab".repeat(32))
    }
    assertThrows(IllegalArgumentException::class.java) {
      ShellUpdateClient.command("base.apk", "dev.tailpark.byd", 0, "ab".repeat(32))
    }
    assertThrows(IllegalArgumentException::class.java) {
      ShellUpdateClient.command("base.apk", "dev.tailpark.byd", 1234, "x; echo nope")
    }
  }
}
