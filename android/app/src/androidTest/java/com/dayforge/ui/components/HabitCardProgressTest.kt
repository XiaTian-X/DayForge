package com.dayforge.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
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
import com.dayforge.domain.model.ActiveTimerState
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

    @Test fun countCardUsesFrozenCountdownRuleWithoutChangingEditablePlan() {
        val today = java.time.LocalDate.now()
        val history = mutableStateOf(com.dayforge.domain.model.CountHistory(today,
            mapOf(today to com.dayforge.domain.model.CountDayPolicy(3, true)), mapOf(today to 2L),
            com.dayforge.domain.model.CountDayPolicy(3, true), emptySet(), emptyList()))
        var decrements = 0
        compose.setContent { MaterialTheme {
            HabitCard(habit.copy(habitType = HabitType.COUNTING, targetValue = 99, isCountdown = false),
                {}, {}, {}, countHistory = history.value, todayCount = 2,
                completed = history.value.completedToday, onDecrement = { decrements++ })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.timer_countdown_remaining, 1L)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.action_check_in)).performClick()
        compose.runOnIdle { assertEquals(1, decrements); history.value = history.value.copy(quantities = mapOf(today to 3L)) }
        compose.onNodeWithText(compose.activity.getString(R.string.habit_card_status_completed)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.action_check_in)).assertDoesNotExist()
    }

    @Test fun countCardShowsLongActualQuantityAndCountupRemainsActionable() {
        val today = java.time.LocalDate.now()
        val history = com.dayforge.domain.model.CountHistory(today,
            mapOf(today to com.dayforge.domain.model.CountDayPolicy(10, false)), mapOf(today to 4_294_967_294L),
            com.dayforge.domain.model.CountDayPolicy(10, false), emptySet(), emptyList())
        var increments = 0
        compose.setContent { MaterialTheme {
            HabitCard(habit.copy(habitType = HabitType.COUNTING, targetValue = 1, isCountdown = true),
                {}, {}, {}, countHistory = history, todayCount = Int.MAX_VALUE,
                completed = true, onIncrement = { increments++ })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.timer_countup_progress, 4_294_967_294L, 10)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.action_check_in)).performClick()
        compose.runOnIdle { assertEquals(1, increments) }
    }

    @Test fun unknownCountRuleShowsPreservedQuantityWithoutOfferingAnInventedTargetAction() {
        val today = java.time.LocalDate.now()
        val history = com.dayforge.domain.model.CountHistory(today, emptyMap(), mapOf(today to 6L),
            null, setOf(today), emptyList())
        compose.setContent { MaterialTheme {
            HabitCard(habit.copy(habitType = HabitType.COUNTING, targetValue = 1), {}, {}, {}, countHistory = history)
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.count_rule_unknown_quantity, 6L)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.action_check_in)).assertDoesNotExist()
    }

    @Test fun summaryOnlyCountCardPreservesLongCountdownAndUnknownContracts() {
        val today = java.time.LocalDate.now()
        fun summary(quantity: Long, target: Int?, countdown: Boolean) =
            com.dayforge.domain.model.CountHistory(today,
                target?.let { mapOf(today to com.dayforge.domain.model.CountDayPolicy(it, countdown)) } ?: emptyMap(),
                mapOf(today to quantity), target?.let { com.dayforge.domain.model.CountDayPolicy(it, countdown) },
                if (target == null) setOf(today) else emptySet(), emptyList()).summary
        data class Case(val summary: com.dayforge.domain.model.CountSummary, val label: String, val action: String?)
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
                { opened++ }, {}, {}, countHistory = null, countSummary = value,
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

    @Test fun kanbanSummaryOnlyCountCardPreservesFrozenRuleLongQuantityAndUnknown() {
        val today = java.time.LocalDate.now()
        fun summary(quantity: Long, target: Int?, countdown: Boolean) =
            com.dayforge.domain.model.CountHistory(today,
                target?.let { mapOf(today to com.dayforge.domain.model.CountDayPolicy(it, countdown)) } ?: emptyMap(),
                mapOf(today to quantity), target?.let { com.dayforge.domain.model.CountDayPolicy(it, countdown) },
                if (target == null) setOf(today) else emptySet(), emptyList()).summary
        data class Case(val summary: com.dayforge.domain.model.CountSummary, val label: String, val action: String?)
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
                isCheckInAllowed = value.todayPolicy != null, countSummary = value)
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

    @Test fun countup_timer_actions_remain_touchable_without_opening_card() =
        assertTimerActions(isCountdown = false)

    @Test fun countdown_timer_actions_remain_touchable_without_opening_card() =
        assertTimerActions(isCountdown = true)

    private fun assertTimerActions(isCountdown: Boolean) {
        val timerHabit = habit.copy(id = 7, habitType = HabitType.TIMER,
            targetValue = 1, isCountdown = isCountdown)
        val timer = mutableStateOf<ActiveTimerState?>(null)
        val completed = mutableStateOf(false)
        val actions = mutableListOf<String>()
        var opened = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                Box(Modifier.width(320.dp)) {
                    HabitCard(timerHabit, { opened++ }, {}, {},
                        completed = completed.value,
                        activeTimer = timer.value,
                        onTimerStart = {
                            actions += "start"
                            timer.value = ActiveTimerState(7, 10, false, 1)
                        },
                        onTimerPause = {
                            actions += "pause"
                            timer.value = requireNotNull(timer.value).copy(isPaused = true)
                        },
                        onTimerResume = {
                            actions += "resume"
                            timer.value = requireNotNull(timer.value).copy(isPaused = false)
                        },
                        onTimerStop = {
                            actions += "stop"
                            timer.value = null
                            completed.value = true
                        }
                    )
                }
            }
        } }
        listOf(R.string.action_start_timer, R.string.action_pause,
            R.string.action_resume, R.string.action_stop).forEach { label ->
            compose.onNodeWithContentDescription(compose.activity.getString(label))
                .assertIsDisplayed().performTouchInput { click() }
            compose.waitForIdle()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.habit_card_status_completed))
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_start_timer))
            .assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("start", "pause", "resume", "stop"), actions)
            assertEquals(0, opened)
        }
    }
    private data class BusyControls(
        val habitType: HabitType = HabitType.CHECK_IN,
        val completed: Boolean = false,
        val undoAvailable: Boolean = false,
        val timerState: TimerState = TimerState.NOT_RUNNING,
        val countdown: Boolean = false,
        val prompt: Boolean = false,
        val allowed: Boolean = true,
        val actionsEnabled: Boolean = true,
        val failed: Boolean = false,
        val goalCompleted: Boolean = false
    )
    private data class BusyAction(val label: Int, val callback: String, val icon: Boolean = false)

    /** Real component/semantics/touches; callback counts prove UI admission, not timer SQL. */
    private fun showBusyControls(state: androidx.compose.runtime.MutableState<BusyControls>,
        busy: androidx.compose.runtime.MutableState<Boolean>, calls: MutableList<String>) {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                Box(Modifier.width(240.dp).testTag("busy-controls-host")) {
                    val value = state.value
                    CompletionButton(completed = value.completed, undoAvailable = value.undoAvailable,
                        habitType = value.habitType, targetValue = 1, currentCount = 12,
                        timerState = value.timerState, isCountdown = value.countdown,
                        onCheckIn = { calls += "check:$it" }, onUndo = { calls += "undo" },
                        onTimerStart = { calls += "start" }, onTimerPause = { calls += "pause" },
                        onTimerResume = { calls += "resume" }, onTimerStop = { calls += "stop" },
                        showMetricPrompt = value.prompt, onRecordMetrics = { calls += "metric" },
                        isCheckInAllowed = value.allowed, actionsEnabled = value.actionsEnabled,
                        hasFailed = value.failed, isGoalCompleted = value.goalCompleted,
                        onReactivation = { calls += "reactivate" }, busy = busy.value,
                        modifier = Modifier.requiredWidth(232.dp).heightIn(min = 72.dp)
                            .testTag("busy-controls-action"))
                }
            }
        } }
    }

    private fun busyNode(action: BusyAction): SemanticsNodeInteraction =
        if (action.icon) compose.onNodeWithContentDescription(compose.activity.getString(action.label))
        else compose.onNodeWithText(compose.activity.getString(action.label))

    // A merged action may include another independent clickable child. Real touches target
    // the visible label/icon leaf; action semantics remain checked on the merged node.
    private fun busyTouchNode(action: BusyAction): SemanticsNodeInteraction =
        if (action.icon) compose.onNodeWithContentDescription(compose.activity.getString(action.label), useUnmergedTree = true)
        else compose.onNodeWithText(compose.activity.getString(action.label), useUnmergedTree = true)

    private fun assertBusyCycle(busy: androidx.compose.runtime.MutableState<Boolean>, calls: MutableList<String>,
        actions: List<BusyAction>, visibleLabel: Int? = null) {
        compose.waitForIdle()
        val root = compose.onNodeWithTag("busy-controls-action")
        val originalBounds = root.fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        // Actual external layout constraints, not merely equality against a shrunken baseline.
        assertEquals(232f * density, originalBounds.width, 1f)
        org.junit.Assert.assertTrue(originalBounds.height + 1f >= 72f * density)
        val actionBounds = actions.map { busyNode(it).fetchSemanticsNode().boundsInRoot }
        val touchBounds = actions.mapIndexed { index, action ->
            val bounds = busyTouchNode(action).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val parent = actionBounds[index]
            org.junit.Assert.assertTrue("Visible touch leaf must be nonempty inside its action: $bounds / $parent",
                bounds.width > 0f && bounds.height > 0f && bounds.left >= parent.left && bounds.top >= parent.top &&
                    bounds.right <= parent.right && bounds.bottom <= parent.bottom)
            bounds
        }
        for (first in touchBounds.indices) for (second in first + 1 until touchBounds.size) {
            val left = touchBounds[first]; val right = touchBounds[second]
            org.junit.Assert.assertFalse("Distinct visible touch leaves overlap: $left / $right",
                left.left < right.right && right.left < left.right && left.top < right.bottom && right.top < left.bottom)
        }
        fun touchNode(index: Int, stage: String): SemanticsNodeInteraction {
            val action = actions[index]
            val node = busyTouchNode(action).assertIsDisplayed()
            val actual = node.fetchSemanticsNode().boundsInRoot
            android.util.Log.i("BusyControlTouch", "stage=$stage label=${action.label} action=${actionBounds[index]} leaf=$actual")
            assertEquals("Visible touch point moved during $stage", touchBounds[index], actual)
            return node
        }
        val labelBounds = visibleLabel?.let {
            compose.onNodeWithText(compose.activity.getString(it)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        val hostBounds = compose.onNodeWithTag("busy-controls-host").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(originalBounds.left >= hostBounds.left && originalBounds.right <= hostBounds.right)
        compose.runOnIdle { calls.clear() }
        actions.forEachIndexed { index, action ->
            busyNode(action).assertIsEnabled()
            touchNode(index, "idle").performTouchInput { click(center) }
        }
        compose.runOnIdle { assertEquals(actions.map { it.callback }, calls); calls.clear(); busy.value = true }
        root.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            compose.activity.getString(R.string.metric_submission_processing)))
        assertEquals(originalBounds, root.fetchSemanticsNode().boundsInRoot)
        actions.forEachIndexed { index, action ->
            val node = busyNode(action).assertIsDisplayed().assertIsNotEnabled()
            assertEquals(actionBounds[index], node.fetchSemanticsNode().boundsInRoot)
            touchNode(index, "busy").performTouchInput { click(center); click(center) }
            // A disabled node may omit OnClick. If retained, its real semantics callback
            // must also refuse busy input; no direct business callback is invoked here.
            val semanticClick = node.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.action
            compose.runOnIdle { semanticClick?.invoke() }
        }
        if (visibleLabel != null) assertEquals(labelBounds,
            compose.onNodeWithText(compose.activity.getString(visibleLabel)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertEquals(emptyList<String>(), calls); busy.value = false }
        compose.waitForIdle()
        assertEquals(originalBounds, root.fetchSemanticsNode().boundsInRoot)
        org.junit.Assert.assertNull(root.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        actions.forEachIndexed { index, action ->
            busyNode(action).assertIsEnabled()
            touchNode(index, "restored").performTouchInput { click(center) }
        }
        compose.runOnIdle { assertEquals(actions.map { it.callback }, calls) }
    }

    @Test fun nonCountCheckAndUndoBusyPreserveGeometryRejectRepeatedInputAndRestorePrompt() {
        val state = mutableStateOf(BusyControls())
        val busy = mutableStateOf(false)
        val calls = mutableListOf<String>()
        showBusyControls(state, busy, calls)
        assertBusyCycle(busy, calls, listOf(BusyAction(R.string.action_check_in, "check:1")))
        compose.runOnIdle { state.value = BusyControls(completed = true, undoAvailable = true, prompt = true) }
        // CHECK and ONCE share this completed/undo surface; the separate prompt stays visible.
        assertBusyCycle(busy, calls, listOf(BusyAction(R.string.habit_card_status_completed, "undo"),
            BusyAction(R.string.post_checkin_record, "metric")))
    }

    @Test fun nonCountTimerBusyPreservesGeometryAndOriginalStartPauseResumeStopAndPrompt() {
        val state = mutableStateOf(BusyControls(habitType = HabitType.TIMER))
        val busy = mutableStateOf(false)
        val calls = mutableListOf<String>()
        showBusyControls(state, busy, calls)
        for (countdown in listOf(false, true)) {
            compose.runOnIdle { state.value = BusyControls(habitType = HabitType.TIMER, countdown = countdown) }
            assertBusyCycle(busy, calls, listOf(BusyAction(R.string.action_start_timer, "start", icon = true)))
            compose.runOnIdle { state.value = BusyControls(habitType = HabitType.TIMER,
                timerState = TimerState.RUNNING, countdown = countdown, allowed = false, actionsEnabled = false) }
            assertBusyCycle(busy, calls, listOf(BusyAction(R.string.action_pause, "pause", icon = true),
                BusyAction(R.string.action_stop, "stop", icon = true)))
            compose.runOnIdle { state.value = state.value.copy(timerState = TimerState.PAUSED) }
            assertBusyCycle(busy, calls, listOf(BusyAction(R.string.action_resume, "resume", icon = true),
                BusyAction(R.string.action_stop, "stop", icon = true)))
            compose.runOnIdle { state.value = BusyControls(habitType = HabitType.TIMER, completed = true,
                countdown = countdown, prompt = true, goalCompleted = true) }
            // The existing necessary prompt keeps priority over goal-completed reactivation.
            assertBusyCycle(busy, calls, listOf(BusyAction(R.string.action_record_metrics, "metric")),
                visibleLabel = R.string.habit_card_status_completed)
        }
    }

    @Test fun nonCountBusyKeepsFailedGoalAndDisabledStateWithoutInventingActionsOrSuccess() {
        val state = mutableStateOf(BusyControls(failed = true, actionsEnabled = false))
        val busy = mutableStateOf(false)
        val calls = mutableListOf<String>()
        showBusyControls(state, busy, calls)
        assertBusyCycle(busy, calls, listOf(BusyAction(R.string.status_already_failed, "reactivate")))
        compose.runOnIdle { state.value = BusyControls(goalCompleted = true, actionsEnabled = false) }
        assertBusyCycle(busy, calls, listOf(BusyAction(R.string.status_goal_completed, "reactivate")))
        compose.runOnIdle { state.value = BusyControls(allowed = false) }
        assertBusyCycle(busy, calls, emptyList(), visibleLabel = R.string.habit_card_status_non_checkin_day)
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onNodeWithText(compose.activity.getString(R.string.habit_card_status_completed)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.action_check_in)).assertDoesNotExist()
    }

}
