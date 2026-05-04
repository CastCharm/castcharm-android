package com.castcharm.android.ui.playlists

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.models.PlaylistOut
import com.castcharm.android.data.api.models.PlayerPlayRequest
import com.castcharm.android.data.api.models.ReorderRequest
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.repository.EpisodeRepository
import com.castcharm.android.data.repository.toEntity
import com.castcharm.android.download.DownloadScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistDetailUiState(
    val playlist: PlaylistOut? = null,
    val episodes: List<EpisodeEntity> = emptyList(),
    val activePhoneDownloadEpisodeIds: Set<Int> = emptySet(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val playlistMemberEpisodeIds: Set<Int> = emptySet()
)

class PlaylistDetailViewModel(private val playlistId: Int) : ViewModel() {
    private val _uiState = MutableStateFlow(PlaylistDetailUiState())
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    private val db = AppDatabase.getDatabase(CastCharmApp.instance)
    private val downloadScheduler = DownloadScheduler(CastCharmApp.instance)

    init {
        loadPlaylist()
    }

    fun loadPlaylist() {
        if (!CastCharmApp.apiClient.isInitialized) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val api = CastCharmApp.apiClient.getApi()
                val playlist = api.getPlaylists().find { it.id == playlistId }
                val remoteEpisodes = api.getPlaylistEpisodes(playlistId)

                val allPhoneDownloadIds = db.downloadDao()
                    .getAllDownloadsOnceOrdered()
                    .map { it.episode_id }
                    .toSet()

                val episodes = remoteEpisodes.map { remote ->
                    val existing = db.episodeDao().getEpisodeOnce(remote.id)
                    remote.toEntity(
                        existing = existing,
                        hasActivePhoneDownload = remote.id in allPhoneDownloadIds
                    )
                }

                val activePhoneIds = episodes.map { it.id }.filter { it in allPhoneDownloadIds }.toSet()

                _uiState.update {
                    it.copy(
                        playlist = playlist,
                        episodes = episodes,
                        activePhoneDownloadEpisodeIds = activePhoneIds,
                        isLoading = false,
                        errorMessage = null,
                        playlistMemberEpisodeIds = episodes.map { ep -> ep.id }.toSet()
                    )
                }
            } catch (e: Exception) {
                Log.e("PlaylistDetailVM", "Failed to load playlist $playlistId", e)
                _uiState.update { it.copy(isLoading = false, errorMessage = e.localizedMessage) }
            }
        }
    }

    fun reorderEpisodes(newOrder: List<EpisodeEntity>) {
        _uiState.update { it.copy(episodes = newOrder) }
        val ids = newOrder.map { it.id }
        viewModelScope.launch {
            try {
                CastCharmApp.apiClient.getApi().reorderPlaylist(playlistId, ReorderRequest(ids))
            } catch (e: Exception) {
                Log.e("PlaylistDetailVM", "Reorder failed", e)
                _uiState.update { it.copy(errorMessage = "Failed to save order: ${e.localizedMessage}") }
                loadPlaylist()
            }
        }
    }

    fun removeFromPlaylist(episodeId: Int) {
        if (!CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            try {
                CastCharmApp.apiClient.getApi().removeFromPlaylist(playlistId, episodeId)
                _uiState.update { state ->
                    state.copy(
                        episodes = state.episodes.filter { it.id != episodeId },
                        playlistMemberEpisodeIds = state.playlistMemberEpisodeIds - episodeId
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to remove: ${e.localizedMessage}") }
            }
        }
    }

    fun playPlaylist(onEpisodeIdReady: (Int) -> Unit) {
        if (!CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            try {
                val state = CastCharmApp.apiClient.getApi().playerPlay(
                    PlayerPlayRequest(context_type = "playlist", context_id = playlistId)
                )
                val episodeId = state.current_episode?.id
                if (episodeId != null) {
                    onEpisodeIdReady(episodeId)
                } else {
                    _uiState.update { it.copy(errorMessage = "No playable episodes in this playlist") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to play: ${e.localizedMessage}") }
            }
        }
    }

    fun togglePlayed(episodeId: Int, currentState: Boolean) {
        viewModelScope.launch {
            try {
                val repo = if (CastCharmApp.apiClient.isInitialized) {
                    EpisodeRepository(CastCharmApp.apiClient.getApi(), db.episodeDao())
                } else null
                repo?.togglePlayed(episodeId, currentState)
                    ?: db.episodeDao().updatePlayedStatus(episodeId, !currentState, System.currentTimeMillis(), pending = true)
                _uiState.update { state ->
                    state.copy(episodes = state.episodes.map { ep ->
                        if (ep.id == episodeId) ep.copy(played = !currentState) else ep
                    })
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to update: ${e.message}") }
            }
        }
    }

    fun downloadToServer(episodeId: Int) {
        if (!CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            try {
                CastCharmApp.apiClient.getApi().queueDownload(episodeId)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to queue: ${e.message}") }
            }
        }
    }

    fun downloadToDevice(episodeId: Int) {
        viewModelScope.launch {
            try {
                downloadScheduler.scheduleDownload(episodeId)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to download: ${e.message}") }
            }
        }
    }

    fun onPlaylistMembershipChanged(episodeId: Int, isInPlaylist: Boolean) {
        _uiState.update { state ->
            val updated = if (isInPlaylist) state.playlistMemberEpisodeIds + episodeId
            else state.playlistMemberEpisodeIds - episodeId
            state.copy(playlistMemberEpisodeIds = updated)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
