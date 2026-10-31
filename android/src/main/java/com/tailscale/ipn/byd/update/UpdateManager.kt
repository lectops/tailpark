// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.byd.BydHelperConnection
import com.tailscale.ipn.byd.BydPreferences
import com.tailscale.ipn.byd.BydUpdateVerifier
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import org.json.JSONObject

data class UpdateState(
    val message: String = "Updates are checked periodically",
    val release: UpdateRelease? = null,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val progress: Int? = null,
    val automatic: Boolean = false,
)

/** All network, archive and shell work runs away from the UI and is serialized. */
object UpdateManager {
  val state = MutableStateFlow(UpdateState())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutex = Mutex()
  private val commitLock = Any()
  @Volatile private var initialized = false
  private const val PERIODIC = "tailpark-updates"
  private const val ATTEMPT = "tailpark-update-attempt"

  private fun prefs(context: Context) =
      context.getSharedPreferences("tailpark_updates", Context.MODE_PRIVATE)

  private fun directory(context: Context) = File(context.filesDir, "updates").apply { mkdirs() }

  private fun apk(context: Context) = File(directory(context), "update.apk")

  @Synchronized
  fun initialize(context: Context) {
    if (initialized) return
    initialized = true
    val preferences = prefs(context)
    val release =
        preferences
            .getString("release", null)
            ?.let {
              runCatching { GitHubUpdates.json.decodeFromString<UpdateRelease>(it) }.getOrNull()
            }
            ?.takeIf { newer(it) }
    val ready = release != null && apk(context).isFile
    if (release == null) {
      apk(context).delete()
      preferences.edit().remove("release").remove("checksum").remove("confirmation").apply()
    }
    state.value =
        UpdateState(
            message =
                if (ready) "Update downloaded; open Updates to install"
                else "Updates are checked periodically",
            release = release,
            ready = ready,
            automatic = preferences.getBoolean("automatic", false),
        )
    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build(),
        )
  }

  @SuppressLint("UseKtx") // Persist opt-out before a pending install can be authorized.
  fun setAutomatic(context: Context, enabled: Boolean) {
    synchronized(commitLock) {
      check(prefs(context).edit().putBoolean("automatic", enabled).commit())
      state.value = state.value.copy(automatic = enabled)
    }
    if (enabled) enqueue(context) else WorkManager.getInstance(context).cancelUniqueWork(ATTEMPT)
  }

  /**
   * Called by the existing helper heartbeat; introduces no new polling or network check cadence.
   */
  fun observeHelperStatus(context: Context, status: JSONObject) {
    if (!prefs(context).getBoolean("automatic", false) || !parked(status)) return
    val now = System.currentTimeMillis()
    synchronized(commitLock) {
      if (!UpdatePolicy.canCheck(now, prefs(context).getLong("attempt_at", 0), 15 * 60 * 1000L, 0))
          return
      prefs(context).edit().putLong("attempt_at", now).apply()
    }
    enqueue(context)
  }

  private fun enqueue(context: Context) {
    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            ATTEMPT,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build(),
        )
  }

  fun check(context: Context) {
    scope.launch { run(context, manual = true) }
  }

  fun download(context: Context) {
    scope.launch { run(context, manual = true, downloadOnly = true) }
  }

  suspend fun run(context: Context, manual: Boolean = false, downloadOnly: Boolean = false) {
    initialize(context)
    mutex.withLock {
      state.value = state.value.copy(busy = true, progress = null)
      try {
        if (!downloadOnly) checkRelease(context, manual)
        val release = state.value.release ?: return@withLock
        val automatic = prefs(context).getBoolean("automatic", false)
        val confirmedFallback =
            prefs(context).getString("confirmation", null) == release.version ||
                prefs(context).getString("rejected", null) == release.version
        if (downloadOnly || (!manual && automatic && freshParked(context) && !confirmedFallback)) {
          if (!state.value.ready) fetch(context, release)
          if (
              !downloadOnly &&
                  !manual &&
                  prefs(context).getBoolean("automatic", false) &&
                  freshParked(context)
          ) {
            attemptAutomatic(context, release)
          }
        }
      } catch (error: Exception) {
        if (!manual && error is SecurityException) {
          state.value.release?.let {
            prefs(context).edit().putString("rejected", it.version).apply()
          }
        }
        state.value = state.value.copy(message = message(error), progress = null)
      } finally {
        state.value = state.value.copy(busy = false)
      }
    }
  }

  private fun checkRelease(context: Context, manual: Boolean) {
    val preferences = prefs(context)
    val now = System.currentTimeMillis()
    if (
        !UpdatePolicy.canCheck(
            now,
            preferences.getLong("checked_at", 0),
            if (manual) UpdatePolicy.MANUAL_INTERVAL_MS else UpdatePolicy.CHECK_INTERVAL_MS,
            preferences.getLong("blocked_until", 0),
        )
    ) {
      if (manual)
          state.value =
              state.value.copy(
                  message = "Please wait before checking again; the last result is shown below"
              )
      return
    }
    preferences.edit().putLong("checked_at", now).apply()
    state.value = state.value.copy(message = "Checking stable GitHub releases…")
    val release =
        try {
          GitHubUpdates.latest()
        } catch (failure: UpdateHttpFailure) {
          if (failure.status == 403 || failure.status == 429) {
            preferences
                .edit()
                .putLong(
                    "blocked_until",
                    maxOf(failure.retryAt, now + UpdatePolicy.CHECK_INTERVAL_MS),
                )
                .apply()
          }
          if (failure.status == 404) {
            state.value = state.value.copy(message = "No stable release is published yet")
            return
          }
          throw failure
        }
    if (release == null)
        throw IOException("The latest release is not a supported stable Tailpark release")
    if (!newer(release)) {
      apk(context).delete()
      preferences.edit().remove("release").remove("checksum").remove("confirmation").apply()
      state.value =
          state.value.copy(message = "Tailpark is up to date", release = null, ready = false)
      return
    }
    val previous = state.value.release
    if (
        previous?.version != release.version ||
            previous.apkUrl != release.apkUrl ||
            previous.size != release.size
    ) {
      apk(context).delete()
      preferences
          .edit()
          .putString("release", GitHubUpdates.json.encodeToString(release))
          .remove("checksum")
          .remove("confirmation")
          .remove("rejected")
          .apply()
      state.value = state.value.copy(ready = false)
    }
    state.value =
        state.value.copy(message = "Tailpark ${release.version} is available", release = release)
  }

  private fun fetch(context: Context, release: UpdateRelease) {
    state.value = state.value.copy(message = "Downloading ${release.version}…", progress = 0)
    val checksum =
        UpdatePolicy.checksum(GitHubUpdates.text(release.checksumUrl, 65536), release.apkName)
    val temporary = File(directory(context), "download.partial")
    try {
      GitHubUpdates.download(release, temporary) { state.value = state.value.copy(progress = it) }
      BydUpdateVerifier.verify(context, temporary, checksum, release.version)
      if (!temporary.renameTo(apk(context))) throw IOException("Cannot retain the verified update")
      prefs(context).edit().putString("checksum", checksum).remove("rejected").apply()
      state.value =
          state.value.copy(
              message = "Update verified and ready to install",
              ready = true,
              progress = null,
          )
    } finally {
      temporary.delete()
    }
  }

  private fun verify(context: Context, release: UpdateRelease): Long =
      try {
        BydUpdateVerifier.verify(
            context,
            apk(context),
            prefs(context).getString("checksum", "") ?: "",
            release.version,
        )
      } catch (error: Exception) {
        apk(context).delete()
        prefs(context).edit().remove("checksum").apply()
        state.value = state.value.copy(ready = false)
        throw error
      }

  private fun attemptAutomatic(context: Context, release: UpdateRelease) {
    verify(context, release)
    state.value = state.value.copy(message = "Preparing parked update…")
    val result = runCatching {
      ShellUpdateClient.install(context, apk(context), release) { commit ->
        synchronized(commitLock) {
          if (prefs(context).getBoolean("automatic", false) && freshParked(context)) {
            commit()
            true
          } else false
        }
      }
    }
        .getOrDefault("CONFIRMATION_REQUIRED")
    state.value =
        state.value.copy(
            message =
                when (result) {
                  "INSTALLED" -> "Update installed; connectivity is recovering"
                  "DEFERRED" -> "Update retained; waiting for fresh ACC-off and screen-off state"
                  else -> {
                    prefs(context).edit().putString("confirmation", release.version).apply()
                    "Update retained; this head unit needs Android install confirmation. Open Updates to install."
                  }
                }
        )
  }

  /** Only an explicit foreground action launches Android's installer or its permission screen. */
  fun installManually(context: Context) {
    scope.launch {
      mutex.withLock {
        val release = state.value.release ?: return@withLock
        try {
          verify(context, release)
          if (!context.packageManager.canRequestPackageInstalls()) {
            state.value =
                state.value.copy(
                    message = "Allow Tailpark to install updates, then return and tap Install again"
                )
            context.startActivity(
                Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return@withLock
          }
          val uri =
              FileProvider.getUriForFile(context, "${context.packageName}.updates", apk(context))
          context.startActivity(
              Intent(Intent.ACTION_VIEW)
                  .setDataAndType(uri, "application/vnd.android.package-archive")
                  .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
          )
          state.value =
              state.value.copy(
                  message =
                      "Finish the update in Android's installer; your login and settings are retained"
              )
        } catch (error: Exception) {
          state.value = state.value.copy(message = message(error))
        }
      }
    }
  }

  private fun freshParked(context: Context): Boolean =
      BydPreferences.shouldResume(context) &&
          !BydPreferences.prefs(context).getBoolean("idle_expired", false) &&
          runCatching { parked(BydHelperConnection.request(context, "STATUS")) }.getOrDefault(false)

  internal fun parked(status: JSONObject): Boolean =
      UpdatePolicy.parked(
          if (status.isNull("powerLevel")) null else status.optInt("powerLevel", -1),
          if (status.isNull("screenOn")) null else status.optBoolean("screenOn", true),
          if (status.isNull("sampleAgeMs")) null else status.optLong("sampleAgeMs", -1),
          status.optString("phase"),
          !status.isNull("tickError") || !status.isNull("error"),
          status.optBoolean("idleExpired") ||
              status.optString("idleState") in setOf("sleep_pending", "expired"),
      )

  private fun newer(release: UpdateRelease): Boolean {
    val installed = TailparkVersion.parse(BuildConfig.VERSION_NAME) ?: return false
    return (TailparkVersion.parse(release.version) ?: return false) > installed
  }

  private fun message(error: Exception): String =
      when (error) {
        is UpdateHttpFailure ->
            if (error.status in listOf(403, 429)) "GitHub rate limit reached; try again later"
            else "Release server returned HTTP ${error.status}; try again later"
        is SecurityException -> error.message ?: "APK validation failed; download a trusted update"
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.SocketTimeoutException ->
            "Update service is unreachable; check your connection and retry"
        else -> "Update could not be completed. Check your connection and retry."
      }
}

class UpdateWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
  override suspend fun doWork(): Result {
    UpdateManager.run(applicationContext)
    return Result.success()
  }
}
