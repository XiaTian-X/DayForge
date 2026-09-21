package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A server parent update must never act as a local cascading delete. */
@RunWith(AndroidJUnit4::class)
class SyncMergeRecordPreservationTest {
    @get:Rule val storage = PhysicalDatabaseRule()
    private val db get() = storage.database
    private val merger get() = SyncV2Merger(db, db.habitDao(), db.completionDao(),
        db.timeLogDao(), db.metricDao(), db.metricLogDao(), db.habitMetricLinkDao(),
        db.syncOutboxDao(), db.syncConflictDao())

    private suspend fun habit(name: String = "Timer", uuid: String = "habit-a"): HabitEntity {
        val row = HabitEntity(name = name, uuid = uuid, habitType = HabitType.TIMER,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily,
            targetValue = 1, isCountdown = true)
        return row.copy(id = db.habitDao().insert(row))
    }

    private suspend fun start(habit: HabitEntity): TimeLogEntity {
        val row = TimeLogEntity(habitId = habit.id, uuid = "session-a", startTime = 10_000,
            endTime = null, durationSeconds = 0, date = 0, timerNextCommandSequence = 2,
            timerControlGeneration = 1, timerTimezone = "UTC", timerLastCommandAt = 10_000)
        val id = db.timeLogDao().insertSyncedTimer(row,
            TimerCommandEntity(sessionUuid = row.uuid, sequence = 1, commandType = "start",
                occurredAt = 10_000, expectedControlGeneration = 0,
                activityUuid = habit.uuid, timezone = "UTC"),
            TimerSegmentEntity(sessionUuid = row.uuid, sequence = 1, startedAt = 10_000))
        return row.copy(id = id)
    }

    private fun change(type: String, uuid: String, payload: String, operation: String = "upsert") =
        Json.decodeFromString<SyncV2Change>("""{"sequence":1,"entity_type":"$type","entity_uuid":"$uuid","operation":"$operation","revision":2,"payload":$payload,"changed_at":"2026-09-21T00:00:00Z"}""")

    private fun habitChange(uuid: String = "habit-a", name: String = "Updated timer") = change(
        "plan_node", uuid,
        """{"node_kind":"activity","title":"$name","status":"active","activity":{"tracking_mode":"duration","target_value":60,"is_countdown":true}}""")

    @Test fun repeatedParentMergePreservesRunningTimerSegmentsAndUnacknowledgedCommands() = runBlocking {
        val habit = habit()
        val timer = start(habit)
        val segments = db.timeLogDao().getTimerSegments(timer.uuid)
        val commands = db.timeLogDao().getPendingTimerCommands()
        val pending = db.syncOutboxDao().count()
        repeat(2) { merger.apply(listOf(habitChange())) }
        storage.reopen()
        assertEquals("Updated timer", db.habitDao().getHabitByUuid(habit.uuid)?.name)
        assertEquals(habit.id, db.habitDao().getHabitByUuid(habit.uuid)?.id)
        assertEquals(timer, db.timeLogDao().getActiveTimeLog())
        assertEquals(segments, db.timeLogDao().getTimerSegments(timer.uuid))
        assertEquals(commands, db.timeLogDao().getPendingTimerCommands())
        assertEquals(pending, db.syncOutboxDao().count())
    }

