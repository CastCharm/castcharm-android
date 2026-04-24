package com.castcharm.android.ui.shared_components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.castcharm.android.CastCharmApp
import com.castcharm.android.R

@Composable
fun PlaceholderArtwork(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    cornerRadiusDp: Int = 8,
    placeholderScaleFraction: Float = 0.9f
) {
    val shape = RoundedCornerShape(cornerRadiusDp.dp)
    val normalizedUrl = imageUrl?.takeIf { it.isNotBlank() }

    if (normalizedUrl != null) {
        SubcomposeAsyncImage(
            model = normalizedUrl,
            contentDescription = contentDescription,
            imageLoader = CastCharmApp.imageLoader,
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = contentScale,
            loading = {
                PlaceholderArtworkFallback(
                    modifier = Modifier.fillMaxSize(),
                    cornerRadiusDp = cornerRadiusDp,
                    placeholderScaleFraction = placeholderScaleFraction
                )
            },
            error = {
                PlaceholderArtworkFallback(
                    modifier = Modifier.fillMaxSize(),
                    cornerRadiusDp = cornerRadiusDp,
                    placeholderScaleFraction = placeholderScaleFraction
                )
            }
        )
    } else {
        PlaceholderArtworkFallback(
            modifier = modifier,
            cornerRadiusDp = cornerRadiusDp,
            placeholderScaleFraction = placeholderScaleFraction
        )
    }
}

@Composable
private fun PlaceholderArtworkFallback(
    modifier: Modifier = Modifier,
    cornerRadiusDp: Int = 8,
    placeholderScaleFraction: Float = 0.48f
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadiusDp.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.icon_no_bg),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize(placeholderScaleFraction)
                .sizeIn(minWidth = 20.dp, minHeight = 20.dp)
                .alpha(0.42f),
            colorFilter = ColorFilter.tint(
                MaterialTheme.colorScheme.onSurfaceVariant,
                blendMode = BlendMode.SrcIn
            )
        )
    }
}