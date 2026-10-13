package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.data.local.replicaStageFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Plain v4 Room/account boundaries; no real elapsed-time or successful server acceptance claims. */
@RunWith(AndroidJUnit4::class)
class LegacyWidgetTimerClaimTest : NextCoreRequestFixture() {
    private fun writer() = NextTimerWriter(db, tokens, sessions)
    private suspend fun legacy(registered: Boolean = false) {
        if (registered) register()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE habits SET appearance=NULL,planMetadata=NULL,completionPolicy=NULL")
            sql.execSQL("UPDATE metrics SET appearance=NULL")
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        timerHabit = requireNotNull(db.habitDao().getHabitById(timerHabit.id))
    }
    private suspend fun claim() = writer().captureLegacyWidget(
        LegacyWidgetRestartClaim.displayed(timerHabit, requireNotNull(tokens.localCoreWriteAccess())),
        db.timeLogDao().getActiveTimeLogForHabit(timerHabit.id))
    private suspend fun startLegacy(captured: LegacyWidgetTimerClaim, number: Int = 80, startedAt: Long = millis): TimeLogEntity {
        val uuid = id(number)
        val row = TimeLogEntity(habitId = captured.identity.habitId, startTime = startedAt, endTime = null, durationSeconds = 0, uuid = uuid,
            timerNextCommandSequence = 2, timerControlGeneration = 1, timerLastCommandAt = startedAt,
            timerTimezone = "UTC", date = millis)
        val inserted = writer().write(row.habitId, null, captured) {
            db.timeLogDao().insertSyncedTimer(row, TimerCommandEntity(sessionUuid = uuid, sequence = 1,
                commandType = "start", occurredAt = startedAt, expectedControlGeneration = 0, activityUuid = captured.identity.habitUuid,
                timezone = "UTC"), TimerSegmentEntity(sessionUuid = uuid, sequence = 1, startedAt = startedAt))
        }
        return row.copy(id = inserted)
    }

    @Test fun offlineOriginalClaimSurvivesColdReadAndStrictDecodeButCannotAdoptANewerDisplayedLog() = runBlocking<Unit> {
        legacy()
        val original = claim()
        assertNull(original.identity.serverInstanceId); assertNull(original.sessionUuid); assertNull(original.incumbent)
        assertEquals(original, LegacyWidgetTimerClaim.decode(original.encode()))
        rejected { LegacyWidgetTimerClaim.decode(original.encode().replaceFirst("\"sessionUuid\":null", "\"sessionUuid\":null,\"sessionUuid\":null")) }
        rejected { LegacyWidgetTimerClaim.decode(" ".repeat(65_537)) }
        storage.reopen()
        writer().requireLegacyWidgetStart(original)
        val running = startLegacy(original)
        rejected { writer().captureLegacyWidget(original.identity, null) }
        rejected { writer().requireLegacyWidgetStart(original) }
        assertEquals(running, writer().requireLegacyWidgetAction(original.running(running.uuid, 2)).activeLog)
        assertEquals(1, count("timer_command_outbox")); assertEquals(0, count("next_request_origins"))
    }

    @Test fun everyOriginalOwnerFieldAndCurrentPermissionRejectWithoutWriting() = runBlocking<Unit> {
        legacy(true)
        val original = claim()
        val identity = original.identity
        val stale = listOf(identity.copy(accountId = id(90)), identity.copy(authenticationGeneration = id(91)),
            identity.copy(serverInstanceId = id(92)), identity.copy(syncEpoch = id(93)),
            identity.copy(deviceId = id(94)), identity.copy(habitUuid = id(95)))
        for (bound in stale) rejected {
            writer().write(timerHabit.id, null, original.copy(identity = bound)) { fail("No stale write") }
        }
        register(permissions = setOf("sync.read", "facts.append"), revision = 2)
        assertEquals(original, writer().captureLegacyWidget(identity, null)) // Read-only accounts still render their timer.
        rejected { writer().write(timerHabit.id, null, original) { fail("No permission") } }
        register(permissions = caps, revision = 3)
        writer().requireLegacyWidgetStart(original)
        tokens.saveLoginSession("same-account-new-login", "refresh", "member", id(1), false)
        rejected { writer().write(timerHabit.id, null, original) { fail("No reauthentication reuse") } }
        assertNull(db.timeLogDao().getActiveTimeLog()); assertEquals(0, count("timer_command_outbox"))
    }

