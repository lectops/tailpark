// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd

import com.tailscale.ipn.byd.BydFlightRecorder.Code
import com.tailscale.ipn.byd.BydFlightRecorder.Event
import com.tailscale.ipn.byd.BydFlightRecorder.Source
import com.tailscale.ipn.byd.BydFlightRecorder.Value
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Explanations only describe ordered observations; a recording gap has no inferred cause. */
object BydDiagnosticTimeline {
  fun hasPreviousObservations(events: List<Event>): Boolean =
      events
          .groupBy { it.source }
          .values
          .any { records -> records.map { it.session }.distinct().size > 1 } ||
          events.any { it.code == Code.BOOT_OBSERVED || it.code == Code.RECOVERY }

  fun ordered(events: List<Event>): List<Event> =
      events.sortedWith(
          compareBy<Event> { if (it.boot >= 0) it.boot else Long.MAX_VALUE }
              .thenBy { if (it.boot >= 0) it.elapsedMs else it.wallMs }
              .thenBy { it.source.name }
              .thenBy { it.sequence }
      )

  fun summary(events: List<Event>): String {
    val ordered = ordered(events)
    val loss = ordered.indexOfLast { it.code == Code.NETWORK && it.value == Value.NO_NETWORK }
    if (
        loss >= 0 &&
            ordered.drop(loss + 1).any {
              it.code == Code.RECOVERY &&
                  it.boot >= 0 &&
                  it.boot == ordered[loss].boot &&
                  it.elapsedMs >= ordered[loss].elapsedMs
            }
    ) {
      return "No underlying network was observed, followed by VPN recovery attempts. " +
          "The timeline does not establish why the network disappeared."
    }
    if (ordered.any { it.code == Code.BOOT_OBSERVED }) {
      return "An Android boot was observed. Events before the restart are retained " +
          "when available; the recorder cannot establish why Android restarted."
    }
    if (interruptedSessions(ordered).isNotEmpty()) {
      return "Recording stopped; cause unknown. A later app or helper session started without " +
          "a recorded stop for the previous session."
    }
    return "Observed connection and power changes are listed below. Missing events do not prove " +
        "that the car stayed connected, shut down, or lost electrical power."
  }

  fun interruptedSessions(events: List<Event>): Set<String> {
    val result = mutableSetOf<String>()
    for (source in Source.values()) {
      val sessions = events.filter { it.source == source }.groupBy { it.session }.values.toList()
      for (session in sessions.dropLast(1)) {
        if (
            session.any { it.code == Code.SESSION_STARTED } &&
                session.none { it.code == Code.SESSION_STOPPED }
        ) {
          result.add(session.first().session)
        }
      }
    }
    return result
  }

  fun describe(event: Event): String =
      when (event.code) {
        Code.SESSION_STARTED ->
            if (event.value == Value.HISTORY_CLEARED) "History cleared; recording resumed"
            else "${source(event)} recording started · ${event.version}"
        Code.SESSION_STOPPED -> "${source(event)} stopped · ${label(event.value)}"
        Code.BOOT_OBSERVED -> "Android boot observed"
        Code.SERVICE -> "VPN service · ${label(event.value)}"
        Code.NETWORK ->
            if (event.source == Source.HELPER)
                "Underlying network inventory · ${label(event.value)}"
            else "Selected underlying network · ${label(event.value)}"
        Code.VPN -> "Android VPN tunnel · ${label(event.value)}"
        Code.BACKEND -> "Tailscale state · ${label(event.value)}"
        Code.ACC -> "ACC · ${label(event.value)}"
        Code.SCREEN -> "Screen · ${label(event.value)}"
        Code.PHASE -> "Parked controller · ${label(event.value)}"
        Code.POWER_ERROR -> "Power control · ${label(event.value)}"
        Code.HELPER -> "Helper connection · ${label(event.value)}"
        Code.WATCHDOG -> "Watchdog process · ${label(event.value)}"
        Code.RECOVERY -> "VPN recovery · ${label(event.value)}"
        Code.SUSPEND -> "Android suspend interval of at least 30 seconds observed"
        Code.PACKAGE_REPLACED -> "App replacement broadcast observed"
        Code.SHUTDOWN_OBSERVED -> "Android shutdown broadcast observed; completion unconfirmed"
        Code.HISTORY_LIMITED -> "Some older helper observations were rotated out"
      }

  fun timestamp(event: Event): String =
      SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(event.wallMs))

  fun export(events: List<Event>): String = buildString {
    append("Tailpark local diagnostics v1\n")
    append(summary(events)).append('\n')
    append(
        "Observations only. Wall clocks may change; elapsed time is comparable within one boot.\n"
    )
    append(
        "No credentials, peer names, IP addresses, SSIDs or other application logs are included.\n\n"
    )
    val gaps = interruptedSessions(events)
    val ends = events.groupBy { it.session }.mapValues { it.value.last().identity() }
    for (event in events) {
      append(timestamp(event)).append(" · boot=").append(event.boot)
      append(" elapsedMs=").append(event.elapsedMs).append(" · ")
      append(event.source).append(" session=").append(event.session)
      append(" sequence=").append(event.sequence).append(" version=").append(event.version)
      append(" · ").append(describe(event)).append('\n')
      if (event.session in gaps && ends[event.session] == event.identity()) {
        append("  Recording stopped; cause unknown. Final events may be missing.\n")
      }
    }
  }

  private fun source(event: Event) = if (event.source == Source.APP) "App" else "Helper"

  private fun label(value: Value): String =
      when (value) {
        Value.NO_NETWORK -> "none available"
        Value.NO_STATE -> "unknown"
        Value.IN_USE_OTHER_USER -> "in use by another user"
        Value.NEEDS_LOGIN -> "sign-in needed"
        Value.NEEDS_MACHINE_AUTH -> "device authorization needed"
        Value.REQUESTED -> "command completed; connectivity not yet confirmed"
        Value.REJECTED -> "request rejected"
        Value.ATTEMPT -> "attempt started"
        Value.OK -> "no reported error"
        else -> value.name.lowercase(Locale.ROOT).replace('_', ' ')
      }
}
