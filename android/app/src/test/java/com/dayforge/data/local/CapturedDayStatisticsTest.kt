package com.dayforge.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.SyncV2Mapper
import com.dayforge.data.repository.SyncV2Merger
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.FailureCheckerUtils
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.domain.service.StreakCalculator
import com.dayforge.domain.service.TargetMetChecker
import com.dayforge.ui.screens.nested.NestedHabitTreeBuilder
import com.dayforge.widget.WidgetFailureChecker
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class CapturedDayStatisticsTest {
    private lateinit var db: HabitDatabase
    private val originalZone = TimeZone.getDefault()
    private val zones = listOf("UTC", "America/Los_Angeles", "Asia/Shanghai", "Pacific/Kiritimati")

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, HabitDatabase::class.java)
            .addCallback(SyncSchemaCallback).allowMainThreadQueries().build()
    }

    @After fun cleanup() {
        db.close()
        TimeZone.setDefault(originalZone)
    }

    private suspend fun habit(type: HabitType = HabitType.COUNTING): HabitEntity {
        val draft = HabitEntity(name = "Captured day $type", habitType = type, iconResId = 1,
            colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 5)
        return draft.copy(id = db.habitDao().insert(draft))
    }

    private fun fact(habit: HabitEntity, date: LocalDate, zone: String, value: Int = 1): CompletionEntity {
        val captureZone = ZoneId.of(zone)
        val occurredAt = date.atTime(0, 5).atZone(captureZone).toInstant().toEpochMilli()
        return CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
            date = date.toDisplayMillis(captureZone), actualCompletedAt = occurredAt,
            recordedTimezone = zone, value = value)
    }

    @Test fun queriesGroupMixedZonesByCivilDateAndUndoOnlyTheRequestedDay() = runBlocking {
        val habit = habit()
        val day = LocalDate.of(2026, 3, 8) // Spring DST transition.
        val a = fact(habit, day, "Asia/Shanghai", 2)
        val b = fact(habit, day, "America/Los_Angeles", 3)
        assertNotEquals(a.date, b.date)
        val dao = db.completionDao()
        dao.insert(a)
        val latest = dao.insert(b)
        dao.insert(fact(habit, day.minusDays(1), "Pacific/Kiritimati", 3))
        dao.insert(fact(habit, day.plusDays(1), "America/Los_Angeles", 7))
        for (zone in zones) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val pendingBeforeRead = db.syncOutboxDao().getAll()
            val found = dao.getCompletionsInRange(habit.id, day, day.plusDays(1))
            assertEquals(setOf(a.uuid, b.uuid), found.map { it.uuid }.toSet())
            assertEquals(5, found.sumOf { it.value })
            assertEquals(2, dao.getCompletionsInRangeSync(habit.id, day, day.plusDays(1)).size)
            assertEquals(latest, dao.getTodayCompletionId(habit.id, day, day.plusDays(1)))
            assertTrue(dao.hasCompletionOnDate(habit.id, day))
            assertFalse(dao.hasCompletionOnDate(habit.id, day.plusDays(2)))
            assertEquals(3, dao.getDistinctDayCount(habit.id))
            assertEquals(2, dao.getTargetMetDayCount(habit.id, 5))
            assertEquals(day.minusDays(1), dao.getFirstCompletionDate(habit.id))
            assertEquals(a, dao.getCompletionByUuid(a.uuid)!!.copy(id = 0))
            assertEquals(pendingBeforeRead, db.syncOutboxDao().getAll())
        }
        dao.delete(dao.getCompletionById(latest)!!)
        assertEquals(2, dao.getCompletionsInRange(habit.id, day, day.plusDays(1)).sumOf { it.value })
        assertEquals(1, dao.getTargetMetDayCount(habit.id, 5))
        dao.deleteByHabitId(habit.id)
        assertNull(dao.getFirstCompletionDate(habit.id))
        assertNull(dao.getTodayCompletionId(habit.id, day, day.plusDays(1)))
        assertEquals(0, dao.getDistinctDayCount(habit.id))
    }

    @Test fun sharedMidnightEpochDoesNotCollapseDifferentCapturedDays() = runBlocking {
        val habit = habit()
        val day = LocalDate.of(2026, 9, 12)
        val east = fact(habit, day, "Pacific/Kiritimati", 2)
        val west = fact(habit, day.minusDays(1), "Pacific/Honolulu", 3)
        assertEquals(east.date, west.date) // UTC+14 vs UTC-10, different civil dates.
        db.completionDao().insert(east)
        db.completionDao().insert(west)
        assertEquals(2, db.completionDao().getDistinctDayCount(habit.id))
        assertEquals(0, db.completionDao().getTargetMetDayCount(habit.id, 5))
        assertEquals(0, TargetMetChecker.countTargetMetDays(listOf(east, west), 5))
    }

    @Test fun streaksAndTargetsSurviveBothDstTransitionsAndZoneChanges() = runBlocking {
        val habit = habit()
        for (today in listOf(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 11, 2))) {
            val facts = (0L..2L).flatMap { offset -> listOf(
                fact(habit, today.minusDays(offset), "America/New_York", 2),
                fact(habit, today.minusDays(offset), "Asia/Shanghai", 3)) }
            val partial = fact(habit, today.minusDays(3), "America/New_York", 4)
            for (zone in zones) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(4, StreakCalculator.calculateCurrentStreak(facts + partial, today))
                assertEquals(4, StreakCalculator.calculateBestStreak(facts + partial))
                assertEquals(3, StreakCalculator.calculateCurrentStreakWithTarget(facts + partial, 5, today))
                assertEquals(3, StreakCalculator.calculateBestStreakWithTarget(facts + partial, 5))
                assertEquals(3, TargetMetChecker.countTargetMetDays(facts + partial, 5))
                assertTrue(TargetMetChecker.isTargetMetOnDate(facts, today, 5))
                assertFalse(TargetMetChecker.isTargetMetOnDate(listOf(partial), today.minusDays(3), 5))
                assertEquals((0L..2L).map { today.minusDays(it) }.toSet(), facts.map { it.businessDate }.toSet())
            }
        }
    }

    @Test fun westwardDateLineTravelDoesNotHideCurrentStreakOrRewriteFutureHistory() = runBlocking {
        val habit = habit()
        val today = LocalDate.of(2026, 9, 12)
        val records = (-1L..2L).map { fact(habit, today.minusDays(it), "Pacific/Kiritimati", 5) }
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
        assertEquals(3, StreakCalculator.calculateCurrentStreak(records, today))
        assertEquals(3, StreakCalculator.calculateCurrentStreakWithTarget(records, 5, today))
        assertEquals(4, StreakCalculator.calculateBestStreak(records))
        assertEquals(today.plusDays(1), records.first().businessDate)
        assertEquals(0, StreakCalculator.calculateCurrentStreak(listOf(records.first()), today))
        assertEquals(2, StreakCalculator.calculateCurrentStreak(records.drop(2), today))
    }

    @Test fun activityRateUsesCapturedDatesWithoutChangingScheduleOrDeductions() = runBlocking {
        val habit = habit()
        val today = LocalDate.of(2026, 11, 2)
        val records = (0L..6L).map { fact(habit, today.minusDays(it), "Asia/Shanghai") }
        for (zone in zones) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val currentZone = ZoneId.systemDefault()
            val now = today.atTime(12, 0).atZone(currentZone).toInstant().toEpochMilli()
            val createdAt = today.minusDays(7).toDisplayMillis(currentZone)
            assertEquals(100, ActivityRateCalculator.calculate(HabitSchedule.Daily, createdAt,
                records.map { it.businessDate }, now))
            assertEquals(85, ActivityRateCalculator.calculate(HabitSchedule.Daily, createdAt,
                records.drop(1).map { it.businessDate }, now))
        }
    }

    @Test fun repositoryDashboardNestedAndWidgetFailureAgreeAfterTravel() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val today = LocalDate.now()
        val dao = db.completionDao()
        val timeDao = db.timeLogDao()
        val parent = habit(HabitType.GOAL)
        val failure = FailureChecker(dao, timeDao)
        val status = HabitStatusCalculator(failure, dao, timeDao)
        val repository = HabitRepository(db.habitDao(), dao, timeDao, db)
        val children = listOf(HabitType.CHECK_IN, HabitType.COUNTING).map { type ->
            val child = habit(type).copy(parentHabitId = parent.uuid, targetCycles = 3,
                createdAt = today.minusDays(3).toDisplayMillis())
            db.habitDao().update(child)
            for (day in listOf(today.minusDays(1), today)) {
                dao.insert(fact(child, day, "Asia/Shanghai", 2))
                dao.insert(fact(child, day, "America/Los_Angeles", 3))
            }
            child
        }
        for (child in children) {
            val stats = status.calculate(child)
            assertTrue(stats.completedToday)
            assertEquals(5, stats.todayCount)
            assertEquals(2, stats.currentStreak)
            assertEquals(2, stats.targetProgress)
            assertFalse(stats.hasFailed)
            assertFalse(WidgetFailureChecker.checkFailure(child, db))
            assertTrue(FailureCheckerUtils.hasTargetMetOnDate(child, dao, timeDao, today))
            assertEquals(5, repository.getTodayCompletionCount(child.id))
            assertEquals(dao.getTodayCompletionId(child.id, today, today.plusDays(1)), repository.getTodayCompletionId(child.id))
            assertEquals(today.toDisplayMillis(), repository.getStreakStats(child.id).first().lastCompletionDate)
        }
        val tree = NestedHabitTreeBuilder(db.habitDao(), dao, timeDao, failure)
            .build(listOf(parent), dao.getAllCompletionsOnce()).single()
        assertEquals(2, tree.completedChildren)
        assertTrue(tree.children.all { it.completedToday && it.todayCount == 5 && !it.hasFailed })
    }

    @Test fun remoteRoundTripAndColdRebuildPreserveDayQueriesAndUtcOccurrence() = runBlocking {
        val habit = habit()
        val day = LocalDate.of(2026, 9, 12)
        val original = fact(habit, day, "Asia/Shanghai", 5)
        val dao = db.completionDao()
        dao.insert(original)
        val payload = SyncV2Mapper.completion(original, habit)
        val merger = SyncV2Merger(db, db.habitDao(), dao, db.timeLogDao(), db.metricDao(),
            db.metricLogDao(), db.habitMetricLinkDao(), db.syncOutboxDao(), db.syncConflictDao())
        for (zone in zones) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            // Simulate acknowledged local writes before pull; retain real outbox triggers.
            db.syncOutboxDao().getAll().forEach { db.syncOutboxDao().deleteById(it.id) }
            merger.applyAuthoritativeEntity("activity_event", original.uuid, 1, payload)
            val pulled = dao.getCompletionByUuid(original.uuid)!!
            assertEquals(payload, SyncV2Mapper.completion(pulled, habit))
            assertEquals(original.actualCompletedAt, pulled.actualCompletedAt)
            assertEquals(day, pulled.businessDate)
            assertEquals(5, dao.getCompletionsInRange(habit.id, day, day.plusDays(1)).sumOf { it.value })
            assertEquals(1, dao.getDistinctDayCount(habit.id))
            assertEquals(0, db.syncOutboxDao().count())
            dao.delete(pulled) // Next iteration rebuilds from a server-only copy.
        }
    }
}
