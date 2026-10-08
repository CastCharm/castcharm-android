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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.castcharm.android.data.db.dao.DownloadDao
import com.castcharm.android.data.db.dao.EpisodeDao
import com.castcharm.android.data.db.dao.FeedDao
import com.castcharm.android.data.db.entities.DownloadEntity
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.db.entities.FeedEntity

@Database(
    entities = [FeedEntity::class, EpisodeEntity::class, DownloadEntity::class],
    version = 5
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 4 → 5: feeds.play_order ("oldest" = listen in order).
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN play_order TEXT")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            // Double-checked locking: check INSTANCE without the lock first for
            // performance, then enter the lock only when INSTANCE is null.
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "castcharm.db"
                )
                    // Known schema steps migrate in place so local_path links and
                    // unsynced offline progress survive an update. The destructive
                    // fallback only covers a jump no migration describes.
                    .addMigrations(MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
