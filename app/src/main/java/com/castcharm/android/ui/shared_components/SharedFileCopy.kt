package com.castcharm.android.ui.shared_components

// Wording for delete confirmations when a downloaded file is also used by a
// playlist on this phone. Deleting removes the one file, so every list that
// shows it loses it at once; the user should know before they tap Delete.
fun sharedFileWarning(names: List<String>): String {
    if (names.isEmpty()) return ""
    val list = if (names.size <= 3) names.joinToString(", ") { "\"$it\"" } else "${names.size} playlists"
    return " This file is also used by ${if (names.size == 1) "the playlist" else ""} $list on this phone; deleting it removes it from there too."
}

fun sharedFilesWarning(sharedCount: Int, total: Int): String =
    if (sharedCount == 0) "" else " $sharedCount of these $total file${if (total == 1) "" else "s"} ${if (sharedCount == 1) "is" else "are"} also used by playlists on this phone; deleting removes them from there too."
