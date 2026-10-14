package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coVerify
import io.mockk.clearMocks
import io.mockk.spyk
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/account/original-policy reads. Spy observes the real history reader without stubbing it. */
@RunWith(AndroidJUnit4::class)
class WidgetTimerTickReadTest : NextCoreRequestFixture() {
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun tick(writer: NextTimerWriter, snapshot: WidgetTimerReadSnapshot,
        render: suspend (com.dayforge.data.local.entity.TimeLogEntity, com.dayforge.data.api.dto.TimerStartPolicy) -> Unit) =
        writer.renderWidgetTick(snapshot.habit.id, NextStructureMapper.writePlan(snapshot.habit).toString(),
            requireNotNull(snapshot.activeLogHash), snapshot.authority, render)

    @Test fun ticksKeepOriginalPolicyAndClaimsWithoutRepeatingRetainedHistoryOrWritingFacts() = runBlocking<Unit> {
        start()
        producer().write(local()) { db.habitDao().update(timerHabit.copy(targetValue = 3, isCountdown = true)) }
        storage.reopen()
        val current = requireNotNull(db.habitDao().getVisibleHabitById(timerHabit.id))
        val history = spyk(TimerHistoryReader(db, tokens, sessions))
        val writer = NextTimerWriter(db, tokens, sessions, history)
        val snapshot = requireNotNull(writer.widgetSnapshot(current))
        // Android spies also record the real suspend function's continuation resumes with
        // null arguments. Verify the entry separately, then observe ONLY subsequent ticks.
        coVerify(exactly = 1) { history.readInTransaction(current, any()) }
        clearMocks(history, answers = false, recordedCalls = true, childMocks = false)
        val claim = com.dayforge.widget.timer.WidgetTimerAction.from(snapshot).encode()
        val before = proof()
        repeat(3) {
            assertTrue(tick(writer, snapshot) { log, policy ->
                assertFalse(db.inTransaction())
                assertEquals(snapshot.activeLog, log)
                assertEquals(60, policy.targetSeconds); assertFalse(policy.isCountdown)
                assertEquals(180, policy.maxDurationSeconds)
            })
        }
        coVerify(exactly = 0) { history.readInTransaction(any(), any()) }
        assertEquals(claim, com.dayforge.widget.timer.WidgetTimerAction.from(snapshot).encode())
        assertEquals(before, proof())
    }

    @Test fun changedRawRowPlanAndAccountCannotUseOldDisplayHints() = runBlocking<Unit> {
        start()
        val writer = NextTimerWriter(db, tokens, sessions)
        val snapshot = requireNotNull(writer.widgetSnapshot(timerHabit))
        var calls = 0
        db.openHelper.writableDatabase.execSQL("UPDATE timelogs SET timerControlGeneration=4294967297 WHERE uuid=?", arrayOf(id(20)))
        val changed = proof()
        assertFalse(tick(writer, snapshot) { _, _ -> calls++ })
        assertEquals(changed, proof())
        db.openHelper.writableDatabase.execSQL("UPDATE timelogs SET timerControlGeneration=1 WHERE uuid=?", arrayOf(id(20)))
        producer().write(local()) { db.habitDao().update(timerHabit.copy(name = "new display")) }
        assertFalse(tick(writer, snapshot) { _, _ -> calls++ })
        val fresh = requireNotNull(writer.widgetSnapshot(requireNotNull(db.habitDao().getVisibleHabitById(timerHabit.id))))
        sessions.exclusive { tokens.saveLoginSession("synthetic-new", "synthetic-refresh", "member", id(1), false) }
        val after = proof()
        assertFalse(tick(writer, fresh) { _, _ -> calls++ })
        assertEquals(0, calls); assertEquals(after, proof())
    }

    @Test fun originalDamageRejectsTickAndNeverFallsBackToCurrentTarget() = runBlocking<Unit> {
        start()
        val writer = NextTimerWriter(db, tokens, sessions)
        val snapshot = requireNotNull(writer.widgetSnapshot(timerHabit))
        db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET occurredAt=occurredAt+1 WHERE commandId=?", arrayOf(id(21)))
        val before = proof()
        var rendered = false
        rejected { tick(writer, snapshot) { _, _ -> rendered = true } }
        assertFalse(rendered); assertEquals(before, proof())
    }

    @Test fun failedAndCancelledTickReleasesAccountWithoutCreatingCommands() = runBlocking<Unit> {
        start()
        val writer = NextTimerWriter(db, tokens, sessions)
        val snapshot = requireNotNull(writer.widgetSnapshot(timerHabit))
        val before = proof()
        assertTrue(rejected { tick(writer, snapshot) { _, _ -> throw IOException("display unavailable") } } is IOException)
        val entered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) { tick(writer, snapshot) { _, _ -> entered.complete(Unit); awaitCancellation() } }
        try {
            withTimeout(5000) { entered.await(); job.cancelAndJoin() }
            withTimeout(5000) { sessions.exclusive { assertEquals(before, proof()) } }
        } finally { job.cancelAndJoin() }
    }
}
