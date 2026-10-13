package com.castcharm.android.data.api.models

data class UpdatePlaylistRequest(
    val name: String? = null,
    val description: String? = null,
    val rules: SmartRules? = null   // smart playlists only
)
