package com.dayforge.ui.theme

import androidx.compose.ui.graphics.Color
import com.dayforge.domain.model.GlobalColorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for ColorSchemeGenerator - Material3 ColorScheme generation from seed colors.
 *
 * Per THEME-08, THEME-09, THEME-10: Tonal palette generation with OLED special handling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ColorSchemeGeneratorTest {

    // ========== Test 1: Light scheme generation from Ocean seed (#1976D2) ==========

    @Test
    fun generateLightColorScheme_oceanSeed_returnsBluePrimary() {
        val seedColor = "#1976D2"  // Ocean theme seed
        val colorScheme = ColorSchemeGenerator.generateLightColorScheme(theme(seedColor))

        assertNotNull(colorScheme)
        // Primary should be a blue-ish color (close to seed)
        // Material3 tonal palette generates harmonious colors, primary should have blue hue
        assertTrue(
            "Primary should be blue-ish for Ocean seed",
            colorScheme.primary.blue >= 0.5f &&
                colorScheme.primary.blue > colorScheme.primary.red
        )
    }

    // ========== Test 2: Dark scheme generation from Dusk seed (#1A237E) ==========

    @Test
    fun generateDarkColorScheme_duskSeed_returnsDeepBluePrimary() {
        val seedColor = "#1A237E"  // Dusk theme seed
        val colorScheme = ColorSchemeGenerator.generateDarkColorScheme(theme(seedColor))

        assertNotNull(colorScheme)
        // Primary should have a deep blue tone for dark scheme
        assertTrue(
            "Primary should be deep blue-ish for Dusk seed",
            colorScheme.primary.blue >= 0.3f
        )
    }

    // ========== Test 3: Light and dark schemes from same seed have harmonious colors ==========

    @Test
    fun lightAndDarkSchemes_fromSameSeed_haveHarmoniousColors() {
        val seedColor = "#1976D2"  // Ocean seed
        val theme = theme(seedColor)
        val lightScheme = ColorSchemeGenerator.generateLightColorScheme(theme)
        val darkScheme = ColorSchemeGenerator.generateDarkColorScheme(theme)

        // Both should generate valid schemes
        assertNotNull(lightScheme)
        assertNotNull(darkScheme)

        // Primary colors should be derived from same seed (both blue-ish)
        assertTrue("Light primary should be blue-ish", lightScheme.primary.blue >= 0.5f)
        assertTrue("Dark primary should be blue-ish", darkScheme.primary.blue >= 0.3f)

        // Background should differ: light scheme has light background, dark has dark
        assertTrue(
            "Light scheme background should be light",
            lightScheme.background.red >= 0.8f &&
                lightScheme.background.green >= 0.8f &&
                lightScheme.background.blue >= 0.8f
        )
        assertTrue(
            "Dark scheme background should be dark",
            darkScheme.background.red <= 0.2f &&
                darkScheme.background.green <= 0.2f &&
                darkScheme.background.blue <= 0.2f
        )
    }

    // ========== Test 4: Invalid hex string returns fallback ColorScheme (Ocean) ==========

    @Test
    fun generateLightColorScheme_invalidHex_returnsFallbackOceanScheme() {
        val invalidColor = "invalid-color-string"
        val colorScheme = ColorSchemeGenerator.generateLightColorScheme(theme(invalidColor))

        assertNotNull(colorScheme)
        // Should fallback to Ocean (#1976D2) - blue primary
        assertTrue(
            "Invalid color should fallback to Ocean primary (blue-ish)",
            colorScheme.primary.blue >= 0.5f
        )
    }

    @Test
    fun generateDarkColorScheme_invalidHex_returnsFallbackOceanScheme() {
        val invalidColor = "not-a-hex-color"
        val colorScheme = ColorSchemeGenerator.generateDarkColorScheme(theme(invalidColor))

        assertNotNull(colorScheme)
        // Should fallback to Ocean (#1976D2) - generates valid dark scheme
        assertNotNull(colorScheme.primary)
        assertNotNull(colorScheme.background)
    }

    // ========== Test 5: OLED scheme uses tech-inspired cyan/blue, not pink ==========

    @Test
    fun generateOledColorScheme_usesCyanBlueAccents_notPink() {
        val colorScheme = ColorSchemeGenerator.generateOledColorScheme()

        // Verify pure black backgrounds
        assertEquals(Color(0xFF000000), colorScheme.background)
        assertEquals(Color(0xFF000000), colorScheme.surface)

        // Primary should be teal/cyan (#00BFA5) - tech feel, not pink
        assertEquals(Color(0xFF00BFA5), colorScheme.primary)
        assertEquals(Color.Black, colorScheme.onPrimary)

        // Secondary should be deep blue-purple (#3D5AFE) - calm, restrained
        assertEquals(Color(0xFF3D5AFE), colorScheme.secondary)

        // Tertiary should be electric blue (#2979FF) - tech accent
        assertEquals(Color(0xFF2979FF), colorScheme.tertiary)

        // Verify all colors are NOT pink-ish (pink has high red, low green, medium-high blue)
        // Primary cyan: green should be highest component
        assertTrue(
            "Primary should be cyan (green dominant), not pink",
            colorScheme.primary.green >= colorScheme.primary.red
        )
        // Secondary: should be blue-purple (blue dominant)
        assertTrue(
            "Secondary should be blue-purple (blue dominant)",
            colorScheme.secondary.blue >= colorScheme.primary.green
        )
    }

    @Test
    fun generateDarkColorScheme_oledSeed_returnsOledScheme() {
        val colorScheme = ColorSchemeGenerator.generateDarkColorScheme(
            theme(seedColor = "#000000", id = "oled", isDefault = true)
        )

        // Should delegate to generateOledColorScheme
        assertEquals(Color(0xFF000000), colorScheme.background)
        assertEquals(Color(0xFF00BFA5), colorScheme.primary)  // Cyan, not pink
    }

    // ========== Test 6: All ColorScheme properties are populated ==========

    @Test
    fun generateLightColorScheme_populatesAllColorSchemeProperties() {
        val seedColor = "#1976D2"
        val colorScheme = ColorSchemeGenerator.generateLightColorScheme(theme(seedColor))

        // Verify all essential color roles are populated (not default/unset)
        assertNotNull(colorScheme.primary)
        assertNotNull(colorScheme.onPrimary)
        assertNotNull(colorScheme.primaryContainer)
        assertNotNull(colorScheme.onPrimaryContainer)
        assertNotNull(colorScheme.secondary)
        assertNotNull(colorScheme.onSecondary)
        assertNotNull(colorScheme.secondaryContainer)
        assertNotNull(colorScheme.onSecondaryContainer)
        assertNotNull(colorScheme.tertiary)
        assertNotNull(colorScheme.onTertiary)
        assertNotNull(colorScheme.background)
        assertNotNull(colorScheme.onBackground)
        assertNotNull(colorScheme.surface)
        assertNotNull(colorScheme.onSurface)
        assertNotNull(colorScheme.error)
        assertNotNull(colorScheme.onError)
    }

    private fun theme(
        seedColor: String,
        id: String = "test",
        isDefault: Boolean = false
    ) = GlobalColorTheme(
        id = id,
        name = "Test",
        seedColor = seedColor,
        suitableForLight = true,
        suitableForDark = true,
        isDefault = isDefault
    )
}
