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

data class LoginUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isLoggedIn: Boolean = false
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
        _uiState.value = _uiState.value.copy(serverUrl = url)
    }

    fun updateUsername(username: String) {
        _uiState.value = _uiState.value.copy(username = username)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    fun login() {
        val currentState = _uiState.value
        val serverUrl = currentState.serverUrl.trim()
        val username = currentState.username.trim()
        val password = currentState.password

        if (serverUrl.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Server URL is required")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                Log.d("LoginViewModel", "Attempting login to: '$serverUrl'")
                // Initialize the API client with the user-provided URL.
                // ApiClient.initialize() normalizes the URL and builds the Retrofit instance.
                CastCharmApp.apiClient.initialize(serverUrl)

                // Check auth requirements before attempting login. This handles
                // servers where auth is disabled — no credentials needed in that case.
                Log.d("LoginViewModel", "Checking auth status...")
                val status = CastCharmApp.apiClient.getApi().getAuthStatus()
                Log.d("LoginViewModel", "Auth status: enabled=${status.auth_enabled}, loggedIn=${status.logged_in}")

                // Only call login() if the server requires authentication AND we're
                // not already logged in (the cookie jar may have a valid session).
                if (status.auth_enabled && !status.logged_in) {
                    if (username.isEmpty() || password.isEmpty()) {
                        throw Exception("Username and password are required for this server")
                    }
                    // The session cookie returned here is captured by PersistentCookieJar
                    // and persisted to disk — no need to store credentials after login.
                    CastCharmApp.apiClient.getApi().login(LoginRequest(username, password))
                    Log.d("LoginViewModel", "Login successful")
                }

                // Persist the URL so it can be restored on next launch and used
                // by SyncWorker / DownloadWorker after a device restart.
                CastCharmApp.instance.dataStore.edit { prefs ->
                    prefs[stringPreferencesKey("server_url")] = serverUrl
                }

                // Setting isLoggedIn=true triggers the LaunchedEffect in LoginScreen
                // which calls onLoginSuccess() to navigate away from the login flow.
                _uiState.value = _uiState.value.copy(isLoading = false, isLoggedIn = true)
            } catch (e: Exception) {
                Log.e("LoginViewModel", "Login failed for URL: $serverUrl", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Connection failed: ${e.localizedMessage ?: "Unknown error"}"
                )
            }
        }
    }
}
