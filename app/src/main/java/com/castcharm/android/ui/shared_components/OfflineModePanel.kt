package com.castcharm.android.ui.shared_components

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
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (primaryActionLabel != null && onPrimaryAction != null) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(primaryActionLabel)
                }
            }

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