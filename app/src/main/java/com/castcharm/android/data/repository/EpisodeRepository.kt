package com.castcharm.android.data.repository

// EpisodeRepository coordinates between the remote API and the local EpisodeDao.
// All writes go through mergeFromApi() so phone-only fields (local_path,
// sync_pending_*) are never clobbered by server data.
//
// Progress and played-status changes follow a write-local-then-sync pattern:
//   - If online: write to server first; on success, write to DB with pending=false.
//   - If offline or server call fails: write to DB with pending=true.
// SyncWorker periodically flushes all pending=true rows to the server.
//
// toEntity() is defined at the bottom of this file as an extension on EpisodeOut
// so the mapping logic lives in the repository layer, not the DAO or the ViewModel.

import android.util.Log
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.CastCharmApi
import com.castcharm.android.data.api.models.EpisodeOut
import com.castcharm.android.data.api.models.ProgressRequest
import com.castcharm.android.data.api.models.parseDuration
import com.castcharm.android.data.api.models.parseServerDateTime
import com.castcharm.android.data.api.models.resolveImageUrl
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.dao.EpisodeDao
import com.castcharm.android.data.db.entities.EpisodeEntity
import kotlinx.coroutines.flow.Flow

class EpisodeRepository(
    private val api: CastCharmApi,
    private val episodeDao: EpisodeDao
) {
    fun getEpisodesByFeed(feedId: Int): Flow<List<EpisodeEntity>> =
        episodeDao.getEpisodesByFeed(feedId)

    // Fetches episodes for a feed from the server and merges them into the local DB.
    // Returns true if there are more episodes beyond the requested limit (pagination).
    //
    // The "limit+1" trick: requesting one more episode than needed lets us detect
    // hasMore without a separate count query — if we get limit+1 results, there's
    // at least one more page. We then take() only the requested limit to store.
    //
    // Pruning: if this is the last page (hasMore=false), episodes missing from the
    // server response are deleted locally — EXCEPT those with active phone downloads,
    // which are preserved so the DownloadWorker can finish writing the file.
    suspend fun refreshEpisodesByFeed(feedId: Int, limit: Int = 200): Boolean {
        if (CastCharmApp.isOfflineMode) {
            Log.d("EpisodeRepository", "Skipping refreshEpisodesByFeed for feed=$feedId while offline")
            return false
        }

        return try {
            val episodes = api.getEpisodes(feedId, limit = limit + 1)
            val hasMore = episodes.size > limit
            val pageEpisodes = episodes.take(limit)

            // Read the full downloads table once to avoid N+1 DB queries in the map below.
            val db = AppDatabase.getDatabase(CastCharmApp.instance)
            val allPhoneDownloadEpisodeIds = db.downloadDao()
                .getAllDownloadsOnceOrdered()
                .map { it.episode_id }
                .toSet()

            // The subset of the current page that has active phone downloads — passed
            // to mergeFromApi() for its status/progress override logic.
            val activePhoneDownloadEpisodeIdsOnPage = pageEpisodes
                .map { it.id }
                .filter { it in allPhoneDownloadEpisodeIds }
                .toSet()

            // Map each remote EpisodeOut to an EpisodeEntity, preserving local state
            // via the toEntity() extension function.
            val mergedEpisodes = pageEpisodes.map { remote ->
                val existing = episodeDao.getEpisodeOnce(remote.id)
                remote.toEntity(
                    existing = existing,
                    hasActivePhoneDownload = remote.id in allPhoneDownloadEpisodeIds
                )
            }

            episodeDao.mergeFromApi(
                episodes = mergedEpisodes,
                activePhoneDownloadEpisodeIds = activePhoneDownloadEpisodeIdsOnPage
            )

            // Pruning only happens when we have the full episode list (last page).
            if (!hasMore) {
                val remoteIds = pageEpisodes.map { it.id }
                // Determine which local episodes have active phone downloads so they
                // can be excluded from the prune operation.
                val preserveIds = episodeDao.getEpisodesByFeedOnce(feedId)
                    .map { it.id }
                    .filter { it in allPhoneDownloadEpisodeIds }
                    .toSet()

                episodeDao.pruneMissingEpisodesForFeed(
                    feedId = feedId,
                    remoteIds = remoteIds,
                    preserveIds = preserveIds
                )
            }

            hasMore
        } catch (e: Exception) {
            Log.e("EpisodeRepository", "Error refreshing episodes for feed $feedId", e)
            throw e
        }
    }

    fun getUnplayedEpisodes(feedId: Int): Flow<List<EpisodeEntity>> =
        episodeDao.getUnplayedEpisodesByFeed(feedId)

    fun getContinueListening(limit: Int = 10): Flow<List<EpisodeEntity>> =
        episodeDao.getContinueListening(limit)

    suspend fun fetchAndCacheContinueListening() {
        if (CastCharmApp.isOfflineMode) {
            Log.d("EpisodeRepository", "Skipping fetchAndCacheContinueListening while offline")
            return
        }

        try {
            val episodes = api.getContinueListening()
            val existingMap = episodes
                .mapNotNull { episodeDao.getEpisodeOnce(it.id) }
                .associateBy { it.id }

            val db = AppDatabase.getDatabase(CastCharmApp.instance)
            val allPhoneDownloadEpisodeIds = db.downloadDao()
                .getAllDownloadsOnceOrdered()
                .map { it.episode_id }
                .toSet()

            val mergedEpisodes = episodes.map { remote ->
                remote.toEntity(
                    existing = existingMap[remote.id],
                    hasActivePhoneDownload = remote.id in allPhoneDownloadEpisodeIds
                )
            }

            episodeDao.mergeFromApi(
                episodes = mergedEpisodes,
                activePhoneDownloadEpisodeIds = allPhoneDownloadEpisodeIds.intersect(episodes.map { it.id }.toSet())
            )
        } catch (e: Exception) {
            Log.e("EpisodeRepository", "Error fetching continue listening", e)
        }
    }

    // Persists playback progress and optionally transitions the played state if the
    // position has crossed the played threshold. Three code paths:
    //   1. Episode not in DB yet → write progress with pending=true (will sync later).
    //   2. Offline mode → write both progress and played changes with pending=true.
    //   3. Online → call the API first; on success, write with pending=false;
    //      on failure, fall back to pending=true so SyncWorker picks it up.
    //
    // durationSeconds can be provided by the player (more accurate than DB duration)
    // to improve the played-threshold calculation.
    suspend fun updateProgress(
        episodeId: Int,
        position: Int,
        durationSeconds: Int? = null,
        playedThresholdPct: Float = 0.98f
    ) {
        val now = System.currentTimeMillis()
        val existing = episodeDao.getEpisodeOnce(episodeId) ?: run {
            // Episode not yet cached — write progress with pending flag so SyncWorker
            // can flush it when the episode row eventually exists.
            episodeDao.updateProgress(episodeId, position, now, pending = true)
            return
        }

        // Use caller-provided duration if available (more accurate for the current
        // playback session than whatever was cached at last sync time).
        val effectiveDuration = durationSeconds ?: existing.duration
        val targetPlayed = derivePlayedState(
            positionSeconds = position,
            durationSeconds = effectiveDuration,
            currentPlayed = existing.played,
            thresholdPct = playedThresholdPct
        )

        if (CastCharmApp.isOfflineMode) {
            episodeDao.updateProgress(
                episodeId,
                position,
                now,
                pending = true
            )
            if (existing.played != targetPlayed) {
                episodeDao.updatePlayedStatus(
                    episodeId,
                    targetPlayed,
                    now,
                    pending = true
                )
            }
            return
        }

        // Online path: sync to server first, then update DB.
        try {
            api.updateProgress(episodeId, ProgressRequest(position))
            episodeDao.updateProgress(episodeId, position, now, pending = false)

            if (existing.played != targetPlayed) {
                api.togglePlayed(episodeId)
                episodeDao.updatePlayedStatus(
                    episodeId,
                    targetPlayed,
                    now,
                    pending = false
                )
            }
        } catch (e: Exception) {
            // Server call failed — save locally with pending=true for SyncWorker.
            Log.e("EpisodeRepository", "Error syncing progress to server, marking for later sync", e)
            episodeDao.updateProgress(episodeId, position, now, pending = true)
            if (existing.played != targetPlayed) {
                episodeDao.updatePlayedStatus(
                    episodeId,
                    targetPlayed,
                    now,
                    pending = true
                )
            }
        }
    }

    suspend fun togglePlayed(episodeId: Int, currentlyPlayed: Boolean) {
        val newState = !currentlyPlayed

        if (CastCharmApp.isOfflineMode) {
            episodeDao.updatePlayedStatus(
                episodeId,
                newState,
                System.currentTimeMillis(),
                pending = true
            )
            return
        }

        try {
            api.togglePlayed(episodeId)
            episodeDao.updatePlayedStatus(episodeId, newState, System.currentTimeMillis(), pending = false)
        } catch (e: Exception) {
            Log.e("EpisodeRepository", "Error toggling played status, marking for later sync", e)
            episodeDao.updatePlayedStatus(episodeId, newState, System.currentTimeMillis(), pending = true)
        }
    }

    suspend fun toggleHidden(episodeId: Int, currentlyHidden: Boolean) {
        if (CastCharmApp.isOfflineMode) {
            throw IllegalStateException("This action is unavailable offline.")
        }

        try {
            if (currentlyHidden) api.unhideEpisode(episodeId) else api.hideEpisode(episodeId)
            episodeDao.updateHidden(episodeId, !currentlyHidden)
        } catch (e: Exception) {
            Log.e("EpisodeRepository", "Error toggling hidden for episode $episodeId", e)
            throw e
        }
    }

    fun getEpisode(episodeId: Int): Flow<EpisodeEntity?> =
        episodeDao.getEpisode(episodeId)

    suspend fun getEpisodeOnce(episodeId: Int): EpisodeEntity? =
        episodeDao.getEpisodeOnce(episodeId)

    suspend fun fetchEpisodeFromApi(episodeId: Int): EpisodeEntity? {
        if (CastCharmApp.isOfflineMode) {
            return episodeDao.getEpisodeOnce(episodeId)
        }

        return try {
            val remote = api.getEpisode(episodeId)
            val existing = episodeDao.getEpisodeOnce(episodeId)

            val db = AppDatabase.getDatabase(CastCharmApp.instance)
            val hasActivePhoneDownload =
                db.downloadDao().getDownload(episodeId) != null

            val entity = remote.toEntity(
                existing = existing,
                hasActivePhoneDownload = hasActivePhoneDownload
            )

            episodeDao.mergeFromApi(
                episodes = listOf(entity),
                activePhoneDownloadEpisodeIds = if (hasActivePhoneDownload) setOf(episodeId) else emptySet()
            )

            episodeDao.getEpisodeOnce(episodeId)
        } catch (e: Exception) {
            Log.e("EpisodeRepository", "Error fetching episode $episodeId from API", e)
            episodeDao.getEpisodeOnce(episodeId)
        }
    }

    companion object {
        // Pure function that determines whether an episode should be marked played
        // based on its current playback position. Used both here and by PlayerViewModel.
        //
        // Returns false if position is 0 (not started).
        // Returns currentPlayed unchanged if duration is unknown — avoids accidentally
        //   marking an episode played when we can't compute the threshold.
        // Returns true when position/duration >= thresholdPct (default 98%).
        fun derivePlayedState(
            positionSeconds: Int,
            durationSeconds: Int?,
            currentPlayed: Boolean,
            thresholdPct: Float = 0.98f
        ): Boolean {
            if (positionSeconds <= 0) return false
            val duration = durationSeconds ?: return currentPlayed
            if (duration <= 0) return currentPlayed

            return (positionSeconds.toFloat() / duration.toFloat()) >= thresholdPct
        }
    }
}

