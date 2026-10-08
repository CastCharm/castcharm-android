package com.castcharm.android.player

// PlayerService is a MediaLibraryService that hosts ExoPlayer and the Media3
// MediaLibrarySession. It runs in the app's process but outlives individual
// Activities, keeping audio playback alive in the background.
//
// Architecture:
//   - PlayerService: the Service itself. Owns ExoPlayer, the MediaLibrarySession,
//     and the progress-tracking coroutine. Handles playback lifecycle and Android Auto
//     library change notifications.
//   - PlayerLibrarySessionCallback: nested class implementing the MediaLibrarySession
//     callback interface. Handles browse tree requests from Android Auto and resolves
//     episode MediaItems to their actual URIs before ExoPlayer sees them.
//
// Android Auto integration:
//   The browse tree is organized as:
//     root → [Continue, Recent, Podcasts, Downloads]
//     Podcasts → feed_<id> → episode_<id>
//   Offline mode collapses the tree to only the Downloads section.
//   A polling loop (startLibraryRefreshObserver) detects DB changes and notifies
//   Android Auto via notifyChildrenChanged() so the UI stays fresh.
//
// Progress tracking:
//   startProgressTracking() runs a 1-second loop that syncs playback position to DB
//   and the server. All write operations follow the same write-local-then-sync pattern
//   as EpisodeRepository — if the server call fails, pending=true is set for SyncWorker.
//
// Artwork:
//   All artwork URIs exposed to Android Auto use content:// scheme via PodcastArtworkProvider.
//   Direct http:// URLs cannot be used because Android Auto runs in a separate process
//   with no access to the app's authenticated OkHttpClient.

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.castcharm.android.AppAuthState
import com.castcharm.android.CastCharmApp
import com.castcharm.android.MainActivity
import com.castcharm.android.R
import com.castcharm.android.SKIP_SILENCE_KEY
import com.castcharm.android.data.api.ApiKeyInterceptor
import com.castcharm.android.data.api.models.PlayedRequest
import com.castcharm.android.data.api.models.PlayerPlayRequest
import com.castcharm.android.data.api.models.ProgressRequest
import com.castcharm.android.data.repository.FeedRepository
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.dao.EpisodeDao
import com.castcharm.android.data.db.dao.FeedDao
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.db.entities.FeedEntity
import com.castcharm.android.data.repository.EpisodeRepository
import com.castcharm.android.data.repository.toEntity
import com.castcharm.android.dataStore
import com.castcharm.android.provider.PodcastArtworkProvider
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// Log tag shared by both PlayerService and PlayerLibrarySessionCallback.
private const val TAG = "CastCharm"

// Browse tree node IDs used in the Android Auto MediaLibrarySession.
// These string constants must match what Android Auto sends back in parentId
// parameters to onGetChildren().
private const val ROOT_ID = "root"
private const val SECTION_CONTINUE = "section_continue"
private const val SECTION_RECENT = "section_recent"
private const val SECTION_PODCASTS = "section_podcasts"
private const val SECTION_DOWNLOADS = "section_downloads"
// "Continue" row at the top of a listen-in-order feed; expands to that feed's queue.
private const val CATCHUP_PREFIX = "catchup_feed_"
// How many episodes to put in the player's timeline after the one that starts.
// Enough to listen for hours; small enough that artwork and resolution stay cheap.
private const val IN_ORDER_QUEUE_LIMIT = 25
// Suffix appended to packageName to form the authority of PodcastArtworkProvider.
// Must match the authority declared in AndroidManifest.xml.
private const val ARTWORK_AUTHORITY_SUFFIX = ".artwork"


// Mutex prevents a race where multiple coroutines call ensureApiClientInitialized
// simultaneously on startup (service init + browser connect + progress job all
// start at roughly the same time). Only one winner initializes; others wait.
private val apiClientInitMutex = Mutex()

private fun normalizeBaseUrl(url: String): String =
    if (url.endsWith("/")) url else "$url/"

// Lazy ApiClient initialization used by PlayerService so it can function
// after a device restart when WorkManager or the system brings the service
// up before the app process has gone through normal startup. Reads the saved
// server URL from DataStore and initializes ApiClient if it isn't already.
// Returns the base URL string (empty if no URL is saved or init fails).
private suspend fun ensureApiClientInitializedFromStorage(context: Context): String {
    if (CastCharmApp.apiClient.isInitialized) {
        return CastCharmApp.apiClient.getBaseUrl()
    }

    return apiClientInitMutex.withLock {
        // Double-checked locking: another coroutine may have initialized it
        // while we waited for the lock.
        if (CastCharmApp.apiClient.isInitialized) {
            return@withLock CastCharmApp.apiClient.getBaseUrl()
        }

        val savedUrl = context.dataStore.data
            .map { it[stringPreferencesKey("server_url")] }
            .first()
            ?: return@withLock ""

        val normalizedUrl = normalizeBaseUrl(savedUrl)
        try {
            CastCharmApp.apiClient.initialize(normalizedUrl)
        } catch (_: Exception) {
        }

        if (CastCharmApp.apiClient.isInitialized) {
            CastCharmApp.apiClient.getBaseUrl()
        } else {
            normalizedUrl
        }
    }
}

// Blocking wrapper used from OkHttp interceptor context (called on a binder
// thread where suspend functions cannot be used).
private fun ensureApiClientInitializedBlocking(context: Context): String = runBlocking(Dispatchers.IO) {
    ensureApiClientInitializedFromStorage(context)
}

// Custom SessionCommand sent from the speed button in the notification / Android Auto.
// The handler in onCustomCommand() cycles through SPEEDS.
private val SPEED_COMMAND = SessionCommand("com.castcharm.android.CYCLE_SPEED", Bundle.EMPTY)
private val SPEEDS = listOf(0.5f, 0.7f, 1.0f, 1.2f, 1.5f, 1.7f, 2.0f, 2.5f, 3.0f)
// Episodes are marked played when position reaches 98% of duration.
private const val PLAYED_THRESHOLD_PCT = 0.98f

private fun speedLabel(speed: Float) = when (speed) {
    0.5f -> "0.5×"
    0.7f -> "0.7×"
    1.0f -> "1.0×"
    1.2f -> "1.2×"
    1.5f -> "1.5×"
    1.7f -> "1.7×"
    2.0f -> "2.0×"
    2.5f -> "2.5×"
    3.0f -> "3.0×"
    else -> "%.1f×".format(speed)
}

// Builds the now-playing subtitle shown in the notification and Android Auto.
// The current playback speed is rendered into the speed CommandButton's icon
// (see speedIconUri / PodcastArtworkProvider) so we no longer append it to
// the subtitle, where long feed titles routinely truncated it.
private fun buildNowPlayingSubtitle(feedTitle: String?, speed: Float): String {
    return feedTitle.orEmpty()
}


// Extracts the raw feed title from MediaMetadata, stripping the speed label
// suffix that buildNowPlayingSubtitle() appends. Used when refreshing metadata
// after a speed change so we don't double-append the label.
private fun extractBaseFeedTitle(metadata: MediaMetadata): String {
    val extras = metadata.extras
    val fromExtras = extras?.getString("castcharm_feed_title")
    if (!fromExtras.isNullOrBlank()) return fromExtras

    val artist = metadata.artist?.toString()
    if (!artist.isNullOrBlank()) return artist

    val subtitle = metadata.subtitle?.toString()
    if (!subtitle.isNullOrBlank()) {
        // Strip the " • 1.5×" suffix that was previously appended.
        return subtitle.substringBefore(" • ").ifBlank { subtitle }
    }

    return ""
}

// Sections visible in Android Auto's browse tree depend on connectivity mode.
// Offline mode shows only Downloads since network-dependent sections would be empty.
private fun visibleRootSections(isOffline: Boolean): List<String> {
    return if (isOffline) {
        listOf(SECTION_DOWNLOADS)
    } else {
        listOf(
            SECTION_CONTINUE,
            SECTION_RECENT,
            SECTION_PODCASTS,
            SECTION_DOWNLOADS
        )
    }
}

// Returns true if the given Android Auto section parentId should be hidden
// while in offline mode. Used by onGetChildren() to return an empty list
// rather than attempting network queries.
private fun isOfflineHiddenSection(parentId: String): Boolean {
    if (!CastCharmApp.isOfflineMode) return false
    return parentId == SECTION_CONTINUE ||
            parentId == SECTION_RECENT ||
            parentId == SECTION_PODCASTS ||
            parentId.startsWith("feed_")
}

