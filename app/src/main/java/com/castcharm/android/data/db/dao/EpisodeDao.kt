package com.castcharm.android.data.db.dao

import androidx.room.*
import com.castcharm.android.data.db.entities.EpisodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(episode: EpisodeEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(episodes: List<EpisodeEntity>): List<Long>

    @Update
    suspend fun update(episode: EpisodeEntity)

    @Update
    suspend fun updateAll(episodes: List<EpisodeEntity>)

    @Delete
    suspend fun delete(episode: EpisodeEntity)

    @Transaction
    suspend fun insert(episode: EpisodeEntity) {
        val existing = getEpisodeOnce(episode.id)
        if (existing == null) {
            insertIgnore(episode)
        } else {
            update(episode)
        }
    }

    @Transaction
    suspend fun insertAll(episodes: List<EpisodeEntity>) {
        if (episodes.isEmpty()) return

        val existingIds = getEpisodesByIds(episodes.map { it.id })
            .map { it.id }
            .toSet()

        val toInsert = episodes.filter { it.id !in existingIds }
        val toUpdate = episodes.filter { it.id in existingIds }

        if (toInsert.isNotEmpty()) insertAllIgnore(toInsert)
        if (toUpdate.isNotEmpty()) updateAll(toUpdate)
    }

    @Query("SELECT * FROM episodes WHERE feed_id = :feedId ORDER BY published_at DESC")
    fun getEpisodesByFeed(feedId: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE feed_id = :feedId ORDER BY published_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getEpisodesByFeedPaginated(
        feedId: Int,
        limit: Int = 50,
        offset: Int = 0
    ): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :episodeId")
    fun getEpisode(episodeId: Int): Flow<EpisodeEntity?>

    @Query("SELECT * FROM episodes WHERE id = :episodeId")
    suspend fun getEpisodeOnce(episodeId: Int): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE feed_id = :feedId AND hidden = 0 AND played = 0 ORDER BY published_at DESC")
    fun getUnplayedEpisodesByFeed(feedId: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE played = 0 AND play_position_seconds > 0 ORDER BY last_played_at DESC LIMIT :limit")
    fun getContinueListening(limit: Int = 10): Flow<List<EpisodeEntity>>

    @Query("""
        UPDATE episodes SET
            played = :played,
            last_played_at = :timestamp,
            play_position_seconds = CASE WHEN :played = 1 THEN COALESCE(duration, play_position_seconds) ELSE 0 END,
            sync_pending_played = :pending
        WHERE id = :episodeId
    """)
    suspend fun updatePlayedStatus(episodeId: Int, played: Boolean, timestamp: Long, pending: Boolean = false)

    @Query("UPDATE episodes SET hidden = :hidden WHERE id = :episodeId")
    suspend fun updateHidden(episodeId: Int, hidden: Boolean)

    @Query("UPDATE episodes SET play_position_seconds = :position, last_played_at = :timestamp, sync_pending_progress = :pending WHERE id = :episodeId")
    suspend fun updateProgress(episodeId: Int, position: Int, timestamp: Long, pending: Boolean = false)

    @Query("SELECT * FROM episodes WHERE local_path IS NOT NULL ORDER BY published_at DESC")
    fun getDownloadedEpisodes(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE local_path IS NOT NULL ORDER BY published_at DESC")
    suspend fun getDownloadedEpisodesOnce(): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE sync_pending_progress = 1 OR sync_pending_played = 1")
    suspend fun getPendingSyncEpisodes(): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE status IN ('queued', 'downloading') ORDER BY published_at DESC")
    fun getInProgressEpisodes(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE feed_id = :feedId ORDER BY published_at DESC")
    suspend fun getEpisodesByFeedOnce(feedId: Int): List<EpisodeEntity>

    @Query("""
        SELECT * FROM episodes
        WHERE feed_id = :feedId
        ORDER BY published_at DESC
    """)
    suspend fun getEpisodesForAndroidAutoByFeed(feedId: Int): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE feed_id = :feedId ORDER BY published_at DESC")
    suspend fun getAllEpisodesByFeedIdRaw(feedId: Int): List<EpisodeEntity>

    @Query("SELECT SUM(local_size_bytes) FROM episodes WHERE local_path IS NOT NULL")
    suspend fun getTotalDownloadedBytes(): Long?

    @Query("DELETE FROM episodes WHERE feed_id = :feedId")
    suspend fun deleteByFeedId(feedId: Int)

    @Query("DELETE FROM episodes WHERE feed_id = :feedId AND id NOT IN (:ids)")
    suspend fun deleteEpisodesByFeedNotIn(feedId: Int, ids: List<Int>)

    @Query("""
        DELETE FROM episodes
        WHERE feed_id = :feedId
        AND id NOT IN (:remoteIds)
        AND id NOT IN (:preserveIds)
    """)
    suspend fun deleteEpisodesByFeedNotInPreserving(
        feedId: Int,
        remoteIds: List<Int>,
        preserveIds: List<Int>
    )

    @Query("DELETE FROM episodes")
    suspend fun deleteAll()

    @Query("SELECT * FROM episodes WHERE feed_id = :feedId AND played = 1 ORDER BY last_played_at ASC LIMIT :limit")
    suspend fun getOldestPlayedEpisodes(feedId: Int, limit: Int): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE played = 1 ORDER BY last_played_at ASC LIMIT :limit")
    suspend fun getOldestPlayedEpisodesGlobal(limit: Int): List<EpisodeEntity>

    @Query("UPDATE episodes SET download_progress = :progress WHERE id = :episodeId")
    suspend fun updateDownloadProgress(episodeId: Int, progress: Int)

    @Query("UPDATE episodes SET status = :status WHERE id = :episodeId")
    suspend fun updateDownloadStatus(episodeId: Int, status: String)

    @Query("UPDATE episodes SET local_path = :localPath, local_size_bytes = :localSizeBytes, status = 'downloaded', download_progress = 100 WHERE id = :episodeId")
    suspend fun updateDownloadComplete(episodeId: Int, localPath: String, localSizeBytes: Long)

    @Query("SELECT * FROM episodes WHERE id IN (:ids)")
    suspend fun getEpisodesByIds(ids: List<Int>): List<EpisodeEntity>

    @Query("""
        SELECT * FROM episodes
        WHERE feed_id = :feedId
        AND feed_image_url IS NOT NULL
        LIMIT 1
    """)
    suspend fun getEpisodeWithFeedImageForFeed(feedId: Int): EpisodeEntity?

    @Query("SELECT COUNT(*) FROM episodes")
    suspend fun getEpisodeTableCount(): Int

    @Query("SELECT * FROM episodes WHERE feed_id = :feedId ORDER BY published_at DESC LIMIT 10")
    suspend fun getSampleEpisodesByFeed(feedId: Int): List<EpisodeEntity>

    @Query("""
    SELECT * FROM episodes
    WHERE hidden = 0
    ORDER BY published_at DESC
    LIMIT :limit
    """)
    suspend fun getRecentEpisodesOnce(limit: Int = 200): List<EpisodeEntity>

    @Transaction
    suspend fun pruneMissingEpisodesForFeed(
        feedId: Int,
        remoteIds: List<Int>,
        preserveIds: Set<Int> = emptySet()
    ) {
        val safeRemoteIds = if (remoteIds.isEmpty()) listOf(-1) else remoteIds
        val safePreserveIds = if (preserveIds.isEmpty()) listOf(-1) else preserveIds.toList()
        deleteEpisodesByFeedNotInPreserving(feedId, safeRemoteIds, safePreserveIds)
    }

    @Transaction
    suspend fun mergeFromApi(
        episodes: List<EpisodeEntity>,
        activePhoneDownloadEpisodeIds: Set<Int> = emptySet()
    ) {
        if (episodes.isEmpty()) return

        val existingMap = getEpisodesByIds(episodes.map { it.id }).associateBy { it.id }

        val merged = episodes.map { ep ->
            val ex = existingMap[ep.id]
            val hasActivePhoneDownload = ep.id in activePhoneDownloadEpisodeIds

            val mergedStatus = when {
                ex?.local_path != null -> "downloaded"
                hasActivePhoneDownload && ex?.status == "downloading" -> "downloading"
                hasActivePhoneDownload -> "queued"
                else -> ep.status
            }

            val mergedProgress = when {
                ex?.local_path != null -> 100
                hasActivePhoneDownload -> ex?.download_progress ?: 0
                ep.status == "queued" || ep.status == "pending" -> 0
                else -> ep.download_progress
            }

            ep.copy(
                local_path = ex?.local_path,
                local_size_bytes = ex?.local_size_bytes,
                download_progress = mergedProgress,
                status = mergedStatus,
                sync_pending_progress = ex?.sync_pending_progress ?: false,
                sync_pending_played = ex?.sync_pending_played ?: false
            )
        }

        insertAll(merged)
    }
}