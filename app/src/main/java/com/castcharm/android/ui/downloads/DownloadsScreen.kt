@file:OptIn(ExperimentalMaterial3Api::class)
// DownloadsScreen renders the Downloads tab with a two-level navigation:
//   Level 1 (feed list): a sidebar/list of feeds that have downloaded episodes,
//     each showing a download count badge. Tapping a feed navigates to Level 2.
//   Level 2 (feed detail): shows that feed's downloaded episodes, phone-side
//     in-progress downloads (with live progress bars), and server-side in-progress
//     downloads (queued/downloading on the server).
//
// The "Phone Downloads" and "Saving to Server" sections each show progress rows
// with status text, a percentage bar, and a speed indicator. The cancel button
// only appears for cancellable states (ENQUEUED or RUNNING on phone side).
//
// Multi-select: long-pressing an episode in Level 2 enters selection mode.
// Selected episodes can be deleted in bulk.
package com.castcharm.android.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.download.BandwidthTracker
import com.castcharm.android.ui.settings.formatBytes
import com.castcharm.android.ui.shared_components.SelectionActionBar
import com.castcharm.android.ui.shared_components.SelectionAction
import com.castcharm.android.ui.shared_components.AppTopBarTitle
import com.castcharm.android.ui.shared_components.EpisodeCard
import com.castcharm.android.ui.shared_components.EpisodeDownloadActionOverride
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = viewModel(),
    onPlayEpisode: (episodeId: Int) -> Unit = {},
    refreshToken: Int = 0
) {
    val uiState by viewModel.uiState.collectAsState()
    val isSelectionMode = uiState.selectedEpisodes.isNotEmpty()
    val isOfflineMode = CastCharmApp.isOfflineMode
    val bandwidth by BandwidthTracker
        .observe(CastCharmApp.instance)
        .collectAsState(initial = com.castcharm.android.download.BandwidthUsage(0L, ""))
    val scope = rememberCoroutineScope()
    val baseUrl = if (CastCharmApp.apiClient.isInitialized) {
        CastCharmApp.apiClient.getBaseUrl()
    } else {
        ""
    }

    var showDeleteSelectedConfirm by remember { mutableStateOf(false) }
    var pendingFeedDelete by remember { mutableStateOf<Int?>(null) }
    var pendingEpisodeDelete by remember { mutableStateOf<EpisodeEntity?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val hasPhoneFailed = uiState.phoneInProgress.any { it.progress?.isCancellable == false }
    val hasPhoneActive = uiState.phoneInProgress.any { it.progress?.isCancellable == true }

    LaunchedEffect(isOfflineMode, refreshToken) {
        viewModel.onScreenVisible(isOfflineMode = isOfflineMode)
    }

    if (showDeleteSelectedConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirm = false },
            title = { Text("Delete downloads?") },
            text = { Text("Delete ${uiState.selectedEpisodes.size} downloaded file(s) from this device?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelectedEpisodes()
                        showDeleteSelectedConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (pendingFeedDelete != null) {
        val feedTitle = uiState.downloadedFeeds.find { it.feed.id == pendingFeedDelete }?.feed?.title ?: "this podcast"
        AlertDialog(
            onDismissRequest = { pendingFeedDelete = null },
            title = { Text("Delete all downloads?") },
            text = { Text("Delete all downloaded files for $feedTitle from this device?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFeedDownloads(pendingFeedDelete!!)
                        pendingFeedDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingFeedDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (pendingEpisodeDelete != null) {
        val episodeTitle = pendingEpisodeDelete?.title ?: "this episode"
        AlertDialog(
            onDismissRequest = { pendingEpisodeDelete = null },
            title = { Text("Delete downloaded file?") },
            text = { Text("Delete the local file for \"$episodeTitle\" from this device?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingEpisodeDelete?.let { viewModel.deleteEpisodeDownload(it) }
                        pendingEpisodeDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingEpisodeDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    val topBarTitle = when {
        isSelectionMode -> "${uiState.selectedEpisodes.size} selected"
        uiState.selectedFeedId != null ->
            uiState.downloadedFeeds.find { it.feed.id == uiState.selectedFeedId }?.feed?.title
                ?: "Feed Downloads"
        else -> "Downloads"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { AppTopBarTitle(topBarTitle) },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    } else if (uiState.selectedFeedId != null) {
                        IconButton(onClick = { viewModel.selectFeed(null) }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    when {
                        // Bulk actions live in the SelectionActionBar at the bottom,
                        // where they can carry text labels.
                        isSelectionMode -> Unit

                        !isOfflineMode -> {
                            // Show a spinner alongside the menu while refreshing so the menu stays accessible.
                            if (uiState.isRefreshing) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .semantics { contentDescription = "Refreshing downloads" },
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Refresh") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    enabled = !uiState.isRefreshing,
                                    onClick = {
                                        showOverflowMenu = false
                                        scope.launch { viewModel.refreshForCurrentMode(isOfflineMode = false) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Retry All Failed") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    enabled = hasPhoneFailed,
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.retryAllFailedPhoneDownloads()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Cancel All Queued") },
                                    leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                    enabled = hasPhoneActive,
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.cancelAllQueuedPhoneDownloads()
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (isSelectionMode) {
                SelectionActionBar(
                    actions = listOf(
                        SelectionAction(
                            icon = Icons.Default.Delete,
                            label = "Delete from device",
                            onClick = { showDeleteSelectedConfirm = true },
                            destructive = true
                        ),
                    )
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            if (bandwidth.bytes > 0L) {
                Text(
                    text = "Downloaded this month: ${formatBytes(bandwidth.bytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            when {
                uiState.isInitialLoading &&
                        uiState.downloadedFeeds.isEmpty() &&
                        uiState.phoneInProgress.isEmpty() &&
                        uiState.serverInProgress.isEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item("header_saved_phone_loading") {
                            SectionHeader(
                                title = "Saved on Phone",
                                subtitle = "Downloads already stored locally on this device."
                            )
                        }
                        items(4, key = { "saved_phone_skeleton_$it" }) {
                            FeedDownloadCardSkeleton()
                        }
                        item("divider_loading") {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                        item("header_saving_phone_loading") {
                            SectionHeader(
                                title = "Saving to Phone",
                                subtitle = "These files are actively being saved on this device."
                            )
                        }
                        items(2, key = { "saving_phone_skeleton_$it" }) {
                            InProgressDownloadSkeleton()
                        }
                    }
                }

                uiState.selectedFeedId == null -> {
                    if (
                        uiState.downloadedFeeds.isEmpty() &&
                        uiState.phoneInProgress.isEmpty() &&
                        uiState.serverInProgress.isEmpty()
                    ) {
                        EmptyDownloadsState()
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            if (uiState.downloadedFeeds.isNotEmpty()) {
                                item("header_saved_phone_root") {
                                    SectionHeader(
                                        title = "Saved on Phone",
                                        subtitle = "Downloads already stored locally on this device."
                                    )
                                }

                                items(
                                    items = uiState.downloadedFeeds,
                                    key = { "root_saved_feed_${it.feed.id}" }
                                ) { feedItem ->
                                    FeedDownloadCard(
                                        feedItem = feedItem,
                                        onClick = { viewModel.selectFeed(feedItem.feed.id) },
                                        onDelete = { pendingFeedDelete = feedItem.feed.id }
                                    )
                                }
                            }

                            if (uiState.phoneInProgress.isNotEmpty()) {
                                item("header_saving_phone_root") {
                                    if (uiState.downloadedFeeds.isNotEmpty()) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    }
                                    SectionHeader(
                                        title = "Saving to Phone",
                                        subtitle = "These files are actively being saved on this device."
                                    )
                                }
                                items(
                                    items = uiState.phoneInProgress,
                                    key = { "root_phone_${it.episode.id}" }
                                ) { item ->
                                    InProgressDownloadCard(
                                        item = item,
                                        titleOverride = if (item.progress?.isCancellable == false) "Phone Download Failed" else "Saving to Phone",
                                        onCancel = { viewModel.cancelDownload(item.episode.id) },
                                        onRetry = { viewModel.retryDownload(item.episode.id) }
                                    )
                                }
                            }

                            if (uiState.serverInProgress.isNotEmpty()) {
                                item("header_saving_server_root") {
                                    if (uiState.downloadedFeeds.isNotEmpty() || uiState.phoneInProgress.isNotEmpty()) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    }
                                    val hasServerFailed = uiState.serverInProgress.any { it.episode.status == "failed" }
                                    val hasServerActive = uiState.serverInProgress.any { it.episode.status != "failed" }
                                    SectionHeader(
                                        title = "Server Downloads",
                                        subtitle = when {
                                            hasServerFailed && hasServerActive -> "Some downloads failed on the server. Others are still in progress."
                                            hasServerFailed -> "These server downloads failed. You can retry them from here."
                                            else -> "These files are not on your phone yet. Phone save becomes available after the server download completes."
                                        }
                                    )
                                }
                                items(
                                    items = uiState.serverInProgress,
                                    key = { "root_server_${it.episode.id}" }
                                ) { item ->
                                    ServerInProgressCard(
                                        item = item,
                                        onRetry = if (item.episode.status == "failed") {
                                            { viewModel.retryServerDownload(item.episode.id) }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    val currentFeed = uiState.downloadedFeeds
                        .find { it.feed.id == uiState.selectedFeedId }
                        ?.feed
                    var expandedEpisodeId by remember(uiState.selectedFeedId) { mutableStateOf<Int?>(null) }

                    val hasAnyCurrentFeedItems =
                        uiState.currentFeedDownloadedEpisodes.isNotEmpty() ||
                                uiState.currentFeedPhoneInProgress.isNotEmpty() ||
                                uiState.currentFeedServerInProgress.isNotEmpty()

                    if (!hasAnyCurrentFeedItems && uiState.isInitialLoading) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(6, key = { "current_feed_skeleton_$it" }) {
                                com.castcharm.android.ui.shared_components.EpisodeCardSkeleton()
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            if (uiState.currentFeedDownloadedEpisodes.isNotEmpty()) {
                                item("header_current_saved_phone") {
                                    SectionHeader(
                                        title = "Saved on Phone",
                                        subtitle = "Already stored locally on this device."
                                    )
                                }

                                items(
                                    items = uiState.currentFeedDownloadedEpisodes,
                                    key = { "current_saved_${it.id}" }
                                ) { episode ->
                                    val isSelected = uiState.selectedEpisodes.contains(episode.id)
                                    EpisodeCard(
                                        episode = episode,
                                        baseUrl = baseUrl,
                                        feedImageUrl = currentFeed?.custom_image_url ?: currentFeed?.image_url,
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
                                        onDownloadToServer = {},
                                        onDownloadToDevice = {},
                                        onDeleteFromPhone = { pendingEpisodeDelete = episode },
                                        downloadActionOverride = EpisodeDownloadActionOverride.ON_PHONE
                                    )
                                }
                            }

                            if (uiState.currentFeedPhoneInProgress.isNotEmpty()) {
                                item("header_current_saving_phone") {
                                    if (uiState.currentFeedDownloadedEpisodes.isNotEmpty()) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    }
                                    SectionHeader(
                                        title = "Saving to Phone",
                                        subtitle = "These files are actively being saved on this device."
                                    )
                                }

                                items(
                                    items = uiState.currentFeedPhoneInProgress,
                                    key = { "current_phone_${it.episode.id}" }
                                ) { item ->
                                    val episode = item.episode
                                    val isSelected = uiState.selectedEpisodes.contains(episode.id)
                                    EpisodeCard(
                                        episode = episode,
                                        baseUrl = baseUrl,
                                        feedImageUrl = currentFeed?.custom_image_url ?: currentFeed?.image_url,
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
                                        onDownloadToServer = {},
                                        onDownloadToDevice = { viewModel.retryDownload(episode.id) },
                                        downloadActionOverride = if (item.progress?.isCancellable == false) {
                                            EpisodeDownloadActionOverride.PHONE_FAILED
                                        } else {
                                            EpisodeDownloadActionOverride.PHONE_IN_PROGRESS
                                        }
                                    )
                                }
                            }

                            if (uiState.currentFeedServerInProgress.isNotEmpty()) {
                                item("header_current_saving_server") {
                                    if (uiState.currentFeedDownloadedEpisodes.isNotEmpty() || uiState.currentFeedPhoneInProgress.isNotEmpty()) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    }
                                    val hasFeedServerFailed = uiState.currentFeedServerInProgress.any { it.episode.status == "failed" }
                                    val hasFeedServerActive = uiState.currentFeedServerInProgress.any { it.episode.status != "failed" }
                                    SectionHeader(
                                        title = "Server Downloads",
                                        subtitle = when {
                                            hasFeedServerFailed && hasFeedServerActive -> "Some downloads failed on the server. Others are still in progress."
                                            hasFeedServerFailed -> "These server downloads failed. You can retry them from here."
                                            else -> "These files are downloading on the server, not on your phone."
                                        }
                                    )
                                }

                                items(
                                    items = uiState.currentFeedServerInProgress,
                                    key = { "current_server_${it.episode.id}" }
                                ) { item ->
                                    val episode = item.episode
                                    val overrideState = when (episode.status) {
                                        "downloading" -> EpisodeDownloadActionOverride.SERVER_DOWNLOADING
                                        "queued" -> EpisodeDownloadActionOverride.SERVER_QUEUED
                                        "failed" -> EpisodeDownloadActionOverride.RETRY_SERVER
                                        else -> EpisodeDownloadActionOverride.SAVE_TO_SERVER
                                    }

                                    // Selection is deliberately disabled for this list.
                                    // These episodes live on the server and have no
                                    // local_path, and the only bulk action is "delete
                                    // from this device" — deleteSelectedEpisodes()
                                    // filters against getDownloadedEpisodesOnce(), so
                                    // picking one here did nothing while the
                                    // confirmation still counted it. Offering no
                                    // checkbox is honest; a checkbox that leads
                                    // nowhere is not.
                                    EpisodeCard(
                                        episode = episode,
                                        baseUrl = baseUrl,
                                        feedImageUrl = currentFeed?.custom_image_url ?: currentFeed?.image_url,
                                        isSelected = false,
                                        selectionActive = false,
                                        expanded = !isSelectionMode && expandedEpisodeId == episode.id,
                                        onToggleExpand = {
                                            expandedEpisodeId = if (expandedEpisodeId == episode.id) null else episode.id
                                        },
                                        onLongPress = {
                                            if (!isSelectionMode) {
                                                expandedEpisodeId = null
                                            }
                                        },
                                        onPlay = { onPlayEpisode(episode.id) },
                                        onTogglePlayedStatus = { viewModel.togglePlayed(episode.id, episode.played) },
                                        onDownloadToServer = { viewModel.retryServerDownload(episode.id) },
                                        onDownloadToDevice = {},
                                        downloadActionOverride = overrideState
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyDownloadsState() {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.DownloadDone,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "No downloads",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InProgressDownloadCard(
    item: DownloadItem,
    onCancel: () -> Unit,
    onRetry: (() -> Unit)? = null,
    titleOverride: String? = null
) {
    val progress = item.progress
    val percent = (progress?.percent ?: item.episode.download_progress).coerceIn(0, 100)
    val statusText = progress?.status ?: item.episode.status.replaceFirstChar { it.uppercase() }

    val totalBytes = when {
        (progress?.totalBytes ?: 0L) > 0L -> progress?.totalBytes ?: 0L
        (item.episode.enclosure_length ?: 0L) > 0L -> item.episode.enclosure_length ?: 0L
        else -> 0L
    }

    val bytesDownloaded = when {
        (progress?.bytesDownloaded ?: 0L) > 0L -> progress?.bytesDownloaded ?: 0L
        percent > 0 && totalBytes > 0L -> (totalBytes * percent) / 100L
        else -> 0L
    }

    val downloadedText = when {
        bytesDownloaded > 0L -> formatBytes(bytesDownloaded)
        percent > 0 -> "Progress updating…"
        else -> "0 B"
    }

    val totalText = if (totalBytes > 0L) {
        formatBytes(totalBytes)
    } else {
        "Unknown size"
    }

    val speedText = if ((progress?.speedBytesPerSec ?: 0L) > 0L) {
        "${formatBytes(progress?.speedBytesPerSec ?: 0L)}/s"
    } else {
        null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (item.feed != null) {
                Text(
                    text = item.feed.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = item.episode.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { (percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = listOfNotNull(titleOverride, "$statusText • $percent%").joinToString(" • "),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = if (speedText != null) {
                            "$downloadedText / $totalText • $speedText"
                        } else {
                            "$downloadedText / $totalText"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val isFailed = item.progress?.isCancellable == false
                if (isFailed && onRetry != null) {
                    TextButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Retry")
                    }
                } else if (!isFailed) {
                    TextButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerInProgressCard(
    item: DownloadItem,
    onRetry: (() -> Unit)? = null
) {
    val isFailed = item.episode.status == "failed"
    val percent = when (item.episode.status) {
        "queued", "pending" -> 0
        "downloading" -> item.episode.download_progress.coerceIn(0, 100)
        else -> 0
    }
    val statusText = when (item.episode.status) {
        "queued" -> "Queued on Server"
        "pending" -> "Queued on Server"
        "downloading" -> "Downloading on Server"
        "failed" -> "Server Download Failed"
        else -> item.episode.status.replaceFirstChar { it.uppercase() }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (item.feed != null) {
                Text(
                    text = item.feed.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = item.episode.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            if (percent > 0) {
                LinearProgressIndicator(
                    progress = { (percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (percent > 0) "$statusText • $percent%" else statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isFailed) {
                            "The server failed to download this file."
                        } else if (percent > 0) {
                            "Progress reported by server."
                        } else {
                            "Waiting for updated server progress."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isFailed && onRetry != null) {
                    TextButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Retry")
                    }
                } else if (!isFailed) {
                    val isDownloading = item.episode.status == "downloading"
                    Icon(
                        imageVector = if (isDownloading) Icons.Default.CloudDownload else Icons.Default.Schedule,
                        contentDescription = if (isDownloading) "Downloading on server" else "Queued on server",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InProgressDownloadSkeleton() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { 0.2f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.3f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .semantics { contentDescription = "Loading" },
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
fun FeedDownloadCard(
    feedItem: FeedDownloadItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = feedItem.feed.custom_image_url ?: feedItem.feed.image_url,
                contentDescription = feedItem.feed.title,
                imageLoader = CastCharmApp.imageLoader,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = feedItem.feed.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${feedItem.downloadCount} episodes on phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete all downloads from feed")
            }
        }
    }
}

@Composable
private fun FeedDownloadCardSkeleton() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .semantics { contentDescription = "Loading" },
                    strokeWidth = 2.dp
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.35f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}