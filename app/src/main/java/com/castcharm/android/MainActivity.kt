package com.castcharm.android

// MainActivity is the single Activity that hosts the entire Compose UI. It:
//   1. Schedules the hourly periodic SyncWorker via WorkManager on startup.
//   2. Reads the theme mode and font scale from DataStore and applies them to
//      CastCharmTheme so the whole UI re-renders when either preference changes.
//   3. Renders CastCharmNavigation(), which owns auth-state routing and the
//      full NavHost for all screens.
//
// Screen is a sealed class rather than an enum so each variant can supply its
// own @Composable Icon function (which lets Downloads swap its icon for an
// animated spinner while downloads are active).

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import com.castcharm.android.ui.search.SearchScreen
import com.castcharm.android.ui.shared_components.ReconnectOutlinedButton
import com.castcharm.android.ui.shared_components.navigateToFeedEpisodes
import com.castcharm.android.ui.shared_components.navigateToFeedEpisodesFromDashboard
import com.castcharm.android.ui.shared_components.navigateToFeedEpisodesHighlighted
import com.castcharm.android.ui.shared_components.navigateToFeedsRootFromNested
import com.castcharm.android.ui.shared_components.navigateToPlayer
import com.castcharm.android.ui.shared_components.navigateToSearch
import com.castcharm.android.ui.shared_components.navigateToTopLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

// Each bottom-nav tab is a Screen subclass. The abstract Icon composable is
// overridden per-object so Downloads can show DownloadIconWithProgress instead
// of a static icon. The isDownloading parameter is only meaningful for Downloads.
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

    // Downloads swaps its icon for an animated spinner while any WorkManager
    // download is in progress, giving the user a persistent activity indicator.
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

// Ordered list of bottom-nav tabs. The order determines their left-to-right position.
val bottomNavItems = listOf(
    Screen.Dashboard,
    Screen.Feeds,
    Screen.Downloads,
    Screen.Settings
)

