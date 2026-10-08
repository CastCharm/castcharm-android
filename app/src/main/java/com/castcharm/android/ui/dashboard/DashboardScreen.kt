@file:OptIn(ExperimentalMaterial3Api::class)
// DashboardScreen renders the Home tab. It is structured as a vertically scrolling
// LazyColumn with multiple independently-loading sections:
//   - Storage / stats bar (device quota + used bytes)
//   - Feed health alerts (feeds with last_error set)
//   - Continue Listening horizontal scroll (in-progress episodes)
//   - Newest Episodes (recently server-downloaded)
//   - Suggestion Buckets (< 15 min, 15–45 min, 45–90 min, 90+ min)
//   - Top Backlog (feeds with most unplayed episodes)
//
// Each section shows a skeleton placeholder while its loading flag is true,
// then fades into real content when data arrives. Sections are hidden in offline
// mode if they require network data (suggestions, newest).
package com.castcharm.android.ui.dashboard

import com.castcharm.android.ui.shared_components.AppTopBarTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
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
import com.castcharm.android.data.db.entities.FeedEntity
import com.castcharm.android.ui.shared_components.formatDate
import com.castcharm.android.ui.shared_components.formatDuration
import com.castcharm.android.ui.shared_components.ConsumeSnackbarMessage
import com.castcharm.android.ui.shared_components.OfflineModePanel
import com.castcharm.android.ui.shared_components.ReconnectIconButton
import com.castcharm.android.ui.shared_components.SkeletonListRow

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(),
    onNavigateToFeed: (feedId: Int) -> Unit = {},
    onNavigateToEpisode: (feedId: Int, episodeId: Int) -> Unit = { feedId, _ -> onNavigateToFeed(feedId) },
    onPlayEpisode: (episodeId: Int) -> Unit = {},
    onNavigateToFeeds: () -> Unit = {},
    onNavigateToDownloads: () -> Unit = {},
    onSearchClick: (() -> Unit)? = null,
    isOfflineMode: Boolean = false,
    isReconnectInFlight: Boolean = false,
    onRetryConnection: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    ConsumeSnackbarMessage(
        message = uiState.errorMessage,
        snackbarHostState = snackbarHostState,
        onConsumed = { viewModel.clearError() },
        enabled = !isOfflineMode
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { AppTopBarTitle("CastCharm") },
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
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (isOfflineMode) {
            OfflineDashboardContent(
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
                onNavigateToDownloads = onNavigateToDownloads,
                isReconnectInFlight = isReconnectInFlight,
                onRetryConnection = onRetryConnection
            )
            return@Scaffold
        }

        val anyLoading = uiState.statsLoading ||
            uiState.feedHealthLoading ||
            uiState.continueListeningLoading ||
            uiState.newestLoading ||
            uiState.suggestionsLoading ||
            uiState.backlogLoading

        PullToRefreshBox(
            isRefreshing = anyLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = 12.dp,
                end = 12.dp,
                bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Podcasts,
                        label = "Podcasts",
                        value = if (uiState.statsLoading) "…" else uiState.podcastsTotal.toString(),
                        sub = if (uiState.statsLoading) {
                            "Loading…"
                        } else {
                            "${uiState.feedsTotal} total feeds"
                        },
                        isLoading = uiState.statsLoading,
                        onClick = onNavigateToFeeds
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.PhoneAndroid,
                        label = "Device Storage",
                        value = if (uiState.statsLoading) "…" else formatBytes(uiState.deviceStorageBytes),
                        sub = if (uiState.statsLoading) {
                            "Loading…"
                        } else if (uiState.deviceQuotaBytes > 0) {
                            "of ${formatBytes(uiState.deviceQuotaBytes)} quota"
                        } else {
                            "used on device"
                        },
                        isLoading = uiState.statsLoading,
                        onClick = onNavigateToDownloads
                    )
                }
            }

            item {
                DashboardCard(
                    title = "Feed Health",
                    isLoading = uiState.feedHealthLoading
                ) {
                    when {
                        uiState.feedHealthLoading && uiState.feedErrors.isEmpty() -> {
                            InlineLoadingRows(count = 2)
                        }

                        uiState.feedErrors.isEmpty() -> {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    "All feeds healthy",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        else -> {
                            uiState.feedErrors.forEach { feed ->
                                FeedErrorRow(feed = feed, onClick = { onNavigateToFeed(feed.id) })
                            }
                        }
                    }
                }
            }

            item {
                DashboardCard(
                    title = "Continue Listening",
                    isLoading = uiState.continueListeningLoading
                ) {
                    when {
                        uiState.continueListeningLoading && uiState.continueListening.isEmpty() -> {
                            InlineLoadingRows(count = 3)
                        }

                        uiState.continueListening.isEmpty() -> {
                            EmptySectionText("Nothing to resume right now.")
                        }

                        else -> {
                            uiState.continueListening.forEach { item ->
                                EpisodeRow(
                                    episode = item.episode,
                                    feedTitle = item.feed?.title ?: "",
                                    sub = item.episode.play_position_seconds.let { pos ->
                                        if (pos > 59) "${pos / 60}m in" else ""
                                    },
                                    onTitleClick = { onNavigateToEpisode(item.episode.feed_id, item.episode.id) },
                                    onPlayClick = { onPlayEpisode(item.episode.id) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                DashboardCard(
                    title = "Newest Episodes",
                    isLoading = uiState.newestLoading
                ) {
                    when {
                        uiState.newestLoading && uiState.newestEpisodes.isEmpty() -> {
                            InlineLoadingRows(count = 3)
                        }

                        uiState.newestEpisodes.isEmpty() -> {
                            EmptySectionText("No recent downloaded episodes yet.")
                        }

                        else -> {
                            uiState.newestEpisodes.forEach { item ->
                                EpisodeRow(
                                    episode = item.episode,
                                    feedTitle = item.feed?.title ?: "",
                                    sub = item.episode.local_size_bytes?.let { formatBytes(it) } ?: "",
                                    onTitleClick = { onNavigateToEpisode(item.episode.feed_id, item.episode.id) },
                                    onPlayClick = { onPlayEpisode(item.episode.id) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                DashboardCard(
                    title = "Suggested Listening",
                    isLoading = uiState.suggestionsLoading,
                    action = {
                        IconButton(
                            onClick = { viewModel.refreshSuggestions() },
                            enabled = !uiState.suggestionsLoading
                        ) {
                            if (uiState.suggestionsLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .semantics { contentDescription = "Refreshing suggestions" },
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh suggestions",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                ) {
                    when {
                        uiState.suggestionsLoading && uiState.suggestionBuckets.isEmpty() -> {
                            InlineLoadingRows(count = 3)
                        }

                        uiState.suggestionBuckets.isEmpty() -> {
                            EmptySectionText("No suggestions available right now.")
                        }

                        else -> {
                            uiState.suggestionBuckets.forEach { bucket ->
                                Text(
                                    text = bucket.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                                HorizontalDivider(thickness = 0.5.dp)
                                bucket.items.forEach { item ->
                                    EpisodeRow(
                                        episode = item.episode,
                                        feedTitle = item.feed?.title ?: "",
                                        sub = item.episode.duration?.let { formatDuration(it) } ?: "",
                                        onTitleClick = { onNavigateToEpisode(item.episode.feed_id, item.episode.id) },
                                        onPlayClick = { onPlayEpisode(item.episode.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                DashboardCard(
                    title = "Top Backlog",
                    isLoading = uiState.backlogLoading
                ) {
                    when {
                        uiState.backlogLoading && uiState.topBacklog.isEmpty() -> {
                            InlineLoadingRows(count = 3)
                        }

                        uiState.topBacklog.isEmpty() -> {
                            EmptySectionText("Nothing piling up right now.")
                        }

                        else -> {
                            uiState.topBacklog.forEach { feed ->
                                FeedBacklogRow(feed = feed, onClick = { onNavigateToFeed(feed.id) })
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
private fun OfflineDashboardContent(
    modifier: Modifier = Modifier,
    onNavigateToDownloads: () -> Unit,
    isReconnectInFlight: Boolean = false,
    onRetryConnection: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        OfflineModePanel(
            message = "Home isn’t available while offline. You can still open Downloads, use Settings, and play files already saved on this device.",
            primaryActionLabel = "Go to Downloads",
            onPrimaryAction = onNavigateToDownloads,
            reconnectInFlight = isReconnectInFlight,
            onRetryConnection = onRetryConnection
        )
    }
}

@Composable
private fun DashboardCard(
    title: String,
    isLoading: Boolean = false,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                if (isLoading && action == null) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .semantics { contentDescription = "Loading $title" },
                        strokeWidth = 2.dp
                    )
                } else {
                    action?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    sub: String,
    isLoading: Boolean,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier,
        onClick = { onClick?.invoke() },
        enabled = onClick != null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(14.dp)
                            .semantics { contentDescription = "Loading $label" },
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InlineLoadingRows(count: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) {
            SkeletonListRow(showTrailingSpinner = true)
        }
    }
}

@Composable
private fun EmptySectionText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun EpisodeRow(
    episode: EpisodeEntity,
    feedTitle: String,
    sub: String,
    onTitleClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val baseUrl = if (CastCharmApp.apiClient.isInitialized) {
        CastCharmApp.apiClient.getBaseUrl()
    } else {
        ""
    }
    val imageUrl = episode.custom_image_url
        ?: episode.episode_image_url
        ?: episode.feed_image_url
        ?: if (baseUrl.isNotBlank()) "${baseUrl}api/feeds/${episode.feed_id}/cover.jpg" else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onTitleClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = episode.title,
                imageLoader = CastCharmApp.imageLoader,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val dateStr = formatDate(episode.published_at)
                val subtitleParts = listOf(feedTitle, dateStr, sub).filter { it.isNotEmpty() }
                if (subtitleParts.isNotEmpty()) {
                    Text(
                        text = subtitleParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        val progress = if (
            episode.duration != null &&
            episode.duration > 0 &&
            episode.play_position_seconds > 0
        ) {
            episode.play_position_seconds.toFloat() / episode.duration
        } else {
            0f
        }

        IconButton(
            onClick = onPlayClick,
            modifier = Modifier.size(48.dp)
        ) {
            PlayResumeIcon(progress = progress, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun PlayResumeIcon(progress: Float, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    if (progress > 0f) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 2.dp,
                color = primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Resume",
                tint = primary,
                modifier = Modifier.size(20.dp)
            )
        }
    } else {
        Icon(
            Icons.Default.PlayCircle,
            contentDescription = "Play",
            tint = primary,
            modifier = modifier
        )
    }
}

@Composable
private fun FeedErrorRow(feed: FeedEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feed.title.ifEmpty { feed.url },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = (feed.last_error ?: "").take(120),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FeedBacklogRow(feed: FeedEntity, onClick: () -> Unit) {
    val imageUrl = feed.custom_image_url ?: feed.image_url
    val pct = if (feed.downloaded_count > 0) {
        (feed.unplayed_count.toFloat() / feed.downloaded_count * 100).toInt()
    } else {
        0
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = feed.title,
                imageLoader = CastCharmApp.imageLoader,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feed.title.ifEmpty { feed.url },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${feed.unplayed_count} of ${feed.downloaded_count} unplayed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier
                    .width(48.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
                text = "$pct%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
}