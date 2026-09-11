package com.dayforge.domain.service

import androidx.compose.ui.graphics.Color
import com.dayforge.domain.model.CardColorStyle

/**
 * Resolves card colors based on CardColorStyle mode.
 *
 * Per CARD-03~07: Provides color calculation logic for theme-follow mode (15% blend)
 * and personalized mode (direct user color).
 *
 * Usage: CardColorResolver.resolveCardColors(style, userColorHex, primaryContainer, onPrimaryContainer, onPrimary)
 *
 * @see CardColorStyle for mode selection
 */
object CardColorResolver {

    /**
     * Blend ratio for theme-follow mode: 15% user color, 85% theme color.
     */
    const val BLEND_RATIO = 0.15f

    /**
     * Resolved card colors for rendering.
     *
     * @param backgroundColor The card background color
     * @param iconColor The icon tint color
     * @param textColor The primary text color (for name, labels, etc.)
     * @param secondaryTextColor The secondary text color (for description, hints, etc.)
     */
    data class ResolvedCardColors(
        val backgroundColor: Color,
        val iconColor: Color,
        val textColor: Color,
        val secondaryTextColor: Color  // Unified secondary text color
    )

    /**
     * Resolves card colors based on CardColorStyle mode.
     *
     * Per CONTEXT.md decisions:
     * - FOLLOW_THEME: backgroundColor = primaryContainer (theme's container color)
     * - FOLLOW_THEME: textColor = onPrimaryContainer (designed for contrast with primaryContainer)
     * - FOLLOW_THEME: iconColor = userColorHex (user's personal touch on icon)
     * - PERSONALIZED: backgroundColor = userColorHex, iconColor/textColor = dynamic contrast color
     *
     * @param style The card color style (FOLLOW_THEME or PERSONALIZED)
     * @param userColorHex The user's custom color as hex string (e.g., "#FF4CAF50")
     * @param primaryContainer The theme's primaryContainer color (Int ARGB)
     * @param onPrimaryContainer The theme's onPrimaryContainer color (Int ARGB)
     * @param onPrimary The theme's onPrimary color (Int ARGB)
     * @return ResolvedCardColors with backgroundColor, iconColor, textColor
     */
    fun resolveCardColors(
        style: CardColorStyle,
        userColorHex: String,
        primaryContainer: Int,
        onPrimaryContainer: Int,
        onPrimary: Int
    ): ResolvedCardColors {
        val userColorArgb = parseColorSafe(userColorHex)

        return when (style) {
            CardColorStyle.FOLLOW_THEME -> {
                // Use primaryContainer directly for background (Material3 designed color)
                // Use onPrimaryContainer for text (designed for contrast with primaryContainer)
                // Use user's color for icon (personal touch)
                ResolvedCardColors(
                    backgroundColor = Color(primaryContainer),
                    iconColor = Color(userColorArgb),  // Icon uses user's original color
                    textColor = Color(onPrimaryContainer),  // Text uses theme's designed contrast color
                    secondaryTextColor = Color(onPrimaryContainer).copy(alpha = 0.7f)
                )
            }
            CardColorStyle.PERSONALIZED -> {
                // Use user color directly for background, calculate dynamic contrast for text/icon
                val textColor = calculateContrastingColor(userColorArgb)
                val secondaryTextColor = textColor.copy(alpha = 0.7f)
                ResolvedCardColors(
                    backgroundColor = Color(userColorArgb),
                    iconColor = textColor,
                    textColor = textColor,
                    secondaryTextColor = secondaryTextColor
                )
            }
        }
    }

    /**
     * Calculates a contrasting color for text/icons on a given background.
     *
     * Uses luminance formula (WCAG standard) to determine if background is light or dark.
     * - Light background (luminance >= 0.55): Returns dark gray for readability
     * - Dark background (luminance < 0.55): Returns white for readability
     *
     * Threshold of 0.55 ensures gray-ish backgrounds also use dark text for better contrast.
     * Gray (luminance ~0.5) + white text = poor contrast, gray + dark text = good contrast.
     *
     * This ensures text and icons are always visible regardless of user's chosen color.
     *
     * @param colorArgb The background color in ARGB format
     * @return A Color that provides good contrast against the background
     */
    private fun calculateContrastingColor(colorArgb: Int): Color {
        val r = ((colorArgb ushr 16) and 0xFF) / 255.0
        val g = ((colorArgb ushr 8) and 0xFF) / 255.0
        val b = (colorArgb and 0xFF) / 255.0
        // WCAG luminance formula: https://www.w3.org/TR/WCAG20/#contrast-ratiodef
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b

        // Threshold 0.55 ensures gray-ish backgrounds use dark text for better readability
        return if (luminance >= 0.55) {
            Color(0xDE1A1A1A) // Dark gray (87% opacity) for light/gray backgrounds
        } else {
            Color.White // White for truly dark backgrounds
        }
    }

    /**
     * Parses a hex color string to Int (ARGB) with fallback.
     *
     * Handles invalid hex strings gracefully by returning fallback color.
     * Per T-73-01: Validates hex format, returns fallback for invalid input.
     *
     * @param hex The hex color string (e.g., "#FF4CAF50" or "#4CAF50")
     * @param fallback The fallback color to use if parsing fails (default: green)
     * @return The parsed Int (ARGB) or fallback if parsing fails
     */
    fun parseColorSafe(hex: String, fallback: Int = 0xFF4CAF50.toInt()): Int {
        return try {
            parseHexColor(hex)
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }

    private fun parseHexColor(hex: String): Int {
        require(hex.startsWith('#')) { "Color must start with #" }
        val value = hex.drop(1)
        return when (value.length) {
            3 -> {
                val r = value[0].digitToInt(16) * 17
                val g = value[1].digitToInt(16) * 17
                val b = value[2].digitToInt(16) * 17
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            4 -> {
                val a = value[0].digitToInt(16) * 17
                val r = value[1].digitToInt(16) * 17
                val g = value[2].digitToInt(16) * 17
                val b = value[3].digitToInt(16) * 17
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            6 -> (0xFF000000L or value.toLong(16)).toInt()
            8 -> value.toLong(16).toInt()
            else -> throw IllegalArgumentException("Unsupported color length")
        }
    }
}
