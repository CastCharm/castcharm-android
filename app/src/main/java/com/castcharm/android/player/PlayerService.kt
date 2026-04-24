package com.castcharm.android.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.castcharm.android.CastCharmApp
import com.castcharm.android.MainActivity
import com.castcharm.android.R
import com.castcharm.android.data.api.PersistentCookieJar
import com.castcharm.android.data.api.models.ProgressRequest
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.dao.EpisodeDao
import com.castcharm.android.data.db.dao.FeedDao
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.db.entities.FeedEntity
import com.castcharm.android.data.repository.EpisodeRepository
import com.castcharm.android.data.repository.toEntity
import com.castcharm.android.dataStore
import com.castcharm.android.provider.PodcastArtworkProvider
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "CastCharm"
private const val ROOT_ID = "root"
private const val SECTION_CONTINUE = "section_continue"
private const val SECTION_RECENT = "section_recent"
private const val SECTION_PODCASTS = "section_podcasts"
private const val SECTION_DOWNLOADS = "section_downloads"
private const val ARTWORK_AUTHORITY_SUFFIX = ".artwork"


private val apiClientInitMutex = Mutex()

private fun normalizeBaseUrl(url: String): String =
    if (url.endsWith("/")) url else "$url/"

private suspend fun ensureApiClientInitializedFromStorage(context: Context): String {
    if (CastCharmApp.apiClient.isInitialized) {
        return CastCharmApp.apiClient.getBaseUrl()
    }

    return apiClientInitMutex.withLock {
        if (CastCharmApp.apiClient.isInitialized) {
            return@withLock CastCharmApp.apiClient.getBaseUrl()
        }

        val savedUrl = context.dataStore.data
            .map { it[stringPreferencesKey("server_url")] }
            .first()
            ?: return@withLock ""

        val normalizedUrl = normalizeBaseUrl(savedUrl)
        try {
            CastCharmApp.apiClient.initialize(normalizedUrl)
        } catch (_: Exception) {
        }

        if (CastCharmApp.apiClient.isInitialized) {
            CastCharmApp.apiClient.getBaseUrl()
        } else {
            normalizedUrl
        }
    }
}

private fun ensureApiClientInitializedBlocking(context: Context): String = runBlocking(Dispatchers.IO) {
    ensureApiClientInitializedFromStorage(context)
}

private val SPEED_COMMAND = SessionCommand("com.castcharm.android.CYCLE_SPEED", Bundle.EMPTY)
private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private const val PLAYED_THRESHOLD_PCT = 0.98f

private fun speedLabel(speed: Float) = when (speed) {
    0.75f -> "0.75×"
    1.0f -> "1.0×"
    1.25f -> "1.25×"
    1.5f -> "1.5×"
    2.0f -> "2.0×"
    else -> "%.2f×".format(speed)
}

private fun buildNowPlayingSubtitle(feedTitle: String?, speed: Float): String {
    val label = speedLabel(speed)
    return if (feedTitle.isNullOrBlank()) label else "$feedTitle • $label"
}

private fun extractBaseFeedTitle(metadata: MediaMetadata): String {
    val extras = metadata.extras
    val fromExtras = extras?.getString("castcharm_feed_title")
    if (!fromExtras.isNullOrBlank()) return fromExtras

    val artist = metadata.artist?.toString()
    if (!artist.isNullOrBlank()) return artist

    val subtitle = metadata.subtitle?.toString()
    if (!subtitle.isNullOrBlank()) {
        return subtitle.substringBefore(" • ").ifBlank { subtitle }
    }

    return ""
}

private fun visibleRootSections(isOffline: Boolean): List<String> {
    return if (isOffline) {
        listOf(SECTION_DOWNLOADS)
    } else {
        listOf(
            SECTION_CONTINUE,
            SECTION_RECENT,
            SECTION_PODCASTS,
            SECTION_DOWNLOADS
        )
    }
}

private fun isOfflineHiddenSection(parentId: String): Boolean {
    if (!CastCharmApp.isOfflineMode) return false
    return parentId == SECTION_CONTINUE ||
            parentId == SECTION_RECENT ||
            parentId == SECTION_PODCASTS ||
            parentId.startsWith("feed_")
}

@OptIn(UnstableApi::class)
private fun createSpeedButton(speed: Float): CommandButton =
    CommandButton.Builder(CommandButton.ICON_UNDEFINED)
        .setSessionCommand(SPEED_COMMAND)
        .setDisplayName(speedLabel(speed))
        .setCustomIconResId(R.drawable.ic_speed)
        .setSlots(CommandButton.SLOT_OVERFLOW)
        .build()

