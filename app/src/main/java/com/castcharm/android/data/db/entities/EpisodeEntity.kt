package com.castcharm.android.data.db.entities

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
    val guid: String,
    val title: String,
    val enclosure_url: String?,
    val enclosure_type: String?,
    val enclosure_length: Long?,
    val published_at: Long?,
    val description: String?,
    val duration: Int?,
    val episode_number: Int?,
    val season_number: Int?,
    val episode_image_url: String?,
    val custom_image_url: String?,
    val author: String?,
    val link: String?,
    val hidden: Boolean = false,
    val played: Boolean = false,
    val play_position_seconds: Int = 0,
    val last_played_at: Long?,
    val status: String = "pending", // pending, queued, downloading, downloaded, failed, skipped
    val local_path: String?, // file path on device if downloaded
    val local_size_bytes: Long?,
    val download_progress: Int = 0,
    val seq_number: Int?,
    val feed_image_url: String?,
    val created_at: Long,
    val sync_pending_progress: Boolean = false,
    val sync_pending_played: Boolean = false
)
