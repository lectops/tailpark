// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import com.tailscale.ipn.byd.update.TailparkVersion;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/** Used by both the application and the shell installer, each against its own staged bytes. */
public final class BydUpdateVerifier {
    private BydUpdateVerifier() {}

    public static String digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    public static long verify(Context context, File file, String hash, String version) throws Exception {
        return verify(context, file, hash, version, Build.VERSION.SDK_INT);
    }

    /** SDK injection keeps both supported PackageManager paths covered by host regressions. */
    @SuppressWarnings("deprecation")
    public static long verify(Context context, File file, String hash, String version, int sdk) throws Exception {
        if (!hash.matches("[a-f0-9]{64}") || !hash.equals(digest(file)))
            throw new SecurityException("Downloaded APK checksum does not match");
        int flags = sdk >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageManager manager = context.getPackageManager();
        PackageInfo archive = manager.getPackageArchiveInfo(file.getPath(), flags);
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        if (archive == null || !context.getPackageName().equals(archive.packageName))
            throw new SecurityException("Downloaded APK has the wrong package");
        TailparkVersion expected = TailparkVersion.Companion.parse(version);
        TailparkVersion found = TailparkVersion.Companion.parse(archive.versionName == null ? "" : archive.versionName);
        TailparkVersion previous = TailparkVersion.Companion.parse(installed.versionName == null ? "" : installed.versionName);
        if (expected == null || expected.getMinor() % 2 != 0 || !expected.equals(found))
            throw new SecurityException("Downloaded APK version does not match the release");
        if (previous == null || expected.compareTo(previous) <= 0)
            throw new SecurityException("Downloaded APK does not have a newer Tailpark version");
        if (code(archive, sdk) <= code(installed, sdk))
            throw new SecurityException("Downloaded APK does not have a newer Android version code");
        if (!sameSigners(signers(installed, sdk), signers(archive, sdk)))
            throw new SecurityException("Downloaded APK was not signed by the installed app signer");
        return code(archive, sdk);
    }

    @SuppressWarnings("deprecation")
    public static long code(PackageInfo info) {
        return code(info, Build.VERSION.SDK_INT);
    }

    @SuppressWarnings("deprecation")
    private static long code(PackageInfo info, int sdk) {
        return sdk >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo info, int sdk) {
        return sdk >= 28
                ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()
                : info.signatures;
    }

    public static boolean sameSigners(Signature[] installed, Signature[] archive) {
        if (installed == null || archive == null || installed.length == 0 || archive.length == 0) return false;
        Set<ByteBuffer> expected = certificates(installed), actual = certificates(archive);
        return expected != null && expected.equals(actual);
    }

    private static Set<ByteBuffer> certificates(Signature[] signers) {
        Set<ByteBuffer> result = new HashSet<>();
        for (Signature signer : signers) {
            if (signer == null) return null;
            byte[] encoded = signer.toByteArray();
            if (encoded == null || encoded.length == 0) return null;
            result.add(ByteBuffer.wrap(encoded).asReadOnlyBuffer());
        }
        return result;
    }
}
