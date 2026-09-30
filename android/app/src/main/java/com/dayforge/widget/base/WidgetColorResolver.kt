package com.dayforge.widget.base

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.toArgb
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.service.CardColorResolver
import com.dayforge.domain.service.DeviceThemeController
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.ui.theme.toComposeColors


/**
 * Resolves widget colors based on CardColorStyle mode and current theme.
 *
 * Widgets cannot access Compose MaterialTheme at runtime (RemoteViews limitation).
 * This class pre-computes colors before widget rendering, using the same logic
 * as the app's CardColorResolver but outputting Int ARGB values for Glance state.
 *
 * Per WIDGET-COLOR-01 and WIDGET-COLOR-06:
 * - FOLLOW_THEME: Widget background equals app card color (primaryContainer)
 * - PERSONALIZED: Widget background equals user's colorHex
 * - System theme changes (light/dark) trigger color recalculation
 *
 * Usage:
 * ```kotlin
 * val resolver = WidgetColorResolver(context, themes)
 * val colors = resolver.resolveWidgetColors(userColorHex)
 * // Store colors.backgroundColorArgb and colors.textColorArgb in Glance state
 * ```
 */
class WidgetColorResolver(
    private val context: Context,
    private val themes: DeviceThemeController
) {
    /**
     * Resolved widget colors for rendering.
     *
     * @param backgroundColorArgb The widget background color as Int ARGB
     * @param textColorArgb The primary text color as Int ARGB
     * @param secondaryTextColorArgb The secondary text color as Int ARGB (with 0.7 alpha)
     * @param iconColorArgb The icon tint color as Int ARGB
     */
    data class ResolvedWidgetColors(
        val backgroundColorArgb: Int,
        val textColorArgb: Int,
        val secondaryTextColorArgb: Int,
        val iconColorArgb: Int
    )

    /**
     * Resolves widget colors based on current preferences and theme.
     *
     * This method:
     * Reads the shared atomic selection and cached complete palettes. Only a changed selection
     * reloads files; timer renders never regenerate colors. CardColorResolver retains object accents.
     *
     * @param userColorHex The user's custom color as hex string (e.g., "#FF4CAF50")
     * @return ResolvedWidgetColors with pre-computed ARGB values
     */
    suspend fun resolveWidgetColors(userColorHex: String): ResolvedWidgetColors {
        val loaded = themes.current()
        val cardColorStyle = when (loaded.saved.selection.cardStyle) {
            DeviceCardStyle.FOLLOW_THEME -> CardColorStyle.FOLLOW_THEME
            DeviceCardStyle.PERSONALIZED -> CardColorStyle.PERSONALIZED
        }
        val colorScheme = loaded.resolve(isSystemInDarkTheme()).toComposeColors()

        val resolvedColors = CardColorResolver.resolveCardColors(
            style = cardColorStyle,
            userColorHex = userColorHex,
            primaryContainer = colorScheme.primaryContainer.toArgb(),
            onPrimaryContainer = colorScheme.onPrimaryContainer.toArgb(),
            onPrimary = colorScheme.onPrimary.toArgb()
        )

        return ResolvedWidgetColors(
            backgroundColorArgb = resolvedColors.backgroundColor.toArgb(),
            textColorArgb = resolvedColors.textColor.toArgb(),
            secondaryTextColorArgb = resolvedColors.secondaryTextColor.toArgb(),
            iconColorArgb = resolvedColors.iconColor.toArgb()
        )
    }

    /**
     * Checks if the system is currently in dark theme.
     *
     * Uses Configuration.uiMode to detect system theme without Compose.
     * This is the standard Android way to check dark mode.
     *
     * @return true if system is in dark theme
     */
    private fun isSystemInDarkTheme(): Boolean {
        val currentNightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES
    }
}
