package com.castcharm.android

// AppSessionManager is the central authority for authentication and connectivity
// state across the entire app. It owns three public flows:
//   authState    — the current login/offline status (drives navigation in MainActivity)
//   connectivityMode — ONLINE or OFFLINE (controls whether API calls are attempted)
//   sessionEvents — one-shot events (ServerUnreachable, AuthInvalid) used to trigger
//                   the offline prompt dialog and snackbar messages
//
// Connectivity detection is intentionally conservative: a single network error does
// NOT immediately switch the app offline. Instead, three failures within an 8-second
// window are required before a ServerUnreachable event is emitted, and a 25-second
// cooldown prevents the event from firing again immediately after it clears. This
// avoids false alarms from transient network blips.

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.castcharm.android.sync.SyncWorker
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

// Internal result of the one-time auth check against the configured server URL.
// Only used within AppSessionManager; callers receive the public AppAuthState instead.
private enum class SessionCheckResult {
    LOGGED_IN,
    NOT_LOGGED_IN,
    UNREACHABLE
}

// One-shot events emitted to the UI via sessionEvents. These are distinct from
// authState changes because they represent transient notifications (prompt the user)
// rather than persistent state (change which screen is visible).
sealed class AppSessionEvent {
    data object ServerUnreachable : AppSessionEvent()
    data object AuthInvalid : AppSessionEvent()
}

// The four possible auth states. CastCharmNavigation in MainActivity uses this
// sealed class to decide which root composable to show.
//   Checking       — startup auth check in progress; show spinner
//   NotLoggedIn    — no saved server URL, or server auth rejected; show Login screen
//   LoggedIn       — server confirmed session is valid; show main scaffold
//   OfflineAvailable — server unreachable but the user has logged in before;
//                      show the offline entry screen offering Offline Mode
sealed class AppAuthState {
    data object Checking : AppAuthState()
    data object NotLoggedIn : AppAuthState()
    data object LoggedIn : AppAuthState()
    data class OfflineAvailable(val serverUrl: String) : AppAuthState()
}

