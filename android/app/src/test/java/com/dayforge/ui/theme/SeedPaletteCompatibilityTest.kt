package com.dayforge.ui.theme

import com.dayforge.domain.model.GlobalColorTheme
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SeedPaletteCompatibilityTest {
    private fun theme(seed: String) = GlobalColorTheme("test", "Test", seed, true, true)
    private fun colors(theme: GlobalColorTheme, dark: Boolean) = SeedPaletteFixtures.colors(
        if (dark) ColorSchemeGenerator.generateDarkColorScheme(theme)
        else ColorSchemeGenerator.generateLightColorScheme(theme)
    )

    @Test fun `all 27 light and dark roles match pre-migration output for 43 seeds`() {
        val fixtures = SeedPaletteFixtures.load()
        assertEquals(86, fixtures.size)
        for (fixture in fixtures) {
            val actual = colors(theme("#${fixture.seed}"), fixture.dark)
            SeedPaletteFixtures.roles.forEachIndexed { i, role ->
                assertEquals("${fixture.seed}, dark=${fixture.dark}, $role", fixture.colors[i], actual[i])
            }
        }
    }

    @Test fun `each custom role overrides only its role in both modes`() {
        for (dark in listOf(false, true)) {
            val base = theme("#1976D2")
            val baseline = colors(base, dark)
            val json = Json.parseToJsonElement(Json.encodeToString(base)).jsonObject
            SeedPaletteFixtures.roles.forEachIndexed { i, role ->
                val custom = Json.decodeFromJsonElement<GlobalColorTheme>(JsonObject(json + (role to JsonPrimitive("#123456"))))
                val expected = baseline.toMutableList().apply { this[i] = 0xff123456.toInt() }
                assertEquals("dark=$dark, $role", expected, colors(custom, dark))
            }
        }
    }

    @Test fun `invalid seed falls back to ocean and invalid override stays black`() {
        for (dark in listOf(false, true)) {
            assertEquals(colors(theme("#1976D2"), dark), colors(theme("invalid"), dark))
            val overridden = colors(theme("invalid").copy(primary = "invalid"), dark)
            assertEquals(0xff000000.toInt(), overridden.first())
            assertEquals(colors(theme("#1976D2"), dark).drop(1), overridden.drop(1))
        }
    }

    @Test fun `OLED preset remains fixed but custom theme named oled uses its own colors`() {
        val preset = theme("#000000").copy(id = "oled", primary = "#123456")
        val fixed = SeedPaletteFixtures.colors(ColorSchemeGenerator.generateOledColorScheme())
        for (dark in listOf(false, true)) {
            assertEquals(fixed, colors(preset, dark))
            val custom = preset.copy(isCustom = true)
            assertEquals(colors(custom.copy(id = "custom"), dark), colors(custom, dark))
            assertEquals(0xff123456.toInt(), colors(custom, dark).first())
        }
    }
}
