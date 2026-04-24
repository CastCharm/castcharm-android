package com.castcharm.android.data.db.dao

import androidx.room.*
import com.castcharm.android.data.db.entities.FeedEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(feeds: List<FeedEntity>)

    // Use upsert for refreshes — INSERT OR REPLACE deletes the row first, triggering
    // ON DELETE CASCADE on episodes and wiping local_path for all downloaded files.
    // @Upsert performs an in-place update that does not delete the existing row.
    @Upsert
    suspend fun upsertAll(feeds: List<FeedEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(feed: FeedEntity)

    @Update
    suspend fun update(feed: FeedEntity)

    @Delete
    suspend fun delete(feed: FeedEntity)

    @Query("DELETE FROM feeds WHERE id = :feedId")
    suspend fun deleteById(feedId: Int)

    @Query("DELETE FROM feeds WHERE id NOT IN (:ids)")
    suspend fun deleteFeedsNotIn(ids: List<Int>)

    @Query("SELECT id FROM feeds")
    suspend fun getAllFeedIds(): List<Int>

    @Query("SELECT * FROM feeds ORDER BY title ASC")
    fun getAllFeeds(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds ORDER BY title ASC")
    suspend fun getFeedOnceAll(): List<FeedEntity>

    @Query("SELECT COUNT(*) FROM feeds")
    suspend fun getFeedTableCount(): Int

    @Query("SELECT * FROM feeds WHERE id = :feedId")
    fun getFeed(feedId: Int): Flow<FeedEntity>

    @Query("SELECT * FROM feeds WHERE id = :feedId")
    suspend fun getFeedOnce(feedId: Int): FeedEntity?

    @Query("SELECT * FROM feeds WHERE active = 1 ORDER BY title ASC")
    fun getActiveFeeds(): Flow<List<FeedEntity>>

    @Query("DELETE FROM feeds")
    suspend fun deleteAll()
}