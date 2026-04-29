@file:OptIn(ExperimentalMaterial3Api::class)
// SettingsScreen and SettingsViewModel are co-located in this file (unlike most other screens)
// because the ViewModel is simple enough that splitting files adds no value.
//
// SettingsViewModel manages six settings, all backed by DataStore or live device queries:
//   - quotaGb: max server-side storage limit (from GlobalSettings API)
//   - usedBytes: current phone-side download usage (from StorageManager)
//   - totalDeviceStorageBytes / availableDeviceStorageBytes: raw device storage
//   - themeMode: "system" / "light" / "dark" (DataStore THEME_KEY)
//   - fontScale: 0.85–1.3 font size multiplier (DataStore)
//   - maxConcurrentDownloads: 1–5 parallel download slots (DataStore MAX_CONCURRENT_DOWNLOADS_KEY)
//   - isClearing: true while the clear-all-downloads wipe is in progress
//   - serverVersion: version string from GET /api/status, shown in the Server card
//
// SettingsScreen renders a scrollable Column with:
//   1. Storage usage bar — quota + phone usage + available space
//   2. Theme picker — system / light / dark chips
//   3. Font scale slider — visual size preview
//   4. Max concurrent downloads stepper — +/- buttons
//   5. Clear all downloads — destructive action with confirmation dialog
//   6. Server info card — URL + logout button
//   7. Offline mode panel — replaces most content when offline
package com.castcharm.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castcharm.android.CastCharmApp
import com.castcharm.android.THEME_KEY
import com.castcharm.android.dataStore
import com.castcharm.android.download.DownloadScheduler
import com.castcharm.android.download.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.castcharm.android.ui.shared_components.AppTopBarTitle
import com.castcharm.android.ui.shared_components.OfflineModePanel

val MAX_CONCURRENT_DOWNLOADS_KEY = intPreferencesKey("max_concurrent_downloads")
private const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 2

data class SettingsUiState(
    val quotaGb: Long = 5,
    val usedBytes: Long = 0,
    val totalDeviceStorageBytes: Long = 0,
    val availableDeviceStorageBytes: Long = 0,
    val serverUrl: String = "",
    val serverVersion: String = "",
    val themeMode: String = "system",
    val fontScale: Float = 1.0f,
    val maxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENT_DOWNLOADS,
    val isClearing: Boolean = false
)

