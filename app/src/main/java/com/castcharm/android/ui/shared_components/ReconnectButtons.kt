package com.castcharm.android.ui.shared_components

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
import androidx.compose.ui.unit.dp

@Composable
fun ReconnectIconButton(
    onClick: () -> Unit,
    inFlight: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String = "Retry connection",
    icon: ImageVector = Icons.Default.Refresh
) {
    IconButton(
        onClick = onClick,
        enabled = !inFlight,
        modifier = modifier
    ) {
        if (inFlight) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription
            )
        }
    }
}

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
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(if (inFlight) "Reconnecting..." else text)
    }
}