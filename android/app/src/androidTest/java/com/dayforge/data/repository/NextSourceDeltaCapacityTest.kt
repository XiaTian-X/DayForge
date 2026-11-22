package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.entity.CompletionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Separate complete capacity class: 10,000 actual completion INSERTs produce 10,000 actual
 * trigger sources. Requires an upfront physical-runtime estimate before admission; no fake
 * before map, journal or relaxed original capacity. Not part of the small class's budget.
 */
@RunWith(AndroidJUnit4::class)
class NextSourceDeltaCapacityTest : NextCoreRequestFixture() {
    private class Rollback : RuntimeException()
    private val tables = listOf("sync_outbox", "timer_command_outbox")
    private suspend fun sources(): Map<String, Map<Long, String>> = tables.associateWith {
        NextRequestSql.sources(db.openHelper.writableDatabase, it)
    }

    @Test fun tenThousandRealSourcesRejectTheNextAllocationLikeTheOriginalScanner() = runBlocking<Unit> {
        // This is source-capacity fixture data, not fabricated accepted business history.
        // Native triggers execute normally for each row; only statement round-trips are batched.
        db.withTransaction {
            for (chunk in (0 until 10000).toList().chunked(64)) {
                val placeholders = chunk.joinToString(",") { "(?,?,?,?,?,?,?,?,?,?)" }
                val args = chunk.flatMap { n -> listOf<Any>(habit.id, millis, 1, millis, id(70000 + n),
                    habit.uuid, millis, "Etc/UTC", "2026-10-06", "captured") }.toTypedArray()
                db.openHelper.writableDatabase.execSQL("INSERT INTO completions(habitId,date,value,actualCompletedAt,uuid,habitUuid," +
                    "createdAt,recordedTimezone,recordedLocalDate,timeMetadataSource) VALUES $placeholders", args)
            }
        }
        assertEquals(10000, count("completions")); assertEquals(10000, count("sync_outbox"))
        var retainedBefore: Map<String, Map<Long, String>>? = null
        try { db.withTransaction<Unit> {
            val sql = db.openHelper.writableDatabase; val native = CountTrustRows(sql)
            val before = sources(); retainedBefore = before
            assertEquals(10000, before.getValue("sync_outbox").size)
            val watermarks = tables.associateWith { NextRequestSql.watermark(sql, it) }
            val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
            val boundary = owner.maintenance(db) { owner.captureChangeBoundary() }
            val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(90001),
                date = millis, actualCompletedAt = millis, value = 1, createdAt = millis,
                recordedTimezone = "Etc/UTC", recordedLocalDate = "2026-10-06")
            val plan = requireNotNull(CountLocalSourcePlan.freeze(habit, fact, watermarks.getValue("sync_outbox")))
            val saved = db.completionDao().insertForSync(fact)
            val raw = native.read("completions", "uuid=?", arrayOf(plan.uuid)).single()
            assertTrue(plan.matchesNativeFact(saved, raw))
            val queue = native.read("sync_outbox", "recordType=? AND entityUuid=?", arrayOf("completion", plan.uuid)).single()
            val dto = requireNotNull(db.syncOutboxDao().getById(queue.long("id")))
            val expected = requireNotNull(plan.bind(saved, raw.completion(), dto))
            val deltaFailure = rejected { owner.maintenance(db) {
                val window = requireNotNull(owner.sourceWindow(boundary, 1))
                NextSourceDelta.prepare(boundary, before, watermarks, listOf(expected), window, owner, sql, native)
            } }
            val originalFailure = rejected { sources() }
            assertTrue(deltaFailure is NextRequestException); assertTrue(originalFailure is NextRequestException)
            val deltaReason = (deltaFailure as NextRequestException).reason
            val originalReason = (originalFailure as NextRequestException).reason
            assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE, deltaReason)
            assertEquals(deltaReason, originalReason)
            throw Rollback()
        } } catch (_: Rollback) { /* Real transaction rollback is required, not a swallowed product failure. */ }
        assertEquals(10000, count("completions")); assertEquals(10000, count("sync_outbox"))
        assertNull(db.completionDao().getCompletionByUuid(id(90001)))
        assertEquals(requireNotNull(retainedBefore), db.withTransaction { sources() })
    }
}
