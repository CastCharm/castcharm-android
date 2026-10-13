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
import com.castcharm.android.data.db.dao.DownloadOwnerDao
import com.castcharm.android.data.db.entities.DownloadOwnerEntity
import com.castcharm.android.data.db.dao.PlaylistDao
import com.castcharm.android.data.db.entities.PlaylistMemberEntity
import com.castcharm.android.data.db.entities.PlaylistEntity
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
    entities = [FeedEntity::class, EpisodeEntity::class, DownloadEntity::class, PlaylistEntity::class, PlaylistMemberEntity::class, DownloadOwnerEntity::class],
    version = 8
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun downloadDao(): DownloadDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadOwnerDao(): DownloadOwnerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 4 → 5: feeds.play_order ("oldest" = listen in order).
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN play_order TEXT")
            }
        }

        // 5 → 6: feeds.next_up_* (the server's resume point, cached for offline).
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN next_up_episode_id INTEGER")
                db.execSQL("ALTER TABLE feeds ADD COLUMN next_up_position INTEGER")
            }
        }

        // 6 → 7: local copies of playlists and their members (offline playlists,
        // the Get Caught Up card, the Downloads tab's playlist section).
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `playlists` (
                    `id` INTEGER NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `type` TEXT NOT NULL,
                    `rules_json` TEXT, `is_system` INTEGER NOT NULL, `total_seconds` INTEGER NOT NULL,
                    `sort_order` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `playlist_members` (
                    `playlist_id` INTEGER NOT NULL, `episode_id` INTEGER NOT NULL, `position` INTEGER NOT NULL,
                    PRIMARY KEY(`playlist_id`, `episode_id`),
                    FOREIGN KEY(`playlist_id`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_members_episode_id` ON `playlist_members` (`episode_id`)")
            }
        }

        // 7 → 8: why each file is on the phone (download_owners). Files already
        // here are credited to their podcast, which is how they got here.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `download_owners` (
                    `episode_id` INTEGER NOT NULL, `owner` TEXT NOT NULL, PRIMARY KEY(`episode_id`, `owner`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_owners_owner` ON `download_owners` (`owner`)")
                db.execSQL("INSERT OR IGNORE INTO download_owners (episode_id, owner) SELECT id, 'feed' FROM episodes WHERE local_path IS NOT NULL")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            // Double-checked locking. The second check, inside the lock, is the
            // half that actually makes this safe and was previously missing: two
            // threads could both see a null INSTANCE, both enter the lock in turn,
            // and both build a database, with the second silently replacing the
            // first. Room's InvalidationTracker is per-instance, so every Flow
            // already collecting from the discarded instance would stop receiving
            // change notifications — screens would quietly stop updating until the
            // process restarted. Reachable on a cold start, where a ContentProvider
            // (created before Application.onCreate), a WorkManager worker and
            // PlayerService can all reach this on different threads at once.
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "castcharm.db"
                )
                    // Known schema steps migrate in place so local_path links and
                    // unsynced offline progress survive an update. The destructive
                    // fallback only covers a jump no migration describes.
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
