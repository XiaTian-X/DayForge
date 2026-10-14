package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Retained-index capacity only; synthetic index rows are NOT accepted timers or completion proof. */
@RunWith(AndroidJUnit4::class)
class TimerHistoryIndexTest : NextCoreRequestFixture() {
    @Test fun retainedIndexHasNoLifetimeCutoffAndLateCorruptionCannotHideBeyondTenThousand() = runBlocking<Unit> {
        val began = android.os.SystemClock.elapsedRealtime()
        var previous = began
        fun mark(phase: String) {
            val now = android.os.SystemClock.elapsedRealtime()
            android.util.Log.i("DayForgeTimerCapacity", "phase=$phase deltaMillis=${now - previous} totalMillis=${now - began}")
            previous = now
        }
        register(); start()
        val original = requireNotNull(db.nextRequestDao().origin(NEXT_TIMER, id(21)))
        val intent = decodeNextTimerIntent(original.intentJson)
        mark("real-start-ready")
        // Keep one real producer-created running session. The additional index-only metadata
        // deliberately has no TimeLog/segment/fact/receipt and must never create progress.
        db.withTransaction {
            db.openHelper.writableDatabase.compileStatement("INSERT INTO next_request_origins " +
                "(kind,requestId,queueId,protocol,accountId,serverInstanceId,syncEpoch,sourceHash,intentJson) " +
                "VALUES (?,?,?,?,?,?,?,?,?)").use { insert ->
                repeat(10_001) { n ->
                    val request = id(100_000 + n * 2)
                    val encoded = Json.encodeToString(intent.copy(command = intent.command.copy(
                        commandId = request, sessionId = id(100_001 + n * 2))))
                    // The last page exceeds the batch memory hint, not the per-row contract.
                    // It must fall back to the identical single-row audit, never truncate history.
                    val body = if (n >= 9_998) encoded.padEnd(710_000, ' ') else encoded
                    insert.clearBindings()
                    insert.bindString(1, NEXT_TIMER); insert.bindString(2, request); insert.bindLong(3, 100_000L + n)
                    insert.bindLong(4, 5); insert.bindString(5, original.accountId)
                    insert.bindString(6, requireNotNull(original.serverInstanceId)); insert.bindString(7, requireNotNull(original.syncEpoch))
                    insert.bindString(8, original.sourceHash); insert.bindString(9, body); insert.executeInsert()
                }
            }
        }
        mark("retained-index-seeded")
        val queued = db.timeLogDao().getPendingTimerCommands()
        val log = db.timeLogDao().getActiveTimeLog()
        val last = requireNotNull(db.nextRequestDao().origin(NEXT_TIMER, id(120_000)))
        val reader = TimerHistoryReader(db, tokens, sessions)
        mark("normal-read-start")
        val view = reader.read(timerHabit)
        mark("normal-read-complete")
        assertEquals(log, view.activeLog); assertEquals(listOf(log), view.logs)
        assertTrue(view.completedMillis.isEmpty()); assertFalse(view.completedToday); assertTrue(view.qualifiedDates.isEmpty())
        assertEquals(queued, db.timeLogDao().getPendingTimerCommands())
        assertEquals(last, db.nextRequestDao().origin(NEXT_TIMER, last.requestId))
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET sourceHash=CAST(sourceHash AS BLOB) WHERE requestId=?",
            arrayOf(last.requestId))
        mark("late-corruption-read-start")
        rejected { reader.read(timerHabit) }
        mark("late-corruption-rejected")
        assertEquals(log, db.timeLogDao().getActiveTimeLog()); assertEquals(queued, db.timeLogDao().getPendingTimerCommands())
        db.openHelper.writableDatabase.query("SELECT typeof(sourceHash) FROM next_request_origins WHERE requestId=?",
            arrayOf(last.requestId)).use { c -> assertTrue(c.moveToFirst()); assertEquals("blob", c.getString(0)) }
        mark("assertions-complete")
    }
}
