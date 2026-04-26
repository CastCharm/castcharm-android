package com.castcharm.android.ui.shared_components

// AppTopBarTitle renders the CastCharm app icon alongside a screen title text,
// used consistently in every screen's top app bar. The icon size is derived
// from the current title text size so it scales proportionally when the user
// changes the in-app font size setting.

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.castcharm.android.R

@Composable
fun AppTopBarTitle(
    text: String,
    maxLines: Int = 1,
    showIcon: Boolean = true,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    // Derive the icon size from the title text size so the icon and text remain
    // proportional regardless of the user's font scale preference. The factor
    // 1.25f makes the icon slightly taller than a capital letter for visual balance.
    val titleStyle = MaterialTheme.typography.titleLarge
    val density = LocalDensity.current
    val iconSize = with(density) { (titleStyle.fontSize * 1.25f).toDp() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        // Render the app icon with a small trailing gap proportional to its size.
        // contentDescription is null because the icon is decorative — the text
        // label already identifies the screen.
        if (showIcon) {
            Image(
                painter = painterResource(id = R.drawable.icon_no_bg),
                contentDescription = null,
                modifier = Modifier.size(iconSize)
            )
            Spacer(Modifier.width(iconSize * 0.35f))
        }

        // Screen title text. Bold weight distinguishes it from body content in
        // the top bar. maxLines/overflow are forwarded so callers can control
        // whether long titles truncate or wrap (e.g., feed name vs. "CastCharm").
        Text(
            text = text,
            style = titleStyle,
            fontWeight = FontWeight.Bold,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}