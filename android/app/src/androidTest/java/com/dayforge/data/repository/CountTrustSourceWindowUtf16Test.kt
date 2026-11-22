package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.entity.CompletionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A real UTF-16le SQLite file opened by the same controlled Room builder; no fake codec or
 * changed product format. Encoding is established only while creating a new isolated file.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustSourceWindowUtf16Test : NextCoreRequestFixture() {
    private val spy = SourceWindowNativeSpy(utf16 = true)
    init { storage.isolatedBuilder = { spy.build(it) } }
    private class Rollback : RuntimeException()

    @Test fun realUtf16FileKeepsOriginalHashesAndExactFactAndSourceWindowMeaning() = runBlocking<Unit> {
        register()
        val stable = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
        try { db.withTransaction<Unit> {
            val sql = db.openHelper.writableDatabase
            sql.query("PRAGMA main.encoding").use {
                assertTrue(it.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_STRING, it.getType(0))
                assertEquals("UTF-16le", it.getString(0)); assertFalse(it.moveToNext())
            }
            val tables = listOf("sync_outbox", "timer_command_outbox")
            val before = tables.associateWith { NextRequestSql.sources(sql, it) }
            val marks = tables.associateWith { NextRequestSql.watermark(sql, it) }
            val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
            val boundary = owner.maintenance(db) { owner.captureChangeBoundary() }
            val input = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(66001), value = 1,
                date = millis, actualCompletedAt = millis, createdAt = millis, recordedTimezone = "Etc/UTC", recordedLocalDate = "2026-10-06")
            val plan = requireNotNull(CountLocalSourcePlan.freeze(habit, input, marks.getValue("sync_outbox")))
            val factId = db.completionDao().insertForSync(input)
            assertEquals(input.copy(id = factId), plan.verifiedStoredFact(sql, factId))
            val native = CountTrustRows(sql)
            assertTrue(plan.matchesNativeFact(factId, native.read("completions", "uuid=?", arrayOf(input.uuid)).single()))
            val row = db.syncOutboxDao().getAll().single()
            val expected = requireNotNull(plan.bind(factId, input.copy(id = factId), row))
            assertEquals(NextRequestSql.sourceHash(expected), NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(row.id)))
            spy.begin()
            val delta = requireNotNull(owner.maintenance(db) {
                val window = requireNotNull(owner.sourceWindow(boundary, 1))
                NextSourceDelta.prepare(boundary, before, marks, listOf(expected), window, owner, sql, native)
            })
            val sample = spy.end()
            assertFalse(sample.overflow)
            assertEquals(0, sample.count(SourceWindowNativeSpy.Kind.JOURNAL_HEADERS_UNBOUNDED))
            assertEquals(0, sample.count(SourceWindowNativeSpy.Kind.JOURNAL_CELLS_FULL))
            assertTrue(sample.count(SourceWindowNativeSpy.Kind.JOURNAL_CELLS_SCALAR) >= 2)
            assertEquals(delta.completeExpected(), tables.associateWith { NextRequestSql.sources(sql, it) })
            val all = owner.maintenance(db) { owner.pendingChanges(boundary) }
            assertEquals(setOf("completions", "sync_outbox"), all.map { it.table }.toSet())
            assertEquals(1, all.count { it.table == "sync_outbox" && it.operation == "INSERT" })
            throw Rollback()
        } } catch (_: Rollback) { }
        assertTrue("Complete typed main contents must roll back in the real UTF-16 file",
            stable == db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents })
    }
}
