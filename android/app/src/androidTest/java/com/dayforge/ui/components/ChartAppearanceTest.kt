package com.dayforge.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.domain.appearance.AppearanceContrast
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.domain.appearance.DeviceThemeMode
import com.dayforge.domain.appearance.DeviceThemeSelection
import com.dayforge.domain.appearance.LoadedDeviceTheme
import com.dayforge.domain.appearance.ResolvedTheme
import com.dayforge.domain.appearance.SavedThemeSelection
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.model.ThemeDefinition
import com.dayforge.ui.theme.ChartAppearance
import com.dayforge.ui.theme.DayForgeTheme
import com.dayforge.ui.theme.rememberChartAppearance
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChartAppearanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val white = Color.White
    private val black = Color.Black
    private val red = Color(0xffff0000)
    private val blue = Color(0xff0000ff)
    private val green = Color(0xff008000)
    private val cyan = Color(0xff00ffff)
    private val yellow = Color(0xffffff00)
    private val metric = MetricEntity(id = 1, name = "Weight", unit = "kg", iconResId = 1,
        colorHex = "#000000", aggregationType = "by_time", targetDirection = "increase", targetValue = 4.0)
    private fun logs(): List<MetricLogEntity> {
        val now = System.currentTimeMillis()
        return listOf(1.0, 3.0, 2.0).mapIndexed { index, value ->
            MetricLogEntity(metricId = 1, date = now - (2 - index) * 86_400_000L, value = value, unit = "kg")
        }
    }
    private fun definition(): ThemeDefinition {
        val source = InstrumentationRegistry.getInstrumentation().context.assets
            .open("next/theme.json").bufferedReader().use { it.readText() }
        val original = Json.decodeFromString<ThemeDefinition>(source)
        return original.copy(
            light = original.light.copy(material = original.light.material + mapOf(
                "background" to "#FFFFFF", "primary_container" to "#FFFFFF", "on_surface_variant" to "#000000"),
                chart = mapOf("line" to "#FF0000", "target" to "#0000FF", "grid" to "#008000", "selection" to "#FF00FF")),
            dark = original.dark.copy(material = original.dark.material + mapOf(
                "background" to "#000000", "primary_container" to "#000000", "on_surface_variant" to "#FFFFFF"),
                chart = mapOf("line" to "#00FFFF", "target" to "#FFFF00", "grid" to "#808080", "selection" to "#FF00FF"))
        )
    }
    private fun loaded(definition: ThemeDefinition, dark: Boolean) = LoadedDeviceTheme(
        SavedThemeSelection(1, DeviceThemeSelection(
            ThemeVersionRef(definition.themeId, definition.revision), ThemeVersionRef(definition.themeId, definition.revision),
            if (dark) DeviceThemeMode.DARK else DeviceThemeMode.LIGHT, DeviceCardStyle.FOLLOW_THEME)),
        ResolvedTheme.from(definition, false), ResolvedTheme.from(definition, true))

    // Native Vico Canvas, not a fake composable or a counter mirroring production resolution.
    private fun pixels(color: Color, plotOnly: Boolean = false): Int {
        val image = compose.onNodeWithTag("metric-trend-chart", useUnmergedTree = true).captureToImage().toPixelMap()
        val expected = color.toArgb()
        var count = 0
        val xs = if (plotOnly) image.width / 4 until image.width * 4 / 5 else 0 until image.width
        val ys = if (plotOnly) image.height / 4 until image.height * 4 / 5 else 0 until image.height
        for (y in ys) for (x in xs) {
            if (image[x, y].toArgb() == expected) count++
        }
        return count
    }
    private fun painted(color: Color, plotOnly: Boolean = false) {
        compose.waitUntil(10_000) { runCatching { pixels(color, plotOnly) > 20 }.getOrDefault(false) }
        assertTrue("expected actual chart pixels $color", pixels(color, plotOnly) > 20)
    }

    @Test fun savedLineTargetAndGridReachNativePixelsAndModeSwitchRepaints() {
        val definition = definition()
        val current = mutableStateOf(loaded(definition, false))
        val records = logs()
        compose.setContent { DayForgeTheme(current.value) {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                TrendChart(metric, records)
            }
        } }
        painted(red); painted(blue); painted(green)
        compose.runOnIdle { current.value = loaded(definition, true) }
        painted(cyan); painted(yellow); painted(Color(0xff808080))
        assertEquals(0, pixels(red)); assertEquals(0, pixels(blue))
        assertEquals("#FF0000", definition.light.chart.getValue("line"))
        assertEquals("#00FFFF", definition.dark.chart.getValue("line"))
    }

    @Test fun savedRoleRevisionAndObjectAccentChangesDoNotRegenerateOrOverridePalette() {
        val original = definition()
        val current = mutableStateOf(loaded(original, false))
        val entity = mutableStateOf(metric)
        val records = logs()
        compose.setContent { DayForgeTheme(current.value) {
            Box(Modifier.background(white)) { TrendChart(entity.value, records) }
        } }
        painted(red)
        compose.runOnIdle { entity.value = metric.copy(colorHex = "#008000") }
        painted(red)
        val next = original.copy(revision = 2, light = original.light.copy(
            chart = original.light.chart + ("line" to "#800080")))
        compose.runOnIdle { current.value = loaded(next, false) }
        painted(Color(0xff800080)); assertEquals(0, pixels(red))
        assertEquals("#FF0000", original.light.chart.getValue("line"))
    }

    @Test fun legacyMetricColorEditsRepaintAndInvalidLegacyColorUsesPrimaryOnlyWithoutSavedTheme() {
        val entity = mutableStateOf(metric.copy(colorHex = "#FF0000", targetDirection = null, targetValue = null))
        val records = logs()
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme(primary = blue, background = white)) {
            Box(Modifier.background(white)) { TrendChart(entity.value, records) }
        } }
        painted(red)
        compose.runOnIdle { entity.value = entity.value.copy(colorHex = "#008000") }
        painted(green); assertEquals(0, pixels(red))
        compose.runOnIdle { entity.value = entity.value.copy(colorHex = "invalid") }
        painted(blue); assertEquals(0, pixels(green))
    }

    @Test fun explicitOverrideChangesOnlyCurveAndStillEnforcesActualBackgroundContrast() {
        val source = definition()
        val theme = loaded(source, false)
        val override = mutableStateOf<Color?>(Color(0xff800080))
        var observed: ChartAppearance? = null
        val records = logs()
        compose.setContent { DayForgeTheme(theme) {
            val value = rememberChartAppearance(metric.colorHex, override.value, white, white)
            SideEffect { observed = value }
            Box(Modifier.background(white)) { TrendChart(metric, records, lineColor = override.value) }
        } }
        painted(Color(0xff800080)); painted(blue)
        compose.runOnIdle {
            assertEquals(Color(0xff800080), observed!!.line)
            assertEquals(blue, observed!!.target)
            override.value = white
        }
        compose.runOnIdle { assertEquals(black, observed!!.line); assertEquals(blue, observed!!.target) }
        painted(black, plotOnly = true); assertEquals(0, pixels(Color(0xff800080)))
        compose.runOnIdle { override.value = Color.Unspecified }
        painted(red)
    }

    @Test fun actualAlphaBackgroundAndOpaqueBackdropInvalidateResolutionAndTextUsesFourPointFive() {
        val background = mutableStateOf(Color(0x80ffffff))
        val base = mutableStateOf(black)
        val override = mutableStateOf<Color?>(Color(0xff777777))
        var observed: ChartAppearance? = null
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme(onSurfaceVariant = Color(0xff777777))) {
            val value = rememberChartAppearance("#777777", override.value, background.value, base.value)
            SideEffect { observed = value }
        } }
        compose.runOnIdle {
            assertEquals(black, observed!!.line)
            assertEquals(black, observed!!.targetLabel)
            assertTrue(AppearanceContrast.ratio(observed!!.label.toArgb(), 0xff808080.toInt()) >= 4.5)
            base.value = white
        }
        compose.runOnIdle {
            // #777777 on white passes 3:1 for graphics but not 4.5:1 for normal text.
            assertEquals(Color(0xff777777), observed!!.line)
            assertEquals(black, observed!!.targetLabel)
            background.value = black
        }
        compose.runOnIdle {
            assertEquals(Color(0xff777777), observed!!.line)
            assertEquals(Color(0xff777777), observed!!.targetLabel)
            override.value = black
        }
        compose.runOnIdle { assertEquals(white, observed!!.line) }
    }

    @Test fun expandedCardSuppliesActualFollowThemeAndPersonalizedBackgroundWithoutNavigation() {
        val source = definition().let { it.copy(light = it.light.copy(chart = it.light.chart + ("line" to "#0000FF"))) }
        val theme = loaded(source, false)
        val style = mutableStateOf(CardColorStyle.FOLLOW_THEME)
        val records = logs()
        val entity = metric.copy(targetDirection = null, targetValue = null)
        var navigation = 0
        compose.setContent { DayForgeTheme(theme) {
            Box(Modifier.background(white)) {
                MetricCard(entity, 2.0, records.last().date, records, onClick = { navigation++ }, cardColorStyle = style.value)
            }
        } }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.content_description_expand)).performClick()
        painted(blue)
        compose.runOnIdle { assertEquals(0, navigation); style.value = CardColorStyle.PERSONALIZED }
        painted(white); assertEquals(0, pixels(blue))
        compose.runOnIdle { assertEquals(0, navigation) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.content_description_collapse)).performClick()
        compose.onNodeWithTag("metric-trend-chart", useUnmergedTree = true).assertDoesNotExist()
    }
}
