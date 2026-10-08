package com.castcharm.android.ui.player

// PlayerViewModel bridges PlayerScreen and the global PlayerController.
// It observes PlayerController.playbackState (which ticks every 500ms) and
// enriches it with the full EpisodeEntity + FeedEntity from the DB for display.
//
// Key responsibilities:
//   - Keeps currentlyLoadedEpisodeId to detect episode transitions and
//     trigger a fresh DB load only when the playing episode changes.
//   - reconcilePlayedForUi(): adjusts episode.played locally so the scrubber
//     color and "mark played" button update immediately when the threshold is
//     crossed, without waiting for the DB write to round-trip.
//   - Progress and played state are written by PlayerService only; this
//     ViewModel never writes them (two writers used to disagree on the
//     threshold and flip episodes between played and unplayed).
//   - Sleep timer: counts down in UI state only; pauses the player when it hits 0.
//   - justMarkedPlayed: one-shot flag consumed by PlayerScreen to auto-dismiss.

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.db.entities.FeedEntity
import com.castcharm.android.data.repository.EpisodeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val episode: EpisodeEntity? = null,
    val feed: FeedEntity? = null,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val playbackSpeed: Float = 1f,
    val isLoading: Boolean = false,
    val sleepTimerMinutes: Int = 0,
    val sleepTimerRemainingMs: Long = 0L,
    val justMarkedPlayed: Boolean = false
)

class PlayerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val db = AppDatabase.getDatabase(CastCharmApp.instance)
    private val playerController = CastCharmApp.playerController

    private var playbackStateCollectionJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var loadEpisodeJob: Job? = null

    private var autoPlayedThresholdPct: Float = 0.98f
    private var currentlyLoadedEpisodeId: Int? = null

    init {
        viewModelScope.launch {
            if (!CastCharmApp.isOfflineMode && CastCharmApp.apiClient.isInitialized) {
                runCatching {
                    CastCharmApp.apiClient.getApi().getSettings()
                }.onSuccess {
                    autoPlayedThresholdPct = it.auto_played_threshold / 100f
                }
            }
        }

        // Progress and played state are written by PlayerService alone (it runs
        // whenever audio does: car, headset, screen off). This ViewModel only
        // mirrors the player for display.
        observeSharedPlaybackState()
    }

    private fun episodeRepositoryOrNull(): EpisodeRepository? {
        return if (CastCharmApp.apiClient.isInitialized) {
            EpisodeRepository(
                CastCharmApp.apiClient.getApi(),
                db.episodeDao()
            )
        } else {
            null
        }
    }

    // Updates episode.played in the UI state to reflect the current position
    // without triggering a DB write — gives immediate visual feedback when the
    // player crosses the threshold. The actual DB write happens in the progress
    // sync loop (PlayerService or PlayerViewModel.progressTrackingJob).
    private fun reconcilePlayedForUi(
        episode: EpisodeEntity,
        positionMs: Long,
        durationMs: Long
    ): EpisodeEntity {
        val positionSeconds = (positionMs / 1000L).toInt()
        val durationSeconds = durationMs.takeIf { it > 0L }?.div(1000L)?.toInt() ?: episode.duration

        val targetPlayed = EpisodeRepository.derivePlayedState(
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
            currentPlayed = episode.played,
            thresholdPct = autoPlayedThresholdPct
        )

        return if (episode.played == targetPlayed) episode else episode.copy(played = targetPlayed)
    }

    private fun observeSharedPlaybackState() {
        playbackStateCollectionJob?.cancel()
        playbackStateCollectionJob = viewModelScope.launch {
            playerController.playbackState.collectLatest { playbackState ->
                var nextState = _uiState.value.copy(
                    isPlaying = playbackState.isPlaying,
                    currentPosition = playbackState.positionMs,
                    duration = playbackState.durationMs,
                    playbackSpeed = playbackState.playbackSpeed
                )

                val activeEpisodeId = playbackState.episodeId

                when {
                    activeEpisodeId == null -> {
                        currentlyLoadedEpisodeId = null
                        loadEpisodeJob?.cancel()
                        nextState = nextState.copy(
                            episode = null,
                            feed = null,
                            isLoading = false,
                            justMarkedPlayed = nextState.justMarkedPlayed
                        )
                    }

                    activeEpisodeId != currentlyLoadedEpisodeId -> {
                        _uiState.value = nextState
                        loadEpisodeForDisplay(activeEpisodeId)
                        return@collectLatest
                    }

                    else -> {
                        val episode = nextState.episode
                        if (episode != null && episode.id == activeEpisodeId) {
                            val reconciledEpisode = reconcilePlayedForUi(
                                episode = episode,
                                positionMs = playbackState.positionMs,
                                durationMs = playbackState.durationMs
                            )

                            nextState = nextState.copy(
                                episode = reconciledEpisode,
                                justMarkedPlayed = if (reconciledEpisode.played) {
                                    nextState.justMarkedPlayed
                                } else {
                                    false
                                }
                            )
                        }
                    }
                }

                _uiState.value = nextState
            }
        }
    }

    private fun loadEpisodeForDisplay(targetEpisodeId: Int) {
        loadEpisodeJob?.cancel()
        loadEpisodeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val localEpisode = db.episodeDao().getEpisodeOnce(targetEpisodeId)
                val episode = localEpisode ?: episodeRepositoryOrNull()?.fetchEpisodeFromApi(targetEpisodeId)

                if (episode == null) {
                    currentlyLoadedEpisodeId = null
                    _uiState.value = _uiState.value.copy(
                        episode = null,
                        feed = null,
                        isLoading = false,
                        justMarkedPlayed = false
                    )
                    return@launch
                }

                val feed = db.feedDao().getFeedOnce(episode.feed_id)

                currentlyLoadedEpisodeId = episode.id

                val playbackState = playerController.playbackState.value
                val reconciledEpisode = reconcilePlayedForUi(
                    episode = episode,
                    positionMs = playbackState.positionMs,
                    durationMs = playbackState.durationMs
                )

                _uiState.value = _uiState.value.copy(
                    episode = reconciledEpisode,
                    feed = feed,
                    isLoading = false,
                    justMarkedPlayed = false
                )
            } catch (_: Exception) {
                currentlyLoadedEpisodeId = null
                _uiState.value = _uiState.value.copy(
                    episode = null,
                    feed = null,
                    isLoading = false,
                    justMarkedPlayed = false
                )
            }
        }
    }

    fun stop() {
        playerController.stopAndClear()
    }

    fun togglePlayPause() {
        playerController.togglePlayPause()
    }

    fun skipBackward() {
        playerController.skipBackward()
    }

    fun skipForward() {
        playerController.skipForward()
    }

    fun seekTo(positionMs: Long) {
        playerController.seekTo(positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        playerController.setPlaybackSpeed(speed)
        val feedId = _uiState.value.feed?.id ?: return
        viewModelScope.launch {
            db.feedDao().updatePlaybackSpeed(feedId, speed)
        }
    }

    fun markPlayed() {
        viewModelScope.launch {
            try {
                val episode = _uiState.value.episode ?: return@launch

                val finalDurationMs = when {
                    _uiState.value.duration > 0L -> _uiState.value.duration
                    (episode.duration ?: 0) > 0 -> (episode.duration!! * 1000L)
                    else -> 0L
                }

                val finalPositionSeconds = if (finalDurationMs > 0L) {
                    (finalDurationMs / 1000L).toInt()
                } else {
                    episode.play_position_seconds
                }

                // Set the state outright. Deriving it from the RSS duration
                // silently did nothing whenever that duration was longer than
                // the real file.
                val repo = episodeRepositoryOrNull()
                if (repo != null) {
                    repo.setPlayed(episode.id, true)
                } else {
                    db.episodeDao().updatePlayedStatus(episode.id, true, System.currentTimeMillis(), pending = true)
                }

                _uiState.value = _uiState.value.copy(
                    episode = episode.copy(
                        played = true,
                        play_position_seconds = finalPositionSeconds
                    ),
                    currentPosition = finalDurationMs,
                    justMarkedPlayed = true
                )

                // In a queue, "mark played" means "on to the next one".
                if (playerController.hasNextMediaItem()) {
                    playerController.seekToNextMediaItem()
                } else {
                    playerController.stopAndClear()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun consumeJustMarkedPlayed() {
        if (_uiState.value.justMarkedPlayed) {
            _uiState.value = _uiState.value.copy(justMarkedPlayed = false)
        }
    }

    // Starts a countdown coroutine that pauses playback when it reaches zero.
    // Calling with minutes <= 0 cancels any existing timer (timer off).
    // Replacing an existing timer by calling startSleepTimer() again is safe —
    // the old job is cancelled before the new one starts.
    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()

        if (minutes <= 0) {
            _uiState.value = _uiState.value.copy(
                sleepTimerMinutes = 0,
                sleepTimerRemainingMs = 0
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            sleepTimerMinutes = minutes,
            sleepTimerRemainingMs = minutes * 60 * 1000L
        )

        sleepTimerJob = viewModelScope.launch {
            var remaining = minutes * 60 * 1000L

            while (isActive && remaining > 0) {
                delay(1000)
                remaining -= 1000
                _uiState.value = _uiState.value.copy(
                    sleepTimerRemainingMs = remaining
                )
            }

            if (isActive) {
                // Timer expired — pause and reset the timer display.
                playerController.pause()
                _uiState.value = _uiState.value.copy(
                    sleepTimerMinutes = 0,
                    sleepTimerRemainingMs = 0,
                    isPlaying = false
                )
            }
        }
    }

    override fun onCleared() {
        playbackStateCollectionJob?.cancel()
        sleepTimerJob?.cancel()
        loadEpisodeJob?.cancel()
        super.onCleared()
    }
}