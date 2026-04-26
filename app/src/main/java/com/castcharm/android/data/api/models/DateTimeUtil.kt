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
