// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Bounded subprocess execution; callers provide fixed, separate arguments. */
final class BydShell {
    static final class Result {
        final int exitCode;
        final String output;
        Result(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
    }

    static Result run(int timeoutSeconds, String... command) throws IOException, InterruptedException {
        java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (java.io.InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    if (output.size() < 16384) output.write(buffer, 0, Math.min(size, 16384 - output.size()));
                }
            } catch (IOException ignored) { }
        }, "byd-command-output");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("Android command timed out");
            }
            drain.join(500);
            return new Result(process.exitValue(), new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
