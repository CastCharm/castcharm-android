package com.castcharm.android.data.api.models

// Lengths the server accepts for these fields (PlaylistCreate in app/schemas.py).
// Text inputs cap themselves to these so a long paste is trimmed as it is typed
// rather than rejected when the user presses Create.
const val PLAYLIST_NAME_MAX = 500
const val PLAYLIST_DESC_MAX = 5000

data class CreatePlaylistRequest(
    val name: String,
    val description: String? = null,
    val type: String = "custom"
)
