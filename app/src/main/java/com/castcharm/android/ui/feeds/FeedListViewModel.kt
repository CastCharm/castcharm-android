package com.castcharm.android.ui.feeds

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.entities.FeedEntity
import com.castcharm.android.data.repository.FeedRepository
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
    val errorMessage: String? = null
)

class FeedListViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(FeedListUiState())
    val uiState: StateFlow<FeedListUiState> = _uiState.asStateFlow()

    private val db = AppDatabase.getDatabase(CastCharmApp.instance)

    init {
        loadFeeds()
    }

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
            Log.d("FeedListViewModel", "Starting collection of feeds from DB")
            db.feedDao().getAllFeeds().collectLatest { feeds ->
                Log.d("FeedListViewModel", "Collected ${feeds.size} feeds from DB")
                _uiState.update {
                    it.copy(
                        feeds = feeds,
                        isInitialLoading = false
                    )
                }
            }
        }

        refreshFeeds()
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
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