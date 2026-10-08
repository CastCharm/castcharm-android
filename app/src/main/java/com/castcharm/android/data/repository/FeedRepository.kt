package com.castcharm.android.data.repository

// FeedRepository bridges the remote API and the local FeedDao.
// The server is the sole source of truth for which feeds exist: any local feed
// not present in the server response is deleted from the local DB during refresh.
// This means a feed deleted on the server will disappear from the Android app
// on the next successful refresh, even if no delete action was taken in the app.

import android.util.Log
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.provider.PodcastArtworkProvider
import com.castcharm.android.data.api.CastCharmApi
import com.castcharm.android.data.api.ServerLimits
import com.castcharm.android.data.api.models.parseServerDateTime
import com.castcharm.android.data.api.models.resolveImageUrl
import com.castcharm.android.data.api.models.withFeedCoverToken
import com.castcharm.android.data.db.dao.FeedDao
import com.castcharm.android.data.db.entities.FeedEntity
import kotlinx.coroutines.flow.Flow

class FeedRepository(
    private val api: CastCharmApi,
    private val feedDao: FeedDao
) {
    // Expose DB flows directly — callers observe these for real-time updates.
    fun getFeeds(): Flow<List<FeedEntity>> = feedDao.getAllFeeds()
    fun getFeed(feedId: Int): Flow<FeedEntity> = feedDao.getFeed(feedId)

    // Fetch feeds from the server and sync the local DB.
    // In offline mode, does nothing (callers see the cached DB state via the flows).
    // In online mode:
    //   1. Fetch the full feed list from the server.
    //   2. Resolve relative image URLs to absolute URLs.
    //   3. Delete local feeds not present in the server response.
    //   4. Upsert the server feeds into the DB (@Upsert avoids the CASCADE issue).
    suspend fun refreshFeeds() {
        if (CastCharmApp.isOfflineMode) {
            Log.d("FeedRepository", "Skipping refreshFeeds while offline")
            return
        }

        // Learn what this server accepts before anything sizes a request against
        // it. This sits here because refreshFeeds is the one call every online
        // entry point makes — dashboard, feed list, episode list, Android Auto —
        // so the limits are known regardless of where the user starts. It is
        // throttled and never throws; see ServerLimits.ensureFresh.
        ServerLimits.ensureFresh(api)

        val feeds = api.getFeeds()
        val baseUrl = CastCharmApp.apiClient.getBaseUrl()

        // Snapshot existing rows first so local-only fields (playback_speed) are
        // not erased when the server response overwrites the rest of the row.
        val existingById = feedDao.getFeedOnceAll().associateBy { it.id }

        val entities = feeds.map { feed ->
            FeedEntity(
                id = feed.id,
                url = feed.url,
                title = feed.title ?: "",
                description = feed.description,
                // withFeedCoverToken only affects URLs pointing at this server's
                // own /api/feeds/{id}/cover.jpg, which the server substitutes for
                // the remote artwork once a local cover.jpg exists. That URL is
                // keyed by feed id alone and so collides across a recycled id.
                image_url = withFeedCoverToken(resolveImageUrl(baseUrl, feed.image_url), feed.url),
                custom_image_url = withFeedCoverToken(
                    resolveImageUrl(baseUrl, feed.custom_image_url), feed.url
                ),
                podcast_group = feed.podcast_group,
                auto_download_new = feed.auto_download_new,
                active = feed.active,
                last_synced_at = parseServerDateTime(feed.last_checked) ?: System.currentTimeMillis(),
                last_error = feed.last_error,
                episode_count = feed.episode_count,
                unplayed_count = feed.unplayed_count,
                downloaded_count = feed.downloaded_count,
                playback_speed = existingById[feed.id]?.playback_speed,
                play_order = feed.play_order
            )
        }

        // A feed id that now points at a different RSS URL is a RECYCLED id: the old
        // podcast was deleted and the server handed its id to a new subscription
        // (SQLite reuses rowids — the feeds table has no AUTOINCREMENT). The cover
        // URL is derived from the id alone, so without this the new podcast inherits
        // the deleted one's artwork out of cache.
        //
        // Detected here rather than in the delete action so it holds however the feed
        // was removed — including from the web UI, where the phone only ever sees the
        // result of the change.
        val recycledIds = entities
            .filter { incoming ->
                val previous = existingById[incoming.id]
                previous != null && previous.url != incoming.url
            }
            .map { it.id }

        for (id in recycledIds) {
            Log.i("FeedRepository", "Feed id $id was reused by a different podcast — clearing its artwork")
            PodcastArtworkProvider.evictFeedArtwork(CastCharmApp.instance, id)
        }

        val remoteIds = entities.map { it.id }

        // If the server returns no feeds, delete all local feeds (the user has no
        // active subscriptions). Otherwise, prune only the feeds missing from the response.
        // The cascade will remove the episodes of any feed that is gone; take
        // their files with them, or they sit on disk uncounted and unreachable.
        val doomed = existingById.keys.filterNot { it in remoteIds }
        if (doomed.isNotEmpty()) {
            releaseFilesForFeeds(doomed)
        }
        if (remoteIds.isEmpty()) {
            feedDao.deleteAll()
        } else {
            feedDao.deleteFeedsNotIn(remoteIds)
        }

        // Whether any feed's artwork actually moved, decided before the upsert
        // overwrites the old values. A feed the app has not seen before counts:
        // its episodes may already be cached from a previous install of the same
        // subscription and would otherwise keep whatever art they had.
        val artworkChanged = entities.any { incoming ->
            val previous = existingById[incoming.id]
            previous == null || previous.image_url != incoming.image_url
        }

        feedDao.upsertAll(entities)

        // Push the freshly written artwork URLs down onto episodes. This is the one
        // moment a feed's cover can have changed, so it is the right place for a
        // whole-table update — as opposed to inside every episode merge, where it
        // re-scanned the episodes table on each page the list happened to fetch.
        // Guarded so the ordinary refresh, where nothing moved, costs nothing.
        if (artworkChanged) {
            Log.d("FeedRepository", "Feed artwork changed — propagating to episodes")
            AppDatabase.getDatabase(CastCharmApp.instance)
                .episodeDao()
                .syncFeedArtworkOntoEpisodes()
        }
    }

    // Deletes a feed from the local DB. The server-side delete is expected to have
    // been triggered separately (via a web UI action); this just reflects it locally.
    suspend fun deleteFeed(feedId: Int) {
        releaseFilesForFeeds(listOf(feedId))
        feedDao.deleteById(feedId)
    }

    private suspend fun releaseFilesForFeeds(feedIds: List<Int>) {
        val db = com.castcharm.android.data.db.AppDatabase.getDatabase(CastCharmApp.instance)
        val eps = db.episodeDao().getEpisodesWithFilesForFeeds(feedIds)
        val scheduler = com.castcharm.android.download.DownloadScheduler(CastCharmApp.instance)
        for (ep in eps) {
            runCatching { scheduler.cancelDownload(ep.id) }
            ep.local_path?.let { runCatching { java.io.File(it).delete() } }
        }
    }
}