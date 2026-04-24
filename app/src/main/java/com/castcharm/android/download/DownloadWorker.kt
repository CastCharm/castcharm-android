package com.castcharm.android.download

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.castcharm.android.data.api.PersistentCookieJar
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val episodeId = inputData.getInt("episode_id", -1)
        if (episodeId == -1) return@withContext Result.failure()

        val myWorkId = id.toString()

        val db = AppDatabase.getDatabase(applicationContext)
        val episodeDao = db.episodeDao()
        val downloadDao = db.downloadDao()
        val scheduler = DownloadScheduler(applicationContext)

        var partialFile: File? = null
        var completedSuccessfully = false

        suspend fun ownsRow(): Boolean {
            val row = downloadDao.getDownload(episodeId) ?: return false
            return row.work_request_id == myWorkId
        }

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

            runCatching { scheduler.kickQueue() }
        }

        try {
            val episode = episodeDao.getEpisodeOnce(episodeId) ?: return@withContext Result.failure()

            val row = downloadDao.getDownload(episodeId)
            if (row == null || row.work_request_id != myWorkId) {
                return@withContext Result.failure()
            }

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

            val storageManager = StorageManager(applicationContext)
            val estimatedSize = episode.enclosure_length ?: 0L
            if (!storageManager.canDownload(estimatedSize)) {
                storageManager.enforceQuota()
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

            val safeTitle = episode.title
                .replace("""[^\w\s.-]""".toRegex(), "")
                .trim()
                .take(100)
                .ifBlank { "episode_$episodeId" }

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

            val downloadFile = storageManager.getDownloadPath(episodeId, "$safeTitle.$extension")
            partialFile = downloadFile

            if (!ownsRow()) return@withContext Result.failure()

            episodeDao.update(
                episode.copy(
                    status = "downloading",
                    download_progress = 0
                )
            )
            downloadDao.updateProgressPct(episodeId, 0)

            setProgress(
                workDataOf(
                    "status" to "starting",
                    "bytes_downloaded" to 0L,
                    "total_bytes" to 0L,
                    "progress_percent" to 0,
                    "speed_bps" to 0L
                )
            )

            val client = OkHttpClient.Builder()
                .cookieJar(PersistentCookieJar(applicationContext))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            val response = client.newCall(
                Request.Builder()
                    .url("${baseUrl}api/episodes/$episodeId/stream")
                    .build()
            ).execute()

            if (!response.isSuccessful) {
                requeueOwnedDownload(
                    preserveProgress = true,
                    deletePartial = false
                )
                return@withContext Result.failure()
            }

            val body = response.body ?: run {
                requeueOwnedDownload(
                    preserveProgress = true,
                    deletePartial = false
                )
                return@withContext Result.failure()
            }

            val reportedTotalBytes = body.contentLength()
            val effectiveTotalBytes = when {
                reportedTotalBytes > 0L -> reportedTotalBytes
                (episode.enclosure_length ?: 0L) > 0L -> episode.enclosure_length!!
                else -> -1L
            }

            val startedAt = System.currentTimeMillis()
            var lastUiUpdateAt = 0L
            var lastReportedPct = -1

            body.byteStream().use { input ->
                downloadFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesCopied = 0L

                    while (true) {
                        ensureActive()
                        if (isStopped) {
                            throw InterruptedException("Download stopped")
                        }

                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break

                        output.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead

                        val now = System.currentTimeMillis()
                        val elapsedMs = max(1L, now - startedAt)
                        val speedBps = bytesCopied * 1000L / elapsedMs
                        val pct = if (effectiveTotalBytes > 0L) {
                            ((bytesCopied * 100L) / effectiveTotalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }

                        val shouldReport =
                            pct >= lastReportedPct + 1 ||
                                    now - lastUiUpdateAt >= 1000L

                        if (shouldReport) {
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

            if (!ownsRow()) return@withContext Result.failure()

            db.withTransaction {
                episodeDao.updateDownloadComplete(
                    episodeId = episodeId,
                    localPath = downloadFile.absolutePath,
                    localSizeBytes = downloadFile.length()
                )
                downloadDao.deleteByEpisodeId(episodeId)
            }

            completedSuccessfully = true
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

            runCatching { scheduler.kickQueue() }
            Result.success()
        } catch (e: InterruptedException) {
            if (!completedSuccessfully) {
                requeueOwnedDownload(
                    preserveProgress = false,
                    deletePartial = true
                )
            }

            if (completedSuccessfully) Result.success() else Result.failure()
        } catch (e: Exception) {
            e.printStackTrace()

            if (!completedSuccessfully) {
                requeueOwnedDownload(
                    preserveProgress = true,
                    deletePartial = true
                )
            }

            if (completedSuccessfully) Result.success() else Result.failure()
        }
    }
}