package com.castcharm.android.ui.episodes

// EpisodeListViewModel drives the episode list for a single feed. It:
//   - Combines the episodes Flow and the downloads Flow so the UI sees live
//     download-in-progress indicators without separate refresh calls.
//   - Uses a "hasMore" flag (from the server's limit+1 trick) to show a
//     "Load More" button at the bottom of the list.
//   - Supports multi-select mode: selectedEpisodes is the selected set; the
//     UI switches to multi-select mode when any episode is selected.
//   - Detects when a feed is deleted server-side (feed disappears mid-refresh)
//     and surfaces an error rather than showing a stale empty list.

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.db.entities.FeedEntity
import com.castcharm.android.data.repository.EpisodeRepository
import com.castcharm.android.data.repository.FeedRepository
import com.castcharm.android.download.DownloadScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EpisodeListUiState(
    val feed: FeedEntity? = null,
    val episodes: List<EpisodeEntity> = emptyList(),
    val activePhoneDownloadEpisodeIds: Set<Int> = emptySet(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val hasMore: Boolean = false,
    val selectedEpisodes: Set<Int> = emptySet()
)

class EpisodeListViewModel(private val feedId: Int) : ViewModel() {
    private val _uiState = MutableStateFlow(EpisodeListUiState())
    val uiState: StateFlow<EpisodeListUiState> = _uiState.asStateFlow()

    private val db = AppDatabase.getDatabase(CastCharmApp.instance)
    private val downloadScheduler = DownloadScheduler(CastCharmApp.instance)

    init {
        loadFeedAndEpisodes()
    }

    private fun feedRepositoryOrNull(): FeedRepository? {
        return if (CastCharmApp.apiClient.isInitialized) {
            FeedRepository(
                CastCharmApp.apiClient.getApi(),
                db.feedDao()
            )
        } else {
            null
        }
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

    private fun loadFeedAndEpisodes() {
        viewModelScope.launch {
            Log.d("EpisodeListViewModel", "Starting collection for feed $feedId")
            // Combine the episodes list with the downloads table so that any
            // phone-side download activity (new row added, progress updated, row
            // deleted) causes the UI to recompose without an explicit refresh.
            // activePhoneDownloadEpisodeIds is the subset of this feed's episodes
            // that currently have a DownloadEntity row — used by EpisodeCard to
            // show the in-progress indicator.
            combine(
                db.episodeDao().getEpisodesByFeed(feedId),
                db.downloadDao().getAllDownloads()
            ) { episodes, downloads ->
                val activeDownloadIds = downloads.map { it.episode_id }.toSet()
                val activePhoneIdsForFeed = episodes
                    .map { it.id }
                    .filter { it in activeDownloadIds }
                    .toSet()

                episodes to activePhoneIdsForFeed
            }.collectLatest { (episodes, activePhoneIdsForFeed) ->
                Log.d("EpisodeListViewModel", "Collected ${episodes.size} episodes from DB")
                _uiState.update {
                    it.copy(
                        episodes = episodes,
                        activePhoneDownloadEpisodeIds = activePhoneIdsForFeed,
                        isInitialLoading = false
                    )
                }
            }
        }

        viewModelScope.launch {
            try {
                val localFeed = db.feedDao().getFeedOnce(feedId)
                _uiState.update { it.copy(feed = localFeed) }

                if (!CastCharmApp.isOfflineMode && CastCharmApp.apiClient.isInitialized) {
                    feedRepositoryOrNull()
                }
            } catch (e: Exception) {
                Log.e("EpisodeListViewModel", "Failed to load feed info", e)
            }
        }

        refreshEpisodes()
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun reloadFromDb() {
        // Flow-backed already.
    }

    fun refreshEpisodes() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRefreshing = true,
                    isInitialLoading = it.episodes.isEmpty()
                )
            }

            try {
                val localFeed = db.feedDao().getFeedOnce(feedId)
                _uiState.update { state -> state.copy(feed = localFeed) }

                if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            isInitialLoading = false,
                            hasMore = false,
                            errorMessage = null
                        )
                    }
                    return@launch
                }

                feedRepositoryOrNull()?.refreshFeeds()

                val refreshedFeedBeforeEpisodes = db.feedDao().getFeedOnce(feedId)
                if (refreshedFeedBeforeEpisodes == null) {
                    _uiState.update {
                        it.copy(
                            feed = null,
                            episodes = emptyList(),
                            activePhoneDownloadEpisodeIds = emptySet(),
                            isRefreshing = false,
                            isInitialLoading = false,
                            hasMore = false,
                            errorMessage = "This podcast no longer exists on the server."
                        )
                    }
                    return@launch
                }

                val hasMore = episodeRepositoryOrNull()?.refreshEpisodesByFeed(feedId, limit = 100) ?: false

                val refreshedFeed = db.feedDao().getFeedOnce(feedId)
                if (refreshedFeed == null) {
                    _uiState.update {
                        it.copy(
                            feed = null,
                            episodes = emptyList(),
                            activePhoneDownloadEpisodeIds = emptySet(),
                            isRefreshing = false,
                            isInitialLoading = false,
                            hasMore = false,
                            errorMessage = "This podcast no longer exists on the server."
                        )
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        feed = refreshedFeed,
                        isRefreshing = false,
                        isInitialLoading = false,
                        hasMore = hasMore,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                Log.e("EpisodeListViewModel", "Failed to refresh episodes", e)
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        isInitialLoading = false,
                        errorMessage = "Failed to refresh: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    // Triggered by the "Load More" button at the bottom of the episode list.
    // Calculates the new limit as current episode count + 100, so each page
    // load adds another 100 episodes to the local cache.
    fun loadMore() {
        if (_uiState.value.isRefreshing || !_uiState.value.hasMore || CastCharmApp.isOfflineMode) return

        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val refreshedFeed = db.feedDao().getFeedOnce(feedId)
                if (refreshedFeed == null) {
                    _uiState.update {
                        it.copy(
                            feed = null,
                            episodes = emptyList(),
                            activePhoneDownloadEpisodeIds = emptySet(),
                            isRefreshing = false,
                            hasMore = false,
                            errorMessage = "This podcast no longer exists on the server."
                        )
                    }
                    return@launch
                }

                // The new limit expands the window by 100 each time. The server
                // returns limit+1 items if more exist, so hasMore stays true.
                val newLimit = _uiState.value.episodes.size + 100
                val hasMore = episodeRepositoryOrNull()?.refreshEpisodesByFeed(feedId, limit = newLimit) ?: false
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        hasMore = hasMore
                    )
                }
            } catch (e: Exception) {
                Log.e("EpisodeListViewModel", "Failed to load more episodes", e)
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = "Failed to load more: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun togglePlayed(episodeId: Int, currentState: Boolean) {
        viewModelScope.launch {
            val newState = !currentState
            try {
                val repo = episodeRepositoryOrNull()
                if (CastCharmApp.isOfflineMode || repo == null) {
                    db.episodeDao().updatePlayedStatus(
                        episodeId,
                        newState,
                        System.currentTimeMillis(),
                        pending = true
                    )
                } else {
                    repo.togglePlayed(episodeId, currentState)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to update: ${e.message}")
                }
            }
        }
    }

    fun downloadEpisode(episodeId: Int) {
        viewModelScope.launch {
            try {
                downloadScheduler.scheduleDownload(episodeId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to start download: ${e.message}")
                }
            }
        }
    }

    fun downloadToServer(episodeId: Int) {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) {
            _uiState.update {
                it.copy(errorMessage = "Server downloads are unavailable offline.")
            }
            return
        }

        viewModelScope.launch {
            try {
                CastCharmApp.apiClient.getApi().queueDownload(episodeId)
                refreshEpisodes()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to queue server download: ${e.message}")
                }
            }
        }
    }

    fun toggleEpisodeSelection(episodeId: Int) {
        val current = _uiState.value.selectedEpisodes
        _uiState.update {
            it.copy(
                selectedEpisodes = if (episodeId in current) current - episodeId else current + episodeId
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedEpisodes = emptySet()) }
    }

    fun selectAll() {
        _uiState.update {
            it.copy(selectedEpisodes = it.episodes.map { episode -> episode.id }.toSet())
        }
    }

    fun downloadSelected() {
        viewModelScope.launch {
            _uiState.value.selectedEpisodes.forEach { id ->
                downloadScheduler.scheduleDownload(id)
            }
            clearSelection()
        }
    }

    fun toggleHidden(episodeId: Int, currentlyHidden: Boolean) {
        viewModelScope.launch {
            try {
                val repo = episodeRepositoryOrNull()
                if (CastCharmApp.isOfflineMode || repo == null) {
                    _uiState.update {
                        it.copy(errorMessage = "This action is unavailable offline.")
                    }
                } else {
                    repo.toggleHidden(episodeId, currentlyHidden)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to update: ${e.message}")
                }
            }
        }
    }
}