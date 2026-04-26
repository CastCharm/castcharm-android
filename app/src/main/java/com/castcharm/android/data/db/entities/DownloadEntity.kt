package com.castcharm.android.data.db.entities

// Tracks in-progress and queued phone-side downloads (audio files being saved to
// this device). One row per episode that is queued or actively downloading.
//
// The row lifecycle:
//   INSERT (work_request_id=null) — episode is queued, no WorkManager job yet
//   UPDATE work_request_id        — DownloadScheduler assigned a WorkManager job
//   DELETE                        — DownloadWorker completed (success) or
//                                   DownloadScheduler cleaned up (cancelled/failed)
//
// The ON DELETE CASCADE FK means that when an EpisodeEntity row is deleted (e.g.,
// when a feed is removed), its DownloadEntity row is automatically cleaned up.
// DownloadScheduler must also cancel the corresponding WorkManager job separately.
//
// work_request_id=null signals "queued but not yet dispatched to WorkManager".
// DownloadDao.getQueuedDownloads() returns exactly these rows so kickQueue() can
// fill available concurrency slots without issuing duplicate WorkManager requests.

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
    // WorkManager UUID string for the active DownloadWorker. Null until
    // DownloadScheduler.kickQueue() assigns a WorkManager work request.
    val work_request_id: String?,
    val enqueued_at: Long,
    val progress_pct: Int = 0  // 0–100 as reported by DownloadWorker progress updates
)