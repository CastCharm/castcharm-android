package com.castcharm.android.data.api.models

data class PlayerPlayRequest(
    val context_type: String,
    val context_id: Int,
    val episode_id: Int? = null,
    val context_filter: String = "unplayed"
)
