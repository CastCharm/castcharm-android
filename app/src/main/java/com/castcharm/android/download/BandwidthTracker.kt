package com.castcharm.android.download

// BandwidthTracker maintains a cumulative "bytes downloaded this month" figure
// that surfaces on the Downloads screen. The month is stored as a "yyyy-MM"
// string so the reset logic doesn't need to reason about calendar arithmetic.
//
// Cost of a per-download update is one DataStore edit — no extra network I/O
// or file scan. If the reset text ever drifts across timezones (rare — only if
// the user travels across the month boundary), the counter simply carries the
// old value into the new month; harmless.

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.castcharm.android.BANDWIDTH_BYTES_KEY
import com.castcharm.android.BANDWIDTH_MONTH_KEY
import com.castcharm.android.dataStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class BandwidthUsage(val bytes: Long, val month: String)

object BandwidthTracker {

    private val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US)

    private fun currentMonth(): String = monthFormat.format(Date())

    // Adds the given byte count to this month's counter. Called from
    // DownloadWorker after a file has fully written to disk.
    suspend fun record(context: Context, bytes: Long) {
        if (bytes <= 0) return
        val month = currentMonth()
        context.dataStore.edit { prefs ->
            val storedMonth = prefs[BANDWIDTH_MONTH_KEY]
            val base = if (storedMonth == month) prefs[BANDWIDTH_BYTES_KEY] ?: 0L else 0L
            prefs[BANDWIDTH_BYTES_KEY] = base + bytes
            prefs[BANDWIDTH_MONTH_KEY] = month
        }
    }

    // Reactive flow of "bytes used this month" that self-resets when the
    // stored month is stale. Emits 0 for a fresh install or right after a
    // month rollover before any new download happens.
    fun observe(context: Context): Flow<BandwidthUsage> =
        context.dataStore.data.map { prefs ->
            val month = currentMonth()
            val storedMonth = prefs[BANDWIDTH_MONTH_KEY]
            val bytes = if (storedMonth == month) prefs[BANDWIDTH_BYTES_KEY] ?: 0L else 0L
            BandwidthUsage(bytes = bytes, month = month)
        }
}
