package com.castcharm.android.ui.feeds

// FeedListViewModel drives the Podcasts tab. It:
//   - Continuously observes the feeds table via getAllFeeds() Flow so any server-side
//     change (new feed added from web UI, feed deleted) is reflected in real time.
//   - Immediately triggers a server refresh on init and on explicit pull-to-refresh.
//   - Provides a repositoryOrNull() guard so refresh is silently skipped while
//     offline or before ApiClient is initialized.

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.models.AddFeedRequest
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.entities.FeedEntity
import com.castcharm.android.data.repository.FeedRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedListUiState(
    val feeds: List<FeedEntity> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isSyncing: Boolean = false,
    val isAddingFeed: Boolean = false,
    val syncingFeedIds: Set<Int> = emptySet(),
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class FeedListViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(FeedListUiState())
    val uiState: StateFlow<FeedListUiState> = _uiState.asStateFlow()

    private val db = AppDatabase.getDatabase(CastCharmApp.instance)
    private var syncPollingJob: Job? = null

    init {
        loadFeeds()
    }

    override fun onCleared() {
        super.onCleared()
        syncPollingJob?.cancel()
    }

    // Returns null when the ApiClient hasn't been initialized yet (e.g., first-run
    // before server URL is configured). Callers that get null skip the network call.
    private fun repositoryOrNull(): FeedRepository? {
        return if (CastCharmApp.apiClient.isInitialized) {
            FeedRepository(
                CastCharmApp.apiClient.getApi(),
                db.feedDao()
            )
        } else {
            null
        }
    }

    private fun loadFeeds() {
        viewModelScope.launch {
            db.feedDao().getAllFeeds().collectLatest { feeds ->
                _uiState.update { it.copy(feeds = feeds, isInitialLoading = false) }
            }
        }
        // No refreshFeeds() here. The flow above shows cached feeds straight away,
        // and the server pull is triggered once by the screen's ON_RESUME observer
        // (OnScreenResumed in MainActivity). Calling it here too meant two
        // refreshes on every navigation to this tab, each flipping isRefreshing —
        // which is what made pull-to-refresh appear to double-trigger.
        checkInitialSyncStatus()
    }

    // On load, check whether a sync is already running server-side (e.g., the
    // periodic sync fired while the user was navigating) and start polling if so.
    private fun checkInitialSyncStatus() {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            val status = runCatching { CastCharmApp.apiClient.getApi().getStatus() }.getOrNull()
                ?: return@launch
            val ids = status.syncing_feed_ids.toSet()
            if (ids.isNotEmpty()) {
                _uiState.update { it.copy(syncingFeedIds = ids) }
                startSyncPolling()
            }
        }
    }

    // Polls GET /api/status every 3s while feeds are syncing. Stops when the
    // server reports no active syncs, then refreshes the feed list so updated
    // episode counts are reflected. A 2-minute hard timeout prevents eternal polling
    // if the server gets stuck.
    // First poll fires immediately so there's no gap between the API call returning
    // and sync state being reflected. Subsequent polls are delayed 3s.
    private fun startSyncPolling() {
        if (syncPollingJob?.isActive == true) return
        syncPollingJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + 2 * 60 * 1000L
            var firstPoll = true
            while (System.currentTimeMillis() < deadline) {
                if (firstPoll) firstPoll = false else delay(3_000)
                val status = runCatching { CastCharmApp.apiClient.getApi().getStatus() }.getOrNull()
                    ?: break
                val ids = status.syncing_feed_ids.toSet()
                _uiState.update { it.copy(isSyncing = false, syncingFeedIds = ids) }
                if (ids.isEmpty()) {
                    refreshFeeds()
                    break
                }
            }
            _uiState.update { it.copy(isSyncing = false, syncingFeedIds = emptySet()) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearSuccess() {
        _uiState.update { it.copy(successMessage = null) }
    }

    fun syncAllFeeds() {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            runCatching { CastCharmApp.apiClient.getApi().refreshAllFeeds() }
                .onSuccess {
                    // isSyncing stays true — startSyncPolling clears it on the first poll
                    // so there's no enabled gap between the API call and sync detection.
                    startSyncPolling()
                }
                .onFailure { e ->
                    Log.e("FeedListViewModel", "Sync all failed", e)
                    _uiState.update { it.copy(isSyncing = false, errorMessage = "Sync failed: ${e.localizedMessage}") }
                }
        }
    }

    fun addFeed(url: String, onSuccess: () -> Unit) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            _uiState.update { it.copy(isAddingFeed = true) }
            runCatching { CastCharmApp.apiClient.getApi().addFeed(AddFeedRequest(url = trimmed)) }
                .onSuccess { feed ->
                    refreshFeeds()
                    _uiState.update { it.copy(isAddingFeed = false, successMessage = "Added \"${feed.title ?: trimmed}\"") }
                    onSuccess()
                }
                .onFailure { e ->
                    Log.e("FeedListViewModel", "Add feed failed", e)
                    _uiState.update { it.copy(isAddingFeed = false, errorMessage = "Could not add feed: ${e.localizedMessage}") }
                }
        }
    }

    fun refreshFeeds() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRefreshing = true,
                    isInitialLoading = it.feeds.isEmpty()
                )
            }

            if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        isInitialLoading = false,
                        errorMessage = null
                    )
                }
                return@launch
            }

            try {
                Log.d("FeedListViewModel", "Refreshing feeds from server...")
                repositoryOrNull()?.refreshFeeds()
                Log.d("FeedListViewModel", "Feeds refreshed successfully")
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                Log.e("FeedListViewModel", "Failed to refresh feeds", e)
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        isInitialLoading = false,
                        errorMessage = "Failed to refresh feeds: ${e.localizedMessage}"
                    )
                }
            }
        }
    }
}