package com.dayforge.data.local

import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimeLogDayAllocationEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.SyncV2Merger
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class SyncV2OutboxTest {
    private lateinit var database: HabitDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        check(context.packageName == "com.dayforge.testbed")
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase("habit_database")
        database = HabitDatabaseProvider.getInstance(context)
    }

    @After
    fun teardown() {
        database.close()
        HabitDatabaseProvider.clearInstanceForTesting()
    }

    @Test
    fun fresh_database_captures_inserts_and_meaningful_updates() = runBlocking {
        val habitId = database.habitDao().insert(testHabit())
        assertEquals(1, database.syncOutboxDao().count())

        // Local-only computed activity rate must not create a config revision.
        database.habitDao().updateActivityRate(habitId, 80)
        assertEquals(1, database.syncOutboxDao().count())

        database.habitDao().updateIsActive(habitId, false)
        assertEquals(2, database.syncOutboxDao().count())
    }

    @Test
    fun failed_outbox_insert_rolls_back_the_business_insert() = runBlocking {
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("CREATE TRIGGER reject_outbox BEFORE INSERT ON sync_outbox BEGIN SELECT RAISE(ABORT, 'test outbox failure'); END")
        val failure = runCatching { database.habitDao().insert(testHabit()) }.exceptionOrNull()
        assertTrue(failure is android.database.sqlite.SQLiteException)
        reopenDatabase()
        assertNull(database.habitDao().getHabitByName("Water"))
        assertEquals(0, database.syncOutboxDao().count())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_outbox")
        database.habitDao().insert(testHabit())
        assertNotNull(database.habitDao().getHabitByName("Water"))
        assertEquals(1, database.syncOutboxDao().count())
    }

    @Test
    fun failed_outbox_update_keeps_the_prior_business_row_and_queue() = runBlocking {
        val id = database.habitDao().insert(testHabit())
        val before = database.habitDao().getHabitById(id)!!
        val queue = database.syncOutboxDao().getAll()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_outbox BEFORE INSERT ON sync_outbox BEGIN SELECT RAISE(ABORT, 'test outbox failure'); END")
        val failure = runCatching { database.habitDao().updateIsActive(id, false) }.exceptionOrNull()
        assertTrue(failure is android.database.sqlite.SQLiteException)
        reopenDatabase()
        assertEquals(before, database.habitDao().getHabitById(id))
        assertEquals(queue, database.syncOutboxDao().getAll())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_outbox")
        database.habitDao().updateIsActive(id, false)
        assertEquals(false, database.habitDao().getHabitById(id)!!.isActive)
        assertEquals(2, database.syncOutboxDao().count())
    }

    private fun reopenDatabase() {
        database.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        database = HabitDatabaseProvider.getInstance(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun completion_outbox_retains_its_parent_activity_uuid() = runBlocking {
        val habitId = database.habitDao().insert(testHabit())
        val habit = database.habitDao().getHabitById(habitId)!!
        database.completionDao().insert(
            CompletionEntity(
                habitId = habitId,
                habitUuid = habit.uuid,
                date = System.currentTimeMillis()
            )
        )
        val completionRow = database.syncOutboxDao().getAll()
            .firstOrNull { it.recordType == "completion" }
        assertNotNull(completionRow)
        assertEquals(habit.uuid, completionRow?.referenceUuid)
    }

    @Test
    fun fresh_database_captures_config_and_fact_changes_while_timers_use_command_sync() = runBlocking {
        val activityId = database.habitDao().insert(testHabit())
        val activity = database.habitDao().getHabitById(activityId)!!
        val timerId = database.habitDao().insert(
            testHabit().copy(name = "Focus", habitType = HabitType.TIMER)
        )
        val timer = database.habitDao().getHabitById(timerId)!!
        database.completionDao().insert(
            CompletionEntity(
                habitId = activityId,
                habitUuid = activity.uuid,
                date = System.currentTimeMillis()
            )
        )
        val now = System.currentTimeMillis()
        database.timeLogDao().insert(
            TimeLogEntity(
                habitId = timerId,
                startTime = now - 60_000,
                endTime = now,
                durationSeconds = 60,
                date = now
            )
        )
        val metricId = database.metricDao().insert(
            MetricEntity(
                name = "Weight",
                unit = "kg",
                iconResId = 1,
                colorHex = "#2196F3"
            )
        )
        val metric = database.metricDao().getMetricById(metricId)!!
        database.metricLogDao().insert(
            MetricLogEntity(
                metricId = metricId,
                date = now,
                value = 60.5,
                unit = metric.unit
            )
        )
        database.habitMetricLinkDao().insert(
            HabitMetricLinkEntity(
                habitId = activityId,
                habitUuid = activity.uuid,
                metricId = metricId,
                metricUuid = metric.uuid
            )
        )

        val rows = database.syncOutboxDao().getAll()

        assertEquals(
            setOf("habit", "completion", "metric", "metric_log", "link"),
            rows.map { it.recordType }.toSet()
        )
        assertTrue(rows.none { it.recordType == "timelog" })
        assertEquals(activity.uuid, rows.single { it.recordType == "link" }.referenceUuid)
    }

    @Test
    fun clear_all_data_restores_outbox_trigger_control() = runBlocking {
        database.habitDao().insert(testHabit())
        database.clearAllData()
        assertEquals(0, database.syncOutboxDao().count())
        database.habitDao().insert(testHabit().copy(name = "After clear"))
        assertEquals(1, database.syncOutboxDao().count())
    }

    @Test
    fun accepting_server_timer_state_clears_every_command_for_the_session() = runBlocking {
        val dao = database.timeLogDao()
        val sessionUuid = UUID.randomUUID().toString()
        dao.insertTimerCommand(timerCommand(sessionUuid, 1, "start"))
        dao.insertTimerCommand(timerCommand(sessionUuid, 2, "stop").copy(deadLetteredAt = 1L))
        dao.insertTimerCommand(timerCommand(UUID.randomUUID().toString(), 1, "start"))

        dao.resolveRejectedTimerCommand(sessionUuid, removeLocalSession = false)

        assertTrue(dao.getPendingTimerCommands(10).none { it.sessionUuid == sessionUuid })
        assertTrue(dao.getRejectedTimerCommands().none { it.sessionUuid == sessionUuid })
        assertEquals(1, dao.getPendingTimerCommands(10).size)
    }

    @Test
    fun bootstrap_replacement_refuses_to_overwrite_pending_local_intent() = runBlocking {
        database.habitDao().insert(testHabit())
        val merger = SyncV2Merger(
            database,
            database.habitDao(),
            database.completionDao(),
            database.timeLogDao(),
            database.metricDao(),
            database.metricLogDao(),
            database.habitMetricLinkDao(),
            database.syncOutboxDao(),
            database.syncConflictDao()
        )

        val failure = runCatching { merger.replaceWithBootstrap(emptyList()) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, database.syncOutboxDao().count())
        assertNotNull(database.habitDao().getHabitByName("Water"))
    }

    @Test
    fun server_countdown_and_goal_policy_survive_the_Room_merge() = runBlocking {
        val merger = merger()
        val timerUuid = UUID.randomUUID().toString()
        merger.applyAuthoritativeEntity(
            "plan_node",
            timerUuid,
            1,
            buildJsonObject {
                put("node_kind", "activity")
                put("title", "Countdown timer")
                put("activity", buildJsonObject {
                    put("tracking_mode", "duration")
                    put("is_countdown", true)
                    put("target_value", 60)
                    put("target_unit", "second")
                    put("recurrence_rule", buildJsonObject {
                        put("schema_version", 1)
                        put("type", "daily")
                        put("interval", 1)
                    })
                })
            }
        )
        val goalUuid = UUID.randomUUID().toString()
        merger.applyAuthoritativeEntity(
            "plan_node",
            goalUuid,
            1,
            buildJsonObject {
                put("node_kind", "goal")
                put("title", "Thirty day goal")
                put("goal", buildJsonObject {
                    put("target_cycles", 30)
                    put("failure_policy", buildJsonObject {
                        put("schema_version", 1)
                        put("type", "loose")
                    })
                    put("evaluation_policy", buildJsonObject {
                        put("schema_version", 1)
                        put("type", "manual")
                    })
                })
            }
        )

        val timer = database.habitDao().getHabitByUuid(timerUuid)!!
        assertEquals(HabitType.TIMER, timer.habitType)
        assertEquals(true, timer.isCountdown)
        assertEquals(1, timer.targetValue)
        val goal = database.habitDao().getHabitByUuid(goalUuid)!!
        assertEquals(30, goal.targetCycles)
        assertEquals(com.dayforge.data.model.FailMode.LOOSE, goal.failMode)
    }

    @Test
    fun server_metric_observation_preserves_its_exact_occurrence_time() = runBlocking {
        val merger = merger()
        val metricUuid = UUID.randomUUID().toString()
        merger.applyAuthoritativeEntity(
            "metric",
            metricUuid,
            1,
            buildJsonObject {
                put("name", "Weight")
                put("unit", "kg")
                put("decimal_places", 1)
                put("aggregation_type", "average")
                put("icon", "health")
                put("color_hex", "#2196F3")
                put("status", "active")
            }
        )
        val observationUuid = UUID.randomUUID().toString()
        val occurredAt = "2026-08-13T13:05:27.940Z"

        merger.applyAuthoritativeEntity(
            "metric_observation",
            observationUuid,
            1,
            buildJsonObject {
                put("metric_uuid", metricUuid)
                put("value", 67.7)
                put("unit", "kg")
                put("occurred_at", occurredAt)
                put("local_date", "2026-08-13")
                put("timezone", "UTC")
            }
        )

        val observation = database.metricLogDao().getLogByUuid(observationUuid)!!
        assertEquals(Instant.parse(occurredAt).toEpochMilli(), observation.date)
    }

    @Test
    fun server_timer_merge_preserves_exact_milliseconds_and_cross_day_allocations() = runBlocking {
        val merger = merger()
        val activityUuid = UUID.randomUUID().toString()
        merger.applyAuthoritativeEntity(
            "plan_node",
            activityUuid,
            1,
            buildJsonObject {
                put("node_kind", "activity")
                put("title", "Cross-day timer")
                put("activity", buildJsonObject {
                    put("tracking_mode", "duration")
                    put("target_value", 60)
                    put("target_unit", "second")
                    put("recurrence_rule", buildJsonObject {
                        put("schema_version", 1)
                        put("type", "daily")
                        put("interval", 1)
                    })
                })
            }
        )
        val sessionUuid = UUID.randomUUID().toString()
        merger.applyAuthoritativeEntity(
            "activity_event",
            sessionUuid,
            1,
            buildJsonObject {
                put("activity_uuid", activityUuid)
                put("event_type", "duration_session")
                put("started_at", "2026-08-03T15:59:30Z")
                put("ended_at", "2026-08-03T16:00:30.750Z")
                put("occurred_at", "2026-08-03T16:00:30.750Z")
                put("local_date", "2026-08-03")
                put("timezone", "Asia/Shanghai")
                put("duration_seconds", 60)
                put("duration_milliseconds", 60_750)
                put("day_allocations", buildJsonArray {
                    add(buildJsonObject {
                        put("local_date", "2026-08-03")
                        put("timezone", "Asia/Shanghai")
                        put("duration_milliseconds", 30_000)
                    })
                    add(buildJsonObject {
                        put("local_date", "2026-08-04")
                        put("timezone", "Asia/Shanghai")
                        put("duration_milliseconds", 30_750)
                    })
                })
            }
        )

        val log = database.timeLogDao().getTimeLogByUuid(sessionUuid)!!
        assertEquals(60_750L, log.timerActiveElapsedMillis)
        assertEquals(
            listOf("2026-08-03" to 30_000L, "2026-08-04" to 30_750L),
            database.timeLogDao().getDayAllocations(sessionUuid)
                .map { it.localDate to it.durationMillis }
        )
    }

    @Test
    fun unallocated_and_allocated_timer_sessions_on_the_same_day_share_one_daily_bucket() = runBlocking {
        val habitId = database.habitDao().insert(
            testHabit().copy(habitType = HabitType.TIMER, targetValue = 1)
        )
        val zone = ZoneId.systemDefault()
        val date = java.time.LocalDate.of(2026, 8, 14)
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        database.timeLogDao().insert(
            TimeLogEntity(
                habitId = habitId,
                startTime = midnight,
                endTime = midnight + 30_000,
                durationSeconds = 30,
                date = midnight
            )
        )
        val allocated = TimeLogEntity(
            habitId = habitId,
            startTime = midnight + 30_000,
            endTime = midnight + 60_000,
            durationSeconds = 30,
            timerTimezone = zone.id,
            timerActiveElapsedMillis = 30_000,
            date = midnight
        )
        database.timeLogDao().insert(allocated)
        database.timeLogDao().upsertDayAllocations(
            listOf(
                TimeLogDayAllocationEntity(
                    sessionUuid = allocated.uuid,
                    habitId = habitId,
                    localDate = date.toString(),
                    localDateEpoch = midnight,
                    timezone = zone.id,
                    durationMillis = 30_000
                )
            )
        )

        assertEquals(1, database.timeLogDao().getDistinctDayCount(habitId))
        assertEquals(1, database.timeLogDao().getTargetMetDayCount(habitId, 60))
    }

    @Test
    fun server_timer_recovery_atomically_clears_rejected_command_and_local_session() = runBlocking {
        val habitId = database.habitDao().insert(
            testHabit().copy(habitType = HabitType.TIMER, targetValue = 1)
        )
        val sessionUuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        database.timeLogDao().insert(
            TimeLogEntity(
                habitId = habitId,
                startTime = now - 60_000,
                endTime = now,
                durationSeconds = 60,
                date = now,
                uuid = sessionUuid
            )
        )
        database.timeLogDao().insertTimerSegment(
            TimerSegmentEntity(
                sessionUuid = sessionUuid,
                sequence = 1,
                startedAt = now - 60_000,
                endedAt = now
            )
        )
        val commandId = database.timeLogDao().insertTimerCommand(
            TimerCommandEntity(
                sessionUuid = sessionUuid,
                sequence = 2,
                commandType = "stop",
                occurredAt = now,
                expectedControlGeneration = 1,
                deadLetteredAt = now
            )
        )

        database.timeLogDao().resolveRejectedTimerCommand(
            sessionUuid,
            removeLocalSession = true
        )

        assertEquals(null, database.timeLogDao().getRejectedTimerCommand(commandId))
        assertEquals(null, database.timeLogDao().getTimeLogByUuid(sessionUuid))
        assertTrue(database.timeLogDao().getTimerSegments(sessionUuid).isEmpty())
    }

    @Test
    fun dead_letter_no_longer_blocks_pending_queue_and_can_be_retried() = runBlocking {
        database.habitDao().insert(testHabit())
        val row = database.syncOutboxDao().getAll().single()

        database.syncOutboxDao().markDeadLetter(
            row.id,
            "INVALID_PAYLOAD",
            "invalid payload",
            System.currentTimeMillis()
        )

        assertEquals(0, database.syncOutboxDao().count())
        assertEquals(1, database.syncOutboxDao().countDeadLetters())
        assertEquals("INVALID_PAYLOAD", database.syncOutboxDao().getDeadLetters().single().errorCode)

        val newOperationId = UUID.randomUUID().toString()
        database.syncOutboxDao().retryDeadLetter(row.id, newOperationId)
        assertEquals(1, database.syncOutboxDao().count())
        assertEquals(0, database.syncOutboxDao().countDeadLetters())
        val retried = database.syncOutboxDao().getAll().single()
        assertEquals(newOperationId, retried.operationId)
        assertEquals(null, retried.payloadJson)
        assertEquals(null, retried.baseRevision)
        assertEquals(null, retried.attemptedAt)
    }

    @Test
    fun newer_entity_edit_is_detected_without_deleting_the_request_snapshot() = runBlocking {
        val habitId = database.habitDao().insert(testHabit())
        val snapshot = database.syncOutboxDao().getAll().single()
        database.habitDao().updateIsActive(habitId, false)
        val newer = database.syncOutboxDao().getAll().last()
        database.syncOutboxDao().markDeadLetter(
            newer.id,
            "INVALID_PAYLOAD",
            "invalid payload",
            System.currentTimeMillis()
        )

        assertEquals(
            true,
            database.syncOutboxDao().hasNewerPending(
                snapshot.recordType,
                snapshot.entityUuid,
                snapshot.id
            )
        )
        database.syncOutboxDao().deleteById(snapshot.id)
        assertEquals(0, database.syncOutboxDao().count())
        assertEquals(1, database.syncOutboxDao().countDeadLetters())
    }

    @Test
    fun retry_restores_rejected_revert_to_a_local_delete_intent() = runBlocking {
        val originalEventUuid = UUID.randomUUID().toString()
        val rowId = database.syncOutboxDao().insert(
            SyncOutboxEntity(
                operationId = UUID.randomUUID().toString(),
                recordType = "completion",
                entityUuid = originalEventUuid,
                wireEntityUuid = UUID.randomUUID().toString(),
                action = "upsert",
                referenceUuid = UUID.randomUUID().toString(),
                payloadJson = "{}",
                attemptedAt = 1
            )
        )
        database.syncOutboxDao().markDeadLetter(
            rowId,
            "INVALID_PAYLOAD",
            "invalid revert",
            System.currentTimeMillis()
        )

        database.syncOutboxDao().retryDeadLetter(rowId, UUID.randomUUID().toString())

        val retried = database.syncOutboxDao().getAll().single { it.id == rowId }
        assertEquals("delete", retried.action)
        assertEquals(originalEventUuid, retried.wireEntityUuid)
        assertEquals(null, retried.payloadJson)
    }

    private fun testHabit() = HabitEntity(
        name = "Water",
        habitType = HabitType.CHECK_IN,
        iconResId = 1,
        colorHex = "#2196F3",
        schedule = HabitSchedule.Daily
    )

    private fun merger() = SyncV2Merger(
        database,
        database.habitDao(),
        database.completionDao(),
        database.timeLogDao(),
        database.metricDao(),
        database.metricLogDao(),
        database.habitMetricLinkDao(),
        database.syncOutboxDao(),
        database.syncConflictDao()
    )

    private fun timerCommand(sessionUuid: String, sequence: Int, type: String) =
        TimerCommandEntity(
            sessionUuid = sessionUuid,
            sequence = sequence,
            commandType = type,
            occurredAt = System.currentTimeMillis(),
            expectedControlGeneration = if (type == "start") 0 else 1,
            activityUuid = if (type == "start") UUID.randomUUID().toString() else null,
            timezone = if (type == "start") "Asia/Shanghai" else null
        )
}
