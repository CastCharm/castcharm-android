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

    // Fetches a specific set of episodes, comma-separated, max 500 per call.
    // This is how the windowed list fills itself in: it knows exactly which rows
    // are on screen and asks for those, rather than an offset that only lines up
    // if its idea of the feed's ordering matches the server's.
    @GET("api/feeds/{feed_id}/episodes")
    suspend fun getEpisodesByIds(
        @Path("feed_id") feedId: Int,
        @Query("ids") ids: String,
        @Query("include_hidden") includeHidden: Boolean = false
    ): List<EpisodeOut>

    // The feed's episode ids in display order — see EpisodeIndexOut. Offsets
    // into this list address getEpisodes() pages exactly, because the server
    // builds both from the same filter and ORDER BY.
    //
    // filter is "all", "unplayed" or "in_progress". There is deliberately no
    // "downloaded": that means "on this phone", which only the local DB knows.
    //
    // Added after the Android app shipped, so a server that predates it answers
    // 404 — EpisodeRepository treats that as "no index available" and the list
    // falls back to sequential loading rather than failing.
    @GET("api/feeds/{feed_id}/episode-index")
    suspend fun getEpisodeIndex(
        @Path("feed_id") feedId: Int,
        @Query("filter") filter: String = "all",
        @Query("include_hidden") includeHidden: Boolean = false,
        @Query("order") order: String = "desc"
    ): EpisodeIndexOut

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


    // Removes a podcast and all of its episode records from the SERVER, for every
    // client. deleteFiles additionally purges the downloaded audio, sidecar XML and
    // cover art from the server's disk; without it those files are left behind.
    // There is no undo, and nothing about this is local to the phone.
    @DELETE("api/feeds/{feed_id}")
    suspend fun deleteFeed(
        @Path("feed_id") feedId: Int,
        @Query("delete_files") deleteFiles: Boolean = false,
    )

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

    // One action, many episodes, one request. Used by multi-select so marking a
    // whole feed played is a handful of calls rather than one per episode.
    @POST("api/episodes/bulk")
    suspend fun bulkEpisodeAction(@Body body: BulkEpisodeRequest): BulkEpisodeResult

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
    // There is deliberately no binding for POST api/episodes/{id}/played. That
    // endpoint *toggles*, and no caller in this app ever wanted that: every one
    // of them had already decided on a target state and used a toggle to reach
    // it, which only lands on the right answer while the server's copy agrees
    // with the phone's. A flush that ran twice, or that raced a change made on
    // another device, drove the state the wrong way. Use setPlayed() below.

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

    // The request ceilings this server enforces. Read through ServerLimits, which
    // caches the answer and falls back to conservative defaults for a server too
    // old to have the endpoint.
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
    @POST("api/player/play")
    suspend fun playerPlay(@Body body: PlayerPlayRequest): PlayerStateOut

    @POST("api/player/next")
    suspend fun playerNext(): PlayerStateOut

    @POST("api/player/prev")
    suspend fun playerPrev(): PlayerStateOut
}

/**
 * Sets an episode's played state to an absolute value.
 *
 * Routed through the bulk endpoint because its mark_played / mark_unplayed
 * actions assign rather than flip, which makes this safe to repeat: the phone
 * decides the target state once, and sending it again — on a retry, a second
 * flush, or after another device has already made the same change — converges
 * on that state instead of oscillating around it.
 *
 * Every played-state write in the app goes through here. The single-episode
 * toggle endpoint is intentionally not bound; see the note on it above.
 */
suspend fun CastCharmApi.setPlayed(episodeId: Int, played: Boolean) {
    bulkEpisodeAction(
        BulkEpisodeRequest(
            episode_ids = listOf(episodeId),
            action = if (played) "mark_played" else "mark_unplayed"
        )
    )
}