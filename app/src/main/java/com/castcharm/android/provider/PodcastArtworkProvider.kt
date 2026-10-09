package com.castcharm.android.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import com.castcharm.android.CastCharmApp
import com.castcharm.android.R
import coil.memory.MemoryCache
import com.castcharm.android.data.api.ApiKeyInterceptor
import com.castcharm.android.download.LocalArtwork
import com.castcharm.android.data.api.PersistentCookieJar
import com.castcharm.android.data.api.models.feedCoverUrl
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * PodcastArtworkProvider serves artwork images to external processes like Android Auto (Gearhead).
 * 
 * Why this is necessary:
 * 1. Android Auto runs in a separate process and cannot access your app's internal cache or 
 *    resource files directly via file paths.
 * 2. Media3/MediaSession requires "content://" URIs for artwork to ensure cross-app permission 
 *    handling works correctly.
 * 3. This provider handles the downloading and caching of remote images so that the system UI 
 *    doesn't have to wait for network requests.
 */
class PodcastArtworkProvider : ContentProvider() {

    companion object {
        const val TAG = "PodcastArtworkProvider"
        const val CACHE_DIR_NAME = "artwork"

        /**
         * Cache filename for a feed's artwork, including a token from the feed's RSS
         * URL. Two different podcasts that end up sharing a recycled feed id get
         * different files, so neither can serve the other's cover.
         */
        fun feedArtworkCacheName(feedId: Int, feedUrl: String?): String {
            val token = feedUrl?.takeIf { it.isNotBlank() }?.hashCode() ?: 0
            return "feed_${feedId}_$token.img"
        }

        /**
         * Throws away every cached copy of one feed's artwork.
         *
         * Needed because the server's feeds table is `INTEGER PRIMARY KEY` without
         * AUTOINCREMENT, so SQLite recycles ids: delete the most recently added
         * podcast, subscribe to a different one, and it can be handed the same id.
         * The cover URL is built purely from that id
         * (api/feeds/{id}/cover.jpg), so it comes out byte-identical for two
         * unrelated shows and every cache keyed on it happily serves the old image.
         *
         * Both caches have to go: this provider's own file cache (used by Android
         * Auto) and Coil's memory + disk caches (used by the phone UI).
         */
        fun evictFeedArtwork(context: android.content.Context, feedId: Int) {
            // The durable copy stored beside downloads is keyed by feed id too,
            // so a recycled id would serve the previous podcast's cover from
            // disk long after every cache had been cleared.
            LocalArtwork.deleteFeed(context, feedId)

            runCatching {
                val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
                // Filenames carry a token, so sweep by prefix rather than guessing it.
                cacheDir.listFiles { f -> f.name.startsWith("feed_${feedId}_") }
                    ?.forEach { it.delete() }
            }.onFailure { Log.w(TAG, "Could not clear artwork file cache for feed $feedId", it) }

            runCatching {
                val baseUrl = CastCharmApp.apiClient.getBaseUrl()
                if (baseUrl.isBlank()) return@runCatching
                val url = "${baseUrl}api/feeds/$feedId/cover.jpg"
                val loader = CastCharmApp.imageLoader
                loader.memoryCache?.remove(MemoryCache.Key(url))
                loader.diskCache?.remove(url)
            }.onFailure { Log.w(TAG, "Could not clear Coil cache for feed $feedId", it) }
        }

        /**
         * The best artwork file already on the device for an episode, without
         * touching the network: its own durable or cached copy, else the feed's.
         * Null when nothing is on the device yet.
         */
        fun localArtworkFileFor(context: android.content.Context, episode: com.castcharm.android.data.db.entities.EpisodeEntity): File? {
            val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
            fun ok(f: File) = f.takeIf { runCatching { it.length() > 0 }.getOrDefault(false) }
            ok(LocalArtwork.episodeFile(context, episode.id))?.let { return it }
            ok(File(cacheDir, "episode_${episode.id}.img"))?.let { return it }
            ok(LocalArtwork.feedFile(context, episode.feed_id))?.let { return it }
            val feedUrl = runCatching {
                runBlocking { AppDatabase.getDatabase(context).feedDao().getFeedOnce(episode.feed_id)?.url }
            }.getOrNull()
            ok(File(cacheDir, feedArtworkCacheName(episode.feed_id, feedUrl)))?.let { return it }
            return null
        }

        /** True when *bytes* are the placeholder this provider serves on failure. */
        fun isFallbackImage(context: android.content.Context, bytes: ByteArray): Boolean {
            val fallback = File(File(context.cacheDir, CACHE_DIR_NAME), "fallback.png")
            if (!fallback.exists() || fallback.length() != bytes.size.toLong()) return false
            return runCatching { fallback.readBytes().contentEquals(bytes) }.getOrDefault(false)
        }

        fun prefetchFeedArtwork(context: android.content.Context, feedId: Int) {
            val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }
            try {
                val db = AppDatabase.getDatabase(context)
                val feed = runBlocking { db.feedDao().getFeedOnce(feedId) }
                // Named after the feed is known — see feedArtworkCacheName.
                val cacheFile = File(cacheDir, feedArtworkCacheName(feedId, feed?.url))
                if (cacheFile.exists() && cacheFile.length() > 0) return

                // Seed from the durable copy when there is one — no network, and
                // correct even after the system has cleared this cache.
                val stored = LocalArtwork.feedFile(context, feedId)
                if (stored.exists() && stored.length() > 0) {
                    runCatching { stored.copyTo(cacheFile, overwrite = true) }
                    if (cacheFile.exists() && cacheFile.length() > 0) return
                }
                val episodeWithFeedArt = runBlocking { db.episodeDao().getEpisodeWithFeedImageForFeed(feedId) }
                val baseUrl = runBlocking {
                    val savedUrl = context.dataStore.data.map { it[stringPreferencesKey("server_url")] }.first()
                    if (savedUrl.isNullOrBlank()) "" else if (savedUrl.endsWith("/")) savedUrl else "$savedUrl/"
                }
                val url = feed?.custom_image_url?.takeIf { it.isNotBlank() }
                    ?: feed?.image_url?.takeIf { it.isNotBlank() }
                    ?: episodeWithFeedArt?.feed_image_url?.takeIf { it.isNotBlank() }
                    ?: feedCoverUrl(baseUrl, feedId, feed?.url)
                if (url != null) {
                    val client = OkHttpClient.Builder()
                        .cookieJar(PersistentCookieJar.getInstance(context))
                        .addInterceptor(ApiKeyInterceptor(context))
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()
                    downloadToCacheStatic(client, url, cacheFile)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Feed artwork prefetch failed for ${feedId}", e)
            }
        }

        private fun downloadToCacheStatic(client: OkHttpClient, url: String, cacheFile: File): Boolean {
            return try {
                val tmpFile = File(cacheFile.parentFile, cacheFile.name + ".tmp")
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return false
                    val body = response.body ?: return false
                    tmpFile.outputStream().use { output -> body.byteStream().copyTo(output) }
                    if (tmpFile.length() <= 0L) {
                        tmpFile.delete()
                        return false
                    }
                    tmpFile.copyTo(cacheFile, overwrite = true)
                    tmpFile.delete()
                    response.header("Content-Type")?.let { type ->
                        File(cacheFile.parentFile, cacheFile.name + ".type").writeText(type)
                    }
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prefetch artwork: $url", e)
                false
            }
        }
    }

