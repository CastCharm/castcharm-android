package com.castcharm.android.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "feeds")
data class FeedEntity(
    @PrimaryKey
    val id: Int,
    val url: String,
    val title: String = "",
    val description: String?,
    val image_url: String?,
    val custom_image_url: String?,
    val podcast_group: String?,
    val auto_download_new: Boolean?,
    val active: Boolean,
    val last_synced_at: Long?,
    val last_error: String?,
    val episode_count: Int = 0,
    val unplayed_count: Int = 0,
    val downloaded_count: Int = 0
)
