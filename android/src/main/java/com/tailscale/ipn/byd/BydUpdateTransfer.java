// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Bounded local staging; leaves the subsequent protocol frame unread. */
public final class BydUpdateTransfer {
    private BydUpdateTransfer() {}

    public static void copy(InputStream input, OutputStream output, long length) throws IOException {
        copy(input, output, length, System.nanoTime() + 60_000_000_000L);
    }

    public static void copy(InputStream input, OutputStream output, long length, long deadline) throws IOException {
        if (length <= 0 || length > 256 * 1024 * 1024L) throw new IOException("Invalid APK length");
        byte[] buffer = new byte[65536];
        long remaining = length;
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
                throw new IOException("APK transfer timed out");
            int count = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
            if (count < 0) throw new EOFException("Incomplete APK transfer");
            if (count == 0) continue;
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }
}
