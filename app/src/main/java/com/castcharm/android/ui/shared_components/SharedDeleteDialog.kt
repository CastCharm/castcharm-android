package com.castcharm.android.ui.shared_components

// Confirmation for deleting downloaded files. When a file is also wanted by
// another list on this phone, a third choice removes only this list's claim
// and leaves the file where the other owner can still use it.

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun SharedDeleteDialog(
    title: String,
    body: String,
    shared: Boolean,
    onDeleteEverywhere: () -> Unit,
    onRemoveHere: () -> Unit,
    onDismiss: () -> Unit,
    removeHereLabel: String = "Remove from this list only",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Row {
                if (shared) TextButton(onClick = onRemoveHere) { Text(removeHereLabel) }
                TextButton(onClick = onDeleteEverywhere, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(if (shared) "Delete file" else "Delete")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
