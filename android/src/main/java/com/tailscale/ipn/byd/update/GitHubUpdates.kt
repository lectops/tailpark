// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UpdateRelease(
    val version: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val checksumUrl: String,
    val size: Long,
)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val body: String? = null,
    val assets: List<GitHubAsset>,
)

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long,
)

class UpdateHttpFailure(val status: Int, val retryAt: Long = 0) : IOException("Update HTTP $status")

/** HTTPS only; redirects never forward credentials and remain on GitHub's asset hosts. */
object GitHubUpdates {
  val json = Json { ignoreUnknownKeys = true }
  private const val API = "https://api.github.com/repos/lectops/tailpark/releases/latest"

  fun parseRelease(body: String): UpdateRelease? {
    val release = json.decodeFromString<GitHubRelease>(body)
    if (release.draft || release.prerelease) return null
    if (!release.tag.matches(Regex("^v?[0-9]+\\.[0-9]+\\.[0-9]+-byd\\.[0-9]+$"))) return null
    val version = TailparkVersion.parse(release.tag) ?: return null
    if (version.minor % 2 != 0L) return null
    val normalized = release.tag.removePrefix("v")
    val apkName = "Tailpark-$normalized.apk"
    val apk =
        release.assets.singleOrNull { it.name == apkName }
            ?: throw IOException("Release APK is missing or ambiguous")
    val checksum =
        release.assets.singleOrNull { it.name == "SHA256.txt" }
            ?: throw IOException("Release checksum is missing or ambiguous")
    val prefix = "https://github.com/lectops/tailpark/releases/download/${release.tag}/"
    require(apk.url == prefix + apkName && checksum.url == prefix + "SHA256.txt") {
      "Release asset is outside the Tailpark repository"
    }
    require(apk.size in 1..UpdatePolicy.MAX_APK_BYTES && checksum.size in 1..65536)
    return UpdateRelease(
        normalized,
        release.body.orEmpty().take(16000),
        apkName,
        apk.url,
        checksum.url,
        apk.size,
    )
  }

  fun latest(): UpdateRelease? = parseRelease(text(API, 1024 * 1024))

  fun text(url: String, maximum: Int): String =
      connection(url).let { connection ->
        try {
          connection.inputStream.use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
              val count = input.read(buffer)
              if (count < 0) break
              if (bytes.size() + count > maximum) throw IOException("Update response is too large")
              bytes.write(buffer, 0, count)
            }
            bytes.toString("UTF-8")
          }
        } finally {
          connection.disconnect()
        }
      }

  fun download(release: UpdateRelease, destination: File, progress: (Int) -> Unit) {
    val connection = connection(release.apkUrl)
    try {
      connection.inputStream.use { input ->
        destination.outputStream().use { output ->
          val buffer = ByteArray(65536)
          var total = 0L
          var lastPercent = -1
          while (true) {
            if (Thread.currentThread().isInterrupted) throw IOException("Download interrupted")
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > release.size || total > UpdatePolicy.MAX_APK_BYTES)
                throw IOException("APK exceeds release size")
            output.write(buffer, 0, count)
            val percent = (100 * total / release.size).toInt()
            if (percent != lastPercent) {
              progress(percent)
              lastPercent = percent
            }
          }
          if (total != release.size) throw IOException("APK download was incomplete")
        }
      }
    } finally {
      connection.disconnect()
    }
  }

  private fun connection(address: String): HttpURLConnection {
    var url = URL(address)
    repeat(6) {
      require(url.protocol == "https" && url.port in listOf(-1, 443) && url.userInfo == null)
      require(
          url.host in
              setOf(
                  "api.github.com",
                  "github.com",
                  "release-assets.githubusercontent.com",
                  "objects.githubusercontent.com",
              )
      )
      val connection =
          (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Tailpark-Android-Updater")
            setRequestProperty(
                "Accept",
                if (url.host == "api.github.com") "application/vnd.github+json"
                else "application/octet-stream",
            )
          }
      val status = connection.responseCode
      if (status in listOf(301, 302, 303, 307, 308)) {
        val next =
            connection.getHeaderField("Location") ?: throw IOException("Invalid update redirect")
        connection.disconnect()
        url = URL(url, next)
      } else if (status != 200) {
        val reset = connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.times(1000) ?: 0
        val retry =
            connection
                .getHeaderField("Retry-After")
                ?.toLongOrNull()
                ?.times(1000)
                ?.plus(System.currentTimeMillis()) ?: 0
        connection.disconnect()
        throw UpdateHttpFailure(status, maxOf(reset, retry))
      } else return connection
    }
    throw IOException("Too many update redirects")
  }
}
