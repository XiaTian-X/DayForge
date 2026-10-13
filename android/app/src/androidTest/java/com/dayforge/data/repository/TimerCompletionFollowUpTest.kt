package com.dayforge.data.repository

import android.content.Intent
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.FailMode
import com.dayforge.domain.model.*
import com.dayforge.domain.service.DurationDayAllocator
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real file Room/producer/editor/ACK proof; logical recorded times avoid another stopwatch wait. */
@RunWith(AndroidJUnit4::class)
class TimerCompletionFollowUpTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private fun writer() = NextTimerWriter(db, tokens, sessions)
    private fun goalHabits() = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
        nextObjectEditor = editor, timerWriter = writer())
    private fun promptMetrics(logs: MetricLogDao = db.metricLogDao()) = MetricRepository(db, db.metricDao(), logs,
        db.habitDao(), db.habitMetricLinkDao(), nextObjectEditor = editor, timerWriter = writer())
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun view(source: TimerActionAuthority) = sessions.exclusive {
        db.withTransaction { writer().completionInTransaction(timerHabit.id, source) }
    }
    private suspend fun goal() {
        val edit = goalHabits().getHabitForEditing(timerHabit.id)
        goalHabits().updateHabit(requireNotNull(edit.value).copy(targetCycles = 1, failMode = FailMode.STRICT), editAuthority = edit.authority)
        timerHabit = requireNotNull(db.habitDao().getHabitById(timerHabit.id))
    }
    private suspend fun complete(): TimerActionAuthority {
        writer().write(timerHabit.id, writer().capture(timerHabit.id)) {
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = timerHabit.id, uuid = id(20), startTime = millis,
                endTime = null, durationSeconds = 0, date = millis, timerNextCommandSequence = 2,
                timerControlGeneration = 1, timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai"),
                TimerCommandEntity(commandId = id(21), sessionUuid = id(20), sequence = 1, commandType = "start",
                    occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai"),
                TimerSegmentEntity(sessionUuid = id(20), sequence = 1, startedAt = millis))
        }
        val source = requireNotNull(writer().capture(timerHabit.id))
        val log = requireNotNull(db.timeLogDao().getActiveTimeLog())
        writer().write(timerHabit.id, source) {
            db.timeLogDao().finishTimerAndQueue(log.id, millis + 60_000, 60, 0, 3, 60_000,
                TimerCommandEntity(commandId = id(22), sessionUuid = log.uuid, sequence = 2, commandType = "stop",
                    occurredAt = millis + 60_000, expectedControlGeneration = 1, activeElapsedMillis = 60_000), false)
            db.timeLogDao().replaceDayAllocations(log.uuid,
                DurationDayAllocator.allocate(log.uuid, timerHabit.id, "Asia/Shanghai", db.timeLogDao().getTimerSegments(log.uuid), 60_000))
        }
        return source
    }

    @Test fun originalCompletedDayAndLaterActiveTimerRemainEligibleWithoutRecapturingTheActiveSession() = runBlocking<Unit> {
        goal(); val source = complete()
        val original = view(source)
        assertTrue(original.goalReached); assertEquals(1, original.progress)
        val intent = Intent().also { original.claim.attach(it) }
        assertEquals(original.claim, TimerCompletionFollowUpClaim.read(intent))
        start(session = 30, command = 31)
        val active = requireNotNull(db.timeLogDao().getActiveTimeLog())
        assertNotEquals(source.sessionUuid, active.uuid)
        assertEquals(original, writer().goalDisplay(original.claim))
        val before = proof()
        rejected { writer().goalDisplay(original.claim.copy(source = source.copy(nextSequence = 3))) }
        rejected { writer().goalDisplay(original.claim.copy(source = source.copy(habitUuid = id(91)))) }
        assertEquals(before, proof())
        assertEquals(active, db.timeLogDao().getActiveTimeLog())
    }

    @Test fun goalConfirmationRejectsChangedPlanThenContinuesOrArchivesWithoutErasingHistory() = runBlocking<Unit> {
        goal(); val source = complete(); val displayed = view(source).claim
        val session = requireNotNull(source.sessionUuid)
        val logs = db.timeLogDao().getAllTimeLogsForHabit(timerHabit.id)
        val segments = db.timeLogDao().getTimerSegments(session)
        val allocations = db.timeLogDao().getDayAllocations(session)
        val edited = goalHabits().getHabitForEditing(timerHabit.id)
        goalHabits().updateHabit(requireNotNull(edited.value).copy(name = "Edited after display"), editAuthority = edited.authority)
        val before = proof()
        rejected { goalHabits().applyTimerGoal(app, displayed, true) }
        assertEquals(before, proof())
        goalHabits().applyTimerGoal(app, view(source).claim, false)
        assertEquals(FailMode.LOOSE, db.habitDao().getHabitById(timerHabit.id)!!.failMode)
        assertTrue(db.habitDao().getHabitById(timerHabit.id)!!.isActive)
        goalHabits().applyTimerGoal(app, view(source).claim, true)
        assertFalse(db.habitDao().getHabitById(timerHabit.id)!!.isActive)
        assertTrue(view(source).goalReached) // No isActive/STRICT escape for a late automatic prompt.
        assertEquals(logs, db.timeLogDao().getAllTimeLogsForHabit(timerHabit.id))
        assertEquals(segments, db.timeLogDao().getTimerSegments(session))
        assertEquals(allocations, db.timeLogDao().getDayAllocations(session))
        assertTrue(db.syncOutboxDao().getAll().any { it.entityUuid == timerHabit.uuid })
    }

    @Test fun automaticMetricWaitsForGoalAndExplicitFollowUpRetainsOriginalCompletionThroughActualWrites() = runBlocking<Unit> {
        goal(); val source = complete()
        producer().write(local()) {
            db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = timerHabit.id, habitUuid = timerHabit.uuid,
                metricId = metric.id, metricUuid = metric.uuid, coefficient = 1.0, showInHabitDetail = true, promptOnComplete = true))
        }
        val coordinator = LinkedMetricCoordinator(app, preferences, promptMetrics(), goalHabits(), timerWriter = writer())
        coordinator.showPromptAfterTimerStop(timerHabit.id, source)
        assertNull(coordinator.postCheckInState.value)
        goalHabits().applyTimerGoal(app, view(source).claim, true)
        coordinator.showPromptAfterTimerStop(timerHabit.id, source)
        assertNull(coordinator.postCheckInState.value)
        coordinator.showPromptAfterTimerGoal(timerHabit.id, source)
        val prompt = requireNotNull(coordinator.postCheckInState.value?.timerPrompt)
        val values = listOf(MetricValueInput(metric.id, 4.0))
        withContext(Dispatchers.Main) {
            assertTrue(coordinator.recordMetricValues(timerHabit.id, values, expectedTimerPrompt = prompt))
            assertTrue(coordinator.recordMetricValues(timerHabit.id, values, expectedTimerPrompt = prompt))
        }
        assertEquals(1, db.metricLogDao().countAll())
        coordinator.showPromptAfterTimerGoal(timerHabit.id, source)
        val next = requireNotNull(coordinator.postCheckInState.value?.timerPrompt)
        tokens.saveSyncDeviceId(id(94))
        val before = proof()
        withContext(Dispatchers.Main) {
            assertFalse(coordinator.recordMetricValues(timerHabit.id, values, expectedTimerPrompt = next))
        }
        assertEquals(before, proof())
    }

    @Test fun tokenInvalidationAfterMetricInsertRollsBackRowsAndOutboxUnderOriginalCompletion() = runBlocking<Unit> {
        goal(); val source = complete()
        producer().write(local()) {
            db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = timerHabit.id, habitUuid = timerHabit.uuid,
                metricId = metric.id, metricUuid = metric.uuid, coefficient = 1.0, showInHabitDetail = true, promptOnComplete = true))
        }
        val metricSnapshot = promptMetrics().getMetricForEditing(metric.id)
        val originalAuthentication = requireNotNull(tokens.authenticationSnapshot())
        var inserted = false
        val invalidating = object : MetricLogDao by db.metricLogDao() {
            override suspend fun insertAll(logs: List<MetricLogEntity>): List<Long> {
                val ids = db.metricLogDao().insertAll(logs)
                inserted = db.metricLogDao().countAll() == 1
                tokens.clearRejectedRefresh(originalAuthentication)
                return ids
            }
        }
        val before = proof()
        rejected {
            promptMetrics(invalidating).recordValues(listOf(MetricValueDraft(metric.id, 7.0)), authority = metricSnapshot.authority,
                expectedMetrics = listOf(requireNotNull(metricSnapshot.value)), promptHabit = timerHabit, promptTimer = source)
        }
        assertTrue(inserted); assertEquals(before, proof()); assertEquals(0, db.metricLogDao().countAll())
    }

    @Test fun terminalEndTimeWithoutOriginalStopOrAllocationsCannotAuthorizeGoal() = runBlocking<Unit> {
        goal(); val source = complete(); val claim = view(source).claim
        db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET commandType='pause' WHERE commandId=?", arrayOf(id(22)))
        val before = proof()
        rejected { writer().goalDisplay(claim) }
        assertEquals(before, proof())
        db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET commandType='stop' WHERE commandId=?", arrayOf(id(22)))
        db.timeLogDao().deleteDayAllocations(requireNotNull(source.sessionUuid))
        val noAllocations = proof()
        rejected { writer().goalDisplay(claim) }
        assertEquals(noAllocations, proof())
    }

    @Test fun completedGoalCannotAdoptNewAuthenticationReplicaOrDevice() = runBlocking<Unit> {
        register(); goal(); val source = complete(); val claim = view(source).claim
        val before = proof()
        tokens.saveDeviceRegistration(id(95), caps, true, 2)
        rejected { writer().goalDisplay(claim) }
        tokens.saveDeviceRegistration(id(4), caps, true, 3)
        tokens.saveServerIdentity(id(2), id(96))
        rejected { writer().goalDisplay(claim) }
        tokens.saveServerIdentity(id(2), id(3))
        tokens.saveLoginSession("new-auth", "new-refresh", "member", id(1), false)
        rejected { writer().goalDisplay(claim) }
        assertEquals(before, proof())
    }

    @Test fun laterChallengeHeadCannotReuseAnEarlierRoundCompletionToArchiveTheNewRound() = runBlocking<Unit> {
        register()
        val state = NextSyncStateEntity(id(1), id(2), id(3), id(4), 1, 0, "a".repeat(64), "b".repeat(64), challengeContract = 1)
        val initial = ChallengeRoundRecord(initialChallengeRoundHead(timerHabit.uuid), null, null, null)
        val other = ChallengeRoundRecord(initialChallengeRoundHead(habit.uuid), null, null, null)
        val metadata = ChallengeMetadata(1, listOf(ChallengeCheckpoint(initial.head, listOf(initial)),
            ChallengeCheckpoint(other.head, listOf(other))), emptyList())
        db.withTransaction {
            NextChallengeStore(db).mergeInTransaction(access(), null, state, metadata)
            db.nextSyncStateDao().insert(state)
        }
        goal(); val source = complete(); val claim = view(source).claim
        val next = ChallengeRoundRecord(ChallengeRoundHead(timerHabit.uuid, id(71), 1), id(4), id(72),
            ChallengeRestartIntent(timerHabit.uuid, id(71), initial.head.roundUuid, 0, 1))
        db.withTransaction { NextChallengeStore(db).acknowledgeInTransaction(access(), metadata.copy(checkpoints = listOf(
            ChallengeCheckpoint(next.head, listOf(initial, next)), ChallengeCheckpoint(other.head, listOf(other))))) }
        val before = proof()
        rejected { goalHabits().applyTimerGoal(app, claim, true) }
        rejected { writer().publishPrompt(timerHabit.id, source) { fail("No earlier-round metric publication") } }
        assertEquals(before, proof())
        assertTrue(requireNotNull(db.habitDao().getHabitById(timerHabit.id)).isActive)
    }

    private fun timerReply(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        if (input.path.endsWith("/push")) return successReply(input)
        val command = json.decodeFromString<TimerCommandBatchRequest>(input.body.toString(Charsets.UTF_8)).commands.single()
        val ended = command.commandType == "stop"
        val state = TimerSessionResponse(command.sessionId, timerHabit.uuid, if (ended) "completed" else "running", id(4),
            1, command.sequence, command.sequence + 1, time, command.occurredAt,
            endedAt = command.occurredAt.takeIf { ended }, timezone = "Asia/Shanghai", isCountdown = false,
            targetSeconds = 60, maxDurationSeconds = 180, activeElapsedMs = if (ended) 60_000 else 0,
            completedEventId = command.sessionId.takeIf { ended })
        return MaterialSocketServer.Reply(json.encodeToString(TimerCommandBatchResponse(listOf(
            TimerCommandResult(command.commandId, command.sessionId, "applied", session = state)), time)).toByteArray())
    }

    @Test fun acknowledgedStopAfterQueueDrainAndColdReopenStillNeedsTheCompleteOriginalReceipt() = runBlocking<Unit> {
        register(); goal(); val source = complete(); val original = view(source)
        val (http, _) = channel(::timerReply)
        for (operation in db.syncOutboxDao().getAll()) sender(http).sendAndAcceptOperation(access(), operation.operationId)
        val timers = NextTimerRequestStore(db, tokens, sessions, sender(http))
        assertEquals(2, timers.pushPending(access()))
        assertTrue(db.timeLogDao().getPendingTimerCommands().isEmpty())
        storage.reopen()
        val reopened = view(source)
        // successReply's authoritative plan ACK owns updated_at; the local projection stores
        // milliseconds. Every business field and the original completion claim remain exact.
        assertEquals(java.time.Instant.parse("2026-10-06T00:00:01.000123Z").toEpochMilli(), reopened.habit.updatedAt)
        assertEquals(original.habit, reopened.habit.copy(updatedAt = original.habit.updatedAt))
        assertEquals(original.progress, reopened.progress)
        assertEquals(original.claim, reopened.claim)
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultHash='broken' WHERE requestId=?", arrayOf(id(22)))
        val before = proof()
        rejected { writer().goalDisplay(original.claim) }
        assertEquals(before, proof())
    }
}
