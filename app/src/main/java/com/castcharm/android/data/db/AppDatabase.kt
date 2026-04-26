package com.castcharm.android.data.db

// Room database definition for the local cache. The database acts purely as a
// cache of server data plus local-only fields (local_path, sync_pending_*,
// WorkManager IDs). It is intentionally NOT the source of truth — the server is.
// This means fallbackToDestructiveMigration() is acceptable: if the schema changes
// in an incompatible way, the local cache is wiped and rebuilt from the server on
// the next sync.
//
// The singleton pattern uses @Volatile + synchronized(this) to ensure only one
// AppDatabase instance is created across all threads, even if two threads race
// to call getDatabase() simultaneously.

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.castcharm.android.data.db.dao.DownloadDao
import com.castcharm.android.data.db.dao.EpisodeDao
import com.castcharm.android.data.db.dao.FeedDao
import com.castcharm.android.data.db.entities.DownloadEntity
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.db.entities.FeedEntity

@Database(
    entities = [FeedEntity::class, EpisodeEntity::class, DownloadEntity::class],
    version = 4
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            // Double-checked locking: check INSTANCE without the lock first for
            // performance, then enter the lock only when INSTANCE is null.
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "castcharm.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
