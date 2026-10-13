package com.castcharm.android.ui.playlists

// Playlists read from the local copy (PlaylistRepository); the server is
// pulled on resume and after every change. Playback goes through the
// service's "playlist_<id>" media ids so manual and smart, online and
// offline, share one path.

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.models.SmartRules
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.entities.FeedEntity
import com.castcharm.android.data.db.entities.PlaylistEntity
import com.castcharm.android.data.repository.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistsUiState(
    val playlists: List<PlaylistEntity> = emptyList(),
    val feeds: List<FeedEntity> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
)

class PlaylistsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(PlaylistsUiState())
    val uiState: StateFlow<PlaylistsUiState> = _uiState.asStateFlow()

    private val db = AppDatabase.getDatabase(CastCharmApp.instance)
    val repo = PlaylistRepository(db)

    init {
        viewModelScope.launch {
            repo.playlistsFlow().collect { list -> _uiState.update { it.copy(playlists = list, isLoading = false) } }
        }
        viewModelScope.launch {
            db.feedDao().getAllFeeds().collect { feeds -> _uiState.update { it.copy(feeds = feeds) } }
        }
    }

    fun rulesOf(pl: PlaylistEntity): SmartRules? = repo.rulesOf(pl)

    /** Pull from the server; the Room flow above repaints. Offline: no-op. */
    fun loadPlaylists() {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        viewModelScope.launch {
            try {
                repo.refreshAll()
                _uiState.update { it.copy(isLoading = false, errorMessage = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.localizedMessage) }
            }
        }
    }

    fun createManual(name: String, description: String?) = create(name, description, "custom", null)
    fun createSmart(name: String, description: String?, rules: SmartRules) = create(name, description, "smart", rules)

    private fun create(name: String, description: String?, type: String, rules: SmartRules?) {
        if (!CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            try {
                repo.create(name, description?.takeIf { it.isNotBlank() }, type, rules)
                _uiState.update { it.copy(infoMessage = if (type == "smart") "Smart playlist created" else "Playlist created") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to create playlist: ${e.localizedMessage}") }
            }
        }
    }

    fun updatePlaylist(id: Int, name: String?, description: String?, rules: SmartRules?) {
        viewModelScope.launch {
            try {
                repo.update(id, name, description, rules)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to save: ${e.localizedMessage}") }
            }
        }
    }

    fun deletePlaylist(id: Int) {
        if (!CastCharmApp.apiClient.isInitialized) return
        if (_uiState.value.playlists.any { it.id == id && it.is_system }) {
            _uiState.update { it.copy(errorMessage = "Get Caught Up is built in and can't be deleted. Edit its rules instead.") }
            return
        }
        viewModelScope.launch {
            try {
                repo.delete(id)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to delete: ${e.localizedMessage}") }
            }
        }
    }

    fun playPlaylist(id: Int) {
        CastCharmApp.playerController.playMediaId("playlist_$id")
    }

    fun downloadAll(id: Int) {
        viewModelScope.launch {
            try {
                val n = repo.downloadAll(id)
                _uiState.update { it.copy(infoMessage = if (n == 0) "Everything in this playlist is already on your phone" else "Saving $n episode${if (n == 1) "" else "s"} to this phone") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to queue downloads: ${e.localizedMessage}") }
            }
        }
    }

    /** What rules would match now: the server's answer online, this phone's files offline. */
    suspend fun preview(rules: SmartRules): PreviewResult {
        if (!CastCharmApp.isOfflineMode && CastCharmApp.apiClient.isInitialized) {
            runCatching { CastCharmApp.apiClient.getApi().previewPlaylist(rules) }
                .getOrNull()?.let { r -> return PreviewResult(r.count, r.total_seconds, r.sample.mapNotNull { it["title"]?.toString() }) }
        }
        val local = rules.evaluate(db.episodeDao().getDownloadedEpisodesOnce(), localOnly = true)
        return PreviewResult(local.size, local.sumOf { it.duration ?: 0 }, local.take(2).map { it.title })
    }

    fun clearError() { _uiState.update { it.copy(errorMessage = null) } }
    fun clearInfo() { _uiState.update { it.copy(infoMessage = null) } }
}
