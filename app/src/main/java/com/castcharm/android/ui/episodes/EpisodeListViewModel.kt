package com.castcharm.android.ui.episodes

// EpisodeListViewModel drives the episode list for a single feed. It:
//   - Combines the episodes Flow and the downloads Flow so the UI sees live
//     download-in-progress indicators without separate refresh calls.
//   - Uses a "hasMore" flag (from the server's limit+1 trick) to show a
//     "Load More" button at the bottom of the list.
//   - Supports multi-select mode: selectedEpisodes is the selected set; the
//     UI switches to multi-select mode when any episode is selected.
//   - Detects when a feed is deleted server-side (feed disappears mid-refresh)
//     and surfaces an error rather than showing a stale empty list.

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.db.entities.FeedEntity
import com.castcharm.android.data.repository.EpisodeRepository
import com.castcharm.android.data.api.ServerLimits
import com.castcharm.android.data.repository.FeedRepository
import com.castcharm.android.data.api.models.FeedUpdateRequest
import com.castcharm.android.data.api.models.PlayerPlayRequest
import com.castcharm.android.download.DownloadScheduler
import com.castcharm.android.download.StorageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which episodes the list is showing. Drives the chips above the list. */
enum class EpisodeFilter(val label: String, val indexFilter: String?) {
    ALL("All", "all"),
    UNPLAYED("Unplayed", "unplayed"),
    // No server-side equivalent: on the phone this means "the file is on this
    // device", which is local state. Answered from Room instead — see
    // EpisodeDao.getDownloadedEpisodeIdsByFeedFlow.
    DOWNLOADED("Downloaded", null),
    IN_PROGRESS("In progress", "in_progress"),
}

