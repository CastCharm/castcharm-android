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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import com.castcharm.android.ui.playlists.PlaylistDetailScreen
import com.castcharm.android.ui.playlists.PlaylistDetailViewModel
import com.castcharm.android.ui.playlists.PlaylistsScreen
import com.castcharm.android.ui.playlists.PlaylistsViewModel
import com.castcharm.android.ui.feeds.FeedListScreen
import com.castcharm.android.ui.feeds.FeedListViewModel
import com.castcharm.android.ui.login.LoginScreen
import com.castcharm.android.ui.player.PlayerScreen
import com.castcharm.android.ui.settings.ENABLE_PLAYLISTS_KEY
import com.castcharm.android.ui.settings.SettingsScreen
import com.castcharm.android.ui.settings.SettingsViewModel
import com.castcharm.android.ui.theme.CastCharmTheme
import com.castcharm.android.ui.search.SearchScreen
import com.castcharm.android.ui.shared_components.SelectionBarHost
import com.castcharm.android.ui.shared_components.SelectionActionBar
import com.castcharm.android.ui.shared_components.LocalSelectionBar
import com.castcharm.android.ui.shared_components.ReconnectOutlinedButton
import com.castcharm.android.ui.shared_components.navigateToFeedEpisodes
import com.castcharm.android.ui.shared_components.navigateToFeedEpisodesFromDashboard
import com.castcharm.android.ui.shared_components.navigateToFeedEpisodesHighlighted
import com.castcharm.android.ui.shared_components.navigateToFeedsRootFromNested
import com.castcharm.android.ui.shared_components.navigateToPlaylistDetail
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

    data object Playlists : Screen("playlists", "Playlists") {
        @Composable
        override fun Icon(isDownloading: Boolean) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Playlists")
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
    Screen.Playlists,
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
                        // The launcher art with the progress ring drawn around it,
                        // rather than a bare spinner. This is the first frame of a
                        // cold start and it can sit here for a couple of seconds on a
                        // slow LAN, so it may as well say which app is starting.
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(96.dp),
                                strokeWidth = 3.dp
                            )
                            Image(
                                painter = painterResource(id = R.drawable.icon_no_bg),
                                contentDescription = null,
                                modifier = Modifier.size(60.dp)
                            )
                        }
                        Spacer(Modifier.height(20.dp))
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
                    // Non-destructive: returns to the login screen but keeps this
                    // device's API key and cookies. Signing out for real lives in
                    // Settings (onChangeServer) — backing out of a temporary network
                    // problem should not cost the user their credentials.
                    onBackToLogin = {
                        hideOfflinePrompt()
                        CastCharmApp.returnToLoginScreen()
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

/**
 * Runs [onResume] every time the screen resumes, including the first time it is
 * shown. This is the *single* place a screen's data load is triggered from.
 *
 * Screens used to load twice on arrival: once from the ViewModel's init block and
 * again from this observer. Two loads land within milliseconds of each other, and
 * because each flips isRefreshing, the pull-to-refresh indicator would appear to
 * fire twice when navigating to a tab. The ViewModels no longer self-load, so
 * arriving at a screen produces exactly one load — from here.
 *
 * Relying on this for the first load is safe because a destination always reaches
 * RESUMED, and LifecycleRegistry replays the upward events into an observer that
 * registers when the lifecycle is already RESUMED.
 *
 * Keyed on the lifecycle alone. Do not add changing values such as isOfflineMode
 * to the key — re-keying disposes and re-registers the observer, which replays
 * ON_RESUME and fires a spurious extra load. Read such values through
 * rememberUpdatedState inside [onResume] instead.
 */
@Composable
private fun OnScreenResumed(
    lifecycle: Lifecycle,
    onResume: () -> Unit,
) {
    val currentOnResume by rememberUpdatedState(onResume)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentOnResume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/**
 * Runs [onOnline] when the app transitions from offline back to online while this
 * screen is showing.
 *
 * Screens whose resume handler is gated on being online would otherwise sit on
 * cached data until the user navigated away and back. The previous code got this
 * behaviour by accident — isOfflineMode was part of a DisposableEffect key, so
 * flipping it re-registered a lifecycle observer and replayed ON_RESUME. That
 * replay was the same mechanism causing spurious double-refreshes, so the
 * transition is now handled explicitly instead.
 */
@Composable
private fun OnReturnedOnline(isOfflineMode: Boolean, onOnline: () -> Unit) {
    val currentOnOnline by rememberUpdatedState(onOnline)
    var wasOffline by remember { mutableStateOf(isOfflineMode) }
    LaunchedEffect(isOfflineMode) {
        if (wasOffline && !isOfflineMode) currentOnOnline()
        wasOffline = isOfflineMode
    }
}

// Main scaffold: NavHost + bottom nav bar + mini player bar. Shown when the user
// is fully logged in. Each screen's data load is triggered from exactly one place,
// OnScreenResumed, rather than from both the ViewModel's init and a lifecycle
// observer — see the note on that function.
// Duration of the fade between navigation destinations.
//
// navigation-compose defaults to 700 ms, which felt like waiting. Dropping it to
// 160 ms felt worse — not quick, but abrupt: a fade that short is about ten
// frames, so any hitch while the incoming screen composes eats a visible fraction
// of it and the whole thing reads as a stutter. 500 ms is long enough that the
// motion carries through a dropped frame or two, which is what "smooth" actually
// depends on here, and it costs nothing on slower hardware because a crossfade is
// just alpha.
private const val NAV_TRANSITION_MS = 500

// The player's slide. Kept on tween's default FastOutSlowIn easing, which is what
// that curve is actually for — it gives the sheet a sense of weight, starting and
// settling rather than moving at a constant rate. Slightly quicker than the fade
// because travel reads as slower than a crossfade of the same duration.
private const val PLAYER_TRANSITION_MS = 380

/** True when this back-stack entry is the full-screen player. */
private val androidx.navigation.NavBackStackEntry.isPlayerRoute: Boolean
    get() = destination.route == "player"

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

    val dataStore = CastCharmApp.instance.dataStore
    val enablePlaylists by remember(dataStore) {
        dataStore.data.map { it[ENABLE_PLAYLISTS_KEY] ?: false }
    }.collectAsState(initial = false)

    val playerController = CastCharmApp.playerController
    val playbackUiState by playerController.playbackState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // Integer token incremented every time the Downloads tab becomes visible,
    // used to trigger a fresh DownloadsViewModel observation when re-entering.
    // Constant now — DownloadsScreen reloads via its own LaunchedEffect when the
    // composable enters composition. Kept as a parameter so a future caller can
    // still force a reload by changing it.
    val downloadsRefreshToken by remember { mutableIntStateOf(0) }

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
    val playlistRoutes = if (enablePlaylists) listOf("playlists") else emptyList()
    val showBottomBar =
        currentRoute in (listOf("dashboard", "feeds", "downloads", "settings", "search") + playlistRoutes) ||
                currentRoute?.startsWith("episodes/") == true ||
                (enablePlaylists && currentRoute?.startsWith("playlists/") == true)

    val isOnPlayerRoute = currentRoute == "player"

    // Mini player bar is shown whenever media is loaded and we're not already on
    // the full-screen player route (to avoid a redundant playback bar).
    val showMiniPlayer =
        playbackUiState.hasMedia &&
                playbackUiState.episodeId != null &&
                !playbackUiState.title.isNullOrBlank() &&
                !isOnPlayerRoute

    // Screens inside the NavHost publish their multi-select actions here via
    // ProvideSelectionActions; the bottom bar below renders them in place of the
    // tab bar while a selection is active.
    val selectionBarHost = remember { SelectionBarHost() }

    CompositionLocalProvider(LocalSelectionBar provides selectionBarHost) {
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
                    modifier = Modifier.fillMaxSize(),
                    // navigation-compose defaults to a 700 ms crossfade on every
                    // destination change. That is most of a second before a screen
                    // settles — it reads as the app thinking rather than as motion,
                    // and it applies to every tab switch, every feed opened, and
                    // opening and closing the player. A short fade keeps the sense
                    // of a transition without making the user wait for it.
                    // Two kinds of motion, chosen per destination.
                    //
                    // The player behaves like a sheet drawn up over the app, so it
                    // moves through space — and the screen underneath deliberately
                    // does nothing, because something sliding over a surface that
                    // is simultaneously fading reads as two unrelated animations.
                    //
                    // Everything else crossfades. Those are lateral moves between
                    // peers, where there is no "direction" to travel in, and alpha
                    // uses LinearEasing: tween() defaults to FastOutSlowIn, which
                    // is right for movement but makes a fade appear to stall at
                    // each end and rush the middle.
                    enterTransition = {
                        if (targetState.isPlayerRoute) {
                            slideInVertically(
                                initialOffsetY = { it },
                                animationSpec = tween(PLAYER_TRANSITION_MS),
                            )
                        } else {
                            fadeIn(tween(NAV_TRANSITION_MS, easing = LinearEasing))
                        }
                    },
                    exitTransition = {
                        if (targetState.isPlayerRoute) ExitTransition.None
                        else fadeOut(tween(NAV_TRANSITION_MS, easing = LinearEasing))
                    },
                    popEnterTransition = {
                        if (initialState.isPlayerRoute) EnterTransition.None
                        else fadeIn(tween(NAV_TRANSITION_MS, easing = LinearEasing))
                    },
                    popExitTransition = {
                        if (initialState.isPlayerRoute) {
                            slideOutVertically(
                                targetOffsetY = { it },
                                animationSpec = tween(PLAYER_TRANSITION_MS),
                            )
                        } else {
                            fadeOut(tween(NAV_TRANSITION_MS, easing = LinearEasing))
                        }
                    },
                ) {
                    // ---- Dashboard route -------------------------------------
                    // The ViewModel collects cached data from the DB in its init;
                    // OnScreenResumed triggers the server pull, both on first arrival
                    // and when returning from another tab or the episode list.
                    composable("dashboard") { backStackEntry ->
                        val dashVm: DashboardViewModel = viewModel()

                        val offlineNowDash by rememberUpdatedState(isOfflineMode)
                        OnScreenResumed(backStackEntry.lifecycle) {
                            if (!offlineNowDash) dashVm.refresh()
                        }
                        OnReturnedOnline(isOfflineMode) { dashVm.refresh() }

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
                        val offlineNow by rememberUpdatedState(isOfflineMode)

                        OnScreenResumed(backStackEntry.lifecycle) {
                            if (!offlineNow) feedVm.refreshFeeds()
                        }
                        OnReturnedOnline(isOfflineMode) { feedVm.refreshFeeds() }

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
                    // ViewModel internally. The refreshToken increment is passed down
                    // to DownloadsScreen so it can re-trigger its internal observation
                    // when the user comes back to the tab. DownloadsScreen already
                    // loads once on first composition via LaunchedEffect(refreshToken),
                    // so the token must not be bumped on that first pass.
                    composable("downloads") { _ ->
                        // No ON_RESUME observer here: DownloadsScreen's own
                        // LaunchedEffect(isOfflineMode, refreshToken) already runs
                        // when the composable enters composition, which happens on
                        // every navigation to this tab. Bumping the token from a
                        // lifecycle observer as well produced a second load a few
                        // milliseconds after the first.

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

                        // Activity lifecycle, not the back-stack entry: this reloads
                        // storage figures when the app returns from the background.
                        // SettingsViewModel already loads in its init block.
                        OnScreenResumed(lifecycleOwner.lifecycle) {
                            settingsVm.reload()
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

                        val offlineNow by rememberUpdatedState(isOfflineMode)
                        OnScreenResumed(backStackEntry.lifecycle) {
                            if (!offlineNow) vm.refresh()
                        }
                        OnReturnedOnline(isOfflineMode) { vm.refresh() }

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
                            },
                            enablePlaylists = enablePlaylists
                        )
                    }

                    composable("playlists") { backStackEntry ->
                        val playlistsVm: PlaylistsViewModel = viewModel()

                        OnScreenResumed(backStackEntry.lifecycle) { playlistsVm.loadPlaylists() }

                        PlaylistsScreen(
                            viewModel = playlistsVm,
                            onViewPlaylist = { playlistId ->
                                navController.navigateToPlaylistDetail(playlistId)
                            },
                            onPlayPlaylist = { playlistId ->
                                navController.navigateToPlaylistDetail(playlistId)
                            },
                            onEpisodeReady = { episodeId ->
                                playerController.playEpisode(episodeId)
                                navController.navigateToPlayer()
                            }
                        )
                    }

                    composable(
                        "playlists/{playlistId}",
                        arguments = listOf(
                            navArgument("playlistId") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val playlistId = backStackEntry.arguments?.getString("playlistId")?.toIntOrNull() ?: 0
                        val vm = viewModel<PlaylistDetailViewModel>(key = "playlist_$playlistId") {
                            PlaylistDetailViewModel(playlistId)
                        }

                        OnScreenResumed(backStackEntry.lifecycle) { vm.loadPlaylist() }

                        PlaylistDetailScreen(
                            playlistId = playlistId,
                            viewModel = vm,
                            onPlayEpisode = { episodeId ->
                                playerController.playEpisode(episodeId)
                                navController.navigateToPlayer()
                            },
                            onNavigateBack = { navController.popBackStack() },
                            isOfflineMode = isOfflineMode,
                            enablePlaylists = enablePlaylists
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

                    // While a screen has bulk actions to offer, they take over this
                    // bar rather than appearing as a second bar stacked above it.
                    // Leaving tab navigation live during multi-select would let the
                    // user wander off mid-selection anyway, and the top bar keeps an
                    // X to exit, so nothing becomes unreachable.
                    val selectionActions = selectionBarHost.actions
                    if (selectionActions.isNotEmpty()) {
                        SelectionActionBar(actions = selectionActions)
                    } else {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        bottomNavItems.filter { screen ->
                            screen !is Screen.Playlists || enablePlaylists
                        }.forEach { screen ->
                            // Determine if this tab is "selected" by checking whether any
                            // destination in the back stack hierarchy matches the route.
                            // The Feeds tab is also selected when the episode list (a child
                            // of Feeds) is active, so the Feeds icon stays highlighted while
                            // browsing episodes.
                            val selected =
                                navBackStackEntry?.destination?.hierarchy?.any {
                                    it.route == screen.route
                                } == true ||
                                        (screen is Screen.Feeds && currentRoute?.startsWith("episodes/") == true) ||
                                        (screen is Screen.Playlists && currentRoute?.startsWith("playlists/") == true)

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

                                        is Screen.Playlists -> {
                                            navController.navigateToTopLevel(Screen.Playlists.route)
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
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (showBottomBar) 88.dp else 24.dp)
        )
    }
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