// Extension function that maps a remote EpisodeOut (from the API) to a local
// EpisodeEntity for DB storage. The existing parameter provides phone-only field
// values (local_path, sync_pending_*) that must be preserved across syncs.
//
// Status and progress resolution here mirrors the logic in EpisodeDao.mergeFromApi()
// — this function handles the toEntity conversion before mergeFromApi() writes to DB,
// while mergeFromApi() applies the same rules during bulk inserts/updates.
fun EpisodeOut.toEntity(
    existing: EpisodeEntity?,
    hasActivePhoneDownload: Boolean = false
): EpisodeEntity {
    val baseUrl = CastCharmApp.apiClient.getBaseUrl()

    // Resolve status based on phone-side reality (same rules as mergeFromApi).
    val resolvedStatus = when {
        existing?.local_path != null -> "downloaded"
        hasActivePhoneDownload && existing?.status == "downloading" -> "downloading"
        hasActivePhoneDownload -> "queued"
        else -> status
    }

    val resolvedDownloadProgress = when {
        existing?.local_path != null -> 100
        hasActivePhoneDownload -> existing?.download_progress ?: 0
        status == "queued" || status == "pending" -> 0
        else -> download_progress
    }

    return EpisodeEntity(
        id = id,
        feed_id = feed_id,
        guid = guid,
        title = title ?: "",
        enclosure_url = enclosure_url,
        enclosure_type = enclosure_type,
        enclosure_length = enclosure_length,
        // parseServerDateTime handles both ISO-8601 with offset and naive datetime strings.
        published_at = parseServerDateTime(published_at),
        description = description,
        // parseDuration handles both integer-seconds and H:MM:SS / MM:SS strings.
        duration = parseDuration(duration),
        episode_number = episode_number,
        season_number = season_number,
        // resolveImageUrl prepends the base URL to relative paths from the server.
        episode_image_url = resolveImageUrl(baseUrl, episode_image_url),
        custom_image_url = resolveImageUrl(baseUrl, custom_image_url),
        author = author,
        link = link,
        hidden = hidden,
        played = played,
        play_position_seconds = play_position_seconds,
        last_played_at = parseServerDateTime(last_played_at),
        status = resolvedStatus,
        // Always preserve phone-only fields from the existing DB row.
        local_path = existing?.local_path,
        local_size_bytes = existing?.local_size_bytes,
        download_progress = resolvedDownloadProgress,
        seq_number = seq_number,
        feed_image_url = resolveImageUrl(baseUrl, feed_image_url),
        created_at = parseServerDateTime(created_at) ?: 0L,
        sync_pending_progress = existing?.sync_pending_progress ?: false,
        sync_pending_played = existing?.sync_pending_played ?: false,
    )
}