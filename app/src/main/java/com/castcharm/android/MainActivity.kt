package com.castcharm.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.download.StorageManager
import com.castcharm.android.player.PlaybackUiEvent
import com.castcharm.android.ui.dashboard.DashboardScreen
import com.castcharm.android.ui.dashboard.DashboardViewModel
import com.castcharm.android.ui.downloads.DownloadsScreen
import com.castcharm.android.ui.episodes.EpisodeListScreen
import com.castcharm.android.ui.episodes.EpisodeListViewModel
import com.castcharm.android.ui.feeds.FeedListScreen
import com.castcharm.android.ui.feeds.FeedListViewModel
import com.castcharm.android.ui.login.LoginScreen
import com.castcharm.android.ui.player.PlayerScreen
import com.castcharm.android.ui.settings.SettingsScreen
import com.castcharm.android.ui.settings.SettingsViewModel
import com.castcharm.android.ui.theme.CastCharmTheme
import com.castcharm.android.ui.shared_components.ReconnectOutlinedButton
import com.castcharm.android.ui.shared_components.navigateToFeedEpisodes
import com.castcharm.android.ui.shared_components.navigateToFeedEpisodesFromDashboard
import com.castcharm.android.ui.shared_components.navigateToFeedsRootFromNested
import com.castcharm.android.ui.shared_components.navigateToPlayer
import com.castcharm.android.ui.shared_components.navigateToTopLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

sealed class Screen(val route: String, val label: String) {
    @Composable
    abstract fun Icon(isDownloading: Boolean)

    data object Dashboard : Screen("dashboard", "Home") {
        @Composable
        override fun Icon(isDownloading: Boolean) {
            Icon(Icons.Default.Home, contentDescription = "Home")
        }
    }

    data object Feeds : Screen("feeds", "Podcasts") {
        @Composable
        override fun Icon(isDownloading: Boolean) {
            Icon(Icons.Default.Podcasts, contentDescription = "Podcasts")
        }
    }

    data object Downloads : Screen("downloads", "Downloads") {
        @Composable
        override fun Icon(isDownloading: Boolean) {
            if (isDownloading) {
                DownloadIconWithProgress(isDownloading = true)
            } else {
                Icon(Icons.Default.Download, contentDescription = "Downloads")
            }
        }
    }

    data object Settings : Screen("settings", "Settings") {
        @Composable
        override fun Icon(isDownloading: Boolean) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }
}

val bottomNavItems = listOf(
    Screen.Dashboard,
    Screen.Feeds,
    Screen.Downloads,
    Screen.Settings
)

val THEME_KEY = stringPreferencesKey("theme_mode")
val FONT_SCALE_KEY = floatPreferencesKey("font_scale")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val syncRequest = PeriodicWorkRequestBuilder<com.castcharm.android.sync.SyncWorker>(
            1,
            TimeUnit.HOURS
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "offline_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )

        setContent {
            val dataStore = CastCharmApp.instance.dataStore

            val themeMode by remember(dataStore) {
                dataStore.data.map { it[THEME_KEY] ?: "system" }
            }.collectAsState(initial = "system")

            val fontScale by remember(dataStore) {
                dataStore.data.map { it[FONT_SCALE_KEY] ?: 1.0f }
            }.collectAsState(initial = 1.0f)

            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            CastCharmTheme(darkTheme = darkTheme, fontScale = fontScale) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CastCharmNavigation()
                }
            }
        }
    }
}

