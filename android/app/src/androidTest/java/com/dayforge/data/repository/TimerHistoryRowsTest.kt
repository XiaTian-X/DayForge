package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.*
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Child SELECT equivalence only; these synthetic rows are NOT completed timer proof. */
@RunWith(AndroidJUnit4::class)
class TimerHistoryRowsTest : NextCoreRequestFixture() {
    private val queries = AtomicInteger()
    init { storage.queryCallback = object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            if (sqlQuery.startsWith("SELECT * FROM timer_segments") ||
                sqlQuery.startsWith("SELECT * FROM timelog_day_allocations")) queries.incrementAndGet()
        }
    } }

    private suspend fun seed(n: Int, count: Int): String {
        val session = id(2000 + n)
        db.timeLogDao().insertForSync(TimeLogEntity(uuid = session, habitId = timerHabit.id, startTime = millis,
            endTime = millis + 500, durationSeconds = 0, date = millis))
        repeat(count) { i ->
            db.timeLogDao().insertTimerSegment(TimerSegmentEntity(sessionUuid = session, sequence = i + 1,
                startedAt = millis + i * 1000L, endedAt = millis + i * 1000L + 500))
            val day = LocalDate.parse("2026-01-01").plusDays(i.toLong())
            db.timeLogDao().upsertDayAllocations(listOf(TimeLogDayAllocationEntity(session, timerHabit.id,
                day.toString(), day.toEpochDay() * 86_400_000, "UTC", 500)))
        }
        return session
    }

    @Test fun boundedHintsMatchOriginalQueriesAcrossPagesAndEmptySessions() = runBlocking<Unit> {
        db.withTransaction {
            val ids = (0..128).map { seed(it, if (it % 2 == 0) 2 else 0) }
            val expected = ids.associateWith { db.timeLogDao().getTimerSegments(it) to db.timeLogDao().getDayAllocations(it) }
            queries.set(0)
            for (page in ids.chunked(128)) {
                val rows = TimerHistoryRows.read(db, page)
                for (id in page) assertEquals(expected[id], rows.segments(db, id) to rows.allocations(db, id))
            }
            assertEquals("Two bounded SELECTs per page, no per-session trips", 4, queries.get())
            val first = ids.first()
            db.timeLogDao().deleteDayAllocations(first)
            assertTrue(TimerHistoryRows.read(db, listOf(first)).allocations(db, first).isEmpty())
        }
    }

    @Test fun overflowFallsBackWithoutTruncatingOrSkippingLateRows() = runBlocking<Unit> {
        db.withTransaction {
            val id = seed(0, 257)
            val rows = TimerHistoryRows.read(db, listOf(id))
            queries.set(0)
            assertEquals(257, rows.segments(db, id).size)
            assertEquals(257, rows.allocations(db, id).size)
            assertEquals(2, queries.get())
            assertEquals(db.timeLogDao().getTimerSegments(id), rows.segments(db, id))
            assertEquals(db.timeLogDao().getDayAllocations(id), rows.allocations(db, id))
        }
    }

    @Test fun wrongPageAndNonTransactionAreRejected() = runBlocking<Unit> {
        assertTrue(runCatching { TimerHistoryRows.read(db, listOf(id(1))) }.isFailure)
        db.withTransaction {
            val id = seed(0, 0)
            val rows = TimerHistoryRows.read(db, listOf(id))
            assertTrue(runCatching { rows.segments(db, this@TimerHistoryRowsTest.id(1)) }.isFailure)
            assertTrue(runCatching { rows.allocations(db, this@TimerHistoryRowsTest.id(1)) }.isFailure)
            assertTrue(runCatching { TimerHistoryRows.read(db, listOf(id, id)) }.isFailure)
        }
    }
}
