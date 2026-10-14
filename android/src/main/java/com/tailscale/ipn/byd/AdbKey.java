// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import javax.crypto.Cipher;

/** ADB's RSA-2048 authentication format, including its prehashed SHA-1 token signature. */
public final class AdbKey {
    private static final byte[] SHA1_PREFIX = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };
    private final KeyPair key;
    public AdbKey(KeyPair key) { this.key = key; }

    public byte[] sign(byte[] token) throws Exception {
        if (token.length != 20) throw new IllegalArgumentException("Invalid ADB authentication token");
        byte[] digestInfo = new byte[SHA1_PREFIX.length + token.length];
        System.arraycopy(SHA1_PREFIX, 0, digestInfo, 0, SHA1_PREFIX.length);
        System.arraycopy(token, 0, digestInfo, SHA1_PREFIX.length, token.length);
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, key.getPrivate());
        return rsa.doFinal(digestInfo);
    }

    public byte[] publicKey() {
        RSAPublicKey publicKey = (RSAPublicKey) key.getPublic();
        BigInteger modulus = publicKey.getModulus();
        BigInteger word = BigInteger.ONE.shiftLeft(32);
        int n0inv = modulus.mod(word).modInverse(word).negate().mod(word).intValue();
        ByteBuffer buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(64).putInt(n0inv);
        buffer.put(littleEndian(modulus));
        buffer.put(littleEndian(BigInteger.ONE.shiftLeft(4096).mod(modulus)));
        buffer.putInt(publicKey.getPublicExponent().intValue());
        return (Base64.getEncoder().encodeToString(buffer.array())
                + " tailscale-byd@headunit\0").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] littleEndian(BigInteger value) {
        byte[] big = value.toByteArray();
        byte[] little = new byte[256];
        for (int i = 0; i < Math.min(big.length, little.length); i++) little[i] = big[big.length - 1 - i];
        return little;
    }
}
