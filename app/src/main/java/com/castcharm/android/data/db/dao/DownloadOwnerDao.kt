package com.castcharm.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.castcharm.android.data.db.entities.DownloadOwnerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadOwnerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(row: DownloadOwnerEntity)

    @Query("DELETE FROM download_owners WHERE episode_id = :episodeId AND owner = :owner")
    suspend fun remove(episodeId: Int, owner: String)

    @Query("DELETE FROM download_owners WHERE episode_id = :episodeId")
    suspend fun removeAll(episodeId: Int)

    @Query("SELECT owner FROM download_owners WHERE episode_id = :episodeId")
    suspend fun ownersOf(episodeId: Int): List<String>

    @Query("SELECT episode_id FROM download_owners WHERE owner = :owner")
    suspend fun episodesOwnedBy(owner: String): List<Int>

    @Query("SELECT * FROM download_owners")
    fun all(): Flow<List<DownloadOwnerEntity>>

    @Query("SELECT * FROM download_owners")
    suspend fun allOnce(): List<DownloadOwnerEntity>
}
