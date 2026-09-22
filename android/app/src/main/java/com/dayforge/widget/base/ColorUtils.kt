package com.dayforge.widget.base

import androidx.core.graphics.toColorInt
import androidx.compose.ui.graphics.Color

/**
 * Shared color parsing utility for widgets.
 * Eliminates duplicate parseColor functions across widget classes.
 */
object ColorUtils {

    /**
     * Parse a hex color string to Compose Color, with fallback.
     * Handles invalid hex strings gracefully by returning fallback color.
     *
     * @param hex The hex color string (e.g., "#FF4CAF50")
     * @param fallback The fallback color to use if parsing fails (default: green)
     * @return The parsed Color or fallback if parsing fails
     */
    fun parseColor(hex: String, fallback: Color = Color(0xFF4CAF50)): Color {
        return try {
            Color(hex.toColorInt())
        } catch (e: Exception) {
            fallback
        }
    }
}
