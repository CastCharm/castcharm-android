@file:OptIn(ExperimentalMaterial3Api::class)
package com.castcharm.android.ui.playlists

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.castcharm.android.CastCharmApp
import com.castcharm.android.ui.shared_components.ConsumeSnackbarMessage
import com.castcharm.android.ui.shared_components.EpisodeCard
import com.castcharm.android.ui.shared_components.EpisodeDownloadActionOverride
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistDetailScreen(
    playlistId: Int,
    viewModel: PlaylistDetailViewModel,
    onPlayEpisode: (episodeId: Int) -> Unit,
    onNavigateBack: () -> Unit,
    isOfflineMode: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var expandedEpisodeId by remember { mutableStateOf<Int?>(null) }
    var addToPlaylistSheetEpisodeId by remember { mutableStateOf<Int?>(null) }

    val isCustomPlaylist = uiState.playlist?.type == "custom"
    val lazyListState = rememberLazyListState()

    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val currentEpisodes = uiState.episodes.toMutableList()
        currentEpisodes.add(to.index, currentEpisodes.removeAt(from.index))
        viewModel.reorderEpisodes(currentEpisodes)
    }

    ConsumeSnackbarMessage(
        message = uiState.errorMessage,
        snackbarHostState = snackbarHostState,
        onConsumed = { viewModel.clearError() }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.playlist?.name ?: "Playlist") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.playlist != null && !isOfflineMode) {
                        IconButton(onClick = {
                            viewModel.playPlaylist { episodeId ->
                                onPlayEpisode(episodeId)
                            }
                        }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play Playlist")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.episodes.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No episodes", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Add episodes from any feed",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        top = 4.dp + padding.calculateTopPadding(),
                        end = 8.dp,
                        bottom = 4.dp
                    )
                ) {
                    // Playlist header
                    item(key = "header") {
                        PlaylistDetailHeader(
                            playlist = uiState.playlist,
                            isOfflineMode = isOfflineMode,
                            onPlay = {
                                viewModel.playPlaylist { episodeId -> onPlayEpisode(episodeId) }
                            }
                        )
                    }

                    items(uiState.episodes, key = { it.id }) { episode ->
                        val isPhoneDownloadInProgress = episode.id in uiState.activePhoneDownloadEpisodeIds

                        if (isCustomPlaylist) {
                            ReorderableItem(reorderState, key = episode.id) { isDragging ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .alpha(if (isDragging) 0.4f else 1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.DragHandle,
                                        contentDescription = "Drag to reorder",
                                        modifier = Modifier
                                            .draggableHandle()
                                            .padding(start = 8.dp)
                                            .size(24.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    Box(Modifier.weight(1f)) {
                                        EpisodeCard(
                                            episode = episode,
                                            baseUrl = if (CastCharmApp.apiClient.isInitialized) CastCharmApp.apiClient.getBaseUrl() else "",
                                            expanded = expandedEpisodeId == episode.id,
                                            onToggleExpand = {
                                                expandedEpisodeId = if (expandedEpisodeId == episode.id) null else episode.id
                                            },
                                            onPlay = { onPlayEpisode(episode.id) },
                                            onTogglePlayedStatus = { viewModel.togglePlayed(episode.id, episode.played) },
                                            onDownloadToServer = { viewModel.downloadToServer(episode.id) },
                                            onDownloadToDevice = { viewModel.downloadToDevice(episode.id) },
                                            downloadActionOverride = when {
                                                episode.local_path != null -> EpisodeDownloadActionOverride.ON_PHONE
                                                isPhoneDownloadInProgress -> EpisodeDownloadActionOverride.PHONE_IN_PROGRESS
                                                else -> null
                                            },
                                            onAddToPlaylist = if (!isOfflineMode) {
                                                { addToPlaylistSheetEpisodeId = episode.id }
                                            } else null,
                                            isInPlaylist = episode.id in uiState.playlistMemberEpisodeIds
                                        )
                                    }
                                }
                            }
                        } else {
                            EpisodeCard(
                                episode = episode,
                                baseUrl = if (CastCharmApp.apiClient.isInitialized) CastCharmApp.apiClient.getBaseUrl() else "",
                                expanded = expandedEpisodeId == episode.id,
                                onToggleExpand = {
                                    expandedEpisodeId = if (expandedEpisodeId == episode.id) null else episode.id
                                },
                                onPlay = { onPlayEpisode(episode.id) },
                                onTogglePlayedStatus = { viewModel.togglePlayed(episode.id, episode.played) },
                                onDownloadToServer = { viewModel.downloadToServer(episode.id) },
                                onDownloadToDevice = { viewModel.downloadToDevice(episode.id) },
                                downloadActionOverride = when {
                                    episode.local_path != null -> EpisodeDownloadActionOverride.ON_PHONE
                                    isPhoneDownloadInProgress -> EpisodeDownloadActionOverride.PHONE_IN_PROGRESS
                                    else -> null
                                },
                                onAddToPlaylist = if (!isOfflineMode) {
                                    { addToPlaylistSheetEpisodeId = episode.id }
                                } else null,
                                isInPlaylist = episode.id in uiState.playlistMemberEpisodeIds
                            )
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
private fun PlaylistDetailHeader(
    playlist: com.castcharm.android.data.api.models.PlaylistOut?,
    isOfflineMode: Boolean,
    onPlay: () -> Unit
) {
    if (playlist == null) return
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        if (!playlist.description.isNullOrBlank()) {
            Text(
                text = playlist.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${playlist.episode_count} episodes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (!isOfflineMode) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
