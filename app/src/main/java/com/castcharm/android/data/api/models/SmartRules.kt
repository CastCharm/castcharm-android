package com.castcharm.android.data.api.models

// Smart-playlist rules, mirroring SmartRules in the server's app/schemas.py.
// The server is the authority online; the phone evaluates the same rules over
// its own episode table so smart playlists keep working offline and so a row
// drops out of a list the moment it stops matching (e.g. marked played).

import com.castcharm.android.data.db.entities.EpisodeEntity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class SmartFeedsRule(
    val mode: String = "include",          // "include" | "exclude"
    val ids: List<Int> = emptyList(),
)

data class SmartPublishedRule(
    val within_days: Int? = null,
    val after: String? = null,             // YYYY-MM-DD
    val before: String? = null,            // YYYY-MM-DD
)

data class SmartDurationRule(
    val min_minutes: Int? = null,
    val max_minutes: Int? = null,
)

data class SmartRules(
    val feeds: SmartFeedsRule? = null,
    val played: String = "unplayed",       // unplayed | in_progress | played | any
    val published: SmartPublishedRule? = null,
    val duration: SmartDurationRule? = null,
    val title_contains: String? = null,
    val sort: String = "oldest",           // oldest | newest | shortest | longest
    val limit: Int? = null,
) {
    /** Does this episode satisfy every condition? "Downloaded" means on the
     *  server when evaluating a synced list, or on this phone when [localOnly]. */
    fun matches(ep: EpisodeEntity, nowMs: Long = System.currentTimeMillis(), localOnly: Boolean = false, ignoreFeeds: Boolean = false): Boolean {
        if (ep.hidden) return false
        if (localOnly) { if (ep.local_path == null) return false }
        else if (ep.status != "downloaded" && ep.local_path == null) return false

        // The podcast clause is left to the server when re-filtering its own
        // member list: it expands a podcast to its supplementary feeds, which
        // the phone never sees, so judging it here would drop those episodes.
        if (!ignoreFeeds) feeds?.let { f ->
            if (f.ids.isNotEmpty()) {
                val inSet = ep.feed_id in f.ids
                if (f.mode == "exclude" && inSet) return false
                if (f.mode != "exclude" && !inSet) return false
            }
        }
        when (played) {
            "unplayed" -> if (ep.played) return false
            "in_progress" -> if (ep.played || ep.play_position_seconds <= 0) return false
            "played" -> if (!ep.played) return false
        }
        published?.let { p ->
            val at = ep.published_at
            p.within_days?.let { d -> if (at == null || at < nowMs - d * 86_400_000L) return false }
            p.after?.let { s -> val t = dayStart(s); if (t != null && (at == null || at < t)) return false }
            p.before?.let { s -> val t = dayStart(s); if (t != null && (at == null || at >= t + 86_400_000L)) return false }
        }
        duration?.let { d ->
            val secs = ep.duration ?: 0
            d.min_minutes?.let { if (secs < it * 60) return false }
            d.max_minutes?.let { if (secs > it * 60) return false }
        }
        title_contains?.takeIf { it.isNotBlank() }?.let { needle ->
            if (!ep.title.contains(needle, ignoreCase = true)) return false
        }
        return true
    }

    /** Sort and cap a list of matching episodes the way the server does. */
    fun apply(eps: List<EpisodeEntity>): List<EpisodeEntity> {
        val oldestFirst = eps.sortedWith(
            compareBy<EpisodeEntity> { it.published_at == null }
                .thenBy { it.published_at ?: Long.MAX_VALUE }
                .thenBy { it.episode_number ?: Int.MAX_VALUE }
                .thenBy { it.id }
        )
        val sorted = when (sort) {
            "newest" -> oldestFirst.reversed()
            "shortest" -> oldestFirst.sortedBy { it.duration ?: 0 }
            "longest" -> oldestFirst.sortedByDescending { it.duration ?: 0 }
            else -> oldestFirst
        }
        return limit?.let { sorted.take(it) } ?: sorted
    }

    fun evaluate(eps: List<EpisodeEntity>, localOnly: Boolean, ignoreFeeds: Boolean = false): List<EpisodeEntity> {
        val now = System.currentTimeMillis()
        return apply(eps.filter { matches(it, now, localOnly, ignoreFeeds) })
    }

    /** One-line, human reading, e.g. "Unplayed · last 14 days · oldest first". */
    fun describe(): String {
        val parts = mutableListOf<String>()
        parts += when (played) { "in_progress" -> "In progress"; "played" -> "Played"; "any" -> "Any played state"; else -> "Unplayed" }
        feeds?.takeIf { it.ids.isNotEmpty() }?.let {
            parts += "${if (it.mode == "exclude") "not from" else "from"} ${it.ids.size} podcast${if (it.ids.size == 1) "" else "s"}"
        }
        published?.let { p ->
            p.within_days?.let { parts += "last $it day${if (it == 1) "" else "s"}" }
            p.after?.let { parts += "after $it" }
            p.before?.let { parts += "before $it" }
        }
        duration?.let { d ->
            when {
                d.min_minutes != null && d.max_minutes != null -> parts += "${d.min_minutes}–${d.max_minutes} min"
                d.min_minutes != null -> parts += "over ${d.min_minutes} min"
                d.max_minutes != null -> parts += "under ${d.max_minutes} min"
            }
        }
        title_contains?.takeIf { it.isNotBlank() }?.let { parts += "title has “$it”" }
        parts += when (sort) { "newest" -> "newest first"; "shortest" -> "shortest first"; "longest" -> "longest first"; else -> "oldest first" }
        limit?.let { parts += "first $it" }
        return parts.joinToString(" · ")
    }

    companion object {
        val PLAYED_OPTIONS = listOf("unplayed" to "Unplayed", "in_progress" to "In progress", "played" to "Played", "any" to "Any")
        val SORT_OPTIONS = listOf("oldest" to "Oldest first", "newest" to "Newest first", "shortest" to "Shortest first", "longest" to "Longest first")

        private fun dayStart(s: String): Long? = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time
        }.getOrNull()
    }
}

data class SmartPreviewOut(
    val count: Int,
    val total_seconds: Int,
    val sample: List<Map<String, Any?>> = emptyList(),
)
