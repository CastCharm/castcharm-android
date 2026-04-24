package com.castcharm.android.download

import android.content.Context
import android.content.SharedPreferences
import com.castcharm.android.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class StorageManager(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val episodeDao = db.episodeDao()
    private val downloadDir = File(context.getExternalFilesDir(null), "downloads")
    private val prefs: SharedPreferences = context.getSharedPreferences("storage", Context.MODE_PRIVATE)

    private var quotaBytes: Long = prefs.getLong("quota_bytes", DEFAULT_QUOTA_GB * GB)

    init {
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
    }

    suspend fun getTotalUsedBytes(): Long = withContext(Dispatchers.IO) {
        episodeDao.getTotalDownloadedBytes() ?: 0L
    }

    fun getQuotaBytes(): Long = quotaBytes

    suspend fun getAvailableBytes(): Long {
        val used = getTotalUsedBytes()
        return (quotaBytes - used).coerceAtLeast(0)
    }

    suspend fun canDownload(fileSize: Long): Boolean {
        return getTotalUsedBytes() + fileSize <= quotaBytes
    }

    fun setQuota(quotaGb: Long) {
        quotaBytes = quotaGb * GB
        prefs.edit().putLong("quota_bytes", quotaBytes).apply()
    }

    suspend fun enforceQuota() = withContext(Dispatchers.IO) {
        val used = getTotalUsedBytes()
        if (used > quotaBytes) {
            val excessBytes = used - quotaBytes
            deleteOldestEpisodes(excessBytes)
        }
    }

    private suspend fun deleteOldestEpisodes(bytesToFree: Long) = withContext(Dispatchers.IO) {
        var freedBytes = 0L
        val episodes = episodeDao.getOldestPlayedEpisodesGlobal(100)

        for (episode in episodes) {
            if (freedBytes >= bytesToFree) break

            episode.local_path?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    val fileSize = file.length()
                    if (file.delete()) {
                        episodeDao.update(
                            episode.copy(
                                local_path = null,
                                local_size_bytes = null,
                                status = "pending"
                            )
                        )
                        freedBytes += fileSize
                    }
                }
            }
        }
    }

    suspend fun clearAllDownloads() = withContext(Dispatchers.IO) {
        val episodes = episodeDao.getDownloadedEpisodesOnce()
        for (episode in episodes) {
            episode.local_path?.let { path ->
                File(path).delete()
                episodeDao.update(
                    episode.copy(local_path = null, local_size_bytes = null, status = "pending")
                )
            }
        }
    }

    fun getDownloadPath(episodeId: Int, fileName: String): File {
        return File(downloadDir, "${episodeId}_$fileName")
    }

    companion object {
        private const val GB = 1024L * 1024L * 1024L
        private const val DEFAULT_QUOTA_GB = 5L
    }
}
