package com.castcharm.android.data.db.entities

// Local cache of a podcast episode. This entity has two conceptually distinct groups
// of fields:
//
// SERVER-SIDE FIELDS — mirrored from the server's EpisodeOut schema. These are
// overwritten on every sync from the server (status, play_position_seconds,
// played, etc.) EXCEPT where mergeFromApi() explicitly preserves local values.
//
// PHONE-ONLY FIELDS — only written by local logic and never sent to the server:
//   local_path         — device file path of the downloaded audio file
//   local_size_bytes   — size of the file on disk
//   sync_pending_*     — flags marking offline changes that need to be flushed to server
//
// The indices on feed_id, guid, played, and status cover the most common filter
// and join patterns used by EpisodeDao queries.
//
// status lifecycle:
//   pending → (server queues download) → queued → downloading → downloaded
//                                               → failed / skipped

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = FeedEntity::class,
            parentColumns = ["id"],
            childColumns = ["feed_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("feed_id"),
        Index("guid"),
        Index("played"),
        Index("status")
    ]
)
data class EpisodeEntity(
    @PrimaryKey
    val id: Int,
    val feed_id: Int,
    val guid: String,           // Unique episode identifier from the RSS feed
    val title: String,
    val enclosure_url: String?, // Audio file URL from the RSS feed
    val enclosure_type: String?,
    val enclosure_length: Long?,
    val published_at: Long?,    // Unix ms publication timestamp
    val description: String?,   // May contain HTML; use stripHtml() before display
    val duration: Int?,         // Duration in seconds (from RSS or ID3 tag)
    val episode_number: Int?,
    val season_number: Int?,
    val episode_image_url: String?,  // Episode-level artwork from the RSS item
    val custom_image_url: String?,   // User override; takes priority over episode/feed art
    val author: String?,
    val link: String?,
    val hidden: Boolean = false,
    val played: Boolean = false,
    val play_position_seconds: Int = 0,  // Last known playback position
    val last_played_at: Long?,
    // Server-side download status. Values: pending, queued, downloading, downloaded, failed, skipped.
    // When local_path != null, the episode is also considered locally downloaded.
    val status: String = "pending",
    val local_path: String?,         // Absolute device file path, null if not on this device
    val local_size_bytes: Long?,
    val download_progress: Int = 0,  // 0–100 percent for in-progress downloads
    val seq_number: Int?,            // Sequential episode number within the feed (for filenames)
    val feed_image_url: String?,     // Denormalized feed-level artwork for queries that don't join feeds
    val created_at: Long,
    // Offline sync flags: set to true when the user makes a change while offline.
    // SyncWorker reads these and flushes the changes to the server, then clears them.
    val sync_pending_progress: Boolean = false,
    val sync_pending_played: Boolean = false
)
