package com.castcharm.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// CastCharm dark scheme — mirrors the web app's "Midnight" theme
private val CastCharmDarkScheme = darkColorScheme(
    primary = CastCharmPrimary,
    onPrimary = CastCharmOnPrimary,
    primaryContainer = CastCharmPrimary.copy(alpha = 0.2f),
    onPrimaryContainer = CastCharmPrimary,
    secondary = DarkTextSecondary,
    onSecondary = DarkTextPrimary,
    tertiary = WarningOrange,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    outlineVariant = DarkBorder.copy(alpha = 0.6f),
    error = ErrorRed,
    onError = Color.White,
    inverseSurface = LightSurface,
    inverseOnSurface = LightTextPrimary
)

// CastCharm light scheme
private val CastCharmLightScheme = lightColorScheme(
    primary = CastCharmPrimary,
    onPrimary = CastCharmOnPrimary,
    primaryContainer = CastCharmPrimary.copy(alpha = 0.1f),
    onPrimaryContainer = CastCharmPrimary,
    secondary = LightTextSecondary,
    onSecondary = LightTextPrimary,
    tertiary = WarningOrange,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    outlineVariant = LightBorder.copy(alpha = 0.6f),
    error = ErrorRed,
    onError = Color.White,
    inverseSurface = DarkSurface,
    inverseOnSurface = DarkTextPrimary
)

@Composable
fun CastCharmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) CastCharmDarkScheme else CastCharmLightScheme

    val scaledTypography = remember(fontScale) {
        AppTypography.copy(
            displayLarge = AppTypography.displayLarge.copy(fontSize = AppTypography.displayLarge.fontSize * fontScale),
            displayMedium = AppTypography.displayMedium.copy(fontSize = AppTypography.displayMedium.fontSize * fontScale),
            displaySmall = AppTypography.displaySmall.copy(fontSize = AppTypography.displaySmall.fontSize * fontScale),
            headlineLarge = AppTypography.headlineLarge.copy(fontSize = AppTypography.headlineLarge.fontSize * fontScale),
            headlineMedium = AppTypography.headlineMedium.copy(fontSize = AppTypography.headlineMedium.fontSize * fontScale),
            headlineSmall = AppTypography.headlineSmall.copy(fontSize = AppTypography.headlineSmall.fontSize * fontScale),
            titleLarge = AppTypography.titleLarge.copy(fontSize = AppTypography.titleLarge.fontSize * fontScale),
            titleMedium = AppTypography.titleMedium.copy(fontSize = AppTypography.titleMedium.fontSize * fontScale),
            titleSmall = AppTypography.titleSmall.copy(fontSize = AppTypography.titleSmall.fontSize * fontScale),
            bodyLarge = AppTypography.bodyLarge.copy(fontSize = AppTypography.bodyLarge.fontSize * fontScale),
            bodyMedium = AppTypography.bodyMedium.copy(fontSize = AppTypography.bodyMedium.fontSize * fontScale),
            bodySmall = AppTypography.bodySmall.copy(fontSize = AppTypography.bodySmall.fontSize * fontScale),
            labelLarge = AppTypography.labelLarge.copy(fontSize = AppTypography.labelLarge.fontSize * fontScale),
            labelMedium = AppTypography.labelMedium.copy(fontSize = AppTypography.labelMedium.fontSize * fontScale),
            labelSmall = AppTypography.labelSmall.copy(fontSize = AppTypography.labelSmall.fontSize * fontScale),
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = scaledTypography,
        content = content
    )
}
