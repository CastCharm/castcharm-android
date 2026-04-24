package com.castcharm.android.data.db.dao

import androidx.room.*
import com.castcharm.android.data.db.entities.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity)

    @Update
    suspend fun update(download: DownloadEntity)

    @Delete
    suspend fun delete(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE episode_id = :episodeId")
    suspend fun getDownload(episodeId: Int): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY enqueued_at DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY enqueued_at ASC")
    suspend fun getAllDownloadsOnceOrdered(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE work_request_id IS NULL ORDER BY enqueued_at ASC")
    suspend fun getQueuedDownloads(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE work_request_id IS NOT NULL")
    suspend fun countActiveDownloads(): Int

    @Query("SELECT episode_id FROM downloads WHERE episode_id IN (:episodeIds)")
    suspend fun getEpisodeIdsWithDownloadRows(episodeIds: List<Int>): List<Int>

    @Query("UPDATE downloads SET progress_pct = :progressPct WHERE episode_id = :episodeId")
    suspend fun updateProgressPct(episodeId: Int, progressPct: Int)

    @Query("UPDATE downloads SET work_request_id = :workRequestId WHERE episode_id = :episodeId")
    suspend fun updateWorkRequestId(episodeId: Int, workRequestId: String?)

    @Query("DELETE FROM downloads WHERE episode_id = :episodeId")
    suspend fun deleteByEpisodeId(episodeId: Int)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()
}