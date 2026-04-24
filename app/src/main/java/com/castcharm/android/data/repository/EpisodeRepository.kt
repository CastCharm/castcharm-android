package com.castcharm.android.data.repository

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

    suspend fun refreshEpisodesByFeed(feedId: Int, limit: Int = 200): Boolean {
        if (CastCharmApp.isOfflineMode) {
            Log.d("EpisodeRepository", "Skipping refreshEpisodesByFeed for feed=$feedId while offline")
            return false
        }

        return try {
            val episodes = api.getEpisodes(feedId, limit = limit + 1)
            val hasMore = episodes.size > limit
            val pageEpisodes = episodes.take(limit)

            val db = AppDatabase.getDatabase(CastCharmApp.instance)
            val allPhoneDownloadEpisodeIds = db.downloadDao()
                .getAllDownloadsOnceOrdered()
                .map { it.episode_id }
                .toSet()

            val activePhoneDownloadEpisodeIdsOnPage = pageEpisodes
                .map { it.id }
                .filter { it in allPhoneDownloadEpisodeIds }
                .toSet()

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

            if (!hasMore) {
                val remoteIds = pageEpisodes.map { it.id }
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

    suspend fun updateProgress(
        episodeId: Int,
        position: Int,
        durationSeconds: Int? = null,
        playedThresholdPct: Float = 0.98f
    ) {
        val now = System.currentTimeMillis()
        val existing = episodeDao.getEpisodeOnce(episodeId) ?: run {
            episodeDao.updateProgress(episodeId, position, now, pending = true)
            return
        }

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

fun EpisodeOut.toEntity(
    existing: EpisodeEntity?,
    hasActivePhoneDownload: Boolean = false
): EpisodeEntity {
    val baseUrl = CastCharmApp.apiClient.getBaseUrl()

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
        published_at = parseServerDateTime(published_at),
        description = description,
        duration = parseDuration(duration),
        episode_number = episode_number,
        season_number = season_number,
        episode_image_url = resolveImageUrl(baseUrl, episode_image_url),
        custom_image_url = resolveImageUrl(baseUrl, custom_image_url),
        author = author,
        link = link,
        hidden = hidden,
        played = played,
        play_position_seconds = play_position_seconds,
        last_played_at = parseServerDateTime(last_played_at),
        status = resolvedStatus,
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