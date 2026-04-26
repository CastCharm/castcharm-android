package com.castcharm.android.player

// PlayerController is the app-side interface to ExoPlayer running inside PlayerService.
// It connects to PlayerService via the MediaController IPC bridge (Media3 session protocol)
// and exposes playback state as a StateFlow so Compose UI can observe it reactively.
//
// Key design points:
//   - MediaController is built asynchronously in init{}. Any playEpisode() call that
//     arrives before the controller is ready is queued in pendingPlaybackRequest and
//     replayed as soon as the controller connects.
//   - Progress ticks every 500 ms while media is loaded; this drives the scrubber
//     animation in PlayerScreen without requiring the player to emit position events.
//   - All state changes (play/pause/seek/speed) call updatePlaybackStateFromController()
//     immediately so the UI updates without waiting for the next tick.
//   - Episode metadata is read from MediaItem extras (castcharm_*) which are set by
//     PlayerService.resolveMediaItem() and survive inter-process transport.

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.repository.EpisodeRepository
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// All fields needed by PlayerScreen and MiniPlayerBar. Emitted as a single
// snapshot so the UI never observes a partially-updated state.
data class PlaybackUiState(
    val episodeId: Int? = null,
    val title: String? = null,
    val feedTitle: String? = null,
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val hasMedia: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE
)

// One-shot UI signals (toasts, error messages) that should not be re-delivered
// on recomposition. SharedFlow ensures each event is consumed exactly once.
sealed class PlaybackUiEvent {
    data class ShowMessage(val message: String) : PlaybackUiEvent()
}

// Internal value object capturing everything needed to call playUri() later.
// Stored in pendingPlaybackRequest when a play request arrives before the
// MediaController IPC connection is ready.
private data class EpisodePlaybackRequest(
    val episodeId: Int,
    val mediaUri: String?,
    val positionSeconds: Int,
    val resumeWithRewind: Boolean
)

class PlayerController(private val context: Context) {

    private var mediaController: MediaController? = null
    // Holds a play request that arrived before the MediaController was ready.
    // Replayed in the listener callback once the controller connects.
    private var pendingPlaybackRequest: EpisodePlaybackRequest? = null

    // Optional callbacks for callers that need imperative notification
    // (e.g., triggering side effects outside the StateFlow observation chain).
    private var onPlaybackStateChanged: ((isPlaying: Boolean) -> Unit)? = null
    private var onCompletion: (() -> Unit)? = null

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    // extraBufferCapacity=8: if no collector is active, up to 8 events are
    // buffered before tryEmit() starts dropping. This prevents losing error
    // messages emitted while the PlayerScreen is briefly off-screen.
    private val _events = MutableSharedFlow<PlaybackUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlaybackUiEvent> = _events.asSharedFlow()

    // Main.immediate ensures state updates are processed synchronously on the
    // main thread, preventing one-frame delays in UI updates after seek/play.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressTickerStarted = false

    private val db by lazy { AppDatabase.getDatabase(CastCharmApp.instance) }

