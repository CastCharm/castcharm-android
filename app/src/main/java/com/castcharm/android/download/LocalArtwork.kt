package com.castcharm.android.download

// Durable cover art stored alongside downloaded episodes — one image per feed,
// plus the episode's own image where a podcast publishes distinct art per
// episode.
//
// Artwork used to live only in caches: Coil's disk cache for the phone UI and
// PodcastArtworkProvider's file cache for Android Auto, both under cacheDir,
// which Android reclaims whenever it wants space. Download a season for a
// flight, have the system clear cacheDir, and every episode sitting on the
// device shows a placeholder. Nothing on the phone had a durable copy: the
// download path fetched audio and nothing else.
//
// These files go in getExternalFilesDir, next to the audio they belong to, so
// they survive cache eviction, are available with no network at all, and are
// removed on uninstall.
//
// Deliberately not a DB column: AppDatabase is built with
// fallbackToDestructiveMigration(), so bumping the schema to record a path would
// wipe every local download, play position and pending sync. Paths are derived
// from the feed or episode id instead, which needs no migration.

import android.content.Context
import android.util.Log
import com.castcharm.android.CastCharmApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File

object LocalArtwork {
    private const val TAG = "LocalArtwork"
    private const val DIR_NAME = "artwork"

    private fun dir(context: Context): File =
        File(context.getExternalFilesDir(null), DIR_NAME)

    /**
     * Where this feed's cover lives. The file may not exist.
     *
     * Named by feed id alone, which the server recycles — see
     * PodcastArtworkProvider.evictFeedArtwork, which deletes this file for
     * exactly that reason and is already called when a recycled id is spotted.
     */
    fun feedFile(context: Context, feedId: Int): File =
        File(dir(context), "feed_$feedId.jpg")

    /** Where this episode's own cover lives. The file may not exist. */
    fun episodeFile(context: Context, episodeId: Int): File =
        File(dir(context), "episode_$episodeId.jpg")

    fun feedExists(context: Context, feedId: Int): Boolean = nonEmpty(feedFile(context, feedId))

    fun episodeExists(context: Context, episodeId: Int): Boolean =
        nonEmpty(episodeFile(context, episodeId))

    private fun nonEmpty(file: File): Boolean =
        runCatching { file.length() > 0 }.getOrDefault(false)

    /**
     * Fetches a feed's cover if it is not already on disk.
     *
     * Called when an episode download completes: the point of the file is that
     * the episode can be looked at offline, so it is acquired at the moment the
     * phone commits to keeping it.
     */
    suspend fun ensureFeed(context: Context, feedId: Int): Boolean =
        ensure(feedFile(context, feedId)) {
            CastCharmApp.apiClient.getApi().getFeedCoverImage(feedId)
        }

    /**
     * Fetches an episode's own cover if it is not already on disk.
     *
     * Most episodes have none — the server answers 404 and the row falls back to
     * the feed cover, which is the same thing the UI does online.
     */
    suspend fun ensureEpisode(context: Context, episodeId: Int): Boolean =
        ensure(episodeFile(context, episodeId)) {
            CastCharmApp.apiClient.getApi().getEpisodeCoverImage(episodeId)
        }

    /** Best-effort throughout: artwork must never be able to fail a download. */
    private suspend fun ensure(target: File, body: suspend () -> ResponseBody): Boolean =
        withContext(Dispatchers.IO) {
            if (nonEmpty(target)) return@withContext true
            if (!CastCharmApp.apiClient.isInitialized || CastCharmApp.isOfflineMode) {
                return@withContext false
            }

            // Written to one side and moved into place, so a failure part-way
            // cannot leave a truncated JPEG that later reads as a valid cover.
            val tmp = File(target.parentFile, "${target.name}.tmp")
            try {
                target.parentFile?.mkdirs()
                body().byteStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (tmp.length() == 0L) {
                    tmp.delete()
                    return@withContext false
                }
                tmp.renameTo(target)
                true
            } catch (e: Exception) {
                // An episode or feed the server holds no art for answers 404
                // here. That is an ordinary outcome, not a fault worth shouting
                // about — most episodes have no art of their own.
                Log.d(TAG, "No art stored for ${target.name}: ${e.message}")
                runCatching { tmp.delete() }
                false
            }
        }

    /** Returns the bytes freed. */
    fun deleteFeed(context: Context, feedId: Int): Long = delete(feedFile(context, feedId))

    /** Returns the bytes freed. */
    fun deleteEpisode(context: Context, episodeId: Int): Long =
        delete(episodeFile(context, episodeId))

    private fun delete(file: File): Long = runCatching {
        val size = if (file.exists()) file.length() else 0L
        if (file.delete()) size else 0L
    }.getOrDefault(0L)

    /** Removes every stored cover — used when the user clears all downloads. */
    fun deleteAll(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
            .onFailure { Log.w(TAG, "Could not clear stored artwork", it) }
    }

    /**
     * Bytes held by stored artwork.
     *
     * Read from disk rather than tracked in the database because there is no
     * column to track it in — see the note at the top of this file.
     */
    fun totalBytes(context: Context): Long =
        runCatching { dir(context).listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)
}
