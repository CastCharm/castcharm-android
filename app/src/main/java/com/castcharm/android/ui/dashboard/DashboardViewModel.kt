package com.castcharm.android.ui.dashboard

// DashboardViewModel populates the home screen with multiple independent data sections:
//   - Stats (total podcasts / feeds from the server)
//   - Feed health (feeds with last_error set)
//   - Continue Listening (in-progress episodes, live via Flow)
//   - Newest Episodes (recently server-downloaded episodes)
//   - Suggestion Buckets (bucketed by duration: < 15 min, 15–45 min, etc.)
//   - Top Backlog (feeds sorted by unplayed count)
//
// Each section has its own loading flag so the UI can show skeletons for each
// section independently while data arrives. sawAnyFailure tracks whether any
// individual section failed so a single toast is shown at the end rather than
// per-section errors.
//
// Offline mode: skip all network calls and populate sections from the local DB.
// Continue Listening is still live via Flow even offline.

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.db.entities.FeedEntity
import com.castcharm.android.data.repository.EpisodeRepository
import com.castcharm.android.data.repository.FeedRepository
import com.castcharm.android.data.repository.toEntity
import com.castcharm.android.download.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardItem(
    val episode: EpisodeEntity,
    val feed: FeedEntity?
)

data class SuggestionBucket(
    val label: String,
    val items: List<DashboardItem>
)

