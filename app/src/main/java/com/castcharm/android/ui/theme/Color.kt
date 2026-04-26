package com.castcharm.android.ui.theme

import androidx.compose.ui.graphics.Color

// This file defines all color constants used by CastCharmTheme. Colors are split
// into four groups: brand/primary, dark-theme surfaces + text, light-theme surfaces
// + text, and semantic/status colors. The dark palette mirrors the web app's
// "Midnight" theme so the Android app feels visually consistent with the browser UI.

// ---- Brand / primary --------------------------------------------------------
// Indigo-600 used for interactive controls, active indicators, and primary actions.
// CastCharmPrimaryDark is used on components that need a slightly deeper shade
// (e.g., ripple overlays or pressed states).
val CastCharmPrimary = Color(0xFF6366F1)       // Indigo primary
val CastCharmPrimaryDark = Color(0xFF4F51CC)
val CastCharmOnPrimary = Color(0xFFFFFFFF)

// ---- Dark theme surfaces (matching web app Midnight theme) ------------------
// Layered from darkest (background canvas) to progressively lighter surface tiers.
// DarkBackground is the deepest layer (behind all cards/surfaces).
// DarkSurface is the main card/panel background.
// DarkSurfaceVariant is used for input fields, chip backgrounds, and inset areas.
// DarkBorder is the subtle stroke color used on dividers and card outlines.
val DarkBackground = Color(0xFF0E1117)
val DarkSurface = Color(0xFF161B27)
val DarkSurfaceVariant = Color(0xFF1E2435)
val DarkBorder = Color(0xFF2A3149)

// ---- Dark theme text --------------------------------------------------------
// Three-tier text hierarchy on dark backgrounds.
// Primary: near-white, used for headings and primary content.
// Secondary: mid-gray, used for metadata labels and secondary descriptions.
// Tertiary: dim gray, used for timestamps, counts, and placeholder text.
val DarkTextPrimary = Color(0xFFE2E8F0)
val DarkTextSecondary = Color(0xFF94A3B8)
val DarkTextTertiary = Color(0xFF64748B)

// ---- Light theme surfaces ---------------------------------------------------
// Mirrors the dark surface hierarchy but for the light color scheme.
// LightBackground is the page canvas; LightSurface is cards/panels;
// LightSurfaceVariant is input and inset areas; LightBorder is dividers/outlines.
val LightBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF1F5F9)
val LightBorder = Color(0xFFE2E8F0)

// ---- Light theme text -------------------------------------------------------
// Three-tier hierarchy on light backgrounds, same conceptual roles as dark tier.
val LightTextPrimary = Color(0xFF0F172A)
val LightTextSecondary = Color(0xFF475569)
val LightTextTertiary = Color(0xFF94A3B8)

// ---- Semantic / status colors (shared across both themes) -------------------
// Used for status indicators, error states, and informational badges.
// These are intentionally the same in both light and dark themes so that
// status meanings remain unambiguous regardless of the active color scheme.
val SuccessGreen = Color(0xFF10B981)
val ErrorRed = Color(0xFFEF4444)
val WarningOrange = Color(0xFFF59E0B)
val InfoBlue = Color(0xFF3B82F6)
