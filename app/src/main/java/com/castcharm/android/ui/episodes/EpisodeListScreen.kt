@file:OptIn(ExperimentalMaterial3Api::class)
// EpisodeListScreen shows the episodes for a single feed. It uses EpisodeCard
// from shared_components for each row, which handles play/download/played actions
// and the expandable description block.
//
// Two top-bar modes:
//   1. Normal: feed title + art, refresh icon, reconnect icon (if offline).
//   2. Selection: episode count badge, Select All, and download-selected button.
//
// "Load More" button appears at the bottom of the list when hasMore=true
// (server has more episodes beyond the current local cache limit).
//
// Offline mode: hides server-download actions, shows OfflineModePanel if the
// feed has no cached episodes at all.

package com.castcharm.android.ui.episodes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.castcharm.android.CastCharmApp
import com.castcharm.android.ui.playlists.AddToPlaylistSheet
import com.castcharm.android.ui.shared_components.AppTopBarTitle
import com.castcharm.android.ui.shared_components.ConsumeSnackbarMessage
import com.castcharm.android.ui.shared_components.EpisodeCard
import com.castcharm.android.ui.shared_components.EpisodeCardSkeleton
import com.castcharm.android.ui.shared_components.EpisodeDownloadActionOverride
import com.castcharm.android.ui.shared_components.OfflineModePanel
import com.castcharm.android.ui.shared_components.PlaceholderArtwork
import com.castcharm.android.ui.shared_components.ReconnectIconButton
import com.castcharm.android.ui.shared_components.stripHtml

// Client-side visibility filter applied to the loaded episode list. The list of
// episodes itself is not re-fetched — filtering just narrows what the LazyColumn
// renders. The selection is intentionally not persisted across visits: episode
// screens are visited transiently and a "sticky" filter is more surprising than
// helpful.
private enum class EpisodeFilter(val label: String) {
    ALL("All"),
    UNPLAYED("Unplayed"),
    DOWNLOADED("Downloaded"),
    IN_PROGRESS("In progress"),
}

