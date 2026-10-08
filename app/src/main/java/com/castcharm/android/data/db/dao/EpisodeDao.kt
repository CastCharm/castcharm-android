package com.castcharm.android.data.db.dao

// DAO for the episodes table. This is the most complex DAO in the app because it must
// carefully preserve local-only fields (local_path, sync_pending_*) while still
// reflecting server state changes on every sync.
//
// Key design decisions:
//   - insertIgnore/insertAllIgnore use IGNORE so new episodes never silently overwrite
//     an existing row (preserving local state). The insert() and insertAll() @Transaction
//     functions manually route to insert vs. update after checking for existing rows.
//   - mergeFromApi() is the correct path for all server sync writes. It preserves
//     local_path, local_size_bytes, and sync_pending_* flags from the existing row
//     before calling insertAll(), which resolves to update for existing episodes.
//   - pruneMissingEpisodesForFeed() uses deleteEpisodesByFeedNotInPreserving() to
//     skip episodes that have active phone downloads so their files are not orphaned.

import androidx.room.*
import com.castcharm.android.data.db.entities.EpisodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {
    // IGNORE conflict strategy: if the episode ID already exists, do nothing.
    // This is intentional — use insert() or insertAll() (which check first) instead
    // of calling these directly when you want upsert semantics.
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

    // Safe upsert: check existence first, then insert or update within a transaction.
    // This avoids the INSERT OR REPLACE path which deletes the row (losing local state).
    @Transaction
    suspend fun insert(episode: EpisodeEntity) {
        val existing = getEpisodeOnce(episode.id)
        if (existing == null) {
            insertIgnore(episode)
        } else {
            update(episode)
        }
    }

    // Batch-optimized upsert: fetches all existing IDs in one query, then splits
    // the input list into inserts (new) and updates (existing). This avoids N+1
    // queries that would result from calling insert() in a loop.
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

    // Offline search fallback. Matches the given term against title and
    // description columns. The percent signs must be included by the caller
    // (e.g., "%dogs%") so the DAO signature stays declarative.
    @Query("""
        SELECT * FROM episodes
        WHERE hidden = 0 AND (
            title LIKE :term COLLATE NOCASE
            OR description LIKE :term COLLATE NOCASE
        )
        ORDER BY published_at DESC
        LIMIT :limit
    """)
    suspend fun searchEpisodesLocal(term: String, limit: Int = 40): List<EpisodeEntity>

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

    // All episodes with unflushed offline changes — read by SyncWorker to determine
    // what to push to the server on the next sync cycle.
    @Query("SELECT * FROM episodes WHERE sync_pending_progress = 1 OR sync_pending_played = 1")
    suspend fun getPendingSyncEpisodes(): List<EpisodeEntity>

    // Used by MainActivity to show the animated spinner on the Downloads tab icon
    // and by DownloadsViewModel for the "Saving to Server" section.
    @Query("SELECT * FROM episodes WHERE status IN ('queued', 'downloading', 'failed') ORDER BY published_at DESC")
    fun getInProgressEpisodes(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE feed_id = :feedId ORDER BY published_at DESC")
    suspend fun getEpisodesByFeedOnce(feedId: Int): List<EpisodeEntity>

    @Query("""
        SELECT * FROM episodes
        WHERE feed_id = :feedId
        ORDER BY published_at DESC
    """)
    suspend fun getEpisodesForAndroidAutoByFeed(feedId: Int): List<EpisodeEntity>

    // Oldest-first listing for feeds listened to in order (stories, serials).
    @Query("""
        SELECT * FROM episodes
        WHERE feed_id = :feedId
        ORDER BY published_at ASC, episode_number ASC, id ASC
    """)
    suspend fun getEpisodesForAndroidAutoByFeedOldestFirst(feedId: Int): List<EpisodeEntity>

    // The in-order queue: everything not yet heard, oldest first. The local-only
    // variant is what offline playback can actually play.
    @Query("""
        SELECT * FROM episodes
        WHERE feed_id = :feedId AND played = 0 AND hidden = 0
        ORDER BY published_at ASC, episode_number ASC, id ASC
    """)
    suspend fun getInOrderQueue(feedId: Int): List<EpisodeEntity>

    @Query("""
        SELECT * FROM episodes
        WHERE feed_id = :feedId AND played = 0 AND hidden = 0 AND local_path IS NOT NULL
        ORDER BY published_at ASC, episode_number ASC, id ASC
    """)
    suspend fun getInOrderQueueLocal(feedId: Int): List<EpisodeEntity>

    // The episode "Continue" resumes: most recently left mid-way, if any.
    @Query("""
        SELECT * FROM episodes
        WHERE feed_id = :feedId AND played = 0 AND hidden = 0 AND play_position_seconds > 0
        ORDER BY last_played_at DESC LIMIT 1
    """)
    suspend fun getInProgressForFeed(feedId: Int): EpisodeEntity?

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

    // Candidates for quota cleanup: played episodes that actually hold a file.
    @Query("SELECT * FROM episodes WHERE played = 1 AND local_path IS NOT NULL ORDER BY last_played_at ASC LIMIT :limit")
    suspend fun getOldestPlayedEpisodesGlobal(limit: Int): List<EpisodeEntity>

    // Targeted writes, so a stale full-row copy can never stomp newer progress.
    @Query("UPDATE episodes SET local_path = NULL, local_size_bytes = NULL, status = 'pending', download_progress = 0 WHERE id = :episodeId")
    suspend fun clearLocalFile(episodeId: Int)

    // SyncWorker clears a pending flag only if the row still holds the value it
    // sent; a newer write in between keeps its flag and is flushed next time.
    @Query("UPDATE episodes SET sync_pending_progress = 0 WHERE id = :episodeId AND play_position_seconds = :sentPosition")
    suspend fun clearPendingProgressIf(episodeId: Int, sentPosition: Int)

    @Query("UPDATE episodes SET sync_pending_played = 0 WHERE id = :episodeId AND played = :sentPlayed")
    suspend fun clearPendingPlayedIf(episodeId: Int, sentPlayed: Boolean)

    @Query("UPDATE episodes SET sync_pending_progress = 0, sync_pending_played = 0 WHERE id = :episodeId")
    suspend fun clearPendingFlags(episodeId: Int)

    // Rows a server-side prune must leave alone: they hold a file or unsynced state.
    @Query("SELECT id FROM episodes WHERE feed_id = :feedId AND (local_path IS NOT NULL OR sync_pending_progress = 1 OR sync_pending_played = 1)")
    suspend fun getPreservableIdsForFeed(feedId: Int): List<Int>

    @Query("SELECT * FROM episodes WHERE feed_id IN (:feedIds) AND local_path IS NOT NULL")
    suspend fun getEpisodesWithFilesForFeeds(feedIds: List<Int>): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE local_path IS NULL AND id IN (:ids)")
    suspend fun getEpisodesWithoutFileByIds(ids: List<Int>): List<EpisodeEntity>

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

    // Deletes local episodes that are no longer in the server's episode list for this
    // feed. Rows in preserveIds (episodes with active phone downloads) are kept so
    // the DownloadWorker can finish writing the file without losing its row.
    // The listOf(-1) sentinel handles the empty-list case: SQL IN () is invalid, but
    // IN (-1) safely matches nothing since -1 is never a valid episode ID.
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

    // The core sync merge function. Called by EpisodeRepository after fetching
    // fresh data from the server. For each incoming episode, it copies phone-only
    // fields from the existing DB row (if one exists) before writing, so that:
    //   - local_path and local_size_bytes are never overwritten by server data
    //   - sync_pending_* flags are not cleared by a sync that isn't the SyncWorker
    //   - the episode's status/progress accurately reflects phone-download reality,
    //     not just server-side state
    //
    // activePhoneDownloadEpisodeIds is the set of episodes currently tracked in
    // the downloads table (queued or actively downloading to this device). The
    // merged status override rules prevent a server response from reverting a
    // phone-side download to "pending" status mid-download.
    @Transaction
    suspend fun mergeFromApi(
        episodes: List<EpisodeEntity>,
        activePhoneDownloadEpisodeIds: Set<Int> = emptySet()
    ) {
        if (episodes.isEmpty()) return

        // Fetch all existing rows in a single query to avoid N+1 lookups.
        val existingMap = getEpisodesByIds(episodes.map { it.id }).associateBy { it.id }

        val merged = episodes.map { ep ->
            val ex = existingMap[ep.id]
            val hasActivePhoneDownload = ep.id in activePhoneDownloadEpisodeIds

            // Status resolution priority:
            //   1. local_path exists → always "downloaded" (phone has the file)
            //   2. Active WorkManager download (and ex.status was already "downloading") → keep "downloading"
            //   3. Active WorkManager download (any other state) → "queued" (about to start)
            //   4. Otherwise → trust the server's status
            val mergedStatus = when {
                ex?.local_path != null -> "downloaded"
                hasActivePhoneDownload && ex?.status == "downloading" -> "downloading"
                hasActivePhoneDownload -> "queued"
                else -> ep.status
            }

            // Progress resolution:
            //   local_path present → 100% (file is complete on device)
            //   Active phone download → preserve existing progress so the UI shows
            //     real WorkManager progress rather than jumping back to server's value
            //   Server reports queued/pending → 0 (not started yet)
            //   Otherwise → use the server's progress value
            val mergedProgress = when {
                ex?.local_path != null -> 100
                hasActivePhoneDownload -> ex?.download_progress ?: 0
                ep.status == "queued" || ep.status == "pending" -> 0
                else -> ep.download_progress
            }

            // Build the merged entity: keep all server fields except the
            // phone-only ones, which always come from the existing row.
            // Unsynced listening state is phone-only too: while a pending
            // flag is set the phone's value is newer than anything the server
            // can tell us, so it stays put until SyncWorker has pushed it.
            val pendingProgress = ex?.sync_pending_progress == true
            val pendingPlayed = ex?.sync_pending_played == true
            val keepListening = ex != null && (pendingProgress || pendingPlayed)
            ep.copy(
                local_path = ex?.local_path,
                local_size_bytes = ex?.local_size_bytes,
                download_progress = mergedProgress,
                status = mergedStatus,
                played = if (ex != null && pendingPlayed) ex.played else ep.played,
                play_position_seconds = if (keepListening) ex!!.play_position_seconds else ep.play_position_seconds,
                last_played_at = if (keepListening) ex!!.last_played_at else ep.last_played_at,
                sync_pending_progress = pendingProgress,
                sync_pending_played = pendingPlayed
            )
        }

        // insertAll handles the insert/update routing based on whether each row
        // already exists in the DB.
        insertAll(merged)
    }
}