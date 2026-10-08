package com.castcharm.android.ui.shared_components

// EpisodeCard is the shared expandable episode row used in EpisodeListScreen,
// DownloadsScreen, and PlaylistDetailScreen. It handles the full episode interaction
// surface: artwork, title, metadata row (date / duration / download indicator /
// playback state), a 2dp progress bar, and an animated action panel that expands on
// tap to show Play / Mark Played / download action buttons and the episode description.
//
// The download state is abstracted through EpisodeDownloadActionOverride so callers
// from different screens can inject the correct state without re-deriving it here.
// When no override is provided, the card derives the state from the episode entity
// fields directly (local_path, status).

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhonelinkRing
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.castcharm.android.data.db.entities.EpisodeEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The eight possible download states an episode can be in from the app's perspective.
// ON_PHONE          — audio file is saved on this device (local_path != null)
// SAVE_TO_PHONE     — file is on the server (status=downloaded) but not yet on this device
// PHONE_IN_PROGRESS — WorkManager is actively downloading to this device
// PHONE_FAILED      — phone-side download exhausted all retries; tap Retry to restart
// SERVER_DOWNLOADING — the server is actively downloading from the RSS source
// SERVER_QUEUED     — the server has the episode queued for download
// SAVE_TO_SERVER    — episode is not downloaded anywhere; only the metadata exists
// RETRY_SERVER      — a previous server-side download attempt failed
enum class EpisodeDownloadActionOverride {
    ON_PHONE,
    SAVE_TO_PHONE,
    PHONE_IN_PROGRESS,
    PHONE_FAILED,
    SERVER_DOWNLOADING,
    SERVER_QUEUED,
    SAVE_TO_SERVER,
    RETRY_SERVER
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeCard(
    episode: EpisodeEntity,
    baseUrl: String,
    feedImageUrl: String? = null,
    isSelected: Boolean = false,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onPlay: () -> Unit,
    onTogglePlayedStatus: () -> Unit,
    onDownloadToServer: () -> Unit,
    onDownloadToDevice: () -> Unit,
    onDeleteFromPhone: (() -> Unit)? = null,
    downloadActionOverride: EpisodeDownloadActionOverride? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    isInPlaylist: Boolean = false,
    enablePlaylists: Boolean = true,
    // True whenever multi-select is active, regardless of whether *this* row is
    // picked. Lets unselected rows show an empty checkbox, so the distinction is
    // between two obviously different marks rather than a background tint the user
    // has to hunt for. Appended rather than slotted next to isSelected so adding it
    // did not shuffle the positional argument order of a component with six callers.
    selectionActive: Boolean = false,
) {
    // Resolve artwork URL through a four-level fallback chain:
    // 1. Episode-specific custom image (set by the user or override)
    // 2. Episode-level image from the RSS feed entry
    // 3. feedImageUrl passed in by the caller (e.g., from the joined feed record)
    // 4. Feed-level image stored denormalized on the episode entity
    // 5. Constructed server cover URL (requires a valid baseUrl)
    val imageUrl = episode.custom_image_url
        ?: episode.episode_image_url
        ?: feedImageUrl
        ?: episode.feed_image_url
        ?: if (baseUrl.isNotBlank()) "${baseUrl}api/feeds/${episode.feed_id}/cover.jpg" else null

    // Determine the server-side download state. local_path is checked alongside status
    // because a phone download sets local_path independently of the server status field.
    val isAvailableOnServer = episode.status == "downloaded" && episode.local_path == null
    val isServerQueued = episode.local_path == null && episode.status == "queued"
    val isServerDownloading = episode.local_path == null && episode.status == "downloading"
    val isServerFailed = episode.local_path == null && episode.status == "failed"

    // Use the caller-supplied override if present (e.g., DownloadsScreen injects
    // PHONE_IN_PROGRESS based on WorkManager state). Otherwise derive from entity fields.
    val actionState = downloadActionOverride ?: when {
        episode.local_path != null -> EpisodeDownloadActionOverride.ON_PHONE
        isAvailableOnServer -> EpisodeDownloadActionOverride.SAVE_TO_PHONE
        isServerDownloading -> EpisodeDownloadActionOverride.SERVER_DOWNLOADING
        isServerQueued -> EpisodeDownloadActionOverride.SERVER_QUEUED
        isServerFailed -> EpisodeDownloadActionOverride.RETRY_SERVER
        else -> EpisodeDownloadActionOverride.SAVE_TO_SERVER
    }

    // Selection is signalled three ways at once, so it survives any theme and does
    // not depend on telling two similar background colours apart: a check badge over
    // the artwork (see below), an accent border, and a raised elevation. The tinted
    // container alone used to be the only cue, and in several of the darker themes
    // primaryContainer sits very close to the card surface.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        elevation = if (isSelected) {
            CardDefaults.cardElevation(defaultElevation = 6.dp)
        } else {
            CardDefaults.cardElevation()
        }
    ) {
        Column {
            // ---- Collapsed header row ----------------------------------------
            // Always visible. Tap toggles the expanded panel; long-press activates
            // multi-select mode (onLongPress is null if multi-select is disabled
            // for this context, e.g., the dashboard "Continue Listening" section).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onToggleExpand, onLongClick = onLongPress)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // While multi-select is active the artwork doubles as the checkbox.
                // Every row gets a mark — filled tick or empty ring — so "selected"
                // and "not selected" differ by shape, which stays legible over any
                // cover art and in any theme.
                Box(modifier = Modifier.size(48.dp)) {
                    PlaceholderArtwork(
                        imageUrl = imageUrl,
                        contentDescription = episode.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        cornerRadiusDp = 6
                    )

                    if (selectionActive) {
                        // Both marks sit on an OPAQUE disc. Anything translucent
                        // composites against whatever cover art happens to be
                        // underneath, so its contrast is unknowable at author time —
                        // a translucent accent wash with a white tick measured
                        // 1.2:1 on Cyberpunk over light artwork, i.e. invisible,
                        // which is the very problem this indicator exists to solve.
                        if (isSelected) {
                            // Accent wash over the whole thumbnail so picked rows are
                            // obvious when scanning, plus a solid primary disc that
                            // gives the tick a known background to sit on.
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                // The theme computes onPrimary from the primary's
                                // luminance for exactly this purpose, so it is
                                // legible on every one of the shipped themes.
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(18.dp)
                            )
                        } else {
                            // Unselected rows keep their artwork — washing out every
                            // thumbnail the moment multi-select opens makes the list
                            // look broken. Only the ring gets a backdrop.
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f))
                            )
                            Icon(
                                imageVector = Icons.Default.RadioButtonUnchecked,
                                contentDescription = "Not selected",
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(26.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.size(12.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Episode title. Unplayed episodes render in SemiBold with full
                    // opacity; played episodes are dimmed to 60% and Normal weight to
                    // visually distinguish the backlog from already-heard content.
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (!episode.played) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (episode.played) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )

                    // Metadata row: date | playback state | duration | download state.
                    // Each element is only rendered when the data is available (e.g.,
                    // duration is nullable, date may be absent for manually added episodes).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val dateStr = formatDate(episode.published_at)
                        if (dateStr.isNotEmpty()) {
                            Text(
                                text = dateStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Playback state, sitting right after the date. This is the
                        // single place playback status is stated. It used to be carried
                        // by the accent progress bar along the bottom edge of the card,
                        // which multi-select then wiped out — the selected-row border
                        // runs along that same edge in the same accent colour, so the
                        // moment you started picking rows you could no longer tell what
                        // you had already heard. An icon inside the row is unaffected
                        // by the border and reads the same in every theme.
                        //
                        // The three states are one glyph in three fills — empty ring,
                        // part-filled, solid — so they are read as points on a single
                        // scale. Part-played used to be a separate "Resume" pill sat
                        // beside an empty ring, which said "unplayed" and "half played"
                        // simultaneously in two unrelated visual languages.
                        //
                        // When the empty ring is worth drawing: only for episodes that
                        // are actually to hand, so it means "downloaded, not listened
                        // yet" rather than putting a marker on every row in the list.
                        // Started and played episodes always get their mark, since the
                        // fill is the only thing now carrying that information.
                        val isDownloaded =
                            actionState == EpisodeDownloadActionOverride.ON_PHONE ||
                                actionState == EpisodeDownloadActionOverride.SAVE_TO_PHONE
                        val isStarted = episode.play_position_seconds > 0 && !episode.played
                        if (episode.played || isStarted || isDownloaded) {
                            PlaybackStateIcon(
                                played = episode.played,
                                progress = when {
                                    !isStarted -> 0f
                                    episode.duration != null && episode.duration > 0 ->
                                        episode.play_position_seconds.toFloat() / episode.duration
                                    // Started, but the feed never supplied a duration,
                                    // so there is no fraction to be had. Half is the
                                    // honest reading of "somewhere in the middle".
                                    else -> 0.5f
                                },
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        if (episode.duration != null) {
                            Text(
                                text = formatDuration(episode.duration),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Compact download state indicator: icon or spinner sized to 14dp
                        // so it sits inline with the label text without dominating it.
                        when (actionState) {
                            EpisodeDownloadActionOverride.ON_PHONE -> {
                                Icon(
                                    Icons.Default.PhonelinkRing,
                                    contentDescription = "On Device",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            EpisodeDownloadActionOverride.SAVE_TO_PHONE -> {
                                // Deliberately not DownloadDone: that glyph is a bare
                                // tick over a line, and the played marker a few pixels
                                // to the left is now the row's tick. Two checkmarks
                                // meaning different things in one row is exactly the
                                // confusion this pass is undoing.
                                Icon(
                                    Icons.Default.Cloud,
                                    contentDescription = "Downloaded to Server",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            EpisodeDownloadActionOverride.PHONE_IN_PROGRESS -> {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .semantics { contentDescription = "Downloading to phone" },
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                )
                            }

                            EpisodeDownloadActionOverride.PHONE_FAILED -> {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = "Phone Download Failed",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }

                            EpisodeDownloadActionOverride.SERVER_DOWNLOADING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .semantics { contentDescription = "Downloading on server" },
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    trackColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                                )
                            }

                            EpisodeDownloadActionOverride.SERVER_QUEUED -> {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = "Queued on Server",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            EpisodeDownloadActionOverride.SAVE_TO_SERVER -> {
                                Icon(
                                    Icons.Default.CloudDownload,
                                    contentDescription = "Not on Server",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            EpisodeDownloadActionOverride.RETRY_SERVER -> {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = "Server Download Failed",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                // Expand/collapse chevron aligned to the right edge of the row.
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 2dp playback progress bar at the bottom of the collapsed header row.
            // Only shown when the user has started listening but hasn't finished:
            // once played, a full-width accent bar along the bottom edge reads as a
            // highlight rather than as progress, and it competes with the selected-row
            // border. The tick in the metadata row states played instead.
            // coerceIn guards against server data where position > duration.
            if (!episode.played && episode.play_position_seconds > 0 && episode.duration != null && episode.duration > 0) {
                LinearProgressIndicator(
                    progress = { (episode.play_position_seconds.toFloat() / episode.duration).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // ---- Expandable action panel -------------------------------------
            // Animates in/out using expandVertically/shrinkVertically so the list
            // smoothly reflows without an abrupt jump. Contains the action buttons
            // and episode description (which can itself be tapped to expand/collapse).
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

                    // Action buttons row: Play/Resume, Mark Played, and one download
                    // action button whose label and behaviour depend on actionState.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Show "Resume" with an arrow icon if the user started listening;
                        // show "Play" with a circle icon for unstarted episodes.
                        val hasProgress = episode.play_position_seconds > 0 && !episode.played
                        EpisodeActionButton(
                            icon = if (hasProgress) Icons.Default.PlayArrow else Icons.Default.PlayCircle,
                            label = if (hasProgress) "Resume" else "Play",
                            onClick = onPlay
                        )

                        // Toggle played/unplayed. A bare tick in both directions, tinted
                        // to say which way it goes: accent while played (tap to undo),
                        // muted while unplayed (tap to mark). The ringed glyphs are
                        // reserved for the status markers in the metadata row above —
                        // reusing them on an action button made the button look like a
                        // state readout, which is why it read as "Played" rather than
                        // as something you could press.
                        EpisodeActionButton(
                            icon = Icons.Default.Check,
                            label = if (episode.played) "Mark Unplayed" else "Mark Played",
                            tint = if (episode.played) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onTogglePlayedStatus
                        )

                        // Third button is context-sensitive based on the download state.
                        when (actionState) {
                            EpisodeDownloadActionOverride.ON_PHONE -> {
                                if (onDeleteFromPhone != null) {
                                    EpisodeActionButton(
                                        icon = Icons.Default.Delete,
                                        label = "Delete",
                                        tint = MaterialTheme.colorScheme.error,
                                        onClick = onDeleteFromPhone
                                    )
                                } else {
                                    EpisodeActionButton(
                                        icon = Icons.Default.PhonelinkRing,
                                        label = "On Phone",
                                        tint = MaterialTheme.colorScheme.primary,
                                        onClick = {}
                                    )
                                }
                            }

                            EpisodeDownloadActionOverride.SAVE_TO_PHONE -> {
                                EpisodeActionButton(
                                    icon = Icons.Default.PhoneAndroid,
                                    label = "Save to Phone",
                                    onClick = onDownloadToDevice
                                )
                            }

                            EpisodeDownloadActionOverride.PHONE_IN_PROGRESS -> {
                                EpisodeActionButton(
                                    icon = Icons.Default.PhoneAndroid,
                                    label = "Phone DL…",
                                    tint = MaterialTheme.colorScheme.primary,
                                    onClick = {}
                                )
                            }

                            EpisodeDownloadActionOverride.PHONE_FAILED -> {
                                EpisodeActionButton(
                                    icon = Icons.Default.Error,
                                    label = "Retry",
                                    tint = MaterialTheme.colorScheme.error,
                                    onClick = onDownloadToDevice
                                )
                            }

                            EpisodeDownloadActionOverride.SERVER_DOWNLOADING -> {
                                EpisodeActionButton(
                                    icon = Icons.Default.Download,
                                    label = "Server DL…",
                                    tint = MaterialTheme.colorScheme.primary,
                                    onClick = {}
                                )
                            }

                            EpisodeDownloadActionOverride.SERVER_QUEUED -> {
                                EpisodeActionButton(
                                    icon = Icons.Default.Schedule,
                                    label = "Queued Server",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    onClick = {}
                                )
                            }

                            EpisodeDownloadActionOverride.SAVE_TO_SERVER -> {
                                EpisodeActionButton(
                                    icon = Icons.Default.CloudDownload,
                                    label = "Save to Server",
                                    onClick = onDownloadToServer
                                )
                            }

                            EpisodeDownloadActionOverride.RETRY_SERVER -> {
                                EpisodeActionButton(
                                    icon = Icons.Default.Error,
                                    label = "Retry Server",
                                    tint = MaterialTheme.colorScheme.error,
                                    onClick = onDownloadToServer
                                )
                            }
                        }

                        if (onAddToPlaylist != null && enablePlaylists) {
                            EpisodeActionButton(
                                icon = if (isInPlaylist) Icons.AutoMirrored.Filled.PlaylistAddCheck else Icons.AutoMirrored.Filled.PlaylistAdd,
                                label = if (isInPlaylist) "In Playlist" else "Add to List",
                                tint = if (isInPlaylist) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = onAddToPlaylist
                            )
                        }
                    }

                    // Episode description section — only rendered when description exists.
                    // The HTML from RSS feeds is stripped to plain text via stripHtml().
                    // remember(episode.description) avoids re-running the Html parser on
                    // every recomposition (only re-runs when the description content changes).
                    // The description is initially clamped to 3 lines; tapping it toggles
                    // full expansion without needing a separate "Show more" button.
                    if (!episode.description.isNullOrBlank()) {
                        val plainDesc = remember(episode.description) { stripHtml(episode.description) }
                        var descExpanded by remember { mutableStateOf(false) }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

                        Text(
                            text = plainDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (descExpanded) Int.MAX_VALUE else 3,
                            overflow = if (descExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { descExpanded = !descExpanded }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Stand-in for an episode card whose content is not loaded yet.
 *
 * [showSpinner] is on when the whole screen is waiting for its first data. It is
 * off for the placeholder rows inside a loaded list: a long feed puts a row here
 * for every episode it has not fetched, and a fling past a few hundred of them
 * with a spinner in each is a strobe rather than a hint that anything is coming.
 */
@Composable
fun EpisodeCardSkeleton(showSpinner: Boolean = true) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (showSpinner) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = "Loading" },
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(Modifier.size(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(14.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(10.dp)
                )
            }

            Spacer(Modifier.size(12.dp))
            Box(modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Playback state as one glyph in three fills: an empty ring (not started), a ring
 * filled clockwise from the top (part heard), and a solid disc (played).
 *
 * Drawn rather than assembled from Material glyphs because the set has to be one
 * shape at three fills to read as a scale, and the icon font has no half-filled
 * circle whose metaphor is progress — the near misses are all about brightness or
 * contrast, which is a different idea wearing the same outline.
 *
 * [progress] is the fraction heard and is only consulted when [played] is false;
 * a played episode is a full disc regardless of where its position marker sits.
 */
@Composable
private fun PlaybackStateIcon(
    played: Boolean,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val started = !played && progress > 0f
    val tint = if (played || started) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val description = when {
        played -> "Played"
        started -> "Partly played"
        else -> "Not played"
    }

    Canvas(
        modifier = modifier.semantics { contentDescription = description }
    ) {
        val strokeWidth = 1.5.dp.toPx()
        val diameter = size.minDimension - strokeWidth
        val radius = diameter / 2f

        if (played) {
            drawCircle(color = tint, radius = radius)
            return@Canvas
        }

        if (started) {
            // The wedge is a state indicator, not a gauge, so its sweep is held
            // away from both ends: a thirty-second start still has to look
            // different from untouched, and an episode with two minutes left must
            // not read as finished. The exact fraction is on the progress bar
            // along the bottom of the row, which is where precision belongs.
            drawArc(
                color = tint,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0.12f, 0.9f),
                useCenter = true,
                topLeft = Offset(
                    (size.width - diameter) / 2f,
                    (size.height - diameter) / 2f
                ),
                size = Size(diameter, diameter)
            )
        }
        drawCircle(color = tint, radius = radius, style = Stroke(width = strokeWidth))
    }
}

@Composable
private fun EpisodeActionButton(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}

// Convert an HTML string (from an RSS episode description) to plain text.
// FROM_HTML_MODE_COMPACT preserves block-level line breaks (paragraphs, list items)
// while stripping all markup tags. Consecutive blank lines are collapsed to at most
// one blank line so the description doesn't have excessive vertical whitespace.
fun stripHtml(html: String): String {
    return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT)
        .toString()
        .trim()
        .replace(Regex("\n{3,}"), "\n\n")
}

// Format an episode duration in seconds to a concise human-readable string.
// Episodes shorter than one hour show only minutes (e.g., "42m"); longer episodes
// show hours and remaining minutes (e.g., "1h 23m"). Seconds are omitted because
// podcast durations at that precision add visual noise without meaningful context.
fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

// Format a Unix-epoch millisecond timestamp to a short locale-aware date string
// (e.g., "Apr 25, 2026"). Returns empty string for null or zero (episodes where
// the RSS feed did not supply a publication date).
fun formatDate(publishedAt: Long?): String {
    if (publishedAt == null || publishedAt == 0L) return ""
    return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(publishedAt))
}