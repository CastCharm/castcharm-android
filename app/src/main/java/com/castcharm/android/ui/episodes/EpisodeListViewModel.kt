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
import com.castcharm.android.data.api.models.PlayerPlayRequest
import com.castcharm.android.download.DownloadScheduler
import com.castcharm.android.download.StorageManager
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
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val hasMore: Boolean = false,
    val selectedEpisodes: Set<Int> = emptySet(),
    // Tracked separately from selectedEpisodes being non-empty. An empty
    // selection is a legitimate state to sit in — "Select none" and unticking
    // the last row should leave the user in multi-select with the bar still up,
    // not silently kick them back to browsing and force another long-press.
    // Leaving multi-select is an explicit act: the X in the top bar, or
    // finishing a bulk action.
    val selectionMode: Boolean = false,
    val playlistMemberEpisodeIds: Set<Int> = emptySet()
)

class EpisodeListViewModel(private val feedId: Int) : ViewModel() {
    private val _uiState = MutableStateFlow(EpisodeListUiState())
    val uiState: StateFlow<EpisodeListUiState> = _uiState.asStateFlow()

    private val db = AppDatabase.getDatabase(CastCharmApp.instance)
    private val downloadScheduler = DownloadScheduler(CastCharmApp.instance)
    private val storageManager = StorageManager(CastCharmApp.instance)

    init {
        // Sets up DB observation only. The server pull is triggered once, by the
        // screen's ON_RESUME observer (OnScreenResumed in MainActivity), so that
        // arriving at this screen produces exactly one refresh.
        observeLocalData()
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

    private fun observeLocalData() {
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

        // Show the cached feed header straight away; refresh() replaces it with
        // server data moments later.
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(feed = db.feedDao().getFeedOnce(feedId)) }
            } catch (e: Exception) {
                Log.e("EpisodeListViewModel", "Failed to load feed info", e)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Pull this feed's data from the server. Called by the screen's ON_RESUME
     * observer, so it runs on first arrival and again whenever the user comes back
     * to the screen. Distinct from syncFeed(), which asks the server to re-read the
     * podcast's RSS feed and is wired to pull-to-refresh.
     */
    fun refresh() {
        refreshEpisodes()
        refreshPlaylistMemberships()
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
                            isSyncing = false,
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
                        isSyncing = false,
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
                        isSyncing = false,
                        isInitialLoading = false,
                        errorMessage = "Failed to refresh: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    // Tells the server to re-fetch this feed's RSS, then refreshes the local episode
    // list. isSyncing stays true through the subsequent refreshEpisodes() call so the
    // spinner remains visible without a gap.
    fun syncFeed() {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            runCatching { CastCharmApp.apiClient.getApi().refreshFeed(feedId) }
                .onSuccess { refreshEpisodes() }
                .onFailure { e ->
                    Log.e("EpisodeListViewModel", "Sync feed failed", e)
                    _uiState.update { it.copy(isSyncing = false, errorMessage = "Sync failed: ${e.localizedMessage}") }
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

    // Also the entry point into multi-select: the first long-press lands here.
    fun toggleEpisodeSelection(episodeId: Int) {
        val current = _uiState.value.selectedEpisodes
        _uiState.update {
            it.copy(
                selectedEpisodes = if (episodeId in current) current - episodeId else current + episodeId,
                selectionMode = true
            )
        }
    }

    /** Empties the selection but stays in multi-select ("Select none"). */
    fun clearSelection() {
        _uiState.update { it.copy(selectedEpisodes = emptySet()) }
    }

    /** Leaves multi-select entirely — the X in the top bar, or a finished batch. */
    fun exitSelectionMode() {
        _uiState.update { it.copy(selectedEpisodes = emptySet(), selectionMode = false) }
    }

    fun selectAll() {
        _uiState.update {
            it.copy(
                selectedEpisodes = it.episodes.map { episode -> episode.id }.toSet(),
                selectionMode = true
            )
        }
    }

    fun downloadSelected() {
        viewModelScope.launch {
            _uiState.value.selectedEpisodes.forEach { id ->
                downloadScheduler.scheduleDownload(id)
            }
            exitSelectionMode()
        }
    }

    // Applies the played state to every currently-selected episode. Reuses the
    // existing togglePlayed() path per-episode so pending/offline handling and
    // server sync behave identically to a single toggle.
    fun markSelectedPlayed(played: Boolean) {
        viewModelScope.launch {
            val ids = _uiState.value.selectedEpisodes
            val currentEpisodes = _uiState.value.episodes.associateBy { it.id }
            ids.forEach { id ->
                val current = currentEpisodes[id]?.played ?: false
                if (current != played) {
                    togglePlayed(id, current)
                }
            }
            exitSelectionMode()
        }
    }

    // Removes the on-disk file for every currently-selected episode that has
    // one, then clears the selection. Episodes that aren't downloaded are
    // silently skipped inside deleteLocalFile(). This affects the phone only —
    // the episode remains available on the server.
    fun deleteSelectedDownloads() {
        viewModelScope.launch {
            _uiState.value.selectedEpisodes.forEach { id ->
                runCatching { storageManager.deleteLocalFile(id) }
            }
            exitSelectionMode()
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

    fun refreshPlaylistMemberships() {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            try {
                val result = CastCharmApp.apiClient.getApi().getFeedPlaylistMemberships(feedId)
                _uiState.update { it.copy(playlistMemberEpisodeIds = result.episode_ids.toSet()) }
            } catch (_: Exception) {
                // Non-critical — playlist membership badge is best-effort
            }
        }
    }

    fun onPlaylistMembershipChanged(episodeId: Int, isInPlaylist: Boolean) {
        _uiState.update { state ->
            val updated = if (isInPlaylist) {
                state.playlistMemberEpisodeIds + episodeId
            } else {
                state.playlistMemberEpisodeIds - episodeId
            }
            state.copy(playlistMemberEpisodeIds = updated)
        }
    }

    fun playFeed(onEpisodeIdReady: (Int) -> Unit) {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            try {
                val state = CastCharmApp.apiClient.getApi().playerPlay(
                    PlayerPlayRequest(context_type = "feed", context_id = feedId, context_filter = "unplayed")
                )
                val episodeId = state.current_episode?.id
                if (episodeId != null) {
                    onEpisodeIdReady(episodeId)
                } else {
                    _uiState.update { it.copy(errorMessage = "No unplayed downloaded episodes to play") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to play feed: ${e.localizedMessage}") }
            }
        }
    }
}