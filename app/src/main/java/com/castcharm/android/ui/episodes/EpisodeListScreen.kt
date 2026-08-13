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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.castcharm.android.data.api.models.feedCoverUrl
import com.castcharm.android.ui.playlists.AddToPlaylistSheet
import com.castcharm.android.ui.shared_components.ProvideSelectionActions
import com.castcharm.android.ui.shared_components.SelectionAction
import com.castcharm.android.ui.shared_components.AppTopBarTitle
import com.castcharm.android.ui.shared_components.ConsumeSnackbarMessage
import com.castcharm.android.ui.shared_components.EpisodeCard
import com.castcharm.android.ui.shared_components.EpisodeCardSkeleton
import com.castcharm.android.ui.shared_components.EpisodeDownloadActionOverride
import com.castcharm.android.ui.shared_components.OfflineModePanel
import com.castcharm.android.ui.shared_components.PlaceholderArtwork
import com.castcharm.android.ui.shared_components.ReconnectIconButton
import com.castcharm.android.ui.shared_components.stripHtml

/**
 * The items the episode list draws above the first episode.
 *
 * An enum rather than a count so that "what is drawn" and "how many rows the
 * episodes are offset by" cannot be stated separately and disagree. Everything
 * that turns an episode's position into a list index reads the size of the list
 * of these that the screen built.
 */
