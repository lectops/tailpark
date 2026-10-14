// Copyright (c) Tailscale BYD contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import static org.junit.Assert.*;
import java.io.IOException;
import org.junit.Test;

public class BydShellTest {
    @Test public void largeOutputIsDrainedButBounded() throws Exception {
        BydShell.Result result = BydShell.run(3, "/bin/sh", "-c",
                "awk 'BEGIN { for (i=0; i<50000; i++) print \"test output\" }'");
        assertEquals(0, result.exitCode);
        assertEquals(16384, result.output.length());
    }

    @Test public void timedOutCommandCannotBlockHelperIndefinitely() {
        assertThrows(IOException.class, () -> BydShell.run(1, "/bin/sh", "-c", "exec sleep 5"));
    }
}
