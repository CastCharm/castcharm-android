package com.castcharm.android.download

// DownloadWorker streams an episode audio file from the server to phone storage.
// It is dispatched by DownloadScheduler.kickQueue() and runs on Dispatchers.IO.
//
// Ownership model: DownloadScheduler writes the worker's UUID into the download
// row's work_request_id field before dispatch. DownloadWorker checks ownsRow()
// at multiple checkpoints throughout doWork() — if the UUID doesn't match,
// another worker was dispatched for the same episode (e.g., due to a retry),
// and this worker exits immediately to avoid a double-write race.
//
// Completion is atomic: local_path + local_size_bytes are written and the
// download row is deleted in a single withTransaction block so there is no
// window where the episode looks "downloaded" but the row still exists (or
// vice versa).
//
// Error handling:
//   - InterruptedException (isStopped / coroutine cancellation): discards partial
//     file, resets progress to 0, re-queues with preserveProgress=false.
//   - All other exceptions: deletes partial file, preserves progress pct so the
//     UI shows how far the download got before failing, re-queues for retry.

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.castcharm.android.data.api.ApiKeyInterceptor
import com.castcharm.android.data.api.PersistentCookieJar
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max

private const val MAX_DOWNLOAD_ATTEMPTS = 5

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // episode_id is the only input parameter. -1 is the WorkManager default
        // for a missing int key, so treat it as a permanent failure.
        val episodeId = inputData.getInt("episode_id", -1)
        if (episodeId == -1) return@withContext Result.failure()

        // Capture this worker's UUID as a string for all subsequent ownsRow() checks.
        val myWorkId = id.toString()

        val db = AppDatabase.getDatabase(applicationContext)
        val episodeDao = db.episodeDao()
        val downloadDao = db.downloadDao()
        val scheduler = DownloadScheduler(applicationContext)

        // Tracks the file being written to disk so it can be deleted on failure.
        // Set to null once the download completes successfully (atomic transaction).
        var partialFile: File? = null
        var completedSuccessfully = false

        // Ownership check: verifies that this worker's UUID still matches what
        // DownloadScheduler recorded in the download row. Returns false if the
        // row was deleted (episode cancelled) or reassigned (another dispatch
        // superseded this one), signalling the worker should abort.
        suspend fun ownsRow(): Boolean {
            val row = downloadDao.getDownload(episodeId) ?: return false
            return row.work_request_id == myWorkId
        }

        // Shared cleanup helper called from both catch blocks. Only acts if this
        // worker still owns the row (prevents double-cleanup with a concurrent worker).
        // Clears the work_request_id so DownloadScheduler can re-dispatch,
        // optionally deletes the partial file, and optionally preserves progress.
        suspend fun requeueOwnedDownload(
            preserveProgress: Boolean,
            deletePartial: Boolean
        ) {
            if (!ownsRow()) return

            if (deletePartial) {
                partialFile?.let {
                    if (it.exists()) it.delete()
                }
            }

            val current = episodeDao.getEpisodeOnce(episodeId)
            db.withTransaction {
                // Clear the work_request_id so kickQueue() treats this row as
                // unassigned and will dispatch a fresh worker on its next cycle.
                downloadDao.updateWorkRequestId(episodeId, null)

                if (current != null && current.local_path == null) {
                    episodeDao.update(
                        current.copy(
                            status = "queued",
                            download_progress = if (preserveProgress) {
                                current.download_progress.coerceIn(0, 100)
                            } else {
                                0
                            }
                        )
                    )
                }
            }

            // Kick the queue so the freed concurrency slot can be filled by
            // the next waiting episode.
            runCatching { scheduler.kickQueue() }
        }

        // For retriable errors: retry with WorkManager's backoff up to MAX_DOWNLOAD_ATTEMPTS.
        // On the final attempt, mark the episode as permanently failed and keep the download
        // row (with work_request_id cleared) so the UI can show the failure indicator.
        suspend fun retryOrFail(deletePartial: Boolean): Result {
            if (!ownsRow()) return Result.failure()

            if (deletePartial) {
                partialFile?.let { if (it.exists()) it.delete() }
            }

            val current = episodeDao.getEpisodeOnce(episodeId)

            return if (runAttemptCount >= MAX_DOWNLOAD_ATTEMPTS - 1) {
                // All attempts exhausted — mark as phone_failed. Use "FAILED_PERMANENT"
                // sentinel (not null) so kickQueue() Phase 2 never auto-retries this row
                // even if a server sync resets the episode status back to "downloaded".
                if (current != null) {
                    db.withTransaction {
                        episodeDao.update(current.copy(status = "phone_failed"))
                        downloadDao.updateWorkRequestId(episodeId, "FAILED_PERMANENT")
                    }
                }
                Result.failure()
            } else {
                // More attempts remain — reset status for the backoff wait period
                // and let WorkManager retry with the configured exponential backoff.
                if (current != null && current.local_path == null) {
                    episodeDao.update(current.copy(status = "queued"))
                }
                Result.retry()
            }
        }

        try {
            // Permanent failure if the episode row doesn't exist (shouldn't happen,
            // but guards against a race where the row was deleted before we started).
            val episode = episodeDao.getEpisodeOnce(episodeId) ?: return@withContext Result.failure()

            // Ownership check: if our UUID doesn't match the row, another worker
            // was dispatched for this episode (e.g., from a race in kickQueue()).
            // Exit immediately so only one worker writes the file.
            val row = downloadDao.getDownload(episodeId)
            if (row == null || row.work_request_id != myWorkId) {
                return@withContext Result.failure()
            }

            // Read the server base URL from DataStore. This is needed to build
            // the streaming URL; if it's missing, the server was never configured
            // on this device — re-queue so we can retry after setup completes.
            val serverUrlKey = stringPreferencesKey("server_url")
            val savedUrl = applicationContext.dataStore.data.map { it[serverUrlKey] }.first()
            if (savedUrl.isNullOrBlank()) {
                requeueOwnedDownload(
                    preserveProgress = true,
                    deletePartial = false
                )
                return@withContext Result.failure()
            }

            val baseUrl = if (savedUrl.endsWith("/")) savedUrl else "$savedUrl/"

            // Storage quota check. enforceQuota() deletes oldest played episodes
            // to free space. If there's still not enough room after enforcement,
            // mark the episode failed and remove its download row — it needs manual
            // intervention (user clears storage or raises the quota).
            val storageManager = StorageManager(applicationContext)
            val estimatedSize = episode.enclosure_length ?: 0L
            if (!storageManager.canDownload(estimatedSize)) {
                storageManager.enforceQuota(neededBytes = estimatedSize)
                if (!storageManager.canDownload(estimatedSize)) {
                    if (ownsRow()) {
                        db.withTransaction {
                            episodeDao.updateDownloadStatus(episodeId, "failed")
                            downloadDao.deleteByEpisodeId(episodeId)
                        }
                    }
                    runCatching { scheduler.kickQueue() }
                    return@withContext Result.failure()
                }
            }

            // Build a filesystem-safe filename from the episode title: strip
            // special characters, trim whitespace, cap at 100 chars, and fall back
            // to "episode_<id>" if the result is blank.
            val safeTitle = episode.title
                .replace("""[^\w\s.-]""".toRegex(), "")
                .trim()
                .take(100)
                .ifBlank { "episode_$episodeId" }

            // Determine the file extension. Priority order:
            //   1. MIME type from the enclosure (most reliable)
            //   2. File extension from the enclosure URL
            //   3. Default to mp3 as the most common podcast format
            val extension = when {
                episode.enclosure_type?.contains("mp3") == true -> "mp3"
                episode.enclosure_type?.contains("mp4") == true ||
                        episode.enclosure_type?.contains("m4a") == true -> "m4a"
                episode.enclosure_type?.contains("ogg") == true -> "ogg"
                episode.enclosure_url?.substringAfterLast('.')?.take(4)
                    ?.let { it in listOf("mp3", "m4a", "ogg", "flac", "opus", "aac") } == true ->
                    episode.enclosure_url!!.substringAfterLast('.').take(4)
                else -> "mp3"
            }

            // getDownloadPath() prefixes the filename with episodeId_ to guarantee
            // uniqueness even when two episodes have the same title.
            val downloadFile = storageManager.getDownloadPath(episodeId, "$safeTitle.$extension")
            // Stream into a worker-specific temp file and rename on completion,
            // so a cancelled or crashed download never leaves a truncated file
            // under the final name (which a later scan would mistake for complete).
            val tempFile = File(downloadFile.parentFile, downloadFile.name + ".$myWorkId.part")
            partialFile = tempFile

            // Final ownership check before we start writing — avoids beginning
            // a potentially large download if we've already been superseded.
            if (!ownsRow()) return@withContext Result.failure()

            // Transition status to "downloading" so the UI shows the active spinner.
            episodeDao.update(
                episode.copy(
                    status = "downloading",
                    download_progress = 0
                )
            )
            downloadDao.updateProgressPct(episodeId, 0)

            // Report initial progress to any WorkManager observers (e.g., the
            // DownloadsViewModel watching for live progress updates).
            setProgress(
                workDataOf(
                    "status" to "starting",
                    "bytes_downloaded" to 0L,
                    "total_bytes" to 0L,
                    "progress_percent" to 0,
                    "speed_bps" to 0L
                )
            )

            // Build a dedicated OkHttpClient for the streaming download.
            // ApiKeyInterceptor supplies the credential; the cookie jar stays as a
            // fallback for servers too old to issue keys. readTimeout(0) disables
            // the read deadline — without this, a large file or slow connection
            // would time out mid-stream after the default 10s idle window.
            val client = OkHttpClient.Builder()
                .cookieJar(PersistentCookieJar.getInstance(applicationContext))
                .addInterceptor(ApiKeyInterceptor(applicationContext))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            val response = client.newCall(
                Request.Builder()
                    .url("${baseUrl}api/episodes/$episodeId/stream")
                    .build()
            ).execute()

            // Non-2xx response or missing body: retry with exponential backoff up to
            // MAX_DOWNLOAD_ATTEMPTS. On the final attempt, mark permanently failed.
            if (!response.isSuccessful) {
                return@withContext retryOrFail(deletePartial = false)
            }

            val body = response.body ?: return@withContext retryOrFail(deletePartial = false)

            // Determine total size for progress calculation. Priority:
            //   1. Content-Length from the HTTP response (most accurate)
            //   2. enclosure_length from the RSS feed (known from metadata sync)
            //   3. -1 if unknown (progress will always show 0%)
            val reportedTotalBytes = body.contentLength()
            val effectiveTotalBytes = when {
                reportedTotalBytes > 0L -> reportedTotalBytes
                (episode.enclosure_length ?: 0L) > 0L -> episode.enclosure_length!!
                else -> -1L
            }

            val startedAt = System.currentTimeMillis()
            var lastUiUpdateAt = 0L
            var lastReportedPct = -1

            // Streaming copy loop. 64 KB buffer balances memory use and I/O calls.
            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesCopied = 0L

                    while (true) {
                        // ensureActive() throws CancellationException if the coroutine
                        // was cancelled (e.g., app killed by system). isStopped is the
                        // WorkManager signal — both result in an InterruptedException
                        // caught below to trigger cleanup.
                        ensureActive()
                        if (isStopped) {
                            throw InterruptedException("Download stopped")
                        }

                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break  // End of stream — download complete.

                        output.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead

                        val now = System.currentTimeMillis()
                        val elapsedMs = max(1L, now - startedAt)
                        // Instantaneous speed averaged over total elapsed time.
                        val speedBps = bytesCopied * 1000L / elapsedMs
                        val pct = if (effectiveTotalBytes > 0L) {
                            ((bytesCopied * 100L) / effectiveTotalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }

                        // Throttle DB and WorkManager progress writes: update when
                        // at least 1 percentage point has changed OR at least 1 second
                        // has passed — whichever comes first. This avoids hammering the
                        // DB on every 64 KB buffer fill for large files.
                        val shouldReport =
                            pct >= lastReportedPct + 1 ||
                                    now - lastUiUpdateAt >= 1000L

                        if (shouldReport) {
                            // Ownership check inside the loop. If we lost ownership
                            // (row deleted or reassigned), abort the stream immediately.
                            if (!ownsRow()) {
                                throw InterruptedException("Ownership lost")
                            }

                            if (pct > lastReportedPct) {
                                episodeDao.updateDownloadProgress(episodeId, pct)
                                downloadDao.updateProgressPct(episodeId, pct)
                                lastReportedPct = pct
                            }

                            setProgress(
                                workDataOf(
                                    "status" to "downloading",
                                    "bytes_downloaded" to bytesCopied,
                                    "total_bytes" to effectiveTotalBytes,
                                    "progress_percent" to pct,
                                    "speed_bps" to speedBps
                                )
                            )

                            lastUiUpdateAt = now
                        }
                    }
                }
            }

            // Final ownership check after the stream closes but before committing.
            if (!ownsRow()) return@withContext Result.failure()

            if (downloadFile.exists()) downloadFile.delete()
            if (!tempFile.renameTo(downloadFile)) {
                throw java.io.IOException("Could not move finished download into place")
            }

            // Atomic completion: write local_path + size and delete the download
            // row in a single transaction. This prevents any window where the
            // episode shows as "downloaded" but the queue row still exists (or
            // vice versa), which would confuse kickQueue() on the next cycle.
            val finalSize = downloadFile.length()
            db.withTransaction {
                episodeDao.updateDownloadComplete(
                    episodeId = episodeId,
                    localPath = downloadFile.absolutePath,
                    localSizeBytes = finalSize
                )
                downloadDao.deleteByEpisodeId(episodeId)
            }
            // Record for the "downloaded this month" indicator on the
            // Downloads screen. Kept out of the transaction so a DataStore
            // hiccup can't roll back the completed download.
            BandwidthTracker.record(applicationContext, finalSize)

            completedSuccessfully = true
            // Clear partialFile so the catch blocks don't attempt to delete
            // the fully-written file if an exception somehow fires after this.
            partialFile = null

            setProgress(
                workDataOf(
                    "status" to "completed",
                    "bytes_downloaded" to downloadFile.length(),
                    "total_bytes" to downloadFile.length(),
                    "progress_percent" to 100,
                    "speed_bps" to 0L
                )
            )

            // Trigger the queue so the freed concurrency slot fills immediately.
            runCatching { scheduler.kickQueue() }
            Result.success()
        } catch (e: InterruptedException) {
            // Intentional stop: WorkManager signalled isStopped, or we detected
            // ownership loss. Don't preserve progress — the download will restart
            // cleanly from 0 on the next attempt.
            if (!completedSuccessfully) {
                requeueOwnedDownload(
                    preserveProgress = false,
                    deletePartial = true
                )
            }

            if (completedSuccessfully) Result.success() else Result.failure()
        } catch (e: Exception) {
            // Unexpected error (network blip, disk full, etc.). Retry with backoff
            // up to MAX_DOWNLOAD_ATTEMPTS; permanently fail after that.
            e.printStackTrace()
            if (completedSuccessfully) Result.success() else retryOrFail(deletePartial = true)
        } finally {
            // The temp file is ours alone (named with this worker's id), so it is
            // always safe to remove — and this must run even when the coroutine
            // was cancelled, which is exactly when the catch blocks above cannot
            // reach their suspend calls.
            if (!completedSuccessfully) {
                withContext(NonCancellable) {
                    partialFile?.let { runCatching { if (it.exists()) it.delete() } }
                }
            }
        }
    }
}