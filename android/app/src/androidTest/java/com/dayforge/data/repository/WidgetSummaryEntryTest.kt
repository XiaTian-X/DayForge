package com.dayforge.data.repository

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.di.WidgetEntryPoint
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.util.DateTimeUtils
import com.dayforge.widget.IsolatedWidgetRefreshRule
import com.dayforge.widget.motivation.MotivationWidget
import com.dayforge.widget.progress.ProgressWidget
import com.dayforge.widget.timer.WidgetTimerRefreshCallback
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.*
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production Hilt/Room/Glance persistence, not installed launcher or RemoteViews pixels. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WidgetSummaryEntryTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var calculator: HabitStatusCalculator
    @Inject lateinit var publisher: WidgetDisplayPublisher
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = storage.database
    private fun id(n: Int) = "ad320000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun glanceId() = requireNotNull(GlanceAppWidgetManager(app).getGlanceIdBy(Intent().putExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID, ((System.nanoTime() and 0x1fffffff) + 1).toInt())))

    @Before fun setup() = runBlocking<Unit> {
        hilt.inject()
        sessions.exclusive {
            tokens.clearTokens()
            tokens.saveLoginSession("synthetic-summary", "synthetic-refresh", "member", id(1), false)
        }
    }

    @After fun cleanup() = runBlocking<Unit> {
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
        com.dayforge.widget.FocusWidgetAlarmScheduler.cancelScheduledRefresh(app)
        com.dayforge.reminder.HabitReminderScheduler.cancelAllReminders(app)
    }

    private suspend fun create(type: HabitType, once: Boolean = false): Long = habits.createHabit(
        "Summary $type $once", "", type, 0, "#123456",
        if (once) HabitSchedule.Once() else HabitSchedule.Daily, targetValue = if (type == HabitType.COUNTING) 2 else 1,
        isCountdown = type == HabitType.COUNTING,
        failMode = if (once) com.dayforge.data.model.FailMode.LOOSE else com.dayforge.data.model.FailMode.STRICT,
        appearance = ObjectAppearance(IconReference.Role(if (once) "task.custom" else "habit.custom"), "#123456", "theme"),
        completionPolicy = if (type == HabitType.GOAL) null else if (once) "one_and_done" else "recurring",
        creationAuthority = creator.capture())
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun refresh(progress: androidx.glance.GlanceId, motivation: androidx.glance.GlanceId) {
        ProgressWidget.refreshWidgetData(app, progress)
        MotivationWidget.refreshWidgetData(app, motivation)
    }

    @Test fun summariesPreserveFrozenCountRuleExcludeTasksAndGoalsAndUpdateOnlyRequestedInstance() = runBlocking<Unit> {
        assertSame(publisher, WidgetEntryPoint.from(app).displayPublisher())
        val check = create(HabitType.CHECK_IN)
        val count = create(HabitType.COUNTING)
        create(HabitType.CHECK_IN, once = true)
        create(HabitType.GOAL)
        habits.logCompletion(app, check); habits.logCompletion(app, count)
        val ticket = habits.getHabitForEditing(count)
        habits.updateHabit(ticket.value!!.copy(targetValue = 1, isCountdown = false), editAuthority = ticket.authority)
        val progress = glanceId(); val motivation = glanceId(); val untouched = glanceId()
        updateAppWidgetState(app, untouched) { it[ProgressWidget.COMPLETED_COUNT_KEY] = 777 }
        val before = proof()
        refresh(progress, motivation)
        val p = ProgressWidget().getAppWidgetState<Preferences>(app, progress)
        val m = MotivationWidget().getAppWidgetState<Preferences>(app, motivation)
        assertEquals(true, p[ProgressWidget.DATA_LOADED_KEY]); assertEquals(false, p[ProgressWidget.READ_FAILED_KEY])
        assertEquals(1, p[ProgressWidget.COMPLETED_COUNT_KEY]); assertEquals(2, p[ProgressWidget.TOTAL_COUNT_KEY])
        assertEquals(0.5f, requireNotNull(p[ProgressWidget.PROGRESS_KEY]), 0f)
        assertEquals(1, m[MotivationWidget.COMPLETED_TODAY_KEY]); assertEquals(2, m[MotivationWidget.TOTAL_HABITS_KEY])
        assertEquals(1, m[MotivationWidget.BEST_STREAK_KEY])
        assertTrue(m[MotivationWidget.MESSAGE_KEY] in listOf(app.getString(R.string.motivation_3days_1),
            app.getString(R.string.motivation_3days_2)))
        assertEquals(777, ProgressWidget().getAppWidgetState<Preferences>(app, untouched)[ProgressWidget.COMPLETED_COUNT_KEY])
        assertEquals(before, proof())
        habits.logCompletion(app, count)
        refresh(progress, motivation)
        assertEquals(2, ProgressWidget().getAppWidgetState<Preferences>(app, progress)[ProgressWidget.COMPLETED_COUNT_KEY])
        assertEquals(2, MotivationWidget().getAppWidgetState<Preferences>(app, motivation)[MotivationWidget.COMPLETED_TODAY_KEY])
    }

    @Test fun corruptTypedSourceHidesBothSummariesAndReadRetryReloadsBeforeHostSubmission() = runBlocking<Unit> {
        val check = create(HabitType.CHECK_IN)
        habits.logCompletion(app, check)
        val progress = glanceId(); val motivation = glanceId()
        refresh(progress, motivation)
        db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=CAST(value AS BLOB)")
        val damaged = proof()
        refresh(progress, motivation)
        val p = ProgressWidget().getAppWidgetState<Preferences>(app, progress)
        val m = MotivationWidget().getAppWidgetState<Preferences>(app, motivation)
        assertEquals(false, p[ProgressWidget.DATA_LOADED_KEY]); assertEquals(true, p[ProgressWidget.READ_FAILED_KEY])
        assertEquals(false, m[MotivationWidget.DATA_LOADED_KEY]); assertEquals(true, m[MotivationWidget.READ_FAILED_KEY])
        assertEquals(damaged, proof())
        db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=1")
        val restored = proof()
        mockkConstructor(ProgressWidget::class, MotivationWidget::class)
        try {
            // Only submission to a non-installed host is isolated; the actual loader runs.
            coEvery { anyConstructed<ProgressWidget>().update(app, progress) } coAnswers {
                val state = ProgressWidget().getAppWidgetState<Preferences>(app, progress)
                assertEquals(false, state[ProgressWidget.READ_FAILED_KEY])
                assertEquals(true, state[ProgressWidget.DATA_LOADED_KEY])
                assertEquals(1, state[ProgressWidget.COMPLETED_COUNT_KEY])
            }
            coEvery { anyConstructed<MotivationWidget>().update(app, motivation) } coAnswers {
                val state = MotivationWidget().getAppWidgetState<Preferences>(app, motivation)
                assertEquals(false, state[MotivationWidget.READ_FAILED_KEY])
                assertEquals(true, state[MotivationWidget.DATA_LOADED_KEY])
                assertEquals(1, state[MotivationWidget.COMPLETED_TODAY_KEY])
            }
            val retry = WidgetTimerRefreshCallback()
            for ((kind, widgetId) in listOf("progress" to progress, "motivation" to motivation)) {
                retry.onAction(app, widgetId, actionParametersOf(ActionParameters.Key<String>("widget") to kind))
            }
            coVerify(exactly = 1) { anyConstructed<ProgressWidget>().update(app, progress) }
            coVerify(exactly = 1) { anyConstructed<MotivationWidget>().update(app, motivation) }
        } finally { unmockkConstructor(ProgressWidget::class, MotivationWidget::class) }
        assertEquals(restored, proof())
    }

    @Test fun accountReplacementRejectsActualLateSummaryAndItsSourceFailure() = runBlocking<Unit> {
        val check = create(HabitType.CHECK_IN)
        habits.logCompletion(app, check)
        val original = requireNotNull(habits.getHabitById(check))
        val before = proof()
        // Suspend only after the real calculator/account/Room read. No fake stats or authority.
        mockkObject(WidgetEntryPoint.Companion)
        val gated = mockk<HabitStatusCalculator>()
        try {
            every { WidgetEntryPoint.calculator(app.applicationContext, db) } returns gated
            for (motivation in listOf(false, true)) for (fail in listOf(false, true)) {
                sessions.exclusive { tokens.saveLoginSession("synthetic-summary", "synthetic-refresh", "member", id(1), false) }
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                coEvery { gated.calculate(any(), any(), any()) } coAnswers {
                    val stats = calculator.calculate(firstArg(), secondArg(), thirdArg())
                    assertEquals(original, stats.habit)
                    entered.complete(Unit); release.await()
                    if (fail) throw IOException("old summary source unavailable")
                    stats
                }
                val widgetId = glanceId()
                val reading = async(Dispatchers.IO) {
                    if (motivation) MotivationWidget.refreshWidgetData(app, widgetId)
                    else ProgressWidget.refreshWidgetData(app, widgetId)
                }
                try {
                    withTimeout(5000) { entered.await() }
                    sessions.exclusive {
                        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false)
                        updateAppWidgetState(app, widgetId) {
                            if (motivation) {
                                it[MotivationWidget.COMPLETED_TODAY_KEY] = 123
                                it[MotivationWidget.DATA_LOADED_KEY] = true
                                it[MotivationWidget.READ_FAILED_KEY] = false
                            } else {
                                it[ProgressWidget.COMPLETED_COUNT_KEY] = 123
                                it[ProgressWidget.DATA_LOADED_KEY] = true
                                it[ProgressWidget.READ_FAILED_KEY] = false
                            }
                        }
                    }
                    release.complete(Unit)
                    withTimeout(5000) { reading.await() }
                    val state = if (motivation) MotivationWidget().getAppWidgetState<Preferences>(app, widgetId)
                        else ProgressWidget().getAppWidgetState<Preferences>(app, widgetId)
                    assertEquals(123, state[if (motivation) MotivationWidget.COMPLETED_TODAY_KEY else ProgressWidget.COMPLETED_COUNT_KEY])
                    assertEquals(false, state[if (motivation) MotivationWidget.READ_FAILED_KEY else ProgressWidget.READ_FAILED_KEY])
                    assertEquals(before, proof())
                } finally { release.complete(Unit); reading.cancelAndJoin() }
            }
        } finally { unmockkObject(WidgetEntryPoint.Companion) }
    }

    @Test fun legacyMotivationKeepsRecordedCountDaysAndQualifiedTimerDaysWithoutBusinessWrites() = runBlocking<Unit> {
        val counter = db.habitDao().insert(HabitEntity(name = "Legacy count", habitType = HabitType.COUNTING,
            iconResId = 1, colorHex = "#123456", targetValue = 5, schedule = HabitSchedule.Daily))
        val timer = db.habitDao().insert(HabitEntity(name = "Legacy timer", habitType = HabitType.TIMER,
            iconResId = 1, colorHex = "#123456", targetValue = 1, schedule = HabitSchedule.Daily))
        // The count is below target every day, but the original motivation streak is 3.
        for (n in 0..2) {
            val date = DateTimeUtils.today().minusDays(n.toLong())
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            db.completionDao().insert(CompletionEntity(habitId = counter, date = date, value = 1))
            db.timeLogDao().insert(TimeLogEntity(habitId = timer, startTime = date,
                endTime = date + 60_000, durationSeconds = if (n == 2) 59 else 60, date = date))
        }
        val progress = glanceId(); val motivation = glanceId()
        val before = proof()
        refresh(progress, motivation)
        val m = MotivationWidget().getAppWidgetState<Preferences>(app, motivation)
        assertEquals(3, m[MotivationWidget.BEST_STREAK_KEY])
        assertEquals(1, m[MotivationWidget.COMPLETED_TODAY_KEY]); assertEquals(2, m[MotivationWidget.TOTAL_HABITS_KEY])
        assertEquals(0.5f, requireNotNull(ProgressWidget().getAppWidgetState<Preferences>(app, progress)[ProgressWidget.PROGRESS_KEY]), 0f)
        assertEquals(before, proof())
        // Remove only the synthetic legacy count: timer streak still requires its target.
        db.habitDao().delete(requireNotNull(db.habitDao().getHabitById(counter)))
        MotivationWidget.refreshWidgetData(app, motivation)
        assertEquals(2, MotivationWidget().getAppWidgetState<Preferences>(app, motivation)[MotivationWidget.BEST_STREAK_KEY])
        assertNotEquals(before, proof()) // Only this explicit fixture deletion changed business rows.
        val after = proof()
        MotivationWidget.refreshWidgetData(app, motivation)
        assertEquals(after, proof())
    }
}
