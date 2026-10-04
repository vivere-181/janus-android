/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Janus navy colour system (icon/): tonal palettes from the icon's navy #1B2C50 and steel blue #6E8BB8 —
// primary = navy (tone 30 light / 80 dark), secondary = steel blue, tertiary = a muted teal, neutrals with a hint of
// blue (navy-tinted in dark mode, like the dark icon). Also in res/values{,-night}/colors_janus.xml for XML screens.

val JanusLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1B4684),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001B3D),
    inversePrimary = Color(0xFFA8C8FC),
    secondary = Color(0xFF505F78),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E3FF),
    onSecondaryContainer = Color(0xFF0A1C31),
    tertiary = Color(0xFF05696F),
    onTertiary = Color(0xFFFEFFFF),
    tertiaryContainer = Color(0xFF9DF0F6),
    onTertiaryContainer = Color(0xFF012022),
    background = Color(0xFFF6F9FF),
    onBackground = Color(0xFF191C21),
    surface = Color(0xFFF6F9FF),
    onSurface = Color(0xFF191C21),
    surfaceVariant = Color(0xFFDAE3F3),
    onSurfaceVariant = Color(0xFF3F4754),
    surfaceTint = Color(0xFF1B4684),
    inverseSurface = Color(0xFF2D3136),
    inverseOnSurface = Color(0xFFEDF1F8),
    outline = Color(0xFF6F7886),
    outlineVariant = Color(0xFFBEC7D7),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF6F9FF),
    surfaceDim = Color(0xFFD6DAE1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4FB),
    surfaceContainer = Color(0xFFEAEEF5),
    surfaceContainerHigh = Color(0xFFE4E8F0),
    surfaceContainerHighest = Color(0xFFDFE3EA),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val JanusDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFA8C8FC),
    onPrimary = Color(0xFF023063),
    primaryContainer = Color(0xFF1B4684),
    onPrimaryContainer = Color(0xFFD3E4FF),
    inversePrimary = Color(0xFF1B4684),
    secondary = Color(0xFFB8C7E4),
    onSecondary = Color(0xFF213148),
    secondaryContainer = Color(0xFF38475F),
    onSecondaryContainer = Color(0xFFD5E3FF),
    tertiary = Color(0xFF81D4DA),
    onTertiary = Color(0xFF02363A),
    tertiaryContainer = Color(0xFF034F54),
    onTertiaryContainer = Color(0xFF9DF0F6),
    background = Color(0xFF0D131F),
    onBackground = Color(0xFFDCE2F3),
    surface = Color(0xFF0D131F),
    onSurface = Color(0xFFDCE2F3),
    surfaceVariant = Color(0xFF3D475B),
    onSurfaceVariant = Color(0xFFBDC7DE),
    surfaceTint = Color(0xFFA8C8FC),
    inverseSurface = Color(0xFFDCE2F3),
    inverseOnSurface = Color(0xFF2A303D),
    outline = Color(0xFF8791A7),
    outlineVariant = Color(0xFF3D475B),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF333946),
    surfaceDim = Color(0xFF0D131F),
    surfaceContainerLowest = Color(0xFF070E1B),
    surfaceContainerLow = Color(0xFF151C27),
    surfaceContainer = Color(0xFF19202C),
    surfaceContainerHigh = Color(0xFF242A37),
    surfaceContainerHighest = Color(0xFF2F3542),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)
