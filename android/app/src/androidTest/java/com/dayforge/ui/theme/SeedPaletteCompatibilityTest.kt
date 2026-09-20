package com.dayforge.ui.theme

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.domain.model.GlobalColorTheme
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeedPaletteCompatibilityTest {
    private fun theme(seed: String) = GlobalColorTheme("test", "Test", seed, true, true)
    private fun colors(theme: GlobalColorTheme, dark: Boolean) = SeedPaletteFixtures.colors(
        if (dark) ColorSchemeGenerator.generateDarkColorScheme(theme)
        else ColorSchemeGenerator.generateLightColorScheme(theme)
    )

    @Test fun all_27_light_and_dark_roles_match_pre_migration_output_for_43_seeds() {
        val fixtures = SeedPaletteFixtures.load()
        assertEquals(86, fixtures.size)
        for (fixture in fixtures) {
            val actual = colors(theme("#${fixture.seed}"), fixture.dark)
            SeedPaletteFixtures.roles.forEachIndexed { i, role ->
                assertEquals("${fixture.seed}, dark=${fixture.dark}, $role", fixture.colors[i], actual[i])
            }
        }
    }

    @Test fun each_custom_role_overrides_only_its_role_in_both_modes() {
        for (dark in listOf(false, true)) {
            val base = theme("#1976D2")
            val baseline = SeedPaletteFixtures.ocean(dark)
            val json = Json.parseToJsonElement(Json.encodeToString(base)).jsonObject
            SeedPaletteFixtures.roles.forEachIndexed { i, role ->
                val custom = Json.decodeFromJsonElement<GlobalColorTheme>(JsonObject(json + (role to JsonPrimitive("#123456"))))
                val expected = baseline.toMutableList().apply { this[i] = 0xff123456.toInt() }
                assertEquals("dark=$dark, $role", expected, colors(custom, dark))
            }
        }
    }

    @Test fun invalid_seed_falls_back_to_ocean_and_invalid_override_stays_black() {
        for (dark in listOf(false, true)) {
            assertEquals(SeedPaletteFixtures.ocean(dark), colors(theme("invalid"), dark))
            val overridden = colors(theme("invalid").copy(primary = "invalid"), dark)
            assertEquals(0xff000000.toInt(), overridden.first())
            assertEquals(SeedPaletteFixtures.ocean(dark).drop(1), overridden.drop(1))
        }
    }

    @Test fun OLED_preset_remains_fixed_but_custom_theme_named_oled_uses_its_own_colors() {
        val preset = theme("#000000").copy(id = "oled", primary = "#123456")
        val fixed = SeedPaletteFixtures.oledColors
        for (dark in listOf(false, true)) {
            assertEquals(fixed, colors(preset, dark))
            val custom = preset.copy(isCustom = true)
            val black = SeedPaletteFixtures.load().single { it.seed == "FF000000" && it.dark == dark }.colors
            assertEquals(black.toMutableList().apply { this[0] = 0xff123456.toInt() }, colors(custom, dark))
            assertEquals(0xff123456.toInt(), colors(custom, dark).first())
        }
    }
}