class AppSessionManager(
    private val appContext: Context
) {
    // DataStore keys for the two pieces of persistent state this class cares about.
    // hasAuthenticatedBefore guards the OfflineAvailable path: we only offer offline
    // mode if the user has successfully logged in at some point in the past.
    private val hasAuthenticatedBeforeKey = booleanPreferencesKey("has_authenticated_before")
    private val serverUrlKey = stringPreferencesKey("server_url")

    // Public state flows — all exposed as read-only StateFlow/SharedFlow so UI
    // components cannot mutate state directly.
    private val _connectivityMode = MutableStateFlow(AppConnectivityMode.ONLINE)
    val connectivityMode: StateFlow<AppConnectivityMode> = _connectivityMode.asStateFlow()

    private val _authState = MutableStateFlow<AppAuthState>(AppAuthState.Checking)
    val authState: StateFlow<AppAuthState> = _authState.asStateFlow()

    // sessionEvents is a SharedFlow with no replay so events are consumed once and
    // not re-delivered to new subscribers. extraBufferCapacity=8 with DROP_OLDEST
    // prevents blocking the interceptor thread if the UI is slow to collect.
    private val _sessionEvents = MutableSharedFlow<AppSessionEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sessionEvents: SharedFlow<AppSessionEvent> = _sessionEvents.asSharedFlow()

    // Tracks whether a manual reconnect attempt is currently running.
    // The UI disables reconnect buttons and shows a spinner while this is true.
    private val _reconnectInFlight = MutableStateFlow(false)
    val reconnectInFlight: StateFlow<Boolean> = _reconnectInFlight.asStateFlow()

    // Holds the most recent reconnect error message for display as a snackbar.
    // Cleared by clearReconnectError() after the UI has shown it.
    private val _reconnectErrorMessage = MutableStateFlow<String?>(null)
    val reconnectErrorMessage: StateFlow<String?> = _reconnectErrorMessage.asStateFlow()

    // ---- Transient failure tracking (for the 3-in-8s threshold) --------------
    // @Volatile ensures visibility across threads (the OkHttp interceptor calls
    // reportServerUnreachable() from a background network thread).
    @Volatile
    private var firstRecentFailureAtMs: Long = 0L

    @Volatile
    private var recentFailureCount: Int = 0

    @Volatile
    private var lastServerUnreachableEmissionAtMs: Long = 0L

    // Tuning constants for the failure-window debounce.
    private val failureWindowMs = 8_000L       // window in which failures are counted
    private val emissionCooldownMs = 25_000L   // minimum gap between ServerUnreachable events
    private val requiredRecentFailures = 3      // failures needed within the window

    val isOfflineMode: Boolean
        get() = _connectivityMode.value == AppConnectivityMode.OFFLINE

    fun clearReconnectError() {
        _reconnectErrorMessage.value = null
    }

    // Switch to offline mode: stop the failure counter (no point counting failures
    // when we're already offline), and promote OfflineAvailable → LoggedIn so the
    // main scaffold becomes visible (the offline scaffold panels replace server data).
    fun enterOfflineMode() {
        _connectivityMode.value = AppConnectivityMode.OFFLINE
        clearReconnectError()
        resetTransientFailureTracking()

        if (_authState.value is AppAuthState.OfflineAvailable) {
            _authState.value = AppAuthState.LoggedIn
        }
    }

    // Switch to online mode. Promotes OfflineAvailable → LoggedIn so the normal
    // main scaffold becomes visible. The screens will then attempt server refreshes
    // on their next ON_RESUME lifecycle event.
    fun enterOnlineMode() {
        _connectivityMode.value = AppConnectivityMode.ONLINE

        if (_authState.value is AppAuthState.OfflineAvailable) {
            _authState.value = AppAuthState.LoggedIn
        }
    }

    // Called once on app startup (via LaunchedEffect(Unit) in CastCharmNavigation).
    // Initializes the ApiClient with the saved URL and tries a live auth check.
    // The result determines which root composable MainActivity shows.
    suspend fun refreshSessionState() {
        _authState.value = AppAuthState.Checking
        clearReconnectError()
        resetTransientFailureTracking()

        val savedUrl = loadSavedUrl()
        val hasAuthenticatedBefore = loadHasAuthenticatedBefore()

        // No saved URL means first launch or after a logout — go straight to Login.
        if (savedUrl.isNullOrBlank()) {
            enterOnlineMode()
            _authState.value = AppAuthState.NotLoggedIn
            return
        }

        when (checkSavedSession(savedUrl)) {
            SessionCheckResult.LOGGED_IN -> {
                enterOnlineMode()
                _authState.value = AppAuthState.LoggedIn
                // Persist the fact that login succeeded so future unreachable events
                // can offer OfflineAvailable instead of dropping to NotLoggedIn.
                appContext.dataStore.edit { prefs ->
                    prefs[hasAuthenticatedBeforeKey] = true
                }
                enqueueImmediateSync()
            }

            SessionCheckResult.NOT_LOGGED_IN -> {
                enterOnlineMode()
                _authState.value = AppAuthState.NotLoggedIn
            }

            SessionCheckResult.UNREACHABLE -> {
                // Only offer offline mode if the user has logged in before — otherwise
                // they would see offline content without ever having set up the server.
                if (hasAuthenticatedBefore) {
                    _authState.value = AppAuthState.OfflineAvailable(savedUrl)
                } else {
                    enterOnlineMode()
                    _authState.value = AppAuthState.NotLoggedIn
                }
            }
        }
    }

    // Manual reconnect attempt triggered by the user tapping a reconnect button.
    // Guards against concurrent attempts with the reconnectInFlight flag. Uses a
    // try/finally to ensure the flag is always cleared, even if an unexpected
    // exception escapes checkSavedSession().
    suspend fun tryReconnectInPlace() {
        if (_reconnectInFlight.value) return

        _reconnectInFlight.value = true
        _reconnectErrorMessage.value = null
        resetTransientFailureTracking()

        try {
            val savedUrl = loadSavedUrl()
            val hasAuthenticatedBefore = loadHasAuthenticatedBefore()

            if (savedUrl.isNullOrBlank()) {
                enterOnlineMode()
                _authState.value = AppAuthState.NotLoggedIn
                _reconnectErrorMessage.value = "Unable to reconnect to server."
                return
            }

            when (checkSavedSession(savedUrl)) {
                SessionCheckResult.LOGGED_IN -> {
                    // Success — restore full online session and kick a sync to flush
                    // any offline progress changes to the server.
                    enterOnlineMode()
                    _authState.value = AppAuthState.LoggedIn
                    appContext.dataStore.edit { prefs ->
                        prefs[hasAuthenticatedBeforeKey] = true
                    }
                    enqueueImmediateSync()
                }

                SessionCheckResult.NOT_LOGGED_IN -> {
                    // Server responded but auth was rejected — user must log in again.
                    enterOnlineMode()
                    _authState.value = AppAuthState.NotLoggedIn
                    _reconnectErrorMessage.value = "Unable to reconnect to server."
                }

                SessionCheckResult.UNREACHABLE -> {
                    // Still unreachable after the explicit retry.
                    _reconnectErrorMessage.value = "Unable to reconnect to server."
                    // Keep the user where they are (offline or offline-available) so
                    // they don't lose access to already-loaded content.
                    if (isOfflineMode && hasAuthenticatedBefore) {
                        _authState.value = AppAuthState.LoggedIn
                    } else if (hasAuthenticatedBefore) {
                        _authState.value = AppAuthState.OfflineAvailable(savedUrl)
                    } else {
                        enterOnlineMode()
                        _authState.value = AppAuthState.NotLoggedIn
                    }
                }
            }
        } finally {
            _reconnectInFlight.value = false
        }
    }

    suspend fun completeLogin() {
        appContext.dataStore.edit { prefs ->
            prefs[hasAuthenticatedBeforeKey] = true
        }
        enterOnlineMode()
        _authState.value = AppAuthState.LoggedIn
        clearReconnectError()
        resetTransientFailureTracking()
        enqueueImmediateSync()
    }

    suspend fun logoutAndForgetSession() {
        CastCharmApp.apiClient.clearCookies()
        enterOnlineMode()
        appContext.dataStore.edit { prefs ->
            prefs[hasAuthenticatedBeforeKey] = false
        }
        _authState.value = AppAuthState.NotLoggedIn
        clearReconnectError()
        resetTransientFailureTracking()
    }

    // Called by SessionStateInterceptor on every successful HTTP response.
    // A successful response means the server is reachable, so we reset the
    // failure counter so that the window starts fresh on the next failure run.
    fun reportServerReachable() {
        resetTransientFailureTracking()
    }

    // Called by SessionStateInterceptor when an HTTP request fails with a
    // connectivity exception (e.g., UnknownHostException, ConnectException).
    // Applies the 3-in-8s windowed debounce before emitting ServerUnreachable.
    fun reportServerUnreachable() {
        // Don't count failures when we're already offline, during a reconnect
        // attempt, or when auth state isn't LoggedIn (no point prompting offline
        // mode if the user isn't in the main app).
        if (isOfflineMode) return
        if (_authState.value != AppAuthState.LoggedIn) return
        if (_reconnectInFlight.value) return

        val now = System.currentTimeMillis()

        // Enforce the 25-second cooldown so the dialog doesn't re-appear
        // immediately after the user dismisses it.
        if (now - lastServerUnreachableEmissionAtMs < emissionCooldownMs) {
            return
        }

        // Start or extend the failure window. If the window has expired
        // (or this is the first failure) reset the counter.
        if (firstRecentFailureAtMs == 0L || now - firstRecentFailureAtMs > failureWindowMs) {
            firstRecentFailureAtMs = now
            recentFailureCount = 1
            return
        }

        recentFailureCount += 1

        // Threshold reached — emit the event and reset so the window starts fresh.
        if (recentFailureCount >= requiredRecentFailures) {
            lastServerUnreachableEmissionAtMs = now
            resetTransientFailureTracking()
            _sessionEvents.tryEmit(AppSessionEvent.ServerUnreachable)
        }
    }

    // Called by SessionStateInterceptor on 401/403 responses. Immediately drops
    // the session — cookies are still present but the server rejected them — and
    // emits AuthInvalid so the UI can show a "please log in again" snackbar.
    fun reportAuthInvalid() {
        enterOnlineMode()
        _authState.value = AppAuthState.NotLoggedIn
        _reconnectInFlight.value = false
        clearReconnectError()
        resetTransientFailureTracking()
        _sessionEvents.tryEmit(AppSessionEvent.AuthInvalid)
    }

    // Clears the failure window fields. Called after a successful request, after
    // entering offline mode, or when starting a manual reconnect attempt.
    private fun resetTransientFailureTracking() {
        firstRecentFailureAtMs = 0L
        recentFailureCount = 0
    }

    // Schedules a one-time SyncWorker to run immediately after login or reconnect.
    // This flushes any sync_pending_played/progress changes that accumulated while
    // the app was offline. ExistingWorkPolicy.REPLACE cancels any already-queued
    // immediate sync so we don't run two back-to-back syncs.
    private fun enqueueImmediateSync() {
        if (isOfflineMode) return

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "immediate_offline_sync",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    // Reads the saved server URL from DataStore. Returns null on any read failure
    // (e.g., DataStore corruption) so callers fall through to NotLoggedIn.
    private suspend fun loadSavedUrl(): String? {
        return try {
            appContext.dataStore.data.first()[serverUrlKey]
        } catch (_: Exception) {
            null
        }
    }

    // Reads the hasAuthenticatedBefore flag. Defaults to false on read failure.
    private suspend fun loadHasAuthenticatedBefore(): Boolean {
        return try {
            appContext.dataStore.data.first()[hasAuthenticatedBeforeKey] ?: false
        } catch (_: Exception) {
            false
        }
    }

    // Attempts to reach the server and check auth status. Initializing the ApiClient
    // first ensures Retrofit uses the correct base URL. Any network or parse exception
    // maps to UNREACHABLE so the caller can decide whether to offer offline mode.
    private suspend fun checkSavedSession(savedUrl: String): SessionCheckResult {
        return try {
            CastCharmApp.apiClient.initialize(savedUrl)
            val status = CastCharmApp.apiClient.getApi().getAuthStatus()
            // auth_enabled=false means the server has no password protection; the user
            // is implicitly "logged in" without credentials.
            if (!status.auth_enabled || status.logged_in) {
                SessionCheckResult.LOGGED_IN
            } else {
                SessionCheckResult.NOT_LOGGED_IN
            }
        } catch (_: Exception) {
            SessionCheckResult.UNREACHABLE
        }
    }
}