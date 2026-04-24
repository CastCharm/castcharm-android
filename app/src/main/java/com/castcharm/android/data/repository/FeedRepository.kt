package com.castcharm.android.data.repository

import android.util.Log
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.CastCharmApi
import com.castcharm.android.data.api.models.parseServerDateTime
import com.castcharm.android.data.api.models.resolveImageUrl
import com.castcharm.android.data.db.dao.FeedDao
import com.castcharm.android.data.db.entities.FeedEntity
import kotlinx.coroutines.flow.Flow

class FeedRepository(
    private val api: CastCharmApi,
    private val feedDao: FeedDao
) {
    fun getFeeds(): Flow<List<FeedEntity>> = feedDao.getAllFeeds()

    fun getFeed(feedId: Int): Flow<FeedEntity> = feedDao.getFeed(feedId)

    /**
     * Fetch feeds from the server and update the local database.
     * In offline mode, do nothing and keep cached DB state.
     *
     * The server is the source of truth for what feeds exist.
     * Any local feed missing from the server response is removed.
     */
    suspend fun refreshFeeds() {
        if (CastCharmApp.isOfflineMode) {
            Log.d("FeedRepository", "Skipping refreshFeeds while offline")
            return
        }

        val feeds = api.getFeeds()
        val baseUrl = CastCharmApp.apiClient.getBaseUrl()
        val entities = feeds.map { feed ->
            FeedEntity(
                id = feed.id,
                url = feed.url,
                title = feed.title ?: "",
                description = feed.description,
                image_url = resolveImageUrl(baseUrl, feed.image_url),
                custom_image_url = resolveImageUrl(baseUrl, feed.custom_image_url),
                podcast_group = feed.podcast_group,
                auto_download_new = feed.auto_download_new,
                active = feed.active,
                last_synced_at = parseServerDateTime(feed.last_checked) ?: System.currentTimeMillis(),
                last_error = feed.last_error,
                episode_count = feed.episode_count,
                unplayed_count = feed.unplayed_count,
                downloaded_count = feed.downloaded_count
            )
        }

        val remoteIds = entities.map { it.id }

        if (remoteIds.isEmpty()) {
            feedDao.deleteAll()
        } else {
            feedDao.deleteFeedsNotIn(remoteIds)
        }

        feedDao.upsertAll(entities)
    }

    suspend fun deleteFeed(feedId: Int) {
        feedDao.deleteById(feedId)
    }
}