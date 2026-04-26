package com.castcharm.android.data.db.dao

// DAO for the downloads table. The downloads table is the source of truth for which
// episodes have an active or queued phone-side download. DownloadScheduler is the
// primary writer; DownloadWorker reads/updates it; DownloadsViewModel observes it.

import androidx.room.*
import com.castcharm.android.data.db.entities.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    // INSERT OR REPLACE: if a row for this episode already exists (e.g., a retry),
    // it is replaced with the new entity (which may have a new work_request_id).
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity)

    @Update
    suspend fun update(download: DownloadEntity)

    @Delete
    suspend fun delete(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE episode_id = :episodeId")
    suspend fun getDownload(episodeId: Int): DownloadEntity?

    // Always-on Flow for DownloadsViewModel to observe the full download queue.
    // Ordered newest-first so the most recently enqueued items appear at the top.
    @Query("SELECT * FROM downloads ORDER BY enqueued_at DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    // One-shot read of all downloads, oldest-first. Used by kickQueue() to process
    // queued downloads in the order they were enqueued (FIFO).
    @Query("SELECT * FROM downloads ORDER BY enqueued_at ASC")
    suspend fun getAllDownloadsOnceOrdered(): List<DownloadEntity>

    // Returns rows where work_request_id IS NULL — episodes that are queued but
    // have not yet been dispatched to WorkManager. kickQueue() reads these to fill
    // available concurrency slots.
    @Query("SELECT * FROM downloads WHERE work_request_id IS NULL ORDER BY enqueued_at ASC")
    suspend fun getQueuedDownloads(): List<DownloadEntity>

    // Returns the number of rows that have a work_request_id (i.e., a WorkManager
    // job is actively running or pending). Used by kickQueue() to determine how many
    // new slots are available before reaching the concurrency limit.
    @Query("SELECT COUNT(*) FROM downloads WHERE work_request_id IS NOT NULL")
    suspend fun countActiveDownloads(): Int

    // Efficient set-membership check: given a list of episode IDs, returns the subset
    // that have a downloads row. Used by EpisodeListViewModel to mark which episodes
    // have an active phone download without loading full DownloadEntity objects.
    @Query("SELECT episode_id FROM downloads WHERE episode_id IN (:episodeIds)")
    suspend fun getEpisodeIdsWithDownloadRows(episodeIds: List<Int>): List<Int>

    // Targeted progress update — avoids loading and re-writing the full entity.
    @Query("UPDATE downloads SET progress_pct = :progressPct WHERE episode_id = :episodeId")
    suspend fun updateProgressPct(episodeId: Int, progressPct: Int)

    // Assigns the WorkManager UUID to a download row after kickQueue() creates
    // the WorkRequest. Passing null clears the assignment (e.g., on retry reset).
    @Query("UPDATE downloads SET work_request_id = :workRequestId WHERE episode_id = :episodeId")
    suspend fun updateWorkRequestId(episodeId: Int, workRequestId: String?)

    @Query("DELETE FROM downloads WHERE episode_id = :episodeId")
    suspend fun deleteByEpisodeId(episodeId: Int)

    // Used by StorageManager.clearAllDownloads() to remove all download tracking rows.
    @Query("DELETE FROM downloads")
    suspend fun deleteAll()
}