@file:OptIn(ExperimentalMaterial3Api::class)
// FeedListScreen renders the Podcasts tab: a responsive grid of feed artwork cards.
// Three display states:
//   1. Offline: OfflineFeedsContent with a reconnect button and a link to Downloads.
//   2. Loading (no cached data): a grid of 6 SkeletonArtworkCard placeholders.
//   3. Loaded: LazyVerticalGrid of FeedCard items, keyed by feed ID for stable recomposition.
//
// FeedCard: shows the custom or default artwork (falling back to the server cover endpoint),
// overlays an unplayed-count Badge in the top-right corner, and displays the feed title +
// episode count below. Uses minLines=2 on the title so cards in a row are aligned.
//
// EmptyScreen: shown when the server has no feeds yet (fresh server setup).
package com.castcharm.android.ui.feeds

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.layout.width
import com.castcharm.android.ui.shared_components.ProvideSelectionActions
import com.castcharm.android.ui.shared_components.SelectionAction
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.FolderConflict
import com.castcharm.android.data.api.models.feedCoverUrl
import com.castcharm.android.data.db.entities.FeedEntity
import com.castcharm.android.ui.shared_components.AppTopBarTitle
import com.castcharm.android.ui.shared_components.ConsumeSnackbarMessage
import com.castcharm.android.ui.shared_components.OfflineModePanel
import com.castcharm.android.ui.shared_components.PlaceholderArtwork
import com.castcharm.android.ui.shared_components.ReconnectIconButton
import com.castcharm.android.ui.shared_components.SkeletonArtworkCard

