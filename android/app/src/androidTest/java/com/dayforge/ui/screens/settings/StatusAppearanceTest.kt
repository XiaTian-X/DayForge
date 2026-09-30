package com.dayforge.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.SyncProgress
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.domain.appearance.DeviceThemeMode
import com.dayforge.domain.appearance.DeviceThemeSelection
import com.dayforge.domain.appearance.LoadedDeviceTheme
import com.dayforge.domain.appearance.ResolvedTheme
import com.dayforge.domain.appearance.SavedThemeSelection
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.ThemeDefinition
import com.dayforge.ui.components.ParentHabitCard
import com.dayforge.ui.theme.DayForgeTheme
import com.dayforge.ui.theme.SemanticStatus
import com.dayforge.ui.theme.StatusAppearance
import com.dayforge.ui.theme.rememberStatusAppearance
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatusAppearanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val blue = Color(0xff0000ff)
    private val green = Color(0xff008000)
    private val red = Color(0xffff0000)
    private val purple = Color(0xff800080)
    private val gray = Color(0xff444444)
    private val white = Color.White
    private val black = Color.Black
    private fun label(id: Int) = compose.activity.getString(id)
    private fun definition(): ThemeDefinition {
        val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("next/theme.json")
            .bufferedReader().use { it.readText() }
        val original = Json.decodeFromString<ThemeDefinition>(raw)
        return original.copy(
            light = original.light.copy(material = original.light.material + mapOf(
                "background" to "#FFFFFF", "surface_variant" to "#FFFFFF", "primary_container" to "#FFFFFF",
                "on_surface_variant" to "#444444", "error" to "#0000FF", "on_error" to "#FFFFFF"),
                status = original.light.status + mapOf("success" to "#008000", "on_success" to "#FFFFFF",
                    "warning" to "#FF0000", "on_warning" to "#FFFFFF", "pending" to "#800080", "on_pending" to "#FFFFFF")),
            dark = original.dark.copy(material = original.dark.material + mapOf(
                "background" to "#000000", "surface_variant" to "#000000", "primary_container" to "#000000",
                "error" to "#FF0000", "on_error" to "#000000"),
                status = original.dark.status + mapOf("success" to "#00FFFF", "on_success" to "#000000",
                    "warning" to "#FF00FF", "on_warning" to "#000000", "pending" to "#FFFF00", "on_pending" to "#000000"))
        )
    }
    private fun loaded(definition: ThemeDefinition, dark: Boolean) = LoadedDeviceTheme(
        SavedThemeSelection(1, DeviceThemeSelection(
            ThemeVersionRef(definition.themeId, definition.revision), ThemeVersionRef(definition.themeId, definition.revision),
            if (dark) DeviceThemeMode.DARK else DeviceThemeMode.LIGHT, DeviceCardStyle.FOLLOW_THEME)),
        ResolvedTheme.from(definition, false), ResolvedTheme.from(definition, true))
    private fun pixels(tag: String, color: Color): Int {
        val image = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image[x, y].toArgb() == color.toArgb()) count++
        }
        return count
    }
    private fun icon(color: Color) {
        compose.waitForIdle()
        assertTrue("expected actual icon pixels $color", pixels("sync-state-icon", color) > 10)
    }
    private fun state(description: String) = compose.onNodeWithTag("sync-state-summary")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description))

    @Test fun everySyncStateUsesSavedColorsAndErrorNeverFallsThroughToSuccess() {
        val theme = loaded(definition(), false)
        val progress = mutableStateOf<SyncProgress>(SyncProgress.Success)
        val online = mutableStateOf(true)
        compose.setContent { DayForgeTheme(theme) {
            Box(Modifier.background(white)) { SyncStatusCard(online.value, true, "12:34", null,
                progress.value, true, true, 0, {}, {}, {}) }
        } }
        icon(green); state(label(R.string.sync_success))
        compose.runOnIdle { progress.value = SyncProgress.Error("synthetic internal failure") }
        icon(blue); state(label(R.string.sync_error)); assertEquals(0, pixels("sync-state-icon", green))
        for (running in listOf(SyncProgress.UploadingChanges(1, 3), SyncProgress.Downloading, SyncProgress.Recovering)) {
            compose.runOnIdle { progress.value = running }
            icon(purple); state(label(R.string.sync_in_progress))
            compose.onNodeWithText(label(R.string.sync_now_button)).assertIsNotEnabled()
        }
        compose.runOnIdle { progress.value = SyncProgress.Error("offline error"); online.value = false }
        icon(red); state(label(R.string.sync_need_network))
        compose.runOnIdle { online.value = true; progress.value = SyncProgress.Idle }
        icon(gray); state(compose.activity.getString(R.string.sync_last_time, "12:34"))
    }

    @Test fun darkModeAndNewPaletteRevisionRepaintWithoutRegeneration() {
        val definition = definition()
        val theme = mutableStateOf(loaded(definition, false))
        val progress = mutableStateOf<SyncProgress>(SyncProgress.Success)
        val online = mutableStateOf(true)
        compose.setContent { DayForgeTheme(theme.value) {
            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                SyncStatusCard(online.value, true, "12:34", null, progress.value, true, true, 0, {}, {}, {})
            }
        } }
        icon(green)
        compose.runOnIdle { theme.value = loaded(definition, true) }
        icon(Color(0xff00ffff))
        compose.runOnIdle { progress.value = SyncProgress.Downloading }
        icon(Color(0xffffff00))
        compose.runOnIdle { progress.value = SyncProgress.Error("synthetic") }
        icon(red)
        compose.runOnIdle { online.value = false }
        icon(Color(0xffff00ff))
        val next = definition.copy(revision = 2, dark = definition.dark.copy(
            status = definition.dark.status + ("warning" to "#00FF00")))
        compose.runOnIdle { theme.value = loaded(next, true) }
        icon(Color(0xff00ff00))
        assertEquals("#FF00FF", definition.dark.status.getValue("warning"))
    }

    @Test fun syncRoleAndRejectedActionsKeepTheirOriginalEnabledAndCallbackRules() {
        val theme = loaded(definition(), false)
        val progress = mutableStateOf<SyncProgress>(SyncProgress.Idle)
        val online = mutableStateOf(true)
        val loggedIn = mutableStateOf(true)
        var sync = 0; var rejected = 0; var primary = 0
        compose.setContent { DayForgeTheme(theme) { SyncStatusCard(online.value, loggedIn.value, "12:34", null,
            progress.value, false, false, 2, { rejected++ }, { sync++ }, { primary++ }) } }
        for (ready in listOf(SyncProgress.Idle, SyncProgress.Success, SyncProgress.Error("synthetic"))) {
            compose.runOnIdle { progress.value = ready }
            compose.onNodeWithText(label(R.string.sync_now_button)).assertIsEnabled().performClick()
        }
        for (running in listOf(SyncProgress.UploadingChanges(1, 3), SyncProgress.Downloading, SyncProgress.Recovering)) {
            compose.runOnIdle { progress.value = running }
            compose.onNodeWithText(label(R.string.sync_now_button)).assertIsNotEnabled()
        }
        compose.runOnIdle { progress.value = SyncProgress.Idle; online.value = false }
        compose.onNodeWithText(label(R.string.sync_now_button)).assertIsEnabled().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.sync_rejected_count, 2)).performClick()
        compose.onNodeWithText(label(R.string.sync_device_make_primary)).performClick()
        compose.runOnIdle { assertEquals(4, sync); assertEquals(1, rejected); assertEquals(1, primary); loggedIn.value = false }
        compose.onNodeWithText(label(R.string.sync_now_button)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.sync_device_make_primary)).assertDoesNotExist()
    }

    @Test fun loggedOutCardDoesNotPresentOldSuccessfulProgressAsCurrentSuccess() {
        val theme = loaded(definition(), false)
        compose.setContent { DayForgeTheme(theme) { SyncStatusCard(true, false, "12:34", null,
            SyncProgress.Success, true, true, 0, {}, {}, {}) } }
        icon(gray); state(compose.activity.getString(R.string.sync_last_time, "12:34"))
        compose.onNodeWithText(label(R.string.sync_now_button)).assertIsNotEnabled()
        assertEquals(0, pixels("sync-state-icon", green))
    }

    @Test fun actualGoalCardKeepsResultVisibilityAndNavigationWhileUsingSavedSuccessRole() {
        val theme = loaded(definition(), false)
        val goal = mutableStateOf(HabitEntity(id = 1, name = "Reading goal", habitType = HabitType.GOAL,
            iconResId = 1, colorHex = "#112233", schedule = HabitSchedule.Daily, goalSuccess = true))
        var navigation = 0; var expands = 0
        compose.setContent { DayForgeTheme(theme) {
            Box(Modifier.background(white)) {
                ParentHabitCard(goal.value, emptyList(), 0, 0, isExpanded = false, onClick = { navigation++ },
                    onExpandToggle = { expands++ }, onChildCheckIn = { _, _ -> }, onChildUndo = {},
                    onChildIncrement = {}, onChildDecrement = {}, onChildTimerStart = { _, _ -> },
                    onChildTimerPause = {}, onChildTimerResume = {}, onChildTimerStop = {}, activeTimer = null)
            }
        } }
        compose.onNodeWithText(label(R.string.habit_card_status_success)).assertIsDisplayed()
        assertTrue(pixels("goal-result-status", green) > 10)
        compose.onNodeWithText("Reading goal").performClick()
        compose.runOnIdle { assertEquals(1, navigation); goal.value = goal.value.copy(goalSuccess = false) }
        compose.onNodeWithText(label(R.string.habit_card_status_failed)).assertIsDisplayed()
        assertTrue(pixels("goal-result-status", blue) > 10)
        compose.runOnIdle { goal.value = goal.value.copy(goalSuccess = null) }
        compose.onNodeWithTag("goal-result-status", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.content_description_expand)).performClick()
        compose.runOnIdle { assertEquals(1, navigation); assertEquals(1, expands); assertNull(goal.value.goalSuccess) }
    }

    @Test fun displayContrastUsesActualAlphaBackdropAndSeparateTextThresholds() {
        val background = mutableStateOf(Color(0x80ffffff))
        val backdrop = mutableStateOf(black)
        val status = mutableStateOf(SemanticStatus.WARNING)
        var observed: StatusAppearance? = null
        val theme = loaded(definition(), false)
        compose.setContent { DayForgeTheme(theme) {
            val colors = rememberStatusAppearance(status.value, background.value, backdrop.value)
            SideEffect { observed = colors }
        } }
        compose.runOnIdle {
            assertEquals(red, observed!!.main)
            // White on red fails normal text 4.5:1; main red remains the saved value.
            assertEquals(black, observed!!.onMain)
            assertEquals(black, observed!!.icon)
            backdrop.value = white
        }
        compose.runOnIdle { assertEquals(red, observed!!.icon); assertEquals(black, observed!!.text); status.value = SemanticStatus.SUCCESS }
        compose.runOnIdle { assertEquals(green, observed!!.icon); assertEquals(green, observed!!.text); assertEquals(white, observed!!.onMain) }
    }

    @Test fun plainMaterialCallersRetainFallbackRolesWithoutPretendingToLoadSavedTheme() {
        var observed: StatusAppearance? = null
        val status = mutableStateOf(SemanticStatus.SUCCESS)
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme(primary = blue, onPrimary = white,
            tertiary = purple, onTertiary = white, error = green, onError = white)) {
            val colors = rememberStatusAppearance(status.value, white, white)
            SideEffect { observed = colors }
        } }
        compose.runOnIdle { assertEquals(blue, observed!!.main); status.value = SemanticStatus.PENDING }
        compose.runOnIdle { assertEquals(purple, observed!!.main); status.value = SemanticStatus.ERROR }
        compose.runOnIdle { assertEquals(green, observed!!.main) }
    }
}
