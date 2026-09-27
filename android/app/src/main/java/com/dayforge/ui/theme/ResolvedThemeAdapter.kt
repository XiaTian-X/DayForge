package com.dayforge.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.dayforge.domain.appearance.ResolvedTheme

/** All 36 frozen roles are explicit; this never regenerates colors from a theme seed. */
internal fun ResolvedTheme.toComposeColors(): ColorScheme {
    fun color(role: String) = Color(material.getValue(role))
    return lightColorScheme(
        primary = color("primary"), onPrimary = color("on_primary"),
        primaryContainer = color("primary_container"), onPrimaryContainer = color("on_primary_container"),
        inversePrimary = color("inverse_primary"),
        secondary = color("secondary"), onSecondary = color("on_secondary"),
        secondaryContainer = color("secondary_container"), onSecondaryContainer = color("on_secondary_container"),
        tertiary = color("tertiary"), onTertiary = color("on_tertiary"),
        tertiaryContainer = color("tertiary_container"), onTertiaryContainer = color("on_tertiary_container"),
        background = color("background"), onBackground = color("on_background"),
        surface = color("surface"), onSurface = color("on_surface"),
        surfaceVariant = color("surface_variant"), onSurfaceVariant = color("on_surface_variant"),
        surfaceTint = color("surface_tint"), inverseSurface = color("inverse_surface"),
        inverseOnSurface = color("inverse_on_surface"), error = color("error"), onError = color("on_error"),
        errorContainer = color("error_container"), onErrorContainer = color("on_error_container"),
        outline = color("outline"), outlineVariant = color("outline_variant"), scrim = color("scrim"),
        surfaceBright = color("surface_bright"), surfaceDim = color("surface_dim"),
        surfaceContainer = color("surface_container"), surfaceContainerHigh = color("surface_container_high"),
        surfaceContainerHighest = color("surface_container_highest"), surfaceContainerLow = color("surface_container_low"),
        surfaceContainerLowest = color("surface_container_lowest")
    )
}
