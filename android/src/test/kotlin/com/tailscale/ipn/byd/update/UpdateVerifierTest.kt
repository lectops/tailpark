// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.tailscale.ipn.byd.BydUpdateVerifier
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

@Suppress("DEPRECATION")
class UpdateVerifierTest {
  private val context = mock<Context>()
  private val manager = mock<PackageManager>()
  private val signer = mock<Signature>()
  private val installed =
      mock<PackageInfo>().apply {
        packageName = "dev.tailpark.byd"
        versionName = "1.104.4-t938213198-gb9d487fe6-byd.6"
        versionCode = 100
        signatures = arrayOf(signer)
      }
  private val archive =
      mock<PackageInfo>().apply {
        packageName = "dev.tailpark.byd"
        versionName = "1.104.4-t938213198-gb9d487fe6-byd.7"
        versionCode = 101
        signatures = arrayOf(signer)
      }

  init {
    whenever(context.packageName).thenReturn("dev.tailpark.byd")
    whenever(context.packageManager).thenReturn(manager)
    whenever(manager.getPackageInfo(eq("dev.tailpark.byd"), any<Int>())).thenReturn(installed)
    whenever(manager.getPackageArchiveInfo(any(), any<Int>())).thenReturn(archive)
  }

  private fun verify(hashOverride: String? = null): Long {
    val file = File.createTempFile("tailpark-verifier", ".apk")
    try {
      file.writeText("fake archive bytes; PackageManager is mocked")
      return BydUpdateVerifier.verify(
          context,
          file,
          hashOverride ?: BydUpdateVerifier.digest(file),
          "1.104.4-byd.7",
      )
    } finally {
      file.delete()
    }
  }

  @Test
  fun acceptsSameSignerAndNewerSemanticAndAndroidVersions() {
    assertEquals(101L, verify())
  }

  @Test
  fun rejectsCorruptionBeforeParsing() {
    assertThrows(SecurityException::class.java) { verify("00".repeat(32)) }
    verify(manager, never()).getPackageArchiveInfo(any(), any<Int>())
  }

  @Test
  fun rejectsOtherPackage() {
    archive.packageName = "dev.other.app"
    assertThrows(SecurityException::class.java) { verify() }
  }

  @Test
  fun rejectsDifferentOrMissingSigner() {
    archive.signatures = arrayOf(mock<Signature>())
    assertThrows(SecurityException::class.java) { verify() }
    archive.signatures = emptyArray()
    assertThrows(SecurityException::class.java) { verify() }
  }

  @Test
  fun rejectsEqualOrOlderAndroidCodeEvenWithNewerName() {
    for (code in listOf(99, 100)) {
      archive.versionCode = code
      assertThrows(SecurityException::class.java) { verify() }
    }
  }

  @Test
  fun rejectsFalseVersionNameEvenWithHigherAndroidCode() {
    archive.versionName = "1.104.4-byd.8"
    assertThrows(SecurityException::class.java) { verify() }
    installed.versionName = "1.106.0-byd.1"
    archive.versionName = "1.104.4-byd.7"
    assertThrows(SecurityException::class.java) { verify() }
  }
}