data class EpisodeListUiState(
    val feed: FeedEntity? = null,
    // Every episode in the feed, in display order — ids only. This is the feed's
    // full shape, and the list draws one row per entry whether or not the episode
    // behind it has been fetched.
    val orderedIds: List<Int> = emptyList(),
    // The episodes actually loaded, keyed by id — the current window and nothing
    // else. The list looks each row up here as it composes it, so an id with no
    // entry draws as a skeleton and fills in once it arrives.
    //
    // A map rather than a prepared list of rows: the list is rebuilt on every
    // database emission, and an active download emits several times a second, so
    // anything O(feed) per emission is work proportional to the whole podcast to
    // redraw a screenful of it.
    val loadedById: Map<Int, EpisodeEntity> = emptyMap(),
    val filter: EpisodeFilter = EpisodeFilter.ALL,
    // False when the server predates /episode-index, or when we are offline. The
    // list then shows only what is cached and keeps the old "Load More" button.
    val isIndexed: Boolean = false,
    val activePhoneDownloadEpisodeIds: Set<Int> = emptySet(),
    // Every episode of this feed with a file on the phone, not just the loaded
    // ones — so "delete downloads" over a whole-feed selection can tell whether
    // there is anything to delete without having fetched all of it.
    val downloadedEpisodeIds: Set<Int> = emptySet(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val hasMore: Boolean = false,
    val selectedEpisodes: Set<Int> = emptySet(),
    // Tracked separately from selectedEpisodes being non-empty. An empty
    // selection is a legitimate state to sit in — "Select none" and unticking
    // the last row should leave the user in multi-select with the bar still up,
    // not silently kick them back to browsing and force another long-press.
    // Leaving multi-select is an explicit act: the X in the top bar, or
    // finishing a bulk action.
    val selectionMode: Boolean = false,
    val playlistMemberEpisodeIds: Set<Int> = emptySet(),
    // For a listen-in-order feed: what "Continue" will play (null = caught up).
    val nextUp: NextUp? = null
)

data class NextUp(
    val episodeId: Int,
    val seqNumber: Int?,
    val title: String?,
    val positionSeconds: Int,
    val resume: Boolean
) {
    /** "Ep. 12 · Title · 14 min in" */
    val line: String get() = buildString {
        seqNumber?.let { append("Ep. ").append(it).append(" \u00B7 ") }
        append(title ?: "Untitled")
        if (resume && positionSeconds >= 60) append(" \u00B7 ").append(positionSeconds / 60).append(" min in")
    }
}

// How many more episodes one tap of "Load More" pulls in. Only reachable on the
// unindexed fallback path; an indexed feed has no Load More button.
const val LOAD_MORE_STEP = 100

// Episodes per fetched page. Small enough that a page lands quickly and a scroll
// that overshoots wastes little, large enough that steady scrolling stays ahead
// of the user.
const val PAGE_SIZE = 50

// How many pages either side of the visible range to keep hydrated. The window
// is what bounds memory: a page in front, a page behind, and whatever the screen
// itself spans — roughly 150 episodes held at a time regardless of feed size.
const val WINDOW_PAGE_MARGIN = 1

// How far down the list the user has to be before the jump-to-top button appears.
// Deliberately past a screenful: offering it after a row or two would put a
// control permanently over content the user can already see the top of.
const val SCROLL_TO_TOP_AFTER_ROWS = 8

// How many rows the jump-to-top animation actually animates over. Anything above
// this is covered by an instant jump first, so the cost of the animation does not
// grow with how deep into the feed the user was.
const val SCROLL_TO_TOP_RUNWAY = 12

// How long the list has to sit still before the jump-to-top button fades out. It
// sits over the bottom-right of a row, so leaving it up while the user is reading
// hides part of the very content they stopped to look at. Scrolling brings it
// straight back, which is the only moment it is any use.
//
// Tuned short because re-summoning it is free — the hand is already in scrolling
// posture — unlike a media control, which is why those sit at five seconds. Two
// and a half seconds was tried on device and still felt like loitering.
const val SCROLL_TO_TOP_IDLE_MS = 1_500L

// Arriving from a deep link, the list holds still at the top for this long before
// travelling down to the episode. Long enough for the header art and the first
// rows to paint, so the trip starts from a page that looks finished rather than
// from a screen of skeletons.
const val JUMP_DWELL_MS = 500L

// Longest the jump will wait for the feed header before travelling without it.
// The header is normally a local cache read, so this is only reached on a feed
// being opened for the first time over a bad connection — where standing still
// indefinitely would be worse than arriving at a headerless list.
const val JUMP_HEADER_WAIT_MS = 3_000L

// How long that trip takes. Fixed, not proportional to distance: the list covers
// everything above the last screenful instantly, so an episode 2,000 back arrives
// in the same time as one 20 back.
const val JUMP_TRAVEL_MS = 700

// Most episodes the hydrated window may span. A whole page-aligned multiple, well
// under SQLite's 999-variable ceiling for the IN () clause it becomes.
const val MAX_WINDOW_IDS = PAGE_SIZE * 8

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeListViewModel(private val feedId: Int) : ViewModel() {
    private val _uiState = MutableStateFlow(EpisodeListUiState())
    val uiState: StateFlow<EpisodeListUiState> = _uiState.asStateFlow()

    private val db = AppDatabase.getDatabase(CastCharmApp.instance)
    private val downloadScheduler = DownloadScheduler(CastCharmApp.instance)
    private val storageManager = StorageManager(CastCharmApp.instance)

    /**
     * The slice of orderedIds currently observed from Room, and where it starts.
     *
     * The two travel together on purpose. They were a list plus a separate
     * windowStart field, and the pair could be read while it disagreed with the
     * hydrated episodes derived from an earlier window — which made every page of
     * a freshly moved window look unloaded, so scrolling back over cached
     * episodes re-fetched all of them from the server.
     */
    private data class Window(val start: Int, val ids: List<Int>)

    // ── State used by observeLocalData ─────────────────────────────────────────
    // These MUST stay above the init block below. Kotlin runs property
    // initialisers and init blocks in declaration order, and viewModelScope
    // dispatches on Main.immediate — so a coroutine launched from init starts
    // running synchronously, right there, while any property declared further
    // down the class is still null. Declared after init, `window` reached
    // flatMapLatest as a null upstream and every navigation to an episode list
    // crashed with a NullPointerException from inside the flow machinery.

    // Everything outside the window is dropped from memory and redrawn as a
    // skeleton if the user scrolls back — Room still has the rows, so returning is
    // a local read.
    private val window = MutableStateFlow(Window(0, emptyList()))

    // Pages already being fetched, so a scroll that crosses the same boundary
    // twice does not fire the request twice.
    private val pagesInFlight = mutableSetOf<Int>()

    // Pages whose fetch failed. Held back until the next refresh so a server that
    // is down does not turn every scroll into another doomed request.
    private val failedPages = mutableSetOf<Int>()

    init {
        // Sets up DB observation only. The server pull is triggered once, by the
        // screen's ON_RESUME observer (OnScreenResumed in MainActivity), so that
        // arriving at this screen produces exactly one refresh.
        observeLocalData()
    }

    private fun feedRepositoryOrNull(): FeedRepository? {
        return if (CastCharmApp.apiClient.isInitialized) {
            FeedRepository(
                CastCharmApp.apiClient.getApi(),
                db.feedDao()
            )
        } else {
            null
        }
    }

    private fun episodeRepositoryOrNull(): EpisodeRepository? {
        return if (CastCharmApp.apiClient.isInitialized) {
            EpisodeRepository(
                CastCharmApp.apiClient.getApi(),
                db.episodeDao()
            )
        } else {
            null
        }
    }

    private fun observeLocalData() {
        // Hydration: watch only the current window, joined with the downloads
        // table so phone-side download activity (new row, progress tick, row
        // removed) redraws without an explicit refresh.
        //
        // This used to observe getEpisodesByFeed(feedId) — every episode in the
        // feed, full rows including descriptions, reloaded on every write to the
        // table. On a 2,000-episode podcast that is megabytes of churn to keep a
        // screenful of about twenty rows up to date.
        viewModelScope.launch {
            window
                // The window is carried through with its own result so the
                // collector always knows exactly which slice it is looking at,
                // rather than reading back a field that may have moved on.
                .flatMapLatest { w ->
                    if (w.ids.isEmpty()) {
                        flowOf(w to emptyList<EpisodeEntity>())
                    } else {
                        db.episodeDao().getEpisodesByIdsFlow(w.ids).map { w to it }
                    }
                }
                .combine(db.downloadDao().getAllDownloads()) { (w, episodes), downloads ->
                    Triple(w, episodes, downloads.map { it.episode_id }.toSet())
                }
                .collectLatest { (w, episodes, activeDownloadIds) ->
                    val byId = episodes.associateBy { it.id }
                    _uiState.update { state ->
                        state.copy(
                            loadedById = byId,
                            activePhoneDownloadEpisodeIds = byId.keys
                                .filter { it in activeDownloadIds }
                                .toSet()
                        )
                    }
                    // Rows inside the window that Room has never seen have to come
                    // from the server. This is the only place hydration is
                    // triggered, so it always runs against a window and a loaded
                    // set that describe the same slice.
                    requestMissingPages(w, byId.keys)
                }
        }

        // Ordering for the "Downloaded" filter, which is local by nature.
        viewModelScope.launch {
            db.episodeDao().getDownloadedEpisodeIdsByFeedFlow(feedId).collectLatest { ids ->
                _uiState.update { it.copy(downloadedEpisodeIds = ids.toSet()) }
                // Only when the set actually changed. This flow watches the
                // episodes table, so an active download re-emits on every progress
                // tick with an identical list, and applyOrdering is not cheap —
                // it rebuilds a row per episode in the feed and resets the window.
                if (_uiState.value.filter == EpisodeFilter.DOWNLOADED &&
                    ids != _uiState.value.orderedIds
                ) {
                    applyOrdering(ids, indexed = true)
                }
            }
        }

        // Show the cached feed header straight away; refresh() replaces it with
        // server data moments later.
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(feed = db.feedDao().getFeedOnce(feedId)) }
            } catch (e: Exception) {
                Log.e("EpisodeListViewModel", "Failed to load feed info", e)
            }
        }
    }

    /**
     * Installs a new id ordering — the feed's shape — and rebuilds the rows.
     *
     * Existing hydrated episodes are carried over, so switching filters or
     * re-running a refresh does not blank rows the app already has.
     */
    private fun applyOrdering(ids: List<Int>, indexed: Boolean) {
        val idSet = ids.toHashSet()
        _uiState.update { state ->
            // Carry over what is already loaded and still present, so a refresh
            // or filter switch does not blank rows the app already has.
            val keep = state.loadedById.filterKeys { it in idSet }
            state.copy(
                orderedIds = ids,
                loadedById = keep,
                isIndexed = indexed,
                isInitialLoading = false,
                // An indexed feed knows its full extent, so there is nothing left
                // to "load more" of.
                hasMore = if (indexed) false else state.hasMore
            )
        }

        failedPages.clear()

        // Re-anchor the window into the new ordering, keeping the user's place if
        // any of what they were looking at survived — a refresh should not throw
        // them back to the top — and starting from the top if none of it did,
        // which is what a filter switch looks like. The span is preserved so a
        // refresh cannot shrink the window out from under what is on screen.
        val previous = window.value
        val anchor = previous.ids.firstOrNull { it in idSet }?.let { ids.indexOf(it) } ?: 0
        val span = previous.ids.size.coerceAtLeast(PAGE_SIZE * (2 * WINDOW_PAGE_MARGIN + 1))
        setWindow(anchor, anchor + span)
    }

    /** Re-reads the visible episodes from the server, loaded or not. */
    private fun refetchWindow() {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return
        val ids = window.value.ids
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { episodeRepositoryOrNull()?.fetchEpisodesByIds(feedId, ids) }
                .onFailure { Log.e("EpisodeListViewModel", "Failed to refresh visible episodes", it) }
        }
    }

    /**
     * Points the hydrated window at orderedIds[[from], [to]), snapped outwards to
     * whole pages.
     *
     * The alignment is not tidiness, it is what stops a fetch loop.
     * requestMissingPages works in whole PAGE_SIZE blocks, so a window starting
     * mid-page leaves the front of its first block outside the window: those ids
     * are never observed from Room, so they are never in the loaded set, so they
     * look missing on every pass. Fetching them writes to the episodes table,
     * which makes Room re-emit, which calls requestMissingPages again — and they
     * are still outside the window, so it fetches them again, forever.
     *
     * Reachable from applyOrdering, which anchors on wherever the user was: a
     * refresh that finds new episodes shifts every index down and the anchor stops
     * being page-aligned.
     */
    private fun setWindow(from: Int, to: Int) {
        val ids = _uiState.value.orderedIds
        val start = ((from.coerceIn(0, ids.size)) / PAGE_SIZE) * PAGE_SIZE
        val alignedEnd = ceilToPage(to.coerceIn(start, ids.size))
        // Hard ceiling as well as the page alignment. The window becomes an
        // IN (:ids) clause in Room, and SQLite binds one variable per id against a
        // limit of 999 on older Android builds — so the size of that clause must
        // not be a function of how many rows the layout happened to report.
        val end = alignedEnd.coerceAtMost(ids.size).coerceAtMost(start + MAX_WINDOW_IDS)
        val next = Window(start, ids.subList(start, end))
        // Setting this restarts the Room observation, which is what asks for any
        // episodes the new window is missing. Nothing else needs to trigger that,
        // and nothing else should: a caller that fetched here would be working
        // from the previous window's loaded set.
        if (next != window.value) window.value = next
    }

    private fun ceilToPage(value: Int): Int =
        ((value + PAGE_SIZE - 1) / PAGE_SIZE) * PAGE_SIZE

    /** Switches which episodes the list shows, re-reading the ordering to match. */
    fun setFilter(filter: EpisodeFilter) {
        if (_uiState.value.filter == filter) return
        _uiState.update { it.copy(filter = filter) }
        viewModelScope.launch { loadOrdering(filter) }
    }

    /**
     * Called by the list as it scrolls. Moves the hydrated window to follow the
     * user and kicks off fetches for any page inside it that is still empty.
     */
    fun onVisibleRangeChanged(firstVisible: Int, lastVisible: Int) {
        val ids = _uiState.value.orderedIds
        if (ids.isEmpty()) return

        val firstPage = (firstVisible / PAGE_SIZE - WINDOW_PAGE_MARGIN).coerceAtLeast(0)
        val lastPage = lastVisible.coerceAtLeast(firstVisible) / PAGE_SIZE + WINDOW_PAGE_MARGIN
        setWindow(firstPage * PAGE_SIZE, (lastPage + 1) * PAGE_SIZE)
    }

    /**
     * Fetches the episodes inside the window that we do not have yet.
     *
     * Note what decides this: whether a row is actually missing, not whether we
     * remember asking for it. Fetched episodes are written to Room, so they stop
     * being missing on their own; a pruned one becomes missing again and is
     * re-fetched. There is no loaded-page bookkeeping to drift out of sync.
     *
     * Work is grouped into fixed page-aligned blocks purely so the in-flight and
     * failed sets have something stable to key on — the request itself names the
     * ids, so nothing depends on the block boundaries lining up with anything.
     */
    private fun requestMissingPages(w: Window, haveIds: Set<Int>) {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return

        val state = _uiState.value
        val ids = state.orderedIds
        if (ids.isEmpty() || w.ids.isEmpty()) return

        // The window may describe an ordering that has since been replaced — a
        // filter switch, say. Its indices would address the wrong episodes, and a
        // fresh observation for the new ordering is already on its way.
        if (w.start + w.ids.size > ids.size) return

        // On the unindexed fallback the ordering came from Room, so every row in
        // it is already loaded and there is nothing to fetch. Guarding here also
        // keeps us from sending ?ids= to a server old enough to lack /episode-index
        // — it would ignore the parameter and answer with the newest 200 episodes
        // instead of the ones asked for.
        if (!state.isIndexed) return

        val firstPage = w.start / PAGE_SIZE
        val lastPage = (w.start + w.ids.size - 1) / PAGE_SIZE

        for (page in firstPage..lastPage) {
            if (page in pagesInFlight || page in failedPages) continue
            val from = (page * PAGE_SIZE).coerceIn(0, ids.size)
            val to = ((page + 1) * PAGE_SIZE).coerceIn(from, ids.size)
            if (from >= to) continue

            val missing = ids.subList(from, to).filter { it !in haveIds }
            if (missing.isEmpty()) continue

            pagesInFlight += page
            viewModelScope.launch {
                try {
                    val got = episodeRepositoryOrNull()?.fetchEpisodesByIds(feedId, missing).orEmpty()
                    if (got.size < missing.size) {
                        // The index listed episodes the server then declined to
                        // return — hidden or deleted in between. Without this they
                        // would stay missing, and every subsequent write to the
                        // episodes table would re-trigger the same doomed fetch.
                        failedPages += page
                    }
                } catch (e: Exception) {
                    Log.e("EpisodeListViewModel", "Failed to load ${missing.size} episode(s)", e)
                    failedPages += page
                    _uiState.update {
                        it.copy(errorMessage = "Couldn't load episodes: ${e.localizedMessage}")
                    }
                } finally {
                    pagesInFlight -= page
                }
            }
        }
    }

    /**
     * Position of [episodeId] in the feed, or -1.
     *
     * This is the whole point of the index: a deep link from the dashboard or from
     * search resolves to a scroll offset with a local lookup, instead of pulling
     * page after page until the episode happens to show up.
     */
    fun indexOfEpisode(episodeId: Int): Int = _uiState.value.orderedIds.indexOf(episodeId)

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // Server first (it knows every episode, not just the page we cached), the
    // local table otherwise.  Only meaningful for a listen-in-order feed.
    private var lastNextUpFetchMs = 0L

    fun refreshNextUp(force: Boolean = false) {
        viewModelScope.launch {
            val feed = db.feedDao().getFeedOnce(feedId)
            if (feed == null || !feed.listenInOrder) {
                _uiState.update { it.copy(nextUp = null) }
                return@launch
            }
            val now = System.currentTimeMillis()
            val askServer = force || now - lastNextUpFetchMs > 30_000L
            val fromServer = if (askServer && !CastCharmApp.isOfflineMode && CastCharmApp.apiClient.isInitialized) {
                lastNextUpFetchMs = now
                runCatching { CastCharmApp.apiClient.getApi().getFeed(feedId).next_up }.getOrNull()
                    ?.let { NextUp(it.episode_id, it.seq_number, it.title, it.position_seconds, it.resume) }
            } else null
            val next = fromServer ?: run {
                val ep = db.episodeDao().getInProgressForFeed(feedId)
                    ?: (if (CastCharmApp.isOfflineMode) db.episodeDao().getInOrderQueueLocal(feedId)
                        else db.episodeDao().getInOrderQueue(feedId)).firstOrNull()
                ep?.let { NextUp(it.id, it.seq_number, it.title, it.play_position_seconds, it.play_position_seconds > 0) }
            }
            _uiState.update { it.copy(nextUp = next) }
        }
    }

    // The one per-feed playback setting the phone can change. Server first so
    // every device agrees; the local row follows.
    fun setListenInOrder(enabled: Boolean) {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) {
            _uiState.update { it.copy(errorMessage = "Connect to the server to change this setting") }
            return
        }
        viewModelScope.launch {
            val value = if (enabled) "oldest" else "newest"
            try {
                CastCharmApp.apiClient.getApi().updateFeed(feedId, FeedUpdateRequest(play_order = value))
                db.feedDao().updatePlayOrder(feedId, value)
                _uiState.update { it.copy(feed = db.feedDao().getFeedOnce(feedId)) }
                refreshNextUp(force = true)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Couldn't save setting: ${e.localizedMessage}") }
            }
        }
    }

    private fun markFeedGone() {
        _uiState.update {
            it.copy(
                feed = null,
                orderedIds = emptyList(),
                loadedById = emptyMap(),
                activePhoneDownloadEpisodeIds = emptySet(),
                isRefreshing = false,
                isInitialLoading = false,
                hasMore = false,
                errorMessage = "This podcast no longer exists on the server."
            )
        }
        window.value = Window(0, emptyList())
        pagesInFlight.clear()
        failedPages.clear()
    }

    /**
     * Establishes the feed's episode ordering for [filter].
     *
     * Three sources, in order of preference:
     *   - "Downloaded" is local by definition and comes from Room's Flow, which is
     *     already collecting; nothing to do here.
     *   - /episode-index, one small request that describes the entire feed.
     *   - A server too old for that endpoint: fall back to fetching the newest 100
     *     episodes and ordering by what landed, with the Load More button back.
     */
    private suspend fun loadOrdering(filter: EpisodeFilter) {
        val serverFilter = filter.indexFilter
        val repo = episodeRepositoryOrNull()

        if (serverFilter == null || repo == null || CastCharmApp.isOfflineMode) {
            // No server to ask — "Downloaded", which is local by definition, or we
            // are offline. Answer from the cache, filtered to match the chip. This
            // used to fall through to the unfiltered ordering, so an offline user
            // tapping "Unplayed" got every episode under a chip claiming otherwise.
            applyOrdering(localOrderingFor(filter), indexed = true)
            return
        }

        val index = try {
            repo.fetchEpisodeIndex(feedId, serverFilter)
        } catch (e: Exception) {
            Log.e("EpisodeListViewModel", "Failed to fetch episode index", e)
            null
        }

        if (index != null) {
            applyOrdering(index.ids, indexed = true)
            if (index.truncated) {
                showMessage("This podcast has more episodes than can be listed at once; showing the newest ${index.total}.")
            }
            // requestMissingPages only fetches rows it does not already have, so
            // on its own a refresh would never notice an episode whose played
            // state or download status changed on the server. Re-read what the
            // user is actually looking at.
            refetchWindow()
            // The index is the authoritative list, so anything local and absent
            // from it has been removed server-side.
            //
            // Both guards are load-bearing. A filtered index legitimately omits
            // episodes, and a truncated one omits the entire tail of the feed —
            // pruning against either would delete local rows, and their downloaded
            // audio, for episodes that are alive and well on the server.
            if (filter == EpisodeFilter.ALL && !index.truncated) {
                runCatching { repo.pruneToIndex(feedId, index.ids) }
            }
            return
        }

        // Fallback for a server without /episode-index. The ordering comes from
        // the cache, so it has to be filtered here too.
        val hasMore = repo.refreshEpisodesByFeed(feedId, limit = LOAD_MORE_STEP)
        applyOrdering(localOrderingFor(filter), indexed = false)
        _uiState.update { it.copy(hasMore = hasMore) }
    }

    /** This feed's episode ids from the local cache, ordered and filtered to match [filter]. */
    private suspend fun localOrderingFor(filter: EpisodeFilter): List<Int> {
        val dao = db.episodeDao()
        return when (filter) {
            EpisodeFilter.ALL -> dao.getEpisodeIdsByFeed(feedId)
            EpisodeFilter.UNPLAYED -> dao.getUnplayedEpisodeIdsByFeed(feedId)
            EpisodeFilter.DOWNLOADED -> dao.getDownloadedEpisodeIdsByFeed(feedId)
            EpisodeFilter.IN_PROGRESS -> dao.getInProgressEpisodeIdsByFeed(feedId)
        }
    }

    /**
     * Pull this feed's data from the server. Called by the screen's ON_RESUME
     * observer, so it runs on first arrival and again whenever the user comes back
     * to the screen. Distinct from syncFeed(), which asks the server to re-read the
     * podcast's RSS feed and is wired to pull-to-refresh.
     */
    fun refresh() {
        refreshEpisodes()
        refreshPlaylistMemberships()
        refreshNextUp()
    }

    fun refreshEpisodes() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRefreshing = true,
                    isInitialLoading = it.loadedById.isEmpty()
                )
            }

            try {
                val localFeed = db.feedDao().getFeedOnce(feedId)
                _uiState.update { state -> state.copy(feed = localFeed) }

                if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) {
                    // Offline: the local cache is all there is, so its own ordering
                    // is the ordering. Every row it lists is already hydrated.
                    applyOrdering(localOrderingFor(_uiState.value.filter), indexed = true)
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            isSyncing = false,
                            isInitialLoading = false,
                            hasMore = false,
                            errorMessage = null
                        )
                    }
                    return@launch
                }

                feedRepositoryOrNull()?.refreshFeeds()

                val refreshedFeedBeforeEpisodes = db.feedDao().getFeedOnce(feedId)
                if (refreshedFeedBeforeEpisodes == null) {
                    markFeedGone()
                    return@launch
                }

                loadOrdering(_uiState.value.filter)

                val refreshedFeed = db.feedDao().getFeedOnce(feedId)
                if (refreshedFeed == null) {
                    markFeedGone()
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        feed = refreshedFeed,
                        isRefreshing = false,
                        isSyncing = false,
                        isInitialLoading = false,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                Log.e("EpisodeListViewModel", "Failed to refresh episodes", e)
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        isSyncing = false,
                        isInitialLoading = false,
                        errorMessage = "Failed to refresh: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    // Tells the server to re-fetch this feed's RSS, then refreshes the local episode
    // list. isSyncing stays true through the subsequent refreshEpisodes() call so the
    // spinner remains visible without a gap.
    fun syncFeed() {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            runCatching { CastCharmApp.apiClient.getApi().refreshFeed(feedId) }
                .onSuccess { refreshEpisodes() }
                .onFailure { e ->
                    Log.e("EpisodeListViewModel", "Sync feed failed", e)
                    _uiState.update { it.copy(isSyncing = false, errorMessage = "Sync failed: ${e.localizedMessage}") }
                }
        }
    }

    /**
     * The "Load More" button, which only exists on the unindexed fallback path.
     *
     * An indexed feed has every row laid out from the start and fills them in as
     * they come into view, so there is nothing for the user to ask for.
     */
    fun loadMore() {
        if (_uiState.value.isIndexed) return
        if (_uiState.value.isRefreshing || !_uiState.value.hasMore || CastCharmApp.isOfflineMode) return

        // Clamped: the server rejects an over-large page rather than trimming it,
        // so an unbounded ask here would turn into an error instead of a shorter
        // list once the window grew past the cap.
        val limit = (_uiState.value.orderedIds.size + LOAD_MORE_STEP)
            .coerceAtMost(ServerLimits.current.safePageSize)
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                if (db.feedDao().getFeedOnce(feedId) == null) {
                    markFeedGone()
                    return@launch
                }

                // The server returns limit+1 items if more exist, so hasMore
                // stays true.
                val hasMore = episodeRepositoryOrNull()?.refreshEpisodesByFeed(feedId, limit = limit) ?: false
                applyOrdering(db.episodeDao().getEpisodeIdsByFeed(feedId), indexed = false)
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        hasMore = hasMore
                    )
                }
            } catch (e: Exception) {
                Log.e("EpisodeListViewModel", "Failed to load more episodes", e)
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = "Failed to load more: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /** Puts [text] on this screen's snackbar. */
    fun showMessage(text: String) {
        _uiState.update { it.copy(errorMessage = text) }
    }

    fun togglePlayed(episodeId: Int, currentState: Boolean) {
        viewModelScope.launch {
            val newState = !currentState
            try {
                val repo = episodeRepositoryOrNull()
                if (CastCharmApp.isOfflineMode || repo == null) {
                    db.episodeDao().updatePlayedStatus(
                        episodeId,
                        newState,
                        System.currentTimeMillis(),
                        pending = true
                    )
                } else {
                    repo.togglePlayed(episodeId, currentState)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to update: ${e.message}")
                }
            }
        }
    }

    fun downloadEpisode(episodeId: Int) {
        viewModelScope.launch {
            try {
                downloadScheduler.scheduleDownload(episodeId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to start download: ${e.message}")
                }
            }
        }
    }

    fun downloadToServer(episodeId: Int) {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) {
            _uiState.update {
                it.copy(errorMessage = "Server downloads are unavailable offline.")
            }
            return
        }

        viewModelScope.launch {
            try {
                CastCharmApp.apiClient.getApi().queueDownload(episodeId)
                refreshEpisodes()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to queue server download: ${e.message}")
                }
            }
        }
    }

    // Also the entry point into multi-select: the first long-press lands here.
    fun toggleEpisodeSelection(episodeId: Int) {
        val current = _uiState.value.selectedEpisodes
        _uiState.update {
            it.copy(
                selectedEpisodes = if (episodeId in current) current - episodeId else current + episodeId,
                selectionMode = true
            )
        }
    }

    /** Empties the selection but stays in multi-select ("Select none"). */
    fun clearSelection() {
        _uiState.update { it.copy(selectedEpisodes = emptySet()) }
    }

    /** Leaves multi-select entirely — the X in the top bar, or a finished batch. */
    fun exitSelectionMode() {
        _uiState.update { it.copy(selectedEpisodes = emptySet(), selectionMode = false) }
    }

    // Selects the whole feed, not just the loaded window — orderedIds covers every
    // episode under the current filter, so "Select all" means all of them even
    // when most are still skeletons on screen.
    fun selectAll() {
        _uiState.update {
            it.copy(
                selectedEpisodes = it.orderedIds.toSet(),
                selectionMode = true
            )
        }
    }

    fun downloadSelected() {
        viewModelScope.launch {
            _uiState.value.selectedEpisodes.forEach { id ->
                downloadScheduler.scheduleDownload(id)
            }
            exitSelectionMode()
        }
    }

    // Applies the played state to every currently-selected episode.
    //
    // This used to look each episode's current state up in the loaded list and
    // toggle the ones that differed. That was one request per episode, and — now
    // that "Select all" means the whole feed rather than the loaded window — it
    // read `false` for every episode still showing as a skeleton, which would have
    // marked already-played episodes back to unplayed. The bulk endpoint sets the
    // state outright, so neither the count nor the missing rows matter.
    fun markSelectedPlayed(played: Boolean) {
        viewModelScope.launch {
            val ids = _uiState.value.selectedEpisodes.toList()
            exitSelectionMode()
            try {
                val repo = episodeRepositoryOrNull()
                if (CastCharmApp.isOfflineMode || repo == null) {
                    val now = System.currentTimeMillis()
                    ids.chunked(400).forEach {
                        db.episodeDao().updatePlayedStatusForIds(it, played, now, pending = true)
                    }
                } else {
                    repo.bulkSetPlayed(ids, played)
                }
            } catch (e: Exception) {
                Log.e("EpisodeListViewModel", "Bulk mark played failed", e)
                _uiState.update { it.copy(errorMessage = "Failed to update: ${e.localizedMessage}") }
            }
        }
    }

    // Removes the on-disk file for every currently-selected episode that has
    // one, then clears the selection. Episodes that aren't downloaded are
    // silently skipped inside deleteLocalFile(). This affects the phone only —
    // the episode remains available on the server.
    fun deleteSelectedDownloads() {
        viewModelScope.launch {
            _uiState.value.selectedEpisodes.forEach { id ->
                runCatching { storageManager.deleteLocalFile(id) }
            }
            exitSelectionMode()
        }
    }

    fun toggleHidden(episodeId: Int, currentlyHidden: Boolean) {
        viewModelScope.launch {
            try {
                val repo = episodeRepositoryOrNull()
                if (CastCharmApp.isOfflineMode || repo == null) {
                    _uiState.update {
                        it.copy(errorMessage = "This action is unavailable offline.")
                    }
                } else {
                    repo.toggleHidden(episodeId, currentlyHidden)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to update: ${e.message}")
                }
            }
        }
    }

    fun refreshPlaylistMemberships() {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            try {
                val result = CastCharmApp.apiClient.getApi().getFeedPlaylistMemberships(feedId)
                _uiState.update { it.copy(playlistMemberEpisodeIds = result.episode_ids.toSet()) }
            } catch (_: Exception) {
                // Non-critical — playlist membership badge is best-effort
            }
        }
    }

    fun onPlaylistMembershipChanged(episodeId: Int, isInPlaylist: Boolean) {
        _uiState.update { state ->
            val updated = if (isInPlaylist) {
                state.playlistMemberEpisodeIds + episodeId
            } else {
                state.playlistMemberEpisodeIds - episodeId
            }
            state.copy(playlistMemberEpisodeIds = updated)
        }
    }

    fun playFeed(onEpisodeIdReady: (Int) -> Unit) {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return
        viewModelScope.launch {
            try {
                val state = CastCharmApp.apiClient.getApi().playerPlay(
                    PlayerPlayRequest(context_type = "feed", context_id = feedId, context_filter = "unplayed")
                )
                val episodeId = state.current_episode?.id
                if (episodeId != null) {
                    onEpisodeIdReady(episodeId)
                } else {
                    _uiState.update { it.copy(errorMessage = "No unplayed downloaded episodes to play") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to play feed: ${e.localizedMessage}") }
            }
        }
    }
}