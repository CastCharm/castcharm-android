package com.castcharm.android.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.models.CreatePlaylistRequest
import com.castcharm.android.data.api.models.PlaylistOut
import com.castcharm.android.data.api.models.PlayerPlayRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistsUiState(
    val playlists: List<PlaylistOut> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class PlaylistsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(PlaylistsUiState())
    val uiState: StateFlow<PlaylistsUiState> = _uiState.asStateFlow()

    init {
        loadPlaylists()
    }

    fun loadPlaylists() {
        if (!CastCharmApp.apiClient.isInitialized) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val playlists = CastCharmApp.apiClient.getApi().getPlaylists()
                _uiState.update { it.copy(playlists = playlists, isLoading = false, errorMessage = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.localizedMessage) }
            }
        }
    }

    fun createPlaylist(name: String, description: String? = null, onCreated: (PlaylistOut) -> Unit = {}) {
        if (!CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            try {
                val created = CastCharmApp.apiClient.getApi().createPlaylist(
                    CreatePlaylistRequest(name = name, description = description?.takeIf { it.isNotBlank() })
                )
                loadPlaylists()
                onCreated(created)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to create playlist: ${e.localizedMessage}") }
            }
        }
    }

    fun deletePlaylist(id: Int) {
        if (!CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            try {
                CastCharmApp.apiClient.getApi().deletePlaylist(id)
                loadPlaylists()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to delete: ${e.localizedMessage}") }
            }
        }
    }

    fun playPlaylist(playlistId: Int, onEpisodeIdReady: (Int) -> Unit) {
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

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
