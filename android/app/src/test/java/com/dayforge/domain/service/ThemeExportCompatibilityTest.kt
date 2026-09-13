package com.dayforge.domain.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dayforge.domain.model.GlobalColorTheme
import com.dayforge.domain.repository.CustomThemeRepository
import com.dayforge.ui.theme.ColorSchemeGenerator
import com.dayforge.ui.theme.SeedPaletteFixtures
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
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

    @Test fun `generated export retains the light-reference palette for all fixture seeds`() = runTest {
        for (fixture in SeedPaletteFixtures.load().filter { !it.dark }) {
            every { manager.getById("test") } returns theme("#${fixture.seed}").copy(suitableForLight = false)
            assertRoles(fixture.colors, service.exportToJson("test", true).getOrThrow())
        }
        coVerify(exactly = 0) { repository.getThemeJson(any()) }
    }

    @Test fun `generated reference still replaces overrides while normal export preserves original JSON`() = runTest {
        val custom = theme().copy(isCustom = true, primary = "#123456")
        every { manager.getById("test") } returns custom
        val original = "{\"id\":\"test\", \"primary\":\"#123456\", \"extra\":true}"
        coEvery { repository.getThemeJson("test") } returns original
        assertEquals(original, service.exportToJson("test").getOrThrow())
        val reference = SeedPaletteFixtures.load().first { it.seed == "FF1976D2" && !it.dark }
        assertRoles(reference.colors, service.exportToJson("test", true).getOrThrow())
    }

    @Test fun `OLED export uses fixed preset colors while custom oled remains seed based`() = runTest {
        val oled = theme("#000000").copy(id = "oled")
        every { manager.getById("oled") } returns oled
        assertRoles(SeedPaletteFixtures.colors(ColorSchemeGenerator.generateOledColorScheme()),
            service.exportToJson("oled", true).getOrThrow())
        every { manager.getById("oled") } returns oled.copy(isCustom = true)
        val black = SeedPaletteFixtures.load().first { it.seed == "FF000000" && !it.dark }
        assertRoles(black.colors, service.exportToJson("oled", true).getOrThrow())
    }

    @Test fun `invalid export seed falls back to ocean without changing metadata`() = runTest {
        every { manager.getById("test") } returns theme("invalid")
        val result = service.exportToJson("test", true).getOrThrow()
        val ocean = SeedPaletteFixtures.load().first { it.seed == "FF1976D2" && !it.dark }
        assertRoles(ocean.colors, result)
        assertEquals("invalid", Json.parseToJsonElement(result).jsonObject["seedColor"]?.jsonPrimitive?.content)
    }
}
