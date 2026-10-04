/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import org.kde.kdeconnect_tp.R

/** Google Sans Flex (variable, bundled) with its optical size matched to the text size. */
private fun googleSans(opticalSize: Float) = FontFamily(
    listOf(400, 500, 600, 700).map { weight ->
        Font(
            R.font.google_sans_flex,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight),
                FontVariation.Setting("opsz", opticalSize.coerceIn(6f, 144f)),
            ),
        )
    }
)

private fun TextStyle.inGoogleSans() = copy(fontFamily = googleSans(fontSize.value))

private val AppTypography = Typography().run {
    Typography(
        displayLarge = displayLarge.inGoogleSans(),
        displayMedium = displayMedium.inGoogleSans(),
        displaySmall = displaySmall.inGoogleSans(),
        headlineLarge = headlineLarge.inGoogleSans(),
        headlineMedium = headlineMedium.inGoogleSans(),
        headlineSmall = headlineSmall.inGoogleSans(),
        titleLarge = titleLarge.inGoogleSans(),
        titleMedium = titleMedium.inGoogleSans(),
        titleSmall = titleSmall.inGoogleSans(),
        bodyLarge = bodyLarge.inGoogleSans(),
        bodyMedium = bodyMedium.inGoogleSans(),
        bodySmall = bodySmall.inGoogleSans(),
        labelLarge = labelLarge.inGoogleSans(),
        labelMedium = labelMedium.inGoogleSans(),
        labelSmall = labelSmall.inGoogleSans(),
        displayLargeEmphasized = displayLargeEmphasized.inGoogleSans(),
        displayMediumEmphasized = displayMediumEmphasized.inGoogleSans(),
        displaySmallEmphasized = displaySmallEmphasized.inGoogleSans(),
        headlineLargeEmphasized = headlineLargeEmphasized.inGoogleSans(),
        headlineMediumEmphasized = headlineMediumEmphasized.inGoogleSans(),
        headlineSmallEmphasized = headlineSmallEmphasized.inGoogleSans(),
        titleLargeEmphasized = titleLargeEmphasized.inGoogleSans(),
        titleMediumEmphasized = titleMediumEmphasized.inGoogleSans(),
        titleSmallEmphasized = titleSmallEmphasized.inGoogleSans(),
        bodyLargeEmphasized = bodyLargeEmphasized.inGoogleSans(),
        bodyMediumEmphasized = bodyMediumEmphasized.inGoogleSans(),
        bodySmallEmphasized = bodySmallEmphasized.inGoogleSans(),
        labelLargeEmphasized = labelLargeEmphasized.inGoogleSans(),
        labelMediumEmphasized = labelMediumEmphasized.inGoogleSans(),
        labelSmallEmphasized = labelSmallEmphasized.inGoogleSans(),
    )
}

/**
 * Screen background and the cards/list rows on it. Like Google's apps, cards are lighter than
 * the background in both modes (Material's default "surface" rows would be darker in dark mode).
 */
@Immutable
data class AppSurfaces(val background: Color, val card: Color)

/** The Janus colours for home-screen widgets (Glance), light and dark. */
val JanusGlanceColors = androidx.glance.material3.ColorProviders(light = JanusLightColors, dark = JanusDarkColors)

val LocalAppSurfaces = staticCompositionLocalOf { AppSurfaces(Color.Unspecified, Color.Unspecified) }

/**
 * Material 3 Expressive theme for our screens, in the style of Google's own apps: the Janus navy colours
 * (JanusColors.kt, the same in light/dark on every phone instead of wallpaper colours), Google Sans Flex,
 * expressive motion.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) JanusDarkColors else JanusLightColors
    val surfaces = if (dark) {
        AppSurfaces(background = colors.surface, card = colors.surfaceContainerHigh)
    } else {
        AppSurfaces(background = colors.surfaceContainer, card = colors.surfaceBright)
    }
    CompositionLocalProvider(LocalAppSurfaces provides surfaces) {
        MaterialExpressiveTheme(
            // Upstream screens use the default Scaffold color (background): make it ours.
            colorScheme = colors.copy(background = surfaces.background),
            typography = AppTypography,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
