package com.castcharm.android.ui.shared_components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.Flow

@Composable
fun ConsumeSnackbarMessage(
    message: String?,
    snackbarHostState: SnackbarHostState,
    onConsumed: () -> Unit,
    enabled: Boolean = true
) {
    LaunchedEffect(message, enabled) {
        if (enabled && !message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            onConsumed()
        }
    }
}

@Composable
fun CollectSnackbarEvents(
    events: Flow<String>,
    snackbarHostState: SnackbarHostState
) {
    LaunchedEffect(events) {
        events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
}