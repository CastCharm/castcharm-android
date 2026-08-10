package com.castcharm.android.data.api.models

// Utility functions for normalizing server data into Android-friendly types.
// Called from EpisodeRepository.toEntity() during every API-to-DB merge.

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

// If the server returns a relative URL (e.g., "/uploads/cover.jpg"), prepend
// the base URL so Coil can load it. Absolute URLs (http/https) pass through unchanged.
fun resolveImageUrl(baseUrl: String, path: String?): String? {
    if (path == null) return null
    return if (path.startsWith("http://") || path.startsWith("https://")) path
    else "${baseUrl.trimEnd('/')}$path"
}

/**
 * Cover-art URL for a feed, carrying a cache-busting token.
 *
 * The plain endpoint is `api/feeds/{id}/cover.jpg`, addressed by feed id alone. The
 * server's feeds table is `INTEGER PRIMARY KEY` with no AUTOINCREMENT, so SQLite
 * recycles ids — delete the most recently added podcast, subscribe to a different
 * one, and it can be handed the same id. That makes the URL byte-identical for two
 * unrelated shows, and every cache keyed on it (Coil's memory and disk caches, the
 * artwork ContentProvider's file cache, any HTTP cache in between) keeps serving the
 * first podcast's art.
 *
 * [feedUrl] is the RSS URL, which actually identifies the podcast, so hashing it
 * gives a token that is stable for one show and different for another. The query
 * string is ignored by the server and changes nothing about the response — it exists
 * purely so the two shows occupy different cache entries.
 *
 * Note this fixes an already-broken install on sight: the URL changes, so the stale
 * entry is simply never consulted again.
 */
fun feedCoverUrl(baseUrl: String, feedId: Int, feedUrl: String?): String? {
    if (baseUrl.isBlank()) return null
    return "${baseUrl}api/feeds/$feedId/cover.jpg${coverToken(feedUrl)}"
}

private fun coverToken(feedUrl: String?): String =
    "?v=${feedUrl?.takeIf { it.isNotBlank() }?.hashCode() ?: 0}"

/**
 * Adds the same cache-busting token to a cover URL the SERVER supplied.
 *
 * This is the path that actually matters. Once a podcast has synced, the server
 * stops reporting the podcast's external RSS artwork and instead points image_url
 * at its own `/api/feeds/{id}/cover.jpg` (see `_feed_out` in app/routers/feeds.py —
 * it prefers a local cover.jpg over the remote URL). That URL is keyed by feed id
 * alone, so on a recycled id it is identical to the one cached for the podcast that
 * used to hold that id.
 *
 * It explains a confusing symptom: the new podcast shows the RIGHT artwork at first
 * — while image_url is still the unique remote URL — and flips to the previous
 * podcast's artwork the moment the feed populates and the server switches to the
 * id-keyed endpoint.
 *
 * URLs that don't point at that endpoint are returned untouched; remote artwork URLs
 * already differ between podcasts and need no help.
 */
fun withFeedCoverToken(url: String?, feedUrl: String?): String? {
    if (url == null) return null
    if (!url.contains("/api/feeds/") || !url.contains("/cover.jpg")) return url
    if (url.contains("?v=") || url.contains("&v=")) return url
    val separator = if (url.contains('?')) "&v=" else "?v="
    return url + separator + (feedUrl?.takeIf { it.isNotBlank() }?.hashCode() ?: 0)
}

// The server returns duration as either an integer (seconds) or a colon-separated
// string (H:MM:SS or MM:SS from RSS feed data). Both forms are normalized to seconds.
fun parseDuration(value: String?): Int? {
    if (value.isNullOrBlank()) return null
    return value.toIntOrNull() ?: run {
        val parts = value.split(":").mapNotNull { it.trim().toIntOrNull() }
        when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> null
        }
    }
}

// The server returns timestamps in two formats depending on the endpoint:
//   - ISO-8601 with offset (e.g., "2024-01-15T12:30:00+00:00") — most endpoints
//   - Naive datetime string (e.g., "2024-01-15T12:30:00") — legacy/some DB fields
// Both are normalized to epoch milliseconds (UTC). Returns null for blank/unparseable values.
fun parseServerDateTime(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            // Treat naive datetime strings as UTC (server stores all datetimes in UTC).
            LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
