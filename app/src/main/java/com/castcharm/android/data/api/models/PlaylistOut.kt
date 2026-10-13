package com.castcharm.android.data.api.models

data class PlaylistOut(
    val id: Int,
    val name: String,
    val description: String?,
    val type: String,                 // "custom" (shown as Manual) | "smart"
    val feed_id: Int? = null,         // legacy, unused
    val filter: String = "unplayed",  // legacy, unused
    val rules: SmartRules? = null,    // smart playlists only
    val is_system: Boolean = false,   // the built-in "Get Caught Up"
    val created_at: String,
    val episode_count: Int = 0,
    val total_seconds: Int = 0,
) {
    val isSmart: Boolean get() = type == "smart"
}
