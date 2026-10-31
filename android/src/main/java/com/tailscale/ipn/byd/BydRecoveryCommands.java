// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import java.io.IOException;

/** Fixed shell entrypoints; BYD's cold-start policy can discard manifest broadcasts. */
final class BydRecoveryCommands {
    enum Outcome { REQUESTED, DISABLED, UNAVAILABLE, REJECTED }
    interface Runner {
        BydShell.Result run(int timeoutSeconds, String... command) throws IOException, InterruptedException;
    }

    static void recover(String packageName, String token) throws IOException, InterruptedException {
        recover(packageName, token, BydShell::run);
    }

    static void recover(String packageName, String token, Runner runner) throws IOException, InterruptedException {
        recoverObserved(packageName, token, runner);
    }

    static Outcome recoverObserved(String packageName, String token) throws IOException, InterruptedException {
        return recoverObserved(packageName, token, BydShell::run);
    }

    static Outcome recoverObserved(String packageName, String token, Runner runner) throws IOException, InterruptedException {
        try {
            BydShell.Result result = runner.run(10, "/system/bin/am", "broadcast",
                    "--receiver-foreground", "--include-stopped-packages", "-n",
                    packageName + "/com.tailscale.ipn.byd.BydRecoveryReceiver", "-a",
                    packageName + ".BYD_RECOVER", "--es", "token", token);
            if (receiverAnswered(result)) {
                if (result.output.contains("data=\"DISABLED\"")) return Outcome.DISABLED;
                if (result.output.contains("data=\"UNAVAILABLE\"")) return Outcome.UNAVAILABLE;
                if (result.output.contains("data=\"REJECTED\"")) return Outcome.REJECTED;
                return Outcome.REQUESTED;
            }
        } catch (IOException ignored) {
            // A timed-out or discarded broadcast does not establish that the receiver ran.
        }
        startActivity(packageName, token, runner);
        return Outcome.REQUESTED;
    }

    static boolean receiverAnswered(BydShell.Result result) {
        if (result.exitCode != 0) return false;
        for (String reply : new String[]{"RECOVERING", "DISABLED", "UNAVAILABLE", "REJECTED"}) {
            if (result.output.contains("data=\"" + reply + "\"")) return true;
        }
        return false;
    }

    static void startActivity(String packageName, String token) throws IOException, InterruptedException {
        startActivity(packageName, token, BydShell::run);
    }

    private static void startActivity(String packageName, String token, Runner runner) throws IOException, InterruptedException {
        BydShell.Result result = runner.run(10, "/system/bin/am", "start", "-n",
                packageName + "/com.tailscale.ipn.byd.BydRecoveryActivity", "-a",
                packageName + ".BYD_RECOVER", "--es", "token", token);
        if (result.exitCode != 0 || result.output.contains("Error:")
                || result.output.contains("Exception")) {
            // The command's output includes its intent. Never copy it to logs or status.
            throw new IOException("VPN recovery activity could not start");
        }
    }
}
