package com.dayforge.domain.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dayforge.domain.model.GlobalColorTheme
import com.dayforge.domain.repository.CustomThemeRepository
import com.dayforge.ui.theme.SeedPaletteFixtures
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ThemeExportCompatibilityTest {
    private val manager = mockk<ThemeManager>()
    private val repository = mockk<CustomThemeRepository>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val service = ThemeExportService(manager, repository, context)
    private fun theme(seed: String = "#1976D2") = GlobalColorTheme("test", "Test", seed, true, true)

    private fun assertRoles(expected: List<Int>, json: String) {
        val values = Json.parseToJsonElement(json).jsonObject
        SeedPaletteFixtures.roles.forEachIndexed { i, role ->
            assertEquals(role, String.format(Locale.ROOT, "#%06X", expected[i] and 0xffffff), values[role]?.jsonPrimitive?.content)
        }
    }

    @Test fun generated_export_retains_the_light_reference_palette_for_all_fixture_seeds() = runTest {
        for (fixture in SeedPaletteFixtures.load().filter { !it.dark }) {
            every { manager.getById("test") } returns theme("#${fixture.seed}").copy(suitableForLight = false)
            assertRoles(fixture.colors, service.exportToJson("test", true).getOrThrow())
        }
        coVerify(exactly = 0) { repository.getThemeJson(any()) }
    }

    @Test fun generated_reference_still_replaces_overrides_while_normal_export_preserves_original_JSON() = runTest {
        val custom = theme().copy(isCustom = true, primary = "#123456")
        every { manager.getById("test") } returns custom
        val original = "{\"id\":\"test\", \"primary\":\"#123456\", \"extra\":true}"
        coEvery { repository.getThemeJson("test") } returns original
        assertEquals(original, service.exportToJson("test").getOrThrow())
        val reference = SeedPaletteFixtures.load().first { it.seed == "FF1976D2" && !it.dark }
        assertRoles(reference.colors, service.exportToJson("test", true).getOrThrow())
    }

    @Test fun OLED_export_uses_fixed_preset_colors_while_custom_oled_remains_seed_based() = runTest {
        val oled = theme("#000000").copy(id = "oled")
        every { manager.getById("oled") } returns oled
        assertRoles(SeedPaletteFixtures.oledColors,
            service.exportToJson("oled", true).getOrThrow())
        every { manager.getById("oled") } returns oled.copy(isCustom = true)
        val black = SeedPaletteFixtures.load().first { it.seed == "FF000000" && !it.dark }
        assertRoles(black.colors, service.exportToJson("oled", true).getOrThrow())
    }

    @Test fun invalid_export_seed_falls_back_to_ocean_without_changing_metadata() = runTest {
        every { manager.getById("test") } returns theme("invalid")
        val result = service.exportToJson("test", true).getOrThrow()
        val ocean = SeedPaletteFixtures.load().first { it.seed == "FF1976D2" && !it.dark }
        assertRoles(ocean.colors, result)
        assertEquals("invalid", Json.parseToJsonElement(result).jsonObject["seedColor"]?.jsonPrimitive?.content)
    }
}
