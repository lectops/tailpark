// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Final transaction boundary, shared with host tests; the reads happen after staging and commit. */
public final class BydUpdateInstallGate {
    private BydUpdateInstallGate() {}

    public static String execute(String decision, BooleanSupplier stopped,
            Supplier<Integer> power, Supplier<Integer> screen, Callable<Boolean> install) throws Exception {
        if (!"COMMIT".equals(decision) || stopped.getAsBoolean()) return "DEFERRED";
        if (!Integer.valueOf(0).equals(power.get()) || !Integer.valueOf(0).equals(screen.get())
                || !Integer.valueOf(0).equals(power.get()) || stopped.getAsBoolean()) return "DEFERRED";
        return install.call() ? "INSTALLED" : "CONFIRMATION_REQUIRED";
    }
}
