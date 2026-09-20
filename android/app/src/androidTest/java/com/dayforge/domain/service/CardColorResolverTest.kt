package com.dayforge.domain.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

import com.dayforge.domain.model.CardColorStyle
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class CardColorResolverTest {

    // Test colors (Int ARGB format)
    private val primaryContainer = 0xFFFFDAD4.toInt()  // Light pink theme container
    private val onPrimaryContainer = 0xFF410002.toInt()  // Dark red for text on container (Material3 designed)
    private val onPrimary = 0xFFFFFFFF.toInt()  // White for text on primary
    private val userColorHex = "#FF4CAF50"  // Green (with full ARGB format)
    private val userColorArgb = 0xFF4CAF50.toInt()

    // ========== FOLLOW_THEME mode tests ==========

    @Test
    fun resolveCardColors_FOLLOW_THEME_backgroundIsPrimaryContainer() {
        val result = CardColorResolver.resolveCardColors(
            style = CardColorStyle.FOLLOW_THEME,
            userColorHex = userColorHex,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            onPrimary = onPrimary
        )

        // Background should be primaryContainer directly (Material3 designed color)
        assertEquals(
            "FOLLOW_THEME background should be primaryContainer",
            primaryContainer,
            result.backgroundColor.toArgb()
        )
    }

    @Test
    fun resolveCardColors_FOLLOW_THEME_iconColorIsUserColorHex() {
        val result = CardColorResolver.resolveCardColors(
            style = CardColorStyle.FOLLOW_THEME,
            userColorHex = userColorHex,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            onPrimary = onPrimary
        )

        // Icon color should be user's original color (personal touch)
        assertEquals(
            "FOLLOW_THEME icon color should be user's original colorHex",
            userColorArgb,
            result.iconColor.toArgb()
        )
    }

    @Test
    fun resolveCardColors_FOLLOW_THEME_textColorIsOnPrimaryContainer() {
        val result = CardColorResolver.resolveCardColors(
            style = CardColorStyle.FOLLOW_THEME,
            userColorHex = userColorHex,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            onPrimary = onPrimary
        )

        // Text color should be onPrimaryContainer (Material3 designed for contrast)
        assertEquals(
            "FOLLOW_THEME text color should be onPrimaryContainer",
            onPrimaryContainer,
            result.textColor.toArgb()
        )
    }

    @Test
    fun resolveCardColors_FOLLOW_THEME_secondaryTextColorHasReducedAlpha() {
        val result = CardColorResolver.resolveCardColors(
            style = CardColorStyle.FOLLOW_THEME,
            userColorHex = userColorHex,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            onPrimary = onPrimary
        )

        // Secondary text color should be onPrimaryContainer with 0.7 alpha
        assertEquals(
            "FOLLOW_THEME secondary text color should have 0.7 alpha",
            0.7f,
            result.secondaryTextColor.alpha,
            0.01f
        )
        assertEquals(
            "FOLLOW_THEME secondary text color should be based on onPrimaryContainer",
            onPrimaryContainer and 0x00FFFFFF,
            result.secondaryTextColor.toArgb() and 0x00FFFFFF
        )
    }

    @Test
    fun resolveCardColors_FOLLOW_THEME_ensuresMaterial3Contrast() {
        // Test with a blue theme (like Ocean theme)
        val bluePrimaryContainer = 0xFFD0E4FF.toInt()  // Light blue container
        val blueOnPrimaryContainer = 0xFF001D38.toInt()  // Dark blue text (Material3 designed)

        val result = CardColorResolver.resolveCardColors(
            style = CardColorStyle.FOLLOW_THEME,
            userColorHex = userColorHex,
            primaryContainer = bluePrimaryContainer,
            onPrimaryContainer = blueOnPrimaryContainer,
            onPrimary = onPrimary
        )

        // Verify Material3 designed contrast is preserved
        assertEquals(
            "FOLLOW_THEME should preserve Material3 background color",
            bluePrimaryContainer,
            result.backgroundColor.toArgb()
        )
        assertEquals(
            "FOLLOW_THEME should preserve Material3 text contrast",
            blueOnPrimaryContainer,
            result.textColor.toArgb()
        )
    }

    // ========== PERSONALIZED mode tests ==========

    @Test
    fun resolveCardColors_PERSONALIZED_backgroundIsUserColorHex() {
        val result = CardColorResolver.resolveCardColors(
            style = CardColorStyle.PERSONALIZED,
            userColorHex = userColorHex,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            onPrimary = onPrimary
        )

        // Background should be user's color directly
        assertEquals(
            "PERSONALIZED background should be user's colorHex",
            userColorArgb,
            result.backgroundColor.toArgb()
        )
    }

    @Test
    fun resolveCardColors_PERSONALIZED_iconTextColorIsDynamicContrast() {
        // Green (#4CAF50) has luminance ~0.528 (< 0.55), so contrast color should be white
        val result = CardColorResolver.resolveCardColors(
            style = CardColorStyle.PERSONALIZED,
            userColorHex = userColorHex,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            onPrimary = onPrimary
        )

        // Green luminance = 0.528 < 0.55 threshold, so should use white text
        assertEquals(
            "PERSONALIZED icon color should be white for green (luminance ~0.528 < 0.55)",
            0xFFFFFFFF.toInt(),
            result.iconColor.toArgb()
        )
        assertEquals(
            "PERSONALIZED text color should be white for green (luminance ~0.528 < 0.55)",
            0xFFFFFFFF.toInt(),
            result.textColor.toArgb()
        )

        // Verify secondary text color has reduced alpha
        assertEquals(
            "PERSONALIZED secondary text color should have 0.7 alpha",
            0.7f,
            result.secondaryTextColor.alpha,
            0.01f
        )
    }

    @Test
    fun resolveCardColors_PERSONALIZED_darkBackgroundUsesWhiteContrast() {
        // Dark blue (#1A237E) has luminance ~0.084 (< 0.55), so contrast color should be white
        val darkBlueHex = "#1A237E"
        val darkBlueArgb = 0xFF1A237E.toInt()

        val result = CardColorResolver.resolveCardColors(
            style = CardColorStyle.PERSONALIZED,
            userColorHex = darkBlueHex,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            onPrimary = onPrimary
        )

        // Background should be dark blue
        assertEquals(
            "PERSONALIZED background should be user's dark blue",
            darkBlueArgb,
            result.backgroundColor.toArgb()
        )
        // Both icon and text should use white for dark background
        assertEquals(
            "PERSONALIZED icon color should be white for dark backgrounds",
            0xFFFFFFFF.toInt(),
            result.iconColor.toArgb()
        )
        assertEquals(
            "PERSONALIZED text color should be white for dark backgrounds",
            0xFFFFFFFF.toInt(),
            result.textColor.toArgb()
        )
    }

    @Test
    fun resolveCardColors_PERSONALIZED_lightBackgroundUsesDarkContrast() {
        // Light yellow (#FFF59D) has luminance ~0.89 (> 0.55), so contrast color should be dark gray
        val lightYellowHex = "#FFF59D"
        val lightYellowArgb = 0xFFFFF59D.toInt()

        val result = CardColorResolver.resolveCardColors(
            style = CardColorStyle.PERSONALIZED,
            userColorHex = lightYellowHex,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            onPrimary = onPrimary
        )

        // Background should be light yellow
        assertEquals(
            "PERSONALIZED background should be user's light yellow",
            lightYellowArgb,
            result.backgroundColor.toArgb()
        )
        // Both icon and text should use dark gray for light background
        val expectedContrast = 0xDE1A1A1A.toInt()
        assertEquals(
            "PERSONALIZED icon color should be dark gray for light backgrounds",
            expectedContrast,
            result.iconColor.toArgb()
        )
        assertEquals(
            "PERSONALIZED text color should be dark gray for light backgrounds",
            expectedContrast,
            result.textColor.toArgb()
        )
    }

    @Test
    fun resolveCardColors_PERSONALIZED_grayBackgroundUsesWhiteContrast() {
        // Gray (#808080) has luminance ~0.5 (< 0.55), so contrast color should be white
        val grayHex = "#808080"
        val grayArgb = 0xFF808080.toInt()

        val result = CardColorResolver.resolveCardColors(
            style = CardColorStyle.PERSONALIZED,
            userColorHex = grayHex,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            onPrimary = onPrimary
        )

        // Background should be gray
        assertEquals(
            "PERSONALIZED background should be user's gray",
            grayArgb,
            result.backgroundColor.toArgb()
        )
        // Gray has luminance ~0.5, below threshold 0.55, should use white
        assertEquals(
            "PERSONALIZED icon color should be white for gray (luminance ~0.5 < 0.55)",
            0xFFFFFFFF.toInt(),
            result.iconColor.toArgb()
        )
        assertEquals(
            "PERSONALIZED text color should be white for gray (luminance ~0.5 < 0.55)",
            0xFFFFFFFF.toInt(),
            result.textColor.toArgb()
        )
    }

    // ========== Utility tests ==========

    @Test
    fun parseColorSafe_invalidHex_returnsFallbackColor() {
        val invalidHex = "#INVALID"
        val fallback = 0xFF123456.toInt()

        val result = CardColorResolver.parseColorSafe(invalidHex, fallback)

        assertEquals("Invalid hex should return fallback color", fallback, result)
    }

    @Test
    fun parseColorSafe_validHex_returnsParsedColor() {
        val validHex = "#FF4CAF50"
        val expected = 0xFF4CAF50.toInt()

        val result = CardColorResolver.parseColorSafe(validHex)

        assertEquals("Valid hex should be parsed correctly", expected, result)
    }

    @Test
    fun parseColorSafe_shortHex_returnsParsedColor() {
        // Test 6-character hex (without alpha prefix)
        val shortHex = "#4CAF50"
        val expected = 0xFF4CAF50.toInt()

        val result = CardColorResolver.parseColorSafe(shortHex)

        assertEquals("Short hex should be parsed correctly", expected, result)
    }

    @Test
    fun resolveCardColors_defaultFallbackOnInvalidHex() {
        val invalidHex = "#BADHEX"

        val result = CardColorResolver.resolveCardColors(
            style = CardColorStyle.FOLLOW_THEME,
            userColorHex = invalidHex,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            onPrimary = onPrimary
        )

        // Should use default fallback (green) instead of crashing
        val fallbackArgb = 0xFF4CAF50.toInt()
        assertEquals(
            "Invalid userColorHex should use fallback for icon color",
            fallbackArgb,
            result.iconColor.toArgb()
        )
    }
}
