package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Resource/refusal-order gates beneath the actual controlled Room connection. Native faults
 * target real trigger evidence; before maps and expected source rows are never fabricated.
 * Full COUNT/metric business correctness remains with the separate real production-chain suites.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustSourceWindowTest : NextCoreRequestFixture() {
    private val spy = SourceWindowNativeSpy()
    init { storage.isolatedBuilder = { spy.build(it) } }
    private val tables = listOf("sync_outbox", "timer_command_outbox")
    private class Rollback : RuntimeException()
    private data class Frame(val owner: CountTrustRuntime, val boundary: CountTrustJournal.Boundary,
        val before: Map<String, Map<Long, String>>, val watermarks: Map<String, Long>, val source: SyncOutboxEntity)

    private suspend fun image(): JsonObject = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun sources() = tables.associateWith { NextRequestSql.sources(db.openHelper.writableDatabase, it) }
    private suspend fun rollback(block: suspend () -> Unit) {
        try { db.withTransaction<Unit> { block(); throw Rollback() } } catch (_: Rollback) { }
    }
    private suspend fun insert(): Frame {
        val sql = db.openHelper.writableDatabase
        val before = sources(); val marks = tables.associateWith { NextRequestSql.watermark(sql, it) }
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        val boundary = owner.maintenance(db) { owner.captureChangeBoundary() }
        val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(65001), value = 1,
            date = millis, actualCompletedAt = millis, createdAt = millis, recordedTimezone = "Etc/UTC", recordedLocalDate = "2026-10-06")
        val plan = requireNotNull(CountLocalSourcePlan.freeze(habit, fact, marks.getValue("sync_outbox")))
        val factId = db.completionDao().insertForSync(fact)
        assertEquals(fact.copy(id = factId), plan.verifiedStoredFact(sql, factId))
        val ids = sql.query("SELECT id FROM main.sync_outbox WHERE recordType=? AND entityUuid=? ORDER BY id LIMIT 2",
            arrayOf("completion", fact.uuid)).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        val id = ids.single()
        val hash = requireNotNull(NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(id)))
        val dto = requireNotNull(db.syncOutboxDao().getById(id))
        val expected = requireNotNull(plan.bind(factId, fact.copy(id = factId), dto))
        assertEquals(hash, NextRequestSql.sourceHash(expected))
        return Frame(owner, boundary, before, marks, expected)
    }
    private suspend fun prepare(frame: Frame): NextSourceDelta? = frame.owner.maintenance(db) {
        val window = frame.owner.sourceWindow(frame.boundary, 1) ?: return@maintenance null
        NextSourceDelta.prepare(frame.boundary, frame.before, frame.watermarks, listOf(frame.source),
            window, frame.owner, db.openHelper.writableDatabase, CountTrustRows(db.openHelper.writableDatabase))
    }
    private fun noFullJournal(sample: SourceWindowNativeSpy.Snapshot) {
        assertFalse(sample.overflow)
        assertEquals(0, sample.count(SourceWindowNativeSpy.Kind.JOURNAL_HEADERS_UNBOUNDED))
        assertEquals(0, sample.count(SourceWindowNativeSpy.Kind.JOURNAL_CELLS_FULL))
    }
    private suspend fun exactImage(before: JsonObject) { // Used only outside an active transaction.
        assertTrue("Complete typed main image must remain exactly equal", before == image())
    }

    @Test fun realInsertWindowUsesScalarsAndOriginalPreflightBeforeFullSource() = runBlocking<Unit> {
        register(); val stable = image()
        rollback {
            val frame = insert()
            spy.begin()
            val delta = requireNotNull(prepare(frame))
            val sample = spy.end(); noFullJournal(sample)
            assertTrue(sample.count(SourceWindowNativeSpy.Kind.JOURNAL_METADATA_SCALAR) > 0)
            assertEquals(1, sample.count(SourceWindowNativeSpy.Kind.JOURNAL_SOURCE_HEADERS_BOUNDED))
            assertTrue(sample.count(SourceWindowNativeSpy.Kind.JOURNAL_CELLS_SCALAR) >= 2)
            assertTrue(sample.first(SourceWindowNativeSpy.Kind.SOURCE_ROW_SIZE_PREFLIGHT) >= 0)
            assertTrue(sample.first(SourceWindowNativeSpy.Kind.SOURCE_ROW_FULL) > sample.first(SourceWindowNativeSpy.Kind.SOURCE_ROW_SIZE_PREFLIGHT))
            assertEquals(delta.completeExpected(), sources())
            // Original complete journal remains independently available and agrees with the
            // real native contents; the source-only window has not replaced this evidence.
            val all = frame.owner.maintenance(db) { frame.owner.pendingChanges(frame.boundary) }
            assertEquals(setOf("completions", "sync_outbox"), all.map { it.table }.toSet())
            assertEquals(1, all.count { it.table == "sync_outbox" && it.operation == "INSERT" })
        }
        exactImage(stable)
    }

    @Test fun oversizedLateSourcesFallbackBeforeAnyBodyAndKeepOriginalRefusal() = runBlocking<Unit> {
        register(); val stable = image()
        for (column in listOf("lastError", "payloadJson")) {
            rollback {
                val frame = insert()
                db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET $column=? WHERE id=?",
                    arrayOf<Any>("x".repeat(SYNC_REQUEST_LIMIT + 4097), frame.source.id))
                spy.begin(); assertNull(prepare(frame))
                val failure = rejected { sources() }; val sample = spy.end()
                assertTrue(failure is NextRequestException)
                assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE, (failure as NextRequestException).reason)
                noFullJournal(sample)
                assertTrue(sample.count(SourceWindowNativeSpy.Kind.SOURCE_IDS_BOUNDED) > 0)
                assertTrue(sample.count(SourceWindowNativeSpy.Kind.SOURCE_ROW_SIZE_PREFLIGHT) > 0)
                assertEquals(0, sample.count(SourceWindowNativeSpy.Kind.SOURCE_ROW_FULL))
            }
            exactImage(stable)
        }
    }

    @Test fun intermediateLargeSourceRestoredToSmallOnlyLosesOptimization() = runBlocking<Unit> {
        register(); val stable = image()
        rollback {
            val frame = insert(); val original = sources()
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_outbox SET lastError=? WHERE id=?", arrayOf<Any>("x".repeat(SYNC_REQUEST_LIMIT + 4097), frame.source.id))
            sql.execSQL("UPDATE sync_outbox SET lastError=NULL WHERE id=?", arrayOf(frame.source.id))
            spy.begin(); assertNull(prepare(frame)); val sample = spy.end()
            noFullJournal(sample); assertEquals(0, sample.count(SourceWindowNativeSpy.Kind.SOURCE_ROW_FULL))
            assertEquals("Original footer accepts unchanged queue contents; no new COUNT refusal", original, sources())
        }
        exactImage(stable)
    }

    @Test fun manyRealSourcesWithSameUuidMissBeforeBodySelection() = runBlocking<Unit> {
        register(); val stable = image()
        rollback {
            val frame = insert()
            repeat(32) { n -> db.syncOutboxDao().insert(frame.source.copy(id = 0, operationId = id(65100 + n))) }
            spy.begin(); assertNull(prepare(frame)); val sample = spy.end()
            noFullJournal(sample)
            assertEquals(0, sample.count(SourceWindowNativeSpy.Kind.SOURCE_ROW_FULL))
            assertEquals(0, sample.count(SourceWindowNativeSpy.Kind.JOURNAL_SOURCE_HEADERS_BOUNDED))
            val full = sources()
            assertEquals(frame.before.getValue("sync_outbox").size + 33, full.getValue("sync_outbox").size)
            assertEquals(frame.before.getValue("timer_command_outbox"), full.getValue("timer_command_outbox"))
        }
        exactImage(stable)
    }

    @Test fun malformedHeaderAndGapThrowInsteadOfOptionalMiss() = runBlocking<Unit> {
        register(); val stable = image()
        for (fault in listOf(SourceWindowNativeSpy.Fault.HEADER_OPERATION, SourceWindowNativeSpy.Fault.HEADER_TABLE,
            SourceWindowNativeSpy.Fault.SEQUENCE_GAP)) {
            var returned = false
            val failure = rejected { db.withTransaction {
                val frame = insert(); spy.damageLatestSourceEvidence(fault); spy.begin()
                frame.owner.maintenance(db) { frame.owner.sourceWindow(frame.boundary, 1) }
                returned = true
            } }
            val sample = spy.end(); noFullJournal(sample)
            assertFalse(returned)
            assertTrue(failure.message.orEmpty().startsWith("COUNT_TRUST_JOURNAL_"))
            assertEquals(0, sample.count(SourceWindowNativeSpy.Kind.SOURCE_ROW_FULL))
            exactImage(stable); storage.reopen()
        }
    }

    @Test fun realSourceSidesPaddingTypesAndMissingCellsAreNeverMisses() = runBlocking<Unit> {
        register(); val stable = image()
        for (fault in listOf(SourceWindowNativeSpy.Fault.SOURCE_SIDE, SourceWindowNativeSpy.Fault.SOURCE_PADDING,
            SourceWindowNativeSpy.Fault.SOURCE_CELL_TYPE, SourceWindowNativeSpy.Fault.SOURCE_CELLS_MISSING,
            SourceWindowNativeSpy.Fault.SOURCE_TABLE)) {
            var returned = false
            val failure = rejected { db.withTransaction {
                val frame = insert(); spy.damageLatestSourceEvidence(fault); spy.begin()
                prepare(frame); returned = true
            } }
            val sample = spy.end(); noFullJournal(sample)
            assertFalse(returned)
            assertTrue(failure.message.orEmpty().startsWith("COUNT_TRUST_JOURNAL_") ||
                failure.message.orEmpty().startsWith("COUNT_TRUST_SOURCE_JOURNAL_"))
            assertTrue(sample.count(SourceWindowNativeSpy.Kind.JOURNAL_CELLS_SCALAR) > 0)
            exactImage(stable); storage.reopen()
        }
    }

    @Test fun sequenceRegressionAfterInitialMetadataIsStillCorruption() = runBlocking<Unit> {
        register(); val stable = image(); var returned = false
        val failure = rejected { db.withTransaction {
            val frame = insert(); val delta = requireNotNull(prepare(frame))
            frame.owner.maintenance(db) {
                val window = requireNotNull(frame.owner.sourceWindow(frame.boundary, 1))
                spy.begin(); spy.armFinalWindowSequenceRegression()
                delta.verifyWindow(frame.owner, window); returned = true
            }
        } }
        val sample = spy.end(); noFullJournal(sample)
        assertTrue(spy.finalWindowSequenceRegressionTouched()); assertFalse(returned)
        assertEquals("COUNT_TRUST_JOURNAL_GAP", failure.message)
        exactImage(stable)
    }

    @Test fun newerLegitimateChangesInvalidateTheOldWindowWithoutAdoptingThem() = runBlocking<Unit> {
        register(); val stable = image()
        rollback {
            val frame = insert(); val delta = requireNotNull(prepare(frame)); val expected = delta.completeExpected()
            val window = frame.owner.maintenance(db) { requireNotNull(frame.owner.sourceWindow(frame.boundary, 1)) }
            db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET lastError='later' WHERE id=?", arrayOf(frame.source.id))
            spy.begin()
            assertFalse(frame.owner.maintenance(db) { delta.verifyWindow(frame.owner, window) })
            val sample = spy.end(); noFullJournal(sample)
            assertEquals(0, sample.count(SourceWindowNativeSpy.Kind.JOURNAL_CELLS_SCALAR))
            assertEquals(expected, delta.completeExpected()); assertNotEquals(expected, sources())
        }
        exactImage(stable)
    }
}
