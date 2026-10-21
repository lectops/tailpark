// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object BydAdb {
  @Synchronized
  @SuppressLint("UseKtx") // Check the synchronous commit result before starting shell setup.
  fun key(context: Context, create: Boolean = true): AdbKey? {
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
          if (!create) return null
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

  /** Reopen only existing Android 11+ debugging settings while parked mode is requested. */
  fun restoreListener(context: Context) {
    if (Build.VERSION.SDK_INT < 30 || !BydPreferences.shouldResume(context)) return
    if (
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
    )
        return
    for (setting in listOf("adb_wifi_enabled", "adb_enabled")) {
      val current = Settings.Global.getInt(context.contentResolver, setting, -1)
      if (current >= 0 && current != 1) {
        Settings.Global.putInt(context.contentResolver, setting, 1)
        check(Settings.Global.getInt(context.contentResolver, setting, -1) == 1) {
          "Car firmware refused local debugging recovery"
        }
      }
    }
  }
}
