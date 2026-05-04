@file:OptIn(ExperimentalMaterial3Api::class)
package com.castcharm.android.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.models.AddToPlaylistRequest
import com.castcharm.android.data.api.models.CreatePlaylistRequest
import com.castcharm.android.data.api.models.PlaylistOut
import kotlinx.coroutines.launch

@Composable
fun AddToPlaylistSheet(
    episodeId: Int,
    episodeTitle: String,
    onDismiss: () -> Unit,
    onMembershipChanged: (episodeId: Int, isNowInAnyPlaylist: Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var allPlaylists by remember { mutableStateOf<List<PlaylistOut>>(emptyList()) }
    var memberPlaylists by remember { mutableStateOf<List<PlaylistOut>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isAddingNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            if (!CastCharmApp.apiClient.isInitialized) { isLoading = false; return@launch }
            isLoading = true
            errorMessage = null
            try {
                val api = CastCharmApp.apiClient.getApi()
                allPlaylists = api.getPlaylists()
                memberPlaylists = api.getEpisodePlaylists(episodeId)
            } catch (e: Exception) {
                errorMessage = e.localizedMessage
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(episodeId) { reload() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Add to playlist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                text = episodeTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.dp))

            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(16.dp))
                }

                errorMessage != null -> {
                    Text(errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { reload() }) { Text("Retry") }
                }

                else -> {
                    val memberIds = memberPlaylists.map { it.id }.toSet()
                    val customNonMembers = allPlaylists.filter { it.id !in memberIds && it.type == "custom" }

                    if (memberPlaylists.isNotEmpty()) {
                        Text(
                            "Already in",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        memberPlaylists.forEach { pl ->
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            CastCharmApp.apiClient.getApi().removeFromPlaylist(pl.id, episodeId)
                                            memberPlaylists = CastCharmApp.apiClient.getApi().getEpisodePlaylists(episodeId)
                                            onMembershipChanged(episodeId, memberPlaylists.isNotEmpty())
                                        } catch (_: Exception) { }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(pl.name, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                                    Text("Remove", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    if (customNonMembers.isNotEmpty()) {
                        Text(
                            "Add to",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        customNonMembers.forEach { pl ->
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            CastCharmApp.apiClient.getApi().addToPlaylist(pl.id, AddToPlaylistRequest(episodeId))
                                            memberPlaylists = CastCharmApp.apiClient.getApi().getEpisodePlaylists(episodeId)
                                            onMembershipChanged(episodeId, true)
                                        } catch (_: Exception) { }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text(pl.name)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    if (!isAddingNew) {
                        OutlinedButton(
                            onClick = { isAddingNew = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("New Playlist")
                        }
                    } else {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("Playlist name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { isAddingNew = false; newName = "" }) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    val name = newName.trim()
                                    if (name.isBlank()) return@Button
                                    scope.launch {
                                        try {
                                            val api = CastCharmApp.apiClient.getApi()
                                            val created = api.createPlaylist(CreatePlaylistRequest(name = name))
                                            api.addToPlaylist(created.id, AddToPlaylistRequest(episodeId))
                                            allPlaylists = api.getPlaylists()
                                            memberPlaylists = api.getEpisodePlaylists(episodeId)
                                            onMembershipChanged(episodeId, true)
                                            isAddingNew = false
                                            newName = ""
                                        } catch (_: Exception) { }
                                    }
                                },
                                enabled = newName.isNotBlank()
                            ) {
                                Text("Create & Add")
                            }
                        }
                    }
                }
            }
        }
    }
}
