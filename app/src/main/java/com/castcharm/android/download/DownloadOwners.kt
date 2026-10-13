package com.castcharm.android.download

// Owner keys for download_owners (see DownloadOwnerEntity) and their wording.

object DownloadOwners {
    const val FEED = "feed"
    const val CATCHUP = "catchup"
    fun playlist(id: Int) = "playlist:$id"
    fun playlistId(owner: String): Int? = owner.removePrefix("playlist:").toIntOrNull().takeIf { owner.startsWith("playlist:") }

    /** Human wording for an owner, given a way to name playlists. */
    fun displayName(owner: String, feedTitle: String?, playlistName: (Int) -> String?): String = when {
        owner == FEED -> feedTitle?.let { "its podcast ($it)" } ?: "its podcast"
        owner == CATCHUP -> "Get Caught Up (automatic)"
        else -> playlistId(owner)?.let { playlistName(it) }?.let { "playlist \"$it\"" } ?: "a playlist"
    }
}
