package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.SyncEntityStateEntity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AcceptedFactRowsTest : NextCoreRequestFixture() {
    private val calls = AtomicInteger()
    init { storage.queryCallback = object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) { calls.incrementAndGet() }
    } }
    private val sql get() = db.openHelper.writableDatabase
    private class ExpectedRollback : RuntimeException()
    private suspend fun withRolledBackDamage(block: suspend () -> Unit) {
        val expected = ExpectedRollback()
        try { db.withTransaction { block(); throw expected } }
        catch (actual: ExpectedRollback) { assertSame(expected, actual) }
    }
    private suspend fun requireManualSavepointDenied(name: String) {
        val failure = rejected { sql.execSQL("SAVEPOINT $name") }
        assertTrue(failure is IllegalStateException)
        assertEquals("COUNT_TRUST_SQL_DENIED", failure.message)
    }
    private fun assertOneRowChanged() {
        sql.query("SELECT changes()").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(1L, cursor.getLong(0)); assertFalse(cursor.moveToNext())
        }
    }
    private fun row(n: Int, payload: String = "{}") = SyncEntityStateEntity("activity_event", id(n), 1,
        payloadJson = payload, payloadHash = syncPayloadHash(payload), updatedAt = 1)
    private suspend fun original(uuid: String): SyncEntityStateEntity? {
        NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf("activity_event", uuid))
            ?: return null
        return requireNotNull(db.syncOutboxDao().getState("activity_event", uuid))
    }

    @Test fun streamWorkspaceResetsForEveryRowAndEveryScanIncludingAfterRejection() = runBlocking<Unit> {
        val payloads = listOf("", "日🙂".repeat(1800), "{}", "x".repeat(4096), "short")
        val rows = payloads.mapIndexed { index, payload -> row(450 + index, payload).copy(
            revision = index.toLong() + 1, updatedAt = -index.toLong()) }
        val ids = rows.map { it.entityUuid }
        suspend fun compare() {
            val single = ids.associateWith { NextRequestSql.rowHash(sql, "sync_entity_state",
                "entityType=? AND entityUuid=?", arrayOf("activity_event", it)) }
            for (order in listOf(ids, ids.reversed())) {
                assertEquals(single, NextRequestSql.boundedRowHashes(sql, "sync_entity_state", "entityUuid",
                    order, entityType = "activity_event"))
            }
            assertEquals(rows.associateBy { it.entityUuid }, acceptedFactRows(db, ids))
        }
        val before = db.withTransaction {
            rows.forEach { db.syncOutboxDao().upsertState(it) }
            compare()
            requireManualSavepointDenied("stream_rejection")
            NextReplicaStorageProof(db).readInTransaction().contents
        }
        // Catch the expected read refusal, then force a known whole-transaction rollback.
        // Assertion/cancellation failures are not mistaken for the rollback sentinel.
        withRolledBackDamage {
            sql.execSQL("UPDATE sync_entity_state SET deleted=2 WHERE entityUuid=?", arrayOf(ids[2]))
            assertOneRowChanged()
            assertNotNull(rejected { acceptedFactRows(db, ids) })
        }
        db.withTransaction {
            assertEquals(before, NextReplicaStorageProof(db).readInTransaction().contents)
            compare() // A partially hashed failed row cannot contaminate a later scan.
        }
    }

    @Test fun boundedRowsMatchOriginalAuditIncludingMissingAndCompositeIdentityWithoutWrites() = runBlocking<Unit> {
        db.withTransaction {
            val rows = (400..409).map { row(it) }
            rows.forEach { db.syncOutboxDao().upsertState(it) }
            db.syncOutboxDao().upsertState(row(400).copy(entityType = "metric", payloadJson = "different namespace"))
            val ids = (400..410).map(::id)
            val before = nextRestartDatabaseProof(db)
            calls.set(0)
            val expected = ids.mapNotNull { uuid -> original(uuid)?.let { uuid to it } }.toMap()
            val separateCalls = calls.get()
            calls.set(0)
            val actual = acceptedFactRows(db, ids)
            assertEquals(4, calls.get()) // schema + length + raw types/hash + bounded Room rows
            assertTrue(calls.get() < separateCalls)
            assertEquals(expected, actual)
            assertFalse(actual!!.containsKey(id(410)))
            assertEquals(before, nextRestartDatabaseProof(db))
            db.syncOutboxDao().upsertState(row(400).copy(revision = 2))
            assertEquals(2L, acceptedFactRows(db, ids)!!.getValue(id(400)).revision) // no cached proof
        }
    }

    @Test fun overBudgetChunkFallsBackWithoutTruncatingOrRejectingValidRows() = runBlocking<Unit> {
        db.withTransaction {
            val rows = (420..423).map { row(it, "x".repeat(600_000)) }
            rows.forEach { db.syncOutboxDao().upsertState(it) }
            calls.set(0)
            assertNull(acceptedFactRows(db, rows.map { it.entityUuid }))
            assertEquals(2, calls.get()) // no full batch payload allocation
            assertEquals(rows, rows.map { original(it.entityUuid) })
        }
    }

    @Test fun damagedOriginalTypesAndOversizedRowsAreStillRejected() = runBlocking<Unit> {
        val mutations = listOf("revision=1.5", "deleted=2", "payloadHash=x'00'", "entityUuid=x'00'",
            "payloadJson=zeroblob(1200000)")
        val before = db.withTransaction {
            db.syncOutboxDao().upsertState(row(430))
            assertEquals(row(430), original(id(430)))
            assertEquals(row(430), acceptedFactRows(db, listOf(id(430)))!!.getValue(id(430)))
            requireManualSavepointDenied("damaged_fact")
            NextReplicaStorageProof(db).readInTransaction().contents
        }
        for (mutation in mutations) {
            withRolledBackDamage {
                sql.execSQL("UPDATE sync_entity_state SET $mutation WHERE entityType='activity_event' AND entityUuid=?", arrayOf(id(430)))
                assertOneRowChanged()
                // A BLOB primary key no longer matches the original TEXT key: both paths prove absence.
                if (mutation.startsWith("entityUuid")) {
                    assertNull(original(id(430)))
                    assertTrue(acceptedFactRows(db, listOf(id(430)))!!.isEmpty())
                } else {
                    assertNotNull(rejected { original(id(430)) })
                    assertNotNull(rejected { acceptedFactRows(db, listOf(id(430))) })
                }
            }
            db.withTransaction {
                assertEquals(before, NextReplicaStorageProof(db).readInTransaction().contents)
                assertEquals(row(430), original(id(430)))
                assertEquals(row(430), acceptedFactRows(db, listOf(id(430)))!!.getValue(id(430)))
            }
        }
        assertNotNull(rejected { acceptedFactRows(db, listOf(id(430))) })
    }
}
