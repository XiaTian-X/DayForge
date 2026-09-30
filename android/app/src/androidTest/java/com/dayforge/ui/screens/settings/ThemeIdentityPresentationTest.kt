package com.dayforge.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.appearance.*
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.ThemeDefinition
import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeIdentityPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun source() = runBlocking { ValidatedTheme.read {
        InstrumentationRegistry.getInstrumentation().context.assets.open("next/theme.json")
    } }.definition
    private fun choice(source: ThemeDefinition, id: String, revision: Int): ThemeChoiceSummary {
        val definition = source.copy(themeId = id, revision = revision, name = "Same name")
        return ThemeChoiceSummary(ThemeCatalogItem(ThemeCatalogSlot(ThemeVersionRef(id, revision),
            "0".repeat(64), id, ThemeInstallPhase.ACTIVE), null, ThemeCatalogContent.Available(definition)), 1)
    }

    @Test fun sameNamesHaveDistinctVersionsAndNativeTouchesAndMenusKeepExactIdentity() {
        val source = source()
        val one = "11111111-1111-4111-8111-000000000001"
        val two = "22222222-2222-4222-8222-000000000002"
        val options = listOf(choice(source, one, 1), choice(source, one, 2), choice(source, two, 1))
        val selected = mutableStateOf(options[0].id)
        val selections = mutableListOf<String>()
        val edits = mutableListOf<ThemeVersionRef>()
        val exports = mutableListOf<ThemeVersionRef>()
        val deletions = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            GlobalColorThemeSelectionDialog("Themes", options, currentThemeId = selected.value,
                onThemeSelected = { selections += it; selected.value = it },
                onDeleteTheme = { deletions += it }, onShowExportOptions = { exports += it.ref },
                onEditTheme = { edits += it.ref }, onDismiss = {})
        } }
        compose.onNodeWithTag("theme-selection-list").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        for ((index, option) in options.withIndex()) {
            val suffix = if (index == 2) "00000002" else "00000001"
            compose.onNodeWithText(context.getString(R.string.theme_custom_version, option.ref.revision, suffix)).assertIsDisplayed()
        }
        compose.onNodeWithTag("theme-choice-${options[0].id}").assertIsSelected()
        val target = options[1]
        compose.onNodeWithTag("theme-choice-${target.id}").assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose.onNodeWithTag("theme-label-${target.id}", useUnmergedTree = true)
            .performTouchInput { click(center) }
        compose.onNodeWithTag("theme-choice-${target.id}").assertIsSelected()
        compose.onNodeWithTag("theme-choice-${options[0].id}").assertIsNotSelected()
        val menuTarget = options[0]
        fun menu(id: Int) {
            compose.onNodeWithTag("theme-actions-${menuTarget.id}").performClick()
            compose.onNodeWithText(context.getString(id)).performClick()
        }
        menu(R.string.color_theme_edit); menu(R.string.theme_export); menu(R.string.theme_delete)
        compose.runOnIdle {
            assertEquals(listOf(target.id), selections)
            assertEquals(target.id, selected.value)
            assertEquals(listOf(menuTarget.ref), edits); assertEquals(listOf(menuTarget.ref), exports)
            assertEquals(listOf(menuTarget.id), deletions)
        }
    }

    @Test fun narrowLargeFontRowKeepsFullNameSemanticsAndActionReachable() {
        val definition = source().copy(name = "Repeated long theme name ".repeat(3))
        val option = choice(definition, definition.themeId, 1).let { summary -> summary.copy(item = summary.item.copy(
            content = ThemeCatalogContent.Available(definition))) }
        var exports = 0; var selections = 0
        compose.setContent { MaterialTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.width(280.dp)) { GlobalColorThemeOption(option, false, false,
                    { selections++ }, onExport = { exports++ }, identitySuffix = definition.themeId.replace("-", "")) }
            }
        } }
        val title = compose.onNodeWithTag("theme-label-${option.id}", useUnmergedTree = true)
        title.assertTextEquals(definition.name)
        val layouts = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(2, layouts.single().lineCount); assertTrue(layouts.single().hasVisualOverflow)
        compose.onNodeWithTag("theme-actions-${option.id}").assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.theme_export)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, exports); assertEquals(0, selections) }
    }

    @Test fun bothCurrentSelectionCardsShowTheirOwnRevisionAndLineage() {
        val source = source()
        val one = choice(source, "11111111-1111-4111-8111-000000000001", 2)
        val two = choice(source, "22222222-2222-4222-8222-000000000002", 1)
        val options = listOf(one, two)
        var light = 0; var dark = 0
        compose.setContent { MaterialTheme { Column {
            LightThemeSelectorCard(one.id, options) { light++ }
            DarkThemeSelectorCard(two.id, options) { dark++ }
        } } }
        compose.onNodeWithTag("theme-selected-light-identity", useUnmergedTree = true)
            .assertTextEquals(context.getString(R.string.theme_custom_version, 2, "00000001"))
        compose.onNodeWithTag("theme-selected-dark-identity", useUnmergedTree = true)
            .assertTextEquals(context.getString(R.string.theme_custom_version, 1, "00000002"))
        compose.onNodeWithText(context.getString(R.string.settings_light_theme)).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_dark_theme)).performClick()
        compose.runOnIdle { assertEquals(1, light); assertEquals(1, dark) }
    }

    @Test fun unavailableCustomVersionKeepsItsIdentityAndOnlyAllowsExplicitDelete() {
        val source = source()
        val normal = choice(source, source.themeId, 2)
        val broken = normal.copy(item = normal.item.copy(content = ThemeCatalogContent.Unavailable(IOException("synthetic"))))
        var selected = 0; var edited = 0; var exported = 0; var deleted = 0
        compose.setContent { MaterialTheme {
            GlobalColorThemeOption(broken, false, false, { selected++ }, { deleted++ }, { exported++ }, { edited++ }, "00000001")
        } }
        compose.onNodeWithTag("theme-choice-${broken.id}").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.theme_unavailable_version, 2, "00000001")).assertIsDisplayed()
        compose.onNodeWithTag("theme-actions-${broken.id}").performClick()
        compose.onNodeWithText(context.getString(R.string.color_theme_edit)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.theme_export)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.theme_delete)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, deleted); assertEquals(0, selected + edited + exported) }
    }
}
