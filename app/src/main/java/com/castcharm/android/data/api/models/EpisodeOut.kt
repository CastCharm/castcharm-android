package com.castcharm.android.data.api.models

// EpisodeOut is the Moshi-deserialized response body from the episode list and
// individual episode endpoints. Field names match the server's JSON keys exactly
// so no @Json annotations are needed.
//
// Nullable fields reflect optional RSS data (not all podcasts provide every field).
// Server-side status values: "pending" | "queued" | "downloading" | "downloaded" | "failed" | "skipped"
// file_path and file_size refer to SERVER-side storage, not the Android device.
// seq_number is the episode's position within the feed (recalculated on the server).
data class EpisodeOut(
    val id: Int,
    val feed_id: Int,
    val guid: String,
    val title: String?,
    val enclosure_url: String?,
    val enclosure_type: String?,
    val enclosure_length: Long?,
    val published_at: String?,
    val description: String?,
    val duration: String?,
    val episode_number: Int?,
    val season_number: Int?,
    val episode_image_url: String?,
    val custom_image_url: String?,
    val author: String?,
    val link: String?,
    val hidden: Boolean = false,
    val played: Boolean = false,
    val play_position_seconds: Int = 0,
    val last_played_at: String?,
    val status: String = "pending",
    val file_path: String?,
    val file_size: Long?,
    val download_progress: Int = 0,
    val seq_number: Int?,
    val created_at: String,
    val feed_image_url: String?,
    val feed_title: String?,
)
