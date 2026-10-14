package com.dayforge.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.model.ActiveTimerState
import com.dayforge.ui.screens.nested.ChildHabitWithStats
import com.dayforge.ui.screens.settings.IconLibraryFixture
import com.dayforge.ui.theme.LocalResolvedTheme
import com.dayforge.ui.theme.toComposeColors
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObjectIconPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fixture = ObjectIconFixture()
    private val shown = mutableStateOf(true)
    @Before fun setup() = runBlocking { fixture.open(); fixture.install(); Unit }
    @After fun cleanup() {
        try { compose.runOnIdle { shown.value = false }; compose.waitForIdle() }
        finally { runBlocking { fixture.close() } }
    }
    private fun pixels(key: String) = compose.onNodeWithTag("object-icon:$key", useUnmergedTree = true)
        .captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2].toArgb() }
    private fun awaitPixel(key: String, expected: Int) {
        compose.waitUntil(5000) { pixels(key) == expected }
        assertEquals(expected, pixels(key))
    }
    @Test fun actualCardsKeepGeometryAndActionsWithoutReopeningImagesOnTick() {
        val theme = fixture.theme()
        val appearance = ObjectAppearance(IconReference.Asset(fixture.id(11)), "#123456", "theme")
        val habit = HabitEntity(id = 1, uuid = fixture.id(91), name = "Native habit", habitType = HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily,
            completionPolicy = "recurring", appearance = appearance)
        val goal = habit.copy(uuid = fixture.id(92), name = "Native goal", habitType = HabitType.GOAL, completionPolicy = null)
        val child = habit.copy(uuid = fixture.id(93), name = "Native child")
        val metric = MetricEntity(uuid = fixture.id(94), name = "Native metric", unit = "kg",
            iconResId = 1, colorHex = "#123456", appearance = appearance)
        val compact = metric.copy(uuid = fixture.id(95), name = "Native compact")
        val timer = habit.copy(id = 2, uuid = fixture.id(97), name = "Native timer", habitType = HabitType.TIMER)
        val tick = mutableIntStateOf(0)
        var opened = 0; var checked = 0
        compose.setContent {
            if (shown.value) CompositionLocalProvider(LocalAccountIcons provides fixture.controller, LocalResolvedTheme provides theme) {
                MaterialTheme(colorScheme = theme.toComposeColors()) { Surface {
                    LazyColumn(Modifier.width(320.dp).testTag("consumer-list")) {
                        item { HabitCard(habit, { opened++ }, {}, {}, onCheckIn = { checked++ }) }
                        item { ParentHabitCard(goal, emptyList(), 0, 0, isExpanded = false, onExpandToggle = {},
                            onChildCheckIn = { _, _ -> }, onChildUndo = {}, onChildIncrement = {}, onChildDecrement = {},
                            onChildTimerStart = { _, _ -> }, onChildTimerPause = {}, onChildTimerResume = {},
                            onChildTimerStop = {}, activeTimer = null) }
                        item { ChildHabitRow(ChildHabitWithStats(child, false, 0, null, 0, 0),
                            onCheckIn = {}, onUndo = {}, onIncrement = {}, onDecrement = {}, onTimerStart = {},
                            onTimerPause = {}, onTimerResume = {}, onTimerStop = {}, activeTimer = null) }
                        item { MetricCard(metric, 2.0, null, onClick = {}) }
                        item { CompactMetricCard(compact, 2.0, {}) }
                        item { HabitCard(timer, {}, {}, {}, activeTimer = ActiveTimerState(2, tick.intValue, false, 1)) }
                    }
                } }
            }
        }
        for ((key, size) in listOf(habit.uuid to 40.dp, goal.uuid to 40.dp, child.uuid to 16.dp,
            metric.uuid to 40.dp, compact.uuid to 40.dp, timer.uuid to 40.dp)) {
            compose.onNodeWithTag("consumer-list", useUnmergedTree = true).performScrollToNode(hasTestTag("object-icon:$key"))
            awaitPixel(key, IconLibraryFixture.red)
            compose.onNodeWithTag("object-icon:$key", useUnmergedTree = true).assertWidthIsEqualTo(size).assertHeightIsEqualTo(size)
        }
        compose.onNodeWithTag("consumer-list", useUnmergedTree = true).performScrollToNode(hasText(habit.name))
        awaitPixel(habit.uuid, IconLibraryFixture.red)
        compose.onAllNodesWithText(compose.activity.getString(R.string.action_check_in)).onFirst().performClick()
        compose.onNodeWithText(habit.name).performClick()
        compose.runOnIdle { assertEquals(1, checked); assertEquals(1, opened) }
        compose.onNodeWithTag("consumer-list", useUnmergedTree = true).performScrollToNode(hasTestTag("object-icon:${timer.uuid}"))
        awaitPixel(timer.uuid, IconLibraryFixture.red)
        compose.waitForIdle(); runBlocking { fixture.controller.awaitImages() }
        val decrypts = fixture.decrypts.get(); val draws = fixture.draws.get()
        repeat(20) { compose.runOnIdle { tick.intValue++ } }
        compose.waitForIdle(); runBlocking { fixture.controller.awaitImages() }
        assertEquals(decrypts, fixture.decrypts.get()); assertEquals(draws, fixture.draws.get())
        assertEquals(appearance, habit.appearance)
    }
    @Test fun selectionDarkAndTintRefreshRealPixelsAndLogoutDropsAllConsumerImages() {
        runBlocking { fixture.choose() }
        val light = fixture.theme(); val dark = fixture.theme(true)
        val currentTheme = mutableStateOf(light)
        val objectTint = mutableStateOf(false)
        val filled = mutableStateOf(false)
        val role = ObjectAppearance(IconReference.Role("habit.exercise"), "#123456", "theme")
        val fixed = ObjectAppearance(IconReference.Asset(fixture.id(11)), "#123456", "object")
        val task = ObjectAppearance(IconReference.Asset(fixture.id(12)), "#ff123456", "theme")
        compose.setContent {
            if (shown.value) CompositionLocalProvider(LocalAccountIcons provides fixture.controller,
                LocalResolvedTheme provides currentTheme.value) {
                MaterialTheme(colorScheme = currentTheme.value.toComposeColors()) {
                    val background = if (filled.value) Color(0xff123456.toInt()) else MaterialTheme.colorScheme.surface
                    Surface(color = background) {
                    Row {
                        ObjectIcon("role", role, false, 1, Color.Red, 40.dp, background = background)
                        ObjectIcon("fixed", fixed, false, 1, Color.Red, 40.dp, background = background)
                        ObjectIcon("task", task.copy(iconTint = if (objectTint.value) "object" else "theme"), true, 1, Color.Red, 40.dp,
                            background = background)
                    }
                } }
            }
        }
        awaitPixel("role", IconLibraryFixture.red); awaitPixel("fixed", IconLibraryFixture.red)
        awaitPixel("task", light.material.getValue("primary"))
        runBlocking { fixture.install(100, 0xff0000ff.toInt()); fixture.choose(100) }
        awaitPixel("role", 0xff0000ff.toInt()); awaitPixel("fixed", IconLibraryFixture.red)
        compose.runOnIdle { objectTint.value = true }
        awaitPixel("task", 0xff123456.toInt())
        compose.runOnIdle { filled.value = true }
        awaitPixel("task", 0xffffffff.toInt()) // Same-color personalized background needs display-only contrast.
        awaitPixel("fixed", IconLibraryFixture.red) // Original pixels are never recolored.
        compose.runOnIdle { currentTheme.value = dark; filled.value = false }
        awaitPixel("role", IconLibraryFixture.green); awaitPixel("fixed", IconLibraryFixture.green)
        awaitPixel("task", 0xffffffff.toInt())
        compose.runOnIdle { objectTint.value = false }
        awaitPixel("task", dark.material.getValue("primary"))
        assertEquals("#ff123456", task.accentColor)
        val namespace = File(fixture.directory, "account-icons-v1/${fixture.id(1)}/${fixture.id(2)}/${fixture.id(3)}")
        val bytes = namespace.listFiles()!!.map { it.name to it.length() }.sortedBy { it.first }
        runBlocking { fixture.sessions.exclusive { fixture.tokens.clearTokens() } }
        compose.waitUntil(5000) { pixels("fixed") != IconLibraryFixture.green }
        compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.icon_library_image_failed),
            useUnmergedTree = true).assertCountEquals(3)
        assertEquals(bytes, namespace.listFiles()!!.map { it.name to it.length() }.sortedBy { it.first })
        assertEquals(IconReference.Asset(fixture.id(11)), fixed.icon)
    }
}
