package com.dayforge.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/** Actual Compose components using finite inputs; original full-summary tests stay intact.
 * This is rendering correctness, not an end-to-end frame-latency benchmark. */
@RunWith(AndroidJUnit4::class)
class CountDisplayCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val habit = HabitEntity(name = "Walk", habitType = HabitType.COUNTING,
        iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, targetCycles = 30)
    @Test fun finiteCountCardPreservesLongCountdownAndUnknownContracts() {
        val today = java.time.LocalDate.now()
        fun summary(quantity: Long, target: Int?, countdown: Boolean) =
            com.dayforge.domain.model.CountHistory(today,
                target?.let { mapOf(today to com.dayforge.domain.model.CountDayPolicy(it, countdown)) } ?: emptyMap(),
                mapOf(today to quantity), target?.let { com.dayforge.domain.model.CountDayPolicy(it, countdown) },
                if (target == null) setOf(today) else emptySet(), emptyList()).summary.let { com.dayforge.data.repository.projectCountDisplay(
                    habit.copy(habitType = HabitType.COUNTING), it, java.time.ZoneId.systemDefault()) }
        data class Case(val summary: com.dayforge.domain.model.CountDisplayProjection, val label: String, val action: String?)
        val cases = listOf(
            Case(summary(4_294_967_294L, 10, false),
                compose.activity.getString(R.string.timer_countup_progress, 4_294_967_294L, 10), "increment"),
            Case(summary(2L, 3, true),
                compose.activity.getString(R.string.timer_countdown_remaining, 1L), "decrement"),
            Case(summary(3L, 3, true),
                compose.activity.getString(R.string.habit_card_status_completed), null),
            Case(summary(6L, null, false),
                compose.activity.getString(R.string.count_rule_unknown_quantity, 6L), null)
        )
        val current = mutableStateOf(cases.first())
        val actions = mutableListOf<String>()
        var opened = 0
        compose.setContent { MaterialTheme {
            val value = current.value.summary
            HabitCard(habit.copy(habitType = HabitType.COUNTING, targetValue = 99, isCountdown = false),
                { opened++ }, {}, {}, countHistory = null, countDisplay = value,
                todayCount = value.todayQuantity.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                completed = value.completedToday, onIncrement = { actions += "increment" },
                onDecrement = { actions += "decrement" })
        } }
        cases.forEach { case ->
            compose.runOnIdle { current.value = case }
            compose.onNodeWithText(case.label).assertIsDisplayed()
            val action = compose.onNodeWithText(compose.activity.getString(R.string.action_check_in))
            if (case.action == null) action.assertDoesNotExist() else {
                action.performClick()
                compose.runOnIdle { assertEquals(case.action, actions.last()) }
            }
        }
        compose.runOnIdle {
            assertEquals(listOf("increment", "decrement"), actions)
            assertEquals(0, opened)
        }
    }

    @Test fun kanbanFiniteCountCardKeepsTheCapturedRuleAndDoesNotOpenDetails() {
        val today = java.time.LocalDate.now()
        fun summary(quantity: Long, target: Int?, countdown: Boolean) =
            com.dayforge.domain.model.CountHistory(today,
                target?.let { mapOf(today to com.dayforge.domain.model.CountDayPolicy(it, countdown)) } ?: emptyMap(),
                mapOf(today to quantity), target?.let { com.dayforge.domain.model.CountDayPolicy(it, countdown) },
                if (target == null) setOf(today) else emptySet(), emptyList()).summary.let { com.dayforge.data.repository.projectCountDisplay(
                    habit.copy(habitType = HabitType.COUNTING), it, java.time.ZoneId.systemDefault()) }
        data class Case(val summary: com.dayforge.domain.model.CountDisplayProjection, val label: String, val action: String?)
        val cases = listOf(
            Case(summary(2L, 3, true),
                compose.activity.getString(R.string.timer_countdown_remaining, 1L), "decrement"),
            Case(summary(4_294_967_294L, 10, false),
                compose.activity.getString(R.string.timer_countup_progress, 4_294_967_294L, 10), "increment"),
            Case(summary(6L, null, false),
                compose.activity.getString(R.string.count_rule_unknown_quantity, 6L), null)
        )
        val current = mutableStateOf(cases.first())
        val actions = mutableListOf<Pair<String, Long>>()
        var opened = 0
        compose.setContent { MaterialTheme {
            val value = current.value.summary
            val stats = com.dayforge.data.model.HabitWithStats(
                habit.copy(id = 7L, habitType = HabitType.COUNTING, targetValue = 99,
                    isCountdown = false, targetCycles = null), value.completedToday,
                value.todayQuantity.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), null, 0, 0,
                isCheckInAllowed = value.todayPolicy != null, countDisplay = value)
            com.dayforge.ui.screens.dashboard.KanbanColumn(
                title = "Summary", habits = listOf(stats), width = 360.dp,
                activeTimer = null, cardColorStyle = com.dayforge.domain.model.CardColorStyle.FOLLOW_THEME,
                linkedMetricsByHabit = emptyMap(), pendingMetricHabits = emptySet(),
                onHabitClick = { opened++ }, onDelete = {}, onEdit = {}, onCheckIn = {},
                onUndo = { _, _ -> }, onIncrement = { actions += "increment" to it },
                onDecrement = { actions += "decrement" to it }, onTimerStart = { _, _ -> },
                onTimerPause = {}, onTimerResume = {}, onTimerStop = {}, onRecordMetrics = {},
                onMetricClick = {}, onReactivation = {},
                emptyTitleResId = R.string.dashboard_empty_checkable,
                emptyHintResId = R.string.dashboard_empty_checkable_hint)
        } }
        cases.forEach { case ->
            compose.runOnIdle { current.value = case }
            compose.onNodeWithText(case.label).assertIsDisplayed()
            val action = compose.onNodeWithText(compose.activity.getString(R.string.action_check_in))
            if (case.action == null) action.assertDoesNotExist() else {
                action.performClick()
                compose.runOnIdle { assertEquals(case.action to 7L, actions.last()) }
            }
        }
        compose.runOnIdle {
            assertEquals(listOf("decrement" to 7L, "increment" to 7L), actions)
            assertEquals(0, opened)
        }
    }

}
