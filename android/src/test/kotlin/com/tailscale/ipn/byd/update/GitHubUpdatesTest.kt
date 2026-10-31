// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd.update

import org.junit.Assert.*
import org.junit.Test

class GitHubUpdatesTest {
  private fun release(
      tag: String = "v1.104.4-byd.7",
      draft: Boolean = false,
      prerelease: Boolean = false,
      url: String =
          "https://github.com/lectops/tailpark/releases/download/$tag/Tailpark-${tag.removePrefix("v")}.apk",
      extra: String = "",
      size: Long = 1024,
  ): String =
      """
    {"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"body":"Release notes","assets":[
      {"name":"Tailpark-${tag.removePrefix("v")}.apk","size":$size,"browser_download_url":"$url"},
      {"name":"SHA256.txt","size":100,"browser_download_url":"https://github.com/lectops/tailpark/releases/download/$tag/SHA256.txt"}$extra
    ]}
  """

  @Test
  fun selectsStableApkAndBoundedNotes() {
    val parsed = GitHubUpdates.parseRelease(release())!!
    assertEquals("1.104.4-byd.7", parsed.version)
    assertEquals("Tailpark-1.104.4-byd.7.apk", parsed.apkName)
    assertEquals("Release notes", parsed.notes)
  }

  @Test
  fun ignoresDraftsPrereleasesAndUnsupportedTags() {
    assertNull(GitHubUpdates.parseRelease(release(draft = true)))
    assertNull(GitHubUpdates.parseRelease(release(prerelease = true)))
    for (tag in
        listOf("v1.105.0-byd.7", "nightly", "v1.104.4-beta-byd.7", "v1.104.4-tabc-gabc-byd.7")) {
      assertNull(GitHubUpdates.parseRelease(release(tag)))
    }
  }

  @Test
  fun rejectsWrongRepositoryAmbiguousAssetsAndUnboundedPayload() {
    assertThrows(IllegalArgumentException::class.java) {
      GitHubUpdates.parseRelease(release(url = "https://evil.invalid/app.apk"))
    }
    assertThrows(IllegalArgumentException::class.java) {
      GitHubUpdates.parseRelease(release(size = UpdatePolicy.MAX_APK_BYTES + 1))
    }
    val extra =
        """,{"name":"SHA256.txt","size":100,"browser_download_url":"https://github.com/lectops/tailpark/releases/download/v1.104.4-byd.7/SHA256.txt"}"""
    assertThrows(java.io.IOException::class.java) {
      GitHubUpdates.parseRelease(release(extra = extra))
    }
  }
}
