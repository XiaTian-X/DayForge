package com.dayforge.widget.base

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import com.dayforge.data.local.PreferencesManager
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.service.CardColorResolver
import com.dayforge.domain.service.ThemeManager
import com.dayforge.ui.theme.ColorSchemeGenerator
import kotlinx.coroutines.flow.first

private const val TAG = "WidgetColorResolver"

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
 * val resolver = WidgetColorResolver(context, themeManager, preferencesManager)
 * val colors = resolver.resolveWidgetColors(userColorHex)
 * // Store colors.backgroundColorArgb and colors.textColorArgb in Glance state
 * ```
 */
class WidgetColorResolver(
    private val context: Context,
    private val themeManager: ThemeManager,
    private val preferencesManager: PreferencesManager
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
     * 1. Reads cardColorStyle preference (follow_theme or personalized)
     * 2. Determines current theme mode (light/dark/system)
     * 3. Gets the appropriate GlobalColorTheme
     * 4. Generates ColorScheme for the current mode
     * 5. Uses CardColorResolver logic to calculate final colors
     * 6. Returns Int ARGB values for Glance state storage
     *
     * @param userColorHex The user's custom color as hex string (e.g., "#FF4CAF50")
     * @return ResolvedWidgetColors with pre-computed ARGB values
     */
    suspend fun resolveWidgetColors(userColorHex: String): ResolvedWidgetColors {
        // 1. Read cardColorStyle preference
        val cardColorStyleStr = preferencesManager.cardColorStyle.first()
        val cardColorStyle = CardColorStyle.fromStringOrDefault(cardColorStyleStr)

        // 2. Determine current theme mode
        val themeMode = preferencesManager.themeMode.first()
        val isSystemInDarkTheme = isSystemInDarkTheme()
        val useDarkTheme = when (themeMode) {
            "light" -> false
            "dark" -> true
            else -> isSystemInDarkTheme  // System default (null or other)
        }

        // 3. Get the appropriate theme ID
        val themeId = if (useDarkTheme) {
            preferencesManager.darkColorThemeId.first()
        } else {
            preferencesManager.lightColorThemeId.first()
        }

        // 4. Get GlobalColorTheme from ThemeManager
        val globalColorTheme = themeManager.getById(themeId)

        // 5. Generate ColorScheme for the current mode
        val colorScheme = if (useDarkTheme) {
            ColorSchemeGenerator.generateDarkColorScheme(globalColorTheme)
        } else {
            ColorSchemeGenerator.generateLightColorScheme(globalColorTheme)
        }

        // 6. Use CardColorResolver logic to calculate final colors
        val resolvedColors = CardColorResolver.resolveCardColors(
            style = cardColorStyle,
            userColorHex = userColorHex,
            primaryContainer = colorScheme.primaryContainer.toArgb(),
            onPrimaryContainer = colorScheme.onPrimaryContainer.toArgb(),
            onPrimary = colorScheme.onPrimary.toArgb()
        )

        Log.d(TAG, "resolveWidgetColors: style=$cardColorStyle, themeId=$themeId, darkMode=$useDarkTheme, " +
                "bg=${resolvedColors.backgroundColor}, text=${resolvedColors.textColor}")

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