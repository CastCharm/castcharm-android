package com.castcharm.android

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.castcharm.android.data.api.ApiClient
import com.castcharm.android.player.PlayerController
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "castcharm_prefs")

enum class AppConnectivityMode {
    ONLINE,
    OFFLINE
}

class CastCharmApp : Application(), ImageLoaderFactory {
    companion object {
        lateinit var instance: CastCharmApp
        lateinit var apiClient: ApiClient
        lateinit var playerController: PlayerController
        lateinit var imageLoader: ImageLoader
        lateinit var appSessionManager: AppSessionManager


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

        val isOfflineMode: Boolean
            get() = appSessionManager.isOfflineMode

        fun enterOfflineMode() = appSessionManager.enterOfflineMode()
        fun enterOnlineMode() = appSessionManager.enterOnlineMode()
        suspend fun refreshSessionState() = appSessionManager.refreshSessionState()
        suspend fun tryReconnectInPlace() = appSessionManager.tryReconnectInPlace()
        suspend fun completeLogin() = appSessionManager.completeLogin()
        suspend fun logoutAndForgetSession() = appSessionManager.logoutAndForgetSession()
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
        imageLoader = ImageLoader.Builder(this)
            .crossfade(200)
            .callFactory(AuthAwareCallFactory(apiClient))
            .build()
    }

    override fun onTerminate() {
        playerController.release()
        super.onTerminate()
    }

    override fun newImageLoader(): ImageLoader = imageLoader
}

private class AuthAwareCallFactory(private val apiClient: ApiClient) : Call.Factory {
    private val fallback by lazy { OkHttpClient() }

    override fun newCall(request: Request): Call =
        (apiClient.getHttpClient() ?: fallback).newCall(request)
}