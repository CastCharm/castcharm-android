package com.castcharm.android.ui.shared_components

// PlaceholderArtwork is the unified image component used everywhere podcast or
// episode artwork should appear. It wraps Coil's SubcomposeAsyncImage and
// guarantees a visually consistent fallback (the dimmed app icon on a surface-
// variant background) whenever the URL is missing, blank, or fails to load.
//
// Using CastCharmApp.imageLoader (rather than the default Coil singleton) ensures
// that every image request goes through the authenticated OkHttpClient, which
// carries the session cookie. Without this, artwork on cookie-protected servers
// would return 403 and always show the placeholder.

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
    // Treat blank strings the same as null — an empty URL would produce an error
    // from Coil and flash the fallback anyway, so we skip straight to it.
    val normalizedUrl = imageUrl?.takeIf { it.isNotBlank() }

    if (normalizedUrl != null) {
        // SubcomposeAsyncImage lets us compose the loading and error slots inline.
        // Both slots render the same PlaceholderArtworkFallback so the user sees
        // a stable placeholder instead of a layout shift when the image arrives.
        // The auth-aware imageLoader is passed explicitly (see file-level comment).
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
        // No URL at all — render the fallback directly, bypassing Coil entirely.
        PlaceholderArtworkFallback(
            modifier = modifier,
            cornerRadiusDp = cornerRadiusDp,
            placeholderScaleFraction = placeholderScaleFraction
        )
    }
}

// Renders a surfaceVariant box with the app icon centred inside it.
// The icon is tinted to onSurfaceVariant and set to 42% alpha so it reads
// as a neutral placeholder rather than branded content. sizeIn ensures the
// icon stays visible even when the box is very small (e.g., 24dp list icons).
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