package com.castcharm.android.data.api.models

data class PlaylistOut(
    val id: Int,
    val name: String,
    val description: String?,
    val type: String,       // "custom" | "feed"
    val feed_id: Int?,
    val filter: String,
    val created_at: String,
    val episode_count: Int = 0
)