@Composable
fun CastCharmNavigation() {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    val connectivityMode by CastCharmApp.connectivityMode.collectAsState()
    val authState by CastCharmApp.authState.collectAsState()
    val reconnectInFlight by CastCharmApp.reconnectInFlight.collectAsState()
    val reconnectErrorMessage by CastCharmApp.reconnectErrorMessage.collectAsState()

    var showRuntimeOfflinePrompt by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    var pendingOfflinePromptJob by remember { mutableStateOf<Job?>(null) }
    val offlinePromptDelayMs = 1800L

    fun cancelPendingOfflinePrompt() {
        pendingOfflinePromptJob?.cancel()
        pendingOfflinePromptJob = null
    }

    fun hideOfflinePrompt() {
        cancelPendingOfflinePrompt()
        showRuntimeOfflinePrompt = false
    }

    fun scheduleOfflinePromptIfNeeded() {
        if (
            authState != AppAuthState.LoggedIn ||
            connectivityMode != AppConnectivityMode.ONLINE ||
            reconnectInFlight ||
            showRuntimeOfflinePrompt ||
            pendingOfflinePromptJob != null
        ) {
            return
        }

        pendingOfflinePromptJob = scope.launch {
            delay(offlinePromptDelayMs)

            val stillEligible =
                authState == AppAuthState.LoggedIn &&
                        connectivityMode == AppConnectivityMode.ONLINE &&
                        !reconnectInFlight

            if (stillEligible) {
                showRuntimeOfflinePrompt = true
            }

            pendingOfflinePromptJob = null
        }
    }

    fun reconnectNow() {
        scope.launch {
            hideOfflinePrompt()
            CastCharmApp.tryReconnectInPlace()
        }
    }

    LaunchedEffect(Unit) {
        CastCharmApp.refreshSessionState()
    }

    LaunchedEffect(authState, connectivityMode, reconnectInFlight) {
        val shouldSuppressPrompt =
            authState != AppAuthState.LoggedIn ||
                    connectivityMode != AppConnectivityMode.ONLINE ||
                    reconnectInFlight

        if (shouldSuppressPrompt) {
            hideOfflinePrompt()
        }
    }

    LaunchedEffect(Unit) {
        CastCharmApp.sessionEvents.collect { event ->
            when (event) {
                AppSessionEvent.ServerUnreachable -> {
                    scheduleOfflinePromptIfNeeded()
                }

                AppSessionEvent.AuthInvalid -> {
                    hideOfflinePrompt()
                }
            }
        }
    }

    LaunchedEffect(reconnectErrorMessage) {
        reconnectErrorMessage?.let { message ->
            hideOfflinePrompt()
            snackbarHostState.showSnackbar(message)
            CastCharmApp.clearReconnectError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = authState) {
            AppAuthState.Checking -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Connecting...", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            AppAuthState.NotLoggedIn -> {
                LoginScreen(
                    onLoginSuccess = {
                        scope.launch {
                            CastCharmApp.completeLogin()
                        }
                    }
                )
            }

            is AppAuthState.OfflineAvailable -> {
                OfflineModeEntryScreen(
                    serverUrl = state.serverUrl,
                    reconnectInFlight = reconnectInFlight,
                    onRetry = { reconnectNow() },
                    onGoOffline = {
                        hideOfflinePrompt()
                        CastCharmApp.enterOfflineMode()
                    },
                    onBackToLogin = {
                        scope.launch {
                            hideOfflinePrompt()
                            CastCharmApp.logoutAndForgetSession()
                        }
                    }
                )
            }

            AppAuthState.LoggedIn -> {
                MainScaffold(
                    navController = navController,
                    isOfflineMode = connectivityMode == AppConnectivityMode.OFFLINE,
                    isReconnectInFlight = reconnectInFlight,
                    onReconnectRequest = { reconnectNow() },
                    onChangeServer = {
                        scope.launch {
                            hideOfflinePrompt()
                            CastCharmApp.logoutAndForgetSession()
                        }
                    }
                )
            }
        }

        if (
            showRuntimeOfflinePrompt &&
            authState == AppAuthState.LoggedIn &&
            connectivityMode == AppConnectivityMode.ONLINE
        ) {
            AlertDialog(
                onDismissRequest = { hideOfflinePrompt() },
                title = { Text("Server unavailable") },
                text = {
                    Text("Your server can’t be reached right now. You can keep using downloaded content in Offline Mode, or try reconnecting.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hideOfflinePrompt()
                            CastCharmApp.enterOfflineMode()
                        }
                    ) {
                        Text("Go Offline")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { reconnectNow() },
                        enabled = !reconnectInFlight
                    ) {
                        if (reconnectInFlight) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (reconnectInFlight) "Retrying..." else "Retry")
                    }
                }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
private fun OfflineModeEntryScreen(
    serverUrl: String,
    reconnectInFlight: Boolean,
    onRetry: () -> Unit,
    onGoOffline: () -> Unit,
    onBackToLogin: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Offline Mode Available",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "We couldn't reach your server at $serverUrl. You can retry, or enter Offline Mode to access downloaded content.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))
            TextButton(
                onClick = onGoOffline,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enter Offline Mode")
            }
            Spacer(Modifier.height(12.dp))
            ReconnectOutlinedButton(
                onClick = onRetry,
                inFlight = reconnectInFlight,
                text = "Retry Connection",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onBackToLogin,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back to Login")
            }
        }
    }
}

