package com.castcharm.android.ui.playlists

// One playlist, read live from Room (PlaylistRepository.membersFlow), so a
// smart list drops an episode the moment it stops matching — marked played by
// hand, or finished by the player — with no refresh. The server is pulled on
// resume and after every change the user makes here.

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.models.SmartRules
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.db.entities.FeedEntity
import com.castcharm.android.data.db.entities.PlaylistEntity
import com.castcharm.android.data.repository.EpisodeRepository
import com.castcharm.android.data.repository.PlaylistRepository
import com.castcharm.android.download.DownloadScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistDetailUiState(
    val playlist: PlaylistEntity? = null,
    val rules: SmartRules? = null,
    val episodes: List<EpisodeEntity> = emptyList(),
    val feeds: List<FeedEntity> = emptyList(),
    val activePhoneDownloadEpisodeIds: Set<Int> = emptySet(),
    val onPhoneCount: Int = 0,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val playlistMemberEpisodeIds: Set<Int> = emptySet(),
    val deleted: Boolean = false,
    val owners: Map<Int, Map<String, String>> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModel(private val playlistId: Int) : ViewModel() {
    private val _uiState = MutableStateFlow(PlaylistDetailUiState())
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    private val db = AppDatabase.getDatabase(CastCharmApp.instance)
    private val repo = PlaylistRepository(db)
    private val downloadScheduler = DownloadScheduler(CastCharmApp.instance)
    private val offline = MutableStateFlow(CastCharmApp.isOfflineMode)

    init {
        viewModelScope.launch {
            offline.flatMapLatest { off ->
                combine(repo.playlistFlow(playlistId), repo.membersFlow(playlistId, localOnly = off),
                        db.downloadDao().getAllDownloads(), db.feedDao().getAllFeeds()) { pl, eps, rows, feeds ->
                    val active = rows.map { it.episode_id }.toSet()
                    PlaylistDetailUiState(
                        playlist = pl, rules = repo.rulesOf(pl), episodes = eps, feeds = feeds,
                        activePhoneDownloadEpisodeIds = eps.map { it.id }.filter { it in active }.toSet(),
                        onPhoneCount = eps.count { it.local_path != null },
                        isLoading = false, playlistMemberEpisodeIds = eps.map { it.id }.toSet(),
                    )
                }
            }.collect { fresh ->
                _uiState.update { fresh.copy(errorMessage = it.errorMessage, infoMessage = it.infoMessage, deleted = it.deleted, owners = it.owners) }
            }
        }
        viewModelScope.launch {
            repo.ownerNamesFlow().collect { map -> _uiState.update { it.copy(owners = map) } }
        }
    }

    /** Other owners of a file, seen from this playlist. */
    fun sharedFromHere(episodeId: Int): List<String> {
        val pl = _uiState.value.playlist ?: return emptyList()
        val mine = repo.ownerFor(pl)
        return (_uiState.value.owners[episodeId] ?: emptyMap()).filterKeys { it != mine }.values.toList()
    }

    fun setOffline(isOffline: Boolean) { offline.value = isOffline }

    /** Pull this playlist from the server (members too). Offline: no-op. */
    fun loadPlaylist() {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            try { repo.refreshOne(playlistId) } catch (e: Exception) {
                Log.w("PlaylistDetailVM", "refresh failed", e)
                _uiState.update { it.copy(errorMessage = e.localizedMessage) }
            }
        }
    }

    private var orderDirty = false

    fun moveEpisode(fromKey: Any?, toKey: Any?) {
        _uiState.update { state ->
            val list = state.episodes.toMutableList()
            val fromIdx = list.indexOfFirst { it.id == fromKey }
            val toIdx = list.indexOfFirst { it.id == toKey }
            if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return@update state
            list.add(toIdx, list.removeAt(fromIdx))
            orderDirty = true
            state.copy(episodes = list)
        }
    }

    fun commitOrder() {
        if (!orderDirty) return
        orderDirty = false
        val ids = _uiState.value.episodes.map { it.id }
        viewModelScope.launch {
            try { repo.reorder(playlistId, ids) } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to save order: ${e.localizedMessage}") }
                loadPlaylist()
            }
        }
    }

    fun removeFromPlaylist(episodeId: Int) {
        viewModelScope.launch {
            try { repo.removeEpisode(playlistId, episodeId) } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to remove: ${e.localizedMessage}") }
            }
        }
    }

    fun playPlaylist() = CastCharmApp.playerController.playMediaId("playlist_$playlistId")
    fun playFrom(episodeId: Int) = CastCharmApp.playerController.playMediaId("playlist_${playlistId}_from_$episodeId")

    fun togglePlayed(episodeId: Int, currentState: Boolean) {
        viewModelScope.launch {
            try {
                val repoE = if (CastCharmApp.apiClient.isInitialized && !CastCharmApp.isOfflineMode)
                    EpisodeRepository(CastCharmApp.apiClient.getApi(), db.episodeDao()) else null
                repoE?.togglePlayed(episodeId, currentState)
                    ?: db.episodeDao().updatePlayedStatus(episodeId, !currentState, System.currentTimeMillis(), pending = true)
                // The Room flow repaints; a smart list drops the row on its own.
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to update: ${e.message}") }
            }
        }
    }

    fun downloadToServer(episodeId: Int) {
        if (!CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            try { CastCharmApp.apiClient.getApi().queueDownload(episodeId) } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to queue: ${e.message}") }
            }
        }
    }

    fun downloadToDevice(episodeId: Int) {
        viewModelScope.launch {
            try { downloadScheduler.scheduleDownload(episodeId) } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to download: ${e.message}") }
            }
        }
    }

    fun downloadAll() {
        viewModelScope.launch {
            try {
                val n = repo.downloadAll(playlistId)
                _uiState.update { it.copy(infoMessage = if (n == 0) "Everything here is already on your phone" else "Saving $n episode${if (n == 1) "" else "s"} to this phone") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to queue downloads: ${e.message}") }
            }
        }
    }

    fun updatePlaylist(name: String?, description: String?, rules: SmartRules?) {
        viewModelScope.launch {
            try { repo.update(playlistId, name, description, rules) } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to save: ${e.message}") }
            }
        }
    }

    fun deletePlaylist() {
        if (_uiState.value.playlist?.is_system == true) {
            _uiState.update { it.copy(errorMessage = "Get Caught Up is built in and can't be deleted. Edit its rules instead.") }
            return
        }
        viewModelScope.launch {
            try { repo.delete(playlistId); _uiState.update { it.copy(deleted = true) } } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to delete: ${e.message}") }
            }
        }
    }

    fun onPlaylistMembershipChanged(@Suppress("UNUSED_PARAMETER") episodeId: Int, @Suppress("UNUSED_PARAMETER") isInPlaylist: Boolean) {
        viewModelScope.launch { runCatching { repo.refreshMembers(playlistId) } }
    }

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
