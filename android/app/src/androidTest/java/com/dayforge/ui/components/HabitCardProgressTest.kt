package com.dayforge.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HabitCardProgressTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val habit = HabitEntity(name = "Walk", habitType = HabitType.CHECK_IN,
        iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, targetCycles = 30)

    @Test fun exact_long_term_counts_and_accessible_fraction_follow_updates() {
        val progress = mutableStateOf(0)
        var checkIns = 0
        var opened = 0
        compose.setContent { MaterialTheme {
            HabitCard(habit, { opened++ }, {}, {}, targetProgress = progress.value,
                onCheckIn = { checkIns += it })
        } }
        listOf(0, 7, 30, 31).forEach { count ->
            compose.runOnIdle { progress.value = count }
            compose.onNodeWithText(compose.activity.getString(R.string.habit_target_progress_format, count, 30),
                useUnmergedTree = true)
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo((count / 30f).coerceIn(0f, 1f), 0f..1f)))
        }
        // Adding progress must not hide or intercept the existing daily action.
        compose.onNodeWithText(compose.activity.getString(R.string.action_check_in)).performClick()
        compose.runOnIdle { assertEquals(1, checkIns); assertEquals(0, opened) }
    }

    @Test fun unlimited_habit_keeps_activity_rate_without_fake_target() {
        compose.setContent { MaterialTheme {
            HabitCard(habit.copy(targetCycles = null), {}, {}, {}, activityRate = 75)
        } }
        compose.onNodeWithText("75%").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun progress_label_wraps_without_ellipsis_at_narrow_width_and_large_font() {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                Box(Modifier.width(240.dp)) {
                    HabitCard(habit, {}, {}, {}, targetProgress = 7)
                }
            }
        } }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(compose.activity.getString(R.string.habit_target_progress_format, 7, 30),
            useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                it(layouts)
            }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        org.junit.Assert.assertFalse(
            "Progress overflow: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                "lines=${layout.lineCount}, constraints=${layout.layoutInput.constraints}, text=${layout.layoutInput.text}",
            layout.hasVisualOverflow
        )
    }
}
