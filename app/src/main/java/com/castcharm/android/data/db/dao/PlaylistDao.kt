package com.castcharm.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.db.entities.PlaylistEntity
import com.castcharm.android.data.db.entities.PlaylistMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY is_system DESC, sort_order ASC, id ASC")
    fun getAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY is_system DESC, sort_order ASC, id ASC")
    suspend fun getAllOnce(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun get(id: Int): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getOnce(id: Int): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE is_system = 1 LIMIT 1")
    fun getSystem(): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE is_system = 1 LIMIT 1")
    suspend fun getSystemOnce(): PlaylistEntity?

    @Upsert
    suspend fun upsertAll(playlists: List<PlaylistEntity>)

    @Query("DELETE FROM playlists WHERE id NOT IN (:ids)")
    suspend fun deleteNotIn(ids: List<Int>)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("""
        SELECT e.* FROM episodes e
        JOIN playlist_members m ON m.episode_id = e.id
        WHERE m.playlist_id = :id
        ORDER BY m.position ASC
    """)
    fun memberEpisodes(id: Int): Flow<List<EpisodeEntity>>

    @Query("""
        SELECT e.* FROM episodes e
        JOIN playlist_members m ON m.episode_id = e.id
        WHERE m.playlist_id = :id
        ORDER BY m.position ASC
    """)
    suspend fun memberEpisodesOnce(id: Int): List<EpisodeEntity>

    @Query("SELECT * FROM playlist_members")
    fun allMembers(): Flow<List<PlaylistMemberEntity>>

    @Query("DELETE FROM playlist_members WHERE playlist_id = :id")
    suspend fun clearMembers(id: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(rows: List<PlaylistMemberEntity>)

    @Transaction
    suspend fun replaceMembers(id: Int, episodeIds: List<Int>) {
        clearMembers(id)
        if (episodeIds.isNotEmpty()) {
            insertMembers(episodeIds.mapIndexed { i, ep -> PlaylistMemberEntity(id, ep, i) })
        }
    }
}
