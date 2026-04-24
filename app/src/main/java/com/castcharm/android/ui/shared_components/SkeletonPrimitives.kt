package com.castcharm.android.ui.shared_components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp)
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = shape
            )
    )
}

@Composable
fun SkeletonTextLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
    height: Dp = 12.dp
) {
    SkeletonBlock(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
    )
}

@Composable
fun SkeletonImageBox(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    cornerRadius: Dp = 8.dp,
    showSpinner: Boolean = false
) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(cornerRadius)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.4f),
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
fun SkeletonListRow(
    modifier: Modifier = Modifier,
    showTrailingSpinner: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SkeletonImageBox(size = 48.dp)

        Column(modifier = Modifier.weight(1f)) {
            SkeletonTextLine(widthFraction = 0.82f, height = 12.dp)
            Spacer(Modifier.height(8.dp))
            SkeletonTextLine(widthFraction = 0.45f, height = 10.dp)
        }

        if (showTrailingSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
fun SkeletonArtworkCard(
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(10.dp))
        SkeletonTextLine(widthFraction = 0.9f, height = 16.dp)
        Spacer(Modifier.height(6.dp))
        SkeletonTextLine(widthFraction = 0.65f, height = 12.dp)
    }
}