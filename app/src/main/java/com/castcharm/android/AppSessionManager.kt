package com.castcharm.android

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

private enum class SessionCheckResult {
    LOGGED_IN,
    NOT_LOGGED_IN,
    UNREACHABLE
}

sealed class AppSessionEvent {
    data object ServerUnreachable : AppSessionEvent()
    data object AuthInvalid : AppSessionEvent()
}

sealed class AppAuthState {
    data object Checking : AppAuthState()
    data object NotLoggedIn : AppAuthState()
    data object LoggedIn : AppAuthState()
    data class OfflineAvailable(val serverUrl: String) : AppAuthState()
}

class AppSessionManager(
    private val appContext: Context
) {
    private val hasAuthenticatedBeforeKey = booleanPreferencesKey("has_authenticated_before")
    private val serverUrlKey = stringPreferencesKey("server_url")

    private val _connectivityMode = MutableStateFlow(AppConnectivityMode.ONLINE)
    val connectivityMode: StateFlow<AppConnectivityMode> = _connectivityMode.asStateFlow()

    private val _authState = MutableStateFlow<AppAuthState>(AppAuthState.Checking)
    val authState: StateFlow<AppAuthState> = _authState.asStateFlow()

    private val _sessionEvents = MutableSharedFlow<AppSessionEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sessionEvents: SharedFlow<AppSessionEvent> = _sessionEvents.asSharedFlow()

    private val _reconnectInFlight = MutableStateFlow(false)
    val reconnectInFlight: StateFlow<Boolean> = _reconnectInFlight.asStateFlow()

    private val _reconnectErrorMessage = MutableStateFlow<String?>(null)
    val reconnectErrorMessage: StateFlow<String?> = _reconnectErrorMessage.asStateFlow()

    @Volatile
    private var firstRecentFailureAtMs: Long = 0L

    @Volatile
    private var recentFailureCount: Int = 0

    @Volatile
    private var lastServerUnreachableEmissionAtMs: Long = 0L

    private val failureWindowMs = 8_000L
    private val emissionCooldownMs = 25_000L
    private val requiredRecentFailures = 3

    val isOfflineMode: Boolean
        get() = _connectivityMode.value == AppConnectivityMode.OFFLINE

    fun clearReconnectError() {
        _reconnectErrorMessage.value = null
    }

    fun enterOfflineMode() {
        _connectivityMode.value = AppConnectivityMode.OFFLINE
        clearReconnectError()
        resetTransientFailureTracking()

        if (_authState.value is AppAuthState.OfflineAvailable) {
            _authState.value = AppAuthState.LoggedIn
        }
    }

    fun enterOnlineMode() {
        _connectivityMode.value = AppConnectivityMode.ONLINE

        if (_authState.value is AppAuthState.OfflineAvailable) {
            _authState.value = AppAuthState.LoggedIn
        }
    }

    suspend fun refreshSessionState() {
        _authState.value = AppAuthState.Checking
        clearReconnectError()
        resetTransientFailureTracking()

        val savedUrl = loadSavedUrl()
        val hasAuthenticatedBefore = loadHasAuthenticatedBefore()

        if (savedUrl.isNullOrBlank()) {
            enterOnlineMode()
            _authState.value = AppAuthState.NotLoggedIn
            return
        }

        when (checkSavedSession(savedUrl)) {
            SessionCheckResult.LOGGED_IN -> {
                enterOnlineMode()
                _authState.value = AppAuthState.LoggedIn
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
                if (hasAuthenticatedBefore) {
                    _authState.value = AppAuthState.OfflineAvailable(savedUrl)
                } else {
                    enterOnlineMode()
                    _authState.value = AppAuthState.NotLoggedIn
                }
            }
        }
    }

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
                    enterOnlineMode()
                    _authState.value = AppAuthState.LoggedIn
                    appContext.dataStore.edit { prefs ->
                        prefs[hasAuthenticatedBeforeKey] = true
                    }
                    enqueueImmediateSync()
                }

                SessionCheckResult.NOT_LOGGED_IN -> {
                    enterOnlineMode()
                    _authState.value = AppAuthState.NotLoggedIn
                    _reconnectErrorMessage.value = "Unable to reconnect to server."
                }

                SessionCheckResult.UNREACHABLE -> {
                    _reconnectErrorMessage.value = "Unable to reconnect to server."
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

    fun reportServerReachable() {
        resetTransientFailureTracking()
    }

    fun reportServerUnreachable() {
        if (isOfflineMode) return
        if (_authState.value != AppAuthState.LoggedIn) return
        if (_reconnectInFlight.value) return

        val now = System.currentTimeMillis()

        if (now - lastServerUnreachableEmissionAtMs < emissionCooldownMs) {
            return
        }

        if (firstRecentFailureAtMs == 0L || now - firstRecentFailureAtMs > failureWindowMs) {
            firstRecentFailureAtMs = now
            recentFailureCount = 1
            return
        }

        recentFailureCount += 1

        if (recentFailureCount >= requiredRecentFailures) {
            lastServerUnreachableEmissionAtMs = now
            resetTransientFailureTracking()
            _sessionEvents.tryEmit(AppSessionEvent.ServerUnreachable)
        }
    }

    fun reportAuthInvalid() {
        enterOnlineMode()
        _authState.value = AppAuthState.NotLoggedIn
        _reconnectInFlight.value = false
        clearReconnectError()
        resetTransientFailureTracking()
        _sessionEvents.tryEmit(AppSessionEvent.AuthInvalid)
    }

    private fun resetTransientFailureTracking() {
        firstRecentFailureAtMs = 0L
        recentFailureCount = 0
    }

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

    private suspend fun loadSavedUrl(): String? {
        return try {
            appContext.dataStore.data.first()[serverUrlKey]
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadHasAuthenticatedBefore(): Boolean {
        return try {
            appContext.dataStore.data.first()[hasAuthenticatedBeforeKey] ?: false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun checkSavedSession(savedUrl: String): SessionCheckResult {
        return try {
            CastCharmApp.apiClient.initialize(savedUrl)
            val status = CastCharmApp.apiClient.getApi().getAuthStatus()
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