    private lateinit var db: AppDatabase
    // A ContentProvider is created before Application.onCreate(), so AuthStore's
    // cache may still be cold here; ApiKeyInterceptor loads it on first use.
    private val httpClient by lazy {
        val cookieJar = PersistentCookieJar.getInstance(context!!)
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(ApiKeyInterceptor(context!!))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(): Boolean {
        db = AppDatabase.getDatabase(context!!)
        return true
    }

    /**
     * openFile is the primary entry point for the system to fetch the actual image bytes.
     * ContentProvider methods are called on a binder thread, so we can perform blocking I/O 
     * and network calls here, though we use runBlocking for Room/DataStore compatibility.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        Log.d(TAG, "AA PROVIDER openFile uri=$uri mode=$mode")
        val pathSegments = uri.pathSegments
        if (pathSegments.isEmpty()) return null

        // We use a dedicated internal cache directory to store downloaded artwork.
        val cacheDir = File(context!!.cacheDir, CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }

        return when (pathSegments[0]) {
            "feed" -> {
                val feedId = pathSegments.getOrNull(1)?.toIntOrNull() ?: return serveFallback(cacheDir)
                handleFeedArtwork(feedId, cacheDir)
            }
            "episode" -> {
                val episodeId = pathSegments.getOrNull(1)?.toIntOrNull() ?: return serveFallback(cacheDir)
                handleEpisodeArtwork(episodeId, cacheDir)
            }
            "fallback" -> {
                Log.d(TAG, "AA PROVIDER explicit fallback request")
                serveFallback(cacheDir)
            }
            else -> serveFallback(cacheDir)
        }
    }

    /**
     * Handles feed-level artwork.
     */
    private fun handleFeedArtwork(feedId: Int, cacheDir: File): ParcelFileDescriptor? {
        // The feed has to be read BEFORE the cache filename is chosen: the filename
        // carries a token derived from the feed's RSS URL, so a recycled feed id
        // lands on a different file rather than serving the previous podcast's art.
        // Keying purely on the id, as this did, made the stale image permanent here
        // even once Coil had been fixed — this path short-circuits on the file
        // existing and never re-checks the network.
        val feed = runBlocking { db.feedDao().getFeedOnce(feedId) }
        val episodeWithFeedArt = runBlocking { db.episodeDao().getEpisodeWithFeedImageForFeed(feedId) }
        val baseUrl = getBaseUrl()

        val cacheFile = File(cacheDir, feedArtworkCacheName(feedId, feed?.url))

        if (cacheFile.exists() && cacheFile.length() > 0) {
            return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // The copy stored beside downloaded episodes needs no network, which is
        // the whole point in a car: this cache lives in cacheDir and the system
        // reclaims it whenever it likes, and the fallback below is an HTTP call
        // to a server that is usually nowhere in reach at that moment.
        val stored = LocalArtwork.feedFile(context!!, feedId)
        if (stored.exists() && stored.length() > 0) {
            runCatching { stored.copyTo(cacheFile, overwrite = true) }
            if (cacheFile.exists() && cacheFile.length() > 0) {
                return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }

        val url = feed?.custom_image_url?.takeIf { it.isNotBlank() }
            ?: feed?.image_url?.takeIf { it.isNotBlank() }
            ?: episodeWithFeedArt?.feed_image_url?.takeIf { it.isNotBlank() }
            ?: feedCoverUrl(baseUrl, feedId, feed?.url)

        if (url != null && downloadToCache(url, cacheFile)) {
            return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        return serveFallback(cacheDir)
    }

    /**
     * Handles episode-specific artwork. 
     * Similar to feeds, but prioritizes the episode's specific cover art if it differs from the feed.
     */
    private fun handleEpisodeArtwork(episodeId: Int, cacheDir: File): ParcelFileDescriptor? {
        val cacheFile = File(cacheDir, "episode_$episodeId.img")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            Log.d(TAG, "AA EPISODE ART episode=$episodeId cacheHit=true file=${cacheFile.absolutePath} size=${cacheFile.length()}")
            return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val episode = runBlocking { db.episodeDao().getEpisodeOnce(episodeId) } ?: run {
            Log.w(TAG, "AA EPISODE ART episode=$episodeId NOT FOUND")
            return serveFallback(cacheDir)
        }

        // The durable copy stored beside a downloaded episode: no network, and
        // still there after the system has cleared this cache.
        val stored = LocalArtwork.episodeFile(context!!, episodeId)
        if (stored.exists() && stored.length() > 0) {
            runCatching { stored.copyTo(cacheFile, overwrite = true) }
            if (cacheFile.exists() && cacheFile.length() > 0) {
                return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }

        // Most episodes have no art of their own and simply show the podcast's
        // cover. That cover is served by the feed path, which already knows the
        // cache, the durable copy and the network, in that order — so defer to
        // it rather than re-downloading the same image per episode. This used to
        // go straight to the network for every such episode, which in a car (no
        // reachable server, or not yet) meant the placeholder every time.
        val ownArtUrl = episode.custom_image_url?.takeIf { it.isNotBlank() }
            ?: episode.episode_image_url?.takeIf { it.isNotBlank() }
        if (ownArtUrl == null) {
            Log.d(TAG, "AA EPISODE ART episode=$episodeId → feed ${episode.feed_id} art")
            return handleFeedArtwork(episode.feed_id, cacheDir)
        }

        Log.d(TAG, "AA EPISODE ART episode=$episodeId url=$ownArtUrl")
        if (downloadToCache(ownArtUrl, cacheFile)) {
            return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // Episode art unreachable: the podcast's cover is the right stand-in,
        // and it is far more likely to be on the device already.
        Log.w(TAG, "AA EPISODE ART episode=$episodeId own art failed; using feed art")
        return handleFeedArtwork(episode.feed_id, cacheDir)
    }

    private fun downloadToCache(url: String, cacheFile: File): Boolean {
        return try {
            val tmpFile = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                tmpFile.outputStream().use { output ->
                    body.byteStream().copyTo(output)
                }
                if (tmpFile.length() <= 0L) {
                    tmpFile.delete()
                    return false
                }
                tmpFile.copyTo(cacheFile, overwrite = true)
                tmpFile.delete()
                response.header("Content-Type")?.let { type ->
                    File(cacheFile.parentFile, cacheFile.name + ".type").writeText(type)
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download artwork: $url", e)
            false
        }
    }

    /**
     * Provides a local placeholder image if the remote artwork cannot be loaded.
     * This prevents Android Auto from showing a broken image or an empty box.
     */
    private fun serveFallback(cacheDir: File): ParcelFileDescriptor? {
        val fallbackFile = File(cacheDir, "fallback.png")
        if (!fallbackFile.exists() || fallbackFile.length() == 0L) {
            try {
                val drawable = ContextCompat.getDrawable(context!!, R.drawable.ic_launcher_foreground)
                if (drawable != null) {
                    val bitmap = drawableToBitmap(drawable)
                    FileOutputStream(fallbackFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create fallback image", e)
                return null
            }
        }
        return if (fallbackFile.exists()) {
            ParcelFileDescriptor.open(fallbackFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } else null
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * runBlocking is used here because ContentProvider doesn't support suspend functions 
     * and we need to fetch configuration from DataStore synchronously to build URLs.
     */
    private fun getBaseUrl(): String = runBlocking {
        val savedUrl = context?.dataStore?.data?.map { it[stringPreferencesKey("server_url")] }?.first()
        if (savedUrl.isNullOrBlank()) "" else if (savedUrl.endsWith("/")) savedUrl else "$savedUrl/"
    }

    /**
     * getType is required for ContentProviders to inform the requesting app of the MIME type.
     * Android Auto checks this to determine how to decode the file descriptor.
     */
    override fun getType(uri: Uri): String? {
        val pathSegments = uri.pathSegments
        if (pathSegments.isEmpty()) return "image/*"

        val cacheDir = File(context?.cacheDir, CACHE_DIR_NAME)
        val fileName = when (pathSegments[0]) {
            "feed" -> "feed_${pathSegments.getOrNull(1)}.img"
            "episode" -> "episode_${pathSegments.getOrNull(1)}.img"
            "fallback" -> "fallback.png"
            else -> null
        }

        val file = if (fileName != null) File(cacheDir, fileName) else null
        val sidecar = if (file != null) File(cacheDir, file.name + ".type") else null
        return when {
            sidecar != null && sidecar.exists() -> sidecar.readText().ifBlank { "image/*" }
            file != null && file.exists() && file.name.endsWith(".png") -> "image/png"
            file != null && file.exists() -> "image/jpeg"
            pathSegments[0] == "fallback" -> "image/png"
            else -> "image/*"
        }
    }

    // Required overrides for ContentProvider but unused in this read-only implementation.
    override fun query(uri: Uri, p1: Array<out String>?, p2: String?, p3: Array<out String>?, p4: String?): Cursor? = null
    override fun insert(uri: Uri, p1: ContentValues?): Uri? = null
    override fun delete(uri: Uri, p1: String?, p2: Array<out String>?): Int = 0
    override fun update(uri: Uri, p1: ContentValues?, p2: String?, p3: Array<out String>?): Int = 0
}