@Composable
fun FeedListScreen(
    viewModel: FeedListViewModel = viewModel(),
    onNavigateToEpisodes: (feedId: Int) -> Unit = {},
    onSearchClick: (() -> Unit)? = null,
    isOfflineMode: Boolean = false,
    isReconnectInFlight: Boolean = false,
    onRetryConnection: (() -> Unit)? = null,
    onNavigateToDownloads: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val baseUrl = if (CastCharmApp.apiClient.isInitialized) CastCharmApp.apiClient.getBaseUrl() else ""
    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }
    var showAddFeedDialog by remember { mutableStateOf(false) }
    var pendingFeedUrl by remember { mutableStateOf("") }
    var pendingDownloadAll by remember { mutableStateOf(false) }

    ConsumeSnackbarMessage(
        message = uiState.errorMessage,
        snackbarHostState = snackbarHostState,
        onConsumed = { viewModel.clearError() },
        enabled = !isOfflineMode
    )
    ConsumeSnackbarMessage(
        message = uiState.successMessage,
        snackbarHostState = snackbarHostState,
        onConsumed = { viewModel.clearSuccess() }
    )

    if (showAddFeedDialog) {
        val conflict = uiState.addFeedFolderConflict
        if (conflict != null) {
            // The add was refused because the folder is occupied. Hand the decision
            // back rather than picking for them.
            FolderConflictDialog(
                conflict = conflict,
                isLoading = uiState.isAddingFeed,
                errorMessage = uiState.addFeedError,
                onUseDifferentName = { newName ->
                    viewModel.addFeed(
                        url = pendingFeedUrl,
                        downloadAll = pendingDownloadAll,
                        folderNameOverride = newName,
                    ) { showAddFeedDialog = false }
                },
                onUseExistingFolder = {
                    viewModel.addFeed(
                        url = pendingFeedUrl,
                        downloadAll = pendingDownloadAll,
                        allowExistingFolder = true,
                    ) { showAddFeedDialog = false }
                },
                onBack = { viewModel.clearAddFeedFolderConflict() }
            )
        } else {
            AddFeedDialog(
                isLoading = uiState.isAddingFeed,
                errorMessage = uiState.addFeedError,
                onErrorDismissed = { viewModel.clearAddFeedError() },
                onConfirm = { url, downloadAll ->
                    // Remembered so the conflict prompt can retry the same request
                    // without making the user retype the URL.
                    pendingFeedUrl = url
                    pendingDownloadAll = downloadAll
                    viewModel.addFeed(url, downloadAll) { showAddFeedDialog = false }
                },
                onDismiss = { if (!uiState.isAddingFeed) showAddFeedDialog = false }
            )
        }
    }

    val isSelectionMode = uiState.feedSelectionMode

    // Multi-select no longer ends on its own when the selection empties, so back
    // has to be an explicit way out of it.
    BackHandler(enabled = isSelectionMode) { viewModel.exitFeedSelectionMode() }

    // Feeds being deleted are inert, so they are excluded from "all" in both
    // directions — selectAllFeeds() skips them, and this must agree or the label
    // could never flip to "Select none" while a deletion is in flight.
    val selectableFeeds = uiState.feeds.filterNot { it.id in uiState.deletingFeeds }
    val allSelected = selectableFeeds.isNotEmpty() &&
        selectableFeeds.all { it.id in uiState.selectedFeeds }
    var showDeleteFeedsConfirm by remember { mutableStateOf(false) }

    if (showDeleteFeedsConfirm) {
        DeleteFeedsDialog(
            feedTitles = uiState.feeds
                .filter { it.id in uiState.selectedFeeds }
                .map { it.title ?: it.url },
            onConfirm = { deleteFiles ->
                showDeleteFeedsConfirm = false
                viewModel.deleteSelectedFeeds(deleteFiles)
            },
            onDismiss = { showDeleteFeedsConfirm = false }
        )
    }

    // The selection can now legitimately be empty while the bar is still up, so
    // the bulk actions have to grey out rather than quietly do nothing.
    val anySelected = uiState.selectedFeeds.isNotEmpty()
    val bulkEnabled = anySelected && !uiState.bulkActionInFlight

    ProvideSelectionActions(
        active = isSelectionMode,
        allSelected,
        bulkEnabled,
    ) {
        listOf(
            SelectionAction(
                icon = if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                label = if (allSelected) "Select none" else "Select all",
                onClick = {
                    if (allSelected) viewModel.clearFeedSelection() else viewModel.selectAllFeeds()
                }
            ),
            SelectionAction(
                icon = Icons.Default.Refresh,
                label = "Sync",
                onClick = { viewModel.syncSelectedFeeds() },
                enabled = bulkEnabled
            ),
            SelectionAction(
                icon = Icons.Default.DeleteForever,
                label = "Delete",
                onClick = { showDeleteFeedsConfirm = true },
                enabled = bulkEnabled,
                destructive = true
            ),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AppTopBarTitle(
                        text = if (isSelectionMode) {
                            "${uiState.selectedFeeds.size} selected"
                        } else {
                            "Podcasts"
                        }
                    )
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = { viewModel.exitFeedSelectionMode() }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    }
                },
                actions = {
                    if (onSearchClick != null) {
                        IconButton(onClick = onSearchClick) {
                            Icon(Icons.Default.Search, contentDescription = "Search episodes")
                        }
                    }
                    if (isOfflineMode && onRetryConnection != null) {
                        ReconnectIconButton(
                            onClick = onRetryConnection,
                            inFlight = isReconnectInFlight
                        )
                    }
                    if (!isOfflineMode) {
                        // Background activity — a routine refresh as well as a sync —
                        // is reported here rather than over the content. The grid is
                        // already populated during a refresh, so a large spinner adds
                        // nothing and has nowhere to sit that isn't on top of, or
                        // behind, the cards.
                        if (uiState.isSyncing ||
                            uiState.syncingFeedIds.isNotEmpty() ||
                            (uiState.isRefreshing && !uiState.isPullRefreshing)
                        ) {
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
                                            contentDescription =
                                                if (uiState.isSyncing) "Syncing feeds" else "Refreshing"
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
                                text = { Text("Add Feed from URL") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                onClick = { showMenu = false; showAddFeedDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Sync All Feeds") },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                enabled = !uiState.isSyncing && uiState.syncingFeedIds.isEmpty(),
                                onClick = { showMenu = false; viewModel.syncAllFeeds() }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (isOfflineMode) {
            OfflineFeedsContent(
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
                isReconnectInFlight = isReconnectInFlight,
                onRetryConnection = onRetryConnection,
                onNavigateToDownloads = onNavigateToDownloads
            )
            return@Scaffold
        }

        PullToRefreshBox(
            // Pull indicator responds to pulls only — see isPullRefreshing.
            isRefreshing = uiState.isPullRefreshing,
            onRefresh = { viewModel.refreshFeeds(fromPull = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            // No centred spinner here on purpose. With the grid already populated it
            // rendered behind the cards, and a full-page indicator over content the
            // user can already see and scroll is noise. Loading from empty is covered
            // by the skeleton cards below; background refreshes show the small
            // top-bar spinner instead.
            when {
                uiState.isInitialLoading && uiState.feeds.isEmpty() -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            top = 12.dp,
                            end = 12.dp,
                            bottom = 12.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(6) {
                            SkeletonArtworkCard()
                        }
                    }
                }

                uiState.feeds.isEmpty() -> {
                    EmptyScreen(modifier = Modifier)
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            top = 12.dp,
                            end = 12.dp,
                            bottom = 12.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.feeds, key = { it.id }) { feed ->
                            FeedCard(
                                feed = feed,
                                baseUrl = baseUrl,
                                isSelected = feed.id in uiState.selectedFeeds,
                                selectionActive = isSelectionMode,
                                isDeleting = feed.id in uiState.deletingFeeds,
                                onClick = {
                                    if (isSelectionMode) {
                                        viewModel.toggleFeedSelection(feed.id)
                                    } else {
                                        onNavigateToEpisodes(feed.id)
                                    }
                                },
                                onLongPress = { viewModel.toggleFeedSelection(feed.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Confirmation for deleting podcasts from the server.
 *
 * Worth being blunt in this dialog. Everywhere else in the app "delete" means "free
 * up space on this phone" — the Downloads tab, the episode list, the storage quota
 * all operate on local files. This one does something categorically different: it
 * unsubscribes the server itself, for every device, and cannot be undone from here.
 * The wording, the icon and the button label all say "server" for that reason, and
 * the feeds being removed are listed by name so a mis-tap on the wrong card is
 * visible before it is irreversible.
 */
@Composable
private fun DeleteFeedsDialog(
    feedTitles: List<String>,
    onConfirm: (deleteFiles: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var deleteFiles by remember { mutableStateOf(false) }
    val count = feedTitles.size

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text("Remove ${if (count == 1) "podcast" else "$count podcasts"} from the server?")
        },
        text = {
            Column {
                Text(
                    text = "This unsubscribes your CastCharm server, not just this phone. " +
                        "The ${if (count == 1) "podcast" else "podcasts"} and all episode " +
                        "history will be gone for every device, and this cannot be undone " +
                        "from the app.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(12.dp))

                // Cap the list so selecting 40 podcasts doesn't produce a dialog
                // taller than the screen with the buttons pushed off the bottom.
                feedTitles.take(6).forEach { title ->
                    Text(
                        text = "• $title",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (count > 6) {
                    Text(
                        text = "…and ${count - 6} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { deleteFiles = !deleteFiles }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Also erase the downloaded audio files from the server's disk",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!deleteFiles) {
                    Text(
                        text = "Leaving this unchecked keeps the audio files on the server, " +
                            "but they will no longer belong to any podcast.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(deleteFiles) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete from server")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun OfflineFeedsContent(
    modifier: Modifier = Modifier,
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
            message = "Podcast browsing isn’t available while offline. You can still use Downloads, open Settings, and play files already stored on this phone.",
            primaryActionLabel = if (onNavigateToDownloads != null) "Go to Downloads" else null,
            onPrimaryAction = onNavigateToDownloads,
            reconnectInFlight = isReconnectInFlight,
            onRetryConnection = onRetryConnection
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedCard(
    feed: FeedEntity,
    baseUrl: String,
    onClick: () -> Unit,
    isSelected: Boolean = false,
    selectionActive: Boolean = false,
    isDeleting: Boolean = false,
    onLongPress: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // A feed whose deletion is in flight accepts no input at all — it cannot
            // be opened, and cannot be picked up into a new selection.
            .combinedClickable(
                enabled = !isDeleting,
                onClick = onClick,
                onLongClick = onLongPress
            ),
        shape = RoundedCornerShape(12.dp),
        // Same three-signal treatment as EpisodeCard: badge, border and elevation,
        // so selection never depends on telling two similar backgrounds apart.
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        elevation = if (isSelected) {
            CardDefaults.cardElevation(defaultElevation = 6.dp)
        } else {
            CardDefaults.cardElevation()
        }
    ) {
        Column {
            val rawImageUrl = feed.custom_image_url
                ?: feed.image_url
                ?: feedCoverUrl(baseUrl, feed.id, feed.url)

            Box {
                PlaceholderArtwork(
                    imageUrl = rawImageUrl,
                    contentDescription = feed.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentScale = ContentScale.Crop,
                    cornerRadiusDp = 12
                )

                if (feed.unplayed_count > 0) {
                    Badge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Text(feed.unplayed_count.toString())
                    }
                }

                // Slated for deletion: black scrim plus a bin, matching the web UI.
                // Drawn last so it covers the artwork, the unplayed badge and any
                // selection mark — the card reads as "on its way out" and nothing
                // else about it is actionable.
                if (isDeleting) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Deleting",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Selection mark, on an opaque disc so its contrast never depends
                // on the cover art underneath. Bottom-start keeps it clear of the
                // unplayed-count badge in the opposite corner.
                if (selectionActive && !isDeleting) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Black.copy(alpha = 0.6f)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSelected) {
                                Icons.Default.Check
                            } else {
                                Icons.Default.RadioButtonUnchecked
                            },
                            contentDescription = if (isSelected) "Selected" else "Not selected",
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                Color.White
                            },
                            modifier = Modifier.size(if (isSelected) 20.dp else 30.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = feed.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    minLines = 2
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    text = "${feed.episode_count} episodes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FeedCardSkeleton() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.Center)
                        .semantics { contentDescription = "Loading" },
                    strokeWidth = 2.dp
                )
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )

                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
    }
}

/**
 * Shown when the server refuses an add because the podcast's folder already exists
 * and has files in it — almost always left over from a podcast that was removed
 * without deleting its audio.
 *
 * Deliberately offers both ways out and commits to neither. Silently adopting the
 * folder mixes two podcasts' files together and lets the new one inherit the old
 * one's cover art; silently deleting what's there would destroy audio the user chose
 * to keep. Only they know which it should be.
 */
@Composable
private fun FolderConflictDialog(
    conflict: FolderConflict,
    isLoading: Boolean,
    errorMessage: String?,
    onUseDifferentName: (String) -> Unit,
    onUseExistingFolder: () -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable(conflict.folderName) { mutableStateOf(conflict.folderName) }
    val canSubmit = name.isNotBlank() && name.trim() != conflict.folderName && !isLoading

    AlertDialog(
        onDismissRequest = { if (!isLoading) onBack() },
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("Folder already exists") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "A folder named \"${conflict.folderName}\" already exists and " +
                        "contains ${conflict.fileCount} file" +
                        (if (conflict.fileCount == 1) "" else "s") +
                        ". It's most likely left over from a podcast that was removed " +
                        "without deleting its files.",
                    style = MaterialTheme.typography.bodyMedium
                )
                conflict.folderPath?.let { path ->
                    Text(
                        text = path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Give this podcast a different folder name, or use the existing " +
                        "folder anyway — its files will be mixed in with this podcast's.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    enabled = !isLoading,
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Only the folder name changes — the podcast keeps its title " +
                        "from the feed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onUseDifferentName(name.trim()) }, enabled = canSubmit) {
                Text("Use this name")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onBack, enabled = !isLoading) { Text("Back") }
                TextButton(onClick = onUseExistingFolder, enabled = !isLoading) {
                    Text("Use existing folder")
                }
            }
        }
    )
}

@Composable
private fun AddFeedDialog(
    isLoading: Boolean,
    errorMessage: String?,
    onErrorDismissed: () -> Unit,
    onConfirm: (url: String, downloadAll: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var url by rememberSaveable { mutableStateOf("") }
    // Off by default. On a big podcast this is potentially tens of gigabytes on the
    // server, and the scale of it is invisible from a phone.
    var downloadAll by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val canSubmit = url.isNotBlank() && !isLoading

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Feed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter a podcast RSS feed URL or podcast page URL.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        // The previous reason no longer applies once the URL changes.
                        if (errorMessage != null) onErrorDismissed()
                    },
                    label = { Text("Feed URL") },
                    placeholder = { Text("https://example.com/feed.rss") },
                    singleLine = true,
                    enabled = !isLoading,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { if (canSubmit) onConfirm(url, downloadAll) }
                    ),
                    isError = errorMessage != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )

                if (errorMessage != null) {
                    // Shown here rather than as a snackbar: a snackbar renders behind
                    // this dialog, and the dialog stays open on failure so the user
                    // can fix the URL without retyping it.
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isLoading) { downloadAll = !downloadAll },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = downloadAll,
                        onCheckedChange = { downloadAll = it },
                        enabled = !isLoading
                    )
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(
                            "Download the back catalogue",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        // Spelling out both halves: what it downloads (the existing
                        // episodes, on the SERVER — not this phone) and what happens
                        // without it (new episodes still arrive on their own), since
                        // the difference between the two is the whole decision.
                        Text(
                            "Queues every existing episode onto your server once the " +
                                "feed first syncs. New episodes download on their own " +
                                "either way.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url, downloadAll) },
                enabled = canSubmit
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .semantics { contentDescription = "Adding feed" },
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Adding…")
                } else {
                    Text("Add")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        }
    )
}

@Composable
fun EmptyScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Podcasts,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No podcasts yet",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Tap ⋮ above to add your first feed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}