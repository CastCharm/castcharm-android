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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.models.SmartRules
import com.castcharm.android.data.db.entities.PlaylistEntity
import com.castcharm.android.ui.shared_components.ConsumeSnackbarMessage
import com.castcharm.android.ui.shared_components.EpisodeCard
import com.castcharm.android.ui.shared_components.EpisodeDownloadActionOverride
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistDetailScreen(
    playlistId: Int,
    viewModel: PlaylistDetailViewModel,
    onPlaybackStarted: () -> Unit,
    onNavigateBack: () -> Unit,
    isOfflineMode: Boolean = false,
    enablePlaylists: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var expandedEpisodeId by remember { mutableStateOf<Int?>(null) }
    var addToPlaylistSheetEpisodeId by remember { mutableStateOf<Int?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    val playlist = uiState.playlist
    val isManual = playlist?.type == "custom"
    val lazyListState = rememberLazyListState()

    LaunchedEffect(isOfflineMode) { viewModel.setOffline(isOfflineMode) }
    LaunchedEffect(uiState.deleted) { if (uiState.deleted) onNavigateBack() }

    // Moves are resolved by item key: the header sits at index 0 of the
    // LazyColumn, so the library's indices are off by one from the list.
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        viewModel.moveEpisode(from.key, to.key)
    }

    ConsumeSnackbarMessage(message = uiState.errorMessage, snackbarHostState = snackbarHostState, onConsumed = { viewModel.clearError() })
    ConsumeSnackbarMessage(message = uiState.infoMessage, snackbarHostState = snackbarHostState, onConsumed = { viewModel.clearInfo() })

    if (showEdit && playlist != null) {
        if (playlist.isSmart) {
            SmartPlaylistEditor(
                title = "Edit Smart Playlist", initialName = playlist.name, initialDescription = playlist.description ?: "",
                initialRules = uiState.rules, nameLocked = playlist.is_system, feeds = uiState.feeds,
                preview = { viewModel.preview(it) },
                onSave = { name, desc, rules -> showEdit = false; viewModel.updatePlaylist(if (playlist.is_system) null else name, desc ?: "", rules) },
                onDismiss = { showEdit = false }
            )
        } else {
            ManualPlaylistDialog(
                title = "Edit Playlist", initialName = playlist.name, initialDescription = playlist.description ?: "",
                onDismiss = { showEdit = false },
                onSave = { name, desc -> showEdit = false; viewModel.updatePlaylist(name, desc ?: "", null) }
            )
        }
    }
    if (showDelete && playlist != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("\"${playlist.name}\" will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { showDelete = false; viewModel.deletePlaylist() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name ?: "Playlist") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (playlist != null && uiState.episodes.isNotEmpty()) {
                        IconButton(onClick = { viewModel.playPlaylist(); onPlaybackStarted() }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play Playlist")
                        }
                    }
                    if (playlist != null && !isOfflineMode) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(if (playlist.isSmart) "Edit rules" else "Edit") }, onClick = { menuOpen = false; showEdit = true })
                                DropdownMenuItem(text = { Text("Download all to phone") },
                                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                    onClick = { menuOpen = false; viewModel.downloadAll() })
                                if (!playlist.is_system) {
                                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { menuOpen = false; showDelete = true })
                                }
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            else -> {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 8.dp, top = 4.dp + padding.calculateTopPadding(), end = 8.dp, bottom = 4.dp)
                ) {
                    item(key = "header") {
                        PlaylistDetailHeader(
                            playlist = playlist, rules = uiState.rules, count = uiState.episodes.size,
                            onPhoneCount = uiState.onPhoneCount, isOfflineMode = isOfflineMode,
                            onPlay = { viewModel.playPlaylist(); onPlaybackStarted() }
                        )
                    }

                    if (uiState.episodes.isEmpty()) {
                        item(key = "empty") {
                            Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (playlist?.isSmart == true) "Nothing matches right now" else "No episodes", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    when {
                                        isOfflineMode -> "Only episodes saved on this phone show while offline."
                                        playlist?.isSmart == true -> "Edit the rules to widen what this list gathers."
                                        else -> "Add episodes from any podcast with the + button on an episode."
                                    },
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    items(uiState.episodes, key = { it.id }) { episode ->
                        val isPhoneDownloadInProgress = episode.id in uiState.activePhoneDownloadEpisodeIds
                        val card: @Composable () -> Unit = {
                            EpisodeCard(
                                episode = episode,
                                baseUrl = if (CastCharmApp.apiClient.isInitialized) CastCharmApp.apiClient.getBaseUrl() else "",
                                expanded = expandedEpisodeId == episode.id,
                                onToggleExpand = { expandedEpisodeId = if (expandedEpisodeId == episode.id) null else episode.id },
                                onPlay = { viewModel.playFrom(episode.id); onPlaybackStarted() },
                                onTogglePlayedStatus = { viewModel.togglePlayed(episode.id, episode.played) },
                                onDownloadToServer = { viewModel.downloadToServer(episode.id) },
                                onDownloadToDevice = { viewModel.downloadToDevice(episode.id) },
                                sharedWith = viewModel.sharedFromHere(episode.id),
                                downloadActionOverride = when {
                                    episode.local_path != null -> EpisodeDownloadActionOverride.ON_PHONE
                                    isPhoneDownloadInProgress -> EpisodeDownloadActionOverride.PHONE_IN_PROGRESS
                                    else -> null
                                },
                                // Smart lists pick their own members; the "+" would only confuse.
                                onAddToPlaylist = if (!isOfflineMode && isManual) { { addToPlaylistSheetEpisodeId = episode.id } } else null,
                                isInPlaylist = episode.id in uiState.playlistMemberEpisodeIds,
                                enablePlaylists = enablePlaylists
                            )
                        }
                        if (isManual && !isOfflineMode) {
                            ReorderableItem(reorderState, key = episode.id) { isDragging ->
                                Row(modifier = Modifier.fillMaxWidth().alpha(if (isDragging) 0.4f else 1f), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.DragHandle, contentDescription = "Drag to reorder",
                                        modifier = Modifier.draggableHandle(onDragStopped = { viewModel.commitOrder() }).padding(start = 8.dp).size(24.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    Box(Modifier.weight(1f)) { card() }
                                }
                            }
                        } else {
                            card()
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
            onMembershipChanged = { changedEpisodeId, isInPlaylist -> viewModel.onPlaylistMembershipChanged(changedEpisodeId, isInPlaylist) }
        )
    }
}

@Composable
private fun PlaylistDetailHeader(
    playlist: PlaylistEntity?,
    rules: SmartRules?,
    count: Int,
    onPhoneCount: Int,
    isOfflineMode: Boolean,
    onPlay: () -> Unit
) {
    if (playlist == null) return
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlaylistKindIcon(playlist.isSmart, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (playlist.isSmart) "Smart playlist" else "Manual playlist", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (playlist.is_system) {
                Spacer(Modifier.width(8.dp))
                AssistChip(onClick = {}, label = { Text("Built-in", style = MaterialTheme.typography.labelSmall) })
            }
        }
        if (!playlist.description.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(text = playlist.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (rules != null) {
            Spacer(Modifier.height(4.dp))
            Text(text = rules.describe(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append("$count episode${if (count == 1) "" else "s"}")
                    if (playlist.total_seconds > 0 && !isOfflineMode) append(" · ${formatHours(playlist.total_seconds)}")
                    append(" · $onPhoneCount on phone")
                    if (isOfflineMode) append(" (offline)")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
            )
            if (count > 0) {
                Button(onClick = onPlay, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
