package com.dayforge.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.appearance.BuiltInTheme
import com.dayforge.data.appearance.BuiltInThemes
import com.dayforge.data.appearance.DeviceThemeLoadState
import com.dayforge.data.appearance.ThemeCatalogContent
import com.dayforge.data.appearance.ThemeCatalogItem
import com.dayforge.data.appearance.ThemeCatalogListing
import com.dayforge.data.appearance.ThemeCatalogSlot
import com.dayforge.data.appearance.ThemeCatalogState
import com.dayforge.data.appearance.ThemeInstallPhase
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.domain.appearance.DeviceThemeMode
import com.dayforge.domain.appearance.DeviceThemeSelection
import com.dayforge.domain.appearance.SavedThemeSelection
import com.dayforge.domain.appearance.ThemeVersionRef
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeRecoveryPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    private fun ref(theme: BuiltInTheme) = ThemeVersionRef(theme.themeId, theme.revision)
    private val damaged = ThemeVersionRef("60000000-0000-4000-8000-000000000001", 1)
    private fun initial(): ThemeRecoveryState {
        val builtIns = runBlocking { BuiltInThemes(context.assets).readAll() }
        val items = builtIns.map { theme ->
            val definition = theme.definition
            val entry = BuiltInTheme.entries.single { it.themeId == definition.themeId }
            ThemeCatalogItem(ThemeCatalogSlot(ref(entry), "0".repeat(64), definition.themeId, ThemeInstallPhase.ACTIVE),
                entry, if (entry == BuiltInTheme.OCEAN) ThemeCatalogContent.Unavailable(IOException("THEME_MISSING"))
                else ThemeCatalogContent.Available(definition))
        } + ThemeCatalogItem(ThemeCatalogSlot(damaged, "0".repeat(64), damaged.themeId, ThemeInstallPhase.INSTALLING), null,
            ThemeCatalogContent.Pending)
        val saved = SavedThemeSelection(5, DeviceThemeSelection(ref(BuiltInTheme.OCEAN), ref(BuiltInTheme.DUSK),
            DeviceThemeMode.DARK, DeviceCardStyle.PERSONALIZED))
        return ThemeRecoveryState(library = ThemeCatalogListing(ThemeCatalogState(1, 7, items.map { it.slot }), saved, items),
            light = saved.selection.light, dark = saved.selection.dark)
    }

    @Test fun narrowLargeTextRecoveryRequiresAValidPairAndExplicitApply() {
        val state = mutableStateOf(initial())
        var applications = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                Box(Modifier.width(320.dp)) {
                    ThemeRecoveryContent(state.value, { ref, dark ->
                        state.value = if (dark) state.value.copy(dark = ref) else state.value.copy(light = ref)
                    }, { applications++ }, { _, _ -> fail("Unexpected delete") }, {}, {})
                }
            }
        }
        val list = compose.onNodeWithTag("theme-recovery-list")
        list.performScrollToNode(hasText(text(R.string.theme_recovery_apply)))
        compose.onNodeWithText(text(R.string.theme_recovery_apply)).assertIsNotEnabled()
        val nature = "theme-recovery-light-${BuiltInTheme.NATURE.themeId}:1"
        list.performScrollToNode(hasTestTag(nature))
        compose.onNodeWithTag(nature).performClick()
        compose.runOnIdle { assertEquals(0, applications) }
        list.performScrollToNode(hasText(text(R.string.theme_recovery_apply)))
        compose.onNodeWithText(text(R.string.theme_recovery_apply)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, applications); assertEquals(ref(BuiltInTheme.NATURE), state.value.light) }
    }

    @Test fun pendingThemeDeletionRequiresConfirmationAndRetainsExactCatalogRevision() {
        val state = initial()
        var deleted: Pair<ThemeVersionRef, Long>? = null
        compose.setContent { ThemeRecoveryContent(state, { _, _ -> }, {}, { ref, rev -> deleted = ref to rev }, {}, {}) }
        val list = compose.onNodeWithTag("theme-recovery-list")
        list.performScrollToNode(hasText(text(R.string.theme_delete)))
        compose.onNodeWithText(text(R.string.theme_delete)).performClick()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithText(text(R.string.theme_delete)).performClick()
        compose.onNodeWithText(text(R.string.action_delete)).performClick()
        compose.runOnIdle { assertEquals(damaged to 7L, deleted) }
    }

    @Test fun failureSurfaceOffersRepairWithoutLeakingProviderMessages() {
        var repairs = 0
        var retries = 0
        compose.setContent {
            ThemeLoadScreen(DeviceThemeLoadState.Failed(IOException("private/path/secret")), { retries++ }, { repairs++ })
        }
        compose.onNodeWithText("private/path/secret", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.theme_recovery_title)).performClick()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        compose.runOnIdle { assertEquals(1, repairs); assertEquals(1, retries) }
    }
}
