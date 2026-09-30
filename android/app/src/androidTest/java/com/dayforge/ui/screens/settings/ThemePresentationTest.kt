package com.dayforge.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.appearance.BuiltInTheme
import com.dayforge.data.appearance.BuiltInThemes
import com.dayforge.data.appearance.ThemeCatalogContent
import com.dayforge.data.appearance.ThemeCatalogItem
import com.dayforge.data.appearance.ThemeCatalogSlot
import com.dayforge.data.appearance.ThemeInstallPhase
import com.dayforge.data.appearance.ValidatedTheme
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.domain.appearance.DeviceThemeMode
import com.dayforge.domain.appearance.DeviceThemeSelection
import com.dayforge.domain.appearance.LoadedDeviceTheme
import com.dayforge.domain.appearance.ResolvedTheme
import com.dayforge.domain.appearance.SavedThemeSelection
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.ui.theme.DayForgeTheme
import com.dayforge.ui.theme.LocalDeviceCardStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemePresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = InstrumentationRegistry.getInstrumentation()
    private fun builtIns() = runBlocking { BuiltInThemes(app.targetContext.assets).readAll() }
    private fun loaded(mode: DeviceThemeMode, style: DeviceCardStyle): LoadedDeviceTheme {
        val themes = builtIns()
        val ocean = themes.first().definition
        val oled = themes.last().definition
        return LoadedDeviceTheme(SavedThemeSelection(1, DeviceThemeSelection(
            ThemeVersionRef(ocean.themeId, 1), ThemeVersionRef(oled.themeId, 1), mode, style)),
            ResolvedTheme.from(ocean, false), ResolvedTheme.from(oled, true))
    }

    @Test fun modeAndCardStyleReachRealComposeInTheSameSnapshotIncludingOled() {
        val light = loaded(DeviceThemeMode.LIGHT, DeviceCardStyle.FOLLOW_THEME)
        val dark = loaded(DeviceThemeMode.DARK, DeviceCardStyle.PERSONALIZED)
        val state = mutableStateOf(light)
        var observed: Triple<Int, Int, CardColorStyle>? = null
        compose.setContent {
            DayForgeTheme(theme = state.value) {
                val colors = MaterialTheme.colorScheme
                val style = LocalDeviceCardStyle.current
                SideEffect { observed = Triple(colors.background.toArgb(), colors.primary.toArgb(), style) }
                Text("content")
            }
        }
        compose.runOnIdle {
            assertEquals(Triple(0xfffdfcff.toInt(), 0xff005faf.toInt(), CardColorStyle.FOLLOW_THEME), observed)
            state.value = dark
        }
        compose.runOnIdle {
            assertEquals(Triple(0xff000000.toInt(), 0xff00bfa5.toInt(), CardColorStyle.PERSONALIZED), observed)
        }
    }

    @Test fun existingSelectorSendsExactVersionIdentityAndKeepsBuiltinLocalization() {
        val definition = builtIns().first().definition
        val ref = ThemeVersionRef(definition.themeId, 1)
        val option = ThemeChoiceSummary(ThemeCatalogItem(
            ThemeCatalogSlot(ref, "0".repeat(64), definition.themeId, ThemeInstallPhase.ACTIVE),
            BuiltInTheme.OCEAN, ThemeCatalogContent.Available(definition)), 1)
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                GlobalColorThemeSelectionDialog("Themes", listOf(option), currentThemeId = "",
                    onThemeSelected = { selected = it }, onDismiss = {})
            }
        }
        compose.onNodeWithText(app.targetContext.getString(R.string.theme_name_ocean)).performClick()
        compose.runOnIdle { assertEquals(ref.choiceKey(), selected) }
    }

    @Test fun previewDisplaysBothPalettesAndRequiresAnExplicitConfirmation() {
        val preview = runBlocking { ValidatedTheme.read { app.context.assets.open("next/theme.json") } }
        var confirmations = 0
        var cancellations = 0
        compose.setContent {
            MaterialTheme { ThemeImportPreviewDialog(preview, { confirmations++ }, { cancellations++ }) }
        }
        compose.onNodeWithText("${preview.definition.name} · v${preview.definition.revision}").assertIsDisplayed()
        compose.onNodeWithText(app.targetContext.getString(R.string.settings_light_theme)).assertIsDisplayed()
        compose.onNodeWithText(app.targetContext.getString(R.string.settings_dark_theme)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, confirmations) }
        compose.onNodeWithText(app.targetContext.getString(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertEquals(1, cancellations); assertEquals(0, confirmations) }
        compose.onNodeWithText(app.targetContext.getString(R.string.action_confirm)).performClick()
        compose.runOnIdle { assertEquals(1, confirmations) }
    }
}
