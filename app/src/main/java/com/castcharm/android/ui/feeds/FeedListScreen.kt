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
        AddFeedDialog(
            isLoading = uiState.isAddingFeed,
            onConfirm = { url -> viewModel.addFeed(url) { showAddFeedDialog = false } },
            onDismiss = { if (!uiState.isAddingFeed) showAddFeedDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { AppTopBarTitle(text = "Podcasts") },
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
                        if (uiState.isSyncing || uiState.syncingFeedIds.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .padding(10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .semantics { contentDescription = "Syncing feeds" },
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
            // An automatic refresh (arriving at the tab, or coming back online) gets
            // a centred spinner instead. Suppressed while the skeletons are up, since
            // those already say "loading" and two indicators at once reads as a bug.
            if (uiState.isRefreshing && !uiState.isPullRefreshing && uiState.feeds.isNotEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .semantics { contentDescription = "Loading podcasts" }
                )
            }

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
                                onClick = { onNavigateToEpisodes(feed.id) }
                            )
                        }
                    }
                }
            }
        }
    }
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

@Composable
fun FeedCard(
    feed: FeedEntity,
    baseUrl: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            val rawImageUrl = feed.custom_image_url
                ?: feed.image_url
                ?: if (baseUrl.isNotBlank()) "${baseUrl}api/feeds/${feed.id}/cover.jpg" else null

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

@Composable
private fun AddFeedDialog(
    isLoading: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by rememberSaveable { mutableStateOf("") }
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
                    onValueChange = { url = it },
                    label = { Text("Feed URL") },
                    placeholder = { Text("https://example.com/feed.rss") },
                    singleLine = true,
                    enabled = !isLoading,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { if (canSubmit) onConfirm(url) }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url) },
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