class SettingsViewModel(private val storageManager: StorageManager) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val downloadScheduler = DownloadScheduler(CastCharmApp.instance)

    init {
        loadSettings()
    }

    fun reload() = loadSettings()

    private fun loadSettings() {
        viewModelScope.launch {
            val used = storageManager.getTotalUsedBytes()
            val quotaBytes = storageManager.getQuotaBytes()
            val serverUrl = if (CastCharmApp.apiClient.isInitialized) {
                CastCharmApp.apiClient.getBaseUrl()
            } else {
                ""
            }
            val themeMode = CastCharmApp.instance.dataStore.data
                .map { it[THEME_KEY] ?: "system" }
                .first()
            val fontScale = CastCharmApp.instance.dataStore.data
                .map { it[com.castcharm.android.FONT_SCALE_KEY] ?: 1.0f }
                .first()
            val maxConcurrentDownloads = CastCharmApp.instance.dataStore.data
                .map { it[MAX_CONCURRENT_DOWNLOADS_KEY] ?: DEFAULT_MAX_CONCURRENT_DOWNLOADS }
                .first()

            val externalDir = CastCharmApp.instance.getExternalFilesDir(null) ?: CastCharmApp.instance.filesDir
            val totalSpace = externalDir.totalSpace
            val availableSpace = externalDir.usableSpace

            val serverVersion = if (CastCharmApp.apiClient.isInitialized) {
                try { CastCharmApp.apiClient.getApi().getStatus().version } catch (_: Exception) { "" }
            } else {
                ""
            }

            _uiState.value = _uiState.value.copy(
                usedBytes = used,
                quotaGb = quotaBytes / (1024 * 1024 * 1024),
                totalDeviceStorageBytes = totalSpace,
                availableDeviceStorageBytes = availableSpace,
                serverUrl = serverUrl,
                serverVersion = serverVersion,
                themeMode = themeMode,
                fontScale = fontScale,
                maxConcurrentDownloads = maxConcurrentDownloads
            )
        }
    }

    fun updateFontScale(scale: Float) {
        viewModelScope.launch {
            CastCharmApp.instance.dataStore.edit {
                it[com.castcharm.android.FONT_SCALE_KEY] = scale
            }
            _uiState.value = _uiState.value.copy(fontScale = scale)
        }
    }

    fun updateQuota(quotaGb: Long) {
        viewModelScope.launch {
            storageManager.setQuota(quotaGb)
            storageManager.enforceQuota()
            val used = storageManager.getTotalUsedBytes()
            _uiState.value = _uiState.value.copy(quotaGb = quotaGb, usedBytes = used)
        }
    }

    fun updateMaxConcurrentDownloads(value: Int) {
        val clamped = value.coerceIn(1, 6)
        viewModelScope.launch {
            CastCharmApp.instance.dataStore.edit {
                it[MAX_CONCURRENT_DOWNLOADS_KEY] = clamped
            }
            _uiState.value = _uiState.value.copy(maxConcurrentDownloads = clamped)

            // Immediately try to fill any newly opened slots.
            downloadScheduler.kickQueue()
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            CastCharmApp.instance.dataStore.edit { it[THEME_KEY] = mode }
            _uiState.value = _uiState.value.copy(themeMode = mode)
        }
    }

    fun clearAllDownloads() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isClearing = true)
            storageManager.clearAllDownloads()
            _uiState.value = _uiState.value.copy(isClearing = false, usedBytes = 0)
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onChangeServer: () -> Unit = {},
    isOfflineMode: Boolean = false,
    isReconnectInFlight: Boolean = false,
    onTryReconnect: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    var showClearConfirm by remember { mutableStateOf(false) }
    var showChangeServerDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {AppTopBarTitle(text="Settings")}
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("About CastCharm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Version 1.0.0", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Self-hosted podcast manager for your private collection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isOfflineMode) {
                    OfflineModePanel(
                        message = "Offline Mode is active. Downloads, Settings, and playback of files already saved on this device remain available. You can stay offline and continue using downloaded content, or try reconnecting when your server is available again.",
                        modifier = Modifier.fillMaxWidth(),
                        reconnectInFlight = isReconnectInFlight,
                        onRetryConnection = onTryReconnect,
                        retryLabel = "Try reconnecting"
                    )
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }

                        Text(
                            text = uiState.serverUrl.ifBlank { "Not connected" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (uiState.serverVersion.isNotBlank()) {
                            Text(
                                text = "Server version ${uiState.serverVersion}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isOfflineMode) {
                            AssistChip(
                                onClick = { },
                                enabled = false,
                                label = { Text("Currently offline") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.CloudOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }

                        OutlinedButton(
                            onClick = { showChangeServerDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Change Server")
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Palette,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }

                        Text("Theme", style = MaterialTheme.typography.labelLarge)
                        ThemeDropdown(
                            currentKey = uiState.themeMode,
                            onSelect = { viewModel.setThemeMode(it) }
                        )

                        Spacer(Modifier.height(4.dp))

                        Text("Font Size", style = MaterialTheme.typography.labelLarge)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = { viewModel.updateFontScale(uiState.fontScale - 0.1f) },
                                enabled = uiState.fontScale > 0.85f
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Decrease font size")
                            }

                            Text(
                                text = "${(uiState.fontScale * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center
                            )

                            IconButton(
                                onClick = { viewModel.updateFontScale(uiState.fontScale + 0.1f) },
                                enabled = uiState.fontScale < 1.45f
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Increase font size")
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }

                        val total = uiState.totalDeviceStorageBytes
                        val free = uiState.availableDeviceStorageBytes
                        val castCharm = uiState.usedBytes
                        val otherUsed = (total - free - castCharm).coerceAtLeast(0)

                        if (total > 0) {
                            val castCharmFraction = (castCharm.toFloat() / total).coerceIn(0f, 1f)
                            val otherFraction = (otherUsed.toFloat() / total).coerceIn(0f, 1f - castCharmFraction)
                            val primaryColor = MaterialTheme.colorScheme.primary
                            val otherColor = MaterialTheme.colorScheme.onSurfaceVariant
                            val freeColor = MaterialTheme.colorScheme.outline
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(freeColor)
                            ) {
                                if (castCharmFraction > 0f) {
                                    Box(
                                        Modifier
                                            .weight(castCharmFraction)
                                            .fillMaxHeight()
                                            .background(primaryColor)
                                    )
                                }
                                if (otherFraction > 0f) {
                                    Box(
                                        Modifier
                                            .weight(otherFraction)
                                            .fillMaxHeight()
                                            .background(otherColor)
                                    )
                                }
                                val freeFraction = (1f - castCharmFraction - otherFraction).coerceAtLeast(0.01f)
                                Box(
                                    Modifier
                                        .weight(freeFraction)
                                        .fillMaxHeight()
                                        .background(freeColor)
                                )
                            }
                        }

                        LegendRow(
                            color = MaterialTheme.colorScheme.primary,
                            label = "CastCharm Downloads",
                            value = formatBytes(castCharm)
                        )
                        LegendRow(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            label = "Other Apps",
                            value = formatBytes((total - free - castCharm).coerceAtLeast(0))
                        )
                        LegendRow(
                            color = MaterialTheme.colorScheme.outline,
                            label = "Free",
                            value = formatBytes(free)
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        Text(
                            "Download quota: ${uiState.quotaGb} GB",
                            style = MaterialTheme.typography.labelLarge
                        )
                        val maxQuota = (uiState.totalDeviceStorageBytes / (1024 * 1024 * 1024)).coerceAtLeast(10).toFloat()
                        Slider(
                            value = uiState.quotaGb.toFloat(),
                            onValueChange = { viewModel.updateQuota(it.toLong()) },
                            valueRange = 1f..maxQuota,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Download quota: ${uiState.quotaGb} gigabytes" }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        Text(
                            "Concurrent downloads: ${uiState.maxConcurrentDownloads}",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Slider(
                            value = uiState.maxConcurrentDownloads.toFloat(),
                            onValueChange = { viewModel.updateMaxConcurrentDownloads(it.toInt()) },
                            valueRange = 1f..6f,
                            steps = 4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Concurrent downloads: ${uiState.maxConcurrentDownloads}" }
                        )
                        Text(
                            text = "How many episode downloads can run at the same time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedButton(
                            onClick = { showClearConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isClearing,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            if (uiState.isClearing) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .semantics { contentDescription = "Clearing downloads" },
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Clearing...")
                            } else {
                                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Clear all downloads")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all downloads?") },
            text = { Text("This will delete all downloaded episode files from this device. Episodes will remain available for streaming.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.clearAllDownloads(); showClearConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showChangeServerDialog) {
        ChangeServerDialog(
            currentUrl = uiState.serverUrl,
            onConfirm = {
                showChangeServerDialog = false
                onChangeServer()
            },
            onDismiss = { showChangeServerDialog = false }
        )
    }
}

@Composable
private fun LegendRow(color: androidx.compose.ui.graphics.Color, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChangeServerDialog(
    currentUrl: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "You will be logged out and taken to the login screen to connect to a different server.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (currentUrl.isNotBlank()) {
                    Text(
                        "Current: $currentUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Change Server") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ThemeDropdown(currentKey: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = com.castcharm.android.ui.theme.themeLabelFor(currentKey),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )

        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Top three: Follow System / Light Mode / Dark Mode
            listOf(
                "system" to "Follow System",
                "light" to "Light Mode",
                "dark" to "Dark Mode"
            ).forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(key); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Named theme groups
            com.castcharm.android.ui.theme.THEME_GROUPS.forEach { (groupLabel, themes) ->
                // Non-interactive section header
                Text(
                    text = groupLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
                )
                themes.forEach { theme ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Two-dot swatch: surface bg + primary accent
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(theme.surface)
                                )
                                Spacer(Modifier.width(3.dp))
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(theme.primary)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(theme.label)
                            }
                        },
                        onClick = { onSelect(theme.key); expanded = false },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1073741824 -> String.format("%.1f GB", bytes / 1073741824.0)
        bytes >= 1048576 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}