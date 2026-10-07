package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.domain.model.TimerActionAuthority
import com.dayforge.domain.service.TimerService
import com.dayforge.domain.service.TimerServiceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import com.dayforge.domain.service.ActiveTimerStateProvider
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.domain.service.FailureChecker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextTimerWriterTest : NextCoreRequestFixture() {
    private fun writer() = NextTimerWriter(db, tokens, sessions)

    private suspend fun startWith(ticket: TimerActionAuthority?) = writer().write(timerHabit.id, ticket) {
        db.timeLogDao().insertSyncedTimer(
            TimeLogEntity(habitId = timerHabit.id, uuid = id(20), startTime = millis, date = millis, endTime = null, durationSeconds = 0,
                timerNextCommandSequence = 2, timerControlGeneration = 1, timerLastCommandAt = millis,
                timerTimezone = "Asia/Shanghai"),
            TimerCommandEntity(commandId = id(21), sessionUuid = id(20), sequence = 1, commandType = "start",
                occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai"),
            TimerSegmentEntity(sessionUuid = id(20), sequence = 1, startedAt = millis))
    }

    @Test fun staleStartConfigurationAndMissingTicketCannotCreatePartialSession() = runBlocking<Unit> {
        val ticket = writer().capture(timerHabit.id)
        producer().write(local()) { db.habitDao().update(timerHabit.copy(targetValue = 2)) }
        assertNull(writer().capture(timerHabit.id, timerHabit))
        val stats = HabitStatusCalculator(FailureChecker(db.completionDao(), db.timeLogDao()),
            db.completionDao(), db.timeLogDao(), timerWriter = writer())
        assertNull(stats.calculate(timerHabit, emptyList(), emptyList()).timerAuthority)
        val structure = db.syncOutboxDao().getAll()
        rejected { startWith(ticket) }
        rejected { startWith(null) }
        assertNull(db.timeLogDao().getActiveTimeLog())
        assertEquals(0, count("timer_segments")); assertEquals(0, count("timer_command_outbox"))
        assertEquals(structure, db.syncOutboxDao().getAll())
        startWith(writer().capture(timerHabit.id))
        assertEquals(120, writer().policy(timerHabit.id, id(20))!!.targetSeconds)
        producer().write(local()) { db.habitDao().update(db.habitDao().getHabitById(timerHabit.id)!!.copy(targetValue = 1)) }
        assertEquals(1, db.habitDao().getHabitById(timerHabit.id)!!.targetValue)
        assertEquals(120, writer().policy(timerHabit.id, id(20))!!.targetSeconds)
    }

    @Test fun staleAuthenticationDeviceAndKnownPermissionsRejectOriginalAction() = runBlocking<Unit> {
        register()
        val ticket = requireNotNull(writer().capture(timerHabit.id))
        tokens.saveDeviceRegistration(id(5), caps, true, 2)
        rejected { startWith(ticket) }
        tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write", "facts.append"), true, 3)
        rejected { startWith(writer().capture(timerHabit.id)) }
        tokens.saveDeviceRegistration(id(4), caps, true, 4)
        tokens.saveLoginSession("synthetic-next", "synthetic-refresh", "member", id(1), false)
        rejected { startWith(ticket) }
        assertNull(db.timeLogDao().getActiveTimeLog())
        assertEquals(0, count("timer_segments")); assertEquals(0, count("next_request_origins"))
    }

    @Test fun cancellationAndOriginInsertFailureRollbackStateSegmentsAndCommandTogether() = runBlocking<Unit> {
        val ticket = writer().capture(timerHabit.id)
        try {
            writer().write(timerHabit.id, ticket) {
                db.timeLogDao().insert(TimeLogEntity(habitId = timerHabit.id, startTime = millis, date = millis, endTime = null, durationSeconds = 0))
                throw CancellationException("cancel before commit")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertNull(db.timeLogDao().getActiveTimeLog())
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_timer_origin BEFORE INSERT ON next_request_origins BEGIN SELECT RAISE(ABORT, 'origin fault'); END")
        rejected { startWith(ticket) }
        assertNull(db.timeLogDao().getActiveTimeLog()); assertEquals(0, count("timer_segments"))
        assertEquals(0, count("timer_command_outbox")); assertEquals(0, count("next_request_origins"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_timer_origin")
        startWith(ticket)
        assertEquals(1, count("next_request_origins"))
    }

    @Test fun originalSessionActionDoesNotRetargetReplacementSessionOrConfirmation() = runBlocking<Unit> {
        startWith(writer().capture(timerHabit.id))
        val old = requireNotNull(writer().capture(timerHabit.id))
        val intent = TimerServiceController.commandIntent(app, TimerService.ACTION_DISCARD, timerHabit.id, 1, authority = old)
        assertEquals(old, TimerActionAuthority.read(intent))
        writer().write(timerHabit.id, old) { db.timeLogDao().deleteTimerAndQueue(requireNotNull(db.timeLogDao().getActiveTimeLog()),
            TimerCommandEntity(commandId = id(22), sessionUuid = id(20), sequence = 2, commandType = "cancel",
                occurredAt = millis + 1, expectedControlGeneration = 1)) }
        start(session = 30, command = 31)
        val current = requireNotNull(db.timeLogDao().getActiveTimeLog())
        val queued = db.timeLogDao().getPendingTimerCommands()
        rejected { writer().requireAction(timerHabit.id, TimerActionAuthority.read(intent)) }
        assertEquals(current, db.timeLogDao().getActiveTimeLog()); assertEquals(queued, db.timeLogDao().getPendingTimerCommands())
        assertNotEquals(intent.data, TimerServiceController.commandIntent(app, TimerService.ACTION_DISCARD,
            timerHabit.id, 1, authority = writer().capture(timerHabit.id)).data)
    }

    @Test fun corruptOriginalSourceIsNotReplacedByCurrentConfigOnColdRead() = runBlocking<Unit> {
        startWith(writer().capture(timerHabit.id))
        val source = db.timeLogDao().getPendingTimerCommands().single()
        db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET occurredAt=occurredAt+1 WHERE commandId=?", arrayOf(id(21)))
        storage.reopen()
        rejected { writer().policy(timerHabit.id, id(20)) }
        db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET occurredAt=? WHERE commandId=?", arrayOf<Any>(source.occurredAt, id(21)))
        assertEquals(60, writer().policy(timerHabit.id, id(20))!!.targetSeconds)
    }

    @Test fun completionFollowUpCannotPromptForPauseOrChangedAuthentication() = runBlocking<Unit> {
        startWith(writer().capture(timerHabit.id))
        val ticket = requireNotNull(writer().capture(timerHabit.id))
        var published = false
        val pausedWait = async(start = CoroutineStart.UNDISPATCHED) {
            writer().afterCompletion(timerHabit.id, ticket) { published = true }
        }
        delay(50)
        assertFalse(pausedWait.isCompleted); assertFalse(published)
        writer().write(timerHabit.id, ticket) {
            val log = requireNotNull(db.timeLogDao().getActiveTimeLog())
            db.timeLogDao().updatePauseAndQueue(log.id, true, millis + 1, 0, 3, millis + 1, 1, null, null,
                TimerCommandEntity(commandId = id(22), sessionUuid = id(20), sequence = 2, commandType = "pause",
                    occurredAt = millis + 1, expectedControlGeneration = 1, activeElapsedMillis = 1))
        }
        pausedWait.await()
        assertFalse(published)
        // The already-changed sequence returns without prompting; the account change is rejected even on that path.
        tokens.saveLoginSession("synthetic-new", "synthetic-refresh", "member", id(1), false)
        rejected { writer().afterCompletion(timerHabit.id, ticket) { published = true } }
        assertFalse(published)
        assertNull(db.timeLogDao().getActiveTimeLog()!!.endTime)
    }

    @Test fun unrelatedRetainedOriginDoesNotOverrideOrLimitCurrentSessionProof() = runBlocking<Unit> {
        startWith(writer().capture(timerHabit.id))
        val birth = requireNotNull(db.nextRequestDao().origin(NEXT_TIMER, id(21)))
        db.nextRequestDao().insertOrigin(birth.copy(requestId = id(99), queueId = 99,
            intentJson = "unrelated retained proof for ${id(98)}", sourceHash = "unrelated"))
        assertEquals(60, writer().policy(timerHabit.id, id(20))!!.targetSeconds)
        assertEquals(2, count("next_request_origins"))
    }

    @Test fun validTicketCannotCommitCommandsForAnotherSessionOrSequence() = runBlocking<Unit> {
        startWith(writer().capture(timerHabit.id))
        val ticket = requireNotNull(writer().capture(timerHabit.id))
        val original = requireNotNull(db.timeLogDao().getActiveTimeLog())
        val queued = db.timeLogDao().getPendingTimerCommands()
        val segments = db.timeLogDao().getTimerSegments(original.uuid)
        for ((uuid, sequence) in listOf(id(99) to 2, original.uuid to 3)) {
            rejected {
                writer().write(timerHabit.id, ticket) {
                    db.timeLogDao().updatePauseAndQueue(original.id, true, millis + 1, 0, 3, millis + 1, 1, null, null,
                        TimerCommandEntity(commandId = id(22), sessionUuid = uuid, sequence = sequence,
                            commandType = "pause", occurredAt = millis + 1, expectedControlGeneration = 1,
                            activeElapsedMillis = 1))
                }
            }
            assertEquals(original, db.timeLogDao().getActiveTimeLog())
            assertEquals(queued, db.timeLogDao().getPendingTimerCommands())
            assertEquals(segments, db.timeLogDao().getTimerSegments(original.uuid))
            assertEquals(1, count("next_request_origins"))
        }
    }

    @Test fun displayFlowSurvivesAccountTransitionWithoutAdoptingOldTimerOrNewTarget() = runBlocking<Unit> {
        startWith(writer().capture(timerHabit.id))
        val state = ActiveTimerStateProvider(db.timeLogDao(), habits(), app, writer()).observe(scope)
        val initial = withTimeout(5_000) { state.filterNotNull().first() }
        assertEquals(1, initial.targetMinutes)
        val before = requireNotNull(db.timeLogDao().getActiveTimeLog())
        // Delay between authentication replacement and local-store cleanup must not crash the consumer.
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false)
        withTimeout(5_000) { state.first { it == null } }
        assertEquals(before, db.timeLogDao().getActiveTimeLog())
        tokens.saveLoginSession("synthetic-return", "synthetic-refresh", "member", id(1), false)
        val resumed = withTimeout(5_000) { state.filterNotNull().first() }
        assertEquals(1, resumed.targetMinutes)
        assertEquals(before.uuid, resumed.authority!!.sessionUuid)
        assertNotEquals(initial.authority!!.authenticationGeneration, resumed.authority!!.authenticationGeneration)
    }
}