private enum class EpisodeListHeader { Feed, Filters, EmptyState }

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
    val isSelectionMode = uiState.selectionMode

    // Which leading items the list is drawing right now. Episode N lives at list
    // index N + listHeaders.size, and the deep-link jump, the arrival correction
    // and the hydration window all depend on that being exact — a leading item
    // counted but not drawn, or drawn but not counted, puts every one of them a
    // row out.
    //
    // So this list is the single statement of what comes first: the LazyColumn
    // below draws it, and the offset is its size. Neither can drift from the
    // other, and a new leading item is added here once.
    val listHeaders = buildList {
        if (feed != null) add(EpisodeListHeader.Feed)
        if (!isSelectionMode) add(EpisodeListHeader.Filters)
        // An empty feed with no filter applied is a whole different screen rather
        // than a row in this one, so it is not one of these.
        if (uiState.orderedIds.isEmpty() && uiState.filter != EpisodeFilter.ALL) {
            add(EpisodeListHeader.EmptyState)
        }
    }
    val headerItemCount = listHeaders.size
    // The jump effect below outlives changes to this count — the feed header
    // appears as soon as the cached feed row loads, which can land after the
    // effect has started — so it reads the current value rather than whatever was
    // in scope when it launched, and does not restart (and re-scroll) when the
    // count changes.
    val currentHeaderCount by rememberUpdatedState(headerItemCount)

    // Jumping to an episode from the dashboard or from search.
    //
    // This used to be a hunt: widen the loaded window, look for the episode, widen
    // again — thousands of records and a dozen round trips to reach something a few
    // hundred back, and a "sorry, too far back" message when it gave up. The feed's
    // id index makes the position a lookup, and every row exists as a placeholder
    // from the start, so the jump is immediate and the episode loads once it is on
    // screen.
    // The jump is a one-shot, not a property of the screen.
    //
    // highlightEpisodeId comes from the navigation route and therefore never
    // changes while this screen is on the back stack — but a LaunchedEffect
    // re-runs whenever the composable re-enters composition, which happens every
    // time something is shown over the list and then dismissed. Closing the player
    // was enough to fire it again and yank the user back to the deep-linked
    // episode, discarding wherever they had scrolled to since.
    //
    // rememberSaveable rather than remember: the navigation entry keeps its saved
    // state, so this survives the re-entry that caused the bug, and a rotation
    // too. Keyed on the id so arriving from a NEW deep link re-arms it.
    var hasJumpedToHighlight by rememberSaveable(highlightEpisodeId) { mutableStateOf(false) }

    // Set while one of the two automatic scrolls is running. The hydrated window
    // follows whatever is on screen, so without this a travelling scroll would drag
    // it across every position on the way and fetch a page for each — hundreds of
    // requests for episodes the user is passing, not reading. The window is moved
    // once, on arrival, instead.
    var autoScrolling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(highlightEpisodeId) {
        if (highlightEpisodeId == null || hasJumpedToHighlight) return@LaunchedEffect
        // Two different failures, told apart rather than collapsed into one
        // message: the feed never loaded, or it loaded and this episode is not in
        // it. Reporting a slow network as "that episode is gone" sends the user
        // looking for a problem that is not there.
        val loaded = withTimeoutOrNull(20_000) {
            viewModel.uiState.first { it.orderedIds.isNotEmpty() }
        } != null
        if (!loaded) {
            viewModel.showMessage("Couldn't load this podcast in time to jump to that episode.")
            return@LaunchedEffect
        }
        val idx = viewModel.indexOfEpisode(highlightEpisodeId)
        if (idx < 0) {
            viewModel.showMessage("That episode isn't in this podcast any more.")
            return@LaunchedEffect
        }
        // Marked before the scroll, not after: both outcomes count as having had
        // our one go at it, and a cancellation partway through must not leave the
        // jump armed to fire again later.
        hasJumpedToHighlight = true
        // Wait for the LazyColumn to lay out that far. With placeholders this is
        // the next frame rather than however long the network takes.
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > idx + currentHeaderCount }

        // Wait for the feed header before holding, rather than holding for a fixed
        // beat and hoping it arrived. Of everything drawn above the episodes it is
        // the only part that arrives asynchronously — usually from the local cache
        // within a frame or two, but a feed opened for the first time has to be
        // fetched, and a blind timer would start the trip over a headerless list.
        //
        // Cosmetic and correct at once. The trip should begin from a page that
        // looks finished; and the destination index is worked out from the leading
        // item count below, which this is what settles.
        //
        // Capped, because a feed that never loads must not strand the jump.
        withTimeoutOrNull(JUMP_HEADER_WAIT_MS) {
            viewModel.uiState.first { it.feed != null }
        }

        // Then hold a beat so the header and the first rows have painted, and
        // travel down — rather than opening on a stretch of the feed with no sense
        // of how it was reached.
        val startedAt = listState.firstVisibleItemIndex
        delay(JUMP_DWELL_MS)
        // The dwell is a window for the user to take over. If they have started
        // scrolling themselves, they have said where they want to be; hauling them
        // somewhere else now would be the screen fighting them.
        if (listState.firstVisibleItemIndex != startedAt || listState.isScrollInProgress) {
            return@LaunchedEffect
        }

        // Read only now that the header has settled, so it counts what is actually
        // on screen at the moment of the scroll.
        val target = idx + currentHeaderCount

        autoScrolling = true
        try {
            // Give the destination a head start: its page is fetched now so the row
            // has a chance to be real by the time it arrives, instead of being a
            // skeleton that fills in after the list has stopped.
            viewModel.onVisibleRangeChanged(idx, idx)

            // Only the last screenful is animated. Travelling the whole way would
            // make Compose measure every row in between — the stutter that the
            // jump-to-top button had — and would take longer the further back the
            // episode was. Covering the rest instantly keeps the trip identical
            // whatever the distance, and the eye cannot follow rows moving that
            // fast anyway.
            val rowsOnScreen = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(2)
            val approachFrom = (target - rowsOnScreen + 1).coerceAtLeast(0)
            if (approachFrom > listState.firstVisibleItemIndex) {
                listState.scrollToItem(approachFrom)
            }

            // Measured rather than assumed: rows differ in height, and the target
            // is the one that is expanded. Its own offset is the exact distance to
            // put it at the top of the viewport, so the animation lands on it
            // instead of near it.
            val distancePx = withTimeoutOrNull(1_000) {
                snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == target }
                        ?.offset
                }.first { it != null }
            }
            if (distancePx != null && distancePx > 0) {
                listState.animateScrollBy(
                    distancePx.toFloat(),
                    tween(JUMP_TRAVEL_MS, easing = FastOutSlowInEasing)
                )
            } else {
                // Rows taller than expected, so the target never came into view to
                // be measured. Still a scroll, just one whose duration Compose
                // picks.
                listState.animateScrollToItem(target)
            }

            // Self-correct. The distance was measured before the trip, at the same
            // moment the destination's page was requested — so the rows being
            // travelled across can swap skeleton for real content on the way and
            // change height, moving the target while the animation heads for where
            // it used to be.
            //
            // Not needed for the header arriving late, despite the shift that
            // causes: the episodes are keyed, so the list re-anchors the first
            // visible item by key and the viewport stays on the same episode.
            //
            // Only reached when the animation ran to completion: if the user
            // grabbed the list, their drag cancels this coroutine and nothing snaps
            // out from under them.
            val settled = idx + currentHeaderCount
            if (listState.firstVisibleItemIndex != settled) {
                listState.scrollToItem(settled)
            }
        } finally {
            // In a finally so an interrupted trip — the user grabbing the list, or
            // leaving the screen — cannot leave hydration switched off for the rest
            // of this screen's life.
            autoScrolling = false
            val landed = listState.firstVisibleItemIndex - currentHeaderCount
            viewModel.onVisibleRangeChanged(landed.coerceAtLeast(0), landed.coerceAtLeast(0))
        }
    }

    // Keep the loaded window following the user. Distinct from what is drawn: the
    // list always draws every episode in the feed, this decides which ones are
    // held in memory with their content filled in.
    LaunchedEffect(listState, uiState.orderedIds.size, headerItemCount) {
        snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo
            if (info.isEmpty()) IntRange.EMPTY
            else IntRange(info.first().index, info.last().index)
        }
            .distinctUntilChanged()
            .collect { range ->
                if (range.isEmpty() || autoScrolling) return@collect
                viewModel.onVisibleRangeChanged(
                    (range.first - headerItemCount).coerceAtLeast(0),
                    (range.last - headerItemCount).coerceAtLeast(0)
                )
            }
    }

    // Multi-select no longer ends on its own when the selection empties, so back
    // has to be an explicit way out of it — otherwise the only exit is the X and
    // back would drop the user off the screen entirely mid-batch.
    BackHandler(enabled = isSelectionMode) { viewModel.exitSelectionMode() }
    var showDownloadConfirm by remember { mutableStateOf(false) }
    var showDeleteDownloadsConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    // The filter now lives in the ViewModel. It cannot be applied here any more:
    // the screen only ever holds the visible window, so filtering that would hide
    // every match outside it. Each filter instead re-reads the feed's ordering —
    // from the server for played state, from Room for "Downloaded" — and the list
    // renders the result the same way it renders the unfiltered feed.
    val filter = uiState.filter

    // Reset the filter when the user enters selection mode — a batch should start
    // from a clean view.
    LaunchedEffect(isSelectionMode) {
        if (isSelectionMode && filter != EpisodeFilter.ALL) viewModel.setFilter(EpisodeFilter.ALL)
    }

    val orderedIds = uiState.orderedIds

    // Enable the batch-delete action only if at least one selected episode
    // actually has a file on disk to remove. Checked against the feed's full set
    // of downloads rather than the loaded window — a whole-feed selection is
    // mostly episodes the screen has not fetched, and testing only those would
    // grey the action out whenever the downloads happen to sit off screen.
    val anySelectedDownloaded = remember(uiState.selectedEpisodes, uiState.downloadedEpisodeIds) {
        uiState.selectedEpisodes.any { it in uiState.downloadedEpisodeIds }
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

    // Whether "mark played" or "mark unplayed" is offered depends on the current
    // state of the selection — offering whichever change would actually alter the
    // picked rows.
    // Only claims "all played" when every selected episode is actually loaded and
    // played. A whole-feed selection is mostly episodes the screen has not fetched,
    // whose played state is genuinely unknown — judging from the loaded few would
    // offer "Mark unplayed" for a feed that is mostly unplayed.
    val allSelectedPlayed = remember(uiState.selectedEpisodes, uiState.loadedById) {
        val loaded = uiState.selectedEpisodes.mapNotNull { uiState.loadedById[it] }
        uiState.selectedEpisodes.isNotEmpty() &&
            loaded.size == uiState.selectedEpisodes.size &&
            loaded.all { it.played }
    }

    // Against the whole feed, not the loaded window — "Select all" now selects
    // every episode under the current filter, so anything less than that must not
    // report itself as already selected.
    val allSelected = remember(uiState.orderedIds, uiState.selectedEpisodes) {
        uiState.orderedIds.isNotEmpty() &&
            uiState.orderedIds.all { it in uiState.selectedEpisodes }
    }

    // The selection can now legitimately be empty while the bar is still up (see
    // selectionMode in EpisodeListUiState), so every action that operates on the
    // picked rows has to be greyed out rather than quietly doing nothing.
    val anySelected = uiState.selectedEpisodes.isNotEmpty()

    // Hands these to MainScaffold, which shows them in place of the tab bar.
    ProvideSelectionActions(
        active = isSelectionMode,
        allSelected,
        allSelectedPlayed,
        anySelectedDownloaded,
        anySelected,
    ) {
        listOf(
            // Flips to "Select none" once everything is picked — an always-on
            // "Select all" is a dead button at that point. "Select none" only
            // empties the selection; leaving multi-select is the X in the top bar,
            // so unticking everything doesn't force a fresh long-press to get back in.
            SelectionAction(
                icon = if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                label = if (allSelected) "Select none" else "Select all",
                onClick = {
                    if (allSelected) viewModel.clearSelection() else viewModel.selectAll()
                }
            ),
            // Same bare tick in both directions, matching the per-episode card:
            // the label carries the direction, and the row markers show the
            // resulting state. A ringed vs. un-ringed tick here would collide
            // with the meaning those two glyphs now carry on the cards.
            SelectionAction(
                icon = Icons.Default.Check,
                label = if (allSelectedPlayed) "Mark unplayed" else "Mark played",
                onClick = { viewModel.markSelectedPlayed(!allSelectedPlayed) },
                enabled = anySelected
            ),
            SelectionAction(
                icon = Icons.Default.PhoneAndroid,
                label = "Download",
                onClick = { showDownloadConfirm = true },
                enabled = anySelected
            ),
            SelectionAction(
                icon = Icons.Default.DeleteForever,
                label = "Delete files",
                onClick = { showDeleteDownloadsConfirm = true },
                enabled = anySelectedDownloaded,
                destructive = true
            ),
        )
    }

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
                        IconButton(onClick = { viewModel.exitSelectionMode() }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    } else {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    when {
                        // Bulk actions live in the SelectionActionBar at the bottom
                        // of this screen, where they can carry text labels.
                        isSelectionMode -> Unit

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
            uiState.isInitialLoading && orderedIds.isEmpty() -> {
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

            orderedIds.isEmpty() && filter == EpisodeFilter.ALL -> {
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
                    // Drawn from the same list that the episode offset is measured
                    // from, so the two cannot disagree. Which ones appear is
                    // decided where that list is built, not here.
                    //
                    // Keyed, like the episodes below them: when the feed header
                    // arrives late every following item shifts down one, and a
                    // keyed list re-anchors on whatever the user was looking at
                    // instead of letting the content jump under them.
                    listHeaders.forEach { header ->
                        item(key = header.name) {
                            when (header) {
                                EpisodeListHeader.Feed -> feed?.let {
                                    FeedHeader(
                                        feedTitle = it.title,
                                        feedDescription = it.description,
                                        imageUrl = it.custom_image_url ?: it.image_url
                                        ?: feedCoverUrl(baseUrl, it.id, it.url),
                                        episodeCount = it.episode_count,
                                        unplayedCount = it.unplayed_count,
                                        onPlayFeed = if (!isOfflineMode && it.unplayed_count > 0) {
                                            { viewModel.playFeed { episodeId -> onPlayEpisode(episodeId) } }
                                        } else null
                                    )
                                }

                                // Hidden in multi-select to keep the batch context
                                // clean.
                                EpisodeListHeader.Filters -> EpisodeFilterChips(
                                    selected = filter,
                                    onSelect = { viewModel.setFilter(it) },
                                )

                                EpisodeListHeader.EmptyState -> EmptyFilterState(
                                    filter = filter,
                                    onClear = { viewModel.setFilter(EpisodeFilter.ALL) }
                                )
                            }
                        }
                    }

                    // A row per episode in the whole feed. Ones outside the loaded
                    // window draw as skeletons and fill in as they scroll into
                    // view, which is what lets the list jump straight to any
                    // position instead of having to load its way down to it.
                    items(
                        count = orderedIds.size,
                        key = { orderedIds[it] }
                    ) { index ->
                        // The list is the id ordering; the content is whatever the
                        // window has loaded. LazyColumn only composes what is on
                        // screen, so this lookup happens a couple of dozen times a
                        // frame rather than once per episode in the podcast.
                        val episode = uiState.loadedById[orderedIds[index]]
                        if (episode == null) {
                            EpisodeCardSkeleton(showSpinner = false)
                            return@items
                        }
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

        // Jump back to the top. Only offered once the user is far enough down that
        // scrolling back would be tedious — appearing after a couple of rows would
        // just be something permanently in the way.
        //
        // Hidden during multi-select, where the bottom of the screen belongs to the
        // batch actions.
        val showScrollToTop by remember {
            derivedStateOf { listState.firstVisibleItemIndex > SCROLL_TO_TOP_AFTER_ROWS }
        }

        // Being far enough down only makes the button *eligible*. It also has to
        // have been of recent use: a list that has been still for a few seconds is
        // one the user is reading rather than traversing, and the button covers the
        // corner of a card while they do it. So it withdraws, and any scroll brings
        // it back — position permitting.
        //
        // Read through snapshotFlow rather than in composition, so a gesture
        // starting and stopping does not invalidate this whole subtree. collectLatest
        // cancels a pending delay the moment scrolling resumes, which is what makes
        // the button come back immediately instead of after the countdown expires.
        var scrollIdle by remember { mutableStateOf(false) }
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }
                .collectLatest { scrolling ->
                    if (scrolling) {
                        scrollIdle = false
                    } else {
                        delay(SCROLL_TO_TOP_IDLE_MS)
                        scrollIdle = true
                    }
                }
        }

        AnimatedVisibility(
            visible = showScrollToTop && !isSelectionMode && !scrollIdle,
            // Deliberately asymmetric. Appearing answers a gesture, so it wants to
            // feel immediate; disappearing answers nothing the user did, and an
            // abrupt vanish at the edge of vision reads as a glitch. The long exit
            // also stays hit-testable the whole way down, so a tap already on its
            // way still lands.
            enter = fadeIn(tween(durationMillis = 150)),
            exit = fadeOut(tween(durationMillis = 700)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
        ) {
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        autoScrolling = true
                        try {
                            // Animating the whole way from deep in a feed makes
                            // Compose measure and lay out every row it travels
                            // past, which is what the stutter was. Jump the bulk
                            // of the distance instantly, then animate only the
                            // last screenful or so — the part the eye actually
                            // follows — so it reads as a smooth glide to the top
                            // however far down the user started.
                            if (listState.firstVisibleItemIndex > SCROLL_TO_TOP_RUNWAY) {
                                listState.scrollToItem(SCROLL_TO_TOP_RUNWAY)
                            }
                            listState.animateScrollToItem(0)
                        } finally {
                            // Cleared before the window is moved, and in a finally so
                            // an interrupted animation cannot leave hydration switched
                            // off for the rest of the screen's life.
                            autoScrolling = false
                            viewModel.onVisibleRangeChanged(0, 0)
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Scroll to top",
                )
            }
        }
        }
    }

    addToPlaylistSheetEpisodeId?.let { epId ->
        val ep = uiState.loadedById[epId]
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