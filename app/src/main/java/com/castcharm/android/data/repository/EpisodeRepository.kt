package com.castcharm.android.data.repository

// EpisodeRepository coordinates between the remote API and the local EpisodeDao.
// All writes go through mergeFromApi() so phone-only fields (local_path,
// sync_pending_*) are never clobbered by server data, and so playback fields
// with an unsent local change survive a server response that predates it.
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
import com.castcharm.android.data.api.setPlayed
import com.castcharm.android.data.api.models.EpisodeIndexOut
import com.castcharm.android.data.api.models.EpisodeOut
import com.castcharm.android.data.api.models.clampProgressSeconds
import com.castcharm.android.data.api.models.progressRequest
import com.castcharm.android.data.api.models.parseDuration
import com.castcharm.android.data.api.models.parseServerDateTime
import com.castcharm.android.data.api.models.resolveImageUrl
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.dao.EpisodeDao
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.api.models.BulkEpisodeRequest
import com.castcharm.android.data.api.ServerLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

// Ids per purely local statement. Nothing leaves the device on these paths, so
// the only ceiling that applies is SQLite's bind-variable count — 999 on older
// Android builds. Request sizes come from ServerLimits instead, since those have
// to satisfy the server as well.
private const val LOCAL_ID_CHUNK = 400

