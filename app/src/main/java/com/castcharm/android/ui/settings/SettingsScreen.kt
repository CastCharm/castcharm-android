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
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.net.Uri
import com.castcharm.android.CastCharmApp
import com.castcharm.android.NOTIFY_NEW_EPISODES_KEY
import com.castcharm.android.SKIP_SILENCE_KEY
import com.castcharm.android.THEME_KEY
import com.castcharm.android.WIFI_ONLY_DOWNLOADS_KEY
import com.castcharm.android.data.api.models.AddFeedRequest
import com.castcharm.android.opml.OpmlParser
import com.castcharm.android.data.api.AuthStore
import com.castcharm.android.data.api.models.ApiKeyRenameRequest
import com.castcharm.android.dataStore
import com.castcharm.android.download.DownloadScheduler
import com.castcharm.android.download.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.castcharm.android.BuildConfig
import com.castcharm.android.ui.shared_components.AppTopBarTitle
import com.castcharm.android.ui.shared_components.OfflineModePanel

val MAX_CONCURRENT_DOWNLOADS_KEY = intPreferencesKey("max_concurrent_downloads")
private const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 2
val ENABLE_PLAYLISTS_KEY = booleanPreferencesKey("enable_playlists")
private const val DEFAULT_ENABLE_PLAYLISTS = false

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
    val isClearing: Boolean = false,
    val enablePlaylists: Boolean = DEFAULT_ENABLE_PLAYLISTS,
    val wifiOnlyDownloads: Boolean = false,
    val skipSilence: Boolean = false,
    val notifyNewEpisodes: Boolean = false,
    val opmlImportState: OpmlImportState? = null,
)

// Non-null while an OPML import is running. `total` is the number of feeds
// discovered in the file; `added` counts successful POSTs; `failed` counts
// duplicates or unreachable URLs.
data class OpmlImportState(
    val total: Int,
    val current: Int,
    val added: Int,
    val failed: Int,
    val done: Boolean = false,
)

