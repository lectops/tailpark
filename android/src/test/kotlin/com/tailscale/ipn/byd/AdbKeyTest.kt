// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class AdbKeyTest {
  @Test
  fun authSignsTheTokenAsAPrehashedSha1Digest() {
    val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val message = "head-unit-auth".toByteArray()
    val token = MessageDigest.getInstance("SHA-1").digest(message)
    val signature = AdbKey(key).sign(token)
    val verifier =
        Signature.getInstance("SHA1withRSA").apply {
          initVerify(key.public)
          update(message)
        }
    assertTrue(verifier.verify(signature))
  }

  @Test
  fun publicKeyMatchesAdbdMontgomeryEncoding() {
    val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val publicKey = key.public as RSAPublicKey
    val encoded = String(AdbKey(key).publicKey())
    assertTrue(encoded.endsWith("\u0000"))
    val buffer =
        ByteBuffer.wrap(Base64.getDecoder().decode(encoded.substringBefore(' ')))
            .order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(64, buffer.int)
    val n0inv = buffer.int.toLong() and 0xffffffffL
    val modulusBytes = ByteArray(256).also(buffer::get)
    val rrBytes = ByteArray(256).also(buffer::get)
    val modulus = BigInteger(1, modulusBytes.reversedArray())
    val rr = BigInteger(1, rrBytes.reversedArray())
    assertEquals(publicKey.modulus, modulus)
    assertEquals(BigInteger.ONE.shiftLeft(4096).mod(modulus), rr)
    assertEquals(0xffffffffL, modulus.toLong() * n0inv and 0xffffffffL)
    assertEquals(publicKey.publicExponent.toInt(), buffer.int)
  }
}
