package com.dayforge.widget.base

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.color.colorProviders
import com.dayforge.domain.appearance.ResolvedTheme

/** Explicitly selected palette, independent of the launcher/system's current night mode. */
internal fun ResolvedTheme.toGlanceColors() = providers(this, this)

/** System-following mode; light and dark selections may belong to different saved themes. */
internal fun glanceThemeColors(light: ResolvedTheme, dark: ResolvedTheme) = run {
    require(!light.dark && dark.dark)
    providers(light, dark)
}

private fun providers(light: ResolvedTheme, dark: ResolvedTheme) = run {
    fun color(role: String) = ColorProvider(Color(light.material.getValue(role)), Color(dark.material.getValue(role)))
    // Glance's Material3 helper regenerates widgetBackground from secondaryContainer tone.
    // Use public colorProviders instead, with the saved surface as the explicit widget backdrop.
    colorProviders(
        primary = color("primary"), onPrimary = color("on_primary"),
        primaryContainer = color("primary_container"), onPrimaryContainer = color("on_primary_container"),
        secondary = color("secondary"), onSecondary = color("on_secondary"),
        secondaryContainer = color("secondary_container"), onSecondaryContainer = color("on_secondary_container"),
        tertiary = color("tertiary"), onTertiary = color("on_tertiary"),
        tertiaryContainer = color("tertiary_container"), onTertiaryContainer = color("on_tertiary_container"),
        error = color("error"), errorContainer = color("error_container"),
        onError = color("on_error"), onErrorContainer = color("on_error_container"),
        background = color("background"), onBackground = color("on_background"),
        surface = color("surface"), onSurface = color("on_surface"),
        surfaceVariant = color("surface_variant"), onSurfaceVariant = color("on_surface_variant"),
        outline = color("outline"), inverseOnSurface = color("inverse_on_surface"),
        inverseSurface = color("inverse_surface"), inversePrimary = color("inverse_primary"),
        widgetBackground = color("surface")
    )
}
