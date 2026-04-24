package com.castcharm.android.player

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

sealed class PlaybackUiEvent {
    data class ShowMessage(val message: String) : PlaybackUiEvent()
}

private data class EpisodePlaybackRequest(
    val episodeId: Int,
    val mediaUri: String?,
    val positionSeconds: Int,
    val resumeWithRewind: Boolean
)

class PlayerController(private val context: Context) {

    private var mediaController: MediaController? = null
    private var pendingPlaybackRequest: EpisodePlaybackRequest? = null

    private var onPlaybackStateChanged: ((isPlaying: Boolean) -> Unit)? = null
    private var onCompletion: (() -> Unit)? = null

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlaybackUiEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressTickerStarted = false

    private val db by lazy { AppDatabase.getDatabase(CastCharmApp.instance) }

    init {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlayerService::class.java)
        )

        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                try {
                    val controller = controllerFuture.get()
                    mediaController = controller
                    setupControllerListener(controller)
                    updatePlaybackStateFromController()
                    startProgressTickerIfNeeded()

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
            { runnable ->
                Handler(context.mainLooper).post(runnable)
            }
        )
    }

    private fun setupControllerListener(controller: Player) {
        controller.addListener(object : Player.Listener {

            override fun onEvents(player: Player, events: Player.Events) {
                updatePlaybackStateFromController()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlaybackStateChanged?.invoke(isPlaying)
                updatePlaybackStateFromController()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
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

    private fun updatePlaybackStateFromController() {
        val controller = mediaController
        if (controller == null) {
            _playbackState.value = PlaybackUiState()
            return
        }

        val mediaItem = controller.currentMediaItem
        val metadata = mediaItem?.mediaMetadata
        val extras = metadata?.extras

        val episodeId = extras?.getString("castcharm_episode_id")?.toIntOrNull()
            ?: mediaItem?.mediaId?.removePrefix("episode_")?.toIntOrNull()

        val title = metadata?.title?.toString()
            ?: metadata?.displayTitle?.toString()

        val feedTitle = extras?.getString("castcharm_feed_title")
            ?: metadata?.artist?.toString()
            ?: metadata?.subtitle?.toString()

        val artworkUri = extras?.getString("castcharm_artwork_uri")
            ?: metadata?.artworkUri?.toString()

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

    private fun shouldResumeEpisode(played: Boolean, positionSeconds: Int): Boolean {
        return !played && positionSeconds > 0
    }

    private suspend fun buildEpisodePlaybackRequest(episodeId: Int): EpisodePlaybackRequest? {
        val episodeDao = db.episodeDao()
        val localEpisode = episodeDao.getEpisodeOnce(episodeId)

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

        val freshestEpisode = episodeRepository.fetchEpisodeFromApi(episodeId) ?: localEpisode
        val episode = freshestEpisode ?: return null

        val freshestLocalFile = episode.local_path
            ?.takeIf { it.isNotBlank() }
            ?.let { path -> File(path) }
            ?.takeIf { it.exists() }

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

    fun playUri(
        episodeId: Int,
        mediaUri: String?,
        positionSeconds: Int = 0,
        resumeWithRewind: Boolean = false
    ) {
        val controller = mediaController
        if (controller == null) {
            pendingPlaybackRequest = EpisodePlaybackRequest(
                episodeId = episodeId,
                mediaUri = mediaUri,
                positionSeconds = positionSeconds,
                resumeWithRewind = resumeWithRewind
            )
            return
        }

        val startPositionMs = if (resumeWithRewind && positionSeconds > 0) {
            maxOf(0L, positionSeconds * 1000L - 5000L)
        } else {
            positionSeconds * 1000L
        }

        val mediaItemBuilder = MediaItem.Builder()
            .setMediaId("episode_$episodeId")

        if (!mediaUri.isNullOrBlank()) {
            val uri = if (mediaUri.startsWith("/")) {
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