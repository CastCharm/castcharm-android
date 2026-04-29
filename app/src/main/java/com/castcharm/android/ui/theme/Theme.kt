package com.castcharm.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// This file defines CastCharmTheme, the root Compose theme composable.
// It wires together the color scheme (dark or light), the font-scale-adjusted
// typography, and the system bar colors. All screens must be wrapped in
// CastCharmTheme so they receive consistent Material3 tokens.
//
// Two static color schemes are defined here (dark and light). Dynamic color
// (Material You / monet) is intentionally NOT used; the fixed palette keeps the
// app visually consistent with the web server UI across all Android versions.
//
// The fontScale parameter comes from the user's in-app font-size preference
// (stored in DataStore and read by MainActivity). It multiplies every sp value
// in AppTypography so a single slider controls the entire text hierarchy.

// ---- Dark color scheme — mirrors the web app's "Midnight" theme -------------
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
    inverseOnSurface = LightTextPrimary,
    surfaceDim = DarkBackground,
    surfaceBright = DarkSurfaceVariant,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceVariant,
    surfaceContainerHigh = DarkBorder,
    surfaceContainerHighest = DarkBorder,
)

// ---- Light color scheme -----------------------------------------------------
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
    inverseOnSurface = DarkTextPrimary,
    surfaceDim = LightSurfaceVariant,
    surfaceBright = LightSurface,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightBackground,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = LightBorder,
    surfaceContainerHighest = LightBorder,
)

@Composable
fun CastCharmTheme(
    themeKey: String = "system",
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val systemIsDark = isSystemInDarkTheme()
    val named = ALL_NAMED_THEMES[themeKey]
    val darkTheme: Boolean
    val colorScheme: ColorScheme
    when {
        named != null -> {
            darkTheme = named.isDark
            colorScheme = named.colorScheme
        }
        themeKey == "light" -> { darkTheme = false; colorScheme = CastCharmLightScheme }
        themeKey == "dark"  -> { darkTheme = true;  colorScheme = CastCharmDarkScheme  }
        else                -> { darkTheme = systemIsDark; colorScheme = if (systemIsDark) CastCharmDarkScheme else CastCharmLightScheme }
    }

    // Build a scaled copy of AppTypography. Using remember(fontScale) avoids
    // rebuilding the Typography object on every recomposition — it is only
    // recreated when the user actually changes the font size setting.
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
    // SideEffect runs after every successful composition. The isInEditMode guard
    // prevents this from crashing in the layout editor, which does not have a
    // real Activity window.
    if (!view.isInEditMode) {
        SideEffect {
            // Color the system status bar and navigation bar to match the theme,
            // then configure icon contrast (light icons on dark, dark icons on light)
            // so they remain readable against the themed background.
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    // Apply the resolved color scheme and scaled typography to all descendant
    // composables via the Material3 CompositionLocal providers.
    MaterialTheme(
        colorScheme = colorScheme,
        typography = scaledTypography,
        content = content
    )
}
