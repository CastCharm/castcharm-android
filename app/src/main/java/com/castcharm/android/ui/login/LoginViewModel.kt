package com.castcharm.android.ui.login

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
                CastCharmApp.apiClient.initialize(serverUrl)
                
                // First check if auth is even required
                Log.d("LoginViewModel", "Checking auth status...")
                val status = CastCharmApp.apiClient.getApi().getAuthStatus()
                Log.d("LoginViewModel", "Auth status: enabled=${status.auth_enabled}, loggedIn=${status.logged_in}")
                
                if (status.auth_enabled && !status.logged_in) {
                    if (username.isEmpty() || password.isEmpty()) {
                        throw Exception("Username and password are required for this server")
                    }
                    CastCharmApp.apiClient.getApi().login(LoginRequest(username, password))
                    Log.d("LoginViewModel", "Login successful")
                }

                // Save URL to dataStore so we remember it next time
                CastCharmApp.instance.dataStore.edit { prefs ->
                    prefs[stringPreferencesKey("server_url")] = serverUrl
                }

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
