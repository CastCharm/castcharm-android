package com.castcharm.android.data.db.entities

// Why a file is on this phone. One row per (episode, owner); a file with two
// or more owners is "shared". Owners: "feed" (downloaded under its podcast),
// "playlist:<id>" (Download all of that playlist), "catchup" (the automatic
// Get Caught Up download, or Download all on that playlist by hand).
// Deleting from one place removes that owner; the file goes when none remain.

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "download_owners",
    primaryKeys = ["episode_id", "owner"],
    indices = [Index("owner")]
)
data class DownloadOwnerEntity(
    val episode_id: Int,
    val owner: String,
)
