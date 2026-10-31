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

    @SuppressWarnings("deprecation")
    public static long verify(Context context, File file, String hash, String version) throws Exception {
        if (!hash.matches("[a-f0-9]{64}") || !hash.equals(digest(file)))
            throw new SecurityException("Downloaded APK checksum does not match");
        int flags = Build.VERSION.SDK_INT >= 28
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
        if (code(archive) <= code(installed))
            throw new SecurityException("Downloaded APK does not have a newer Android version code");
        if (!sameSigners(signers(installed), signers(archive)))
            throw new SecurityException("Downloaded APK was not signed by the installed app signer");
        return code(archive);
    }

    @SuppressWarnings("deprecation")
    public static long code(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28
                ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()
                : info.signatures;
    }

    public static boolean sameSigners(Signature[] installed, Signature[] archive) {
        if (installed == null || archive == null || installed.length == 0 || archive.length == 0) return false;
        Set<Signature> expected = new HashSet<>(), actual = new HashSet<>();
        java.util.Collections.addAll(expected, installed);
        java.util.Collections.addAll(actual, archive);
        return expected.equals(actual);
    }
}
