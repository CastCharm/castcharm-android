package com.castcharm.android.data.api.models

/**
 * Applies one action to many episodes in a single request.
 *
 * action is one of: download, delete_file, hide, unhide, mark_played,
 * mark_unplayed. The played actions SET the state rather than toggling it, which
 * is what makes them safe to use on a selection containing episodes whose current
 * state the app has not loaded.
 */
data class BulkEpisodeRequest(
    val episode_ids: List<Int>,
    val action: String
)

data class BulkEpisodeResult(
    val affected: Int = 0
)
