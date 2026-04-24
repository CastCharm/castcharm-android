package com.castcharm.android.ui.shared_components

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
    val titleStyle = MaterialTheme.typography.titleLarge
    val density = LocalDensity.current
    val iconSize = with(density) { (titleStyle.fontSize * 1.25f).toDp() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showIcon) {
            Image(
                painter = painterResource(id = R.drawable.icon_no_bg),
                contentDescription = null,
                modifier = Modifier.size(iconSize)
            )
            Spacer(Modifier.width(iconSize * 0.35f))
        }

        Text(
            text = text,
            style = titleStyle,
            fontWeight = FontWeight.Bold,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}