class EpisodeRepository(
    private val api: CastCharmApi,
    private val episodeDao: EpisodeDao
) {
    fun getEpisodesByFeed(feedId: Int): Flow<List<EpisodeEntity>> =
        episodeDao.getEpisodesByFeed(feedId)

    /**
     * Fetches the feed's episode ids in display order, or null if this server is
     * too old to have the endpoint.
     *
     * Returning null rather than throwing is deliberate: the index is an
     * optimisation, and an app updated ahead of its server should degrade to
     * sequential loading rather than showing an empty feed.
     */
    suspend fun fetchEpisodeIndex(feedId: Int, filter: String = "all"): EpisodeIndexOut? {
        if (CastCharmApp.isOfflineMode) return null
        return try {
            api.getEpisodeIndex(feedId, filter = filter)
        } catch (e: HttpException) {
            if (e.code() == 404) {
                Log.i("EpisodeRepository", "Server has no /episode-index; using sequential loading")
                null
            } else {
                throw e
            }
        }
    }

    /**
     * Fetches the named episodes and merges them into the DB.
     *
     * Unlike [refreshEpisodesByFeed] this never prunes: a handful of episodes says
     * nothing about the ones outside it, so deleting local rows on the strength of
     * a page is how a windowed list would erase the rest of the feed. Pruning
     * belongs to [pruneToIndex], which has the whole picture.
     */
    suspend fun fetchEpisodesByIds(feedId: Int, ids: List<Int>): List<EpisodeEntity> {
        if (CastCharmApp.isOfflineMode || ids.isEmpty()) return emptyList()
        val fetched = ids.chunked(ServerLimits.current.idsPerRequest).flatMap { chunk ->
            api.getEpisodesByIds(feedId, ids = chunk.joinToString(","))
        }
        return mergePage(fetched)
    }

    // Fetches episodes for a feed from the server and merges them into the local DB.
    // Returns true if there are more episodes beyond the requested limit (pagination).
    //
    // This is the pre-index path, kept for servers without /episode-index and for
    // callers that genuinely want the newest N episodes. Note that it re-reads the
    // window from the newest episode every time rather than appending, so growing
    // it in steps costs the sum of every limit asked for — which is exactly why
    // reaching deep into a feed this way was so expensive.
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
            // The has-more probe asks for one more than it wants, so the clamp has
            // to leave room for it: a caller passing exactly the server's maximum
            // would otherwise request maximum+1 and get a 422 instead of a page.
            val safeLimit = limit.coerceIn(1, ServerLimits.current.safePageSize)
            val episodes = api.getEpisodes(feedId, limit = safeLimit + 1)
            val hasMore = episodes.size > safeLimit
            val pageEpisodes = episodes.take(safeLimit)

            mergePage(pageEpisodes)

            // Pruning only happens when we have the full episode list (last page).
            if (!hasMore) {
                pruneToIndex(feedId, pageEpisodes.map { it.id })
            }

            hasMore
        } catch (e: Exception) {
            Log.e("EpisodeRepository", "Error refreshing episodes for feed $feedId", e)
            throw e
        }
    }

    /**
     * Deletes local rows for this feed that the server no longer lists.
     *
     * [remoteIds] must be the feed's COMPLETE id list — from /episode-index or from
     * a known-final page. Episodes with an active phone download are preserved so
     * the DownloadWorker does not lose the row out from under a file it is writing.
     */
    suspend fun pruneToIndex(feedId: Int, remoteIds: List<Int>) {
        val db = AppDatabase.getDatabase(CastCharmApp.instance)
        val activeDownloadIds = db.downloadDao()
            .getAllDownloadsOnceOrdered()
            .map { it.episode_id }
            .toSet()

        // The difference is computed here rather than as a SQL "NOT IN (:remoteIds)".
        // A full feed index is thousands of ids, and SQLite binds one variable per
        // id — over the 999-variable ceiling on older Android builds. A NOT IN also
        // cannot be chunked, since each chunk would delete the other chunks' rows.
        val remote = remoteIds.toHashSet()
        val doomed = episodeDao.getEpisodeIdsByFeed(feedId)
            .filter { it !in remote && it !in activeDownloadIds }

        if (doomed.isEmpty()) return
        Log.d("EpisodeRepository", "Pruning ${doomed.size} stale episode(s) from feed $feedId")
        doomed.chunked(LOCAL_ID_CHUNK).forEach { episodeDao.deleteEpisodesByIds(it) }
    }

    /**
     * Sets played state on many episodes at once.
     *
     * Uses the server's bulk endpoint, which SETS the state rather than toggling,
     * so it is correct for a selection whose current state the app has not loaded
     * — which is now the normal case, since "Select all" covers the whole feed
     * while only the visible window is in memory.
     */
    suspend fun bulkSetPlayed(episodeIds: List<Int>, played: Boolean) {
        if (episodeIds.isEmpty()) return
        val action = if (played) "mark_played" else "mark_unplayed"
        val now = System.currentTimeMillis()

        if (CastCharmApp.isOfflineMode) {
            // Local only — no request is made, so only SQLite's limit applies.
            episodeIds.chunked(LOCAL_ID_CHUNK).forEach {
                episodeDao.updatePlayedStatusForIds(it, played, now, pending = true)
            }
            return
        }

        episodeIds.chunked(ServerLimits.current.bulkIdsPerRequest).forEach { chunk ->
            api.bulkEpisodeAction(BulkEpisodeRequest(episode_ids = chunk, action = action))
            episodeDao.updatePlayedStatusForIds(chunk, played, now, pending = false)
        }
    }

    /**
     * Merges a batch of server episodes into the DB, returning them as entities in
     * the order the server gave them.
     *
     * The existing-row lookup is one batched query. It used to be a
     * getEpisodeOnce() per episode inside a map — a full round trip through Room
     * for every record in the page, on top of the identical batch lookup
     * mergeFromApi() does immediately afterwards anyway.
     */
    private suspend fun mergePage(page: List<EpisodeOut>): List<EpisodeEntity> {
        if (page.isEmpty()) return emptyList()

        val db = AppDatabase.getDatabase(CastCharmApp.instance)
        val allPhoneDownloadEpisodeIds = db.downloadDao()
            .getAllDownloadsOnceOrdered()
            .map { it.episode_id }
            .toSet()

        val existingById = episodeDao.getEpisodesByIds(page.map { it.id }).associateBy { it.id }

        val merged = page.map { remote ->
            remote.toEntity(
                existing = existingById[remote.id],
                hasActivePhoneDownload = remote.id in allPhoneDownloadEpisodeIds
            )
        }

        episodeDao.mergeFromApi(
            episodes = merged,
            activePhoneDownloadEpisodeIds = page.map { it.id }
                .filter { it in allPhoneDownloadEpisodeIds }
                .toSet()
        )

        return merged
    }

    fun getUnplayedEpisodes(feedId: Int): Flow<List<EpisodeEntity>> =
        episodeDao.getUnplayedEpisodesByFeed(feedId)

    fun getContinueListening(limit: Int = 10): Flow<List<EpisodeEntity>> =
        episodeDao.getContinueListening(limit)

    // Shared write path for a batch of freshly fetched episodes: resolve each
    // against its existing row and the phone's download queue, then merge.
    private suspend fun mergeRemoteEpisodes(remote: List<EpisodeOut>) {
        if (remote.isEmpty()) return

        val existingMap = remote
            .mapNotNull { episodeDao.getEpisodeOnce(it.id) }
            .associateBy { it.id }

        val db = AppDatabase.getDatabase(CastCharmApp.instance)
        val allPhoneDownloadEpisodeIds = db.downloadDao()
            .getAllDownloadsOnceOrdered()
            .map { it.episode_id }
            .toSet()

        val merged = remote.map {
            it.toEntity(
                existing = existingMap[it.id],
                hasActivePhoneDownload = it.id in allPhoneDownloadEpisodeIds
            )
        }

        episodeDao.mergeFromApi(
            episodes = merged,
            activePhoneDownloadEpisodeIds =
                allPhoneDownloadEpisodeIds.intersect(remote.map { it.id }.toSet())
        )
    }

    // The dashboard's Continue Listening card is a live query over the local DB
    // (played = 0 AND play_position_seconds > 0), so this has to leave the phone
    // agreeing with the server about the whole set — not merely about the rows
    // the server happened to send.
    suspend fun fetchAndCacheContinueListening(limit: Int = 10) {
        if (CastCharmApp.isOfflineMode) {
            Log.d("EpisodeRepository", "Skipping fetchAndCacheContinueListening while offline")
            return
        }

        try {
            val remote = api.getContinueListening(limit)
            mergeRemoteEpisodes(remote)

            // Reconcile what the server left out. This endpoint answers with a
            // *set*, and an episode that was finished, cleared, or hidden simply
            // stops being a member — the response says nothing about it at all.
            // Merging only what came back can therefore add rows to the phone's
            // idea of "in progress" but never retire one, so a row that went stale
            // survived every refresh and sat on the dashboard indefinitely. It got
            // corrected only when something unrelated happened to fetch it, which
            // is exactly the episode "updating itself" on screen after you tap
            // through to it.
            //
            // Rows carrying unsent local changes are left alone: there the phone
            // holds the newer value, and the server's omission is only evidence
            // about what the server has been told so far.
            //
            // Cost is bounded by `limit` single-episode fetches per refresh, and
            // is usually zero. It is not always zero even when nothing is stale:
            // the server also requires status = "downloaded" for membership, so an
            // episode still held only on the phone is omitted for a reason that is
            // not "finished" and gets re-checked each time. That is the price of
            // the two predicates differing, and a correct answer is worth it.
            val remoteIds = remote.map { it.id }.toSet()
            val orphans = episodeDao.getContinueListening(limit).first()
                .filter { it.id !in remoteIds }
                .filterNot { it.sync_pending_played || it.sync_pending_progress }

            if (orphans.isNotEmpty()) {
                Log.d(
                    "EpisodeRepository",
                    "Reconciling ${orphans.size} stale continue-listening rows"
                )
                mergeRemoteEpisodes(
                    orphans.mapNotNull { local ->
                        runCatching { api.getEpisode(local.id) }
                            .onFailure {
                                Log.w(
                                    "EpisodeRepository",
                                    "Could not reconcile episode ${local.id}",
                                    it
                                )
                            }
                            .getOrNull()
                    }
                )
            }
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
        // Clamped once at the top so every write below — local and remote — uses
        // the same value. Shadowed rather than renamed: the parameter name is part
        // of this function's signature and callers pass it by name.
        @Suppress("NAME_SHADOWING")
        val position = clampProgressSeconds(position)
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
            api.updateProgress(episodeId, progressRequest(position))
            episodeDao.updateProgress(episodeId, position, now, pending = false)

            if (existing.played != targetPlayed) {
                api.setPlayed(episodeId, targetPlayed)
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

    // Explicit set used by the player's "Mark played" button. Writes the final
    // state directly instead of re-deriving it from an RSS duration (which is
    // often a few percent off the real file and so never crossed the threshold).
    suspend fun setPlayed(episodeId: Int, played: Boolean) {
        val now = System.currentTimeMillis()
        if (CastCharmApp.isOfflineMode) {
            episodeDao.updatePlayedStatus(episodeId, played, now, pending = true)
            return
        }
        try {
            api.setPlayed(episodeId, played)
            episodeDao.updatePlayedStatus(episodeId, played, now, pending = false)
        } catch (e: Exception) {
            Log.e("EpisodeRepository", "Error setting played state, marking for later sync", e)
            episodeDao.updatePlayedStatus(episodeId, played, now, pending = true)
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
            api.setPlayed(episodeId, newState)
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

    // A sync_pending flag means the phone holds a newer value for the fields it
    // guards — that is the only reason it is ever set. This mapping used to carry
    // the flags forward while overwriting those very fields with the server's
    // copy, leaving a row that advertised an unsent change whose content had
    // already been destroyed. The user's "mark played" reverted silently, and the
    // later flush then replayed a toggle against state it no longer matched.
    val playedSource = existing?.takeIf { it.sync_pending_played }
    val progressSource = existing?.takeIf { it.sync_pending_progress }

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
        played = playedSource?.played ?: played,
        play_position_seconds = progressSource?.play_position_seconds ?: play_position_seconds,
        last_played_at = (playedSource ?: progressSource)?.last_played_at
            ?: parseServerDateTime(last_played_at),
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