@Composable
fun DownloadIconWithProgress(isDownloading: Boolean) {
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isDownloading) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        }

        Icon(
            Icons.Default.Download,
            contentDescription = "Downloads",
            modifier = Modifier.size(if (isDownloading) 14.dp else 24.dp)
        )
    }
}

@Composable
fun MainScaffold(
    navController: androidx.navigation.NavHostController,
    isOfflineMode: Boolean = false,
    isReconnectInFlight: Boolean = false,
    onReconnectRequest: () -> Unit = {},
    onChangeServer: () -> Unit = {}
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val playerController = CastCharmApp.playerController
    val playbackUiState by playerController.playbackState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var downloadsRefreshToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(playerController) {
        playerController.events.collect { event ->
            when (event) {
                is PlaybackUiEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    val db = remember { AppDatabase.getDatabase(CastCharmApp.instance) }
    val inProgressEpisodes by db.episodeDao().getInProgressEpisodes()
        .collectAsState(initial = emptyList())
    val isAnyDownloadInProgress = inProgressEpisodes.isNotEmpty()

    val showBottomBar =
        currentRoute in listOf("dashboard", "feeds", "downloads", "settings") ||
                currentRoute?.startsWith("episodes/") == true

    val isOnPlayerRoute = currentRoute == "player"

    val showMiniPlayer =
        playbackUiState.hasMedia &&
                playbackUiState.episodeId != null &&
                !playbackUiState.title.isNullOrBlank() &&
                !isOnPlayerRoute

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                NavHost(
                    navController = navController,
                    startDestination = "dashboard",
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable("dashboard") { backStackEntry ->
                        val dashVm: DashboardViewModel = viewModel()

                        DisposableEffect(backStackEntry.lifecycle, isOfflineMode) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (!isOfflineMode && event == Lifecycle.Event.ON_RESUME) {
                                    dashVm.refresh()
                                }
                            }
                            backStackEntry.lifecycle.addObserver(observer)
                            onDispose { backStackEntry.lifecycle.removeObserver(observer) }
                        }

                        DashboardScreen(
                            viewModel = dashVm,
                            onNavigateToFeed = { feedId ->
                                navController.navigateToFeedEpisodesFromDashboard(feedId)
                            },
                            onPlayEpisode = { episodeId ->
                                playerController.playEpisode(episodeId)
                                navController.navigateToPlayer()
                            },
                            onNavigateToFeeds = {
                                navController.navigateToTopLevel("feeds")
                            },
                            onNavigateToDownloads = {
                                navController.navigateToTopLevel("downloads")
                            },
                            isOfflineMode = isOfflineMode,
                            isReconnectInFlight = isReconnectInFlight,
                            onRetryConnection = onReconnectRequest
                        )
                    }

                    composable("feeds") { backStackEntry ->
                        val feedVm: FeedListViewModel = viewModel()

                        DisposableEffect(backStackEntry.lifecycle, isOfflineMode) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (!isOfflineMode && event == Lifecycle.Event.ON_RESUME) {
                                    feedVm.refreshFeeds()
                                }
                            }
                            backStackEntry.lifecycle.addObserver(observer)
                            onDispose { backStackEntry.lifecycle.removeObserver(observer) }
                        }

                        FeedListScreen(
                            viewModel = feedVm,
                            onNavigateToEpisodes = { feedId ->
                                navController.navigateToFeedEpisodes(feedId)
                            },
                            isOfflineMode = isOfflineMode,
                            isReconnectInFlight = isReconnectInFlight,
                            onRetryConnection = onReconnectRequest,
                            onNavigateToDownloads = {
                                navController.navigateToTopLevel("downloads")
                            }
                        )
                    }

                    composable("downloads") { backStackEntry ->
                        DisposableEffect(backStackEntry.lifecycle, isOfflineMode) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) {
                                    downloadsRefreshToken++
                                }
                            }
                            backStackEntry.lifecycle.addObserver(observer)
                            onDispose { backStackEntry.lifecycle.removeObserver(observer) }
                        }

                        DownloadsScreen(
                            refreshToken = downloadsRefreshToken,
                            onPlayEpisode = { episodeId ->
                                playerController.playEpisode(episodeId)
                                navController.navigateToPlayer()
                            }
                        )
                    }

                    composable("settings") {
                        val storageManager = remember { StorageManager(CastCharmApp.instance) }
                        val settingsVm = viewModel<SettingsViewModel> { SettingsViewModel(storageManager) }
                        val lifecycleOwner = LocalLifecycleOwner.current

                        DisposableEffect(lifecycleOwner) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) settingsVm.reload()
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }

                        SettingsScreen(
                            viewModel = settingsVm,
                            onChangeServer = onChangeServer,
                            isOfflineMode = isOfflineMode,
                            isReconnectInFlight = isReconnectInFlight,
                            onTryReconnect = onReconnectRequest
                        )
                    }

                    composable("episodes/{feedId}") { backStackEntry ->
                        val feedId = backStackEntry.arguments?.getString("feedId")?.toIntOrNull() ?: 0
                        val vm = viewModel<EpisodeListViewModel>(key = "episodes_$feedId") {
                            EpisodeListViewModel(feedId)
                        }

                        DisposableEffect(backStackEntry.lifecycle, isOfflineMode) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (!isOfflineMode && event == Lifecycle.Event.ON_RESUME) {
                                    vm.reloadFromDb()
                                }
                            }
                            backStackEntry.lifecycle.addObserver(observer)
                            onDispose { backStackEntry.lifecycle.removeObserver(observer) }
                        }

                        EpisodeListScreen(
                            feedId = feedId,
                            viewModel = vm,
                            onPlayEpisode = { episodeId ->
                                playerController.playEpisode(episodeId)
                                navController.navigateToPlayer()
                            },
                            onNavigateBack = {
                                navController.popBackStack()
                            },
                            isOfflineMode = isOfflineMode,
                            isReconnectInFlight = isReconnectInFlight,
                            onRetryConnection = onReconnectRequest,
                            onNavigateToDownloads = {
                                navController.navigateToTopLevel("downloads")
                            }
                        )
                    }

                    composable("player") {
                        PlayerScreen(
                            onClose = {
                                navController.popBackStack()
                            },
                            onStop = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }

            if (showBottomBar) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    if (showMiniPlayer) {
                        MiniPlayerBar(
                            episodeTitle = playbackUiState.title ?: "",
                            feedTitle = playbackUiState.feedTitle ?: "",
                            artworkUri = playbackUiState.artworkUri,
                            isPlaying = playbackUiState.isPlaying,
                            isBuffering = playbackUiState.isBuffering,
                            progress = if (playbackUiState.durationMs > 0) {
                                (playbackUiState.positionMs.toFloat() / playbackUiState.durationMs).coerceIn(0f, 1f)
                            } else {
                                0f
                            },
                            onPlayPause = {
                                if (playbackUiState.isPlaying) {
                                    playerController.pause()
                                } else {
                                    playerController.resume()
                                }
                            },
                            onClick = {
                                navController.navigateToPlayer()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }

                    NavigationBar {
                        bottomNavItems.forEach { screen ->
                            val selected =
                                navBackStackEntry?.destination?.hierarchy?.any {
                                    it.route == screen.route
                                } == true ||
                                        (screen is Screen.Feeds && currentRoute?.startsWith("episodes/") == true)

                            NavigationBarItem(
                                icon = {
                                    if (screen is Screen.Downloads) {
                                        DownloadIconWithProgress(
                                            isDownloading = isAnyDownloadInProgress
                                        )
                                    } else {
                                        screen.Icon(isDownloading = false)
                                    }
                                },
                                label = { Text(screen.label) },
                                selected = selected,
                                onClick = {
                                    when (screen) {
                                        is Screen.Dashboard -> {
                                            navController.navigateToTopLevel(Screen.Dashboard.route)
                                        }

                                        is Screen.Feeds -> {
                                            navController.navigateToFeedsRootFromNested(currentRoute)
                                        }

                                        is Screen.Downloads -> {
                                            navController.navigateToTopLevel(Screen.Downloads.route)
                                        }

                                        is Screen.Settings -> {
                                            navController.navigateToTopLevel(Screen.Settings.route)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (showBottomBar) 88.dp else 24.dp)
        )
    }
}

@Composable
fun MiniPlayerBar(
    episodeTitle: String,
    feedTitle: String,
    artworkUri: String?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Column {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!artworkUri.isNullOrBlank()) {
                    AsyncImage(
                        model = artworkUri,
                        contentDescription = episodeTitle,
                        imageLoader = CastCharmApp.imageLoader,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(Modifier.size(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = episodeTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = feedTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.size(8.dp))

                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        FilledIconButton(
                            onClick = onPlayPause,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}