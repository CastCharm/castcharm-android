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

    // Trades a valid login session for a permanent API key. Requires a session
    // cookie, so it can only be called right after login (or while an existing
    // cookie is still good, which is how already-installed apps migrate).
    // Returns 404 on servers too old to support keys — callers must tolerate that
    // and stay on cookie auth.
    @POST("api/auth/exchange-key")
    suspend fun exchangeKey(@Body request: ExchangeKeyRequest): ApiKeyCreated

    // Revokes the key used to make this very request. Called on logout so the
    // device doesn't leave a live credential behind on the server.
    @DELETE("api/settings/api-keys/self")
    suspend fun revokeOwnKey()

    // Every key the server still honours; used to notice when this device's
    // key was revoked while the session cookie keeps requests working.
    @GET("api/settings/api-keys")
    suspend fun listApiKeys(): List<ApiKeyInfo>

    // Renames a key by id. The Android app calls this only for its own key
    // (id stored in AuthStore at enrolment time) so the user can label the
    // device something friendlier than the default hardware model string.
    @PATCH("api/settings/api-keys/{key_id}")
    suspend fun renameApiKey(
        @Path("key_id") keyId: Int,
        @Body body: ApiKeyRenameRequest,
    )

    // ---- Feeds --------------------------------------------------------------
    @GET("api/feeds")
    suspend fun getFeeds(): List<FeedOut>

    @GET("api/feeds/{feed_id}")
    suspend fun getFeed(@Path("feed_id") feedId: Int): FeedOut

    // Per-feed settings the phone can change (currently just play_order).
    @PUT("api/feeds/{feed_id}")
    suspend fun updateFeed(@Path("feed_id") feedId: Int, @Body body: FeedUpdateRequest): FeedOut

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

    // Adds a new feed by URL. The server resolves redirects and detects RSS
    // from podcast page URLs, so the raw URL the user types is fine.
    @POST("api/feeds")
    suspend fun addFeed(@Body body: AddFeedRequest): FeedOut

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
        @Query("order") order: String = "desc",
        @Query("search") search: String? = null
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

    // Retries a failed server-side download (server endpoint accepts failed/pending/skipped).
    @POST("api/episodes/{episode_id}/retry")
    suspend fun retryServerDownload(@Path("episode_id") episodeId: Int)

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

    // Sets played/unplayed explicitly. Safe to repeat — what every automatic
    // path (end of episode, threshold, offline flush) uses so a second call can
    // never flip an episode back.
    @POST("api/episodes/{episode_id}/played")
    suspend fun setPlayed(@Path("episode_id") episodeId: Int, @Body body: PlayedRequest)

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

    // Request ceilings the server enforces (see ServerLimits). Older servers
    // return 404; ServerLimits falls back to defaults in that case.
    @GET("api/limits")
    suspend fun getLimits(): LimitsOut

    // Global server configuration including download path, naming rules, and
    // the played-detection threshold. Used to drive filename and progress logic.
    @GET("api/settings")
    suspend fun getSettings(): GlobalSettingsOut

    // Library-wide statistics. Returned as a generic map because the server's
    // response shape includes computed aggregates that vary between versions.
    @GET("api/stats")
    suspend fun getStats(): Map<String, Any?>

    // ---- Playlists ----------------------------------------------------------
    @GET("api/playlists")
    suspend fun getPlaylists(): List<PlaylistOut>

    @POST("api/playlists")
    suspend fun createPlaylist(@Body body: CreatePlaylistRequest): PlaylistOut

    @PUT("api/playlists/{id}")
    suspend fun updatePlaylist(@Path("id") id: Int, @Body body: UpdatePlaylistRequest): PlaylistOut

    @DELETE("api/playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: Int)

    @GET("api/playlists/{id}/episodes")
    suspend fun getPlaylistEpisodes(@Path("id") id: Int): List<EpisodeOut>

    @POST("api/playlists/{id}/episodes")
    suspend fun addToPlaylist(@Path("id") id: Int, @Body body: AddToPlaylistRequest)

    @DELETE("api/playlists/{id}/episodes/{episode_id}")
    suspend fun removeFromPlaylist(@Path("id") id: Int, @Path("episode_id") episodeId: Int)

    @PUT("api/playlists/{id}/episodes/reorder")
    suspend fun reorderPlaylist(@Path("id") id: Int, @Body body: ReorderRequest)

    @GET("api/playlists/episode-memberships")
    suspend fun getEpisodePlaylists(@Query("episode_id") episodeId: Int): List<PlaylistOut>

    @GET("api/playlists/feed-memberships")
    suspend fun getFeedPlaylistMemberships(@Query("feed_id") feedId: Int): FeedPlaylistMemberships

    // ---- Player context -----------------------------------------------------
    @GET("api/player/state")
    suspend fun getPlayerState(): PlayerStateOut

    // Direct pointer update, no smart-start: keeps the server's "current
    // episode" in step as the phone's own queue advances.
    @PUT("api/player/state")
    suspend fun updatePlayerState(@Body body: PlayerPlayRequest): PlayerStateOut

    @POST("api/player/play")
    suspend fun playerPlay(@Body body: PlayerPlayRequest): PlayerStateOut

    @POST("api/player/next")
    suspend fun playerNext(): PlayerStateOut

    @POST("api/player/prev")
    suspend fun playerPrev(): PlayerStateOut
}