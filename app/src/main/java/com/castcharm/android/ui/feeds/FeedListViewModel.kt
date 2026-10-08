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
import com.castcharm.android.data.api.FolderConflict
import com.castcharm.android.data.api.parseApiError
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
    // True only while a refresh the USER started by pulling down is in flight.
    // PullToRefreshBox is driven by this, never by isRefreshing: an automatic load
    // on arriving at the tab would otherwise animate the pull indicator and make it
    // look as though the user had swiped when they hadn't.
    val isPullRefreshing: Boolean = false,
    val isSyncing: Boolean = false,
    val isAddingFeed: Boolean = false,
    // Why the last add attempt failed. Shown INSIDE the add-feed dialog: an error
    // routed to the snackbar renders behind the dialog, where the user cannot read
    // it, and the dialog stays open on failure so they can correct the URL.
    val addFeedError: String? = null,
    // Set when the server refuses because the target folder already holds another
    // podcast's files. The dialog turns into a prompt so the user decides: pick a
    // different folder name, or use the existing one deliberately.
    val addFeedFolderConflict: FolderConflict? = null,
    // Feed IDs picked via long-press.
    val selectedFeeds: Set<Int> = emptySet(),
    // Tracked separately from selectedFeeds being non-empty: an empty selection is
    // a valid place to sit. "Select none" and unticking the last card leave the
    // action bar up rather than dropping the user back to browsing and making them
    // long-press all over again. Exits are the X in the top bar, back, or a
    // finished bulk action.
    val feedSelectionMode: Boolean = false,
    // A bulk operation is running; the action bar disables itself so a second tap
    // can't fire the same server-side work twice.
    val bulkActionInFlight: Boolean = false,
    // Feeds whose server-side deletion is in flight. Their cards show a bin overlay
    // and stop responding to taps, mirroring the web UI: the user is returned to
    // browsing immediately rather than being held in selection mode, and progress is
    // reported on the affected cards instead.
    val deletingFeeds: Set<Int> = emptySet(),
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

    // ---- Multi-select -------------------------------------------------------

    // Also the entry point into multi-select: the first long-press lands here.
    fun toggleFeedSelection(feedId: Int) {
        val current = _uiState.value.selectedFeeds
        _uiState.update {
            it.copy(
                selectedFeeds = if (feedId in current) current - feedId else current + feedId,
                feedSelectionMode = true
            )
        }
    }

    /** Empties the selection but stays in multi-select ("Select none"). */
    fun clearFeedSelection() {
        _uiState.update { it.copy(selectedFeeds = emptySet()) }
    }

    /** Leaves multi-select entirely — the X in the top bar, back, or a finished batch. */
    fun exitFeedSelectionMode() {
        _uiState.update { it.copy(selectedFeeds = emptySet(), feedSelectionMode = false) }
    }

    fun selectAllFeeds() {
        _uiState.update { state ->
            // Feeds already being deleted are not selectable — their cards are
            // inert, so including them here would produce a selection the user
            // cannot see or undo.
            state.copy(
                selectedFeeds = state.feeds
                    .map { it.id }
                    .filterNot { it in state.deletingFeeds }
                    .toSet(),
                feedSelectionMode = true
            )
        }
    }

    /**
     * Runs [action] once per selected feed, then reports how many succeeded.
     *
     * Selection is dropped up front, not at the end: the user goes straight back to
     * browsing and the work reports itself in place. Holding them in selection mode
     * until a multi-feed server round-trip finishes leaves the screen stuck in a
     * state whose actions no longer apply to anything.
     *
     * With [markDeleting] the affected cards are flagged for the duration so they
     * can show a bin overlay and refuse input, matching the web UI.
     *
     * Each feed is isolated in its own runCatching: one failure must not abandon the
     * rest of the batch, and the user is told the real count rather than a blanket
     * "done".
     */
    private fun runBulk(
        verb: String,
        markDeleting: Boolean = false,
        successText: (feeds: Int, itemTotal: Int) -> String,
        action: suspend (Int) -> Int,
    ) {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return
        if (_uiState.value.bulkActionInFlight) return
        val ids = _uiState.value.selectedFeeds.toList()
        if (ids.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    bulkActionInFlight = true,
                    selectedFeeds = emptySet(),
                    feedSelectionMode = false,
                    deletingFeeds = if (markDeleting) it.deletingFeeds + ids else it.deletingFeeds,
                )
            }

            try {
            var failed = 0
            var itemTotal = 0
            for (id in ids) {
                runCatching { action(id) }
                    .onSuccess { count -> itemTotal += count }
                    .onFailure { e ->
                        failed++
                        Log.e("FeedListViewModel", "Bulk $verb failed for feed $id", e)
                        // Release this card immediately so a feed that survived the
                        // failed delete becomes usable again. Successful ones stay
                        // flagged until the refresh below drops them from the list,
                        // otherwise the card would flash back to normal for a moment
                        // before disappearing.
                        if (markDeleting) {
                            _uiState.update { s -> s.copy(deletingFeeds = s.deletingFeeds - id) }
                        }
                    }
            }

            // Refresh BEFORE reporting. awaitRefreshFeeds() clears errorMessage as
            // part of its own success path, so posting the batch result first meant
            // a failed bulk action was wiped before the snackbar could read it —
            // the button looked like it did nothing.
            awaitRefreshFeeds()
            if (markDeleting) {
                _uiState.update { it.copy(deletingFeeds = it.deletingFeeds - ids.toSet()) }
            }

            val ok = ids.size - failed
            _uiState.update {
                it.copy(
                    successMessage = if (failed == 0) successText(ok, itemTotal) else null,
                    errorMessage = if (failed > 0) {
                        "$verb failed for $failed of ${ids.size} podcast${if (ids.size == 1) "" else "s"}"
                    } else {
                        null
                    }
                )
            }
            } finally {
                // Always released. If this coroutine is cancelled part-way — the user
                // switching tabs, say — a stuck flag would make every later bulk
                // action return early at the guard above and silently do nothing.
                _uiState.update { it.copy(bulkActionInFlight = false) }
            }
        }
    }

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

    fun syncSelectedFeeds() = runBulk(
        verb = "Sync",
        successText = { feeds, _ -> "Sync started for ${plural(feeds, "podcast")}" },
    ) { id ->
        CastCharmApp.apiClient.getApi().refreshFeed(id)
        0
    }

    /**
     * Deletes the selected podcasts FROM THE SERVER. This is not a local operation:
     * the feed and its episode records are removed for every client, and when
     * [deleteFiles] is set the server also erases the downloaded audio from its own
     * disk. The caller is responsible for confirming this first.
     */
    fun deleteSelectedFeeds(deleteFiles: Boolean) = runBulk(
        verb = "Delete",
        markDeleting = true,
        successText = { feeds, _ -> "Deleted ${plural(feeds, "podcast")} from the server" },
    ) { id ->
        CastCharmApp.apiClient.getApi().deleteFeed(id, deleteFiles = deleteFiles)
        0
    }

    fun clearAddFeedError() {
        _uiState.update { it.copy(addFeedError = null) }
    }

    fun clearAddFeedFolderConflict() {
        _uiState.update { it.copy(addFeedFolderConflict = null) }
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

    /**
     * Subscribes the SERVER to a feed.
     *
     * [downloadAll] sets download_all_on_first_sync on the new feed, which the server
     * acts on once, after the initial sync, by queueing every existing episode into
     * its own download queue. It is a property of the subscription rather than a
     * command, and it concerns the back catalogue only — new episodes are picked up
     * by auto_download_new regardless.
     */
    fun addFeed(
        url: String,
        downloadAll: Boolean = false,
        folderNameOverride: String? = null,
        allowExistingFolder: Boolean = false,
        onSuccess: () -> Unit,
    ) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isAddingFeed = true, addFeedError = null, addFeedFolderConflict = null)
            }
            runCatching {
                CastCharmApp.apiClient.getApi().addFeed(
                    AddFeedRequest(
                        url = trimmed,
                        download_all = downloadAll,
                        title_override = folderNameOverride?.trim()?.takeIf { it.isNotBlank() },
                        allow_existing_folder = allowExistingFolder,
                    )
                )
            }
                .onSuccess { feed ->
                    val name = feed.title ?: trimmed
                    refreshFeeds()
                    _uiState.update {
                        it.copy(
                            isAddingFeed = false,
                            addFeedError = null,
                            addFeedFolderConflict = null,
                            successMessage = if (downloadAll) {
                                "Added \"$name\" — the back catalogue will download after the first sync"
                            } else {
                                "Added \"$name\""
                            }
                        )
                    }
                    onSuccess()
                }
                .onFailure { e ->
                    Log.e("FeedListViewModel", "Add feed failed", e)
                    // The server explains itself properly here — a duplicate URL and
                    // a clashing podcast name are both 409s with distinct reasons —
                    // so surface its message rather than the bare status line.
                    // Parsed once — the error body is a one-shot stream.
                    val parsed = e.parseApiError()
                    _uiState.update {
                        it.copy(
                            isAddingFeed = false,
                            // Keep the existing prompt open if the retry failed for
                            // some OTHER reason — e.g. the alternative folder name
                            // collides with a live podcast. Clearing it here would
                            // drop the user back to the add dialog, losing the URL
                            // they typed and the prompt they were answering.
                            addFeedFolderConflict = parsed.folderConflict
                                ?: it.addFeedFolderConflict,
                            // A folder clash is a prompt in its own right, so it is
                            // not also shown as red text.
                            addFeedError = if (parsed.folderConflict != null) null else {
                                parsed.message
                                    ?: "Could not add feed: ${e.localizedMessage ?: "unknown error"}"
                            }
                        )
                    }
                }
        }
    }

    fun refreshFeeds(fromPull: Boolean = false) {
        viewModelScope.launch { awaitRefreshFeeds(fromPull) }
    }

    // Suspending body of refreshFeeds, so callers that need the refresh to have
    // FINISHED can await it. refreshFeeds() itself only launches and returns
    // immediately, which meant a bulk action's result message was posted first and
    // then wiped by this function's own `errorMessage = null` moments later — the
    // action appeared to do nothing at all.
    private suspend fun awaitRefreshFeeds(fromPull: Boolean = false) {
        run {
            _uiState.update {
                it.copy(
                    isRefreshing = true,
                    isPullRefreshing = fromPull,
                    isInitialLoading = it.feeds.isEmpty()
                )
            }

            if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        isPullRefreshing = false,
                        isInitialLoading = false,
                        errorMessage = null
                    )
                }
                return@run
            }

            try {
                Log.d("FeedListViewModel", "Refreshing feeds from server...")
                repositoryOrNull()?.refreshFeeds()
                Log.d("FeedListViewModel", "Feeds refreshed successfully")
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        isPullRefreshing = false,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                Log.e("FeedListViewModel", "Failed to refresh feeds", e)
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        isPullRefreshing = false,
                        isInitialLoading = false,
                        errorMessage = "Failed to refresh feeds: ${e.localizedMessage}"
                    )
                }
            }
        }
    }
}