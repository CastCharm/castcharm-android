package com.castcharm.android.ui.login

// LoginViewModel handles server URL configuration and authentication.
// It's responsible for:
//   1. Restoring the previously saved server URL from DataStore on init
//   2. Initializing ApiClient with the entered URL
//   3. Calling getAuthStatus() to check if a login is actually required
//      (auth_enabled=false means the server has no password — skip login)
//   4. Calling login() only if auth_enabled=true and we're not yet logged in
//   5. Persisting the server URL to DataStore after a successful connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.models.LoginRequest
import com.castcharm.android.ensureApiKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.castcharm.android.dataStore
import android.util.Log
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

// The login screen runs in one of three UI phases:
//   URL_ONLY      — only Server URL + Connect visible. We haven't yet probed
//                   the server so we don't know if a password is required.
//   NEEDS_CREDS   — server confirmed auth_enabled=true and no valid session;
//                   reveal username + password + Login button.
//   NO_PASSWORD   — server confirmed auth_enabled=false; auto-completes on
//                   the same tap so the user never types unused credentials.
// The two-step split keeps users on a no-password server from filling out
// fields they don't need (and being unsure whether to fill them in).
enum class LoginPhase { URL_ONLY, NEEDS_CREDS, NO_PASSWORD }

data class LoginUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isLoggedIn: Boolean = false,
    val phase: LoginPhase = LoginPhase.URL_ONLY
)

class LoginViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // Pre-populate the server URL field with the previously saved value
        // so returning users don't have to type it again.
        viewModelScope.launch {
            val serverUrlKey = stringPreferencesKey("server_url")
            val savedUrl = CastCharmApp.instance.dataStore.data.map { it[serverUrlKey] }.firstOrNull()
            if (savedUrl != null) {
                _uiState.value = _uiState.value.copy(serverUrl = savedUrl)
            }
        }
    }

    fun updateServerUrl(url: String) {
        // Editing the URL resets the phase — we can't trust the previous probe
        // if the user is pointing at a different server now.
        _uiState.value = _uiState.value.copy(
            serverUrl = url,
            phase = LoginPhase.URL_ONLY,
            errorMessage = null,
        )
    }

    fun updateUsername(username: String) {
        _uiState.value = _uiState.value.copy(username = username)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    /**
     * Step 1: probe the server to see whether a password is required.
     *
     * On auth_enabled=false we immediately complete the login flow (persist
     * URL + enrol key) rather than making the user tap again — there is
     * nothing more for them to enter.
     */
    fun connect() {
        val serverUrl = _uiState.value.serverUrl.trim()
        if (serverUrl.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Server URL is required")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                CastCharmApp.apiClient.initialize(serverUrl)
                val status = CastCharmApp.apiClient.getApi().getAuthStatus()
                Log.d(
                    "LoginViewModel",
                    "Probe: enabled=${status.auth_enabled}, loggedIn=${status.logged_in}",
                )
                if (!status.auth_enabled) {
                    // No password on this server — go straight through.
                    _uiState.value = _uiState.value.copy(phase = LoginPhase.NO_PASSWORD)
                    finishLogin(serverUrl, skipCredentialsCall = true)
                } else if (status.logged_in) {
                    // A pre-existing cookie session is still valid (e.g. reinstall
                    // preserved cookies via backup — rare given the new backup
                    // rules, but possible). Complete the flow without asking again.
                    finishLogin(serverUrl, skipCredentialsCall = true)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        phase = LoginPhase.NEEDS_CREDS,
                    )
                }
            } catch (e: Exception) {
                Log.e("LoginViewModel", "Probe failed for $serverUrl", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Connection failed: ${e.localizedMessage ?: "Unknown error"}",
                )
            }
        }
    }

    /**
     * Step 2: submit the username/password once the phase is NEEDS_CREDS.
     * This should not be called from URL_ONLY — the button that invokes it is
     * only rendered when credentials are visible.
     */
    fun login() {
        val currentState = _uiState.value
        val serverUrl = currentState.serverUrl.trim()
        val username = currentState.username.trim()
        val password = currentState.password

        if (serverUrl.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Server URL is required")
            return
        }
        if (username.isEmpty() || password.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Username and password are required for this server",
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                // The session cookie returned here is captured by PersistentCookieJar
                // and persisted to disk — no need to store credentials after login.
                CastCharmApp.apiClient.getApi().login(LoginRequest(username, password))
                Log.d("LoginViewModel", "Login successful")
                finishLogin(serverUrl, skipCredentialsCall = false)
            } catch (e: Exception) {
                Log.e("LoginViewModel", "Login failed for URL: $serverUrl", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Connection failed: ${e.localizedMessage ?: "Unknown error"}",
                )
            }
        }
    }

    /**
     * Shared tail of both flows: persist the URL, enrol an API key, mark the
     * UI as done. skipCredentialsCall exists only so the log line above is
     * accurate — the actual credentials call has already happened by the time
     * we get here in the credentials flow.
     */
    private suspend fun finishLogin(serverUrl: String, skipCredentialsCall: Boolean) {
        // Persist the URL so it can be restored on next launch and used
        // by SyncWorker / DownloadWorker after a device restart.
        CastCharmApp.instance.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("server_url")] = serverUrl
        }

        // Trade the fresh session for a permanent API key so this device
        // never gets logged out by a cookie quietly reaching its expiry.
        // Best-effort — on an older server this is a no-op and the app
        // simply carries on using the cookie.
        ensureApiKey(CastCharmApp.instance, force = true)

        _uiState.value = _uiState.value.copy(isLoading = false, isLoggedIn = true)
        // skipCredentialsCall is unused at runtime; kept as a parameter so future
        // callers can be explicit about which path they came from.
        @Suppress("UNUSED_PARAMETER")
        skipCredentialsCall
    }
}
