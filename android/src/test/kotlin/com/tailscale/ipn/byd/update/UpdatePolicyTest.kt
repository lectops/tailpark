// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
  @Test
  fun comparesUpstreamAndRevisionNumericallyAndNormalizesBuildIdentity() {
    assertTrue(TailparkVersion.parse("1.104.4-byd.10")!! > TailparkVersion.parse("1.104.4-byd.9")!!)
    assertTrue(TailparkVersion.parse("1.106.0-byd.1")!! > TailparkVersion.parse("1.104.4-byd.99")!!)
    assertEquals(
        TailparkVersion.parse("v1.104.4-byd.6"),
        TailparkVersion.parse("1.104.4-t938213198-gb9d487fe6-byd.6"),
    )
    for (value in
        listOf(
            "1.104.4",
            "1.104.4-byd.7-beta",
            "1.104.4-foo-byd.7",
            "1.104.4-byd.-1",
            "9999999999999999999999999.1.1-byd.1",
        )) {
      assertNull(value, TailparkVersion.parse(value))
    }
  }

  @Test
  fun installRequiresFreshObservedParkedStateAndNoExpiryOrErrors() {
    assertTrue(UpdatePolicy.parked(0, false, 100, "PARKED", false))
    for (power in listOf(null, -1, 1, 4)) assertFalse(
        UpdatePolicy.parked(power, false, 100, "PARKED", false)
    )
    for (screen in listOf(null, true)) assertFalse(
        UpdatePolicy.parked(0, screen, 100, "PARKED", false)
    )
    for (age in listOf(null, -1L, 7501L)) assertFalse(
        UpdatePolicy.parked(0, false, age, "PARKED", false)
    )
    for (phase in listOf("DRIVING", "UNKNOWN", "WAKING", "WAITING")) assertFalse(
        UpdatePolicy.parked(0, false, 100, phase, false)
    )
    assertFalse(UpdatePolicy.parked(0, false, 100, "PARKED", true))
    assertFalse(UpdatePolicy.parked(0, false, 100, "PARKED", false, true))
  }

  @Test
  fun backgroundChecksAndRateLimitsSurviveProcessAndClockChanges() {
    assertTrue(UpdatePolicy.canCheck(100, 0, 600, 0))
    assertFalse(UpdatePolicy.canCheck(500, 100, 600, 0))
    assertTrue(UpdatePolicy.canCheck(700, 100, 600, 0))
    assertFalse(UpdatePolicy.canCheck(700, 100, 600, 900))
    assertTrue(UpdatePolicy.canCheck(50, 100, 600, 0))
  }

  @Test
  fun acceptsExactlyOneChecksumForTheSelectedApk() {
    val hash = "ab".repeat(32)
    assertEquals(
        hash,
        UpdatePolicy.checksum("$hash  Tailpark-1.104.4-byd.7.apk\n", "Tailpark-1.104.4-byd.7.apk"),
    )
    assertEquals(
        hash,
        UpdatePolicy.checksum("$hash *Tailpark-1.104.4-byd.7.apk", "Tailpark-1.104.4-byd.7.apk"),
    )
    for (body in
        listOf(
            "$hash  other.apk",
            "$hash  ../app.apk",
            "$hash  app.apk\n$hash  app.apk",
            "bad  app.apk",
        )) {
      assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.checksum(body, "app.apk") }
    }
  }
}
