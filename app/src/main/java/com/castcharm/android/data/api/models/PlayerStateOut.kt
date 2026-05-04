package com.castcharm.android.data.api.models

data class PlayerStateOut(
    val current_episode_id: Int? = null,
    val context_type: String? = null,
    val context_id: Int? = null,
    val context_filter: String? = null,
    val current_episode: EpisodeOut? = null,
    val queue: List<EpisodeOut> = emptyList(),
    val queue_position: Int? = null
)
