package com.castcharm.android.data.api.models

/**
 * The ordered list of episode ids in a feed — the feed's table of contents.
 *
 * This is what makes an episode's position knowable without downloading the
 * episodes above it. With it the app can lay out the whole feed immediately
 * (every row it has not fetched yet drawn as a skeleton), scroll straight to any
 * position, and then fetch only the page it is actually showing.
 *
 * Ids only, so it stays small: a 2,000-episode feed is around 12 KB, versus
 * several megabytes for the same range of full episode records.
 */
data class EpisodeIndexOut(
    val total: Int = 0,
    val ids: List<Int> = emptyList(),
    // True when the feed had more episodes than the server is willing to describe
    // in one response. The list then covers the newest `total` of them, which is
    // a shortened feed rather than a broken one.
    val truncated: Boolean = false
)
