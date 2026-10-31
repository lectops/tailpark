// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd.update

/** Deliberately accepts only stable Tailpark versions, independently of Android's version code. */
data class TailparkVersion(val major: Long, val minor: Long, val patch: Long, val revision: Long) :
    Comparable<TailparkVersion> {
  override fun compareTo(other: TailparkVersion): Int =
      compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.revision })

  companion object {
    fun parse(value: String): TailparkVersion? {
      val match =
          Regex("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-t[0-9a-f]+-g[0-9a-f]+)?-byd\\.([0-9]+)$")
              .matchEntire(value) ?: return null
      val values = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
      return TailparkVersion(values[0], values[1], values[2], values[3])
    }
  }
}

object UpdatePolicy {
  const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
  const val MANUAL_INTERVAL_MS = 60 * 1000L
  const val MAX_APK_BYTES = 256 * 1024 * 1024L
  // Stable helper ticks are five seconds apart. Final shell installation rereads OEM state.
  const val MAX_SAMPLE_AGE_MS = 7500L

  fun parked(
      power: Int?,
      screenOn: Boolean?,
      sampleAge: Long?,
      phase: String,
      error: Boolean,
      expired: Boolean = false,
  ): Boolean =
      power == 0 &&
          screenOn == false &&
          sampleAge != null &&
          sampleAge in 0..MAX_SAMPLE_AGE_MS &&
          phase == "PARKED" &&
          !error &&
          !expired

  fun canCheck(now: Long, previous: Long, minimum: Long, blockedUntil: Long): Boolean =
      now >= blockedUntil && (previous == 0L || now < previous || now - previous >= minimum)

  fun checksum(text: String, fileName: String): String {
    val matches =
        text
            .lineSequence()
            .mapNotNull { line ->
              val match = Regex("^([a-fA-F0-9]{64}) [ *]([^/\\\\]+)$").matchEntire(line.trim())
              match?.takeIf { it.groupValues[2] == fileName }?.groupValues?.get(1)?.lowercase()
            }
            .toList()
    require(matches.size == 1) { "Release checksum is missing or ambiguous" }
    return matches.single()
  }
}
