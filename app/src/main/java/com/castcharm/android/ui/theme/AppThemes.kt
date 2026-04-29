package com.castcharm.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// All named app themes, translated from the web app's themes.js into Material3 ColorSchemes.
// Each theme maps the same CSS variables used on the web to M3 color roles:
//   --bg       → background
//   --bg-2     → surface
//   --bg-3     → surfaceVariant
//   --border   → outline
//   --border-2 → outlineVariant
//   --primary  → primary
//   --text     → onSurface / onBackground
//   --text-2   → onSurfaceVariant / secondary
//   --chart-2  → tertiary

data class AppThemeEntry(
    val key: String,
    val label: String,
    val isDark: Boolean,
    val primary: Color,
    val surface: Color,
    val colorScheme: ColorScheme
)

private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun onColor(bg: Color): Color =
    if (bg.luminance() > 0.45f) Color(0xFF111111) else Color.White

private val inverseLight = Color(0xFFF1F5F9)
private val inverseDark = Color(0xFF1E293B)

private fun dark(
    primary: Color, bg: Color, bg2: Color, bg3: Color,
    border: Color, border2: Color, text: Color, text2: Color, chart2: Color
): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = onColor(primary),
    primaryContainer = primary.copy(alpha = 0.15f),
    onPrimaryContainer = primary,
    secondary = text2,
    onSecondary = text,
    tertiary = chart2,
    onTertiary = onColor(chart2),
    background = bg,
    onBackground = text,
    surface = bg2,
    onSurface = text,
    surfaceVariant = bg3,
    onSurfaceVariant = text2,
    outline = border,
    outlineVariant = border2,
    error = ErrorRed,
    onError = Color.White,
    inverseSurface = inverseLight,
    inverseOnSurface = inverseDark,
    // Surface container hierarchy: bg (darkest) → bg2 → bg3 → border → border2 (lightest)
    // NavigationBar uses surfaceContainer; Card uses surfaceContainerLow.
    surfaceDim = bg,
    surfaceBright = bg3,
    surfaceContainerLowest = bg,
    surfaceContainerLow = bg2,
    surfaceContainer = bg3,
    surfaceContainerHigh = border,
    surfaceContainerHighest = border2,
)

private fun light(
    primary: Color, bg: Color, bg2: Color, bg3: Color,
    border: Color, border2: Color, text: Color, text2: Color, chart2: Color
): ColorScheme = lightColorScheme(
    primary = primary,
    onPrimary = onColor(primary),
    primaryContainer = primary.copy(alpha = 0.10f),
    onPrimaryContainer = primary,
    secondary = text2,
    onSecondary = text,
    tertiary = chart2,
    onTertiary = onColor(chart2),
    background = bg,
    onBackground = text,
    surface = bg2,
    onSurface = text,
    surfaceVariant = bg3,
    onSurfaceVariant = text2,
    outline = border,
    outlineVariant = border2,
    error = ErrorRed,
    onError = Color.White,
    inverseSurface = inverseDark,
    inverseOnSurface = inverseLight,
    // Surface container hierarchy: bg2 (lightest/purest) → bg → bg3 → border → border2 (most tinted)
    // NavigationBar uses surfaceContainer; Card uses surfaceContainerLow.
    surfaceDim = bg3,
    surfaceBright = bg2,
    surfaceContainerLowest = bg2,
    surfaceContainerLow = bg,
    surfaceContainer = bg3,
    surfaceContainerHigh = border,
    surfaceContainerHighest = border2,
)

// ── Theme catalog (mirrors the order and groupings in themes.js) ─────────────