    @Test fun activeControlsKeepOriginalSessionAcrossMidnightAndPlanEditButRejectRepeatedSequence() = runBlocking<Unit> {
        legacy()
        val start = claim(); val log = startLegacy(start, startedAt = millis + 86_399_500L)
        val active = start.running(log.uuid, 2)
        db.habitDao().update(timerHabit.copy(name = "Edited while active", targetValue = 2))
        val afterMidnight = millis + 86_400_500L
        writer().write(timerHabit.id, null, active) {
            db.timeLogDao().updatePauseAndQueue(log.id, true, afterMidnight, 0, 3, afterMidnight,
                1000, null, null, TimerCommandEntity(sessionUuid = log.uuid, sequence = 2,
                    commandType = "pause", occurredAt = afterMidnight, expectedControlGeneration = 1,
                    activeElapsedMillis = 1000))
        }
        val paused = requireNotNull(db.timeLogDao().getActiveTimeLog())
        assertTrue(paused.isPaused)
        rejected { writer().write(timerHabit.id, null, active) { fail("No repeated transition") } }
        writer().requireLegacyWidgetAction(active.running(log.uuid, 3))
        assertEquals(listOf("start", "pause"), db.timeLogDao().getPendingTimerCommands().map { it.commandType })
        assertEquals(0, count("next_request_origins"))
    }

    @Test fun wrongTransitionRollsBackActualTimerAndCommandTogether() = runBlocking<Unit> {
        legacy()
        val start = claim(); val log = startLegacy(start)
        val active = start.running(log.uuid, 2)
        val before = db.timeLogDao().getPendingTimerCommands()
        rejected { writer().write(timerHabit.id, null, active) {
            db.timeLogDao().updatePauseAndQueue(log.id, true, millis + 1000, 0, 3, millis + 1000,
                1000, null, null, TimerCommandEntity(sessionUuid = id(99), sequence = 2,
                    commandType = "pause", occurredAt = millis + 1000, expectedControlGeneration = 1, activeElapsedMillis = 1000))
        } }
        assertEquals(log, db.timeLogDao().getActiveTimeLog())
        assertEquals(before, db.timeLogDao().getPendingTimerCommands())
        assertNull(db.timeLogDao().getTimerSegments(log.uuid).single().endedAt)
    }

    @Test fun switchRequiresOriginalTargetAndIncumbentThenAbsenceAtFinalStart() = runBlocking<Unit> {
        legacy()
        val firstClaim = claim(); val first = startLegacy(firstClaim)
        val second = timerHabit.copy(id = 0, uuid = id(60), name = "Requested second")
        timerHabit = second.copy(id = db.habitDao().insert(second))
        val switch = claim()
        assertEquals(first.uuid, switch.incumbent!!.sessionUuid)
        storage.reopen()
        writer().requireLegacyWidgetStart(switch)
        db.habitDao().update(timerHabit.copy(targetValue = 3))
        rejected { writer().write(first.habitId, null, switch.incumbentClaim(), switch) { fail("No cancellation for changed target") } }
        db.habitDao().update(timerHabit)
        writer().write(first.habitId, null, switch.incumbentClaim(), switch) {
            db.timeLogDao().deleteTimerAndQueue(first, TimerCommandEntity(sessionUuid = first.uuid,
                sequence = 2, commandType = "cancel", occurredAt = millis + 1000, expectedControlGeneration = 1))
        }
        rejected { writer().requireLegacyWidgetStart(switch) }
        val consumed = switch.copy(incumbent = null)
        val secondLog = startLegacy(consumed, 81)
        rejected { startLegacy(consumed, 82) }
        assertEquals(secondLog, db.timeLogDao().getActiveTimeLog())
        assertEquals(listOf("start", "cancel", "start"), db.timeLogDao().getPendingTimerCommands().map { it.commandType })
    }

    @Test fun numericIdReuseAndReplicaFenceCannotBorrowOriginalLegacyClaim() = runBlocking<Unit> {
        legacy(true)
        val original = claim()
        db.habitDao().update(timerHabit.copy(uuid = id(90)))
        rejected { writer().write(timerHabit.id, null, original) { fail("No numeric ID adoption") } }
        db.habitDao().update(timerHabit)
        val stage = tokens.replicaStageFixture()
        NextReplicaWriteFence(db).acquire(stage.fence)
        rejected { writer().write(timerHabit.id, null, original) { fail("No frozen write") } }
        assertNull(db.timeLogDao().getActiveTimeLog()); assertEquals(0, count("timer_command_outbox"))
    }
}
