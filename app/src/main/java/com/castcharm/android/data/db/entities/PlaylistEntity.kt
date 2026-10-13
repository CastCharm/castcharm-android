package com.castcharm.android.data.db.entities

// Local copy of a server playlist, so the Playlists tab, the Home card, the
// Downloads tab and Android Auto all work from the last sync — offline too.
// Smart rules are kept as the server's JSON (see SmartRules); membership is
// in PlaylistMemberEntity.

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val description: String?,
    val type: String,                 // "custom" | "smart"
    val rules_json: String?,
    val is_system: Boolean = false,
    val total_seconds: Int = 0,
    val sort_order: Int = 0,          // position in the server's listing
    val updated_at: Long = 0L,        // last time members were refreshed (ms)
) {
    val isSmart: Boolean get() = type == "smart"
}

// The server's current member list for a playlist (manual: hand-picked order;
// smart: the last evaluation). For smart lists the phone re-filters these
// against the live episode rows, so a played episode leaves at once.
@Entity(
    tableName = "playlist_members",
    primaryKeys = ["playlist_id", "episode_id"],
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"],
        childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("episode_id")]
)
data class PlaylistMemberEntity(
    val playlist_id: Int,
    val episode_id: Int,
    val position: Int,
)
