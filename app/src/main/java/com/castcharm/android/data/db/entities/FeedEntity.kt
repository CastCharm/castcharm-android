package com.castcharm.android.data.db.entities

// Local cache of a podcast feed, mirroring the server's FeedOut schema.
// FeedRepository writes to this table; Compose screens observe it via FeedDao flows.
// The three count fields (episode_count, unplayed_count, downloaded_count) are
// denormalized counts returned by the server — they are NOT computed from the
// local episodes table, so they reflect server-side state rather than local state.

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "feeds")
data class FeedEntity(
    @PrimaryKey
    val id: Int,
    val url: String,               // RSS feed URL (may change if the user edits it on the server)
    val title: String = "",
    val description: String?,
    val image_url: String?,        // Artwork URL from the RSS feed
    val custom_image_url: String?, // User-overridden artwork URL (takes priority)
    val podcast_group: String?,    // Optional group label for organising feeds
    val auto_download_new: Boolean?,  // null = use global default; true/false = per-feed override
    val active: Boolean,           // Inactive feeds are hidden from default views
    val last_synced_at: Long?,     // Unix ms of the most recent successful RSS refresh
    val last_error: String?,       // Last RSS fetch error message, if any
    // Denormalized counts from the server response, used for feed card badges.
    val episode_count: Int = 0,
    val unplayed_count: Int = 0,
    val downloaded_count: Int = 0,
    // Local-only preference — not synced to server. null means no preference set yet
    // (falls back to 1.0× at playback time). Written by PlayerViewModel on speed change.
    val playback_speed: Float? = null
)
