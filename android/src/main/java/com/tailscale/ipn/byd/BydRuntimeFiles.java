// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/** Shell-owned, private runtime state. No Tailscale credentials are stored here. */
final class BydRuntimeFiles {
    final File directory;

    BydRuntimeFiles(String packageName) {
        if (!packageName.matches("[A-Za-z0-9_.]+")) throw new IllegalArgumentException("Invalid package");
        directory = new File("/data/local/tmp/tailpark-" + packageName);
    }

    File file(String name) { return new File(directory, name); }

    void preserveStatusBeforeRestart() throws IOException {
        File previous = file("last-status.json");
        if (previous.isFile()) {
            write("previous-status.json", new String(Files.readAllBytes(previous.toPath()),
                    StandardCharsets.UTF_8));
        }
    }

    synchronized void write(String name, String value) throws IOException {
        File temporary = file(name + ".new." + Process.myPid());
        Files.write(temporary.toPath(), value.getBytes(StandardCharsets.UTF_8));
        if (!temporary.renameTo(file(name))) {
            temporary.delete();
            throw new IOException("Cannot replace helper runtime state");
        }
    }

    void heartbeat() throws IOException {
        write("heartbeat", Process.myPid() + " " + (SystemClock.elapsedRealtime() / 1000) + "\n");
    }

    void disable() throws IOException { write("disabled", "stopped\n"); }

    boolean confirmIdleExpiry(String packageName, String token, long generation, long timeout) throws IOException, InterruptedException {
        BydShell.Result result = BydShell.run(5, "/system/bin/am", "broadcast",
                "--receiver-foreground", "--include-stopped-packages", "-n",
                packageName + "/com.tailscale.ipn.byd.BydRecoveryReceiver", "-a",
                packageName + ".BYD_IDLE_EXPIRE", "--es", "token", token,
                "--el", "generation", Long.toString(generation), "--el", "timeout", Long.toString(timeout));
        return result.exitCode == 0 && result.output.contains("data=\"IDLE_CONFIRMED\"");
    }

    String bootId() {
        try { return new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()), StandardCharsets.UTF_8).trim(); }
        catch (IOException e) { return ""; }
    }

    boolean watchdogAlive() {
        try {
            String pid = new String(Files.readAllBytes(file("watchdog.lock/pid").toPath()),
                    StandardCharsets.UTF_8).trim();
            if (!pid.matches("[0-9]+")) return false;
            String command = new String(Files.readAllBytes(new File("/proc/" + pid + "/cmdline").toPath()),
                    StandardCharsets.UTF_8);
            return command.contains(file("watchdog.sh").getPath());
        } catch (IOException e) { return false; }
    }

    static String fingerprint(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    static String desiredConfig(String packageName, String token) throws IOException, InterruptedException {
        try {
            return queryDesiredConfig(packageName, token);
        } catch (IOException ignored) {
            // SSC blocks a cold receiver. A protected no-window activity can start our own service.
            BydRecoveryCommands.startActivity(packageName, token);
            return queryDesiredConfig(packageName, token);
        }
    }

    private static String queryDesiredConfig(String packageName, String token) throws IOException, InterruptedException {
        BydShell.Result result = BydShell.run(15, "/system/bin/am", "broadcast",
                "--receiver-foreground", "--include-stopped-packages", "-n",
                packageName + "/com.tailscale.ipn.byd.BydRecoveryReceiver", "-a",
                packageName + ".BYD_HELPER_CHECK", "--es", "token", token);
        if (result.exitCode == 0 && result.output.contains("data=\"READY_WIFI\"")) return "1";
        if (result.exitCode == 0 && result.output.contains("data=\"READY_CELLULAR\"")) return "0";
        if (result.output.contains("data=\"DISABLED\"") || result.output.contains("data=\"REJECTED\"")) return null;
        throw new IOException("Parked connection preference could not be confirmed");
    }
}
