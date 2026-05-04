package com.castcharm.android.data.api.models

data class CreatePlaylistRequest(
    val name: String,
    val description: String? = null,
    val type: String = "custom"
)
