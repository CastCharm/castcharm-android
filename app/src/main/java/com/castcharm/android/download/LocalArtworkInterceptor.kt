package com.castcharm.android.download

// Serves a feed's stored cover from disk instead of asking the server for it.
//
// Sits in the single ImageLoader every screen uses, so the preference for the
// local copy is stated once. The alternative was editing the artwork fallback
// chain on each screen that draws a feed cover — the feed list, the episode
// list, the episode card, the dashboard, the player — which is the same
// five-places-to-remember shape that has already caused artwork bugs here.
//
// Matching is on the id in the URL, so it is unaffected by the cache-busting
// token the app appends (api/feeds/7/cover.jpg?v=-12345).

import android.content.Context
import coil.intercept.Interceptor
import coil.request.ImageResult
import java.io.File

class LocalArtworkInterceptor(private val context: Context) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String
        val stored: File? = when {
            url == null -> null
            else -> EPISODE_COVER.find(url)?.let { m ->
                m.groupValues[1].toIntOrNull()?.let { LocalArtwork.episodeFile(context, it) }
            } ?: FEED_COVER.find(url)?.let { m ->
                m.groupValues[1].toIntOrNull()?.let { LocalArtwork.feedFile(context, it) }
            }
        }

        if (stored != null && stored.length() > 0) {
            return chain.proceed(
                chain.request.newBuilder()
                    .data(stored)
                    // Keyed by path and mtime so replacing the file — a podcast
                    // rebrands, or a recycled id is evicted and re-fetched —
                    // does not keep serving the previous image from memory.
                    .memoryCacheKey("local-art:${stored.absolutePath}:${stored.lastModified()}")
                    .build()
            )
        }

        return chain.proceed(chain.request)
    }

    private companion object {
        val FEED_COVER = Regex("""/api/feeds/(\d+)/cover\.jpg""")
        val EPISODE_COVER = Regex("""/api/episodes/(\d+)/cover\.jpg""")
    }
}
