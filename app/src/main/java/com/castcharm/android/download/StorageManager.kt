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
import com.castcharm.android.CastCharmApp
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

    // Returns total bytes used by everything the phone holds for offline use:
    // the episode files (tracked in the DB as SUM of local_size_bytes) plus the
    // stored cover art.
    //
    // Artwork is measured from disk because there is no column to record it in —
    // see LocalArtwork. It is a stat per file, bounded by the number of feeds
    // plus downloaded episodes, against a DB aggregate that already costs a
    // query. Counting it matters because it is space the user cannot see in any
    // listing: left out, a quota set to fill the device would be quietly
    // overshot by everything the artwork occupies.
    suspend fun getTotalUsedBytes(): Long = withContext(Dispatchers.IO) {
        (episodeDao.getTotalDownloadedBytes() ?: 0L) + LocalArtwork.totalBytes(context)
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

    // Frees room for *neededBytes* more by deleting the oldest played episodes
    // until used + needed fits the quota. Called by DownloadWorker before it
    // starts writing. With no argument it just trims usage back to the quota.
    suspend fun enforceQuota(neededBytes: Long = 0L) = withContext(Dispatchers.IO) {
        val used = getTotalUsedBytes()
        val excess = used + neededBytes - quotaBytes
        if (excess > 0) {
            deleteOldestEpisodes(excess)
        }
    }

    /**
     * Frees the artwork belonging to an episode whose file has just been deleted,
     * and the feed's cover once its last download is gone. Returns bytes freed.
     *
     * Must be called AFTER the episode's row has been cleared, so the "any
     * downloads left for this feed" check sees the current state.
     *
     * Public because deleting a download is not funnelled through this class:
     * DownloadsViewModel unlinks files itself in three places rather than going
     * through deleteLocalFile(), because it deliberately does not reset the
     * episode's status the way this class does. They must call this, or the
     * artwork they leave behind keeps counting against the quota with nothing on
     * screen to explain it.
     */
    suspend fun releaseArtworkFor(episodeId: Int, feedId: Int): Long {
        var freed = LocalArtwork.deleteEpisode(context, episodeId)
        if (episodeDao.getDownloadedEpisodeIdsByFeed(feedId).isEmpty()) {
            freed += LocalArtwork.deleteFeed(context, feedId)
        }
        return freed
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
                        episodeDao.clearLocalFile(episode.id)
                        freedBytes += fileSize + releaseArtworkFor(episode.id, episode.feed_id)
                    }
                }
            }
        }
    }

    // Deletes a single downloaded episode's file and clears its local_path/size
    // in the DB. Returns true if a file was actually removed. Used by the
    // batch-delete action on EpisodeListScreen.
    suspend fun deleteLocalFile(episodeId: Int): Boolean = withContext(Dispatchers.IO) {
        val episode = episodeDao.getEpisodeOnce(episodeId) ?: return@withContext false
        val path = episode.local_path ?: return@withContext false
        val file = File(path)
        val removed = if (file.exists()) file.delete() else false
        episodeDao.clearLocalFile(episodeId)
        releaseArtworkFor(episodeId, episode.feed_id)
        removed
    }

    // Deletes all locally downloaded episode files and resets their DB rows.
    // Used by the "Clear all downloads" button in SettingsScreen.
    suspend fun clearAllDownloads() = withContext(Dispatchers.IO) {
        val episodes = episodeDao.getDownloadedEpisodesOnce()
        for (episode in episodes) {
            episode.local_path?.let { path ->
                File(path).delete()
                episodeDao.clearLocalFile(episode.id)
            }
        }
        sweepPartFiles()
        // The stored covers exist to serve those downloads offline, so they go
        // with them — otherwise "clear all downloads" leaves files behind that
        // the user has no way to see or reclaim.
        LocalArtwork.deleteAll(context)
    }

    // Removes leftovers of downloads that never finished (DownloadWorker writes
    // to "<name>.<workId>.part" and renames on completion).
    fun sweepPartFiles() {
        downloadDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { runCatching { it.delete() } }
    }

    // Re-links audio files in the download folder that the database no longer
    // knows about (an older destructive migration, a pruned or hidden episode).
    // The filename starts with the episode id, which is all we need. Files for
    // episodes the server no longer has are deleted; anything still being
    // written (a downloads row, or a .part file) is left alone.
    suspend fun reconcileOrphanFiles(api: com.castcharm.android.data.api.CastCharmApi) = withContext(Dispatchers.IO) {
        val files = downloadDir.listFiles() ?: return@withContext
        val inFlight = db.downloadDao().getAllDownloadsOnceOrdered().map { it.episode_id }.toSet()
        val repo = com.castcharm.android.data.repository.EpisodeRepository(api, episodeDao)
        for (file in files) {
            if (!file.isFile || file.name.endsWith(".part")) continue
            val id = Regex("^(\\d+)_").find(file.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (id in inFlight) continue
            val row = episodeDao.getEpisodeOnce(id)
            when {
                row?.local_path == file.absolutePath -> continue           // already linked
                row?.local_path != null -> continue                        // linked to another copy
                row != null -> link(row.id, file)
                else -> {
                    val fetched = runCatching { repo.fetchEpisodeFromApi(id) }.getOrNull()
                    if (fetched != null) link(fetched.id, file)
                    else if (CastCharmApp.apiClient.isInitialized && !CastCharmApp.isOfflineMode) {
                        // Server says it doesn't exist: the file is just taking space.
                        runCatching { file.delete() }
                    }
                }
            }
        }
    }

    private suspend fun link(episodeId: Int, file: File) {
        val row = episodeDao.getEpisodeOnce(episodeId) ?: return
        // A truncated leftover must not be presented as a complete download.
        val expected = row.enclosure_length ?: 0L
        if (expected > 0 && file.length() < expected * 95 / 100) {
            runCatching { file.delete() }
            return
        }
        episodeDao.updateDownloadComplete(episodeId, file.absolutePath, file.length())
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
