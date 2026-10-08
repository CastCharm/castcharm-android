package com.castcharm.android.data.db.dao

// DAO for the feeds table. FeedRepository is the primary writer; Compose screens
// observe feeds via the Flow-returning queries. FeedListViewModel and DashboardViewModel
// are the main readers.

import androidx.room.*
import com.castcharm.android.data.db.entities.FeedEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {
    // INSERT OR REPLACE: used for initial population where we know no episodes exist yet.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(feeds: List<FeedEntity>)

    // @Upsert for refreshes: performs an in-place UPDATE when the row already exists,
    // rather than DELETE + INSERT. Using INSERT OR REPLACE would delete the feed row
    // first, which triggers the ON DELETE CASCADE FK and wipes all episodes for that
    // feed — including their local_path values for downloaded files. @Upsert avoids
    // this destructive cascade entirely.
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

    @Query("UPDATE feeds SET playback_speed = :speed WHERE id = :feedId")
    suspend fun updatePlaybackSpeed(feedId: Int, speed: Float)

    @Query("UPDATE feeds SET play_order = :playOrder WHERE id = :feedId")
    suspend fun updatePlayOrder(feedId: Int, playOrder: String?)
}