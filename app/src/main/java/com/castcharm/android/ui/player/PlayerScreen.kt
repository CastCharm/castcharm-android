@file:OptIn(ExperimentalMaterial3Api::class)
// PlayerScreen is a full-screen player launched by tapping the MiniPlayerBar.
// It shows the episode artwork, title, feed, playback controls (back 30s, play/pause,
// forward 30s), a scrubber with position and duration, a speed picker, sleep timer,
// and a "Mark as played" button.
//
// State comes from two sources:
//   - PlayerViewModel.uiState: episode metadata, feed, played state, sleep timer
//   - PlayerController.playbackState (via ViewModel): position, duration, isPlaying
//
// justMarkedPlayed: when the user taps "Mark as played", the screen auto-closes
// via a LaunchedEffect watching this flag. consumeJustMarkedPlayed() clears it.
//
// The scrubber uses the live positionMs from PlaybackUiState and seeks on
// onValueChangeFinished rather than on every drag event to avoid flooding ExoPlayer.
package com.castcharm.android.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.castcharm.android.CastCharmApp

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = viewModel(key = "player_screen") {
        PlayerViewModel()
    },
    onClose: () -> Unit = {},
    onStop: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val playbackUiState by CastCharmApp.playerController.playbackState.collectAsState()

    LaunchedEffect(uiState.justMarkedPlayed) {
        if (uiState.justMarkedPlayed) {
            onStop()
            viewModel.consumeJustMarkedPlayed()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        when {
            uiState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            uiState.episode == null -> {
                NoActivePlaybackContent(
                    onClose = onClose
                )
            }

            else -> {
                val episode = uiState.episode!!
                val baseUrl = CastCharmApp.apiClient.getBaseUrl()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize")
                        }

                        if (uiState.sleepTimerRemainingMs > 0) {
                            Text(
                                text = "Sleep ${formatTime(uiState.sleepTimerRemainingMs / 1000)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (episode.played) {
                            Badge { Text("Played") }
                        } else {
                            Spacer(Modifier.width(1.dp))
                        }
                    }

                    if (CastCharmApp.isOfflineMode) {
                        Spacer(Modifier.height(12.dp))
                        OfflinePlayerBanner()
                    }

                    Spacer(Modifier.height(24.dp))

                    val imageUrl = episode.custom_image_url
                        ?: episode.episode_image_url
                        ?: episode.feed_image_url
                        ?: "${baseUrl}api/feeds/${episode.feed_id}/cover.jpg"

                    AsyncImage(
                        model = imageUrl,
                        contentDescription = episode.title,
                        imageLoader = CastCharmApp.imageLoader,
                        modifier = Modifier
                            .size(280.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(Modifier.height(32.dp))

                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (uiState.feed != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = uiState.feed!!.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(Modifier.height(32.dp))

                    SeekBarSection(
                        currentPosition = uiState.currentPosition,
                        duration = uiState.duration,
                        onSeek = { viewModel.seekTo(it) }
                    )

                    Spacer(Modifier.height(16.dp))

                    PlaybackControls(
                        isPlaying = uiState.isPlaying,
                        isBuffering = playbackUiState.isBuffering,
                        onPlayPause = { viewModel.togglePlayPause() },
                        onSkipBack = { viewModel.skipBackward() },
                        onSkipForward = { viewModel.skipForward() }
                    )

                    Spacer(Modifier.height(24.dp))

                    SecondaryControls(
                        currentSpeed = uiState.playbackSpeed,
                        onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                        isPlayed = episode.played,
                        onMarkPlayed = { viewModel.markPlayed() },
                        sleepTimerRemainingMs = uiState.sleepTimerRemainingMs,
                        onSleepTimer = { viewModel.startSleepTimer(it) },
                        onStop = {
                            viewModel.stop()
                            onStop()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflinePlayerBanner() {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Offline Mode is active. Only episodes saved on this device can play.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoActivePlaybackContent(
    onClose: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close")
            }
        }

        Spacer(Modifier.weight(1f))

        Icon(
            imageVector = Icons.Default.Podcasts,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Nothing is playing",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Start an episode from Home, Podcasts, Downloads, or the mini player.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.weight(1f))
    }
}

@Composable
fun SeekBarSection(
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit = {}
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = if (isSeeking) {
                seekPosition
            } else if (duration > 0) {
                currentPosition.toFloat() / duration
            } else {
                0f
            },
            onValueChange = { newValue ->
                isSeeking = true
                seekPosition = newValue
            },
            onValueChangeFinished = {
                val newPosition = (seekPosition * duration).toLong()
                onSeek(newPosition)
                isSeeking = false
            },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(currentPosition / 1000),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (duration > 0) {
                    "-${formatTime((duration - currentPosition) / 1000)}"
                } else {
                    "--:--"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPause: () -> Unit = {},
    onSkipBack: () -> Unit = {},
    onSkipForward: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = onSkipBack,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    Icons.Default.Replay30,
                    contentDescription = "Skip back 30s",
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(Modifier.width(24.dp))

        Box(
            modifier = Modifier.size(72.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp
                )
            } else {
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }

        Spacer(Modifier.width(24.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = onSkipForward,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    Icons.Default.Forward30,
                    contentDescription = "Skip forward 30s",
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
fun SecondaryControls(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit = {},
    isPlayed: Boolean,
    onMarkPlayed: () -> Unit = {},
    sleepTimerRemainingMs: Long = 0L,
    onSleepTimer: (Int) -> Unit = {},
    onStop: () -> Unit = {}
) {
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showSleepMenu by remember { mutableStateOf(false) }

    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    val sleepOptions = listOf(
        0 to "Off",
        5 to "5 min",
        10 to "10 min",
        15 to "15 min",
        30 to "30 min",
        60 to "60 min"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            OutlinedButton(
                onClick = { showSpeedMenu = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("${formatSpeed(currentSpeed)}x", style = MaterialTheme.typography.labelLarge)
            }

            DropdownMenu(
                expanded = showSpeedMenu,
                onDismissRequest = { showSpeedMenu = false }
            ) {
                speeds.forEach { speed ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${formatSpeed(speed)}x",
                                fontWeight = if (speed == currentSpeed) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onSpeedChange(speed)
                            showSpeedMenu = false
                        },
                        leadingIcon = {
                            if (speed == currentSpeed) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
            }
        }

        Box {
            val sleepTimerActive = sleepTimerRemainingMs > 0L
            OutlinedButton(
                onClick = { showSleepMenu = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.Default.Bedtime,
                    contentDescription = "Sleep timer",
                    modifier = Modifier.size(18.dp),
                    tint = if (sleepTimerActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (sleepTimerActive) formatTime(sleepTimerRemainingMs / 1000) else "Sleep",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sleepTimerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }

            DropdownMenu(
                expanded = showSleepMenu,
                onDismissRequest = { showSleepMenu = false }
            ) {
                sleepOptions.forEach { (minutes, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onSleepTimer(minutes)
                            showSleepMenu = false
                        }
                    )
                }
            }
        }

        if (!isPlayed) {
            OutlinedButton(
                onClick = onMarkPlayed,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Mark played",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Mark Played", style = MaterialTheme.typography.labelLarge)
            }
        }

        OutlinedButton(
            onClick = onStop,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Icon(
                Icons.Default.Stop,
                contentDescription = "Stop",
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text("Stop", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun formatSpeed(speed: Float): String {
    return if (speed == speed.toLong().toFloat()) {
        speed.toLong().toString()
    } else {
        String.format("%.2f", speed).trimEnd('0').trimEnd('.')
    }
}

fun formatTime(seconds: Long): String {
    if (seconds < 0) return "0:00"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "$hours:${String.format("%02d", minutes)}:${String.format("%02d", secs)}"
        else -> "${minutes}:${String.format("%02d", secs)}"
    }
}