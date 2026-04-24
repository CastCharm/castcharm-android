package com.castcharm.android.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "downloads",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("episode_id")]
)
data class DownloadEntity(
    @PrimaryKey
    val episode_id: Int,
    val work_request_id: String?, // WorkManager UUID for the active/retrying phone download
    val enqueued_at: Long,
    val progress_pct: Int = 0
)