    init {
        // Build the SessionToken targeting PlayerService so MediaController knows
        // which service to bind to.
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlayerService::class.java)
        )

        // buildAsync() returns immediately; the actual IPC bind completes on a
        // background thread. The listener fires on the Executor provided (main looper).
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                try {
                    val controller = controllerFuture.get()
                    mediaController = controller
                    setupControllerListener(controller)
                    // Sync initial state in case the service was already playing.
                    updatePlaybackStateFromController()
                    startProgressTickerIfNeeded()

                    // Replay any play request that was queued before the controller
                    // was ready (e.g., user tapped play immediately after app launch).
                    pendingPlaybackRequest?.let { request ->
                        pendingPlaybackRequest = null
                        playUri(
                            episodeId = request.episodeId,
                            mediaUri = request.mediaUri,
                            positionSeconds = request.positionSeconds,
                            resumeWithRewind = request.resumeWithRewind
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            // Executor: post the listener body to the main looper so all
            // MediaController calls are made on the main thread.
            { runnable ->
                Handler(context.mainLooper).post(runnable)
            }
        )
    }

    // Attach a Player.Listener to the MediaController so any state change coming
    // from the PlayerService side (e.g., system media key pause, speed change
    // from Android Auto) propagates into PlaybackUiState immediately.
    private fun setupControllerListener(controller: Player) {
        controller.addListener(object : Player.Listener {

            // onEvents fires as a batch after all individual events for the
            // current frame have been delivered — safe to call updatePlaybackState
            // here without missing any event-driven changes.
            override fun onEvents(player: Player, events: Player.Events) {
                updatePlaybackStateFromController()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Notify imperative callback (e.g., progress flush trigger) and
                // also update the StateFlow for reactive UI.
                onPlaybackStateChanged?.invoke(isPlaying)
                updatePlaybackStateFromController()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // Notify imperative callback so callers can auto-advance queues.
                    onCompletion?.invoke()
                }
                updatePlaybackStateFromController()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updatePlaybackStateFromController()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                updatePlaybackStateFromController()
            }

            // Fired on seek, track transition, or position reset — update the
            // UI scrubber position immediately rather than waiting for the next tick.
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                updatePlaybackStateFromController()
            }

            override fun onPlayerError(error: PlaybackException) {
                error.printStackTrace()
                _events.tryEmit(
                    PlaybackUiEvent.ShowMessage(
                        error.errorCodeName.takeIf { it.isNotBlank() }
                            ?: "Playback failed."
                    )
                )
                updatePlaybackStateFromController()
            }
        })
    }

    // Starts a 500ms polling loop that refreshes positionMs in PlaybackUiState.
    // The flag prevents duplicate loops if startProgressTickerIfNeeded() is called
    // again (e.g., after a reconnect). Only ticks while media is loaded so
    // there's no unnecessary work when the player is idle.
    private fun startProgressTickerIfNeeded() {
        if (progressTickerStarted) return
        progressTickerStarted = true

        scope.launch {
            while (isActive) {
                val controller = mediaController
                if (controller != null && controller.currentMediaItem != null) {
                    updatePlaybackStateFromController()
                }
                delay(500)
            }
        }
    }

    // Reads the current player state and writes a new PlaybackUiState snapshot.
    // Called from every listener callback, the 500ms ticker, and every user action.
    private fun updatePlaybackStateFromController() {
        val controller = mediaController
        if (controller == null) {
            // Controller not yet connected — emit an empty "idle" state.
            _playbackState.value = PlaybackUiState()
            return
        }

        val mediaItem = controller.currentMediaItem
        val metadata = mediaItem?.mediaMetadata
        val extras = metadata?.extras

        // Episode ID: prefer the castcharm_episode_id extra set by resolveMediaItem().
        // Falls back to parsing the mediaId prefix "episode_<id>" which is set when
        // the item was built in PlayerController.playUri().
        val episodeId = extras?.getString("castcharm_episode_id")?.toIntOrNull()
            ?: mediaItem?.mediaId?.removePrefix("episode_")?.toIntOrNull()

        val title = metadata?.title?.toString()
            ?: metadata?.displayTitle?.toString()

        // Feed title: try the extra first, then standard MediaMetadata fields.
        val feedTitle = extras?.getString("castcharm_feed_title")
            ?: metadata?.artist?.toString()
            ?: metadata?.subtitle?.toString()

        val artworkUri = extras?.getString("castcharm_artwork_uri")
            ?: metadata?.artworkUri?.toString()

        // Duration: prefer the extra (set from DB at item-build time) because
        // controller.duration returns C.TIME_UNSET while the player is buffering.
        val resolvedDurationMs = extras?.getLong("castcharm_duration_ms")
            ?.takeIf { it > 0L }
            ?: controller.duration.takeIf { it > 0L && it != C.TIME_UNSET }
            ?: 0L

        _playbackState.value = PlaybackUiState(
            episodeId = episodeId,
            title = title,
            feedTitle = feedTitle,
            artworkUri = artworkUri,
            isPlaying = controller.isPlaying,
            positionMs = controller.currentPosition.coerceAtLeast(0L),
            durationMs = resolvedDurationMs,
            playbackSpeed = controller.playbackParameters.speed,
            hasMedia = mediaItem != null,
            isBuffering = controller.playbackState == Player.STATE_BUFFERING,
            playbackState = controller.playbackState
        )
    }

    // Public entry point used by PlayerViewModel and Android Auto. Runs
    // buildEpisodePlaybackRequest() on IO then hands off to playUri() on Main.
    fun playEpisode(episodeId: Int) {
        scope.launch {
            try {
                val request = withContext(Dispatchers.IO) {
                    buildEpisodePlaybackRequest(episodeId)
                } ?: return@launch

                playUri(
                    episodeId = request.episodeId,
                    mediaUri = request.mediaUri,
                    positionSeconds = request.positionSeconds,
                    resumeWithRewind = request.resumeWithRewind
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // An episode should resume from saved position if it hasn't been marked
    // played AND has a non-zero saved position. Starting a fully played episode
    // restarts from 0 (natural user expectation for a re-listen).
    private fun shouldResumeEpisode(played: Boolean, positionSeconds: Int): Boolean {
        return !played && positionSeconds > 0
    }

    // Resolves the media URI and start position for the given episode ID.
    // Three code paths:
    //   1. Offline mode: must have a local file, otherwise emits an error message.
    //   2. Online mode (ApiClient not ready): returns null — callers skip the play.
    //   3. Online mode (ApiClient ready): fetches fresh episode data from the API
    //      to get the latest play_position_seconds and local_path, then picks the
    //      best URI (local file preferred over streaming).
    private suspend fun buildEpisodePlaybackRequest(episodeId: Int): EpisodePlaybackRequest? {
        val episodeDao = db.episodeDao()
        val localEpisode = episodeDao.getEpisodeOnce(episodeId)

        // Verify that local_path actually points to an existing file — the DB
        // value could be stale if the file was deleted externally.
        val localFile = localEpisode?.local_path
            ?.takeIf { it.isNotBlank() }
            ?.let { path -> File(path) }
            ?.takeIf { it.exists() }

        if (CastCharmApp.isOfflineMode) {
            if (localEpisode == null || localFile == null) {
                _events.tryEmit(
                    PlaybackUiEvent.ShowMessage("This episode is not available offline.")
                )
                return null
            }

            val shouldResume = shouldResumeEpisode(
                played = localEpisode.played,
                positionSeconds = localEpisode.play_position_seconds
            )

            return EpisodePlaybackRequest(
                episodeId = localEpisode.id,
                mediaUri = localFile.absolutePath,
                positionSeconds = if (shouldResume) localEpisode.play_position_seconds else 0,
                resumeWithRewind = shouldResume
            )
        }

        if (!CastCharmApp.apiClient.isInitialized) {
            return null
        }

        val episodeRepository = EpisodeRepository(
            CastCharmApp.apiClient.getApi(),
            episodeDao
        )

        // fetchEpisodeFromApi() merges the API response into the DB and returns
        // the freshest version of the episode (updated played state, position, etc.).
        // Falls back to the cached DB row if the network call fails.
        val freshestEpisode = episodeRepository.fetchEpisodeFromApi(episodeId) ?: localEpisode
        val episode = freshestEpisode ?: return null

        // Re-check local file existence with the refreshed episode data — the API
        // response may have cleared local_path if the server-side file was removed.
        val freshestLocalFile = episode.local_path
            ?.takeIf { it.isNotBlank() }
            ?.let { path -> File(path) }
            ?.takeIf { it.exists() }

        // Prefer local file to avoid network overhead. null means PlayerService
        // will build a streaming URL via resolveMediaItem().
        val mediaUri = when {
            freshestLocalFile != null -> freshestLocalFile.absolutePath
            else -> null
        }

        val shouldResume = shouldResumeEpisode(
            played = episode.played,
            positionSeconds = episode.play_position_seconds
        )

        return EpisodePlaybackRequest(
            episodeId = episode.id,
            mediaUri = mediaUri,
            positionSeconds = if (shouldResume) episode.play_position_seconds else 0,
            resumeWithRewind = shouldResume
        )
    }

    fun setOnPlaybackStateChanged(callback: (Boolean) -> Unit) {
        onPlaybackStateChanged = callback
    }

    fun setOnCompletion(callback: () -> Unit) {
        onCompletion = callback
    }

    // Lowest-level play entry point. Builds a MediaItem and hands it to the
    // MediaController to route across the IPC boundary to ExoPlayer in PlayerService.
    fun playUri(
        episodeId: Int,
        mediaUri: String?,
        positionSeconds: Int = 0,
        resumeWithRewind: Boolean = false
    ) {
        val controller = mediaController
        if (controller == null) {
            // Controller not ready yet — queue the request for replay once connected.
            pendingPlaybackRequest = EpisodePlaybackRequest(
                episodeId = episodeId,
                mediaUri = mediaUri,
                positionSeconds = positionSeconds,
                resumeWithRewind = resumeWithRewind
            )
            return
        }

        // resumeWithRewind: rewind up to 10 seconds before the saved position so the
        // user has a moment of context after resuming (common podcast app behaviour).
        val startPositionMs = if (resumeWithRewind && positionSeconds > 0) {
            maxOf(0L, positionSeconds * 1000L - 10_000L)
        } else {
            positionSeconds * 1000L
        }

        val mediaItemBuilder = MediaItem.Builder()
            .setMediaId("episode_$episodeId")

        // Attach the URI only if we have one. A null URI means PlayerService's
        // onSetMediaItems / resolveMediaItem() will build the streaming URL instead.
        if (!mediaUri.isNullOrBlank()) {
            val uri = if (mediaUri.startsWith("/")) {
                // Absolute filesystem path → convert to file:// URI.
                Uri.fromFile(File(mediaUri))
            } else {
                Uri.parse(mediaUri)
            }
            mediaItemBuilder.setUri(uri)
        }

        val mediaItem = mediaItemBuilder.build()

        controller.setMediaItem(mediaItem, startPositionMs)
        controller.prepare()
        controller.play()

        updatePlaybackStateFromController()
    }

    fun pause() {
        mediaController?.pause()
        updatePlaybackStateFromController()
    }

    fun resume() {
        mediaController?.play()
        updatePlaybackStateFromController()
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
        updatePlaybackStateFromController()
    }

    fun stopAndClear() {
        pendingPlaybackRequest = null
        val controller = mediaController ?: return
        controller.stop()
        controller.clearMediaItems()
        updatePlaybackStateFromController()
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
        updatePlaybackStateFromController()
    }

    fun skipBackward() {
        mediaController?.seekBack()
        updatePlaybackStateFromController()
    }

    fun skipForward() {
        mediaController?.seekForward()
        updatePlaybackStateFromController()
    }

    fun setPlaybackSpeed(speed: Float) {
        mediaController?.setPlaybackSpeed(speed)
        updatePlaybackStateFromController()
    }

    fun getCurrentPositionMs(): Long = mediaController?.currentPosition ?: 0L
    fun getDurationMs(): Long = mediaController?.duration ?: 0L
    fun isPlaying(): Boolean = mediaController?.isPlaying ?: false
    fun hasMedia(): Boolean = mediaController?.currentMediaItem != null

    val currentEpisodeId: Int?
        get() = mediaController?.currentMediaItem?.mediaId
            ?.removePrefix("episode_")
            ?.toIntOrNull()

    // Called from CastCharmApp.onTerminate() to cleanly tear down the IPC
    // connection and stop the progress ticker coroutine. Using releaseFuture()
    // with an already-resolved future is the correct way to release an already-
    // obtained MediaController (vs. the future returned from buildAsync()).
    fun release() {
        pendingPlaybackRequest = null
        mediaController?.let {
            MediaController.releaseFuture(Futures.immediateFuture(it))
            mediaController = null
        }
        scope.cancel()
        _playbackState.value = PlaybackUiState()
    }
}