data class DashboardUiState(
    val podcastsTotal: Int = 0,
    val feedsTotal: Int = 0,
    val deviceStorageBytes: Long = 0,
    val deviceQuotaBytes: Long = 0,
    val feedErrors: List<FeedEntity> = emptyList(),
    val continueListening: List<DashboardItem> = emptyList(),
    val newestEpisodes: List<DashboardItem> = emptyList(),
    val suggestionBuckets: List<SuggestionBucket> = emptyList(),
    val topBacklog: List<FeedEntity> = emptyList(),
    val statsLoading: Boolean = true,
    val feedHealthLoading: Boolean = true,
    val continueListeningLoading: Boolean = true,
    val newestLoading: Boolean = true,
    val suggestionsLoading: Boolean = true,
    val backlogLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

class DashboardViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val db = AppDatabase.getDatabase(CastCharmApp.instance)
    private val storageManager = StorageManager(CastCharmApp.instance)

    private val _feedMap = MutableStateFlow<Map<Int, FeedEntity>>(emptyMap())

    init {
        viewModelScope.launch {
            combine(continueListeningFlow(), _feedMap) { eps, feedMap ->
                eps.map { ep -> DashboardItem(ep, feedMap[ep.feed_id]) }
            }.collect { items ->
                _uiState.update {
                    it.copy(
                        continueListening = items,
                        continueListeningLoading = false
                    )
                }
            }
        }

        // Deliberately no refresh() here. The DB flow above already populates the
        // screen from cache immediately; the server pull is triggered once, by the
        // screen's ON_RESUME observer. Doing both meant two overlapping refreshes
        // on every navigation to this tab, each toggling isRefreshing, which is
        // what made pull-to-refresh look like it fired twice.
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

    // Returns the repository's Flow if online (same underlying query), or falls
    // back to the DAO directly if offline or before the ApiClient is initialized.
    private fun continueListeningFlow() =
        episodeRepositoryOrNull()?.getContinueListening(5)
            ?: db.episodeDao().getContinueListening(5)

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun reloadContinueListeningFromDb() {
        viewModelScope.launch {
            val eps = db.episodeDao().getContinueListening(5).first()
            val feedMap = _feedMap.value
            _uiState.update {
                it.copy(
                    continueListening = eps.map { ep -> DashboardItem(ep, feedMap[ep.feed_id]) },
                    continueListeningLoading = false
                )
            }
        }
    }

    fun refreshSuggestions() {
        viewModelScope.launch {
            _uiState.update { it.copy(suggestionsLoading = true) }

            if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) {
                _uiState.update { it.copy(suggestionsLoading = false) }
                return@launch
            }

            try {
                val suggestions = CastCharmApp.apiClient.getApi().getSuggestions()
                val feedMap = _feedMap.value

                fun EpisodeEntity.asDashboardItem(): DashboardItem {
                    return DashboardItem(this, feedMap[this.feed_id])
                }

                val buckets = listOf(
                    SuggestionBucket("< 15 min", suggestions.short.map { it.toEntity(null).asDashboardItem() }),
                    SuggestionBucket("15–45 min", suggestions.medium.map { it.toEntity(null).asDashboardItem() }),
                    SuggestionBucket("45–90 min", suggestions.long.map { it.toEntity(null).asDashboardItem() }),
                    SuggestionBucket("90+ min", suggestions.extra_long.map { it.toEntity(null).asDashboardItem() })
                ).filter { bucket -> bucket.items.isNotEmpty() }

                _uiState.update {
                    it.copy(
                        suggestionBuckets = buckets,
                        suggestionsLoading = false
                    )
                }
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "Failed to refresh suggestions", e)
                _uiState.update {
                    it.copy(
                        suggestionsLoading = false,
                        errorMessage = "Suggestions unavailable right now."
                    )
                }
            }
        }
    }

    fun refresh() {
        // Belt-and-braces alongside the caller's guard: every other ViewModel
        // refuses to hit the network while offline, and this one used to be the
        // exception. In offline mode it would set isRefreshing, fire a full set of
        // API calls at an unreachable server on every resume, and wait for them all
        // to time out — a real contributor to the app feeling sluggish after
        // losing connectivity.
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRefreshing = true,
                    errorMessage = null,
                    statsLoading = it.podcastsTotal == 0 && it.feedsTotal == 0,
                    feedHealthLoading = it.feedErrors.isEmpty(),
                    continueListeningLoading = it.continueListening.isEmpty(),
                    newestLoading = it.newestEpisodes.isEmpty(),
                    suggestionsLoading = it.suggestionBuckets.isEmpty(),
                    backlogLoading = it.topBacklog.isEmpty()
                )
            }

            var sawAnyFailure = false

            try {
                val localFeeds = db.feedDao().getAllFeeds().first()
                val localFeedMap = localFeeds.associateBy { it.id }
                _feedMap.value = localFeedMap

                val deviceStorageBytes = storageManager.getTotalUsedBytes()
                val quotaBytes = storageManager.getQuotaBytes()

                _uiState.update {
                    it.copy(
                        deviceStorageBytes = deviceStorageBytes,
                        deviceQuotaBytes = quotaBytes
                    )
                }

                if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) {
                    val continueEpisodes = db.episodeDao().getContinueListening(5).first()
                    val downloadedEpisodes = db.episodeDao()
                        .getDownloadedEpisodesOnce()
                        .sortedByDescending { it.published_at }
                        .take(5)

                    _uiState.update {
                        it.copy(
                            podcastsTotal = localFeeds.size,
                            feedsTotal = localFeeds.size,
                            feedErrors = localFeeds.filter { feed -> feed.last_error != null }.take(6),
                            topBacklog = localFeeds
                                .filter { feed -> feed.unplayed_count > 0 }
                                .sortedByDescending { feed -> feed.unplayed_count }
                                .take(6),
                            continueListening = continueEpisodes.map { ep ->
                                DashboardItem(ep, localFeedMap[ep.feed_id])
                            },
                            newestEpisodes = downloadedEpisodes.map { ep ->
                                DashboardItem(ep, localFeedMap[ep.feed_id])
                            },
                            suggestionBuckets = emptyList(),
                            statsLoading = false,
                            feedHealthLoading = false,
                            continueListeningLoading = false,
                            newestLoading = false,
                            suggestionsLoading = false,
                            backlogLoading = false,
                            isRefreshing = false,
                            errorMessage = null
                        )
                    }
                    return@launch
                }

                try {
                    val api = CastCharmApp.apiClient.getApi()
                    val status = api.getStatus()
                    feedRepositoryOrNull()?.refreshFeeds()
                    val feeds = db.feedDao().getAllFeeds().first()

                    val feedMap = feeds.associateBy { it.id }
                    _feedMap.value = feedMap

                    _uiState.update {
                        it.copy(
                            podcastsTotal = status.podcasts_total,
                            feedsTotal = status.feeds_total,
                            feedErrors = feeds.filter { feed -> feed.last_error != null }.take(6),
                            topBacklog = feeds
                                .filter { feed -> feed.unplayed_count > 0 }
                                .sortedByDescending { feed -> feed.unplayed_count }
                                .take(6),
                            statsLoading = false,
                            feedHealthLoading = false,
                            backlogLoading = false
                        )
                    }
                } catch (e: Exception) {
                    sawAnyFailure = true
                    Log.e("DashboardViewModel", "Failed to refresh stats/feeds", e)
                    _uiState.update {
                        it.copy(
                            podcastsTotal = localFeeds.size,
                            feedsTotal = localFeeds.size,
                            feedErrors = localFeeds.filter { feed -> feed.last_error != null }.take(6),
                            topBacklog = localFeeds
                                .filter { feed -> feed.unplayed_count > 0 }
                                .sortedByDescending { feed -> feed.unplayed_count }
                                .take(6),
                            statsLoading = false,
                            feedHealthLoading = false,
                            backlogLoading = false
                        )
                    }
                }

                try {
                    episodeRepositoryOrNull()?.fetchAndCacheContinueListening()
                    _uiState.update { it.copy(continueListeningLoading = false) }
                } catch (e: Exception) {
                    sawAnyFailure = true
                    Log.e("DashboardViewModel", "Failed to refresh continue listening", e)
                    _uiState.update { it.copy(continueListeningLoading = false) }
                }

                try {
                    val newestRaw = CastCharmApp.apiClient.getApi().getAllEpisodes(status = "downloaded", limit = 20)
                    val feedMap = _feedMap.value

                    fun EpisodeEntity.asDashboardItem(): DashboardItem {
                        return DashboardItem(this, feedMap[this.feed_id])
                    }

                    val newestEntities = newestRaw
                        .sortedByDescending { it.published_at }
                        .take(5)
                        .map { it.toEntity(db.episodeDao().getEpisodeOnce(it.id)) }

                    _uiState.update {
                        it.copy(
                            newestEpisodes = newestEntities.map { entity -> entity.asDashboardItem() },
                            newestLoading = false
                        )
                    }
                } catch (e: Exception) {
                    sawAnyFailure = true
                    Log.e("DashboardViewModel", "Failed to refresh newest episodes", e)
                    _uiState.update { it.copy(newestLoading = false) }
                }

                try {
                    val suggestions = CastCharmApp.apiClient.getApi().getSuggestions()
                    val feedMap = _feedMap.value

                    fun EpisodeEntity.asDashboardItem(): DashboardItem {
                        return DashboardItem(this, feedMap[this.feed_id])
                    }

                    val buckets = listOf(
                        SuggestionBucket("< 15 min", suggestions.short.map { it.toEntity(null).asDashboardItem() }),
                        SuggestionBucket("15–45 min", suggestions.medium.map { it.toEntity(null).asDashboardItem() }),
                        SuggestionBucket("45–90 min", suggestions.long.map { it.toEntity(null).asDashboardItem() }),
                        SuggestionBucket("90+ min", suggestions.extra_long.map { it.toEntity(null).asDashboardItem() })
                    ).filter { bucket -> bucket.items.isNotEmpty() }

                    _uiState.update {
                        it.copy(
                            suggestionBuckets = buckets,
                            suggestionsLoading = false
                        )
                    }
                } catch (e: Exception) {
                    sawAnyFailure = true
                    Log.e("DashboardViewModel", "Failed to refresh suggestions", e)
                    _uiState.update { it.copy(suggestionsLoading = false) }
                }

                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = if (sawAnyFailure) {
                            "Some dashboard sections failed to refresh."
                        } else {
                            null
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "Unexpected dashboard refresh failure", e)
                _uiState.update {
                    it.copy(
                        statsLoading = false,
                        feedHealthLoading = false,
                        continueListeningLoading = false,
                        newestLoading = false,
                        suggestionsLoading = false,
                        backlogLoading = false,
                        isRefreshing = false,
                        errorMessage = "Dashboard unavailable right now."
                    )
                }
            }
        }
    }
}