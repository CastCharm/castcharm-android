package com.castcharm.android.data.api.models

// AuthStatus is the response from GET /api/auth/status, called on startup and
// reconnect. The three flags drive the auth state machine in AppSessionManager:
//   - setup_complete=false → server is fresh, show setup flow (not implemented on Android)
//   - auth_enabled=false → server has no password, skip login screen entirely
//   - logged_in=false (with auth_enabled=true) → show LoginScreen
data class AuthStatus(
    val setup_complete: Boolean,
    val auth_enabled: Boolean,
    val logged_in: Boolean
)

// POST /api/auth/login body. The session cookie returned by the server is
// captured automatically by PersistentCookieJar and persisted to disk.
data class LoginRequest(
    val username: String,
    val password: String
)

// POST /api/episodes/{id}/progress body. Sent every 10 seconds while playing
// and on pause/stop. position_seconds is the current player position.
data class ProgressRequest(
    val position_seconds: Int
)

// POST /api/feeds body. url is the only required field; the server resolves
// redirects and detects RSS from podcast page URLs automatically.
data class AddFeedRequest(
    val url: String,
    val download_all: Boolean = false
)

// GET /api/settings response. Only the fields the Android app uses are mapped;
// remaining server fields are ignored by Moshi (lenient parsing).
data class GlobalSettingsOut(
    val auto_played_threshold: Int = 98,
    val show_suggested_listening: Boolean = true,
    val auto_download_new: Boolean = true,
    val timezone: String = "UTC"
)

// GET /api/episodes/suggestions response. Episodes are pre-bucketed by duration
// on the server. The DashboardScreen displays each bucket separately.
data class SuggestionsOut(
    val short: List<EpisodeOut> = emptyList(),
    val medium: List<EpisodeOut> = emptyList(),
    val long: List<EpisodeOut> = emptyList(),
    val extra_long: List<EpisodeOut> = emptyList()
)

// GET /api/stats response. Used by the Stats section in SettingsScreen.
// All counts are server-side totals, not local DB counts.
data class AppStatus(
    val podcasts_total: Int = 0,
    val feeds_total: Int = 0,
    val episodes_total: Int = 0,
    val episodes_downloaded: Int = 0,
    val storage_bytes: Long = 0,
    val download_queue_size: Int = 0,
    val active_downloads: Int = 0,
    val syncing_count: Int = 0,
    val syncing_feed_ids: List<Int> = emptyList(),
    val version: String = ""
)
