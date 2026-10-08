package com.castcharm.android.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.models.EpisodeOut
import com.castcharm.android.data.api.models.parseServerDateTime
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.ui.shared_components.AppTopBarTitle
import com.castcharm.android.ui.shared_components.formatDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    data class UiState(
        val query: String = "",
        val results: List<EpisodeOut> = emptyList(),
        val isPending: Boolean = false,
        val isLocalFallback: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val db by lazy { AppDatabase.getDatabase(CastCharmApp.instance) }
    private var searchJob: Job? = null

    fun setQuery(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) {
            _uiState.update {
                it.copy(
                    query = q,
                    results = emptyList(),
                    isPending = false,
                    isLocalFallback = false,
                    error = null,
                )
            }
            return
        }
        // Mark pending immediately so the UI reacts to the keypress, not the API response
        _uiState.update { it.copy(query = q, isPending = true, error = null) }
        searchJob = viewModelScope.launch {
            delay(400)
            val offline = CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized
            if (!offline) {
                try {
                    val results = CastCharmApp.apiClient.getApi()
                        .getAllEpisodes(search = q, limit = 40)
                    _uiState.update {
                        it.copy(
                            results = results,
                            isPending = false,
                            isLocalFallback = false,
                        )
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Fall through to local search rather than surfacing a
                    // failure — a stale local hit is more useful than nothing.
                }
            }
            runLocalSearch(q)
        }
    }

    private suspend fun runLocalSearch(q: String) {
        try {
            val term = "%${q.trim()}%"
            val feedTitleById = db.feedDao().getFeedOnceAll().associate { it.id to it.title }
            val local = db.episodeDao().searchEpisodesLocal(term = term, limit = 40)
            _uiState.update {
                it.copy(
                    results = local.map { entity -> entity.toEpisodeOut(feedTitleById[entity.feed_id]) },
                    isPending = false,
                    isLocalFallback = true,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _uiState.update {
                it.copy(
                    isPending = false,
                    error = "Search failed — try again",
                )
            }
        }
    }
}

// Renders a local EpisodeEntity as an EpisodeOut for the shared search row.
// Only the fields the row actually reads are populated meaningfully; the rest
// use safe defaults so a partial local row still renders cleanly.
private val serverDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private fun EpisodeEntity.toEpisodeOut(feedTitle: String?): EpisodeOut {
    return EpisodeOut(
        id = id,
        feed_id = feed_id,
        guid = guid,
        title = title,
        enclosure_url = enclosure_url,
        enclosure_type = enclosure_type,
        enclosure_length = enclosure_length,
        published_at = published_at?.let { serverDateFormat.format(Date(it)) },
        description = description,
        duration = duration?.toString(),
        episode_number = episode_number,
        season_number = season_number,
        episode_image_url = episode_image_url,
        custom_image_url = custom_image_url,
        author = author,
        link = link,
        hidden = hidden,
        played = played,
        play_position_seconds = play_position_seconds,
        last_played_at = last_played_at?.let { serverDateFormat.format(Date(it)) },
        status = if (local_path != null) "downloaded" else status,
        file_path = null,
        file_size = null,
        download_progress = download_progress,
        seq_number = seq_number,
        created_at = serverDateFormat.format(Date(created_at)),
        feed_image_url = feed_image_url,
        feed_title = feedTitle,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigateToEpisode: (feedId: Int, episodeId: Int) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: SearchViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { AppTopBarTitle("Search") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = { viewModel.setQuery(it) },
                placeholder = { Text("Search episodes…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    when {
                        uiState.isPending -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        uiState.query.isNotEmpty() -> IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester)
            )

            when {
                uiState.results.isNotEmpty() -> {
                    // Keep results visible even while a new query is pending
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (uiState.isLocalFallback) {
                            Text(
                                text = "Showing offline results from episodes already on this device.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(uiState.results, key = { it.id }) { ep ->
                                SearchResultRow(
                                    episode = ep,
                                    onClick = { onNavigateToEpisode(ep.feed_id, ep.id) }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }

                uiState.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            uiState.error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                uiState.query.isNotBlank() && !uiState.isPending -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No results for \"${uiState.query}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(episode: EpisodeOut, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = episode.title ?: "Untitled",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!episode.feed_title.isNullOrBlank()) {
                Text(
                    text = episode.feed_title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            val dateStr = formatDate(parseServerDateTime(episode.published_at))
            if (dateStr.isNotEmpty()) {
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (episode.status == "downloaded") {
                Text(
                    text = "Downloaded",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}