val THEME_GROUPS: List<Pair<String, List<AppThemeEntry>>> = listOf(

    "Very Dark" to listOf(
        AppThemeEntry("midnight", "Midnight", true, Color(0xFF6366F1), Color(0xFF161B27),
            dark(Color(0xFF6366F1), Color(0xFF0E1117), Color(0xFF161B27), Color(0xFF1E2435),
                Color(0xFF2A3149), Color(0xFF3A4263), Color(0xFFE2E8F0), Color(0xFF94A3B8), Color(0xFF10B981))),

        AppThemeEntry("abyss", "Abyss", true, Color(0xFF22D3EE), Color(0xFF0A0E14),
            dark(Color(0xFF22D3EE), Color(0xFF050709), Color(0xFF0A0E14), Color(0xFF10161E),
                Color(0xFF182030), Color(0xFF222E3D), Color(0xFFDDEEFF), Color(0xFF6898B0), Color(0xFFF97316))),

        AppThemeEntry("galaxy", "Galaxy", true, Color(0xFFD946EF), Color(0xFF0C0820),
            dark(Color(0xFFD946EF), Color(0xFF060310), Color(0xFF0C0820), Color(0xFF140E30),
                Color(0xFF201840), Color(0xFF302458), Color(0xFFF0E8FF), Color(0xFFA888D8), Color(0xFF38BDF8))),

        AppThemeEntry("espresso", "Espresso", true, Color(0xFFCD853F), Color(0xFF221408),
            dark(Color(0xFFCD853F), Color(0xFF180D07), Color(0xFF221408), Color(0xFF2C1C0E),
                Color(0xFF3A2818), Color(0xFF4E3825), Color(0xFFF5E8D8), Color(0xFFC0987A), Color(0xFF38BDF8))),

        AppThemeEntry("lava", "Lava", true, Color(0xFFFF5E00), Color(0xFF1C0800),
            dark(Color(0xFFFF5E00), Color(0xFF110400), Color(0xFF1C0800), Color(0xFF280E00),
                Color(0xFF440F00), Color(0xFF5A1800), Color(0xFFFFF0E0), Color(0xFFD07848), Color(0xFF38BDF8))),

        AppThemeEntry("terminal", "Terminal", true, Color(0xFF00FF41), Color(0xFF071207),
            dark(Color(0xFF00FF41), Color(0xFF030A03), Color(0xFF071207), Color(0xFF0B1A0B),
                Color(0xFF0D2A0D), Color(0xFF153815), Color(0xFFCCFFCC), Color(0xFF66CC66), Color(0xFFF97316))),

        AppThemeEntry("amber", "Amber", true, Color(0xFFFFA500), Color(0xFF150E00),
            dark(Color(0xFFFFA500), Color(0xFF0C0900), Color(0xFF150E00), Color(0xFF1E1500),
                Color(0xFF302000), Color(0xFF403000), Color(0xFFFFE8A0), Color(0xFFCC9838), Color(0xFF60A5FA))),
    ),

    "Dark" to listOf(
        AppThemeEntry("arcane", "Arcane", true, Color(0xFFC9A227), Color(0xFF130A22),
            dark(Color(0xFFC9A227), Color(0xFF0D0618), Color(0xFF130A22), Color(0xFF1A102E),
                Color(0xFF2E1850), Color(0xFF3E2068), Color(0xFFE8DEFF), Color(0xFFB090E0), Color(0xFFA78BFA))),

        AppThemeEntry("psychedelic", "Psychedelic", true, Color(0xFFFF00CC), Color(0xFF150028),
            dark(Color(0xFFFF00CC), Color(0xFF0A0015), Color(0xFF150028), Color(0xFF20003A),
                Color(0xFF600090), Color(0xFF8800CC), Color(0xFFFFFAFF), Color(0xFFDD88FF), Color(0xFF22D3EE))),

        AppThemeEntry("neon_tokyo", "Neon Tokyo", true, Color(0xFFFF2D78), Color(0xFF100E1C),
            dark(Color(0xFFFF2D78), Color(0xFF080610), Color(0xFF100E1C), Color(0xFF181628),
                Color(0xFF2A2244), Color(0xFF382E58), Color(0xFFFFE8F8), Color(0xFFD080B8), Color(0xFF22D3EE))),

        AppThemeEntry("cyberpunk", "Cyberpunk", true, Color(0xFFEFE000), Color(0xFF121212),
            dark(Color(0xFFEFE000), Color(0xFF090909), Color(0xFF121212), Color(0xFF1A1A1A),
                Color(0xFF2A2A2A), Color(0xFF3A3A3A), Color(0xFFFFFFF0), Color(0xFFB0B090), Color(0xFFE879F9))),

        AppThemeEntry("dracula", "Dracula", true, Color(0xFFFF79C6), Color(0xFF282A36),
            dark(Color(0xFFFF79C6), Color(0xFF1E1F29), Color(0xFF282A36), Color(0xFF21222D),
                Color(0xFF44475A), Color(0xFF545770), Color(0xFFF8F8F2), Color(0xFF8090C0), Color(0xFF8BE9FD))),

        AppThemeEntry("forest", "Forest", true, Color(0xFF4ADE80), Color(0xFF162818),
            dark(Color(0xFF4ADE80), Color(0xFF0E1F12), Color(0xFF162818), Color(0xFF1C3220),
                Color(0xFF28482E), Color(0xFF386040), Color(0xFFDCF5E4), Color(0xFF7ABF88), Color(0xFFFB923C))),

        AppThemeEntry("dusk", "Dusk", true, Color(0xFFE879F9), Color(0xFF241E3D),
            dark(Color(0xFFE879F9), Color(0xFF1C1530), Color(0xFF241E3D), Color(0xFF2C254A),
                Color(0xFF3E3265), Color(0xFF524280), Color(0xFFF5E8FF), Color(0xFFC0A0DD), Color(0xFF38BDF8))),

        AppThemeEntry("caramel", "Caramel", true, Color(0xFFFB923C), Color(0xFF301E0A),
            dark(Color(0xFFFB923C), Color(0xFF251808), Color(0xFF301E0A), Color(0xFF3C2710),
                Color(0xFF503822), Color(0xFF664830), Color(0xFFFAEBD7), Color(0xFFD4A870), Color(0xFF60A5FA))),
    ),

    "Medium" to listOf(
        AppThemeEntry("nord", "Nord", true, Color(0xFF88C0D0), Color(0xFF3B4252),
            dark(Color(0xFF88C0D0), Color(0xFF2E3440), Color(0xFF3B4252), Color(0xFF434C5E),
                Color(0xFF4C566A), Color(0xFF5C6880), Color(0xFFECEFF4), Color(0xFFD0DAE8), Color(0xFFEBCB8B))),

        AppThemeEntry("solarized", "Solarized", true, Color(0xFF268BD2), Color(0xFF073642),
            dark(Color(0xFF268BD2), Color(0xFF002B36), Color(0xFF073642), Color(0xFF0E4050),
                Color(0xFF405060), Color(0xFF506878), Color(0xFFEEE8D5), Color(0xFF93A1A1), Color(0xFF2AA198))),

        AppThemeEntry("ocean", "Ocean", true, Color(0xFF38BDF8), Color(0xFF143040),
            dark(Color(0xFF38BDF8), Color(0xFF0D2535), Color(0xFF143040), Color(0xFF1A3C50),
                Color(0xFF264E68), Color(0xFF346280), Color(0xFFE0F4FF), Color(0xFF7ABBD8), Color(0xFFF97316))),

        AppThemeEntry("denim", "Denim", true, Color(0xFF93C5FD), Color(0xFF1E2A52),
            dark(Color(0xFF93C5FD), Color(0xFF162040), Color(0xFF1E2A52), Color(0xFF263462),
                Color(0xFF384880), Color(0xFF4A5898), Color(0xFFE0EEFF), Color(0xFF8AA8D8), Color(0xFF86EFAC))),

        AppThemeEntry("slate", "Slate", true, Color(0xFF60A5FA), Color(0xFF28303C),
            dark(Color(0xFF60A5FA), Color(0xFF1E2530), Color(0xFF28303C), Color(0xFF323C48),
                Color(0xFF424E5E), Color(0xFF546078), Color(0xFFE0E8F4), Color(0xFF90A0B8), Color(0xFFF97316))),

        AppThemeEntry("desert", "Desert", true, Color(0xFFE8C44A), Color(0xFF5A4C35),
            dark(Color(0xFFE8C44A), Color(0xFF4A3C28), Color(0xFF5A4C35), Color(0xFF6A5C42),
                Color(0xFF806848), Color(0xFF9A7C58), Color(0xFFFFF8E0), Color(0xFFD8B870), Color(0xFF7986CB))),
    ),

    "Light" to listOf(
        AppThemeEntry("sage", "Sage", false, Color(0xFF166534), Color(0xFFF0F7EE),
            light(Color(0xFF166534), Color(0xFFE8F0E6), Color(0xFFF0F7EE), Color(0xFFDCE8DA),
                Color(0xFFB8D0B4), Color(0xFF98B894), Color(0xFF1A2A18), Color(0xFF4A6448), Color(0xFFC2410C))),

        AppThemeEntry("parchment", "Parchment", false, Color(0xFFB45309), Color(0xFFFDF6EC),
            light(Color(0xFFB45309), Color(0xFFF4EDE0), Color(0xFFFDF6EC), Color(0xFFEBE0CC),
                Color(0xFFD8C8A8), Color(0xFFC0A880), Color(0xFF2A1E08), Color(0xFF6A5030), Color(0xFF1D4ED8))),

        AppThemeEntry("paper", "Paper", false, Color(0xFF4F46E5), Color(0xFFF8FAFD),
            light(Color(0xFF4F46E5), Color(0xFFEEF2F8), Color(0xFFF8FAFD), Color(0xFFE2E8F4),
                Color(0xFFC8D4E8), Color(0xFFA8B8D8), Color(0xFF1A1F38), Color(0xFF4A5580), Color(0xFF0891B2))),

        AppThemeEntry("ice", "Ice", false, Color(0xFF0369A1), Color(0xFFF4FBFF),
            light(Color(0xFF0369A1), Color(0xFFE8F4FF), Color(0xFFF4FBFF), Color(0xFFD8ECFA),
                Color(0xFFA8D0EE), Color(0xFF80B8E8), Color(0xFF0A1828), Color(0xFF2C5878), Color(0xFFC2410C))),

        AppThemeEntry("coral", "Coral", false, Color(0xFFC2410C), Color(0xFFFFF8F6),
            light(Color(0xFFC2410C), Color(0xFFFFF0EC), Color(0xFFFFF8F6), Color(0xFFFFE8E0),
                Color(0xFFF0C8BC), Color(0xFFE0A898), Color(0xFF1A0A06), Color(0xFF6A3020), Color(0xFF1D4ED8))),

        AppThemeEntry("rose", "Rose", false, Color(0xFFBE185D), Color(0xFFFFF8FA),
            light(Color(0xFFBE185D), Color(0xFFFEF2F5), Color(0xFFFFF8FA), Color(0xFFF5E8EE),
                Color(0xFFE8C0CC), Color(0xFFD8A0B0), Color(0xFF1A0810), Color(0xFF6A3040), Color(0xFF1D4ED8))),

        AppThemeEntry("cotton_candy", "Cotton Candy", false, Color(0xFF9333EA), Color(0xFFFFF8FE),
            light(Color(0xFF9333EA), Color(0xFFFDF4FF), Color(0xFFFFF8FE), Color(0xFFF5E8FF),
                Color(0xFFE8D0F4), Color(0xFFD8B8EC), Color(0xFF1A0828), Color(0xFF6040A0), Color(0xFF0891B2))),
    ),
)

val ALL_NAMED_THEMES: Map<String, AppThemeEntry> =
    THEME_GROUPS.flatMap { it.second }.associateBy { it.key }

fun themeLabelFor(key: String): String = when (key) {
    "system" -> "Follow System"
    "light" -> "Light Mode"
    "dark" -> "Dark Mode"
    else -> ALL_NAMED_THEMES[key]?.label ?: key
}
