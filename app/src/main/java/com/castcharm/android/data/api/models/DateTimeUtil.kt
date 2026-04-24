package com.castcharm.android.data.api.models

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

fun resolveImageUrl(baseUrl: String, path: String?): String? {
    if (path == null) return null
    return if (path.startsWith("http://") || path.startsWith("https://")) path
    else "${baseUrl.trimEnd('/')}$path"
}

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

fun parseServerDateTime(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
