package com.castcharm.android.data.api.models

data class AuthStatus(
    val setup_complete: Boolean,
    val auth_enabled: Boolean,
    val logged_in: Boolean
)

data class LoginRequest(
    val username: String,
    val password: String
)

data class ProgressRequest(
    val position_seconds: Int
)

data class GlobalSettingsOut(
    val auto_played_threshold: Int = 98,
    val show_suggested_listening: Boolean = true,
    val auto_download_new: Boolean = true,
    val timezone: String = "UTC"
)

data class SuggestionsOut(
    val short: List<EpisodeOut> = emptyList(),
    val medium: List<EpisodeOut> = emptyList(),
    val long: List<EpisodeOut> = emptyList(),
    val extra_long: List<EpisodeOut> = emptyList()
)

data class AppStatus(
    val podcasts_total: Int = 0,
    val feeds_total: Int = 0,
    val episodes_total: Int = 0,
    val episodes_downloaded: Int = 0,
    val storage_bytes: Long = 0,
    val download_queue_size: Int = 0,
    val active_downloads: Int = 0,
    val syncing_count: Int = 0
)
