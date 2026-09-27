package com.dayforge.domain.appearance

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.glance.color.ColorProviders
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.model.ThemeDefinition
import com.dayforge.ui.theme.toComposeColors
import com.dayforge.widget.base.glanceThemeColors
import com.dayforge.widget.base.toGlanceColors
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResolvedThemeTest {
    private fun raw(): JsonObject = Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation()
        .context.assets.open("next/theme.json").bufferedReader().use { it.readText() }).jsonObject
    private fun definition(raw: JsonObject = raw()) = Json.decodeFromString<ThemeDefinition>(raw.toString())
    private fun expected(raw: JsonObject, mode: String, group: String) = raw.getValue(mode).jsonObject.getValue(group).jsonObject
        .mapValues { android.graphics.Color.parseColor(it.value.jsonPrimitive.content) }
    private fun context(dark: Boolean): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        })
    }
    private fun compose(c: ColorScheme) = mapOf(
        "primary" to c.primary, "on_primary" to c.onPrimary, "primary_container" to c.primaryContainer,
        "on_primary_container" to c.onPrimaryContainer, "inverse_primary" to c.inversePrimary,
        "secondary" to c.secondary, "on_secondary" to c.onSecondary, "secondary_container" to c.secondaryContainer,
        "on_secondary_container" to c.onSecondaryContainer, "tertiary" to c.tertiary, "on_tertiary" to c.onTertiary,
        "tertiary_container" to c.tertiaryContainer, "on_tertiary_container" to c.onTertiaryContainer,
        "background" to c.background, "on_background" to c.onBackground, "surface" to c.surface, "on_surface" to c.onSurface,
        "surface_variant" to c.surfaceVariant, "on_surface_variant" to c.onSurfaceVariant, "surface_tint" to c.surfaceTint,
        "inverse_surface" to c.inverseSurface, "inverse_on_surface" to c.inverseOnSurface, "error" to c.error,
        "on_error" to c.onError, "error_container" to c.errorContainer, "on_error_container" to c.onErrorContainer,
        "outline" to c.outline, "outline_variant" to c.outlineVariant, "scrim" to c.scrim,
        "surface_bright" to c.surfaceBright, "surface_dim" to c.surfaceDim, "surface_container" to c.surfaceContainer,
        "surface_container_high" to c.surfaceContainerHigh, "surface_container_highest" to c.surfaceContainerHighest,
        "surface_container_low" to c.surfaceContainerLow, "surface_container_lowest" to c.surfaceContainerLowest
    ).mapValues { it.value.toArgb() }
    private fun glance(c: ColorProviders, context: Context) = mapOf(
        "primary" to c.primary, "on_primary" to c.onPrimary, "primary_container" to c.primaryContainer,
        "on_primary_container" to c.onPrimaryContainer, "inverse_primary" to c.inversePrimary,
        "secondary" to c.secondary, "on_secondary" to c.onSecondary, "secondary_container" to c.secondaryContainer,
        "on_secondary_container" to c.onSecondaryContainer, "tertiary" to c.tertiary, "on_tertiary" to c.onTertiary,
        "tertiary_container" to c.tertiaryContainer, "on_tertiary_container" to c.onTertiaryContainer,
        "background" to c.background, "on_background" to c.onBackground, "surface" to c.surface, "on_surface" to c.onSurface,
        "surface_variant" to c.surfaceVariant, "on_surface_variant" to c.onSurfaceVariant,
        "inverse_surface" to c.inverseSurface, "inverse_on_surface" to c.inverseOnSurface, "error" to c.error,
        "on_error" to c.onError, "error_container" to c.errorContainer, "on_error_container" to c.onErrorContainer,
        "outline" to c.outline
    ).mapValues { it.value.getColor(context).toArgb() }

    @Test fun allSavedRolesReachRealComposeAndGlanceWithoutMainActivityOrGeneration() {
        val original = raw()
        // Distinct roles expose accidental swaps that shared white/on colors in a natural theme hide.
        val distinct = JsonObject(original.toMutableMap().apply {
            for ((modeIndex, mode) in listOf("light", "dark").withIndex()) {
                put(mode, JsonObject(original.getValue(mode).jsonObject.mapValues { (_, group) ->
                    JsonObject(group.jsonObject.keys.mapIndexed { index, name ->
                        name to JsonPrimitive("#%06X".format(0x102030 + modeIndex * 0x505050 + index * 0x010203))
                    }.toMap())
                }))
            }
        })
        for (source in listOf(original, distinct)) for (dark in listOf(false, true)) {
            val saved = definition(source).copy(generatorId = "unknown.generator", seed = "#FF0000")
            val result = ResolvedTheme.from(saved, dark)
            val mode = if (dark) "dark" else "light"
            assertEquals("50000000-0000-0000-0000-000000000001", result.themeId)
            assertEquals(1, result.revision); assertEquals(dark, result.dark)
            assertEquals(36, result.material.size); assertEquals(12, result.status.size); assertEquals(4, result.chart.size)
            assertEquals(expected(source, mode, "material"), compose(result.toComposeColors()))
            assertEquals(expected(source, mode, "status"), result.status)
            assertEquals(expected(source, mode, "chart"), result.chart)
            val providers = result.toGlanceColors()
            for (deviceDark in listOf(false, true)) {
                val context = context(deviceDark)
                val actual = glance(providers, context)
                assertEquals(26, actual.size)
                assertEquals(expected(source, mode, "material").filterKeys { it in actual }, actual)
                assertEquals(expected(source, mode, "material").getValue("surface"), providers.widgetBackground.getColor(context).toArgb())
            }
        }
    }

    @Test fun systemModeSelectsIndependentSavedPalettesAndRejectsReversedModes() {
        val raw = raw()
        val light = ResolvedTheme.from(definition(raw), false)
        val dark = ResolvedTheme.from(definition(raw).copy(themeId = "50000000-0000-0000-0000-000000000002", revision = 7), true)
        val providers = glanceThemeColors(light, dark)
        for (night in listOf(false, true)) {
            val actual = glance(providers, context(night))
            assertEquals(expected(raw, if (night) "dark" else "light", "material").filterKeys { it in actual }, actual)
        }
        assertThrows(IllegalArgumentException::class.java) { glanceThemeColors(dark, light) }
        assertThrows(IllegalArgumentException::class.java) { glanceThemeColors(light, light) }
    }

    @Test fun sourceMutationCannotChangeSnapshotsOrBypassPaletteValidation() {
        val original = definition()
        val material = original.light.material.toMutableMap()
        val status = original.light.status.toMutableMap()
        val chart = original.light.chart.toMutableMap()
        val saved = original.copy(light = original.light.copy(material = material, status = status, chart = chart))
        val snapshot = ResolvedTheme.from(saved, false)
        material["primary"] = "#FF0000"; status["success"] = "#FF0000"; chart["line"] = "#FF0000"
        assertEquals(0xff245eac.toInt(), snapshot.material.getValue("primary"))
        assertEquals(0xff146c2e.toInt(), snapshot.status.getValue("success"))
        assertEquals(0xff245eac.toInt(), snapshot.chart.getValue("line"))
        for (values in listOf(snapshot.material, snapshot.status, snapshot.chart)) {
            assertThrows(UnsupportedOperationException::class.java) { (values as MutableMap).clear() }
        }
        material["unknown"] = "#123456"
        assertThrows(IllegalArgumentException::class.java) { ResolvedTheme.from(saved, false) }
        material.remove("unknown"); material.remove("surface")
        assertThrows(IllegalArgumentException::class.java) { ResolvedTheme.from(saved, false) }
        material["surface"] = "#1234567"
        assertThrows(IllegalArgumentException::class.java) { ResolvedTheme.from(saved, false) }
    }

    @Test fun colorSyntaxAndAlphaAreStrictAndNeverUseFallbackGreen() {
        assertEquals(0xffabcdef.toInt(), parseAppearanceColor("#aBcDeF"))
        assertEquals(0x12345678, parseAppearanceColor("#12345678"))
        assertEquals(0, parseAppearanceColor("#00000000"))
        for (bad in listOf("#FFF", "#1234567", "123456", "#GG0000", " #123456", "#123456 ")) {
            assertThrows(IllegalArgumentException::class.java) { parseAppearanceColor(bad) }
        }
    }

    @Test fun contrastUsesLinearSrgbAndUnroundedThresholds() {
        val white = -1; val black = 0xff000000.toInt()
        assertEquals(21.0, AppearanceContrast.ratio(black, white), 0.000000001)
        assertEquals(1.0, AppearanceContrast.ratio(white, white), 0.000000001)
        assertEquals(4.478089453577214, AppearanceContrast.ratio(0xff777777.toInt(), white), 0.000000001)
        assertFalse(AppearanceContrast.resolve(0xff767676.toInt(), white, white).adjusted)
        assertTrue(AppearanceContrast.resolve(0xff777777.toInt(), white, white).adjusted)
        assertFalse(AppearanceContrast.resolve(0xff949494.toInt(), white, white, ContrastUse.LARGE_TEXT_OR_GRAPHIC).adjusted)
        assertTrue(AppearanceContrast.resolve(0xff959595.toInt(), white, white, ContrastUse.LARGE_TEXT_OR_GRAPHIC).adjusted)
        assertEquals(black, AppearanceContrast.resolve(white, 0xff808080.toInt(), white).argb)
        assertEquals(5.252, AppearanceContrast.ratio(black, 0xffff0000.toInt()), 0.000000001)
    }

    @Test fun actualBackdropAndBothAlphasAreUsedWithoutChangingTheSavedColor() {
        val white = -1; val black = 0xff000000.toInt()
        assertEquals(0xff7f7f7f.toInt(), AppearanceContrast.composite(0x80000000.toInt(), white))
        assertEquals(0xff808080.toInt(), AppearanceContrast.composite(0x80ffffff.toInt(), black))
        val original = 0x80000000.toInt()
        val text = AppearanceContrast.resolve(original, white, black)
        assertEquals(black, text.argb); assertTrue(text.adjusted)
        val graphic = AppearanceContrast.resolve(original, white, black, ContrastUse.LARGE_TEXT_OR_GRAPHIC)
        assertEquals(original, graphic.argb); assertFalse(graphic.adjusted)
        val result = AppearanceContrast.resolve(white, 0x80ffffff.toInt(), black)
        assertEquals(0xff808080.toInt(), result.backgroundArgb); assertEquals(black, result.argb)
        assertThrows(IllegalArgumentException::class.java) { AppearanceContrast.resolve(white, black, 0) }
        for (grey in 0..255) {
            val background = black or (grey shl 16) or (grey shl 8) or grey
            assertTrue(AppearanceContrast.resolve(0, background, white).ratio >= 4.5)
        }
    }
}
