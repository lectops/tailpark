// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class BydRecoveryCommandsTest {
    @Test public void successfulShellExitWithoutReceiverDeliveryFallsBack() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        BydRecoveryCommands.recover("dev.tailpark.byd", "test-token", (timeout, command) -> {
            commands.add(Arrays.asList(command));
            // This is the actual SSC-discarded broadcast's output, despite shell exit zero.
            return new BydShell.Result(0, "Broadcast completed: result=0\n");
        });
        assertEquals(2, commands.size());
        assertEquals("broadcast", commands.get(0).get(1));
        assertEquals("start", commands.get(1).get(1));
        assertTrue(commands.get(1).contains("dev.tailpark.byd/com.tailscale.ipn.byd.BydRecoveryActivity"));
    }

    @Test public void acknowledgedRecoveryAndStopsNeverLaunchActivity() throws Exception {
        for (String reply : new String[]{"RECOVERING", "DISABLED", "UNAVAILABLE", "REJECTED"}) {
            List<List<String>> commands = new ArrayList<>();
            BydRecoveryCommands.recover("dev.tailpark.byd", "test-token", (timeout, command) -> {
                commands.add(Arrays.asList(command));
                return new BydShell.Result(0, "Broadcast completed: result=-1, data=\"" + reply + "\"\n");
            });
            assertEquals(reply, 1, commands.size());
        }
    }

    @Test public void broadcastTimeoutStillTriesActivity() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        BydRecoveryCommands.recover("dev.tailpark.byd", "test-token", (timeout, command) -> {
            commands.add(Arrays.asList(command));
            if (command[1].equals("broadcast")) throw new IOException("Timed out");
            return new BydShell.Result(0, "Starting: Intent\n");
        });
        assertEquals(2, commands.size());
    }

    @Test public void failedBroadcastAndActivityReportFailureWithoutIntentOutput() {
        IOException error = assertThrows(IOException.class, () ->
                BydRecoveryCommands.recover("dev.tailpark.byd", "test-token", (timeout, command) ->
                        new BydShell.Result(1, "Error: secret intent output")));
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test public void androidErrorWithZeroExitIsStillFailure() {
        assertThrows(IOException.class, () ->
                BydRecoveryCommands.recover("dev.tailpark.byd", "test-token", (timeout, command) ->
                        new BydShell.Result(0, command[1].equals("broadcast")
                                ? "Broadcast completed: result=0" : "Error: Activity not started")));
    }

    @Test public void shutdownInterruptDoesNotLaunchAnotherComponent() {
        List<List<String>> commands = new ArrayList<>();
        assertThrows(InterruptedException.class, () ->
                BydRecoveryCommands.recover("dev.tailpark.byd", "test-token", (timeout, command) -> {
                    commands.add(Arrays.asList(command));
                    throw new InterruptedException("Shutdown");
                }));
        assertEquals(1, commands.size());
    }
}
