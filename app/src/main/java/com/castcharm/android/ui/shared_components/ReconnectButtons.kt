package com.castcharm.android.ui.shared_components

// Shared reconnect button components used across multiple screens.
// Both variants disable themselves while the reconnect is in flight (inFlight=true)
// and swap their icon/label for a spinner + "Reconnecting..." text, so the user
// gets consistent visual feedback regardless of which screen they triggered the
// reconnect from. The AppSessionManager drives the inFlight state.

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Compact icon-button variant used in top app bars where space is limited.
// While inFlight, the icon is replaced by a small CircularProgressIndicator
// and the button is disabled to prevent double-tapping.
@Composable
fun ReconnectIconButton(
    onClick: () -> Unit,
    inFlight: Boolean,
    modifier: Modifier = Modifier,
    iconLabel: String = "Retry connection",
    icon: ImageVector = Icons.Default.Refresh
) {
    IconButton(
        onClick = onClick,
        enabled = !inFlight,
        modifier = modifier
    ) {
        if (inFlight) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(18.dp)
                    .semantics { contentDescription = "Reconnecting" },
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = iconLabel
            )
        }
    }
}

// Full-width outlined button variant used inside OfflineModePanel and similar
// card-based contexts. While inFlight, a spinner appears to the left of the
// "Reconnecting..." label. The button is disabled so only one reconnect attempt
// can be in progress at a time.
@Composable
fun ReconnectOutlinedButton(
    onClick: () -> Unit,
    inFlight: Boolean,
    text: String = "Try Reconnecting",
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = !inFlight,
        modifier = modifier
    ) {
        if (inFlight) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(16.dp)
                    .semantics { contentDescription = "Reconnecting" },
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(if (inFlight) "Reconnecting..." else text)
    }
}