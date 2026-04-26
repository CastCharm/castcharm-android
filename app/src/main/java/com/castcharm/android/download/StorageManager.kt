package com.castcharm.android.download

// StorageManager handles the phone's download directory and enforces the user-
// configurable storage quota. The quota is stored in SharedPreferences so it
// persists across app restarts. The default quota is 5 GB.
//
// The download directory lives in external files (getExternalFilesDir) so files
// are scoped to the app and deleted automatically on uninstall. They are NOT
// accessible to other apps without explicit sharing.
//
// enforceQuota() deletes the oldest played episodes first (FIFO by last_played_at)
// until usage is under the quota. Unplayed episodes are never automatically deleted.

import android.content.Context
import android.content.SharedPreferences
import com.castcharm.android.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class StorageManager(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val episodeDao = db.episodeDao()
    // Base directory for all phone-side episode downloads.
    private val downloadDir = File(context.getExternalFilesDir(null), "downloads")
    private val prefs: SharedPreferences = context.getSharedPreferences("storage", Context.MODE_PRIVATE)

    // In-memory cache of the quota; reads from prefs on construction.
    private var quotaBytes: Long = prefs.getLong("quota_bytes", DEFAULT_QUOTA_GB * GB)

    init {
        // Create the download directory if it doesn't exist yet (first launch).
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
    }

    // Returns total bytes used by all locally downloaded episode files, as tracked
    // in the DB (SUM of local_size_bytes). Falls back to 0 if no rows exist.
    suspend fun getTotalUsedBytes(): Long = withContext(Dispatchers.IO) {
        episodeDao.getTotalDownloadedBytes() ?: 0L
    }

    fun getQuotaBytes(): Long = quotaBytes

    // Returns how many bytes are still available under the quota, clamped to 0.
    suspend fun getAvailableBytes(): Long {
        val used = getTotalUsedBytes()
        return (quotaBytes - used).coerceAtLeast(0)
    }

    // Quick pre-flight check used by DownloadWorker before starting a download.
    // Returns false if adding fileSize would exceed the quota.
    suspend fun canDownload(fileSize: Long): Boolean {
        return getTotalUsedBytes() + fileSize <= quotaBytes
    }

    // Persists the new quota setting immediately so it survives app kill.
    fun setQuota(quotaGb: Long) {
        quotaBytes = quotaGb * GB
        prefs.edit().putLong("quota_bytes", quotaBytes).apply()
    }

    // Checks if the current usage exceeds the quota and deletes oldest played
    // episodes until usage is at or below the limit. Called by DownloadWorker
    // before beginning a download.
    suspend fun enforceQuota() = withContext(Dispatchers.IO) {
        val used = getTotalUsedBytes()
        if (used > quotaBytes) {
            val excessBytes = used - quotaBytes
            deleteOldestEpisodes(excessBytes)
        }
    }

    // Iterates the oldest played episodes (by last_played_at ASC) and deletes
    // their files from disk + resets their DB state, stopping as soon as enough
    // bytes have been freed. Episodes without a local file are skipped silently.
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
                        // Reset DB row: clear path/size and put status back to "pending"
                        // so the episode can be re-downloaded from the server if needed.
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

    // Deletes all locally downloaded episode files and resets their DB rows.
    // Used by the "Clear all downloads" button in SettingsScreen.
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

    // Returns the File where a downloaded episode should be saved.
    // Format: <downloadDir>/<episodeId>_<fileName>
    // The episodeId prefix ensures uniqueness even when two episodes have
    // identically-named files.
    fun getDownloadPath(episodeId: Int, fileName: String): File {
        return File(downloadDir, "${episodeId}_$fileName")
    }

    companion object {
        private const val GB = 1024L * 1024L * 1024L
        private const val DEFAULT_QUOTA_GB = 5L
    }
}
