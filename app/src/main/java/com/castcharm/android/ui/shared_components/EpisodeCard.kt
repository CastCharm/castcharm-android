package com.castcharm.android.ui.shared_components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhonelinkRing
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Badge
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.castcharm.android.data.db.entities.EpisodeEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class EpisodeDownloadActionOverride {
    ON_PHONE,
    SAVE_TO_PHONE,
    PHONE_IN_PROGRESS,
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
    downloadActionOverride: EpisodeDownloadActionOverride? = null
) {
    val imageUrl = episode.custom_image_url
        ?: episode.episode_image_url
        ?: feedImageUrl
        ?: episode.feed_image_url
        ?: if (baseUrl.isNotBlank()) "${baseUrl}api/feeds/${episode.feed_id}/cover.jpg" else null

    val isAvailableOnServer = episode.status == "downloaded" && episode.local_path == null
    val isServerQueued = episode.local_path == null && episode.status == "queued"
    val isServerDownloading = episode.local_path == null && episode.status == "downloading"
    val isServerFailed = episode.local_path == null && episode.status == "failed"

    val actionState = downloadActionOverride ?: when {
        episode.local_path != null -> EpisodeDownloadActionOverride.ON_PHONE
        isAvailableOnServer -> EpisodeDownloadActionOverride.SAVE_TO_PHONE
        isServerDownloading -> EpisodeDownloadActionOverride.SERVER_DOWNLOADING
        isServerQueued -> EpisodeDownloadActionOverride.SERVER_QUEUED
        isServerFailed -> EpisodeDownloadActionOverride.RETRY_SERVER
        else -> EpisodeDownloadActionOverride.SAVE_TO_SERVER
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onToggleExpand, onLongClick = onLongPress)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlaceholderArtwork(
                    imageUrl = imageUrl,
                    contentDescription = episode.title,
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.Crop,
                    cornerRadiusDp = 6
                )

                Spacer(Modifier.size(12.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
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

                        if (episode.duration != null) {
                            Text(
                                text = formatDuration(episode.duration),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

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
                                Icon(
                                    Icons.Default.DownloadDone,
                                    contentDescription = "Downloaded to Server",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            EpisodeDownloadActionOverride.PHONE_IN_PROGRESS -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                )
                            }

                            EpisodeDownloadActionOverride.SERVER_DOWNLOADING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
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

                        if (episode.play_position_seconds > 0 && !episode.played) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Text("Resume")
                            }
                        }
                    }
                }

                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (episode.play_position_seconds > 0 && episode.duration != null && episode.duration > 0) {
                LinearProgressIndicator(
                    progress = { (episode.play_position_seconds.toFloat() / episode.duration).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val hasProgress = episode.play_position_seconds > 0 && !episode.played
                        EpisodeActionButton(
                            icon = if (hasProgress) Icons.Default.PlayArrow else Icons.Default.PlayCircle,
                            label = if (hasProgress) "Resume" else "Play",
                            onClick = onPlay
                        )

                        EpisodeActionButton(
                            icon = if (episode.played) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            label = if (episode.played) "Played" else "Mark Played",
                            tint = if (episode.played) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onTogglePlayedStatus
                        )

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

                    }

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

@Composable
fun EpisodeCardSkeleton() {
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
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
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

fun stripHtml(html: String): String {
    return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT)
        .toString()
        .trim()
        .replace(Regex("\n{3,}"), "\n\n")
}

fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

fun formatDate(publishedAt: Long?): String {
    if (publishedAt == null || publishedAt == 0L) return ""
    return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(publishedAt))
}