class SettingsViewModel(private val storageManager: StorageManager) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val downloadScheduler = DownloadScheduler(CastCharmApp.instance)

    // No load here on purpose. The screen's ON_RESUME observer (OnScreenResumed in
    // MainActivity) is the single trigger, so arriving at Settings recalculates the
    // storage figures exactly once instead of twice.
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
            val enablePlaylists = CastCharmApp.instance.dataStore.data
                .map { it[ENABLE_PLAYLISTS_KEY] ?: DEFAULT_ENABLE_PLAYLISTS }
                .first()
            val wifiOnlyDownloads = CastCharmApp.instance.dataStore.data
                .map { it[WIFI_ONLY_DOWNLOADS_KEY] ?: false }
                .first()
            val skipSilence = CastCharmApp.instance.dataStore.data
                .map { it[SKIP_SILENCE_KEY] ?: false }
                .first()
            val notifyNewEpisodes = CastCharmApp.instance.dataStore.data
                .map { it[NOTIFY_NEW_EPISODES_KEY] ?: false }
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
                maxConcurrentDownloads = maxConcurrentDownloads,
                enablePlaylists = enablePlaylists,
                wifiOnlyDownloads = wifiOnlyDownloads,
                skipSilence = skipSilence,
                notifyNewEpisodes = notifyNewEpisodes,
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

    fun setEnablePlaylists(enabled: Boolean) {
        viewModelScope.launch {
            CastCharmApp.instance.dataStore.edit { it[ENABLE_PLAYLISTS_KEY] = enabled }
            _uiState.value = _uiState.value.copy(enablePlaylists = enabled)
        }
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        viewModelScope.launch {
            CastCharmApp.instance.dataStore.edit { it[WIFI_ONLY_DOWNLOADS_KEY] = enabled }
            _uiState.value = _uiState.value.copy(wifiOnlyDownloads = enabled)
        }
    }

    fun setSkipSilence(enabled: Boolean) {
        viewModelScope.launch {
            CastCharmApp.instance.dataStore.edit { it[SKIP_SILENCE_KEY] = enabled }
            _uiState.value = _uiState.value.copy(skipSilence = enabled)
        }
    }

    fun setNotifyNewEpisodes(enabled: Boolean) {
        viewModelScope.launch {
            CastCharmApp.instance.dataStore.edit { it[NOTIFY_NEW_EPISODES_KEY] = enabled }
            _uiState.value = _uiState.value.copy(notifyNewEpisodes = enabled)
        }
    }

    fun importOpml(uri: Uri) {
        viewModelScope.launch {
            val ctx = CastCharmApp.instance
            val urls = try {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    OpmlParser.parse(stream)
                } ?: emptyList()
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    opmlImportState = OpmlImportState(
                        total = 0, current = 0, added = 0, failed = 0, done = true,
                    ),
                )
                return@launch
            }

            if (urls.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    opmlImportState = OpmlImportState(
                        total = 0, current = 0, added = 0, failed = 0, done = true,
                    ),
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                opmlImportState = OpmlImportState(
                    total = urls.size, current = 0, added = 0, failed = 0,
                ),
            )

            var added = 0
            var failed = 0
            urls.forEachIndexed { index, url ->
                _uiState.value = _uiState.value.copy(
                    opmlImportState = OpmlImportState(
                        total = urls.size, current = index + 1, added = added, failed = failed,
                    ),
                )
                val ok = runCatching {
                    CastCharmApp.apiClient.getApi().addFeed(AddFeedRequest(url))
                }.isSuccess
                if (ok) added++ else failed++
            }

            _uiState.value = _uiState.value.copy(
                opmlImportState = OpmlImportState(
                    total = urls.size,
                    current = urls.size,
                    added = added,
                    failed = failed,
                    done = true,
                ),
            )
        }
    }

    fun dismissOpmlImport() {
        _uiState.value = _uiState.value.copy(opmlImportState = null)
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
    val usingFallbackCookie by CastCharmApp.usingFallbackCookieAuth.collectAsState()
    var retryingKeyEnrolment by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // POST_NOTIFICATIONS is a runtime permission on Android 13+. The launcher
    // fires whenever the user flips the toggle on without the permission
    // already granted. If the user denies, we still persist the pref so a
    // future "Retry" or system-settings grant activates without another click.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Notifications are turned off in Android settings. You can enable them there."
                )
            }
        }
    }

    // OPML picker. Accepts any XML-ish MIME type since exports vary in what
    // they self-report. Parsing is defensive so a wrong file at worst yields
    // zero URLs and the "no feeds found" branch of the result dialog.
    val opmlPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importOpml(uri)
    }

    // "This device" identity — everything we know about our own API key. Tracked
    // as local state so a successful rename updates the display without having
    // to refetch. Older installs that predate stored ids/prefixes will simply
    // hide the rename affordance (deviceKeyId == null).
    val deviceKeyId = remember { AuthStore.keyId }
    val devicePrefix = remember { AuthStore.keyPrefix }
    var deviceName by remember { mutableStateOf(AuthStore.keyName) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var renameInFlight by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {AppTopBarTitle(text="Settings")}
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
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
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
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

                // Fallback-cookie warning: the app is logged in but running on a
                // short-lived session cookie because the server refused to issue
                // an API key. Almost always this is because External API access
                // has been switched off server-side; the retry button re-attempts
                // enrolment so the user can fix the cause and confirm without
                // relogging in.
                if (usingFallbackCookie && !isOfflineMode) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Session will expire",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                "This device is signed in with a short-lived session because your server has External API access turned off. " +
                                    "Turn it on under Settings → External API on the server, then tap Retry so this device gets a permanent key. " +
                                    "Otherwise you'll be asked to sign in again soon.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Button(
                                onClick = {
                                    if (retryingKeyEnrolment) return@Button
                                    retryingKeyEnrolment = true
                                    scope.launch {
                                        try {
                                            val ok = CastCharmApp.retryApiKeyEnrolment()
                                            snackbarHostState.showSnackbar(
                                                if (ok) "This device is now enrolled with a permanent key."
                                                else "Still unable to enrol — check that External API is enabled on the server."
                                            )
                                        } finally {
                                            retryingKeyEnrolment = false
                                        }
                                    }
                                },
                                enabled = !retryingKeyEnrolment,
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                if (retryingKeyEnrolment) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Retrying…")
                                } else {
                                    Text("Retry enrolment")
                                }
                            }
                        }
                    }
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

                        // "This device" — the friendly name the server will
                        // show for this phone in its API key list. Hidden on
                        // installs that predate stored id/prefix (upgraded
                        // clients get identity fields on their next re-enrol).
                        if (deviceKeyId != null && !deviceName.isNullOrBlank()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Text(
                                "This device",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = deviceName ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!devicePrefix.isNullOrBlank()) {
                                Text(
                                    text = "Key ${devicePrefix}…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    renameInput = deviceName ?: ""
                                    showRenameDialog = true
                                },
                                enabled = !isOfflineMode,
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Rename this device")
                            }
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
                                Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Features", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }

                        SettingsToggleRow(
                            title = "Playlists",
                            subtitle = "Create and manage custom playlists",
                            checked = uiState.enablePlaylists,
                            onCheckedChange = { viewModel.setEnablePlaylists(it) },
                        )
                        SettingsToggleRow(
                            title = "Wi-Fi-only downloads",
                            subtitle = "Wait for an unmetered network before downloading episodes.",
                            checked = uiState.wifiOnlyDownloads,
                            onCheckedChange = { viewModel.setWifiOnlyDownloads(it) },
                        )
                        SettingsToggleRow(
                            title = "Skip silence",
                            subtitle = "Trim silent gaps during playback for a shorter runtime.",
                            checked = uiState.skipSilence,
                            onCheckedChange = { viewModel.setSkipSilence(it) },
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Notifications",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        SettingsToggleRow(
                            title = "New-episode alerts",
                            subtitle = "Only fires when a background sync finds new episodes on your server.",
                            checked = uiState.notifyNewEpisodes,
                            onCheckedChange = { enabled ->
                                viewModel.setNotifyNewEpisodes(enabled)
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val ctx = CastCharmApp.instance
                                    val granted = ContextCompat.checkSelfPermission(
                                        ctx,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!granted) {
                                        notificationPermissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    }
                                }
                            },
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Podcasts,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Podcasts",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            "Import your subscriptions from an OPML file exported by another podcast app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                opmlPickerLauncher.launch(
                                    arrayOf(
                                        "text/xml",
                                        "application/xml",
                                        "text/x-opml",
                                        "*/*",
                                    )
                                )
                            },
                            enabled = !isOfflineMode && uiState.opmlImportState == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Import feeds from OPML file")
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

    uiState.opmlImportState?.let { state ->
        AlertDialog(
            onDismissRequest = { if (state.done) viewModel.dismissOpmlImport() },
            title = { Text(if (state.done) "Import finished" else "Importing feeds") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        state.done && state.total == 0 -> Text(
                            "No feed URLs were found in that file.",
                        )
                        state.done -> Text(
                            "Added ${state.added} of ${state.total} feeds." +
                                if (state.failed > 0) " ${state.failed} couldn't be added (already present or unreachable)." else ""
                        )
                        else -> {
                            Text("Adding feed ${state.current} of ${state.total}…")
                            LinearProgressIndicator(
                                progress = { state.current.toFloat() / state.total.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (state.done) {
                    TextButton(onClick = { viewModel.dismissOpmlImport() }) { Text("OK") }
                }
            },
            dismissButton = null,
        )
    }

    if (showRenameDialog && deviceKeyId != null) {
        AlertDialog(
            onDismissRequest = { if (!renameInFlight) showRenameDialog = false },
            title = { Text("Rename this device") },
            text = {
                Column {
                    Text(
                        "The name is only shown in the server's API key list — clients aren't affected by it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        singleLine = true,
                        enabled = !renameInFlight,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Device name") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !renameInFlight && renameInput.trim().isNotEmpty(),
                    onClick = {
                        val newName = renameInput.trim()
                        renameInFlight = true
                        scope.launch {
                            val ok = runCatching {
                                CastCharmApp.apiClient.getApi()
                                    .renameApiKey(deviceKeyId, ApiKeyRenameRequest(newName))
                                AuthStore.updateName(CastCharmApp.instance, newName)
                            }.isSuccess
                            renameInFlight = false
                            showRenameDialog = false
                            if (ok) {
                                deviceName = newName
                                snackbarHostState.showSnackbar("Renamed to \"$newName\"")
                            } else {
                                snackbarHostState.showSnackbar(
                                    "Couldn't rename — check that you're online and the server can be reached."
                                )
                            }
                        }
                    },
                ) {
                    if (renameInFlight) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRenameDialog = false },
                    enabled = !renameInFlight,
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
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