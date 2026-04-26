package com.castcharm.android.ui.shared_components

// Skeleton loading primitives — surfaceVariant-coloured placeholder shapes
// displayed while data is being fetched. They are composable building blocks
// rather than full-screen skeletons so each screen can assemble the exact
// skeleton layout that matches its real content structure.
//
// None of these components animate (no shimmer). A static muted placeholder
// is sufficient for the app's typical load times and avoids the extra
// complexity of an animation loop. Screens show these during the initial
// local-DB read before server data arrives.

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

// Base primitive: an arbitrary-shaped, surfaceVariant-coloured filled box.
// All other skeleton components delegate to this one.
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

// A horizontal bar that mimics a line of text. widthFraction < 1 lets callers
// create the ragged right-edge that real text has (e.g., 0.82f for a title line,
// 0.45f for a short metadata line below it).
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

// A square box representing a thumbnail or artwork image. showSpinner adds a
// centred CircularProgressIndicator for cases where the image is actively loading
// (e.g., the EpisodeCardSkeleton in EpisodeCard.kt).
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

// A single list-item skeleton: 48dp image box + two text lines (title + subtitle).
// showTrailingSpinner adds a small spinner on the right for lists that are actively
// fetching additional pages. Used in DashboardScreen, FeedListScreen, etc.
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

// A grid-cell skeleton: square 1:1 aspect-ratio image block (podcast artwork)
// with two text-line stubs below it. Used in FeedListScreen's adaptive grid
// while feeds are loading from the server.
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