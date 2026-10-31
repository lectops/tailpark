// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import com.tailscale.ipn.byd.BydUpdateVerifier
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

@Suppress("DEPRECATION")
class UpdateVerifierTest {
  private val context = mock<Context>()
  private val manager = mock<PackageManager>()

  private fun certificate(vararg bytes: Int) =
      mock<Signature>().also { signature ->
        whenever(signature.toByteArray()).thenReturn(bytes.map { it.toByte() }.toByteArray())
      }

  private val signer = certificate(1, 2, 3)
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

  private fun verify(hashOverride: String? = null, sdk: Int = 26): Long {
    val file = File.createTempFile("tailpark-verifier", ".apk")
    try {
      file.writeText("fake archive bytes; PackageManager is mocked")
      return BydUpdateVerifier.verify(
          context,
          file,
          hashOverride ?: BydUpdateVerifier.digest(file),
          "1.104.4-byd.7",
          sdk,
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

  private fun modern(installedCode: Long = 4_294_967_396L, archiveCode: Long = 4_294_967_397L) {
    installed.signingInfo = mock<SigningInfo>().also { current(it, certificate(1, 2, 3)) }
    archive.signingInfo = mock<SigningInfo>().also { current(it, certificate(1, 2, 3)) }
    whenever(installed.longVersionCode).thenReturn(installedCode)
    whenever(archive.longVersionCode).thenReturn(archiveCode)
  }

  private fun current(info: SigningInfo, vararg certificates: Signature) {
    whenever(info.apkContentsSigners).thenReturn(arrayOf(*certificates))
  }

  @Test
  fun modernSdkUsesCurrentCertificateBytesAndLongVersionCode() {
    modern()
    // The obsolete fields are deliberately misleading and must not authorize or reject the APK.
    installed.signatures = arrayOf(certificate(8))
    archive.signatures = arrayOf(certificate(9))
    installed.versionCode = 999
    archive.versionCode = 1
    assertEquals(4_294_967_397L, verify(sdk = 29))
    verify(manager).getPackageArchiveInfo(any(), eq(PackageManager.GET_SIGNING_CERTIFICATES))
    verify(manager)
        .getPackageInfo(eq("dev.tailpark.byd"), eq(PackageManager.GET_SIGNING_CERTIFICATES))
  }

  @Test
  fun modernCurrentSignerSetMatchesRegardlessOfOrderAndRejectsMissingMembers() {
    modern()
    current(installed.signingInfo!!, certificate(1), certificate(2))
    current(archive.signingInfo!!, certificate(2), certificate(1))
    assertEquals(4_294_967_397L, verify(sdk = 28))
    current(archive.signingInfo!!, certificate(1))
    assertThrows(SecurityException::class.java) { verify(sdk = 28) }
  }

  @Test
  fun modernMissingOrEmptySigningInfoCannotFallBackToLegacySignatures() {
    modern()
    archive.signingInfo = null
    assertThrows(SecurityException::class.java) { verify(sdk = 29) }
    modern()
    whenever(archive.signingInfo!!.apkContentsSigners).thenReturn(emptyArray())
    assertThrows(SecurityException::class.java) { verify(sdk = 29) }
    modern()
    installed.signingInfo = null
    assertThrows(SecurityException::class.java) { verify(sdk = 29) }
    modern()
    whenever(installed.signingInfo!!.apkContentsSigners).thenReturn(emptyArray())
    assertThrows(SecurityException::class.java) { verify(sdk = 29) }
  }

  @Test
  fun modernHistoryOnlyCertificateMatchDoesNotAuthorizeDifferentCurrentSigner() {
    modern()
    current(archive.signingInfo!!, certificate(9))
    val history = arrayOf(certificate(1, 2, 3), certificate(9))
    whenever(archive.signingInfo!!.signingCertificateHistory).thenReturn(history)
    assertThrows(SecurityException::class.java) { verify(sdk = 29) }
    verify(archive.signingInfo!!, never()).signingCertificateHistory
  }

  @Test
  fun modernRejectsNullOrEmptyEncodedCertificates() {
    modern()
    current(archive.signingInfo!!, mock<Signature>())
    assertThrows(SecurityException::class.java) { verify(sdk = 29) }
    current(archive.signingInfo!!, certificate())
    assertThrows(SecurityException::class.java) { verify(sdk = 29) }
    modern()
    current(installed.signingInfo!!, certificate())
    assertThrows(SecurityException::class.java) { verify(sdk = 29) }
  }

  @Test
  fun modernRejectsEqualOrLowerLongCodeEvenWhenLegacyFieldIsHigher() {
    for (candidate in listOf(4_294_967_395L, 4_294_967_396L)) {
      modern(archiveCode = candidate)
      archive.versionCode = 999
      installed.versionCode = 1
      assertThrows(SecurityException::class.java) { verify(sdk = 29) }
    }
  }
}
