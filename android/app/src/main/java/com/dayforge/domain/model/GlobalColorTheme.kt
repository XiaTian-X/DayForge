package com.dayforge.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents a global color theme with a single seed color for Material3 ColorScheme generation.
 *
 * All color fields are optional. If not specified, colors are auto-generated from seedColor
 * using Material3 tonal palette algorithm. This allows:
 * - Simple themes: only seedColor (auto-generate all colors)
 * - Full customization: specify all 25 color fields
 * - Partial customization: specify some colors, others auto-generated
 *
 * @param id Unique identifier for the theme (lowercase, e.g., "ocean")
 * @param name Display name
 * @param seedColor HEX color string (#RRGGBB format) for ColorScheme generation
 * @param suitableForLight Whether this theme is suitable for light mode UI
 * @param suitableForDark Whether this theme is suitable for dark mode UI
 * @param isDefault Whether this is a built-in default theme (cannot be deleted)
 * @param isCustom Whether this is a user-created custom theme
 */
@Serializable
data class GlobalColorTheme(
    val id: String,
    val name: String,
    val seedColor: String,
    val suitableForLight: Boolean,
    val suitableForDark: Boolean,
    val isDefault: Boolean = false,
    val isCustom: Boolean = false,

    // ========== Primary colors (5) ==========
    val primary: String? = null,
    val onPrimary: String? = null,
    val primaryContainer: String? = null,
    val onPrimaryContainer: String? = null,
    val inversePrimary: String? = null,

    // ========== Secondary colors (4) ==========
    val secondary: String? = null,
    val onSecondary: String? = null,
    val secondaryContainer: String? = null,
    val onSecondaryContainer: String? = null,

    // ========== Tertiary colors (4) ==========
    val tertiary: String? = null,
    val onTertiary: String? = null,
    val tertiaryContainer: String? = null,
    val onTertiaryContainer: String? = null,

    // ========== Error colors (4) ==========
    val error: String? = null,
    val onError: String? = null,
    val errorContainer: String? = null,
    val onErrorContainer: String? = null,

    // ========== Background colors (2) ==========
    val background: String? = null,
    val onBackground: String? = null,

    // ========== Surface colors (4) ==========
    val surface: String? = null,
    val onSurface: String? = null,
    val surfaceVariant: String? = null,
    val onSurfaceVariant: String? = null,

    // ========== Outline colors (2) ==========
    val outline: String? = null,
    val outlineVariant: String? = null,

    // ========== Inverse colors (2) ==========
    val inverseSurface: String? = null,
    val inverseOnSurface: String? = null
) {
    /**
     * Check if this theme has any custom colors defined.
     */
    fun hasCustomColors(): Boolean {
        return primary != null || onPrimary != null || primaryContainer != null || onPrimaryContainer != null ||
                inversePrimary != null ||
                secondary != null || onSecondary != null || secondaryContainer != null || onSecondaryContainer != null ||
                tertiary != null || onTertiary != null || tertiaryContainer != null || onTertiaryContainer != null ||
                error != null || onError != null || errorContainer != null || onErrorContainer != null ||
                background != null || onBackground != null ||
                surface != null || onSurface != null || surfaceVariant != null || onSurfaceVariant != null ||
                outline != null || outlineVariant != null ||
                inverseSurface != null || inverseOnSurface != null
    }
}