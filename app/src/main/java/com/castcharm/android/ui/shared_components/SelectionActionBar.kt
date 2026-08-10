package com.castcharm.android.ui.shared_components

// Contextual action bar shown at the bottom of the screen while multi-select is
// active.
//
// These actions used to be bare IconButtons in the TopAppBar. Icons alone carry
// almost no meaning for destructive or state-changing bulk operations — a plain
// circle versus a circle with a tick is not something anyone can be expected to
// read as "mark unplayed" versus "mark played". Every action here is labelled, and
// sitting at the bottom puts it within thumb reach on a phone, next to where the
// user's hand already is after long-pressing a row.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One labelled action in [SelectionActionBar].
 *
 * [destructive] tints the item with the error colour — bulk delete should not look
 * identical to bulk download.
 */
data class SelectionAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

@Composable
fun SelectionActionBar(actions: List<SelectionAction>) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEach { action ->
                val contentColor: Color = when {
                    !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    action.destructive -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }

                Column(
                    modifier = Modifier
                        // weight(1f) on every item so the labels line up in a even
                        // grid rather than bunching around variable text widths.
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = action.enabled, onClick = action.onClick)
                        .padding(vertical = 8.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = action.icon,
                        // The label right below is the accessible name; repeating it
                        // here would make TalkBack announce every action twice.
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
