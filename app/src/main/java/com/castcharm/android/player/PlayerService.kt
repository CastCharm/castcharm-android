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
import com.castcharm.android.data.api.models.ProgressRequest
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
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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
private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
// Episodes are marked played when position reaches 98% of duration.
private const val PLAYED_THRESHOLD_PCT = 0.98f

private fun speedLabel(speed: Float) = when (speed) {
    0.75f -> "0.75×"
    1.0f -> "1.0×"
    1.25f -> "1.25×"
    1.5f -> "1.5×"
    2.0f -> "2.0×"
    else -> "%.2f×".format(speed)
}

// Builds the now-playing subtitle shown in the notification and Android Auto.
// Format: "Feed Title • 1.5×" (or just "1.5×" if no feed title is available).
private fun buildNowPlayingSubtitle(feedTitle: String?, speed: Float): String {
    val label = speedLabel(speed)
    return if (feedTitle.isNullOrBlank()) label else "$feedTitle • $label"
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
private fun createSpeedButton(speed: Float): CommandButton =
    CommandButton.Builder(CommandButton.ICON_UNDEFINED)
        .setSessionCommand(SPEED_COMMAND)
        .setDisplayName(speedLabel(speed))
        .setCustomIconResId(R.drawable.ic_speed)
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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlayerService.onCreate start")
        try {
            // Kick off lazy ApiClient init in the background so it's ready before
            // the first playback request or browse query arrives.
            serviceScope.launch {
                ensureApiClientInitializedFromStorage(this@PlayerService)
            }

            // Build a dedicated OkHttpClient for streaming. The interceptor calls
            // ensureApiClientInitializedBlocking() to handle the post-reboot case
            // where the service starts before the ApiClient is initialized.
            // readTimeout(0) disables the read deadline — required for streaming
            // large audio files without a mid-stream timeout.
            //
            // We delegate cookie reads to ApiClient's live cookie jar rather than
            // using a separate PersistentCookieJar. A separate jar would only load
            // cookies from disk once at service creation time, so it would be stale
            // if login happened after the service started — causing 401 errors when
            // streaming. The application interceptor below guarantees ApiClient is
            // initialized before OkHttp's BridgeInterceptor calls loadForRequest().
            val streamingCookieJar = object : CookieJar {
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    CastCharmApp.apiClient.getHttpClient()?.cookieJar?.loadForRequest(url) ?: emptyList()

                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    CastCharmApp.apiClient.getHttpClient()?.cookieJar?.saveFromResponse(url, cookies)
                }
            }
            val streamingClient = OkHttpClient.Builder()
                .cookieJar(streamingCookieJar)
                .addInterceptor { chain ->
                    ensureApiClientInitializedBlocking(this@PlayerService)
                    // Fail loudly if init failed — better than proceeding without auth
                    // and getting a mysterious 401 that ExoPlayer surfaces as a generic error.
                    if (!CastCharmApp.apiClient.isInitialized) {
                        throw IOException("Cannot stream: server connection unavailable")
                    }
                    val response = chain.proceed(chain.request())
                    if (response.code == 401 || response.code == 403) {
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

            latestPlaybackSpeed = player.playbackParameters.speed

            val callback = PlayerLibrarySessionCallback(
                context = this,
                feedDao = db.feedDao(),
                episodeDao = db.episodeDao(),
                player = player,
                scope = serviceScope,
                sessionProvider = { mediaSession }
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
                    val feedSpeed = mediaItem?.mediaMetadata?.extras
                        ?.getFloat("castcharm_feed_speed", 1f) ?: 1f
                    if (player.playbackParameters.speed != feedSpeed) {
                        player.setPlaybackParameters(PlaybackParameters(feedSpeed))
                    }
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
                    latestPlaybackSpeed = playbackParameters.speed
                    callback.updateLatestPlaybackSpeed(playbackParameters.speed)

                    serviceScope.launch {
                        refreshNowPlayingSpeedMetadata(playbackParameters.speed)
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
        } catch (e: Exception) {
            Log.e(TAG, "PlayerService.onCreate FAILED — session will be null", e)
        }
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

                // 10-second server sync gate — save battery and server load.
                if (now - lastSyncMs >= 10_000L) {
                    try {
                        val existing = db.episodeDao().getEpisodeOnce(episodeId)
                        val targetPlayed = EpisodeRepository.derivePlayedState(
                            positionSeconds = positionSeconds,
                            // durationSeconds from the live player is more accurate than
                            // the cached DB value (which may be from RSS metadata).
                            durationSeconds = durationSeconds ?: existing?.duration,
                            currentPlayed = existing?.played ?: false,
                            thresholdPct = PLAYED_THRESHOLD_PCT
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
                                CastCharmApp.apiClient.getApi().togglePlayed(episodeId)
                                db.episodeDao().updatePlayedStatus(episodeId, targetPlayed, now, pending = false)
                            } else {
                                db.episodeDao().updatePlayedStatus(episodeId, targetPlayed, now, pending = true)
                            }
                        }

                        lastSyncMs = now
                        // Notify Android Auto so the "Continue Listening" list updates
                        // to reflect the new position/played state.
                        notifyBrowseSectionsChanged(episodeId)
                    } catch (_: Exception) {
                        // Server call failed — write to DB with pending=true so
                        // SyncWorker can flush the progress on the next cycle.
                        val existing = db.episodeDao().getEpisodeOnce(episodeId)
                        val targetPlayed = EpisodeRepository.derivePlayedState(
                            positionSeconds = positionSeconds,
                            durationSeconds = durationSeconds ?: existing?.duration,
                            currentPlayed = existing?.played ?: false,
                            thresholdPct = PLAYED_THRESHOLD_PCT
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

    private suspend fun markPlayed(episodeId: Int) {
        val now = System.currentTimeMillis()
        try {
            db.episodeDao().updatePlayedStatus(episodeId, true, now)
            if (CastCharmApp.apiClient.isInitialized && !CastCharmApp.isOfflineMode) {
                CastCharmApp.apiClient.getApi().togglePlayed(episodeId)
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
) : MediaLibraryService.MediaLibrarySession.Callback {

    private val connectedControllers = linkedSetOf<MediaSession.ControllerInfo>()

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

        val playerCommands = base.availablePlayerCommands
            .buildUpon()
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_NEXT)
            .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .build()

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

            // Persist the speed change for this feed so it survives session restarts.
            scope.launch {
                val feedId = player.currentMediaItem?.mediaId
                    ?.removePrefix("episode_")?.toIntOrNull()
                    ?.let { episodeDao.getEpisodeOnce(it)?.feed_id }
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

                    allItems = episodeDao
                        .getEpisodesForAndroidAutoByFeed(feedId)
                        .map { ep -> createEpisodeItem(ep, feed.title) }
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
                val resolved = mediaItems.map { resolveMediaItem(it) }

                val safeStartIndex = startIndex.coerceIn(0, resolved.lastIndex.coerceAtLeast(0))
                val selectedItem = resolved.getOrNull(safeStartIndex)

                val selectedEpisodeId = selectedItem
                    ?.mediaId
                    ?.removePrefix("episode_")
                    ?.toIntOrNull()

                val savedResumeMs = selectedEpisodeId?.let { id ->
                    episodeDao.getEpisodeOnce(id)
                        ?.takeIf { !it.played }
                        ?.play_position_seconds
                        ?.takeIf { it > 0 }
                        ?.let { maxOf(0L, it * 1000L - 5000L) }
                } ?: 0L

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
            if (CastCharmApp.isOfflineMode) {
                mediaItems.filter { canPlayOffline(it) }.map { resolveMediaItem(it) }
            } else {
                mediaItems.map { resolveMediaItem(it) }
            }
        }

    private suspend fun canPlayOffline(item: MediaItem): Boolean {
        val episodeId = item.mediaId.removePrefix("episode_").toIntOrNull() ?: return true
        val episode = episodeDao.getEpisodeOnce(episodeId) ?: return false
        val path = episode.local_path ?: return false
        return File(path).exists()
    }

    private suspend fun resolveMediaItem(item: MediaItem): MediaItem {
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
            .setArtworkUri(artworkUri)
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