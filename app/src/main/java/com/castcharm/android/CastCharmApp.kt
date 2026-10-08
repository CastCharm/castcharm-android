package com.castcharm.android

// CastCharmApp is the Application subclass and the global singleton container.
// It creates all process-lifetime singletons (ApiClient, PlayerController,
// AppSessionManager, ImageLoader) in onCreate() and exposes them via the companion
// object so any code in the app can reach them without passing them through
// constructor chains.
//
// The companion object also re-exposes AppSessionManager's flows and methods as
// top-level calls (e.g., CastCharmApp.authState, CastCharmApp.enterOfflineMode()).
// This is intentional: it keeps call sites concise while keeping all state in one
// place (AppSessionManager), avoiding the god-object anti-pattern.
//
// ImageLoaderFactory is implemented so Coil uses the app's custom ImageLoader
// (which routes through the authenticated OkHttpClient) for ALL image loads,
// including those triggered by Compose's AsyncImage / rememberAsyncImagePainter.

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.castcharm.android.download.LocalArtworkInterceptor
import com.castcharm.android.data.api.ApiClient
import com.castcharm.android.data.api.AuthStore
import com.castcharm.android.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

// Process-scoped DataStore accessor. The by-delegate syntax creates a single
// DataStore instance per process, backed by a file named "castcharm_prefs".
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "castcharm_prefs")

// Whether the app is currently operating in online or offline mode.
// OFFLINE means the app uses only locally cached DB data and does not attempt
// any network requests. ONLINE means normal server communication is active.
enum class AppConnectivityMode {
    ONLINE,
    OFFLINE
}

class CastCharmApp : Application(), ImageLoaderFactory {
    companion object {
        // Process-lifetime singletons. lateinit is safe here because they are all
        // initialized in onCreate() before any Activity or Service can use them.
        lateinit var instance: CastCharmApp
        lateinit var apiClient: ApiClient
        lateinit var playerController: PlayerController
        lateinit var imageLoader: ImageLoader
        lateinit var appSessionManager: AppSessionManager

        // ---- AppSessionManager delegation shims ------------------------------
        // These properties and functions forward directly to appSessionManager
        // so callers can write CastCharmApp.authState instead of
        // CastCharmApp.appSessionManager.authState.
        val connectivityMode
            get() = appSessionManager.connectivityMode

        val authState
            get() = appSessionManager.authState

        val sessionEvents
            get() = appSessionManager.sessionEvents

        val reconnectInFlight
            get() = appSessionManager.reconnectInFlight

        val reconnectErrorMessage
            get() = appSessionManager.reconnectErrorMessage

        val usingFallbackCookieAuth
            get() = appSessionManager.usingFallbackCookieAuth

        val isOfflineMode: Boolean
            get() = appSessionManager.isOfflineMode

        fun enterOfflineMode() = appSessionManager.enterOfflineMode()
        fun returnToLoginScreen() = appSessionManager.returnToLoginScreen()
        fun enterOnlineMode() = appSessionManager.enterOnlineMode()
        suspend fun refreshSessionState() = appSessionManager.refreshSessionState()
        suspend fun tryReconnectInPlace() = appSessionManager.tryReconnectInPlace()
        suspend fun completeLogin() = appSessionManager.completeLogin()
        suspend fun logoutAndForgetSession() = appSessionManager.logoutAndForgetSession()
        suspend fun retryApiKeyEnrolment() = appSessionManager.retryApiKeyEnrolment()
        fun reportServerReachable() = appSessionManager.reportServerReachable()
        fun reportServerUnreachable() = appSessionManager.reportServerUnreachable()
        fun reportAuthInvalid() = appSessionManager.reportAuthInvalid()
        fun clearReconnectError() = appSessionManager.clearReconnectError()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        apiClient = ApiClient(this)
        playerController = PlayerController(this)
        appSessionManager = AppSessionManager(this)
        // Warm the API-key cache so interceptors on background threads read a
        // plain field instead of falling back to a blocking DataStore read.
        CoroutineScope(Dispatchers.IO).launch { AuthStore.load(this@CastCharmApp) }
        // Downloads that were waiting when the process died only resume when
        // something calls kickQueue(); do it here so they don't wait for a tap.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { com.castcharm.android.download.StorageManager(this@CastCharmApp).sweepPartFiles() }
            runCatching { com.castcharm.android.download.DownloadScheduler(this@CastCharmApp).kickQueue() }
        }
        // Build the Coil ImageLoader with an AuthAwareCallFactory so all image
        // requests (artwork, feed covers) go through the authenticated OkHttpClient.
        // crossfade(200) applies a short fade-in transition when images load.
        imageLoader = ImageLoader.Builder(this)
            .crossfade(200)
            .callFactory(AuthAwareCallFactory(apiClient))
            // Prefers the copy stored beside downloaded episodes, so artwork is
            // there with no server and no network — which is the state the phone
            // is in whenever those downloads are the reason it is being used.
            .components { add(LocalArtworkInterceptor(this@CastCharmApp)) }
            .build()
        // Register notification channels once at process start. Cheap no-op on
        // subsequent boots because the system tracks channel identity.
        com.castcharm.android.notifications.NewEpisodesNotifier.ensureChannel(this)
    }

    override fun onTerminate() {
        // Release the MediaController connection to PlayerService so the service
        // knows the client is gone and can clean up its own state.
        playerController.release()
        super.onTerminate()
    }

    // Coil calls newImageLoader() once to obtain the app-wide ImageLoader.
    // Returning the same instance we built in onCreate() ensures all image loads
    // share the same cache and the same authenticated call factory.
    override fun newImageLoader(): ImageLoader = imageLoader
}

// OkHttp Call.Factory that routes all Coil image requests through the authenticated
// ApiClient's OkHttpClient (which carries the session cookie). Falls back to a plain
// OkHttpClient if the ApiClient has not yet been initialized (e.g., before login).
private class AuthAwareCallFactory(private val apiClient: ApiClient) : Call.Factory {
    private val fallback by lazy { OkHttpClient() }

    override fun newCall(request: Request): Call =
        (apiClient.getHttpClient() ?: fallback).newCall(request)
}