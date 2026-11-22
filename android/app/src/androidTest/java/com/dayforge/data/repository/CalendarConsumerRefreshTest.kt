package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.model.HabitDraft
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.screens.dashboard.*
import com.dayforge.ui.screens.habitdetail.HabitDetailViewModel
import com.dayforge.ui.screens.nested.*
import com.dayforge.ui.screens.profile.ProfileViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Four actual ViewModels, real account/Room, and controlled presentation events (not the device clock). */
@RunWith(AndroidJUnit4::class)
class CalendarConsumerRefreshTest : NextObjectEditorFixture() {
    private data class Models(val dashboard: DashboardViewModel, val nested: NestedViewModel,
        val profile: ProfileViewModel, val detail: HabitDetailViewModel)
    private suspend fun models(prefs: PreferencesManager, completion: HabitCompletionCoordinator? = null,
        gate: CountActionGate? = null): Models {
        val repo = creatingHabits(); val metrics = creatingMetrics()
        val count = CountHistoryReader(db, tokens, sessions)
        val timerWriter = NextTimerWriter(db, tokens, sessions)
        val failure = FailureChecker(db.completionDao(), db.timeLogDao(), count)
        val status = HabitStatusCalculator(failure, db.completionDao(), db.timeLogDao(), timerWriter = timerWriter, countHistoryReader = count)
        val linked = LinkedMetricCoordinator(app, prefs, metrics, repo,
            habitPreferences = HabitPreferenceRepository(db, tokens, sessions, prefs,
                ReplicaPresentationRepository(db, tokens, sessions)))
        val complete = completion ?: HabitCompletionCoordinator(app, CheckInService(repo, db.completionDao(), db.timeLogDao()), repo, metrics)
        val timers = HabitTimerCoordinator(TimerManager(app, db.habitDao(), db.timeLogDao(), timerWriter),
            ActiveTimerStateProvider(db.timeLogDao(), repo, app, timerWriter))
        return withContext(Dispatchers.Main) {
            Models(own(DashboardViewModel(app, repo, complete, HabitDeletionCoordinator(app, repo),
                DashboardHabitListBuilder(status), DashboardTimeWindowTicker(repo, prefs), HabitLifecycleCoordinator(app, repo),
                timers, db.timeLogDao(), db.habitDao(), prefs, db.completionDao(), metrics, linked, MetricOverviewProvider(metrics),
                DashboardReadRepository(db, tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions),
                    count, CheckHistoryReader(db, tokens, sessions)), gate)),
                own(NestedViewModel(app, db.habitDao(), db.timeLogDao(), repo,
                    NestedHabitTreeBuilder(db.habitDao(), db.completionDao(), db.timeLogDao(), failure, timerWriter = timerWriter, countHistoryReader = count),
                    complete, HabitDeletionCoordinator(app, repo), prefs, linked, HabitLifecycleCoordinator(app, repo), timers, gate)),
                own(ProfileViewModel(repo, db.timeLogDao(), status, prefs)),
                own(HabitDetailViewModel(app, repo, prefs, db.timeLogDao(), db.completionDao(), db.habitDao(),
                    db.habitMetricLinkDao(), db.metricDao(), db.metricLogDao(), countActions = gate)).also { it.loadHabit(habit.id) })
                .also { visible ->
                    // Model visible screens, not an abandoned StateFlow.value observer.
                    visible.dashboard.habitsWithStats.launchIn(visible.dashboard.viewModelScope)
                    visible.nested.topLevelHabitsWithChildren.launchIn(visible.nested.viewModelScope)
                    visible.profile.todayProgress.launchIn(visible.profile.viewModelScope)
                }
        }
    }
    private suspend fun setupCount() {
        val goal = HabitDraft(id = id(180), name = "Container", habitType = com.dayforge.data.model.HabitType.GOAL,
            appearance = ObjectAppearance(IconReference.Role("goal.custom"), "#123456", "theme"))
        creatingHabits().createGoal(goal, emptyList(), creationAuthority = creator.capture())
        producer().write(local()) { db.habitDao().update(requireNotNull(db.habitDao().getHabitById(habit.id)).copy(parentHabitId = goal.id)) }
        creatingHabits().logCompletion(app, habit.id, 6)
    }
    private suspend fun Models.ready() {
        withTimeout(5000) {
            dashboard.habitsWithStats.first { rows -> rows.any { it.habit.id == habit.id && it.actualTodayCount == 6L } }
            nested.topLevelHabitsWithChildren.first { rows -> rows.flatMap { it.children }.any { it.habit.id == habit.id && it.actualTodayCount == 6L } }
            // The fixture also contains an unstarted timer; both eligible habits remain visible.
            profile.todayProgress.first { it == (0 to 2) }
            detail.uiState.first { !it.isLoading && it.countHistory?.todayQuantity == 6L }
            assertFalse(dashboard.readError.value); assertFalse(nested.readError.value); assertFalse(profile.readError.value)
            assertFalse(detail.uiState.value.readError)
            val row = requireNotNull(detail.uiState.value.habit)
            val expectedRate = ActivityRateCalculator.calculate(row.schedule, row.createdAt,
                listOf(com.dayforge.util.DateTimeUtils.today()))
            assertEquals(expectedRate, detail.uiState.value.activityRate)
            val main = dashboard.habitsWithStats.value.single { it.habit.id == habit.id }
            val child = nested.topLevelHabitsWithChildren.value.flatMap { it.children }.single { it.habit.id == habit.id }
            assertEquals(expectedRate, main.activityRate); assertEquals(expectedRate, child.activityRate)
            assertFalse(main.completedToday); assertFalse(child.completedToday) // participation does not qualify six of ten
        }
    }
    private suspend fun Models.failed() = withTimeout(5000) {
        dashboard.readError.first { it }; nested.readError.first { it }; profile.readError.first { it }
        detail.uiState.first { it.readError }
        dashboard.habitsWithStats.first { it.isEmpty() }; nested.topLevelHabitsWithChildren.first { it.isEmpty() }
        profile.todayProgress.first { it == (0 to 0) }
        assertNull(detail.uiState.value.habit); assertNull(detail.uiState.value.writeAuthority)
    }
    private fun peerRule(target: Int) {
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,
            null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE count_days SET targetValue=? WHERE habitId=?", arrayOf<Any>(target, habit.id))
        }
    }

    @Test fun allReadersRecoverExplicitlyWithoutPretendingBadRulesAreEmptyData() = runBlocking<Unit> {
        setupCount(); val models = models(preferences); models.ready()
        val facts = db.completionDao().getByHabitOnce(habit.id); val queue = db.syncOutboxDao().getAll()
        db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE count_days SET targetValue=0 WHERE habitId=?", arrayOf(habit.id)) }
        models.failed()
        peerRule(10) // Peer bypasses Room invalidation: only the explicit retry can refresh the readers.
        withContext(Dispatchers.Main) {
            models.dashboard.retryRead(); models.nested.retryRead(); models.profile.retryRead(); models.detail.retryRead()
        }
        models.ready()
        assertEquals(facts, db.completionDao().getByHabitOnce(habit.id)); assertEquals(queue, db.syncOutboxDao().getAll())
    }

    @Test fun sameDateZoneAndMidnightSignalsRereadAllConsumersWithoutDatabaseNotifications() = runBlocking<Unit> {
        setupCount()
        var now = ZonedDateTime.of(2026, 10, 8, 12, 0, 0, 0, ZoneId.of("UTC"))
        val calendar = DeviceCalendar { now }; val prefs = PreferencesManager(dataStore, calendar)
        val models = models(prefs); models.ready()
        val day = db.countDayDao().forHabit(habit.id).single(); val facts = db.completionDao().getByHabitOnce(habit.id)
        val queue = db.syncOutboxDao().getAll()
        peerRule(0)
        assertFalse(models.dashboard.readError.value)
        now = now.withZoneSameInstant(ZoneId.of("Pacific/Honolulu")); calendar.refresh()
        models.failed()
        peerRule(10)
        now = now.toLocalDate().plusDays(1).atStartOfDay(now.zone); calendar.refresh()
        models.ready()
        assertEquals(day, db.countDayDao().forHabit(habit.id).single())
        assertEquals(facts, db.completionDao().getByHabitOnce(habit.id)); assertEquals(queue, db.syncOutboxDao().getAll())
    }

    @Test fun staleCountActionsUseTheExistingErrorRouteAndDoNotCrashOrCreateRecords() = runBlocking<Unit> {
        setupCount()
        val completion = mockk<HabitCompletionCoordinator>()
        coEvery { completion.incrementCount(any(), any(), any()) } throws IllegalStateException("COUNT_DAY_INVALID")
        coEvery { completion.decrementCount(any(), any()) } throws IllegalStateException("COUNT_DAY_INVALID")
        val models = models(preferences, completion)
        models.ready()
        val facts = db.completionDao().getByHabitOnce(habit.id); val queue = db.syncOutboxDao().getAll()
        withContext(Dispatchers.Main) {
            models.dashboard.incrementCount(habit.id); models.dashboard.decrementCount(habit.id)
            models.nested.incrementCount(habit.id); models.nested.decrementCount(habit.id)
        }
        coVerify(exactly = 2) { completion.incrementCount(habit.id, any(), any()) }
        coVerify(exactly = 2) { completion.decrementCount(habit.id, any()) }
        assertEquals(facts, db.completionDao().getByHabitOnce(habit.id)); assertEquals(queue, db.syncOutboxDao().getAll())
    }

    @Test fun actualHomeGoalAndDetailEntriesShareCountAdmissionAndResumeNormally() = runBlocking<Unit> {
        setupCount()
        var clock = 0L
        val gate = CountActionGate(tokens, { clock }, { _, _ -> })
        val models = models(preferences, gate = gate)
        models.ready()
        val identity = requireNotNull(models.dashboard.habitsWithStats.value.single { it.habit.id == habit.id }.countActionIdentity)
        val lease = gate.tryAcquire(identity)!!
        val before = db.syncOutboxDao().getAll()
        withContext(Dispatchers.Main) {
            repeat(5) {
                models.dashboard.incrementCount(habit.id); models.dashboard.decrementCount(habit.id)
                models.dashboard.logCompletion(habit.id)
                models.nested.incrementCount(habit.id); models.nested.decrementCount(habit.id)
                models.nested.logCompletion(habit.id); models.detail.logCompletion()
            }
        }
        assertEquals(before, db.syncOutboxDao().getAll())
        clock = 300; gate.release(lease)
        withContext(Dispatchers.Main) { models.dashboard.incrementCount(habit.id); clock = 600 }
        withTimeout(10_000) {
            models.dashboard.habitsWithStats.first { rows -> rows.any { it.habit.id == habit.id && it.actualTodayCount == 7L } }
            gate.busyHabitIds.first { it.isEmpty() }
        }
        withContext(Dispatchers.Main) { models.nested.decrementCount(habit.id); clock = 900 }
        withTimeout(10_000) {
            models.detail.uiState.first { it.countHistory?.todayQuantity == 6L }
            gate.busyHabitIds.first { it.isEmpty() }
        }
        withContext(Dispatchers.Main) { models.detail.logCompletion(2); clock = 1200 }
        withTimeout(10_000) {
            models.detail.uiState.first { it.countHistory?.todayQuantity == 8L && it.lastCompletionId != null }
            gate.busyHabitIds.first { it.isEmpty() }
        }
        withContext(Dispatchers.Main) { models.detail.undoCompletion(); clock = 1500 }
        withTimeout(10_000) { models.detail.uiState.first { it.countHistory?.todayQuantity == 6L && it.lastCompletionId == null } }
        assertEquals(6, db.completionDao().getByHabitOnce(habit.id).sumOf { it.value })
        assertEquals(before.size + 4, db.syncOutboxDao().getAll().size)
        assertNull(models.detail.uiState.value.errorMessage)
    }
}
