package com.castcharm.android.data.api

// Retrofit interface that mirrors the CastCharm server's REST API.
// All methods are suspend functions (called from coroutines) and map directly
// to the FastAPI endpoints defined in the server's router files.
//
// Endpoint groups:
//   Auth        — check session, login, logout
//   Feeds       — list, get, refresh, cover image
//   Episodes    — list (global or per-feed), single, stream, download, cover image
//   Progress    — update playback position
//   Played      — toggle played/unplayed status
//   Visibility  — hide/unhide episodes from the default view
//   Discovery   — continue listening, suggestions
//   Status      — app health and server info
//   Settings    — global server configuration (download path, naming rules, etc.)
//   Stats       — library statistics (returned as a generic map because the server
//                 returns a dynamically shaped object)

import com.castcharm.android.data.api.models.*
import okhttp3.ResponseBody
import retrofit2.http.*

interface CastCharmApi {
    // ---- Auth ---------------------------------------------------------------
    // Checks whether auth is enabled on this server and whether the current
    // cookie session is valid. Called on startup and after every reconnect.
    @GET("api/auth/status")
    suspend fun getAuthStatus(): AuthStatus

    // Submits username/password credentials. The server sets a session cookie
    // on success, which PersistentCookieJar captures and persists automatically.
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest)

    // Invalidates the server-side session. The cookie is cleared separately
    // by AppSessionManager.logoutAndForgetSession() → ApiClient.clearCookies().
    @POST("api/auth/logout")
    suspend fun logout()

    // ---- Feeds --------------------------------------------------------------
    @GET("api/feeds")
    suspend fun getFeeds(): List<FeedOut>

    @GET("api/feeds/{feed_id}")
    suspend fun getFeed(@Path("feed_id") feedId: Int): FeedOut

    // Paginated episode list for a single feed. limit+1 is requested by
    // EpisodeRepository so it can detect whether more pages exist.
    @GET("api/feeds/{feed_id}/episodes")
    suspend fun getEpisodes(
        @Path("feed_id") feedId: Int,
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        @Query("include_hidden") includeHidden: Boolean = false,
        @Query("order") order: String = "desc"
    ): List<EpisodeOut>

    // Returns raw JPEG bytes for the feed's cover artwork.
    @GET("api/feeds/{feed_id}/cover.jpg")
    suspend fun getFeedCoverImage(@Path("feed_id") feedId: Int): ResponseBody

    // Triggers the server to refresh all subscribed RSS feeds.
    @POST("api/feeds/refresh-all")
    suspend fun refreshAllFeeds()

    // Triggers the server to refresh a single feed.
    @POST("api/feeds/{feed_id}/refresh")
    suspend fun refreshFeed(@Path("feed_id") feedId: Int)

    // ---- Episodes -----------------------------------------------------------
    // Cross-feed episode list. status filter is used by the server-side download
    // queue (e.g., status="downloading") when monitoring active server downloads.
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

    // @Streaming prevents Retrofit from buffering the entire response body in
    // memory before returning — essential for large audio files. DownloadWorker
    // reads the ResponseBody as a stream and writes it to disk in chunks.
    @Streaming
    @GET("api/episodes/{episode_id}/stream")
    suspend fun streamEpisode(@Path("episode_id") episodeId: Int): ResponseBody

    @GET("api/episodes/{episode_id}/cover.jpg")
    suspend fun getEpisodeCoverImage(@Path("episode_id") episodeId: Int): ResponseBody

    // Asks the server to queue a server-side download for this episode.
    @POST("api/episodes/{episode_id}/download")
    suspend fun queueDownload(@Path("episode_id") episodeId: Int)

    // ---- Progress tracking --------------------------------------------------
    // Persists the current playback position to the server. Called every 10s
    // by PlayerService and during seek completion.
    @POST("api/episodes/{episode_id}/progress")
    suspend fun updateProgress(
        @Path("episode_id") episodeId: Int,
        @Body request: ProgressRequest
    )

    // ---- Played status ------------------------------------------------------
    // Toggles played/unplayed on the server. The server determines the new state
    // based on the current state, so no request body is needed.
    @POST("api/episodes/{episode_id}/played")
    suspend fun togglePlayed(@Path("episode_id") episodeId: Int)

    // ---- Hide / unhide ------------------------------------------------------
    // Hidden episodes are excluded from the default episode list and counts.
    @POST("api/episodes/{episode_id}/hide")
    suspend fun hideEpisode(@Path("episode_id") episodeId: Int)

    @POST("api/episodes/{episode_id}/unhide")
    suspend fun unhideEpisode(@Path("episode_id") episodeId: Int)

    // ---- Discovery ----------------------------------------------------------
    // Returns episodes that have been started but not finished, ordered by most
    // recently played. Used for the "Continue Listening" section on the Dashboard.
    @GET("api/episodes/continue-listening")
    suspend fun getContinueListening(@Query("limit") limit: Int = 10): List<EpisodeOut>

    // Returns bucketed episode suggestions (short/medium/long duration). Used
    // for the "Suggested Listening" section on the Dashboard.
    @GET("api/episodes/suggestions")
    suspend fun getSuggestions(): SuggestionsOut

    // ---- Status / settings / stats ------------------------------------------
    // Basic server health info (version, storage usage).
    @GET("api/status")
    suspend fun getStatus(): AppStatus

    // Global server configuration including download path, naming rules, and
    // the played-detection threshold. Used to drive filename and progress logic.
    @GET("api/settings")
    suspend fun getSettings(): GlobalSettingsOut

    // Library-wide statistics. Returned as a generic map because the server's
    // response shape includes computed aggregates that vary between versions.
    @GET("api/stats")
    suspend fun getStats(): Map<String, Any?>
}