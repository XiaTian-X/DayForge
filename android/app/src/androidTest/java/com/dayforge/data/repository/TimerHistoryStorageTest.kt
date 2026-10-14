package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerHistoryStorageTest : NextCoreRequestFixture() {
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }

    @Test fun wrappedRawIntegersAreRejectedWithoutRepairingTimersOrQueues() = runBlocking<Unit> {
        register(); start()
        val reader = TimerHistoryReader(db, tokens, sessions)
        val healthy = reader.read(timerHabit)
        val sql = db.openHelper.writableDatabase
        for ((column, value) in listOf("durationSeconds" to 0L, "timerNextCommandSequence" to 2L,
            "timerControlGeneration" to 1L, "timerBootCount" to null)) {
            for (direction in listOf(1L, -1L)) {
                // Both values would wrap back into an apparently lawful Int when read by Room.
                sql.execSQL("UPDATE timelogs SET $column=? WHERE uuid=?", arrayOf<Any?>(direction * 4_294_967_296L + (value ?: 0), id(20)))
                val bad = proof()
                assertEquals("TIMER_STORAGE_INVALID", rejected { reader.read(timerHabit) }.message)
                assertEquals(bad, proof())
                sql.execSQL("UPDATE timelogs SET $column=? WHERE uuid=?", arrayOf<Any?>(value, id(20)))
                assertEquals(healthy, reader.read(timerHabit))
            }
        }
        sql.execSQL("UPDATE timer_segments SET sequence=4294967297 WHERE sessionUuid=?", arrayOf(id(20)))
        val badSegment = proof()
        assertEquals("TIMER_STORAGE_INVALID", rejected { reader.read(timerHabit) }.message)
        assertEquals(badSegment, proof())
        sql.execSQL("UPDATE timer_segments SET sequence=1 WHERE sessionUuid=?", arrayOf(id(20)))
        assertEquals(healthy, reader.read(timerHabit))
        assertFalse(healthy.completedToday); assertTrue(healthy.completedMillis.isEmpty())
    }

    @Test fun batchedTimerHashesKeepKindNamespaceAndOriginalBytesWithoutWriting() = runBlocking<Unit> {
        register(); val command = start()
        val original = requireNotNull(db.nextRequestDao().origin(NEXT_TIMER, command.commandId))
        // Synthetic metadata collision checks SQL namespace selection only, not an accepted operation.
        db.withTransaction { db.nextRequestDao().insertOrigin(original.copy(kind = NEXT_OPERATION, queueId = 99_999)) }
        val before = proof()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            for (kind in listOf(NEXT_TIMER, NEXT_OPERATION)) {
                val single = NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(kind, original.requestId))
                val batch = requireNotNull(NextRequestSql.boundedRowHashes(sql, "next_request_origins", "requestId", listOf(original.requestId), kind))
                assertEquals(mapOf(original.requestId to single), batch)
            }
            val batch = requireNotNull(NextRequestSql.boundedRowHashes(sql, "timer_command_outbox", "id", listOf(command.id, 99_998L)))
            assertEquals(original.sourceHash, batch[command.id]); assertNull(batch[99_998L])
            assertEquals(listOf(command), db.timeLogDao().getTimerCommands(listOf(command.id, 99_998L)))
        }
        assertEquals(before, proof())
    }
}
