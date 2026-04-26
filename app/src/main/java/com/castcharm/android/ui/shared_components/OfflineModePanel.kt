package com.castcharm.android.ui.shared_components

// OfflineModePanel is the standard "server unreachable / offline" placeholder shown
// in place of each screen's normal content when the app cannot reach the server.
// It supports an optional primary action button (e.g., "View Downloads") and an
// optional "Try Reconnecting" button that delegates to ReconnectOutlinedButton so
// the reconnect-in-flight spinner state is handled uniformly across all screens.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OfflineModePanel(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "Offline Mode",
    icon: ImageVector = Icons.Default.CloudOff,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    reconnectInFlight: Boolean = false,
    onRetryConnection: (() -> Unit)? = null,
    retryLabel: String = "Try Reconnecting"
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Contextual icon — defaults to CloudOff but callers can pass a different
            // icon (e.g., WifiOff for a connectivity-specific message).
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(16.dp))

            // Bold title (e.g., "Offline Mode" or "Server Unavailable").
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            // Secondary message with context-specific detail about what's unavailable
            // and what the user can do (e.g., "You can still access downloaded episodes").
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Optional primary CTA shown only when the caller provides both a label and
            // a handler — e.g., a "Go to Downloads" button on the Dashboard offline panel.
            if (primaryActionLabel != null && onPrimaryAction != null) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(primaryActionLabel)
                }
            }

            // Optional reconnect button rendered via ReconnectOutlinedButton so the
            // spinner-while-in-flight behaviour is consistent with all other reconnect
            // buttons in the app (top bar icon, dialog button, etc.).
            if (onRetryConnection != null) {
                Spacer(Modifier.height(10.dp))
                ReconnectOutlinedButton(
                    onClick = onRetryConnection,
                    inFlight = reconnectInFlight,
                    text = retryLabel,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}