// DataStore keys for appearance preferences written by SettingsScreen.
val THEME_KEY = stringPreferencesKey("theme_mode")
val FONT_SCALE_KEY = floatPreferencesKey("font_scale")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Schedule the hourly SyncWorker. KEEP policy means if a periodic work
        // is already enqueued (e.g., from a previous app launch), it is not
        // replaced — we don't want to reset the 1-hour interval every cold start.
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

            // Collect theme key and font-scale preferences from DataStore. Both are
            // mapped to a Flow so the UI automatically re-renders when the user
            // changes them in Settings without needing to restart the app.
            val themeKey by remember(dataStore) {
                dataStore.data.map { it[THEME_KEY] ?: "system" }
            }.collectAsState(initial = "system")

            val fontScale by remember(dataStore) {
                dataStore.data.map { it[FONT_SCALE_KEY] ?: 1.0f }
            }.collectAsState(initial = 1.0f)

            CastCharmTheme(themeKey = themeKey, fontScale = fontScale) {
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

// Root composable for the app. Owns auth state routing, the "server unreachable"
// offline prompt dialog, session event handling, and the reconnect error snackbar.
// The NavHost and bottom navigation live inside MainScaffold (shown when LoggedIn).
@Composable
fun CastCharmNavigation() {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    // Observe auth and connectivity state from AppSessionManager.
    val connectivityMode by CastCharmApp.connectivityMode.collectAsState()
    val authState by CastCharmApp.authState.collectAsState()
    val reconnectInFlight by CastCharmApp.reconnectInFlight.collectAsState()
    val reconnectErrorMessage by CastCharmApp.reconnectErrorMessage.collectAsState()

    var showRuntimeOfflinePrompt by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // The offline prompt is debounced by 1.8s so a brief transient error (e.g.,
    // a single failed request that resolves immediately) doesn't flash the dialog.
    // pendingOfflinePromptJob holds the pending coroutine so it can be cancelled
    // if conditions change before the delay expires.
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

    // Schedule the offline prompt dialog after a short delay. Multiple guards prevent
    // scheduling when: the user isn't fully logged in online, a reconnect is already
    // in flight, the dialog is already showing, or a pending schedule exists.
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

            // Re-check conditions after the delay — they may have changed
            // (e.g., the server became reachable again during the wait).
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

    // Dismisses the dialog (if showing) and fires a reconnect attempt.
    fun reconnectNow() {
        scope.launch {
            hideOfflinePrompt()
            CastCharmApp.tryReconnectInPlace()
        }
    }

    // Kick off the startup auth check (checks DataStore for a saved URL and
    // pings the server). The result transitions authState out of Checking.
    LaunchedEffect(Unit) {
        CastCharmApp.refreshSessionState()
    }

    // Dismiss the offline prompt whenever the user navigates away from the
    // LoggedIn+Online state (e.g., they manually entered offline mode or the
    // reconnect succeeded and cleared authState).
    LaunchedEffect(authState, connectivityMode, reconnectInFlight) {
        val shouldSuppressPrompt =
            authState != AppAuthState.LoggedIn ||
                    connectivityMode != AppConnectivityMode.ONLINE ||
                    reconnectInFlight

        if (shouldSuppressPrompt) {
            hideOfflinePrompt()
        }
    }

    // Collect one-shot events from AppSessionManager and react to them.
    // ServerUnreachable → schedule the offline prompt dialog.
    // AuthInvalid → dismiss any prompt (the Login screen will appear instead).
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

    // Show reconnect error messages (e.g., "Unable to reconnect") as snackbars.
    // The message is cleared from AppSessionManager after it's shown so it
    // doesn't re-appear on the next recomposition.
    LaunchedEffect(reconnectErrorMessage) {
        reconnectErrorMessage?.let { message ->
            hideOfflinePrompt()
            snackbarHostState.showSnackbar(message)
            CastCharmApp.clearReconnectError()
        }
    }

    // ---- Root composable tree ------------------------------------------------
    // A Box allows the offline prompt dialog and snackbar to float above the
    // current auth-state screen without nesting inside a Scaffold.
    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = authState) {
            // Startup check in progress — show a centred spinner.
            AppAuthState.Checking -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Connecting...", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            // No saved URL or server auth rejected — show the Login screen.
            AppAuthState.NotLoggedIn -> {
                LoginScreen(
                    onLoginSuccess = {
                        scope.launch {
                            CastCharmApp.completeLogin()
                        }
                    }
                )
            }

            // Server unreachable but the user has logged in before — offer offline mode.
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

            // Fully logged in (online or offline) — show the main scaffold with
            // NavHost and bottom navigation.
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

        // The runtime offline alert dialog floats above MainScaffold. It is only
        // shown when the server becomes unreachable during an active logged-in session
        // (not during the entry screen flow, which has its own UI for this case).
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

// Renders the Downloads tab icon. When isDownloading=true, a thin circular
// progress ring surrounds a smaller download arrow, giving a compact activity
// indicator without requiring a badge or counter. The arrow shrinks from 24dp
// to 14dp when active so the ring has room to show around it.
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

// Main scaffold: NavHost + bottom nav bar + mini player bar. Shown when the user
// is fully logged in. Owns the per-screen ViewModel lifecycle via DisposableEffect
// observers that trigger refreshes on ON_RESUME.
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
    // Integer token incremented every time the Downloads tab becomes visible,
    // used to trigger a fresh DownloadsViewModel observation when re-entering.
    var downloadsRefreshToken by remember { mutableIntStateOf(0) }

    // Forward ShowMessage events from the player (e.g., "Cannot stream offline")
    // to the snackbar host so they appear over the current screen.
    LaunchedEffect(playerController) {
        playerController.events.collect { event ->
            when (event) {
                is PlaybackUiEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    // Observe in-progress episodes from the DB to drive the Downloads tab spinner.
    // The DB query is always-on so the spinner appears/disappears in real time.
    val db = remember { AppDatabase.getDatabase(CastCharmApp.instance) }
    val inProgressEpisodes by db.episodeDao().getInProgressEpisodes()
        .collectAsState(initial = emptyList())
    val isAnyDownloadInProgress = inProgressEpisodes.isNotEmpty()

    // Bottom nav is shown on all main tabs and the episode list screen (nested
    // under Feeds). It is hidden on the full-screen player route.
    val showBottomBar =
        currentRoute in listOf("dashboard", "feeds", "downloads", "settings", "search") ||
                currentRoute?.startsWith("episodes/") == true

    val isOnPlayerRoute = currentRoute == "player"

    // Mini player bar is shown whenever media is loaded and we're not already on
    // the full-screen player route (to avoid a redundant playback bar).
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
                    // ---- Dashboard route -------------------------------------
                    // DisposableEffect observes the back-stack entry's lifecycle
                    // so refresh() is called when the user returns from another tab
                    // or navigates back from the episode list. The isOfflineMode
                    // guard prevents network calls while offline.
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
                            onNavigateToEpisode = { feedId, episodeId ->
                                navController.navigateToTopLevel("feeds")
                                navController.navigateToFeedEpisodesHighlighted(feedId, episodeId)
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
                            onSearchClick = if (!isOfflineMode) {
                                { navController.navigateToSearch() }
                            } else null,
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
                            onSearchClick = if (!isOfflineMode) {
                                { navController.navigateToSearch() }
                            } else null,
                            isOfflineMode = isOfflineMode,
                            isReconnectInFlight = isReconnectInFlight,
                            onRetryConnection = onReconnectRequest,
                            onNavigateToDownloads = {
                                navController.navigateToTopLevel("downloads")
                            }
                        )
                    }

                    // ---- Downloads route -------------------------------------
                    // No ViewModel at this level — DownloadsScreen manages its own
                    // ViewModel internally. The refreshToken increment on ON_RESUME
                    // is passed down to DownloadsScreen so it can re-trigger its
                    // internal observation when the tab becomes active.
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

                    // ---- Episode list route ----------------------------------
                    // The feedId is extracted from the route path argument and used
                    // as the ViewModel key so each feed gets its own ViewModel instance
                    // in the ViewModelStore (preventing state bleed between feeds).
                    // The optional ?highlight={episodeId} query param is set when
                    // navigating from search results so EpisodeListScreen can scroll
                    // to and auto-expand that specific episode.
                    composable(
                        "episodes/{feedId}?highlight={highlight}",
                        arguments = listOf(
                            navArgument("feedId") { type = NavType.StringType },
                            navArgument("highlight") {
                                type = NavType.IntType
                                defaultValue = -1
                            }
                        )
                    ) { backStackEntry ->
                        val feedId = backStackEntry.arguments?.getString("feedId")?.toIntOrNull() ?: 0
                        val highlight = backStackEntry.arguments?.getInt("highlight").let {
                            if (it == null || it == -1) null else it
                        }
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
                            highlightEpisodeId = highlight,
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

                    composable("search") {
                        SearchScreen(
                            onNavigateToEpisode = { feedId, episodeId ->
                                navController.navigateToFeedEpisodesHighlighted(feedId, episodeId)
                            },
                            onNavigateBack = { navController.popBackStack() }
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

            // ---- Bottom chrome (mini player + nav bar) -----------------------
            if (showBottomBar) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    // Mini player bar sits directly above the nav bar when media is active.
                    // It shows the episode title, feed name, artwork, a progress bar, and
                    // a play/pause button. Tapping anywhere on the bar opens the full player.
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

                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        bottomNavItems.forEach { screen ->
                            // Determine if this tab is "selected" by checking whether any
                            // destination in the back stack hierarchy matches the route.
                            // The Feeds tab is also selected when the episode list (a child
                            // of Feeds) is active, so the Feeds icon stays highlighted while
                            // browsing episodes.
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

// Compact persistent playback bar shown above the bottom nav while an episode is
// playing. Tapping the bar opens the full PlayerScreen. The play/pause button
// shows a buffering spinner when ExoPlayer is in the buffering state.
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
            // 2dp progress bar at the very top of the mini player bar, mirroring
            // the episode list progress bar. coerceIn prevents rendering artifacts
            // if the position overshoots the duration by a small amount.
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            // Content row: optional artwork | episode + feed title | play-pause button.
            // The entire row is clickable to open the full player, but the play/pause
            // button intercepts its own click without bubbling to the row handler.
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Artwork is only shown when a URI is available; the column fills the
                // remaining space with episode/feed title either way.
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

                // Episode title (primary) and feed name (secondary), both capped at
                // 1 line with ellipsis so the bar height stays fixed regardless of
                // title length.
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

                // 40dp button area. Shows a spinner while buffering; shows the
                // play/pause icon otherwise. The spinner replaces the button entirely
                // so the user knows tapping is not meaningful while buffering.
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