    @Test fun parentMergePreservesCompletedTimerCheckInsAndMetricLinks() = runBlocking {
        val habit = habit()
        val timer = start(habit)
        db.timeLogDao().finishTimerAndQueue(timer.id, 70_000, 60, 0, 3, 60_000,
            TimerCommandEntity(sessionUuid = timer.uuid, sequence = 2, commandType = "stop",
                occurredAt = 70_000, expectedControlGeneration = 1, activeElapsedMillis = 60_000), false)
        db.timeLogDao().replaceDayAllocations(timer.uuid, listOf(TimeLogDayAllocationEntity(
            timer.uuid, habit.id, "1970-01-01", 0, "UTC", 60_000)))
        // Fact fixtures are storage oracles, not evidence that a timer service completed.
        val completionId = db.completionDao().insert(CompletionEntity(habitId = habit.id,
            date = 0, uuid = "check-a", value = 3))
        val metricId = db.metricDao().insert(MetricEntity(name = "Metric", unit = "kg",
            iconResId = 1, colorHex = "#123456", uuid = "metric-a"))
        db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = habit.id,
            habitUuid = habit.uuid, metricId = metricId, metricUuid = "metric-a", uuid = "link-a"))
        val completed = db.timeLogDao().getById(timer.id)
        val segments = db.timeLogDao().getTimerSegments(timer.uuid)
        val allocations = db.timeLogDao().getDayAllocations(timer.uuid)
        val commands = db.timeLogDao().getPendingTimerCommands()
        val link = db.habitMetricLinkDao().getLinkByUuid("link-a")
        merger.apply(listOf(habitChange()))
        storage.reopen()
        assertEquals(completed, db.timeLogDao().getById(timer.id))
        assertEquals(segments, db.timeLogDao().getTimerSegments(timer.uuid))
        assertEquals(allocations, db.timeLogDao().getDayAllocations(timer.uuid))
        assertEquals(commands, db.timeLogDao().getPendingTimerCommands())
        assertEquals(completionId, db.completionDao().getCompletionByUuid("check-a")?.id)
        assertEquals(link, db.habitMetricLinkDao().getLinkByUuid("link-a"))
    }

    @Test fun metricMergePreservesObservationsAndLinksWithoutEchoOutbox() = runBlocking {
        val habit = habit()
        val metric = MetricEntity(name = "Metric", unit = "kg", iconResId = 1,
            colorHex = "#123456", uuid = "metric-a")
        val metricId = db.metricDao().insert(metric)
        db.metricLogDao().insert(MetricLogEntity(metricId = metricId, date = 10_000,
            value = 66.6, unit = "kg", uuid = "observation-a", recordedTimezone = "UTC"))
        db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = habit.id,
            habitUuid = habit.uuid, metricId = metricId, metricUuid = metric.uuid, uuid = "link-a"))
        val log = db.metricLogDao().getLogByUuid("observation-a")
        val link = db.habitMetricLinkDao().getLinkByUuid("link-a")
        val pending = db.syncOutboxDao().count()
        repeat(2) { merger.apply(listOf(change("metric", metric.uuid,
            """{"name":"Renamed metric","unit":"kg","status":"active"}"""))) }
        storage.reopen()
        assertEquals("Renamed metric", db.metricDao().getMetricByUuid(metric.uuid)?.name)
        assertEquals(metricId, db.metricDao().getMetricByUuid(metric.uuid)?.id)
        assertEquals(log, db.metricLogDao().getLogByUuid("observation-a"))
        assertEquals(link, db.habitMetricLinkDao().getLinkByUuid("link-a"))
        assertEquals(pending, db.syncOutboxDao().count())
    }

    @Test fun repeatedCompletedEventMergePreservesTimerSegmentsAndReplacesAllocations() = runBlocking {
        val habit = habit()
        val timer = start(habit)
        db.timeLogDao().finishTimerAndQueue(timer.id, 70_000, 60, 0, 3, 60_000,
            TimerCommandEntity(sessionUuid = timer.uuid, sequence = 2, commandType = "stop",
                occurredAt = 70_000, expectedControlGeneration = 1, activeElapsedMillis = 60_000), false)
        val segments = db.timeLogDao().getTimerSegments(timer.uuid)
        val commands = db.timeLogDao().getPendingTimerCommands()
        val event = change("activity_event", timer.uuid,
            """{"event_type":"duration_session","activity_uuid":"habit-a","local_date":"1970-01-01","timezone":"UTC","started_at":"1970-01-01T00:00:10Z","ended_at":"1970-01-01T00:01:10Z","duration_seconds":60,"duration_milliseconds":60000,"day_allocations":[{"local_date":"1970-01-01","timezone":"UTC","duration_milliseconds":60000}]}""")
        repeat(2) { merger.apply(listOf(event)) }
        storage.reopen()
        assertEquals(segments, db.timeLogDao().getTimerSegments(timer.uuid))
        assertEquals(commands, db.timeLogDao().getPendingTimerCommands())
        assertEquals(60, db.timeLogDao().getById(timer.id)?.durationSeconds)
        assertEquals(listOf(60_000L), db.timeLogDao().getDayAllocations(timer.uuid).map { it.durationMillis })
        assertEquals(1, db.timeLogDao().getAllTimeLogsForHabit(habit.id).size)
    }

    @Test fun sameTitleDifferentIdentityRollsBackWholePageInsteadOfDeletingLocalRecord() = runBlocking {
        val habit = habit()
        val timer = start(habit)
        val pending = db.syncOutboxDao().count()
        val failure = runCatching {
            merger.apply(listOf(habitChange("new-a", "First row"), habitChange("new-b", habit.name)))
        }.exceptionOrNull()
        assertTrue(failure is SyncMergeException)
        storage.reopen()
        assertNull(db.habitDao().getHabitByUuid("new-a"))
        assertNull(db.habitDao().getHabitByUuid("new-b"))
        assertEquals(habit, db.habitDao().getHabitByUuid(habit.uuid))
        assertEquals(timer, db.timeLogDao().getById(timer.id))
        assertEquals(pending, db.syncOutboxDao().count())
    }

    @Test fun explicitDeleteThenSameTitleNewIdentityStillWorks() = runBlocking {
        val old = habit()
        start(old)
        merger.apply(listOf(change("plan_node", old.uuid, "{}", "delete"), habitChange("new-a", old.name)))
        storage.reopen()
        assertNull(db.habitDao().getHabitByUuid(old.uuid))
        assertEquals("new-a", db.habitDao().getHabitByName(old.name)?.uuid)
        assertTrue(db.timeLogDao().getAllTimeLogsForHabit(old.id).isEmpty())
    }

    @Test fun failedUpdateRollsBackEarlierPageChangesAndDoesNotAdvanceShadow() = runBlocking {
        val habit = habit()
        val timer = start(habit)
        val commands = db.timeLogDao().getPendingTimerCommands()
        val pending = db.syncOutboxDao().count()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_sync_update BEFORE UPDATE ON habits BEGIN SELECT RAISE(ABORT, 'test update failure'); END")
        val changes = listOf(habitChange("new-a", "Earlier page row"), habitChange())
        assertTrue(runCatching { merger.apply(changes) }.exceptionOrNull() is SyncMergeException)
        storage.reopen()
        assertNull(db.habitDao().getHabitByUuid("new-a"))
        assertEquals(habit, db.habitDao().getHabitByUuid(habit.uuid))
        assertEquals(timer, db.timeLogDao().getById(timer.id))
        assertEquals(commands, db.timeLogDao().getPendingTimerCommands())
        assertEquals(pending, db.syncOutboxDao().count())
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM sync_entity_state").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_sync_update")
        merger.apply(changes)
        assertEquals("Updated timer", db.habitDao().getHabitByUuid(habit.uuid)?.name)
        assertEquals(timer, db.timeLogDao().getById(timer.id))
    }

    @Test fun syncUpdateWithMissingLocalIdFailsRatherThanSilentlyInserting() = runBlocking {
        val missing = HabitEntity(id = 99, uuid = "missing", name = "Missing", habitType = HabitType.TIMER,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily)
        assertTrue(runCatching { db.habitDao().upsert(missing) }.exceptionOrNull() is IllegalStateException)
        assertNull(db.habitDao().getHabitByUuid(missing.uuid))
        assertEquals(0, db.syncOutboxDao().count())
    }

    @Test fun repeatedLeafMergesUpdateInPlaceAndDoNotReplaceDifferentLinkIdentity() = runBlocking {
        val habit = habit()
        val metricId = db.metricDao().insert(MetricEntity(name = "Metric", unit = "kg", iconResId = 1,
            colorHex = "#123456", uuid = "metric-a"))
        val facts = listOf(
            change("activity_event", "check-a", """{"event_type":"count_delta","activity_uuid":"habit-a","value":3,"occurred_at":"1970-01-01T00:00:10Z","local_date":"1970-01-01","timezone":"UTC"}"""),
            change("metric_observation", "observation-a", """{"metric_uuid":"metric-a","value":66.6,"unit":"kg","occurred_at":"1970-01-01T00:00:10Z","local_date":"1970-01-01","timezone":"UTC"}"""),
            change("activity_metric_link", "link-a", """{"activity_uuid":"habit-a","metric_uuid":"metric-a","prompt_on_complete":true}"""))
        merger.apply(facts)
        val completionId = db.completionDao().getCompletionByUuid("check-a")!!.id
        val observationId = db.metricLogDao().getLogByUuid("observation-a")!!.id
        val link = db.habitMetricLinkDao().getLinkByUuid("link-a")!!
        val pending = db.syncOutboxDao().count()
        merger.apply(facts)
        storage.reopen()
        assertEquals(completionId, db.completionDao().getCompletionByUuid("check-a")?.id)
        assertEquals(3, db.completionDao().getCompletionByUuid("check-a")?.value)
        assertEquals(observationId, db.metricLogDao().getLogByUuid("observation-a")?.id)
        assertEquals(66.6, db.metricLogDao().getLogByUuid("observation-a")!!.value, 0.0)
        assertEquals(link.id, db.habitMetricLinkDao().getLinkByUuid("link-a")?.id)
        assertEquals(metricId, db.habitMetricLinkDao().getLinkByUuid("link-a")?.metricId)
        assertEquals(pending, db.syncOutboxDao().count())
        val failure = runCatching { merger.apply(listOf(facts.last().copy(entityUuid = "different-link"))) }.exceptionOrNull()
        assertTrue(failure is SyncMergeException)
        assertEquals(link.id, db.habitMetricLinkDao().getLinkByUuid("link-a")?.id)
        assertNull(db.habitMetricLinkDao().getLinkByUuid("different-link"))
        assertEquals(habit.id, db.habitMetricLinkDao().getLinkByUuid("link-a")?.habitId)
    }
}