// Speed cycle button shown in the notification shade and Android Auto overflow.
// SLOT_OVERFLOW places it in the "more actions" menu rather than primary controls.
@OptIn(UnstableApi::class)
private fun speedIconResId(speed: Float): Int = when {
    speed < 0.6f  -> R.drawable.ic_play_speed_0_5x
    speed < 0.85f -> R.drawable.ic_play_speed_0_7x
    speed < 1.1f  -> R.drawable.ic_play_speed_1_0x
    speed < 1.35f -> R.drawable.ic_play_speed_1_2x
    speed < 1.6f  -> R.drawable.ic_play_speed_1_5x
    speed < 1.85f -> R.drawable.ic_play_speed_1_7x
    speed < 2.25f -> R.drawable.ic_play_speed_2_0x
    speed < 2.75f -> R.drawable.ic_play_speed_2_5x
    else          -> R.drawable.ic_play_speed_3_0x
}

private fun createSpeedButton(speed: Float): CommandButton =
    CommandButton.Builder(CommandButton.ICON_UNDEFINED)
        .setSessionCommand(SPEED_COMMAND)
        .setDisplayName(speedLabel(speed))
        .setCustomIconResId(speedIconResId(speed))
        .setSlots(CommandButton.SLOT_OVERFLOW)
        .build()

// SLOT_BACK and SLOT_FORWARD map to the standard skip-back / skip-forward positions
// in the media notification and Android Auto player bar. 30-second increments are
// the podcast industry standard.
@OptIn(UnstableApi::class)
private fun createSeekBackButton(): CommandButton =
    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30)
        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
        .setDisplayName("Back 30s")
        .setSlots(CommandButton.SLOT_BACK)
        .build()

@OptIn(UnstableApi::class)
private fun createSeekForwardButton(): CommandButton =
    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
        .setDisplayName("Forward 30s")
        .setSlots(CommandButton.SLOT_FORWARD)
        .build()

// Returns the three media buttons shown at session start. Called from both
// PlayerService.onCreate() and PlayerLibrarySessionCallback.onConnect() so
// the current speed label is reflected immediately when a new controller connects.
private fun initialMediaButtonPreferences(speed: Float): ImmutableList<CommandButton> {
    return ImmutableList.of(
        createSpeedButton(speed),
        createSeekBackButton(),
        createSeekForwardButton()
    )
}

// Snapshot of Android Auto browse counts used by startLibraryRefreshObserver()
// to detect when the DB has changed and notifyChildrenChanged() should be called.
// Comparing snapshots avoids spamming Android Auto with unnecessary notifications.
private data class BrowseSnapshot(
    val isOffline: Boolean,
    val rootCount: Int,
    val continueCount: Int,
    val recentCount: Int,
    val podcastsCount: Int,
    val downloadsCount: Int
)