@OptIn(UnstableApi::class)
private fun createSeekBackButton(): CommandButton =
    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30)
        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
        .setDisplayName("Back 30s")
        .setSlots(CommandButton.SLOT_BACK)
        .build()

@OptIn(UnstableApi::class)
private fun createSeekForwardButton(): CommandButton =
    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
        .setDisplayName("Forward 30s")
        .setSlots(CommandButton.SLOT_FORWARD)
        .build()

private fun initialMediaButtonPreferences(speed: Float): ImmutableList<CommandButton> {
    return ImmutableList.of(
        createSpeedButton(speed),
        createSeekBackButton(),
        createSeekForwardButton()
    )
}

private data class BrowseSnapshot(
    val isOffline: Boolean,
    val rootCount: Int,
    val continueCount: Int,
    val recentCount: Int,
    val podcastsCount: Int,
    val downloadsCount: Int
)

@OptIn(UnstableApi::class)
class PlayerService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressJob: Job? = null
    private var connectivityModeJob: Job? = null
    private var libraryRefreshJob: Job? = null
    @Volatile
    private var latestPlaybackSpeed: Float = 1.0f

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlayerService.onCreate start")
        try {
            serviceScope.launch {
                ensureApiClientInitializedFromStorage(this@PlayerService)
            }

            val cookieJar = PersistentCookieJar(this)
            val streamingClient = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .addInterceptor { chain ->
                    ensureApiClientInitializedBlocking(this@PlayerService)
                    chain.proceed(chain.request())
                }
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            val dataSourceFactory = DefaultDataSource.Factory(
                this,
                OkHttpDataSource.Factory(streamingClient).setUserAgent("CastCharm/1.0")
            )

            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                .setSeekForwardIncrementMs(30_000)
                .setSeekBackIncrementMs(30_000)
                .build()
            Log.d(TAG, "ExoPlayer built OK")

            latestPlaybackSpeed = player.playbackParameters.speed

            val callback = PlayerLibrarySessionCallback(
                context = this,
                feedDao = db.feedDao(),
                episodeDao = db.episodeDao(),
                player = player,
                scope = serviceScope,
                sessionProvider = { mediaSession }
            )
            callback.updateLatestPlaybackSpeed(latestPlaybackSpeed)

            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) startProgressTracking() else stopProgressTracking()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        val episodeId = currentEpisodeId()
                        serviceScope.launch { episodeId?.let { markPlayed(it) } }
                    }
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    latestPlaybackSpeed = playbackParameters.speed
                    callback.updateLatestPlaybackSpeed(playbackParameters.speed)

                    serviceScope.launch {
                        refreshNowPlayingSpeedMetadata(playbackParameters.speed)
                    }
                }
            })

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            mediaSession = MediaLibrarySession.Builder(this, player, callback)
                .setSessionActivity(pendingIntent)
                .setMediaButtonPreferences(initialMediaButtonPreferences(latestPlaybackSpeed))
                .build()
            Log.d(TAG, "MediaLibrarySession built OK, session=$mediaSession")

            serviceScope.launch {
                callback.notifyLibraryChanged()
                notifyBrowseSectionsChanged()
            }

            connectivityModeJob = serviceScope.launch {
                CastCharmApp.connectivityMode.collectLatest { mode ->
                    Log.d(TAG, "AA connectivity mode changed: $mode")
                    callback.notifyLibraryChanged()
                    notifyBrowseSectionsChanged()
                }
            }

            startLibraryRefreshObserver()
        } catch (e: Exception) {
            Log.e(TAG, "PlayerService.onCreate FAILED — session will be null", e)
        }
    }

    private fun startLibraryRefreshObserver() {
        libraryRefreshJob?.cancel()
        libraryRefreshJob = serviceScope.launch {
            var lastSnapshot: BrowseSnapshot? = null

            while (isActive) {
                try {
                    val snapshot = readBrowseSnapshot()
                    if (snapshot != lastSnapshot) {
                        Log.d(TAG, "AA browse snapshot changed: $snapshot")
                        notifyBrowseSectionsChanged()
                        lastSnapshot = snapshot
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AA browse snapshot poll failed", e)
                }

                delay(1500)
            }
        }
    }

    private suspend fun readBrowseSnapshot(): BrowseSnapshot {
        val isOffline = CastCharmApp.isOfflineMode
        val rootCount = visibleRootSections(isOffline).size
        val continueCount = if (isOffline) 0 else db.episodeDao().getContinueListening(limit = 20).first().size
        val recentCount = if (isOffline) 0 else db.episodeDao().getRecentEpisodesOnce(limit = 200).size
        val podcastsCount = if (isOffline) 0 else db.feedDao().getFeedOnceAll().size
        val downloadsCount = db.episodeDao().getDownloadedEpisodesOnce().size

        return BrowseSnapshot(
            isOffline = isOffline,
            rootCount = rootCount,
            continueCount = continueCount,
            recentCount = recentCount,
            podcastsCount = podcastsCount,
            downloadsCount = downloadsCount
        )
    }

    private suspend fun refreshNowPlayingSpeedMetadata(speed: Float) {
        withContext(Dispatchers.Main) {
            if (!::player.isInitialized) return@withContext

            val currentIndex = player.currentMediaItemIndex
            val currentItem = player.currentMediaItem ?: return@withContext
            if (currentIndex < 0) return@withContext

            val currentMetadata = currentItem.mediaMetadata
            val baseFeedTitle = extractBaseFeedTitle(currentMetadata)
            val desiredSubtitle = buildNowPlayingSubtitle(baseFeedTitle, speed)

            if (currentMetadata.subtitle?.toString() == desiredSubtitle) {
                return@withContext
            }

            val updatedExtras = Bundle(currentMetadata.extras ?: Bundle()).apply {
                putString("castcharm_feed_title", baseFeedTitle)
                putString("castcharm_speed_label", speedLabel(speed))
            }

            val updatedMetadata = currentMetadata.buildUpon()
                .setArtist(baseFeedTitle)
                .setSubtitle(desiredSubtitle)
                .setExtras(updatedExtras)
                .build()

            val updatedItem = currentItem.buildUpon()
                .setMediaMetadata(updatedMetadata)
                .build()

            player.replaceMediaItem(currentIndex, updatedItem)
        }
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            var lastSyncMs = 0L
            while (isActive) {
                delay(1000)
                val (episodeId, positionMs, durationMs) = withContext(Dispatchers.Main) {
                    Triple(currentEpisodeId(), player.currentPosition, player.duration)
                }
                episodeId ?: continue

                val positionSeconds = (positionMs / 1000L).toInt()
                val durationSeconds = durationMs.takeIf { it > 0L }?.div(1000L)?.toInt()
                val now = System.currentTimeMillis()

                if (now - lastSyncMs >= 10_000L) {
                    try {
                        val existing = db.episodeDao().getEpisodeOnce(episodeId)
                        val targetPlayed = EpisodeRepository.derivePlayedState(
                            positionSeconds = positionSeconds,
                            durationSeconds = durationSeconds ?: existing?.duration,
                            currentPlayed = existing?.played ?: false,
                            thresholdPct = PLAYED_THRESHOLD_PCT
                        )

                        db.episodeDao().updateProgress(
                            episodeId,
                            positionSeconds,
                            now,
                            pending = CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized
                        )

                        if (CastCharmApp.apiClient.isInitialized && !CastCharmApp.isOfflineMode) {
                            CastCharmApp.apiClient.getApi()
                                .updateProgress(episodeId, ProgressRequest(positionSeconds))
                        }

                        if (existing != null && existing.played != targetPlayed) {
                            if (CastCharmApp.apiClient.isInitialized && !CastCharmApp.isOfflineMode) {
                                CastCharmApp.apiClient.getApi().togglePlayed(episodeId)
                                db.episodeDao().updatePlayedStatus(episodeId, targetPlayed, now, pending = false)
                            } else {
                                db.episodeDao().updatePlayedStatus(episodeId, targetPlayed, now, pending = true)
                            }
                        }

                        lastSyncMs = now
                        notifyBrowseSectionsChanged(episodeId)
                    } catch (_: Exception) {
                        val existing = db.episodeDao().getEpisodeOnce(episodeId)
                        val targetPlayed = EpisodeRepository.derivePlayedState(
                            positionSeconds = positionSeconds,
                            durationSeconds = durationSeconds ?: existing?.duration,
                            currentPlayed = existing?.played ?: false,
                            thresholdPct = PLAYED_THRESHOLD_PCT
                        )

                        db.episodeDao().updateProgress(episodeId, positionSeconds, now, pending = true)
                        if (existing != null && existing.played != targetPlayed) {
                            db.episodeDao().updatePlayedStatus(episodeId, targetPlayed, now, pending = true)
                        }
                    }
                }
            }
        }
    }

    private suspend fun markPlayed(episodeId: Int) {
        val now = System.currentTimeMillis()
        try {
            db.episodeDao().updatePlayedStatus(episodeId, true, now)
            if (CastCharmApp.apiClient.isInitialized && !CastCharmApp.isOfflineMode) {
                CastCharmApp.apiClient.getApi().togglePlayed(episodeId)
            } else {
                db.episodeDao().updatePlayedStatus(episodeId, true, now, pending = true)
            }
            notifyBrowseSectionsChanged(episodeId)
        } catch (_: Exception) {
            db.episodeDao().updatePlayedStatus(episodeId, true, now, pending = true)
        }
    }

    private suspend fun notifyBrowseSectionsChanged(episodeId: Int? = null) {
        val session = mediaSession ?: return
        val isOffline = CastCharmApp.isOfflineMode
        val rootSections = visibleRootSections(isOffline)
        val rootCount = rootSections.size
        val feedCount = if (isOffline) 0 else db.feedDao().getFeedOnceAll().size
        val continueCount = if (isOffline) 0 else db.episodeDao().getContinueListening(limit = 20).first().size
        val recentCount = if (isOffline) 0 else db.episodeDao().getRecentEpisodesOnce(limit = 200).size
        val downloadCount = db.episodeDao().getDownloadedEpisodesOnce().size

        session.notifyChildrenChanged(ROOT_ID, rootCount, browseLibraryParams())
        session.notifyChildrenChanged(SECTION_DOWNLOADS, downloadCount, playableLibraryParams())

        if (!isOffline) {
            session.notifyChildrenChanged(SECTION_CONTINUE, continueCount, playableLibraryParams())
            session.notifyChildrenChanged(SECTION_RECENT, recentCount, playableLibraryParams())
            session.notifyChildrenChanged(SECTION_PODCASTS, feedCount, browsableLibraryParams())

            if (episodeId != null) {
                val episode = db.episodeDao().getEpisodeOnce(episodeId)
                val feedId = episode?.feed_id
                if (feedId != null) {
                    val feedEpisodeCount = db.episodeDao().getEpisodesByFeedOnce(feedId).size
                    session.notifyChildrenChanged(feedMediaId(feedId), feedEpisodeCount, playableLibraryParams())
                }
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
    }

    private fun currentEpisodeId(): Int? =
        player.currentMediaItem?.mediaId?.removePrefix("episode_")?.toIntOrNull()

    override fun onDestroy() {
        stopProgressTracking()
        connectivityModeJob?.cancel()
        libraryRefreshJob?.cancel()
        mediaSession?.release()
        mediaSession = null
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        Log.d(TAG, "onGetSession: pkg=${controllerInfo.packageName}")
        return mediaSession
    }
}

@OptIn(UnstableApi::class)
private class PlayerLibrarySessionCallback(
    private val context: Context,
    private val feedDao: FeedDao,
    private val episodeDao: EpisodeDao,
    private val player: Player,
    private val scope: CoroutineScope,
    private val sessionProvider: () -> MediaLibrarySession?,
) : MediaLibraryService.MediaLibrarySession.Callback {

    private val connectedControllers = linkedSetOf<MediaSession.ControllerInfo>()

    @Volatile
    private var latestPlaybackSpeed: Float = 1.0f

    fun updateLatestPlaybackSpeed(speed: Float) {
        latestPlaybackSpeed = speed
    }

    private fun <T> asyncFuture(fallback: T, block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch {
            try {
                future.set(block())
            } catch (e: Exception) {
                Log.e(TAG, "asyncFuture error", e)
                if (!future.isDone) future.set(fallback)
            }
        }.invokeOnCompletion {
            if (!future.isDone) future.set(fallback)
        }
        return future
    }

    suspend fun notifyLibraryChanged() {
        val session = sessionProvider() ?: return
        val isOffline = CastCharmApp.isOfflineMode
        val rootCount = visibleRootSections(isOffline).size
        val feedCount = if (isOffline) 0 else feedDao.getFeedOnceAll().size
        val continueCount = if (isOffline) 0 else episodeDao.getContinueListening(limit = 20).first().size
        val recentCount = if (isOffline) 0 else episodeDao.getRecentEpisodesOnce(limit = 200).size
        val downloadCount = episodeDao.getDownloadedEpisodesOnce().size

        session.notifyChildrenChanged(ROOT_ID, rootCount, browseLibraryParams())
        session.notifyChildrenChanged(SECTION_DOWNLOADS, downloadCount, playableLibraryParams())

        if (!isOffline) {
            session.notifyChildrenChanged(SECTION_CONTINUE, continueCount, playableLibraryParams())
            session.notifyChildrenChanged(SECTION_RECENT, recentCount, playableLibraryParams())
            session.notifyChildrenChanged(SECTION_PODCASTS, feedCount, browsableLibraryParams())
        }
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        Log.d(TAG, "onConnect: pkg=${controller.packageName}")

        val base = super.onConnect(session, controller)
        if (!base.isAccepted) return base

        connectedControllers.add(controller)

        val sessionCommands = base.availableSessionCommands
            .buildUpon()
            .add(SPEED_COMMAND)
            .build()

        val playerCommands = base.availablePlayerCommands
            .buildUpon()
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_NEXT)
            .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .build()

        val buttons = initialMediaButtonPreferences(latestPlaybackSpeed)

        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(playerCommands)
            .setMediaButtonPreferences(buttons)
            .setCustomLayout(buttons)
            .build()
    }

    override fun onDisconnected(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ) {
        connectedControllers.remove(controller)
        super.onDisconnected(session, controller)
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction == SPEED_COMMAND.customAction) {
            val current = latestPlaybackSpeed
            val idx = SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
                .takeIf { it >= 0 } ?: 1
            val next = SPEEDS[(idx + 1) % SPEEDS.size]

            latestPlaybackSpeed = next
            player.setPlaybackSpeed(next)

            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        return super.onCustomCommand(session, controller, customCommand, args)
    }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> =
        asyncFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)) {
            val accepted = when {
                parentId == ROOT_ID -> true
                parentId == SECTION_CONTINUE -> true
                parentId == SECTION_RECENT -> true
                parentId == SECTION_DOWNLOADS -> true
                parentId == SECTION_PODCASTS -> true
                parentId.startsWith("feed_") -> {
                    val id = parentId.removePrefix("feed_").toIntOrNull()
                    id != null && feedDao.getFeedOnce(id) != null
                }
                else -> false
            }

            Log.d(TAG, "onSubscribe pkg=${browser.packageName} parent=$parentId accepted=$accepted")
            if (accepted) {
                LibraryResult.ofVoid()
            } else {
                Log.e(TAG, "onSubscribe REJECTED pkg=${browser.packageName} parent=$parentId")
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }

    @OptIn(UnstableApi::class)
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val root = rootItem(context)
        val extras = root.mediaMetadata.extras ?: Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )

        Log.d(TAG, "onGetLibraryRoot pkg=${browser.packageName} extras=$extras")
        return Futures.immediateFuture(LibraryResult.ofItem(root, browseLibraryParams()))
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> =
        asyncFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)) {
            val item = when {
                mediaId == ROOT_ID -> rootItem(context)
                mediaId == SECTION_CONTINUE -> section(context, SECTION_CONTINUE, "Continue")
                mediaId == SECTION_RECENT -> section(context, SECTION_RECENT, "Recent")
                mediaId == SECTION_DOWNLOADS -> section(context, SECTION_DOWNLOADS, "Downloads")
                mediaId == SECTION_PODCASTS -> section(context, SECTION_PODCASTS, "Podcasts")
                mediaId.startsWith("feed_") -> {
                    val feedId = mediaId.removePrefix("feed_").toIntOrNull()
                        ?: return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    val feed = feedDao.getFeedOnce(feedId)
                        ?: return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    createFeedItem(feed)
                }
                mediaId.startsWith("episode_") -> {
                    val epId = mediaId.removePrefix("episode_").toIntOrNull()
                        ?: return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    val ep = episodeDao.getEpisodeOnce(epId)
                        ?: return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    val feedTitle = feedDao.getFeedOnce(ep.feed_id)?.title
                    createEpisodeItem(ep, feedTitle)
                }
                else -> return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }

            Log.d(
                TAG,
                "item pkg=${browser.packageName} id=$mediaId browsable=${item.mediaMetadata.isBrowsable} playable=${item.mediaMetadata.isPlayable} type=${item.mediaMetadata.mediaType} artwork=${item.mediaMetadata.artworkUri?.scheme}"
            )
            LibraryResult.ofItem(item, null)
        }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        asyncFuture(LibraryResult.ofItemList(ImmutableList.of(), params)) {
            if (isOfflineHiddenSection(parentId)) {
                Log.d(TAG, "Returning empty AA section while offline parent=$parentId")
                return@asyncFuture LibraryResult.ofItemList(ImmutableList.of(), params)
            }

            val allItems: List<MediaItem>
            val resultParams: MediaLibraryService.LibraryParams

            when {
                parentId == ROOT_ID -> {
                    allItems = visibleRootSections(CastCharmApp.isOfflineMode).map { sectionId ->
                        when (sectionId) {
                            SECTION_CONTINUE -> section(context, SECTION_CONTINUE, "Continue")
                            SECTION_RECENT -> section(context, SECTION_RECENT, "Recent")
                            SECTION_PODCASTS -> section(context, SECTION_PODCASTS, "Podcasts")
                            SECTION_DOWNLOADS -> section(context, SECTION_DOWNLOADS, "Downloads")
                            else -> section(context, SECTION_DOWNLOADS, "Downloads")
                        }
                    }
                    resultParams = browseLibraryParams()
                }

                parentId == SECTION_PODCASTS -> {
                    val feeds = feedDao.getFeedOnceAll()
                    allItems = feeds.map(::createFeedItem)
                    resultParams = browsableLibraryParams()

                    scope.launch {
                        feeds.take(12).forEach { feed ->
                            try {
                                PodcastArtworkProvider.prefetchFeedArtwork(context, feed.id)
                            } catch (e: Exception) {
                                Log.w(TAG, "Feed artwork prefetch failed for ${feed.id}", e)
                            }
                        }
                    }
                }

                parentId.startsWith("feed_") -> {
                    val feedId = parentId.removePrefix("feed_").toIntOrNull()
                    Log.d(TAG, "[onGetChildren] feedId=$feedId parentId=$parentId page=$page pageSize=$pageSize")
                    if (feedId == null) return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)

                    val feed = feedDao.getFeedOnce(feedId)
                    if (feed == null) return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)

                    scope.launch { PodcastArtworkProvider.prefetchFeedArtwork(context, feedId) }

                    val existingCount = episodeDao.getEpisodesForAndroidAutoByFeed(feedId).size
                    val expectedCount = feed.episode_count
                    val shouldRefreshAll = page <= 0 && (expectedCount <= 0 || existingCount < expectedCount)

                    if (!CastCharmApp.isOfflineMode && CastCharmApp.apiClient.isInitialized && shouldRefreshAll) {
                        try {
                            val api = CastCharmApp.apiClient.getApi()
                            val batchSize = 200
                            var offset = 0
                            var fetchedTotal = 0
                            while (true) {
                                val remoteEpisodes = api.getEpisodes(
                                    feedId = feedId,
                                    limit = batchSize,
                                    offset = offset,
                                    includeHidden = false,
                                    order = "desc"
                                )
                                Log.d(TAG, "AA FEED FETCH feed=$feedId offset=$offset apiCount=${remoteEpisodes.size}")
                                if (remoteEpisodes.isEmpty()) break

                                episodeDao.mergeFromApi(remoteEpisodes.map { it.toEntity(null) })
                                fetchedTotal += remoteEpisodes.size

                                if (remoteEpisodes.size < batchSize) break
                                if (expectedCount > 0 && fetchedTotal >= expectedCount) break
                                if (fetchedTotal >= 5000) break
                                offset += remoteEpisodes.size
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "AA: Failed to fetch episodes on-demand for $feedId", e)
                        }
                    }

                    allItems = episodeDao
                        .getEpisodesForAndroidAutoByFeed(feedId)
                        .map { ep -> createEpisodeItem(ep, feed.title) }
                    resultParams = playableLibraryParams()
                }

                parentId == SECTION_CONTINUE -> {
                    val episodes = episodeDao.getContinueListening(limit = 200).first()
                    val feedMap = feedDao.getFeedOnceAll().associateBy { it.id }
                    allItems = episodes.map { ep -> createEpisodeItem(ep, feedMap[ep.feed_id]?.title) }
                    resultParams = playableLibraryParams()
                }

                parentId == SECTION_RECENT -> {
                    val episodes = episodeDao.getRecentEpisodesOnce(limit = 200)
                    val feedMap = feedDao.getFeedOnceAll().associateBy { it.id }
                    allItems = episodes.map { ep -> createEpisodeItem(ep, feedMap[ep.feed_id]?.title) }
                    resultParams = playableLibraryParams()
                }

                parentId == SECTION_DOWNLOADS -> {
                    val episodes = episodeDao.getDownloadedEpisodesOnce()
                    val feedMap = feedDao.getFeedOnceAll().associateBy { it.id }
                    allItems = episodes.map { ep -> createEpisodeItem(ep, feedMap[ep.feed_id]?.title) }
                    resultParams = playableLibraryParams()
                }

                else -> {
                    Log.e(TAG, "[onGetChildren] Unknown parentId: $parentId")
                    return@asyncFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                }
            }

            val pagedItems = paginate(allItems, page, pageSize)
            Log.d(
                TAG,
                "browse pkg=${browser.packageName} parent=$parentId page=$page size=$pageSize total=${allItems.size} returned=${pagedItems.size} ids=${pagedItems.take(10).map { it.mediaId }}"
            )
            LibraryResult.ofItemList(ImmutableList.copyOf(pagedItems), resultParams)
        }

    override fun onSetMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        asyncFuture(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)) {
            val safeRequestedIndex = startIndex.coerceIn(0, mediaItems.lastIndex.coerceAtLeast(0))
            val requestedItem = mediaItems.getOrNull(safeRequestedIndex)

            if (CastCharmApp.isOfflineMode && requestedItem != null && !canPlayOffline(requestedItem)) {
                Log.w(
                    TAG,
                    "Blocked offline playback for mediaId=${requestedItem.mediaId} controller=${controller.packageName}"
                )
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            } else {
                val resolved = mediaItems.map { resolveMediaItem(it) }

                val safeStartIndex = startIndex.coerceIn(0, resolved.lastIndex.coerceAtLeast(0))
                val selectedItem = resolved.getOrNull(safeStartIndex)

                val selectedEpisodeId = selectedItem
                    ?.mediaId
                    ?.removePrefix("episode_")
                    ?.toIntOrNull()

                val savedResumeMs = selectedEpisodeId?.let { id ->
                    episodeDao.getEpisodeOnce(id)
                        ?.takeIf { !it.played }
                        ?.play_position_seconds
                        ?.takeIf { it > 0 }
                        ?.let { maxOf(0L, it * 1000L - 5000L) }
                } ?: 0L

                val finalStartPositionMs = when {
                    startPositionMs > 0L -> startPositionMs
                    (startPositionMs == 0L || startPositionMs == C.TIME_UNSET) && savedResumeMs > 0L -> {
                        Log.d(
                            TAG,
                            "AA resume selectedIndex=$safeStartIndex mediaId=${selectedItem?.mediaId} " +
                                    "controller=${controller.packageName} savedResumeMs=$savedResumeMs " +
                                    "incomingStartPositionMs=$startPositionMs queueSize=${resolved.size}"
                        )
                        savedResumeMs
                    }
                    else -> {
                        if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
                    }
                }

                MediaSession.MediaItemsWithStartPosition(
                    resolved,
                    safeStartIndex,
                    finalStartPositionMs
                )
            }
        }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> =
        asyncFuture(emptyList()) {
            if (CastCharmApp.isOfflineMode) {
                mediaItems.filter { canPlayOffline(it) }.map { resolveMediaItem(it) }
            } else {
                mediaItems.map { resolveMediaItem(it) }
            }
        }

    private suspend fun canPlayOffline(item: MediaItem): Boolean {
        val episodeId = item.mediaId.removePrefix("episode_").toIntOrNull() ?: return true
        val episode = episodeDao.getEpisodeOnce(episodeId) ?: return false
        val path = episode.local_path ?: return false
        return File(path).exists()
    }

    private suspend fun resolveMediaItem(item: MediaItem): MediaItem {
        val episodeId = item.mediaId.removePrefix("episode_").toIntOrNull() ?: return item
        val episode = episodeDao.getEpisodeOnce(episodeId) ?: return item
        val feed = feedDao.getFeedOnce(episode.feed_id)
        val baseUrl = getBaseUrl()

        val localFile = episode.local_path
            ?.let { File(it) }
            ?.takeIf { it.exists() }

        val uri = when {
            localFile != null -> Uri.fromFile(localFile)
            CastCharmApp.isOfflineMode -> null
            baseUrl.isNotEmpty() -> Uri.parse("${baseUrl}api/episodes/$episodeId/stream")
            else -> null
        }

        val feedTitle = feed?.title ?: "Unknown Podcast"
        val artworkUri = resolveEpisodeArtworkUri(context, episode)

        val metadataExtras = Bundle().apply {
            putString("castcharm_episode_id", episode.id.toString())
            putString("castcharm_feed_title", feedTitle)
            putString("castcharm_artwork_uri", artworkUri.toString())
            episode.duration?.let { putLong("castcharm_duration_ms", it * 1000L) }
        }

        val metadata = item.mediaMetadata.buildUpon()
            .setTitle(episode.title)
            .setDisplayTitle(episode.title)
            .setArtist(feedTitle)
            .setSubtitle(feedTitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
            .setArtworkUri(artworkUri)
            .setExtras(metadataExtras)
            .build()

        return item.buildUpon()
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private suspend fun getBaseUrl(): String = ensureApiClientInitializedFromStorage(context)

    @OptIn(UnstableApi::class)
    private fun createFeedItem(feed: FeedEntity): MediaItem {
        val artworkUri = resolveFeedArtworkUri(context, feed)

        val extras = Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )

        Log.d(TAG, "AA FEED ITEM id=${feed.id} title=${feed.title} artworkUri=$artworkUri")

        return MediaItem.Builder()
            .setMediaId(feedMediaId(feed.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(feed.title)
                    .setDisplayTitle(feed.title)
                    .setSubtitle("${feed.episode_count} episodes")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(artworkUri)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    private fun createEpisodeItem(ep: EpisodeEntity, feedName: String?): MediaItem {
        val feedTitle = feedName ?: "Unknown Podcast"
        val artworkUri = resolveEpisodeArtworkUri(context, ep)

        return MediaItem.Builder()
            .setMediaId("episode_${ep.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ep.title)
                    .setDisplayTitle(ep.title)
                    .setArtist(feedTitle)
                    .setSubtitle(feedTitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                    .setArtworkUri(artworkUri)
                    .setExtras(Bundle().apply {
                        putString("castcharm_episode_id", ep.id.toString())
                        putString("castcharm_feed_title", feedTitle)
                        putString("castcharm_artwork_uri", artworkUri.toString())
                        ep.duration?.let { putLong("castcharm_duration_ms", it * 1000L) }
                    })
                    .build()
            )
            .build()
    }
}

private fun paginate(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
    if (items.isEmpty()) return emptyList()
    val safePage = page.coerceAtLeast(0)
    val safePageSize = if (pageSize <= 0) items.size else pageSize
    val from = safePage * safePageSize
    if (from >= items.size) return emptyList()
    val to = minOf(from + safePageSize, items.size)
    return items.subList(from, to)
}

private fun feedMediaId(feedId: Int): String = "feed_$feedId"

@OptIn(UnstableApi::class)
private fun rootItem(context: Context): MediaItem {
    val extras = Bundle()
    extras.putInt(
        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
    )
    extras.putInt(
        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
    )

    return MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("CastCharm")
                .setDisplayTitle("CastCharm")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(extras)
                .build()
        )
        .build()
}

@OptIn(UnstableApi::class)
private fun section(context: Context, id: String, title: String): MediaItem {
    val extras = Bundle()
    if (id == SECTION_PODCASTS) {
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
    }

    Log.d(TAG, "AA SECTION id=$id title=$title extras=$extras")

    return MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setDisplayTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(extras)
                .build()
        )
        .build()
}

@OptIn(UnstableApi::class)
private fun browseLibraryParams(): MediaLibraryService.LibraryParams =
    MediaLibraryService.LibraryParams.Builder()
        .setExtras(
            Bundle().apply {
                putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
            }
        )
        .build()

@OptIn(UnstableApi::class)
private fun browsableLibraryParams(): MediaLibraryService.LibraryParams =
    MediaLibraryService.LibraryParams.Builder()
        .setExtras(
            Bundle().apply {
                putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)
            }
        )
        .build()

@OptIn(UnstableApi::class)
private fun playableLibraryParams(): MediaLibraryService.LibraryParams =
    MediaLibraryService.LibraryParams.Builder()
        .setExtras(
            Bundle().apply {
                putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
            }
        )
        .build()

private fun resolveFeedArtworkUri(context: Context, feed: FeedEntity): Uri {
    return Uri.Builder()
        .scheme("content")
        .authority(context.packageName + ARTWORK_AUTHORITY_SUFFIX)
        .appendPath("feed")
        .appendPath(feed.id.toString())
        .build()
}

private fun resolveEpisodeArtworkUri(context: Context, episode: EpisodeEntity): Uri {
    return Uri.Builder()
        .scheme("content")
        .authority(context.packageName + ARTWORK_AUTHORITY_SUFFIX)
        .appendPath("episode")
        .appendPath(episode.id.toString())
        .build()
}

private fun resourceArtworkUri(context: Context): Uri =
    Uri.parse("android.resource://${context.packageName}/${R.drawable.ic_launcher_foreground}")