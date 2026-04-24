package com.castcharm.android.download

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.entities.DownloadEntity
import com.castcharm.android.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val MAX_CONCURRENT_DOWNLOADS_KEY = intPreferencesKey("max_concurrent_downloads")
private const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 2

class DownloadScheduler(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val episodeDao = db.episodeDao()
    private val downloadDao = db.downloadDao()
    private val workManager = WorkManager.getInstance(context)

    suspend fun scheduleDownload(episodeId: Int): String? {
        val episode = episodeDao.getEpisodeOnce(episodeId)

        if (episode?.local_path != null) {
            downloadDao.deleteByEpisodeId(episodeId)
            return null
        }

        val existing = downloadDao.getDownload(episodeId)
        val existingState = existing?.work_request_id?.let { lookupWorkInfoById(it)?.state }

        val hasActiveExistingWork = when (existingState) {
            WorkInfo.State.RUNNING,
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> true
            else -> false
        }

        if (hasActiveExistingWork) {
            return null
        }

        if (existing == null) {
            downloadDao.insert(
                DownloadEntity(
                    episode_id = episodeId,
                    work_request_id = null,
                    enqueued_at = System.currentTimeMillis(),
                    progress_pct = 0
                )
            )
        } else {
            runCatching { workManager.cancelUniqueWork("download_$episodeId") }
            downloadDao.update(
                existing.copy(
                    work_request_id = null,
                    progress_pct = existing.progress_pct.coerceIn(0, 100)
                )
            )
        }

        if (episode != null && episode.local_path == null) {
            episodeDao.update(
                episode.copy(
                    status = "queued",
                    download_progress = existing?.progress_pct ?: 0
                )
            )
        }

        kickQueue()
        return null
    }

    suspend fun scheduleWifiOnlyDownload(episodeId: Int): String? {
        return scheduleDownload(episodeId)
    }

    suspend fun cancelDownload(episodeId: Int) {
        val download = downloadDao.getDownload(episodeId)
        if (download?.work_request_id != null) {
            runCatching { workManager.cancelUniqueWork("download_$episodeId") }
        }

        val episode = episodeDao.getEpisodeOnce(episodeId)
        if (episode != null && episode.local_path == null) {
            episodeDao.update(
                episode.copy(
                    status = "pending",
                    download_progress = 0
                )
            )
        }

        downloadDao.deleteByEpisodeId(episodeId)
        kickQueue()
    }

    suspend fun kickQueue() {
        val maxConcurrent = context.dataStore.data
            .map { it[MAX_CONCURRENT_DOWNLOADS_KEY] ?: DEFAULT_MAX_CONCURRENT_DOWNLOADS }
            .first()
            .coerceAtLeast(1)

        val allDownloads = downloadDao.getAllDownloadsOnceOrdered()
        var activeCount = 0

        for (download in allDownloads) {
            val requestId = download.work_request_id ?: continue
            val workInfo = lookupWorkInfoById(requestId)
            val episode = episodeDao.getEpisodeOnce(download.episode_id)

            when (workInfo?.state) {
                WorkInfo.State.RUNNING,
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED -> {
                    activeCount++
                }

                WorkInfo.State.SUCCEEDED -> {
                    if (episode?.local_path != null) {
                        downloadDao.deleteByEpisodeId(download.episode_id)
                    } else {
                        activeCount++
                    }
                }

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

                null -> {
                    activeCount++
                }
            }
        }

        val availableSlots = (maxConcurrent - activeCount).coerceAtLeast(0)
        if (availableSlots == 0) return

        val queued = downloadDao.getQueuedDownloads().take(availableSlots)

        queued.forEach { queuedItem ->
            val episodeId = queuedItem.episode_id
            val episode = episodeDao.getEpisodeOnce(episodeId)

            if (episode?.local_path != null) {
                downloadDao.deleteByEpisodeId(episodeId)
                return@forEach
            }

            val currentRow = downloadDao.getDownload(episodeId) ?: return@forEach
            if (!currentRow.work_request_id.isNullOrBlank()) {
                return@forEach
            }

            runCatching { workManager.cancelUniqueWork("download_$episodeId") }

            val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("episode_id" to episodeId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            workManager.enqueueUniqueWork(
                "download_$episodeId",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            downloadDao.update(
                currentRow.copy(
                    work_request_id = workRequest.id.toString()
                )
            )

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

    fun getDownloadStatus(episodeId: Int): WorkInfo.State? {
        return try {
            val infos = workManager.getWorkInfosForUniqueWork("download_$episodeId").get()
            infos.firstOrNull()?.state
        } catch (_: Exception) {
            null
        }
    }

    private fun lookupWorkInfoById(requestId: String): WorkInfo? {
        return try {
            val uuid = UUID.fromString(requestId)
            workManager.getWorkInfoById(uuid).get()
        } catch (_: Exception) {
            null
        }
    }
}