@Composable
fun EpisodeListScreen(
    feedId: Int,
    viewModel: EpisodeListViewModel,
    highlightEpisodeId: Int? = null,
    onPlayEpisode: (episodeId: Int) -> Unit = {},
    onNavigateBack: () -> Unit = {},
    isOfflineMode: Boolean = false,
    isReconnectInFlight: Boolean = false,
    onRetryConnection: (() -> Unit)? = null,
    onNavigateToDownloads: (() -> Unit)? = null,
    enablePlaylists: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val feed = uiState.feed
    val baseUrl = if (CastCharmApp.apiClient.isInitialized) {
        CastCharmApp.apiClient.getBaseUrl()
    } else {
        ""
    }
    val snackbarHostState = remember { SnackbarHostState() }
    // Auto-expand the highlighted episode (navigated from search).
    var expandedEpisodeId by remember { mutableStateOf<Int?>(highlightEpisodeId) }
    val listState = rememberLazyListState()
    var addToPlaylistSheetEpisodeId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(highlightEpisodeId) {
        if (highlightEpisodeId == null) return@LaunchedEffect
        val idx = withTimeoutOrNull(30_000) {
            // Wait for both the DB load and the API refresh to finish.
            // hasMore is only populated after the API call returns, so checking
            // only !isInitialLoading exits the loop too early with hasMore=false.
            viewModel.uiState.first { !it.isInitialLoading && !it.isRefreshing }
            // Walk through pages until the episode is found or there are no more
            while (true) {
                val state = viewModel.uiState.value
                val foundIdx = state.episodes.indexOfFirst { it.id == highlightEpisodeId }
                if (foundIdx >= 0) return@withTimeoutOrNull foundIdx
                if (!state.hasMore) break
                val prevSize = state.episodes.size
                viewModel.loadMore()
                // Wait for this page to land before checking again
                viewModel.uiState.first { !it.isRefreshing && (it.episodes.size != prevSize || !it.hasMore) }
            }
            null
        } ?: return@LaunchedEffect
        // Wait until the LazyColumn has laid out enough items to reach this index.
        // +1 accounts for the feed header item at position 0.
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > idx + 1 }
        listState.animateScrollToItem(idx + 1)
    }
    val isSelectionMode = uiState.selectedEpisodes.isNotEmpty()
    var showDownloadConfirm by remember { mutableStateOf(false) }
    var showDeleteDownloadsConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(EpisodeFilter.ALL) }

    // Reset the filter when the user exits selection mode — a cleared batch
    // should feel like a fresh view.
    LaunchedEffect(isSelectionMode) {
        if (isSelectionMode && filter != EpisodeFilter.ALL) filter = EpisodeFilter.ALL
    }

    val visibleEpisodes = remember(uiState.episodes, filter) {
        when (filter) {
            EpisodeFilter.ALL -> uiState.episodes
            EpisodeFilter.UNPLAYED -> uiState.episodes.filter { !it.played }
            EpisodeFilter.DOWNLOADED -> uiState.episodes.filter { it.local_path != null }
            EpisodeFilter.IN_PROGRESS -> uiState.episodes.filter {
                !it.played && it.play_position_seconds > 0
            }
        }
    }

    // Enable the batch-delete action only if at least one selected episode
    // actually has a file on disk to remove.
    val anySelectedDownloaded = remember(uiState.selectedEpisodes, uiState.episodes) {
        val selected = uiState.selectedEpisodes
        uiState.episodes.any { it.id in selected && it.local_path != null }
    }

    if (showDownloadConfirm) {
        AlertDialog(
            onDismissRequest = { showDownloadConfirm = false },
            title = { Text("Download to phone?") },
            text = { Text("Download ${uiState.selectedEpisodes.size} episode(s) to this device?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.downloadSelected()
                        showDownloadConfirm = false
                    }
                ) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteDownloadsConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteDownloadsConfirm = false },
            title = { Text("Delete downloads?") },
            text = {
                Text(
                    "Remove the downloaded audio for the selected episodes from this " +
                        "device. They'll remain available for streaming."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelectedDownloads()
                        showDeleteDownloadsConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDownloadsConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ConsumeSnackbarMessage(
        message = uiState.errorMessage,
        snackbarHostState = snackbarHostState,
        onConsumed = { viewModel.clearError() },
        enabled = !isOfflineMode
    )

    val topBarTitle = when {
        isSelectionMode -> "${uiState.selectedEpisodes.size} selected"
        isOfflineMode && feed != null -> feed.title
        isOfflineMode -> "Offline Mode"
        feed != null -> feed.title
        uiState.isInitialLoading -> "Loading…"
        else -> "Episodes"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AppTopBarTitle(text = topBarTitle, showIcon = false)
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    } else {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    when {
                        isSelectionMode -> {
                            // Whether "mark played" or "mark unplayed" is the primary
                            // action depends on the majority state of the selection —
                            // showing whichever change would affect the most rows.
                            val allSelectedPlayed = uiState.selectedEpisodes.isNotEmpty() &&
                                uiState.episodes.filter { it.id in uiState.selectedEpisodes }
                                    .all { it.played }
                            TextButton(onClick = { viewModel.selectAll() }) {
                                Text("All")
                            }
                            IconButton(
                                onClick = { viewModel.markSelectedPlayed(!allSelectedPlayed) },
                            ) {
                                if (allSelectedPlayed) {
                                    Icon(
                                        Icons.Default.RadioButtonUnchecked,
                                        contentDescription = "Mark unplayed",
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Mark played",
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showDeleteDownloadsConfirm = true },
                                enabled = anySelectedDownloaded,
                            ) {
                                Icon(
                                    Icons.Default.DeleteForever,
                                    contentDescription = "Delete downloads",
                                )
                            }
                            IconButton(onClick = { showDownloadConfirm = true }) {
                                Icon(Icons.Default.PhoneAndroid, contentDescription = "Download to phone")
                            }
                        }

                        isOfflineMode && onRetryConnection != null -> {
                            ReconnectIconButton(
                                onClick = onRetryConnection,
                                inFlight = isReconnectInFlight
                            )
                        }

                        else -> {
                            if (uiState.isRefreshing || uiState.isSyncing) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .semantics {
                                                contentDescription = if (uiState.isSyncing) "Syncing feed" else "Refreshing"
                                            },
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Sync Feed") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    enabled = !uiState.isSyncing && !uiState.isRefreshing,
                                    onClick = { showMenu = false; viewModel.syncFeed() }
                                )
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (isOfflineMode) {
            OfflineEpisodesContent(
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
                feedTitle = feed?.title,
                isReconnectInFlight = isReconnectInFlight,
                onRetryConnection = onRetryConnection,
                onNavigateToDownloads = onNavigateToDownloads
            )
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = uiState.isSyncing,
            onRefresh = { viewModel.syncFeed() },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
        when {
            uiState.isInitialLoading && uiState.episodes.isEmpty() -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        top = 4.dp,
                        end = 8.dp,
                        bottom = 4.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { FeedHeaderSkeleton() }
                    items(6) { EpisodeCardSkeleton() }
                }
            }

            uiState.episodes.isEmpty() -> {
                EmptyEpisodeScreen(modifier = Modifier)
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        top = 4.dp,
                        end = 8.dp,
                        bottom = 4.dp
                    )
                ) {
                    if (feed != null) {
                        item {
                            FeedHeader(
                                feedTitle = feed.title,
                                feedDescription = feed.description,
                                imageUrl = feed.custom_image_url ?: feed.image_url
                                ?: if (baseUrl.isNotBlank()) "${baseUrl}api/feeds/${feed.id}/cover.jpg" else null,
                                episodeCount = feed.episode_count,
                                unplayedCount = feed.unplayed_count,
                                onPlayFeed = if (!isOfflineMode && feed.unplayed_count > 0) {
                                    { viewModel.playFeed { episodeId -> onPlayEpisode(episodeId) } }
                                } else null
                            )
                        }
                    }

                    // Filter chips: shown once the feed has any episodes and
                    // hidden in selection mode to keep the batch context clean.
                    if (!isSelectionMode) {
                        item {
                            EpisodeFilterChips(
                                selected = filter,
                                onSelect = { filter = it },
                            )
                        }
                    }

                    if (visibleEpisodes.isEmpty()) {
                        item {
                            EmptyFilterState(filter = filter, onClear = { filter = EpisodeFilter.ALL })
                        }
                    }

                    items(visibleEpisodes, key = { it.id }) { episode ->
                        val isSelected = episode.id in uiState.selectedEpisodes
                        val isPhoneDownloadInProgress = episode.id in uiState.activePhoneDownloadEpisodeIds

                        EpisodeCard(
                            episode = episode,
                            baseUrl = baseUrl,
                            isSelected = isSelected,
                            selectionActive = isSelectionMode,
                            expanded = !isSelectionMode && expandedEpisodeId == episode.id,
                            onToggleExpand = {
                                if (isSelectionMode) {
                                    viewModel.toggleEpisodeSelection(episode.id)
                                } else {
                                    expandedEpisodeId = if (expandedEpisodeId == episode.id) null else episode.id
                                }
                            },
                            onLongPress = {
                                if (!isSelectionMode) {
                                    expandedEpisodeId = null
                                    viewModel.toggleEpisodeSelection(episode.id)
                                }
                            },
                            onPlay = { onPlayEpisode(episode.id) },
                            onTogglePlayedStatus = { viewModel.togglePlayed(episode.id, episode.played) },
                            onDownloadToServer = { viewModel.downloadToServer(episode.id) },
                            onDownloadToDevice = { viewModel.downloadEpisode(episode.id) },
                            downloadActionOverride = when {
                                episode.local_path != null -> EpisodeDownloadActionOverride.ON_PHONE
                                isPhoneDownloadInProgress -> EpisodeDownloadActionOverride.PHONE_IN_PROGRESS
                                else -> null
                            },
                            onAddToPlaylist = if (!isOfflineMode) {
                                { addToPlaylistSheetEpisodeId = episode.id }
                            } else null,
                            isInPlaylist = episode.id in uiState.playlistMemberEpisodeIds,
                            enablePlaylists = enablePlaylists
                        )
                    }

                    if (uiState.hasMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (uiState.isRefreshing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .semantics { contentDescription = "Loading more episodes" }
                                    )
                                } else {
                                    OutlinedButton(onClick = { viewModel.loadMore() }) {
                                        Text("Load More")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    addToPlaylistSheetEpisodeId?.let { epId ->
        val ep = uiState.episodes.find { it.id == epId }
        AddToPlaylistSheet(
            episodeId = epId,
            episodeTitle = ep?.title ?: "",
            onDismiss = { addToPlaylistSheetEpisodeId = null },
            onMembershipChanged = { changedEpisodeId, isInPlaylist ->
                viewModel.onPlaylistMembershipChanged(changedEpisodeId, isInPlaylist)
            }
        )
    }
}

@Composable
private fun OfflineEpisodesContent(
    modifier: Modifier = Modifier,
    feedTitle: String?,
    isReconnectInFlight: Boolean = false,
    onRetryConnection: (() -> Unit)? = null,
    onNavigateToDownloads: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        OfflineModePanel(
            message = if (!feedTitle.isNullOrBlank()) {
                "$feedTitle can’t be browsed while offline. You can still use Downloads, open Settings, and play files already saved on this phone."
            } else {
                "Episode browsing isn’t available while offline. You can still use Downloads, open Settings, and play files already saved on this phone."
            },
            primaryActionLabel = if (onNavigateToDownloads != null) "Go to Downloads" else null,
            onPrimaryAction = onNavigateToDownloads,
            reconnectInFlight = isReconnectInFlight,
            onRetryConnection = onRetryConnection
        )
    }
}

@Composable
fun FeedHeader(
    feedTitle: String,
    feedDescription: String?,
    imageUrl: String?,
    episodeCount: Int,
    unplayedCount: Int,
    onPlayFeed: (() -> Unit)? = null
) {
    var descriptionExpanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            PlaceholderArtwork(
                imageUrl = imageUrl,
                contentDescription = feedTitle,
                modifier = Modifier.size(80.dp),
                contentScale = ContentScale.Crop,
                cornerRadiusDp = 8
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = feedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$episodeCount episodes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (unplayedCount > 0) {
                        Badge { Text("$unplayedCount unplayed") }
                    }
                }
                if (onPlayFeed != null) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onPlayFeed,
                        modifier = Modifier.height(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Play Feed", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (feedDescription != null) {
                    val plainText = remember(feedDescription) { stripHtml(feedDescription) }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = plainText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                        overflow = if (descriptionExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { descriptionExpanded = !descriptionExpanded }
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedHeaderSkeleton() {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(22.dp)
                        .semantics { contentDescription = "Loading" },
                    strokeWidth = 2.dp
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.65f)
                        .height(18.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
    }
}

@Composable
private fun EpisodeFilterChips(
    selected: EpisodeFilter,
    onSelect: (EpisodeFilter) -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EpisodeFilter.values().forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option.label) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun EmptyFilterState(filter: EpisodeFilter, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Nothing to show for \"${filter.label}\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClear) { Text("Show all episodes") }
    }
}

@Composable
fun EmptyEpisodeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(12.dp))
        Text("No episodes", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Check back after syncing",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}