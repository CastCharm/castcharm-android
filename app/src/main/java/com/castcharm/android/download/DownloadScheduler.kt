package com.castcharm.android.download

// DownloadScheduler manages the phone-side download queue. It is the single
// point of truth for deciding when to dispatch a DownloadWorker and how many
// workers can run concurrently. The concurrency limit is a user-configurable
// DataStore preference (default: 2).
//
// The two key public entry points are:
//   - scheduleDownload(): adds an episode to the queue and calls kickQueue()
//   - kickQueue(): audits all existing download rows against WorkManager state,
//     cleans up terminal rows, and fills open slots with queued episodes
//
// The "null work_request_id" sentinel in DownloadEntity means the episode is
// queued but hasn't been dispatched to WorkManager yet. kickQueue() reads this
// to find which rows are ready to receive a new worker assignment.

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import com.castcharm.android.WIFI_ONLY_DOWNLOADS_KEY
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.entities.DownloadEntity
import com.castcharm.android.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private const val DOWNLOAD_BACKOFF_SECONDS = 30L

// DataStore key for the user-configurable concurrency limit (Settings screen).
private val MAX_CONCURRENT_DOWNLOADS_KEY = intPreferencesKey("max_concurrent_downloads")
private const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 2

class DownloadScheduler(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val episodeDao = db.episodeDao()
    private val downloadDao = db.downloadDao()
    private val workManager = WorkManager.getInstance(context)

    suspend fun scheduleDownload(episodeId: Int): String? {
        val episode = episodeDao.getEpisodeOnce(episodeId)

        // If the episode already has a local file, any leftover download row is
        // stale — clean it up and return. No new download is needed.
        if (episode?.local_path != null) {
            downloadDao.deleteByEpisodeId(episodeId)
            return null
        }

        // Check whether WorkManager already has live work for this episode.
        // A non-null work_request_id means a worker was previously dispatched;
        // look it up to see if it's still active.
        val existing = downloadDao.getDownload(episodeId)
        val existingState = existing?.work_request_id?.let { lookupWorkInfoById(it)?.state }

        // RUNNING/ENQUEUED/BLOCKED all mean WorkManager is actively handling
        // this download — don't duplicate the work.
        val hasActiveExistingWork = when (existingState) {
            WorkInfo.State.RUNNING,
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> true
            else -> false
        }

        if (hasActiveExistingWork) {
            return null
        }

        // Either create a brand-new download row (first time this episode is
        // queued) or reset the existing row so kickQueue() will re-dispatch it.
        if (existing == null) {
            // First-time queue: insert a row with null work_request_id so
            // kickQueue() knows this slot is ready to be dispatched.
            downloadDao.insert(
                DownloadEntity(
                    episode_id = episodeId,
                    work_request_id = null,
                    enqueued_at = System.currentTimeMillis(),
                    progress_pct = 0
                )
            )
        } else {
            // Re-queue: cancel any stale unique work name and reset the ID so
            // the row re-enters the "queued but unassigned" pool. Preserve any
            // partial progress percentage so the UI doesn't jump to 0.
            runCatching { workManager.cancelUniqueWork("download_$episodeId") }
            downloadDao.update(
                existing.copy(
                    work_request_id = null,
                    progress_pct = existing.progress_pct.coerceIn(0, 100)
                )
            )
        }

        // Reflect the queued state in the episode row so the UI can show the
        // "queued" badge immediately, without waiting for kickQueue() to run.
        if (episode != null && episode.local_path == null) {
            episodeDao.update(
                episode.copy(
                    status = "queued",
                    download_progress = existing?.progress_pct ?: 0
                )
            )
        }

        // Trigger the queue processor to fill any open concurrency slots.
        kickQueue()
        return null
    }

    // Alias kept for call sites that specify a wifi-only intent. The actual
    // network constraint (CONNECTED vs. UNMETERED) is applied uniformly in
    // kickQueue() when building the WorkRequest — this wrapper exists for
    // semantic clarity at the call site, not to enforce a separate constraint.
    suspend fun scheduleWifiOnlyDownload(episodeId: Int): String? {
        return scheduleDownload(episodeId)
    }

    suspend fun cancelDownload(episodeId: Int) {
        // Signal WorkManager to stop the running worker if one is assigned.
        // runCatching suppresses IllegalStateException if WorkManager is not yet
        // initialized (possible on first launch before the process is fully set up).
        val download = downloadDao.getDownload(episodeId)
        if (download?.work_request_id != null) {
            runCatching { workManager.cancelUniqueWork("download_$episodeId") }
        }

        // Reset the episode status to "pending" (not downloaded, not in queue).
        // Only do this if the episode doesn't already have a local file — if it
        // does, the "downloaded" status should be preserved.
        val episode = episodeDao.getEpisodeOnce(episodeId)
        if (episode != null && episode.local_path == null) {
            episodeDao.update(
                episode.copy(
                    status = "pending",
                    download_progress = 0
                )
            )
        }

        // Remove the download row entirely. Any partial file on disk will be
        // cleaned up by DownloadWorker's InterruptedException handler.
        downloadDao.deleteByEpisodeId(episodeId)
        // Re-run the queue so the freed concurrency slot can be assigned to
        // the next waiting episode.
        kickQueue()
    }

    // kickQueue() is the core queue processor. It runs after any scheduling
    // event (new download, cancel, completion) to keep the download pool filled
    // up to the user's concurrency limit.
    //
    // Phase 1: Audit all download rows that have a work_request_id assigned,
    //   counting active workers and cleaning up terminal ones.
    // Phase 2: Fill open slots by dispatching DownloadWorker for each queued
    //   (null work_request_id) row, up to the available slot count.
    suspend fun kickQueue() {
        // Read the user-set concurrency limit from DataStore. coerceAtLeast(1)
        // ensures we always allow at least one download even if the preference
        // was somehow set to 0.
        val prefs = context.dataStore.data.first()
        val maxConcurrent = (prefs[MAX_CONCURRENT_DOWNLOADS_KEY] ?: DEFAULT_MAX_CONCURRENT_DOWNLOADS)
            .coerceAtLeast(1)
        val wifiOnly = prefs[WIFI_ONLY_DOWNLOADS_KEY] ?: false
        val requiredNetwork = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        // getAllDownloadsOnceOrdered() returns rows oldest-first (by enqueued_at)
        // so FIFO ordering is preserved when counting active slots.
        val allDownloads = downloadDao.getAllDownloadsOnceOrdered()
        var activeCount = 0

        // Phase 1: walk every download row that has a work_request_id and
        // reconcile the WorkManager state with the DB state.
        for (download in allDownloads) {
            // Rows with null work_request_id haven't been dispatched yet —
            // skip them here; they'll be picked up in Phase 2.
            val requestId = download.work_request_id ?: continue

            // "FAILED_PERMANENT" sentinel means the download exhausted all retries.
            // Leave the row so the UI shows the failure card; don't count it as an
            // active slot and don't touch it here.
            if (requestId == "FAILED_PERMANENT") continue

            val workInfo = lookupWorkInfoById(requestId)
            val episode = episodeDao.getEpisodeOnce(download.episode_id)

            when (workInfo?.state) {
                // Worker is actively running or scheduled — count it as an
                // occupied slot and leave the row alone.
                WorkInfo.State.RUNNING,
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED -> {
                    activeCount++
                }

                // WorkManager reports success. If local_path is set the file is
                // on disk — clean up the download row. If for some reason
                // local_path is still null (e.g., race with mergeFromApi),
                // count it as still active to avoid dispatching a duplicate.
                WorkInfo.State.SUCCEEDED -> {
                    if (episode?.local_path != null) {
                        downloadDao.deleteByEpisodeId(download.episode_id)
                    } else {
                        activeCount++
                    }
                }

                // Worker failed. If the episode already has a file, clean up the stale
                // row. Otherwise reset work_request_id so Phase 2 can re-dispatch.
                // Permanently failed downloads use the "FAILED_PERMANENT" sentinel and
                // are skipped by the continue above, so they never reach this branch.
                WorkInfo.State.FAILED -> {
                    if (episode?.local_path != null) {
                        downloadDao.deleteByEpisodeId(download.episode_id)
                    } else {
                        downloadDao.updateWorkRequestId(download.episode_id, null)
                        if (episode != null) {
                            episodeDao.update(
                                episode.copy(
                                    status = "queued",
                                    download_progress = download.progress_pct.coerceIn(0, 100)
                                )
                            )
                        }
                    }
                }

                // Worker was cancelled (e.g., by cancelDownload()). If the file
                // landed despite cancellation, clean up. Otherwise delete the
                // download row entirely and reset the episode to "pending" so
                // it doesn't show as queued in the UI.
                WorkInfo.State.CANCELLED -> {
                    if (episode?.local_path != null) {
                        downloadDao.deleteByEpisodeId(download.episode_id)
                    } else {
                        downloadDao.deleteByEpisodeId(download.episode_id)
                        if (episode != null) {
                            episodeDao.update(
                                episode.copy(
                                    status = "pending",
                                    download_progress = 0
                                )
                            )
                        }
                    }
                }

                // WorkInfo is null — WorkManager has no record of this ID (e.g.,
                // device rebooted and pruned old work). Count it as active to
                // avoid over-dispatching; it will be reconciled on the next cycle.
                null -> {
                    activeCount++
                }
            }
        }

        // Phase 2: fill open slots.
        val availableSlots = (maxConcurrent - activeCount).coerceAtLeast(0)
        if (availableSlots == 0) return

        // getQueuedDownloads() returns rows with null work_request_id, oldest-first.
        // take(availableSlots) limits dispatch to the number of open concurrency slots.
        val queued = downloadDao.getQueuedDownloads().take(availableSlots)

        queued.forEach { queuedItem ->
            val episodeId = queuedItem.episode_id
            val episode = episodeDao.getEpisodeOnce(episodeId)

            // Double-check: if the episode already has a local file (e.g., it was
            // downloaded by another path since we last read the queue), clean up
            // the stale row and skip dispatch.
            if (episode?.local_path != null) {
                downloadDao.deleteByEpisodeId(episodeId)
                return@forEach
            }


            // Re-read the row to guard against a race where another kickQueue()
            // call already assigned a work_request_id between our getQueuedDownloads()
            // read and now.
            val currentRow = downloadDao.getDownload(episodeId) ?: return@forEach
            if (!currentRow.work_request_id.isNullOrBlank()) {
                return@forEach
            }

            // Cancel any stale unique work entry for this episode ID before
            // enqueueing a fresh one (defensive cleanup).
            runCatching { workManager.cancelUniqueWork("download_$episodeId") }

            // Network constraint respects the Wi-Fi-only preference: UNMETERED
            // waits for a non-metered connection (typically Wi-Fi), CONNECTED
            // allows any network.
            val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("episode_id" to episodeId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(requiredNetwork)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, DOWNLOAD_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

            // ExistingWorkPolicy.REPLACE ensures a fresh worker is always
            // dispatched even if a stale entry exists for the same unique name.
            workManager.enqueueUniqueWork(
                "download_$episodeId",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            // Record the new work_request_id in the download row so DownloadWorker
            // can verify ownership via ownsRow() throughout its execution.
            downloadDao.update(
                currentRow.copy(
                    work_request_id = workRequest.id.toString()
                )
            )

            // Update the episode's visible status to "queued" so the UI reflects
            // that a worker has been assigned. Preserves partial progress percentage.
            if (episode != null && episode.local_path == null) {
                episodeDao.update(
                    episode.copy(
                        status = "queued",
                        download_progress = currentRow.progress_pct.coerceIn(0, 100)
                    )
                )
            }
        }
    }

    // Synchronous point-in-time WorkManager state lookup by unique work name.
    // Returns null if WorkManager has no entry for this episode or if the
    // blocking .get() call throws (e.g., WorkManager not yet initialized).
    fun getDownloadStatus(episodeId: Int): WorkInfo.State? {
        return try {
            val infos = workManager.getWorkInfosForUniqueWork("download_$episodeId").get()
            infos.firstOrNull()?.state
        } catch (_: Exception) {
            null
        }
    }

    // Looks up WorkInfo by the UUID stored in DownloadEntity.work_request_id.
    // Returns null if the ID is malformed, the work no longer exists in
    // WorkManager's DB, or the blocking .get() call throws.
    private fun lookupWorkInfoById(requestId: String): WorkInfo? {
        return try {
            val uuid = UUID.fromString(requestId)
            workManager.getWorkInfoById(uuid).get()
        } catch (_: Exception) {
            null
        }
    }
}