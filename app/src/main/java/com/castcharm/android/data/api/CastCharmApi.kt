package com.castcharm.android.data.api

import com.castcharm.android.data.api.models.*
import okhttp3.ResponseBody
import retrofit2.http.*

interface CastCharmApi {
    // Auth
    @GET("api/auth/status")
    suspend fun getAuthStatus(): AuthStatus

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest)

    @POST("api/auth/logout")
    suspend fun logout()

    // Feeds
    @GET("api/feeds")
    suspend fun getFeeds(): List<FeedOut>

    @GET("api/feeds/{feed_id}")
    suspend fun getFeed(@Path("feed_id") feedId: Int): FeedOut

    @GET("api/feeds/{feed_id}/episodes")
    suspend fun getEpisodes(
        @Path("feed_id") feedId: Int,
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        @Query("include_hidden") includeHidden: Boolean = false,
        @Query("order") order: String = "desc"
    ): List<EpisodeOut>

    @GET("api/feeds/{feed_id}/cover.jpg")
    suspend fun getFeedCoverImage(@Path("feed_id") feedId: Int): ResponseBody

    @POST("api/feeds/refresh-all")
    suspend fun refreshAllFeeds()

    @POST("api/feeds/{feed_id}/refresh")
    suspend fun refreshFeed(@Path("feed_id") feedId: Int)

    // Episodes
    @GET("api/episodes")
    suspend fun getAllEpisodes(
        @Query("status") status: String? = null,
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        @Query("include_hidden") includeHidden: Boolean = false,
        @Query("order") order: String = "desc"
    ): List<EpisodeOut>

    @GET("api/episodes/{episode_id}")
    suspend fun getEpisode(@Path("episode_id") episodeId: Int): EpisodeOut

    @Streaming
    @GET("api/episodes/{episode_id}/stream")
    suspend fun streamEpisode(@Path("episode_id") episodeId: Int): ResponseBody

    @GET("api/episodes/{episode_id}/cover.jpg")
    suspend fun getEpisodeCoverImage(@Path("episode_id") episodeId: Int): ResponseBody

    @POST("api/episodes/{episode_id}/download")
    suspend fun queueDownload(@Path("episode_id") episodeId: Int)

    // Progress tracking
    @POST("api/episodes/{episode_id}/progress")
    suspend fun updateProgress(
        @Path("episode_id") episodeId: Int,
        @Body request: ProgressRequest
    )

    // Played status
    @POST("api/episodes/{episode_id}/played")
    suspend fun togglePlayed(@Path("episode_id") episodeId: Int)

    // Hide / unhide
    @POST("api/episodes/{episode_id}/hide")
    suspend fun hideEpisode(@Path("episode_id") episodeId: Int)

    @POST("api/episodes/{episode_id}/unhide")
    suspend fun unhideEpisode(@Path("episode_id") episodeId: Int)

    // Continue listening
    @GET("api/episodes/continue-listening")
    suspend fun getContinueListening(@Query("limit") limit: Int = 10): List<EpisodeOut>

    // Suggestions
    @GET("api/episodes/suggestions")
    suspend fun getSuggestions(): SuggestionsOut

    // Status
    @GET("api/status")
    suspend fun getStatus(): AppStatus

    // Settings
    @GET("api/settings")
    suspend fun getSettings(): GlobalSettingsOut

    // Stats
    @GET("api/stats")
    suspend fun getStats(): Map<String, Any?>
}