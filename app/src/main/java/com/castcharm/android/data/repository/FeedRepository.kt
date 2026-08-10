package com.castcharm.android.data.repository

// FeedRepository bridges the remote API and the local FeedDao.
// The server is the sole source of truth for which feeds exist: any local feed
// not present in the server response is deleted from the local DB during refresh.
// This means a feed deleted on the server will disappear from the Android app
// on the next successful refresh, even if no delete action was taken in the app.

import android.util.Log
import com.castcharm.android.CastCharmApp
import com.castcharm.android.provider.PodcastArtworkProvider
import com.castcharm.android.data.api.CastCharmApi
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
                playback_speed = existingById[feed.id]?.playback_speed
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
        if (remoteIds.isEmpty()) {
            feedDao.deleteAll()
        } else {
            feedDao.deleteFeedsNotIn(remoteIds)
        }

        feedDao.upsertAll(entities)
    }

    // Deletes a feed from the local DB. The server-side delete is expected to have
    // been triggered separately (via a web UI action); this just reflects it locally.
    suspend fun deleteFeed(feedId: Int) {
        feedDao.deleteById(feedId)
    }
}