@OptIn(UnstableApi::class)
class PlayerService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressJob: Job? = null
    private var connectivityModeJob: Job? = null
    private var libraryRefreshJob: Job? = null
    @Volatile
    private var latestPlaybackSpeed: Float = 1.0f
    // The server's auto-played threshold (fraction). Fetched once; the
    // constant is only the fallback for a server that can't be asked.
    @Volatile
    private var playedThresholdPct: Float = PLAYED_THRESHOLD_PCT
    // When the player last moved to another item. The progress loop holds off
    // writing a near-zero position right after a transition, so the saved
    // position of the new item is still there for the resume seek.
    @Volatile
    private var lastTransitionMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlayerService.onCreate start")
        try {
            // Kick off lazy ApiClient init in the background so it's ready before
            // the first playback request or browse query arrives.
            serviceScope.launch {
                ensureApiClientInitializedFromStorage(this@PlayerService)
                if (CastCharmApp.apiClient.isInitialized && !CastCharmApp.isOfflineMode) {
                    runCatching { CastCharmApp.apiClient.getApi().getSettings().auto_played_threshold }
                        .onSuccess { if (it in 50..100) playedThresholdPct = it / 100f }
                }
            }

            // Build a dedicated OkHttpClient for streaming. The interceptor calls
            // ensureApiClientInitializedBlocking() to handle the post-reboot case
            // where the service starts before the ApiClient is initialized.
            // readTimeout(0) disables the read deadline — required for streaming
            // large audio files without a mid-stream timeout.
            //
            // Uses the process-wide PersistentCookieJar singleton so the same
            // in-memory cookie map is shared with ApiClient. A login that
            // completes after this service starts is visible to the very next
            // streaming request with no reload required.
            val streamingClient = OkHttpClient.Builder()
                .cookieJar(com.castcharm.android.data.api.PersistentCookieJar.getInstance(this@PlayerService))
                .addInterceptor(ApiKeyInterceptor(this@PlayerService))
                .addInterceptor { chain ->
                    ensureApiClientInitializedBlocking(this@PlayerService)
                    // Fail loudly if init failed — better than proceeding without auth
                    // and getting a mysterious 401 that ExoPlayer surfaces as a generic error.
                    if (!CastCharmApp.apiClient.isInitialized) {
                        throw IOException("Cannot stream: server connection unavailable")
                    }
                    val response = chain.proceed(chain.request())
                    // 401 ONLY — see the matching note in SessionStateInterceptor.
                    // /stream returns 403 when an episode's stored path no longer sits
                    // under the download directory, so treating 403 as an expired
                    // session logged the user out for merely pressing play on one bad
                    // episode.
                    if (response.code == 401) {
                        // Mirror SessionStateInterceptor: drive the auth state machine
                        // so the UI transitions to the login screen on session expiry,
                        // just as it does for regular API calls.
                        CastCharmApp.reportAuthInvalid()
                    }
                    response
                }
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            // Wrap the OkHttpClient in a Media3 data source factory so ExoPlayer
            // uses our authenticated client for all network media requests.
            val dataSourceFactory = DefaultDataSource.Factory(
                this,
                OkHttpDataSource.Factory(streamingClient).setUserAgent("CastCharm/1.0")
            )

            // AUDIO_CONTENT_TYPE_SPEECH activates Android's audio routing for podcasts
            // (e.g., Bluetooth SCO vs. A2DP profile selection).
            // handleAudioBecomingNoisy=true pauses playback when headphones are unplugged.
            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                .setSeekForwardIncrementMs(30_000)
                .setSeekBackIncrementMs(30_000)
                .build()
            Log.d(TAG, "ExoPlayer built OK")

            // Apply the current skip-silence preference to the freshly-built
            // player, then keep it in sync as the user toggles the setting.
            serviceScope.launch {
                this@PlayerService.dataStore.data
                    .map { it[SKIP_SILENCE_KEY] ?: false }
                    .collectLatest { enabled ->
                        withContext(Dispatchers.Main) {
                            if (::player.isInitialized) {
                                player.skipSilenceEnabled = enabled
                            }
                        }
                    }
            }

            latestPlaybackSpeed = player.playbackParameters.speed

            val callback = PlayerLibrarySessionCallback(
                context = this,
                feedDao = db.feedDao(),
                episodeDao = db.episodeDao(),
                player = player,
                scope = serviceScope,
                sessionProvider = { mediaSession },
                // Fired from onGetLibraryRoot so the head-unit browse tree
                // always kicks a fresh server pull. Debounced inside
                // refreshFromServerForBrowse so repeated browses in the same
                // session don't cause a request storm.
                onBrowseRoot = {
                    serviceScope.launch { refreshFromServerForBrowse("browse_root") }
                },
            )
            callback.updateLatestPlaybackSpeed(latestPlaybackSpeed)

            player.addListener(object : Player.Listener {
                // Start/stop the 1-second progress sync loop in sync with play/pause.
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) startProgressTracking() else stopProgressTracking()
                }

                // Apply the per-feed speed preference whenever the playing episode changes.
                // The speed is embedded in castcharm_feed_speed by resolveMediaItem() so we
                // don't need an extra DB round-trip here. The guard prevents a spurious
                // onPlaybackParametersChanged (and its metadata refresh) when speed is unchanged.
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    lastTransitionMs = System.currentTimeMillis()
                    val transitionedId = mediaItem?.mediaId
                    val epId = transitionedId?.removePrefix("episode_")?.toIntOrNull()
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                        // Fresh set: the speed baked into the item at resolve time
                        // is current, and the start position was chosen already.
                        val feedSpeed = mediaItem?.mediaMetadata?.extras
                            ?.getFloat("castcharm_feed_speed", 1f) ?: 1f
                        if (player.playbackParameters.speed != feedSpeed) {
                            player.setPlaybackParameters(PlaybackParameters(feedSpeed))
                        }
                        return
                    }
                    // A move within the queue (AUTO / SEEK / REPEAT).
                    if (epId == null) return
                    serviceScope.launch {
                        val ep = db.episodeDao().getEpisodeOnce(epId) ?: return@launch
                        // Speed: the feed row is what the user last chose (both the
                        // phone and the car write it there), not the value baked
                        // into the item when the queue was built.
                        val feedSpeed = db.feedDao().getFeedOnce(ep.feed_id)?.playback_speed ?: 1f
                        val resumeMs = if (!ep.played && ep.play_position_seconds > 0)
                            maxOf(0L, ep.play_position_seconds * 1000L - 5000L) else -1L
                        withContext(Dispatchers.Main) {
                            if (player.currentMediaItem?.mediaId != transitionedId) return@withContext
                            if (player.playbackParameters.speed != feedSpeed) {
                                player.setPlaybackParameters(PlaybackParameters(feedSpeed))
                            }
                            if (resumeMs >= 0) player.seekTo(resumeMs)
                        }
                        // Android Auto's player screen wants embedded artwork for
                        // the item that is playing; queued items carry only a URI.
                        embedArtworkForCurrent(ep, transitionedId)
                        // Keep the server's pointer on this episode so Next/Previous
                        // elsewhere, and a later "Continue", start from here.
                        callback.activeContext?.let { ctx ->
                            if (CastCharmApp.apiClient.isInitialized && !CastCharmApp.isOfflineMode) {
                                runCatching {
                                    CastCharmApp.apiClient.getApi().updatePlayerState(ctx.copy(episode_id = epId))
                                }
                            }
                        }
                    }
                }

                // ExoPlayer reports STATE_ENDED only when the whole timeline ends.
                // An item that finished and rolled into the next one shows up here
                // instead, so this is where queued episodes get marked played.
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
                    val finishedId = oldPosition.mediaItem?.mediaId?.removePrefix("episode_")?.toIntOrNull() ?: return
                    serviceScope.launch { markPlayed(finishedId) }
                }

                // When the episode finishes naturally, mark it played immediately
                // rather than waiting for the next progress sync cycle.
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        val episodeId = currentEpisodeId()
                        serviceScope.launch { episodeId?.let { markPlayed(it) } }
                    }
                }

                // Speed changes need to be reflected in the now-playing metadata subtitle
                // and the speed button label. Both are updated here synchronously.
                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    val speed = playbackParameters.speed
                    latestPlaybackSpeed = speed
                    callback.updateLatestPlaybackSpeed(speed)

                    mediaSession?.setMediaButtonPreferences(initialMediaButtonPreferences(speed))

                    serviceScope.launch {
                        refreshNowPlayingSpeedMetadata(speed)
                    }
                }
            })

            // Tapping the notification opens MainActivity rather than a specific
            // episode screen — the player state is available via the global PlayerController.
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            mediaSession = MediaLibrarySession.Builder(this, player, callback)
                .setSessionActivity(pendingIntent)
                .setMediaButtonPreferences(initialMediaButtonPreferences(latestPlaybackSpeed))
                .build()
            Log.d(TAG, "MediaLibrarySession built OK, session=$mediaSession")

            // Notify Android Auto of the initial library state on startup.
            serviceScope.launch {
                callback.notifyLibraryChanged()
                notifyBrowseSectionsChanged()
            }

            // Re-notify whenever the connectivity mode changes (online ↔ offline)
            // so Android Auto's browse tree updates to show/hide network sections.
            connectivityModeJob = serviceScope.launch {
                CastCharmApp.connectivityMode.collectLatest { mode ->
                    Log.d(TAG, "AA connectivity mode changed: $mode")
                    callback.notifyLibraryChanged()
                    notifyBrowseSectionsChanged()
                }
            }

            // Stop playback and cancel in-flight progress syncs whenever the session
            // is invalidated (logout, session expiry, or server-side auth rejection).
            // Without this, the progress loop keeps firing API calls with a dead session
            // and the player stays running even though the user has been logged out.
            serviceScope.launch {
                CastCharmApp.authState.collect { state ->
                    if (state is AppAuthState.NotLoggedIn) {
                        stopProgressTracking()
                        withContext(Dispatchers.Main) {
                            if (::player.isInitialized) {
                                player.stop()
                                player.clearMediaItems()
                            }
                        }
                    }
                }
            }

            // Start the polling loop that detects DB content changes and pushes
            // notifyChildrenChanged() updates to Android Auto.
            startLibraryRefreshObserver()

            // Warm the local DB from the server on service startup. Covers the
            // common Android Auto scenario: the app hasn't been touched in days,
            // the process was killed, the user plugs into the car and expects
            // to see current episodes. Without this the head unit reads stale
            // rows and only refreshes once the phone app is reopened.
            serviceScope.launch { refreshFromServerForBrowse("service_create") }
        } catch (e: Exception) {
            Log.e(TAG, "PlayerService.onCreate FAILED — session will be null", e)
        }
    }

    // Timestamp of the last successful (or attempted) server refresh triggered
    // by an Android Auto browse. Guards refreshFromServerForBrowse() so multiple
    // browse requests in quick succession don't hammer the server. The user
    // won't notice a 30-second staleness while they're still opening the browse
    // tree, but they will notice if we make the app hang on every screen.
    @Volatile
    private var lastBrowseRefreshMs: Long = 0L
    private val browseRefreshMinIntervalMs: Long = 30_000L

    /**
     * Pull feeds, continue-listening, and recent-episodes down from the server
     * into the local DB. Called on service create and on each library-root
     * browse from Android Auto — the two moments when a cold app is about to
     * show stale content to the user via the head unit.
     *
     * Once the DB is updated, the existing snapshot poller in
     * startLibraryRefreshObserver() sees the change and calls
     * notifyChildrenChanged, so Android Auto re-reads and shows the fresh data
     * without any extra plumbing here.
     */
    private suspend fun refreshFromServerForBrowse(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastBrowseRefreshMs < browseRefreshMinIntervalMs) {
            Log.d(TAG, "AA browse refresh skipped (last ran ${now - lastBrowseRefreshMs}ms ago, reason=$reason)")
            return
        }
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) {
            Log.d(TAG, "AA browse refresh skipped (offline/uninitialised, reason=$reason)")
            return
        }
        lastBrowseRefreshMs = now
        Log.d(TAG, "AA browse refresh starting (reason=$reason)")

        val api = CastCharmApp.apiClient.getApi()
        val feedRepo = FeedRepository(api, db.feedDao())
        val episodeRepo = EpisodeRepository(api, db.episodeDao())

        // Feeds first — subsequent per-feed data depends on knowing which
        // feeds exist. Each step is isolated in a runCatching so one failure
        // doesn't stop the others; partial freshness is better than no freshness.
        runCatching { feedRepo.refreshFeeds() }
            .onFailure { Log.w(TAG, "AA browse refresh: feeds failed", it) }

        runCatching { episodeRepo.fetchAndCacheContinueListening() }
            .onFailure { Log.w(TAG, "AA browse refresh: continue-listening failed", it) }

        // Recent episodes across every feed. mergeFromApi preserves phone-only
        // fields (local_path, download progress, pending-sync flags) so this
        // never clobbers a download in flight.
        runCatching {
            val recent = api.getAllEpisodes(limit = 100, offset = 0, order = "desc")
            val phoneDownloadIds = db.downloadDao().getAllDownloadsOnceOrdered()
                .map { it.episode_id }
                .toSet()
            val entities = recent.map { remote ->
                remote.toEntity(
                    existing = db.episodeDao().getEpisodeOnce(remote.id),
                    hasActivePhoneDownload = remote.id in phoneDownloadIds,
                )
            }
            db.episodeDao().mergeFromApi(
                episodes = entities,
                activePhoneDownloadEpisodeIds = phoneDownloadIds.intersect(recent.map { it.id }.toSet()),
            )
        }.onFailure { Log.w(TAG, "AA browse refresh: recent episodes failed", it) }

        Log.d(TAG, "AA browse refresh completed (reason=$reason)")
    }

    // Polls the DB every 1.5 seconds to detect content changes (new episodes
    // downloaded, feed updated, played state changed) and pushes notifyChildrenChanged()
    // to Android Auto. The snapshot comparison prevents unnecessary notifications
    // when nothing has changed — Android Auto rate-limits requests from the system.
    private fun startLibraryRefreshObserver() {
        libraryRefreshJob?.cancel()
        libraryRefreshJob = serviceScope.launch {
            var lastSnapshot: BrowseSnapshot? = null

            while (isActive) {
                try {
                    val snapshot = readBrowseSnapshot()
                    if (snapshot != lastSnapshot) {
                        Log.d(TAG, "AA browse snapshot changed: $snapshot")
                        notifyBrowseSectionsChanged()
                        lastSnapshot = snapshot
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AA browse snapshot poll failed", e)
                }

                delay(1500)
            }
        }
    }

    private suspend fun readBrowseSnapshot(): BrowseSnapshot {
        val isOffline = CastCharmApp.isOfflineMode
        val rootCount = visibleRootSections(isOffline).size
        val continueCount = if (isOffline) 0 else db.episodeDao().getContinueListening(limit = 20).first().size
        val recentCount = if (isOffline) 0 else db.episodeDao().getRecentEpisodesOnce(limit = 200).size
        val podcastsCount = if (isOffline) 0 else db.feedDao().getFeedOnceAll().size
        val downloadsCount = db.episodeDao().getDownloadedEpisodesOnce().size

        return BrowseSnapshot(
            isOffline = isOffline,
            rootCount = rootCount,
            continueCount = continueCount,
            recentCount = recentCount,
            podcastsCount = podcastsCount,
            downloadsCount = downloadsCount
        )
    }

    // Updates the subtitle field of the current MediaItem to reflect the new
    // playback speed. This is what Android Auto and Bluetooth headsets display
    // as the now-playing subtitle (e.g., "Feed Name • 1.5×").
    // Must run on Main thread because player.replaceMediaItem() is not thread-safe.
    // Early-exits if the subtitle is already correct to avoid unnecessary updates.
    private suspend fun refreshNowPlayingSpeedMetadata(speed: Float) {
        withContext(Dispatchers.Main) {
            if (!::player.isInitialized) return@withContext

            val currentIndex = player.currentMediaItemIndex
            val currentItem = player.currentMediaItem ?: return@withContext
            if (currentIndex < 0) return@withContext

            val currentMetadata = currentItem.mediaMetadata
            val baseFeedTitle = extractBaseFeedTitle(currentMetadata)
            val desiredSubtitle = buildNowPlayingSubtitle(baseFeedTitle, speed)

            // No-op guard: avoid replacing the MediaItem if the subtitle is already correct.
            if (currentMetadata.subtitle?.toString() == desiredSubtitle) {
                return@withContext
            }

            val updatedExtras = Bundle(currentMetadata.extras ?: Bundle()).apply {
                putString("castcharm_feed_title", baseFeedTitle)
                putString("castcharm_speed_label", speedLabel(speed))
            }

            val updatedMetadata = currentMetadata.buildUpon()
                .setArtist(baseFeedTitle)
                .setSubtitle(desiredSubtitle)
                .setExtras(updatedExtras)
                .build()

            val updatedItem = currentItem.buildUpon()
                .setMediaMetadata(updatedMetadata)
                .build()

            // replaceMediaItem() swaps the metadata in-place without interrupting
            // playback (unlike setMediaItem() which resets player state).
            player.replaceMediaItem(currentIndex, updatedItem)
        }
    }

    // Runs while the player is actively playing. Ticks every 1 second but only
    // syncs to the server every 10 seconds (lastSyncMs gate) to avoid hammering
    // the API with progress updates on every tick.
    //
    // Write pattern mirrors EpisodeRepository.updateProgress():
    //   - Online: write DB (pending=false) + call API; if API fails, write DB (pending=true)
    //   - Offline: write DB (pending=true) only; SyncWorker flushes later
    //
    // Also checks the played threshold on each sync cycle so auto-mark-played
    // works even if the user pauses right at 98% and never hits STATE_ENDED.
    @Volatile
    private var lastContinueNotifyMs: Long = 0L

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            var lastSyncMs = 0L
            while (isActive) {
                delay(1000)
                // Player state must be read on Main thread.
                val (episodeId, positionMs, durationMs) = withContext(Dispatchers.Main) {
                    Triple(currentEpisodeId(), player.currentPosition, player.duration)
                }
                episodeId ?: continue

                val positionSeconds = (positionMs / 1000L).toInt()
                val durationSeconds = durationMs.takeIf { it > 0L }?.div(1000L)?.toInt()
                val now = System.currentTimeMillis()

                // Right after a transition the position is ~0 while the resume
                // seek is still being looked up; writing it would erase the
                // saved position the seek is about to use.
                if (positionSeconds < 2 && now - lastTransitionMs < 3_000L) continue

                // 10-second server sync gate — save battery and server load.
                if (now - lastSyncMs >= 10_000L) {
                    try {
                        val existing = db.episodeDao().getEpisodeOnce(episodeId)
                        // This loop only ever promotes unplayed → played. Un-marking
                        // is a deliberate user action (and resets the position to 0),
                        // never something to infer from a scrub backwards.
                        val targetPlayed = (existing?.played == true) || EpisodeRepository.derivePlayedState(
                            positionSeconds = positionSeconds,
                            // durationSeconds from the live player is more accurate than
                            // the cached DB value (which may be from RSS metadata).
                            durationSeconds = durationSeconds ?: existing?.duration,
                            currentPlayed = false,
                            thresholdPct = playedThresholdPct
                        )

                        // Write progress to DB. pending flag is true only if we can't
                        // reach the server right now; SyncWorker will flush it later.
                        db.episodeDao().updateProgress(
                            episodeId,
                            positionSeconds,
                            now,
                            pending = CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized
                        )

                        if (CastCharmApp.apiClient.isInitialized && !CastCharmApp.isOfflineMode) {
                            CastCharmApp.apiClient.getApi()
                                .updateProgress(episodeId, ProgressRequest(positionSeconds))
                        }

                        // Auto-mark played if the threshold was crossed mid-playback.
                        if (existing != null && existing.played != targetPlayed) {
                            if (CastCharmApp.apiClient.isInitialized && !CastCharmApp.isOfflineMode) {
                                CastCharmApp.apiClient.getApi().setPlayed(episodeId, PlayedRequest(targetPlayed))
                                db.episodeDao().updatePlayedStatus(episodeId, targetPlayed, now, pending = false)
                            } else {
                                db.episodeDao().updatePlayedStatus(episodeId, targetPlayed, now, pending = true)
                            }
                        }

                        lastSyncMs = now
                        // Android Auto re-reads every section it is told about, and
                        // for a feed list that can mean a full server fetch — so
                        // tell it only when the played state changed, or once a
                        // minute for the "Continue" position.
                        val playedChanged = existing != null && existing.played != targetPlayed
                        if (playedChanged || now - lastContinueNotifyMs >= 60_000L) {
                            lastContinueNotifyMs = now
                            notifyBrowseSectionsChanged(episodeId)
                        }
                    } catch (_: Exception) {
                        // Server call failed — write to DB with pending=true so
                        // SyncWorker can flush the progress on the next cycle.
                        val existing = db.episodeDao().getEpisodeOnce(episodeId)
                        val targetPlayed = (existing?.played == true) || EpisodeRepository.derivePlayedState(
                            positionSeconds = positionSeconds,
                            durationSeconds = durationSeconds ?: existing?.duration,
                            currentPlayed = false,
                            thresholdPct = playedThresholdPct
                        )

                        db.episodeDao().updateProgress(episodeId, positionSeconds, now, pending = true)
                        if (existing != null && existing.played != targetPlayed) {
                            db.episodeDao().updatePlayedStatus(episodeId, targetPlayed, now, pending = true)
                        }
                    }
                }
            }
        }
    }

    // Swap embedded artwork into the item that just started playing (queued
    // items are resolved with a URI only, to keep queue building fast).
    private suspend fun embedArtworkForCurrent(episode: EpisodeEntity, mediaId: String?) {
        val bytes = artworkBytesFor(this, episode) ?: return
        withContext(Dispatchers.Main) {
            val idx = player.currentMediaItemIndex
            val current = player.currentMediaItem ?: return@withContext
            if (current.mediaId != mediaId || current.mediaMetadata.artworkData != null) return@withContext
            val md = current.mediaMetadata.buildUpon()
                .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()
            player.replaceMediaItem(idx, current.buildUpon().setMediaMetadata(md).build())
        }
    }

    private suspend fun markPlayed(episodeId: Int) {
        val now = System.currentTimeMillis()
        try {
            db.episodeDao().updatePlayedStatus(episodeId, true, now)
            if (CastCharmApp.apiClient.isInitialized && !CastCharmApp.isOfflineMode) {
                CastCharmApp.apiClient.getApi().setPlayed(episodeId, PlayedRequest(true))
            } else {
                db.episodeDao().updatePlayedStatus(episodeId, true, now, pending = true)
            }
            notifyBrowseSectionsChanged(episodeId)
        } catch (_: Exception) {
            db.episodeDao().updatePlayedStatus(episodeId, true, now, pending = true)
        }
    }

    private suspend fun notifyBrowseSectionsChanged(episodeId: Int? = null) {
        val session = mediaSession ?: return
        val isOffline = CastCharmApp.isOfflineMode
        val rootSections = visibleRootSections(isOffline)
        val rootCount = rootSections.size
        val feedCount = if (isOffline) 0 else db.feedDao().getFeedOnceAll().size
        val continueCount = if (isOffline) 0 else db.episodeDao().getContinueListening(limit = 20).first().size
        val recentCount = if (isOffline) 0 else db.episodeDao().getRecentEpisodesOnce(limit = 200).size
        val downloadCount = db.episodeDao().getDownloadedEpisodesOnce().size

        session.notifyChildrenChanged(ROOT_ID, rootCount, browseLibraryParams())
        session.notifyChildrenChanged(SECTION_DOWNLOADS, downloadCount, playableLibraryParams())

        if (!isOffline) {
            session.notifyChildrenChanged(SECTION_CONTINUE, continueCount, playableLibraryParams())
            session.notifyChildrenChanged(SECTION_RECENT, recentCount, playableLibraryParams())
            session.notifyChildrenChanged(SECTION_PODCASTS, feedCount, browsableLibraryParams())

            if (episodeId != null) {
                val episode = db.episodeDao().getEpisodeOnce(episodeId)
                val feedId = episode?.feed_id
                if (feedId != null) {
                    val feedEpisodeCount = db.episodeDao().getEpisodesByFeedOnce(feedId).size
                    session.notifyChildrenChanged(feedMediaId(feedId), feedEpisodeCount, playableLibraryParams())
                }
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
    }

    private fun currentEpisodeId(): Int? =
        player.currentMediaItem?.mediaId?.removePrefix("episode_")?.toIntOrNull()

    override fun onDestroy() {
        stopProgressTracking()
        connectivityModeJob?.cancel()
        libraryRefreshJob?.cancel()
        mediaSession?.release()
        mediaSession = null
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        Log.d(TAG, "onGetSession: pkg=${controllerInfo.packageName}")
        return mediaSession
    }
}

@OptIn(UnstableApi::class)
private class PlayerLibrarySessionCallback(
    private val context: Context,
    private val feedDao: FeedDao,
    private val episodeDao: EpisodeDao,
    private val player: Player,
    private val scope: CoroutineScope,
    private val sessionProvider: () -> MediaLibrarySession?,
    // Invoked whenever a browser asks for the library root — the "user just
    // opened the browse tree" signal. Used to pull fresh data from the server
    // so the head unit doesn't render a snapshot from days ago.
    private val onBrowseRoot: () -> Unit = {},
) : MediaLibraryService.MediaLibrarySession.Callback {

    private val connectedControllers = linkedSetOf<MediaSession.ControllerInfo>()

    // The server context the current queue was built from, so the service can
    // keep the server's pointer in step with local transitions.
    // null = the queue is local-only (offline) or a plain single item.
    @Volatile
    var activeContext: PlayerPlayRequest? = null

    @Volatile
    private var latestPlaybackSpeed: Float = 1.0f

    fun updateLatestPlaybackSpeed(speed: Float) {
        latestPlaybackSpeed = speed
    }

    private fun <T> asyncFuture(fallback: T, block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch {
            try {
                future.set(block())
            } catch (e: Exception) {
                Log.e(TAG, "asyncFuture error", e)
                if (!future.isDone) future.set(fallback)
            }
        }.invokeOnCompletion {
            if (!future.isDone) future.set(fallback)
        }
        return future
    }

    suspend fun notifyLibraryChanged() {
        val session = sessionProvider() ?: return
        val isOffline = CastCharmApp.isOfflineMode
        val rootCount = visibleRootSections(isOffline).size
        val feedCount = if (isOffline) 0 else feedDao.getFeedOnceAll().size
        val continueCount = if (isOffline) 0 else episodeDao.getContinueListening(limit = 20).first().size
        val recentCount = if (isOffline) 0 else episodeDao.getRecentEpisodesOnce(limit = 200).size
        val downloadCount = episodeDao.getDownloadedEpisodesOnce().size

        session.notifyChildrenChanged(ROOT_ID, rootCount, browseLibraryParams())
        session.notifyChildrenChanged(SECTION_DOWNLOADS, downloadCount, playableLibraryParams())

        if (!isOffline) {
            session.notifyChildrenChanged(SECTION_CONTINUE, continueCount, playableLibraryParams())
            session.notifyChildrenChanged(SECTION_RECENT, recentCount, playableLibraryParams())
            session.notifyChildrenChanged(SECTION_PODCASTS, feedCount, browsableLibraryParams())
        }
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        Log.d(TAG, "onConnect: pkg=${controller.packageName}")

        val base = super.onConnect(session, controller)
        if (!base.isAccepted) return base

        connectedControllers.add(controller)

        val sessionCommands = base.availableSessionCommands
            .buildUpon()
            .add(SPEED_COMMAND)
            .build()

        // Next/previous episode stay available: Media3 only enables them when
        // the timeline actually has a neighbour, so a single episode looks as
        // it always did while a listen-in-order queue gets skip buttons.
        val playerCommands = base.availablePlayerCommands

        val buttons = initialMediaButtonPreferences(latestPlaybackSpeed)

        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(playerCommands)
            .setMediaButtonPreferences(buttons)
            .setCustomLayout(buttons)
            .build()
    }

    override fun onDisconnected(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ) {
        connectedControllers.remove(controller)
        super.onDisconnected(session, controller)
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction == SPEED_COMMAND.customAction) {
            val current = latestPlaybackSpeed
            val idx = SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
                .takeIf { it >= 0 } ?: 1
            val next = SPEEDS[(idx + 1) % SPEEDS.size]

            latestPlaybackSpeed = next
            player.setPlaybackSpeed(next)

            // Push the updated button (with the new icon) to all connected controllers
            // so Android Auto refreshes the speed icon immediately.
            val newButtons = initialMediaButtonPreferences(next)
            session.setMediaButtonPreferences(newButtons)

            // Capture the mediaId on the calling (main) thread — Player must not be
            // accessed from the IO dispatcher the coroutine below runs on.
            val episodeId = player.currentMediaItem?.mediaId
                ?.removePrefix("episode_")?.toIntOrNull()

            // Persist the speed change for this feed so it survives session restarts.
            scope.launch {
                val feedId = episodeId?.let { episodeDao.getEpisodeOnce(it)?.feed_id }
                if (feedId != null) {
                    feedDao.updatePlaybackSpeed(feedId, next)
                }
            }

            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        return super.onCustomCommand(session, controller, customCommand, args)
    }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> =
        asyncFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)) {
            val accepted = when {
                parentId == ROOT_ID -> true
                parentId == SECTION_CONTINUE -> true
                parentId == SECTION_RECENT -> true
                parentId == SECTION_DOWNLOADS -> true
                parentId == SECTION_PODCASTS -> true
                parentId.startsWith(CATCHUP_PREFIX) -> true
                parentId.startsWith("feed_") -> {
                    val id = parentId.removePrefix("feed_").toIntOrNull()
                    id != null && feedDao.getFeedOnce(id) != null
                }
                else -> false
            }

            Log.d(TAG, "onSubscribe pkg=${browser.packageName} parent=$parentId accepted=$accepted")
            if (accepted) {
                LibraryResult.ofVoid()
            } else {
                Log.e(TAG, "onSubscribe REJECTED pkg=${browser.packageName} parent=$parentId")
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }

    @OptIn(UnstableApi::class)
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // Fire-and-forget: fetch fresh data from the server in the background
        // so the head unit's browse tree updates as soon as the DB does. The
        // return path below still uses whatever's currently in the DB so this
        // request stays fast (Android Auto has a strict timeout).
        onBrowseRoot()
        val root = rootItem(context)
        val extras = root.mediaMetadata.extras ?: Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )

        Log.d(TAG, "onGetLibraryRoot pkg=${browser.packageName} extras=$extras")
        return Futures.immediateFuture(LibraryResult.ofItem(root, browseLibraryParams()))
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> =
        asyncFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)) {
            val item = when {
                mediaId == ROOT_ID -> rootItem(context)
                mediaId == SECTION_CONTINUE -> section(context, SECTION_CONTINUE, "Continue")
                mediaId == SECTION_RECENT -> section(context, SECTION_RECENT, "Recent")
                mediaId == SECTION_DOWNLOADS -> section(context, SECTION_DOWNLOADS, "Downloads")
                mediaId == SECTION_PODCASTS -> section(context, SECTION_PODCASTS, "Podcasts")
                mediaId.startsWith(CATCHUP_PREFIX) -> {
                    val feedId = mediaId.removePrefix(CATCHUP_PREFIX).toIntOrNull()
                        ?: return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    val feed = feedDao.getFeedOnce(feedId)
                        ?: return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    createCatchUpItem(feed)
                }
                mediaId.startsWith("feed_") -> {
                    val feedId = mediaId.removePrefix("feed_").toIntOrNull()
                        ?: return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    val feed = feedDao.getFeedOnce(feedId)
                        ?: return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    createFeedItem(feed)
                }
                mediaId.startsWith("episode_") -> {
                    val epId = mediaId.removePrefix("episode_").toIntOrNull()
                        ?: return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    val ep = episodeDao.getEpisodeOnce(epId)
                        ?: return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    val feedTitle = feedDao.getFeedOnce(ep.feed_id)?.title
                    createEpisodeItem(ep, feedTitle)
                }
                else -> return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }

            Log.d(
                TAG,
                "item pkg=${browser.packageName} id=$mediaId browsable=${item.mediaMetadata.isBrowsable} playable=${item.mediaMetadata.isPlayable} type=${item.mediaMetadata.mediaType} artwork=${item.mediaMetadata.artworkUri?.scheme}"
            )
            LibraryResult.ofItem(item, null)
        }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        asyncFuture(LibraryResult.ofItemList(ImmutableList.of(), params)) {
            if (isOfflineHiddenSection(parentId)) {
                Log.d(TAG, "Returning empty AA section while offline parent=$parentId")
                return@asyncFuture LibraryResult.ofItemList(ImmutableList.of(), params)
            }

            val allItems: List<MediaItem>
            val resultParams: MediaLibraryService.LibraryParams

            when {
                parentId == ROOT_ID -> {
                    allItems = visibleRootSections(CastCharmApp.isOfflineMode).map { sectionId ->
                        when (sectionId) {
                            SECTION_CONTINUE -> section(context, SECTION_CONTINUE, "Continue")
                            SECTION_RECENT -> section(context, SECTION_RECENT, "Recent")
                            SECTION_PODCASTS -> section(context, SECTION_PODCASTS, "Podcasts")
                            SECTION_DOWNLOADS -> section(context, SECTION_DOWNLOADS, "Downloads")
                            else -> section(context, SECTION_DOWNLOADS, "Downloads")
                        }
                    }
                    resultParams = browseLibraryParams()
                }

                parentId == SECTION_PODCASTS -> {
                    val feeds = feedDao.getFeedOnceAll()
                    allItems = feeds.map(::createFeedItem)
                    resultParams = browsableLibraryParams()

                    scope.launch {
                        feeds.take(12).forEach { feed ->
                            try {
                                PodcastArtworkProvider.prefetchFeedArtwork(context, feed.id)
                            } catch (e: Exception) {
                                Log.w(TAG, "Feed artwork prefetch failed for ${feed.id}", e)
                            }
                        }
                    }
                }

                parentId.startsWith("feed_") -> {
                    val feedId = parentId.removePrefix("feed_").toIntOrNull()
                    Log.d(TAG, "[onGetChildren] feedId=$feedId parentId=$parentId page=$page pageSize=$pageSize")
                    if (feedId == null) return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)

                    val feed = feedDao.getFeedOnce(feedId)
                    if (feed == null) return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)

                    scope.launch { PodcastArtworkProvider.prefetchFeedArtwork(context, feedId) }

                    val existingCount = episodeDao.getEpisodesForAndroidAutoByFeed(feedId).size
                    val expectedCount = feed.episode_count
                    val shouldRefreshAll = page <= 0 && (expectedCount <= 0 || existingCount < expectedCount)

                    if (!CastCharmApp.isOfflineMode && CastCharmApp.apiClient.isInitialized && shouldRefreshAll) {
                        try {
                            val api = CastCharmApp.apiClient.getApi()
                            val batchSize = 200
                            var offset = 0
                            var fetchedTotal = 0
                            while (true) {
                                val remoteEpisodes = api.getEpisodes(
                                    feedId = feedId,
                                    limit = batchSize,
                                    offset = offset,
                                    includeHidden = false,
                                    order = "desc"
                                )
                                Log.d(TAG, "AA FEED FETCH feed=$feedId offset=$offset apiCount=${remoteEpisodes.size}")
                                if (remoteEpisodes.isEmpty()) break

                                episodeDao.mergeFromApi(remoteEpisodes.map { it.toEntity(null) })
                                fetchedTotal += remoteEpisodes.size

                                if (remoteEpisodes.size < batchSize) break
                                if (expectedCount > 0 && fetchedTotal >= expectedCount) break
                                if (fetchedTotal >= 5000) break
                                offset += remoteEpisodes.size
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "AA: Failed to fetch episodes on-demand for $feedId", e)
                        }
                    }

                    allItems = if (feed.listenInOrder) {
                        // A story reads top to bottom: oldest first, with one
                        // "Continue" row above it that plays the whole queue.
                        listOf(createCatchUpItem(feed)) + episodeDao
                            .getEpisodesForAndroidAutoByFeedOldestFirst(feedId)
                            .map { ep -> createEpisodeItem(ep, feed.title) }
                    } else {
                        episodeDao
                            .getEpisodesForAndroidAutoByFeed(feedId)
                            .map { ep -> createEpisodeItem(ep, feed.title) }
                    }
                    resultParams = playableLibraryParams()
                }

                parentId == SECTION_CONTINUE -> {
                    val episodes = episodeDao.getContinueListening(limit = 200).first()
                    val feedMap = feedDao.getFeedOnceAll().associateBy { it.id }
                    allItems = episodes.map { ep -> createEpisodeItem(ep, feedMap[ep.feed_id]?.title) }
                    resultParams = playableLibraryParams()
                }

                parentId == SECTION_RECENT -> {
                    val episodes = episodeDao.getRecentEpisodesOnce(limit = 200)
                    val feedMap = feedDao.getFeedOnceAll().associateBy { it.id }
                    allItems = episodes.map { ep -> createEpisodeItem(ep, feedMap[ep.feed_id]?.title) }
                    resultParams = playableLibraryParams()
                }

                parentId == SECTION_DOWNLOADS -> {
                    val episodes = episodeDao.getDownloadedEpisodesOnce()
                    val feedMap = feedDao.getFeedOnceAll().associateBy { it.id }
                    allItems = episodes.map { ep -> createEpisodeItem(ep, feedMap[ep.feed_id]?.title) }
                    resultParams = playableLibraryParams()
                }

                else -> {
                    Log.e(TAG, "[onGetChildren] Unknown parentId: $parentId")
                    return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                }
            }

            val pagedItems = paginate(allItems, page, pageSize)
            Log.d(
                TAG,
                "browse pkg=${browser.packageName} parent=$parentId page=$page size=$pageSize total=${allItems.size} returned=${pagedItems.size} ids=${pagedItems.take(10).map { it.mediaId }}"
            )
            LibraryResult.ofItemList(ImmutableList.copyOf(pagedItems), resultParams)
        }

    override fun onSetMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        asyncFuture(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)) {
            val safeRequestedIndex = startIndex.coerceIn(0, mediaItems.lastIndex.coerceAtLeast(0))
            val requestedItem = mediaItems.getOrNull(safeRequestedIndex)

            if (CastCharmApp.isOfflineMode && requestedItem != null && !canPlayOffline(requestedItem)) {
                Log.w(
                    TAG,
                    "Blocked offline playback for mediaId=${requestedItem.mediaId} controller=${controller.packageName}"
                )
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            } else {
                // One episode of a feed listened to in order becomes that feed's
                // queue from this episode onward, so the car, the headset and the
                // phone all keep going without any UI alive to advance them.
                val (itemsToResolve, indexToStart) =
                    expandInOrderQueue(mediaItems, safeRequestedIndex) ?: (mediaItems to startIndex)
                val startAt = indexToStart.coerceIn(0, itemsToResolve.lastIndex.coerceAtLeast(0))
                val resolved = kotlinx.coroutines.coroutineScope {
                    itemsToResolve.mapIndexed { i, it ->
                        async { resolveMediaItem(it, embedArtwork = i == startAt) }
                    }.awaitAll()
                }

                val safeStartIndex = indexToStart.coerceIn(0, resolved.lastIndex.coerceAtLeast(0))
                val selectedItem = resolved.getOrNull(safeStartIndex)

                val selectedEpisodeId = selectedItem
                    ?.mediaId
                    ?.removePrefix("episode_")
                    ?.toIntOrNull()

                val dbEpisode = selectedEpisodeId?.let { episodeDao.getEpisodeOnce(it) }
                val savedResumeMs = dbEpisode
                    ?.takeIf { !it.played }
                    ?.play_position_seconds
                    ?.takeIf { it > 0 }
                    ?.let { maxOf(0L, it * 1000L - 5000L) }
                    ?: 0L

                Log.d(TAG, "onSetMediaItems mediaId=${selectedItem?.mediaId} " +
                    "incomingStartMs=$startPositionMs savedResumeMs=$savedResumeMs " +
                    "dbPos=${dbEpisode?.play_position_seconds} played=${dbEpisode?.played} " +
                    "pkg=${controller.packageName}")

                val finalStartPositionMs = when {
                    startPositionMs > 0L -> startPositionMs
                    (startPositionMs == 0L || startPositionMs == C.TIME_UNSET) && savedResumeMs > 0L -> {
                        Log.d(
                            TAG,
                            "AA resume selectedIndex=$safeStartIndex mediaId=${selectedItem?.mediaId} " +
                                    "controller=${controller.packageName} savedResumeMs=$savedResumeMs " +
                                    "incomingStartPositionMs=$startPositionMs queueSize=${resolved.size}"
                        )
                        savedResumeMs
                    }
                    else -> {
                        if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
                    }
                }

                MediaSession.MediaItemsWithStartPosition(
                    resolved,
                    safeStartIndex,
                    finalStartPositionMs
                )
            }
        }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> =
        asyncFuture(emptyList()) {
            val playable = if (CastCharmApp.isOfflineMode) mediaItems.filter { canPlayOffline(it) } else mediaItems
            kotlinx.coroutines.coroutineScope {
                playable.map { async { resolveMediaItem(it, embedArtwork = false) } }.awaitAll()
            }
        }

    /**
     * The listen-in-order rule.  Given a single `episode_<id>` of a feed with
     * play_order == "oldest", or a `catchup_feed_<id>` row, return the queue to
     * load and the index to start at; null means "play exactly what was asked".
     *
     * Online, the server decides (it owns played state and the queue); offline
     * or on failure, the local table does.  The queue is capped after the start
     * episode, and any queued episode the phone has not cached yet is fetched so
     * resolveMediaItem can give it a URI.
     */
    private suspend fun expandInOrderQueue(items: List<MediaItem>, index: Int): Pair<List<MediaItem>, Int>? {
        if (items.size != 1) return null
        val original = items[0]
        val mediaId = original.mediaId
        val tappedId: Int?
        val feedId: Int
        if (mediaId.startsWith(CATCHUP_PREFIX)) {
            tappedId = null
            feedId = mediaId.removePrefix(CATCHUP_PREFIX).toIntOrNull() ?: return null
        } else {
            val epId = mediaId.removePrefix("episode_").toIntOrNull() ?: return null
            val ep = episodeDao.getEpisodeOnce(epId) ?: return null
            feedId = ep.feed_id
            tappedId = epId
        }
        val feed = feedDao.getFeedOnce(feedId) ?: return null
        val online = !CastCharmApp.isOfflineMode && CastCharmApp.apiClient.isInitialized
        if (!feed.listenInOrder) {
            // Not an in-order feed — but the server may have just been told to
            // play a playlist (or a feed) that starts with exactly this episode.
            // If so, load that queue so it advances without the phone UI too.
            return if (online && tappedId != null) expandFromServerContext(original, tappedId) else null
        }

        var ids: List<Int> = emptyList()
        var start = 0
        if (online) {
            try {
                val state = CastCharmApp.apiClient.getApi().playerPlay(
                    PlayerPlayRequest(context_type = "feed", context_id = feedId,
                                      episode_id = tappedId, context_filter = "unplayed")
                )
                activeContext = PlayerPlayRequest(context_type = "feed", context_id = feedId, context_filter = "unplayed")
                val queueIds = state.queue.map { it.id }
                val currentId = tappedId ?: state.current_episode?.id
                val pos = queueIds.indexOf(currentId)
                ids = when {
                    currentId == null -> queueIds
                    pos >= 0 -> queueIds
                    else -> listOf(currentId) + queueIds       // re-listening a played one
                }
                start = maxOf(0, ids.indexOf(currentId))
            } catch (e: Exception) {
                Log.w(TAG, "In-order queue from server failed; using local table", e)
            }
        }
        if (ids.isEmpty()) {
            activeContext = null
            val local = if (CastCharmApp.isOfflineMode) episodeDao.getInOrderQueueLocal(feedId)
                        else episodeDao.getInOrderQueue(feedId)
            val localIds = local.map { it.id }
            val startId = tappedId
                ?: episodeDao.getInProgressForFeed(feedId)?.id
                ?: localIds.firstOrNull()
                ?: return null
            ids = if (startId in localIds) localIds else listOf(startId) + localIds
            start = ids.indexOf(startId)
        }
        if (ids.isEmpty()) return null

        // Cap the tail; keep everything up to and including the start.
        val tailEnd = minOf(ids.size, start + 1 + IN_ORDER_QUEUE_LIMIT)
        val window = ids.subList(start, tailEnd)
        val windowStart = 0

        // Episodes the phone has never cached cannot be resolved to a URI.
        if (online) {
            val missing = window.filter { episodeDao.getEpisodeOnce(it) == null }
            if (missing.isNotEmpty()) {
                val repo = EpisodeRepository(CastCharmApp.apiClient.getApi(), episodeDao)
                for (id in missing) runCatching { repo.fetchEpisodeFromApi(id) }
            }
        }

        val built = window.mapIndexed { i, id ->
            if (i == windowStart && tappedId != null) original.buildUpon().setMediaId("episode_$id").build()
            else MediaItem.Builder().setMediaId("episode_$id").build()
        }
        Log.d(TAG, "In-order queue for feed $feedId: ${built.size} items starting at episode ${window[windowStart]}")
        return built to windowStart
    }

    /**
     * Playlist (and ordinary-feed) queues: the screen already called
     * /api/player/play, so the server's current episode is the one being
     * started.  Load the rest of that queue behind it.  A stale context whose
     * current episode is something else is ignored.
     */
    private suspend fun expandFromServerContext(original: MediaItem, tappedId: Int): Pair<List<MediaItem>, Int>? {
        val state = try {
            CastCharmApp.apiClient.getApi().getPlayerState()
        } catch (e: Exception) {
            return null
        }
        if (state.context_type == null || state.current_episode_id != tappedId) return null
        activeContext = PlayerPlayRequest(
            context_type = state.context_type, context_id = state.context_id ?: return null,
            context_filter = state.context_filter ?: "unplayed")
        val queueIds = state.queue.map { it.id }
        if (queueIds.size < 2) return null
        val pos = queueIds.indexOf(tappedId)
        val ids = if (pos >= 0) queueIds.subList(pos, queueIds.size) else listOf(tappedId) + queueIds
        val window = ids.take(1 + IN_ORDER_QUEUE_LIMIT)
        val missing = window.filter { episodeDao.getEpisodeOnce(it) == null }
        if (missing.isNotEmpty()) {
            val repo = EpisodeRepository(CastCharmApp.apiClient.getApi(), episodeDao)
            for (id in missing) runCatching { repo.fetchEpisodeFromApi(id) }
        }
        val built = window.mapIndexed { i, id ->
            if (i == 0) original.buildUpon().setMediaId("episode_$id").build()
            else MediaItem.Builder().setMediaId("episode_$id").build()
        }
        Log.d(TAG, "Queue from server context ${state.context_type}/${state.context_id}: ${built.size} items")
        return built to 0
    }

    /** "Continue · Ep. 12 · Title" (or "All caught up") for an in-order feed. */
    private suspend fun createCatchUpItem(feed: FeedEntity): MediaItem {
        val next = episodeDao.getInProgressForFeed(feed.id)
            ?: (if (CastCharmApp.isOfflineMode) episodeDao.getInOrderQueueLocal(feed.id)
                else episodeDao.getInOrderQueue(feed.id)).firstOrNull()
        val subtitle = if (next == null) "All caught up" else buildString {
            next.seq_number?.let { append("Ep. ").append(it).append(" \u00B7 ") }
            append(next.title ?: "Untitled")
            if (next.play_position_seconds >= 60) append(" \u00B7 ").append(next.play_position_seconds / 60).append(" min in")
        }
        val artworkUri = resolveFeedArtworkUri(context, feed)
        return MediaItem.Builder()
            .setMediaId(CATCHUP_PREFIX + feed.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(if (next == null) "All caught up" else "Continue")
                    .setDisplayTitle(if (next == null) "All caught up" else "Continue")
                    .setSubtitle(subtitle)
                    .setArtist(feed.title)
                    .setIsBrowsable(false)
                    .setIsPlayable(next != null)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                    .setArtworkUri(artworkUri)
                    .build()
            )
            .build()
    }

    private suspend fun canPlayOffline(item: MediaItem): Boolean {
        if (item.mediaId.startsWith(CATCHUP_PREFIX)) {
            val feedId = item.mediaId.removePrefix(CATCHUP_PREFIX).toIntOrNull() ?: return false
            return episodeDao.getInOrderQueueLocal(feedId).isNotEmpty()
        }
        val episodeId = item.mediaId.removePrefix("episode_").toIntOrNull() ?: return true
        val episode = episodeDao.getEpisodeOnce(episodeId) ?: return false
        val path = episode.local_path ?: return false
        return File(path).exists()
    }

    private suspend fun resolveMediaItem(item: MediaItem, embedArtwork: Boolean = true): MediaItem {
        val episodeId = item.mediaId.removePrefix("episode_").toIntOrNull() ?: return item
        val episode = episodeDao.getEpisodeOnce(episodeId) ?: return item
        val feed = feedDao.getFeedOnce(episode.feed_id)
        val baseUrl = getBaseUrl()

        val localFile = episode.local_path
            ?.let { File(it) }
            ?.takeIf { it.exists() }

        val uri = when {
            localFile != null -> Uri.fromFile(localFile)
            CastCharmApp.isOfflineMode -> null
            baseUrl.isNotEmpty() -> Uri.parse("${baseUrl}api/episodes/$episodeId/stream")
            else -> null
        }

        val feedTitle = feed?.title ?: "Unknown Podcast"
        val artworkUri = resolveEpisodeArtworkUri(context, episode)
        // Embedded artwork is what the AA player screen needs for the item that
        // plays; for the rest of a queue the URI is enough (and embedding 25
        // covers up front is what made queue starts take seconds).
        val artworkBytes = if (embedArtwork) artworkBytesFor(context, episode) else null

        val feedSpeed = feed?.playback_speed ?: 1f

        val metadataExtras = Bundle().apply {
            putString("castcharm_episode_id", episode.id.toString())
            putString("castcharm_feed_title", feedTitle)
            putString("castcharm_artwork_uri", artworkUri.toString())
            episode.duration?.let { putLong("castcharm_duration_ms", it * 1000L) }
            putFloat("castcharm_feed_speed", feedSpeed)
        }

        val metadata = item.mediaMetadata.buildUpon()
            .setTitle(episode.title)
            .setDisplayTitle(episode.title)
            .setArtist(feedTitle)
            .setSubtitle(feedTitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
            // Prefer embedded JPEG bytes for the AA player screen — avoids the
            // repeated-URI-fetch loop Gearhead enters when it can't decode the
            // cached .img format. Fall back to URI only if transcoding failed.
            .apply {
                if (artworkBytes != null) {
                    setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                } else {
                    setArtworkUri(artworkUri)
                }
            }
            .setExtras(metadataExtras)
            .build()

        return item.buildUpon()
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private suspend fun getBaseUrl(): String = ensureApiClientInitializedFromStorage(context)

    @OptIn(UnstableApi::class)
    private fun createFeedItem(feed: FeedEntity): MediaItem {
        val artworkUri = resolveFeedArtworkUri(context, feed)

        val extras = Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )

        Log.d(TAG, "AA FEED ITEM id=${feed.id} title=${feed.title} artworkUri=$artworkUri")

        return MediaItem.Builder()
            .setMediaId(feedMediaId(feed.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(feed.title)
                    .setDisplayTitle(feed.title)
                    .setSubtitle("${feed.episode_count} episodes")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(artworkUri)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    private fun createEpisodeItem(ep: EpisodeEntity, feedName: String?): MediaItem {
        val feedTitle = feedName ?: "Unknown Podcast"
        val artworkUri = resolveEpisodeArtworkUri(context, ep)

        return MediaItem.Builder()
            .setMediaId("episode_${ep.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ep.title)
                    .setDisplayTitle(ep.title)
                    .setArtist(feedTitle)
                    .setSubtitle(feedTitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                    .setArtworkUri(artworkUri)
                    .setExtras(Bundle().apply {
                        putString("castcharm_episode_id", ep.id.toString())
                        putString("castcharm_feed_title", feedTitle)
                        putString("castcharm_artwork_uri", artworkUri.toString())
                        ep.duration?.let { putLong("castcharm_duration_ms", it * 1000L) }
                    })
                    .build()
            )
            .build()
    }
}


// Downsampled JPEG bytes of the episode's artwork for Android Auto's player
// screen (Gearhead cannot decode the cached .img format via URI). Bounded to
// ~512 px: cover art is routinely 3000², which is a 36 MB bitmap.
private suspend fun artworkBytesFor(context: Context, episode: EpisodeEntity): ByteArray? {
    val artworkUri = resolveEpisodeArtworkUri(context, episode)
    return withContext(Dispatchers.IO) {
        runCatching {
            val raw = context.contentResolver.openInputStream(artworkUri)?.use { it.readBytes() }
                ?: return@runCatching null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return@runCatching null
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            out.toByteArray()
        }.getOrNull()
    }
}

private fun paginate(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
    if (items.isEmpty()) return emptyList()
    val safePage = page.coerceAtLeast(0)
    val safePageSize = if (pageSize <= 0) items.size else pageSize
    val from = safePage * safePageSize
    if (from >= items.size) return emptyList()
    val to = minOf(from + safePageSize, items.size)
    return items.subList(from, to)
}

private fun feedMediaId(feedId: Int): String = "feed_$feedId"

@OptIn(UnstableApi::class)
private fun rootItem(context: Context): MediaItem {
    val extras = Bundle()
    extras.putInt(
        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
    )
    extras.putInt(
        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
    )

    return MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("CastCharm")
                .setDisplayTitle("CastCharm")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(extras)
                .build()
        )
        .build()
}

@OptIn(UnstableApi::class)
private fun section(context: Context, id: String, title: String): MediaItem {
    val extras = Bundle()
    if (id == SECTION_PODCASTS) {
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
    }

    Log.d(TAG, "AA SECTION id=$id title=$title extras=$extras")

    return MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setDisplayTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(extras)
                .build()
        )
        .build()
}

@OptIn(UnstableApi::class)
private fun browseLibraryParams(): MediaLibraryService.LibraryParams =
    MediaLibraryService.LibraryParams.Builder()
        .setExtras(
            Bundle().apply {
                putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
            }
        )
        .build()

@OptIn(UnstableApi::class)
private fun browsableLibraryParams(): MediaLibraryService.LibraryParams =
    MediaLibraryService.LibraryParams.Builder()
        .setExtras(
            Bundle().apply {
                putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)
            }
        )
        .build()

@OptIn(UnstableApi::class)
private fun playableLibraryParams(): MediaLibraryService.LibraryParams =
    MediaLibraryService.LibraryParams.Builder()
        .setExtras(
            Bundle().apply {
                putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
            }
        )
        .build()

private fun resolveFeedArtworkUri(context: Context, feed: FeedEntity): Uri {
    return Uri.Builder()
        .scheme("content")
        .authority(context.packageName + ARTWORK_AUTHORITY_SUFFIX)
        .appendPath("feed")
        .appendPath(feed.id.toString())
        .build()
}

private fun resolveEpisodeArtworkUri(context: Context, episode: EpisodeEntity): Uri {
    return Uri.Builder()
        .scheme("content")
        .authority(context.packageName + ARTWORK_AUTHORITY_SUFFIX)
        .appendPath("episode")
        .appendPath(episode.id.toString())
        .build()
}

private fun resourceArtworkUri(context: Context): Uri =
    Uri.parse("android.resource://${context.packageName}/${R.drawable.ic_launcher_foreground}")