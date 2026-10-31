// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import com.tailscale.ipn.byd.BydFlightRecorder.Event
import org.junit.Assert.*
import org.junit.Test

class BydDiagnosticTimelineTest {
  private fun event(
      seq: Long,
      session: String = "a".repeat(32),
      wall: Long = 1000,
      elapsed: Long = seq * 100,
      boot: Long = 7,
      source: String = "APP",
      code: String = "SESSION_STARTED",
      value: String = "STARTED",
  ) =
      requireNotNull(
          Event.parse("$seq|$session|$wall|$elapsed|$boot|$source|$code|$value|1.104.4-byd.6")
      )

  @Test
  fun bootBroadcastAndRejectedRequestAreDescribedWithoutInferringAnotherCause() {
    val boot = event(1, code = "BOOT_OBSERVED")
    assertEquals("Android boot observed", BydDiagnosticTimeline.describe(boot))
    assertFalse(
        BydDiagnosticTimeline.summary(listOf(boot)).contains("different Android boot count")
    )
    val rejected = event(2, source = "HELPER", code = "RECOVERY", value = "REJECTED")
    assertTrue(BydDiagnosticTimeline.describe(rejected).contains("request rejected"))
    assertFalse(BydDiagnosticTimeline.describe(rejected).contains("revoked"))
  }

  @Test
  fun startupSummaryAppearsForSavedPriorSessionsButNotFreshInstall() {
    assertFalse(BydDiagnosticTimeline.hasPreviousObservations(listOf(event(1))))
    assertTrue(
        BydDiagnosticTimeline.hasPreviousObservations(
            listOf(event(1), event(2, session = "b".repeat(32)))
        )
    )
  }

  @Test
  fun abruptAppLossRemainsUnknownAndDoesNotClaimReboot() {
    val first = event(1)
    val second = event(2, session = "b".repeat(32))
    val summary = BydDiagnosticTimeline.summary(listOf(first, second))
    assertTrue(summary.contains("Recording stopped; cause unknown"))
    assertFalse(summary.contains("reboot"))
    assertEquals(
        setOf(first.session),
        BydDiagnosticTimeline.interruptedSessions(listOf(first, second)),
    )
  }

  @Test
  fun gracefulStopDoesNotCreateRecordingGap() {
    val events =
        listOf(
            event(1),
            event(2, code = "SESSION_STOPPED", value = "STOPPED"),
            event(3, session = "b".repeat(32)),
        )
    assertTrue(BydDiagnosticTimeline.interruptedSessions(events).isEmpty())
    assertFalse(BydDiagnosticTimeline.summary(events).contains("Recording stopped"))
  }

  @Test
  fun unknownBootKeepsSavedSessionOrderWhenWallClockMovesBackwards() {
    val first = event(1, boot = -1, wall = 5000)
    val second = event(2, session = "b".repeat(32), boot = -1, wall = 1000)
    val ordered = BydDiagnosticTimeline.ordered(listOf(first, second))
    assertEquals(listOf(first, second), ordered)
    assertEquals(setOf(first.session), BydDiagnosticTimeline.interruptedSessions(ordered))
    assertTrue(BydDiagnosticTimeline.summary(ordered).contains("Recording stopped; cause unknown"))
    // Helper directory recreation may reset sequences; import order still identifies the old
    // session.
    val reset = event(1, session = "c".repeat(32), source = "HELPER", boot = -1, wall = 500)
    val helper = event(20, session = "d".repeat(32), source = "HELPER", boot = -1, wall = 6000)
    val imported = BydDiagnosticTimeline.ordered(listOf(helper, reset))
    assertEquals(listOf(helper, reset), imported)
    assertEquals(setOf(helper.session), BydDiagnosticTimeline.interruptedSessions(imported))
  }

  @Test
  fun wallClockJumpUsesElapsedOrderWithinBoot() {
    val loss = event(2, wall = 5000, elapsed = 200, code = "NETWORK", value = "NO_NETWORK")
    val recovery =
        event(
            1,
            session = "b".repeat(32),
            wall = 1000,
            elapsed = 300,
            source = "HELPER",
            code = "RECOVERY",
            value = "ATTEMPT",
        )
    assertEquals(listOf(loss, recovery), BydDiagnosticTimeline.ordered(listOf(recovery, loss)))
    assertTrue(
        BydDiagnosticTimeline.summary(listOf(recovery, loss)).contains("followed by VPN recovery")
    )
  }

  @Test
  fun incomparableBootsCannotEstablishOrderedOutageExplanation() {
    val loss = event(1, code = "NETWORK", value = "NO_NETWORK", boot = -1)
    val recovery = event(2, source = "HELPER", code = "RECOVERY", value = "ATTEMPT", boot = -1)
    assertFalse(
        BydDiagnosticTimeline.summary(listOf(loss, recovery)).contains("followed by VPN recovery")
    )
    assertFalse(
        BydDiagnosticTimeline.summary(
                listOf(
                    loss,
                    event(
                        3,
                        boot = 8,
                        code = "RECOVERY",
                        value = "ATTEMPT",
                    ),
                )
            )
            .contains("followed by VPN recovery")
    )
  }

  @Test
  fun exportIncludesBothClocksVersionAndGapWithNoRawNetworkDetails() {
    val events =
        listOf(
            event(1),
            event(2, code = "NETWORK", value = "CELLULAR"),
            event(3, session = "b".repeat(32)),
        )
    val exported = BydDiagnosticTimeline.export(events)
    assertTrue(exported.contains("Recording stopped; cause unknown"))
    assertTrue(exported.contains("elapsedMs=200"))
    assertTrue(exported.contains("boot=7"))
    assertTrue(exported.contains("1.104.4-byd.6"))
    assertTrue(exported.contains("cellular"))
    assertFalse(exported.contains("http"))
    assertFalse(exported.contains("helper_token="))
  }
}
