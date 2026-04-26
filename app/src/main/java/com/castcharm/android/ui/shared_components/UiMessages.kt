package com.castcharm.android.ui.shared_components

// Utility composables for routing ViewModel messages to the Snackbar host.
// ViewModels expose messages as either a nullable state string (one-shot value
// consumed after display) or a Flow<String> (event channel). These helpers
// bridge both patterns to SnackbarHostState so screens don't duplicate the
// LaunchedEffect boilerplate.

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.Flow

// Displays a one-shot snackbar when the ViewModel emits a non-blank string into
// a nullable state field (e.g., _snackbarMessage: MutableStateFlow<String?>).
// After the snackbar is shown, onConsumed() clears the state to null so the same
// message doesn't re-appear on recomposition. The enabled flag lets callers
// suppress the snackbar in contexts where it would be inappropriate (e.g., when
// an error dialog is already showing).
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

// Collects a SharedFlow<String> event channel and shows each emitted message as
// a snackbar. The LaunchedEffect key is the flow reference itself; as long as the
// flow doesn't change (it won't for a ViewModel-owned SharedFlow), this coroutine
// stays alive for the screen's entire lifetime and handles every event in order.
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