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

// POST /api/auth/exchange-key body. Trades the current login session for a
// long-lived API key. name is what shows up in the server's client list, so it
// should identify the device (we use the hardware model).
data class ExchangeKeyRequest(
    val name: String
)

// POST /api/auth/exchange-key response. key is the plaintext credential and is
// returned exactly once — the server stores only a hash of it, so it cannot be
// read back later.
//
// We keep id, name, and key_prefix around because they let the "This device"
// panel in Settings identify which key belongs to this device (no need to hit
// the full list) and rename it via PATCH /api/settings/api-keys/{id} without
// having to look it up.
// One row of GET /api/settings/api-keys.
data class ApiKeyInfo(
    val id: Int,
    val name: String,
    val key_prefix: String,
)

data class ApiKeyCreated(
    val id: Int,
    val name: String,
    val key_prefix: String,
    val key: String
)

// PATCH /api/settings/api-keys/{id} body. Just the new name — the server
// preserves everything else about the key.
data class ApiKeyRenameRequest(
    val name: String
)

// POST /api/episodes/{id}/progress body. Sent every 10 seconds while playing
// and on pause/stop. position_seconds is the current player position.
//
// Build these with [progressRequest], not directly — see below.
data class ProgressRequest(
    val position_seconds: Int
)

// Range the server accepts (ProgressBody in app/schemas.py). The upper bound is
// roughly 11.5 days, well past any real episode.
private const val MIN_PROGRESS_SECONDS = 0
private const val MAX_PROGRESS_SECONDS = 1_000_000

/**
 * A playback position, forced into the range the server accepts.
 *
 * Clamp at the point the value is produced, not just before it is sent. The
 * player's reported position is not guaranteed non-negative — PlayerController
 * already guards its own read with coerceAtLeast(0) — and dividing a Long
 * milliseconds value before narrowing to Int can wrap. Clamping only the request
 * leaves the unclamped number to be written to the database, where the local row
 * and the server then disagree and nothing ever heals the local copy: SyncWorker
 * would send 0 forever while the row stayed negative.
 *
 * Takes Long so the clamp happens before the narrowing that could wrap.
 */
fun clampProgressSeconds(seconds: Long): Int =
    seconds.coerceIn(MIN_PROGRESS_SECONDS.toLong(), MAX_PROGRESS_SECONDS.toLong()).toInt()

fun clampProgressSeconds(seconds: Int): Int = clampProgressSeconds(seconds.toLong())

/**
 * A progress update for the server. The value is clamped here too, so a caller
 * that skipped [clampProgressSeconds] still cannot send something that would be
 * rejected outright — a 422 does not merely drop one reading, it fails the sync
 * carrying it and keeps failing on every retry.
 */
fun progressRequest(positionSeconds: Int): ProgressRequest =
    ProgressRequest(clampProgressSeconds(positionSeconds))

// POST /api/feeds body. url is the only required field; the server resolves
// redirects and detects RSS from podcast page URLs automatically.
data class AddFeedRequest(
    val url: String,
    val download_all: Boolean = false,
    // Folder name override. The podcast keeps its real title from the RSS feed;
    // this only changes the directory the server files it under.
    val title_override: String? = null,
    // Only ever true once the user has been shown the folder-already-exists prompt
    // and has chosen to go ahead with it.
    val allow_existing_folder: Boolean = false
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
