package com.dayforge.data.repository

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.TokenManager
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.widget.IsolatedWidgetRefreshRule
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.checkin.GoalCompletionActivity
import com.dayforge.widget.checkin.WidgetFactActionActivity
import com.dayforge.widget.checkin.actionIntent
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.focus.FocusWidget
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Hilt/Glance state, foreground Activity and Compose confirmation on physical testbed. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WidgetFactEntryTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var reader: WidgetFactReader
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = storage.database
    private fun id(n: Int) = "ad310000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun appearance(role: String) = ObjectAppearance(IconReference.Role(role), "#123456", "theme")

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        hilt.inject()
        sessions.exclusive {
            tokens.clearTokens()
            tokens.saveLoginSession("synthetic-widget", "synthetic-refresh", "member", id(1), false)
        }
    }

    @After fun cleanup() = runBlocking<Unit> {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in androidx.test.runner.lifecycle.Stage.entries) monitor.getActivitiesInStage(stage).toList().forEach { it.finish() }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
        com.dayforge.reminder.HabitReminderScheduler.cancelAllReminders(app)
        com.dayforge.widget.FocusWidgetAlarmScheduler.cancelScheduledRefresh(app)
    }

    private suspend fun create(type: HabitType = HabitType.CHECK_IN, once: Boolean = false, cycles: Int? = null): Long =
        habits.createHabit("Widget production $type $once", "", type, 0, "#123456",
            if (once) HabitSchedule.Once() else HabitSchedule.Daily, targetValue = if (type == HabitType.COUNTING) 2 else 1,
            bestTime = if (type == HabitType.COUNTING || cycles != null) 540 else null,
            isCountdown = type == HabitType.COUNTING, targetCycles = cycles, failMode = FailMode.LOOSE,
            appearance = appearance(if (once) "task.custom" else "habit.custom"),
            completionPolicy = if (once) "one_and_done" else "recurring", creationAuthority = creator.capture(),
            context = app.takeIf { cycles != null })
    private suspend fun claim(id: Long) = reader.read(requireNotNull(habits.getHabitById(id))).claim
    private suspend fun await(block: suspend () -> Boolean) = withTimeout(5_000) { while (!block()) delay(20) }
    private fun glanceId() = requireNotNull(GlanceAppWidgetManager(app).getGlanceIdBy(Intent().putExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID, ((System.nanoTime() and 0x1fffffff) + 1).toInt())))

    @Test fun actualGlanceStatesCarryTheRenderedIdentityAndFrozenCountRule() = runBlocking<Unit> {
        val check = create()
        val checkId = glanceId()
        CheckInWidget.refreshWidgetData(app, checkId, check)
        val state = CheckInWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, checkId)
        val checkClaim = WidgetFactClaim.decode(requireNotNull(state[CheckInWidget.ACTION_PROOF_KEY]))
        assertEquals(check, checkClaim.habitId)
        assertEquals(false, state[CheckInWidget.IS_COMPLETED_KEY])
        habits.updateIsActive(check, false, app)
        val count = create(HabitType.COUNTING)
        habits.logCompletion(app, count)
        val ticket = habits.getHabitForEditing(count)
        habits.updateHabit(ticket.value!!.copy(targetValue = 20, isCountdown = false), editAuthority = ticket.authority)
        val countId = glanceId()
        val focusId = glanceId()
        CountingWidget.refreshWidgetData(app, countId, count)
        FocusWidget.refreshWidgetData(app, focusId)
        val counter = CountingWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, countId)
        val focus = FocusWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, focusId)
        assertEquals(2, counter[CountingWidget.TARGET_VALUE_KEY])
        assertEquals(true, counter[CountingWidget.IS_COUNTDOWN_KEY])
        assertEquals(1L, counter[CountingWidget.ACTUAL_COUNT_KEY])
        val counterClaim = WidgetFactClaim.decode(requireNotNull(counter[CountingWidget.ACTION_PROOF_KEY]))
        assertEquals(count, focus[FocusWidget.PRIMARY_HABIT_ID_KEY])
        assertEquals(counterClaim, WidgetFactClaim.decode(requireNotNull(focus[FocusWidget.FACT_ACTION_PROOF_KEY])))
        assertNotEquals(checkClaim.habitUuid, counterClaim.habitUuid)
    }

    @Test fun realWidgetActivityCompletesAndRejectsStaleClickWithoutAnotherToggle() = runBlocking<Unit> {
        val task = create(once = true)
        val before = claim(task)
        suspend fun launch(claim: WidgetFactClaim) {
            ActivityScenario.launch<WidgetFactActionActivity>(claim.actionIntent(app, "toggle")).use { scenario ->
                await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            }
        }
        launch(before)
        assertTrue(habits.getOneTimeStatus(task).completed)
        val queued = db.syncOutboxDao().getAll()
        launch(before)
        assertEquals(queued, db.syncOutboxDao().getAll())
        assertEquals(1, db.completionDao().getByHabitOnce(task).size)
        launch(claim(task))
        assertFalse(habits.getOneTimeStatus(task).completed)
        assertEquals(2, db.completionDao().getByHabitOnce(task).size)
        assertNotNull(habits.getHabitById(task))
    }

    @Test fun realGoalConfirmationUsesBoundFactAndClosesWhenAccountChanges() = runBlocking<Unit> {
        val rowId = create(cycles = 1)
        fun reminder() = android.app.PendingIntent.getBroadcast(app, 0,
            com.dayforge.reminder.AndroidReminderAlarms.alarmIntent(app, rowId),
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)
        assertNotNull(reminder())
        habits.logCompletion(app, rowId)
        val before = claim(rowId)
        val row = habits.getHabitById(rowId)!!
        fun intent(claim: WidgetFactClaim) = GoalCompletionActivity.createIntent(app, rowId, row.name, 1, 1, claim)
        ActivityScenario.launch<GoalCompletionActivity>(intent(before)).use { scenario ->
            val confirm = app.getString(R.string.action_confirm_complete)
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(confirm)).fetchSemanticsNodes().isNotEmpty() }
            val ticket = habits.getHabitForEditing(rowId)
            habits.updateHabit(ticket.value!!.copy(name = "Changed goal-plan display"), editAuthority = ticket.authority)
            val queues = db.syncOutboxDao().getAll()
            compose.onNodeWithText(confirm).performClick()
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            assertEquals(queues, db.syncOutboxDao().getAll())
        }
        assertTrue(habits.getHabitById(rowId)!!.isActive)
        assertNotNull(reminder())
        ActivityScenario.launch<GoalCompletionActivity>(intent(claim(rowId))).use { scenario ->
            val confirm = app.getString(R.string.action_confirm_complete)
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(confirm)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(confirm).performClick()
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
        assertFalse(habits.getHabitById(rowId)!!.isActive)
        assertNull(reminder())
        habits.updateIsActive(rowId, true, app)
        assertNotNull(reminder())
        val current = claim(rowId)
        val queues = db.syncOutboxDao().getAll()
        ActivityScenario.launch<GoalCompletionActivity>(intent(current)).use { scenario ->
            val confirm = app.getString(R.string.action_confirm_complete)
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(confirm)).fetchSemanticsNodes().isNotEmpty() }
            sessions.exclusive { tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(2), false) }
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
        assertTrue(habits.getHabitById(rowId)!!.isActive)
        assertEquals(queues, db.syncOutboxDao().getAll())
    }
}
