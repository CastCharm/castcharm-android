package com.castcharm.android.ui.downloads

// DownloadsViewModel drives the Downloads tab. It tracks two categories of downloads:
//   - Phone downloads: episodes being downloaded to this device (via WorkManager / DownloadWorker)
//   - Server downloads: episodes being downloaded on the CastCharm server (queued/downloading status)
//
// The download state is built by combining four Room Flows (downloaded, inProgress, feeds, downloadRows)
// so any DB change (progress update, download completion, cancellation) triggers a recompose.
//
// Live progress for phone downloads comes from polling WorkManager's WorkInfo every 1 second.
// lastKnownPhoneProgress caches the most recent DownloadProgressUi per episode so that
// progress doesn't reset to zero between WorkManager polling cycles (avoids flickering).
//
// Server status is polled every 5 seconds via the API when server downloads are visible.
// Both polling jobs are started/stopped reactively: if there are no active downloads,
// the jobs are cancelled to avoid unnecessary work.
//
// The "current feed" slice concept: when the user drills into a specific feed,
// currentFeed* fields show only that feed's episodes/downloads.

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.ServerLimits
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.entities.DownloadEntity
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.db.entities.FeedEntity
import com.castcharm.android.data.repository.EpisodeRepository
import com.castcharm.android.data.repository.toEntity
import com.castcharm.android.download.DownloadScheduler
import com.castcharm.android.download.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class DownloadProgressUi(
    val status: String,
    val percent: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val isCancellable: Boolean
)

data class DownloadItem(
    val episode: EpisodeEntity,
    val feed: FeedEntity?,
    val progress: DownloadProgressUi? = null
)

data class FeedDownloadItem(
    val feed: FeedEntity,
    val downloadCount: Int
)

data class DownloadsUiState(
    val downloadedFeeds: List<FeedDownloadItem> = emptyList(),
    val downloadedEpisodes: List<EpisodeEntity> = emptyList(),
    val currentFeedDownloadedEpisodes: List<EpisodeEntity> = emptyList(),
    val currentFeedPhoneInProgress: List<DownloadItem> = emptyList(),
    val currentFeedServerInProgress: List<DownloadItem> = emptyList(),
    val selectedFeedId: Int? = null,
    val selectedEpisodes: Set<Int> = emptySet(),
    val phoneInProgress: List<DownloadItem> = emptyList(),
    val serverInProgress: List<DownloadItem> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false
)

class DownloadsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val db = AppDatabase.getDatabase(CastCharmApp.instance)
    private val episodeDao = db.episodeDao()
    private val feedDao = db.feedDao()
    private val downloadDao = db.downloadDao()
    private val downloadScheduler = DownloadScheduler(CastCharmApp.instance)
    private val storageManager = StorageManager(CastCharmApp.instance)
    private val workManager = WorkManager.getInstance(CastCharmApp.instance)

    private var phoneProgressRefreshJob: Job? = null
    private var serverStatusRefreshJob: Job? = null

    private val serverActivePollIntervalMs = 5_000L
    private val lastKnownPhoneProgress = mutableMapOf<Int, DownloadProgressUi>()

    init {
        observeDownloads()
    }

    private fun episodeRepositoryOrNull(): EpisodeRepository? {
        return if (CastCharmApp.apiClient.isInitialized) {
            EpisodeRepository(
                CastCharmApp.apiClient.getApi(),
                episodeDao
            )
        } else {
            null
        }
    }

    fun onScreenVisible(isOfflineMode: Boolean) {
        viewModelScope.launch {
            refreshForCurrentMode(isOfflineMode)
        }
    }

    suspend fun refreshForCurrentMode(isOfflineMode: Boolean) {
        if (isOfflineMode) {
            applyOfflineOnlyUiState()
            startOrStopRefreshJobs()
        } else {
            refreshOnlineSnapshot()
        }
    }

    fun selectFeed(feedId: Int?) {
        val current = _uiState.value
        val (downloaded, phone, server) = buildCurrentFeedSlices(
            selectedFeedId = feedId,
            downloadedEpisodes = current.downloadedEpisodes,
            phoneInProgress = current.phoneInProgress,
            serverInProgress = current.serverInProgress
        )

        _uiState.update {
            it.copy(
                selectedFeedId = feedId,
                currentFeedDownloadedEpisodes = downloaded,
                currentFeedPhoneInProgress = phone,
                currentFeedServerInProgress = server
            )
        }
    }

    fun togglePlayed(episodeId: Int, currentlyPlayed: Boolean) {
        viewModelScope.launch {
            val newState = !currentlyPlayed
            try {
                val repo = episodeRepositoryOrNull()
                if (CastCharmApp.isOfflineMode || repo == null) {
                    episodeDao.updatePlayedStatus(
                        episodeId,
                        newState,
                        System.currentTimeMillis(),
                        pending = true
                    )
                } else {
                    repo.togglePlayed(episodeId, currentlyPlayed)
                }
            } catch (_: Exception) {
                episodeDao.updatePlayedStatus(
                    episodeId,
                    newState,
                    System.currentTimeMillis(),
                    pending = true
                )
            }
        }
    }

    fun cancelDownload(episodeId: Int) {
        viewModelScope.launch {
            downloadScheduler.cancelDownload(episodeId)

            val episode = episodeDao.getEpisodeOnce(episodeId)
            if (episode != null && episode.local_path == null) {
                episodeDao.update(
                    episode.copy(
                        status = "pending",
                        download_progress = 0
                    )
                )
            }

            lastKnownPhoneProgress.remove(episodeId)
            refreshPhoneProgress()
        }
    }

    fun retryDownload(episodeId: Int) {
        viewModelScope.launch {
            downloadScheduler.scheduleDownload(episodeId)
            lastKnownPhoneProgress.remove(episodeId)
            refreshPhoneProgress()
        }
    }

    // isCancellable == false is the established signal for a permanent WorkManager failure
    // (work_request_id set to "FAILED_PERMANENT"). Reschedules each, clears stale cached
    // progress, then does a single refreshPhoneProgress() rather than one per episode.
    fun retryAllFailedPhoneDownloads() {
        viewModelScope.launch {
            val failedIds = _uiState.value.phoneInProgress
                .filter { it.progress?.isCancellable == false }
                .map { it.episode.id }
            failedIds.forEach { episodeId ->
                downloadScheduler.scheduleDownload(episodeId)
                lastKnownPhoneProgress.remove(episodeId)
            }
            if (failedIds.isNotEmpty()) refreshPhoneProgress()
        }
    }

    // isCancellable == true covers both queued and actively running WorkManager tasks.
    // Clears cached progress so the item disappears from the in-progress list immediately.
    fun cancelAllQueuedPhoneDownloads() {
        viewModelScope.launch {
            val activeIds = _uiState.value.phoneInProgress
                .filter { it.progress?.isCancellable == true }
                .map { it.episode.id }
            activeIds.forEach { episodeId ->
                downloadScheduler.cancelDownload(episodeId)
                lastKnownPhoneProgress.remove(episodeId)
            }
            if (activeIds.isNotEmpty()) refreshPhoneProgress()
        }
    }

    fun retryServerDownload(episodeId: Int) {
        viewModelScope.launch {
            if (!CastCharmApp.apiClient.isInitialized) return@launch
            runCatching { CastCharmApp.apiClient.getApi().retryServerDownload(episodeId) }
            refreshOnlineSnapshot()
        }
    }

    fun deleteEpisodeDownload(episode: EpisodeEntity) {
        viewModelScope.launch {
            episode.local_path?.let { path ->
                java.io.File(path).delete()
                episodeDao.update(
                    episode.copy(
                        local_path = null,
                        local_size_bytes = null
                    )
                )
                // Artwork counts against the download quota, so it has to go with
                // the episode it was kept for. Called after the row is cleared —
                // it checks whether the feed has any downloads left before also
                // dropping the feed cover.
                storageManager.releaseArtworkFor(episode.id, episode.feed_id)
            }
        }
    }

    fun deleteEpisodeDownloadById(episodeId: Int) {
        viewModelScope.launch {
            val episode = episodeDao.getEpisodeOnce(episodeId) ?: return@launch
            deleteEpisodeDownload(episode)
        }
    }

    fun deleteFeedDownloads(feedId: Int) {
        viewModelScope.launch {
            val episodes = episodeDao.getEpisodesByFeedOnce(feedId)
            episodes.filter { it.local_path != null }.forEach { ep ->
                java.io.File(ep.local_path!!).delete()
                episodeDao.update(
                    ep.copy(
                        local_path = null,
                        local_size_bytes = null
                    )
                )
                storageManager.releaseArtworkFor(ep.id, ep.feed_id)
            }
        }
    }

    fun toggleEpisodeSelection(episodeId: Int) {
        val currentSelection = _uiState.value.selectedEpisodes
        _uiState.update {
            it.copy(
                selectedEpisodes = if (currentSelection.contains(episodeId)) {
                    currentSelection - episodeId
                } else {
                    currentSelection + episodeId
                }
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedEpisodes = emptySet()) }
    }

    fun deleteSelectedEpisodes() {
        viewModelScope.launch {
            val toDelete = _uiState.value.selectedEpisodes
            val episodes = episodeDao.getDownloadedEpisodesOnce()
            episodes.filter { it.id in toDelete }.forEach { ep ->
                ep.local_path?.let { path -> java.io.File(path).delete() }
                episodeDao.update(
                    ep.copy(
                        local_path = null,
                        local_size_bytes = null
                    )
                )
                storageManager.releaseArtworkFor(ep.id, ep.feed_id)
            }
            clearSelection()
        }
    }

    // Combines four DB Flows so any change to downloads, episodes, feeds, or
    // download rows triggers a single recomputation of the full UI state.
    // This avoids N separate collectors that could emit interleaved partial updates.
    private fun observeDownloads() {
        viewModelScope.launch {
            _uiState.update { it.copy(isInitialLoading = true, isRefreshing = true) }

            combine(
                episodeDao.getDownloadedEpisodes(),
                episodeDao.getInProgressEpisodes(),
                feedDao.getAllFeeds(),
                downloadDao.getAllDownloads()
            ) { downloaded: List<EpisodeEntity>, inProgress: List<EpisodeEntity>, allFeeds: List<FeedEntity>, allDownloadRows: List<DownloadEntity> ->
                val feedMap = allFeeds.associateBy { it.id }
                val phoneRowsByEpisodeId = allDownloadRows.associateBy { it.episode_id }
                val phoneEpisodeIds = phoneRowsByEpisodeId.keys
                // Server-side in-progress downloads are hidden while offline so the
                // UI doesn't show stale "Downloading on server" items.
                val hideServerInProgress = CastCharmApp.isOfflineMode
                val inProgressMap = inProgress.associateBy { it.id }

                // Group downloaded episodes by feed so the sidebar shows a per-feed
                // download count. Creates a synthetic FeedEntity for orphaned episodes
                // whose feed row was pruned from the local DB.
                val downloadedByFeed = downloaded.groupBy { it.feed_id }
                val downloadedFeeds = downloadedByFeed.map { (feedId, eps) ->
                    val feed = feedMap[feedId]
                    if (feed != null) {
                        FeedDownloadItem(feed, eps.size)
                    } else {
                        // Feed row missing locally (e.g., feed was deleted on server
                        // but episode files are still on device). Synthesize a minimal
                        // FeedEntity so the UI can still display the feed group.
                        val firstEp = eps.firstOrNull()
                        FeedDownloadItem(
                            FeedEntity(
                                id = feedId,
                                url = "",
                                title = "Unknown Podcast ($feedId)",
                                description = null,
                                image_url = firstEp?.feed_image_url,
                                custom_image_url = null,
                                podcast_group = null,
                                auto_download_new = null,
                                active = true,
                                last_synced_at = null,
                                last_error = null
                            ),
                            eps.size
                        )
                    }
                }.sortedBy { it.feed.title }

                val phoneInProgress = allDownloadRows.mapNotNull { row ->
                    val episode = episodeDao.getEpisodeOnce(row.episode_id)
                        ?: inProgressMap[row.episode_id]

                    if (episode == null || episode.local_path != null) {
                        null
                    } else {
                        val seededProgress = lastKnownPhoneProgress[row.episode_id]
                            ?: seedPhoneProgressFromRow(row, episode)
                        DownloadItem(
                            episode = episode,
                            feed = feedMap[episode.feed_id],
                            progress = seededProgress
                        )
                    }
                }
                    .distinctBy { it.episode.id }
                    .sortedByDescending { it.episode.published_at }

                val phoneIdsActuallyShown = phoneInProgress.map { it.episode.id }.toSet()

                val serverInProgress = if (hideServerInProgress) {
                    emptyList()
                } else {
                    inProgress
                        .filter { it.id !in phoneIdsActuallyShown && it.id !in phoneEpisodeIds }
                        .map { ep -> DownloadItem(ep, feedMap[ep.feed_id], progress = null) }
                        .distinctBy { it.episode.id }
                        .sortedByDescending { it.episode.published_at }
                }

                cleanupProgressCache(
                    downloadedEpisodeIds = downloaded.map { it.id }.toSet(),
                    activePhoneEpisodeIds = phoneIdsActuallyShown
                )

                val selectedFeedId = _uiState.value.selectedFeedId
                val (currentFeedDownloaded, currentFeedPhone, currentFeedServer) = buildCurrentFeedSlices(
                    selectedFeedId = selectedFeedId,
                    downloadedEpisodes = downloaded,
                    phoneInProgress = phoneInProgress,
                    serverInProgress = serverInProgress
                )

                DownloadsUiState(
                    downloadedFeeds = downloadedFeeds,
                    downloadedEpisodes = downloaded,
                    currentFeedDownloadedEpisodes = currentFeedDownloaded,
                    currentFeedPhoneInProgress = currentFeedPhone,
                    currentFeedServerInProgress = currentFeedServer,
                    selectedFeedId = selectedFeedId,
                    selectedEpisodes = _uiState.value.selectedEpisodes,
                    phoneInProgress = phoneInProgress,
                    serverInProgress = serverInProgress,
                    isInitialLoading = false,
                    isRefreshing = _uiState.value.isRefreshing
                )
            }.collect { newState ->
                _uiState.value = newState
                startOrStopRefreshJobs()
            }
        }
    }

    private fun buildCurrentFeedSlices(
        selectedFeedId: Int?,
        downloadedEpisodes: List<EpisodeEntity>,
        phoneInProgress: List<DownloadItem>,
        serverInProgress: List<DownloadItem>
    ): Triple<List<EpisodeEntity>, List<DownloadItem>, List<DownloadItem>> {
        if (selectedFeedId == null) {
            return Triple(emptyList(), emptyList(), emptyList())
        }

        val downloaded = downloadedEpisodes
            .filter { it.feed_id == selectedFeedId }
            .sortedByDescending { it.published_at }

        val phone = phoneInProgress
            .filter { it.episode.feed_id == selectedFeedId }
            .distinctBy { it.episode.id }
            .sortedByDescending { it.episode.published_at }

        val phoneIds = phone.map { it.episode.id }.toSet()

        val server = serverInProgress
            .filter { it.episode.feed_id == selectedFeedId && it.episode.id !in phoneIds }
            .distinctBy { it.episode.id }
            .sortedByDescending { it.episode.published_at }

        return Triple(downloaded, phone, server)
    }

    // Builds an initial DownloadProgressUi from the DB row before WorkManager
    // has emitted its first progress update. Takes the max of the episode's
    // stored download_progress and the download row's progress_pct to avoid
    // showing 0% when we already know how far a previous session got.
    private fun seedPhoneProgressFromRow(
        row: DownloadEntity,
        episode: EpisodeEntity
    ): DownloadProgressUi {
        if (episode.status == "phone_failed" || row.work_request_id == "FAILED_PERMANENT") {
            return DownloadProgressUi(
                status = "Failed",
                percent = episode.download_progress.coerceIn(0, 100),
                bytesDownloaded = 0L,
                totalBytes = episode.enclosure_length ?: 0L,
                speedBytesPerSec = 0L,
                isCancellable = false
            )
        }

        val episodePercent = episode.download_progress.coerceIn(0, 100)
        val rowPercent = row.progress_pct.coerceIn(0, 100)
        val percent = maxOf(rowPercent, episodePercent)
        val totalBytes = episode.enclosure_length ?: 0L
        val bytesDownloaded = if (percent > 0 && totalBytes > 0L) {
            (totalBytes * percent) / 100L
        } else {
            0L
        }

        // null work_request_id means the row exists but no worker has been dispatched yet.
        val status = when {
            row.work_request_id.isNullOrBlank() -> "Queued"
            percent >= 100 -> "Finishing"
            percent > 0 -> "Downloading"
            else -> "Starting"
        }

        return DownloadProgressUi(
            status = status,
            percent = percent,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            speedBytesPerSec = 0L,
            isCancellable = true
        )
    }

    private fun cleanupProgressCache(
        downloadedEpisodeIds: Set<Int>,
        activePhoneEpisodeIds: Set<Int>
    ) {
        val iterator = lastKnownPhoneProgress.keys.iterator()
        while (iterator.hasNext()) {
            val episodeId = iterator.next()
            if (episodeId in downloadedEpisodeIds || episodeId !in activePhoneEpisodeIds) {
                iterator.remove()
            }
        }
    }

    private fun startOrStopRefreshJobs() {
        val hasPhoneInProgress = _uiState.value.phoneInProgress.isNotEmpty()
        val hasServerInProgress = _uiState.value.serverInProgress.isNotEmpty()

        if (!hasPhoneInProgress) {
            phoneProgressRefreshJob?.cancel()
            phoneProgressRefreshJob = null
        } else if (phoneProgressRefreshJob?.isActive != true) {
            phoneProgressRefreshJob = viewModelScope.launch {
                while (isActive) {
                    try {
                        refreshPhoneProgress()
                    } catch (_: Exception) {
                    }
                    delay(1000)
                }
            }
        }

        val hasActiveServerInProgress = _uiState.value.serverInProgress.any {
            it.episode.status == "queued" || it.episode.status == "downloading"
        }
        val canRefreshServer = hasActiveServerInProgress &&
                !CastCharmApp.isOfflineMode &&
                CastCharmApp.apiClient.isInitialized

        if (!canRefreshServer) {
            serverStatusRefreshJob?.cancel()
            serverStatusRefreshJob = null
        } else if (serverStatusRefreshJob?.isActive != true) {
            serverStatusRefreshJob = viewModelScope.launch {
                while (isActive) {
                    try {
                        refreshActiveServerStatuses()
                    } catch (_: Exception) {
                    }
                    delay(serverActivePollIntervalMs)
                }
            }
        }
    }

    private suspend fun refreshOnlineSnapshot() {
        _uiState.update { it.copy(isRefreshing = true) }
        try {
            if (!CastCharmApp.apiClient.isInitialized) return
            refreshServerDownloadSnapshot()
        } finally {
            _uiState.update { it.copy(isRefreshing = false) }
            startOrStopRefreshJobs()
        }
    }

    private suspend fun refreshServerDownloadSnapshot() {
        val api = CastCharmApp.apiClient.getApi()
        val phoneDownloadEpisodeIds = downloadDao
            .getAllDownloadsOnceOrdered()
            .map { it.episode_id }
            .toSet()

        val queuedResult = runCatching {
            api.getAllEpisodes(status = "queued", limit = ServerLimits.current.pageSize(1000), includeHidden = true, order = "desc")
        }
        val downloadingResult = runCatching {
            api.getAllEpisodes(status = "downloading", limit = ServerLimits.current.pageSize(1000), includeHidden = true, order = "desc")
        }
        val downloadedResult = runCatching {
            api.getAllEpisodes(status = "downloaded", limit = ServerLimits.current.pageSize(1000), includeHidden = true, order = "desc")
        }
        val failedResult = runCatching {
            api.getAllEpisodes(status = "failed", limit = ServerLimits.current.pageSize(1000), includeHidden = true, order = "desc")
        }

        // If every API call failed (server unreachable), bail before touching the DB.
        // Running the stale cleanup with all-empty results would incorrectly reset
        // every currently-shown server episode to "pending".
        val anyCallSucceeded = listOf(queuedResult, downloadingResult, downloadedResult, failedResult)
            .any { it.isSuccess }
        if (!anyCallSucceeded) return

        val queued = queuedResult.getOrDefault(emptyList())
        val downloading = downloadingResult.getOrDefault(emptyList())
        val downloaded = downloadedResult.getOrDefault(emptyList())
        val failed = failedResult.getOrDefault(emptyList())

        val remoteEpisodesById = linkedMapOf<Int, EpisodeEntity>()
        val allRemote = listOf(queued, downloading, downloaded, failed).flatten()

        allRemote.forEach { remote ->
            val existing = episodeDao.getEpisodeOnce(remote.id)
            val entity = remote.toEntity(
                existing = existing,
                hasActivePhoneDownload = remote.id in phoneDownloadEpisodeIds
            )
            remoteEpisodesById[entity.id] = entity
        }

        if (remoteEpisodesById.isNotEmpty()) {
            episodeDao.mergeFromApi(
                episodes = remoteEpisodesById.values.toList(),
                activePhoneDownloadEpisodeIds = phoneDownloadEpisodeIds
            )
        }

        val activeServerIds = (queued.map { it.id } + downloading.map { it.id }).toSet()
        val resolvedServerIds = remoteEpisodesById.keys

        // Exclude failed episodes from stale cleanup: they should persist until the
        // user retries or a full snapshot confirms the server resolved them. If the
        // `failed` API call itself returned an error, failed episodes won't be in
        // resolvedServerIds, and without this filter they'd be incorrectly reset to pending.
        val stalePreviouslyShownServerIds = _uiState.value.serverInProgress
            .filter { it.episode.status != "failed" }
            .map { it.episode.id }
            .filter { it !in activeServerIds && it !in phoneDownloadEpisodeIds }
            .distinct()

        stalePreviouslyShownServerIds.forEach { episodeId ->
            if (episodeId !in resolvedServerIds) {
                val existing = episodeDao.getEpisodeOnce(episodeId) ?: return@forEach
                if (existing.local_path == null) {
                    episodeDao.update(
                        existing.copy(
                            status = "pending",
                            download_progress = 0
                        )
                    )
                }
            }
        }
    }

    private suspend fun refreshActiveServerStatuses() {
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return

        val api = CastCharmApp.apiClient.getApi()
        val phoneDownloadEpisodeIds = downloadDao
            .getAllDownloadsOnceOrdered()
            .map { it.episode_id }
            .toSet()

        val queuedResult = runCatching {
            api.getAllEpisodes(status = "queued", limit = ServerLimits.current.pageSize(1000), includeHidden = true, order = "desc")
        }
        val downloadingResult = runCatching {
            api.getAllEpisodes(status = "downloading", limit = ServerLimits.current.pageSize(1000), includeHidden = true, order = "desc")
        }

        // If both calls failed (server unreachable), bail — running the stale cleanup
        // with empty results would incorrectly reset active episodes to "pending".
        if (queuedResult.isFailure && downloadingResult.isFailure) return

        val queued = queuedResult.getOrDefault(emptyList())
        val downloading = downloadingResult.getOrDefault(emptyList())
        val activeRemote = (queued + downloading).distinctBy { it.id }

        val mergedActive = activeRemote.map { remote ->
            val existing = episodeDao.getEpisodeOnce(remote.id)
            remote.toEntity(
                existing = existing,
                hasActivePhoneDownload = remote.id in phoneDownloadEpisodeIds
            )
        }

        if (mergedActive.isNotEmpty()) {
            episodeDao.mergeFromApi(
                episodes = mergedActive,
                activePhoneDownloadEpisodeIds = phoneDownloadEpisodeIds
            )
        }

        val activeServerIds = activeRemote.map { it.id }.toSet()
        val stalePreviouslyShownServerIds = _uiState.value.serverInProgress
            .filter { it.episode.status != "failed" }
            .map { it.episode.id }
            .filter { it !in activeServerIds && it !in phoneDownloadEpisodeIds }
            .distinct()

        stalePreviouslyShownServerIds.forEach { episodeId ->
            val existing = episodeDao.getEpisodeOnce(episodeId) ?: return@forEach
            if (existing.local_path == null) {
                episodeDao.update(
                    existing.copy(
                        status = "pending",
                        download_progress = 0
                    )
                )
            }
        }
    }

    private fun applyOfflineOnlyUiState() {
        serverStatusRefreshJob?.cancel()
        serverStatusRefreshJob = null

        _uiState.update { current ->
            val (downloaded, phone, _) = buildCurrentFeedSlices(
                selectedFeedId = current.selectedFeedId,
                downloadedEpisodes = current.downloadedEpisodes,
                phoneInProgress = current.phoneInProgress,
                serverInProgress = emptyList()
            )

            current.copy(
                serverInProgress = emptyList(),
                currentFeedDownloadedEpisodes = downloaded,
                currentFeedPhoneInProgress = phone,
                currentFeedServerInProgress = emptyList(),
                isRefreshing = false
            )
        }
    }

    private suspend fun refreshPhoneProgress() {
        val snapshot = _uiState.value
        if (snapshot.phoneInProgress.isEmpty()) return

        val updatedPhoneInProgress = withContext(Dispatchers.IO) {
            snapshot.phoneInProgress.mapNotNull { item ->
                try {
                    val downloadRow = downloadDao.getDownload(item.episode.id)
                    val latestEpisode = episodeDao.getEpisodeOnce(item.episode.id)
                    val episode = latestEpisode ?: item.episode

                    if (episode.local_path != null) {
                        lastKnownPhoneProgress.remove(item.episode.id)
                        null
                    } else if (downloadRow == null) {
                        val cached = lastKnownPhoneProgress[item.episode.id] ?: item.progress
                        cached?.let {
                            DownloadItem(episode = episode, feed = item.feed, progress = it)
                        }
                    } else if (downloadRow.work_request_id.isNullOrBlank() ||
                        downloadRow.work_request_id == "FAILED_PERMANENT"
                    ) {
                        val seeded = lastKnownPhoneProgress[item.episode.id]
                            ?: item.progress
                            ?: seedPhoneProgressFromRow(downloadRow, episode)
                        lastKnownPhoneProgress[item.episode.id] = seeded
                        DownloadItem(episode = episode, feed = item.feed, progress = seeded)
                    } else {
                        val workInfo = try {
                            workManager.getWorkInfoById(UUID.fromString(downloadRow.work_request_id)).get()
                        } catch (_: Exception) {
                            null
                        }

                        val freshProgress = workInfo?.toProgressUi(episode)
                        when {
                            freshProgress != null -> {
                                lastKnownPhoneProgress[item.episode.id] = freshProgress
                                DownloadItem(episode = episode, feed = item.feed, progress = freshProgress)
                            }
                            else -> {
                                val fallback = lastKnownPhoneProgress[item.episode.id]
                                    ?: item.progress
                                    ?: seedPhoneProgressFromRow(downloadRow, episode)
                                lastKnownPhoneProgress[item.episode.id] = fallback
                                DownloadItem(episode = episode, feed = item.feed, progress = fallback)
                            }
                        }
                    }
                } catch (_: Exception) {
                    val cached = lastKnownPhoneProgress[item.episode.id] ?: item.progress
                    cached?.let {
                        DownloadItem(episode = item.episode, feed = item.feed, progress = it)
                    }
                }
            }
        }

        _uiState.update { current ->
            val (downloaded, phone, server) = buildCurrentFeedSlices(
                selectedFeedId = current.selectedFeedId,
                downloadedEpisodes = current.downloadedEpisodes,
                phoneInProgress = updatedPhoneInProgress,
                serverInProgress = current.serverInProgress
            )

            current.copy(
                phoneInProgress = updatedPhoneInProgress,
                currentFeedDownloadedEpisodes = downloaded,
                currentFeedPhoneInProgress = phone,
                currentFeedServerInProgress = server
            )
        }
    }

    // Extension that converts a WorkManager WorkInfo into a DownloadProgressUi.
    // Returns null for CANCELLED/FAILED states so the caller can fall back to
    // the lastKnownPhoneProgress cache rather than showing a stale final state.
    //
    // Progress percentage: takes the max of three sources to avoid flickering
    // backwards: the WorkInfo progress_percent key, the value computed from
    // bytes_downloaded / total_bytes, and the DB episode's stored percentage.
    private fun WorkInfo.toProgressUi(ep: EpisodeEntity): DownloadProgressUi? {
        if (state == WorkInfo.State.CANCELLED || state == WorkInfo.State.FAILED) {
            return null
        }

        // Use content-length from the response if available; fall back to the
        // RSS enclosure_length from the DB; if neither, show 0 (unknown total).
        val fallbackTotalBytes = ep.enclosure_length ?: 0L
        val rawTotalBytes = progress.getLong("total_bytes", -1L)
        val totalBytes = when {
            rawTotalBytes > 0L -> rawTotalBytes
            fallbackTotalBytes > 0L -> fallbackTotalBytes
            else -> 0L
        }

        val rawBytesDownloaded = progress.getLong("bytes_downloaded", -1L)
        val speedBps = progress.getLong("speed_bps", 0L)
        val rawStatus = progress.getString("status")

        val pctFromProgress = progress.getInt("progress_percent", -1)
        val pctFromBytes = when {
            rawBytesDownloaded >= 0L && totalBytes > 0L ->
                ((rawBytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
            else -> -1
        }

        // Take the max so the displayed percentage can only go forward, never back.
        val percent = maxOf(
            pctFromProgress.coerceAtLeast(-1),
            pctFromBytes,
            ep.download_progress.coerceIn(0, 100)
        ).coerceIn(0, 100)

        val bytesDownloaded = when {
            rawBytesDownloaded >= 0L -> rawBytesDownloaded
            percent > 0 && totalBytes > 0L -> (totalBytes * percent) / 100L
            else -> 0L
        }

        val statusText = when {
            state == WorkInfo.State.ENQUEUED -> "Queued"
            state == WorkInfo.State.RUNNING && rawStatus == "starting" -> "Starting"
            state == WorkInfo.State.RUNNING -> "Downloading"
            state == WorkInfo.State.SUCCEEDED -> "Finishing"
            else -> ep.status.replaceFirstChar { it.uppercase() }
        }

        return DownloadProgressUi(
            status = statusText,
            percent = percent,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            speedBytesPerSec = speedBps,
            isCancellable = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
        )
    }

    override fun onCleared() {
        phoneProgressRefreshJob?.cancel()
        serverStatusRefreshJob?.cancel()
        super.onCleared()
    }
}