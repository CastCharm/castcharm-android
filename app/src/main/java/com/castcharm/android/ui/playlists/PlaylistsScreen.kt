@file:OptIn(ExperimentalMaterial3Api::class)
package com.castcharm.android.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.castcharm.android.data.api.models.PLAYLIST_DESC_MAX
import com.castcharm.android.data.api.models.PLAYLIST_NAME_MAX
import com.castcharm.android.data.api.models.SmartRules
import com.castcharm.android.data.db.entities.PlaylistEntity
import com.castcharm.android.ui.shared_components.ConsumeSnackbarMessage

@Composable
fun PlaylistsScreen(
    viewModel: PlaylistsViewModel,
    onViewPlaylist: (playlistId: Int) -> Unit,
    onPlaybackStarted: () -> Unit = {},
    isOfflineMode: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var createStep by remember { mutableStateOf<String?>(null) }   // null | "choose" | "manual" | "smart"
    var editTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistEntity?>(null) }

    ConsumeSnackbarMessage(message = uiState.errorMessage, snackbarHostState = snackbarHostState, onConsumed = { viewModel.clearError() })
    ConsumeSnackbarMessage(message = uiState.infoMessage, snackbarHostState = snackbarHostState, onConsumed = { viewModel.clearInfo() })

    when (createStep) {
        "choose" -> PlaylistKindDialog(onDismiss = { createStep = null }, onChoose = { createStep = it })
        "manual" -> ManualPlaylistDialog(
            title = "New Manual Playlist", initialName = "", initialDescription = "",
            onDismiss = { createStep = null },
            onSave = { name, desc -> createStep = null; viewModel.createManual(name, desc) }
        )
        "smart" -> SmartPlaylistEditor(
            title = "New Smart Playlist", initialName = "", initialDescription = "", initialRules = null,
            nameLocked = false, feeds = uiState.feeds, preview = { viewModel.preview(it) },
            onSave = { name, desc, rules -> createStep = null; viewModel.createSmart(name, desc, rules) },
            onDismiss = { createStep = null }
        )
    }

    editTarget?.let { pl ->
        if (pl.isSmart) {
            SmartPlaylistEditor(
                title = "Edit Smart Playlist", initialName = pl.name, initialDescription = pl.description ?: "",
                initialRules = viewModel.rulesOf(pl), nameLocked = pl.is_system, feeds = uiState.feeds,
                preview = { viewModel.preview(it) },
                onSave = { name, desc, rules -> editTarget = null; viewModel.updatePlaylist(pl.id, if (pl.is_system) null else name, desc ?: "", rules) },
                onDismiss = { editTarget = null }
            )
        } else {
            ManualPlaylistDialog(
                title = "Edit Playlist", initialName = pl.name, initialDescription = pl.description ?: "",
                onDismiss = { editTarget = null },
                onSave = { name, desc -> editTarget = null; viewModel.updatePlaylist(pl.id, name, desc ?: "", null) }
            )
        }
    }

    deleteTarget?.let { pl ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete playlist?") },
            text = { Text("\"${pl.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deletePlaylist(pl.id); deleteTarget = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playlists") },
                actions = {
                    if (!isOfflineMode) {
                        IconButton(onClick = { createStep = "choose" }) {
                            Icon(Icons.Default.Add, contentDescription = "New Playlist")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            uiState.isLoading && uiState.playlists.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            uiState.playlists.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(12.dp))
                        Text("No playlists yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(if (isOfflineMode) "Playlists appear here once the app has synced." else "Tap + to create one",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(uiState.playlists, key = { it.id }) { pl ->
                        PlaylistCard(
                            playlist = pl,
                            rules = viewModel.rulesOf(pl),
                            isOfflineMode = isOfflineMode,
                            onPlay = { viewModel.playPlaylist(pl.id); onPlaybackStarted() },
                            onView = { onViewPlaylist(pl.id) },
                            onEdit = { editTarget = pl },
                            onDownloadAll = { viewModel.downloadAll(pl.id) },
                            onDelete = { deleteTarget = pl }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistKindIcon(isSmart: Boolean, modifier: Modifier = Modifier) {
    Icon(
        if (isSmart) Icons.Default.Psychology else Icons.AutoMirrored.Filled.QueueMusic,
        contentDescription = if (isSmart) "Smart playlist" else "Manual playlist",
        tint = if (isSmart) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

fun playlistMetaLine(pl: PlaylistEntity, count: Int? = null): String {
    val n = count ?: -1
    val kind = if (pl.isSmart) "Smart" else "Manual"
    val countPart = if (n >= 0) " · $n episode${if (n == 1) "" else "s"}" else ""
    val hours = if (pl.total_seconds > 0) " · ${formatHours(pl.total_seconds)}" else ""
    return "$kind$countPart$hours"
}

@Composable
private fun PlaylistCard(
    playlist: PlaylistEntity,
    rules: SmartRules?,
    isOfflineMode: Boolean,
    onPlay: () -> Unit,
    onView: () -> Unit,
    onEdit: () -> Unit,
    onDownloadAll: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(onClick = onView)) {
        Row(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistKindIcon(playlist.isSmart, Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = playlist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (playlist.is_system) {
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick = {}, label = { Text("Built-in", style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(text = playlistMetaLine(playlist), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rules != null) {
                    Text(text = rules.describe(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else if (!playlist.description.isNullOrBlank()) {
                    Text(text = playlist.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, contentDescription = "Play") }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!isOfflineMode) {
                        DropdownMenuItem(text = { Text(if (playlist.isSmart) "Edit rules" else "Edit") }, onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(text = { Text("Download all to phone") },
                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                            onClick = { menuOpen = false; onDownloadAll() })
                        if (!playlist.is_system) {
                            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; onDelete() })
                        }
                    } else {
                        DropdownMenuItem(text = { Text("Editing needs a connection") }, enabled = false, onClick = {})
                    }
                }
            }
        }
    }
}

// Step one of "New playlist": the kind, which is fixed for the playlist's life.
@Composable
private fun PlaylistKindDialog(onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KindOption(isSmart = false, name = "Manual", desc = "You pick the episodes and put them in order.") { onChoose("manual") }
                KindOption(isSmart = true, name = "Smart", desc = "Rules pick the episodes for you and keep the list current, like \"unplayed, from the last two weeks\".") { onChoose("smart") }
                Text("This can't be changed later: a manual playlist never gains rules, and a smart one never takes hand-picked episodes.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun KindOption(isSmart: Boolean, name: String, desc: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistKindIcon(isSmart, Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ManualPlaylistDialog(
    title: String,
    initialName: String,
    initialDescription: String,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String?) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(PLAYLIST_NAME_MAX) },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it.take(PLAYLIST_DESC_MAX) },
                    label = { Text("Description (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim(), description.trim().takeIf { it.isNotBlank() }) },
                enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
