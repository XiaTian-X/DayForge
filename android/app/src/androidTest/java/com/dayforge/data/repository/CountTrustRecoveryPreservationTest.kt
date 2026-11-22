package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exact original preservation bytes plus the only permitted derived control transition. */
@RunWith(AndroidJUnit4::class)
class CountTrustRecoveryPreservationTest : NextCoreRequestFixture() {
    private val sql get() = db.openHelper.writableDatabase
    private val owner get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    private val recoveryId get() = id(9010)
    private val store get() = NextRecoveryPreservationStore(db)

    @Test fun originalAllowedWritesAdvanceOnlyTheExactNativeJournalGeneration() = runBlocking<Unit> {
        register()
        db.withTransaction {
            val before = store.snapshot(recoveryId)
            sql.execSQL("UPDATE habits SET name=name WHERE id=?", arrayOf(habit.id))
            store.requireUnchanged(recoveryId, before)
            val after = requireNotNull(CountTrustRecoveryControl.read(db))
            val old = requireNotNull(before.control)
            assertTrue(after.generation > old.generation)
            assertEquals(old.storageId, after.storageId); assertEquals(1L, after.dirty)
            assertFalse(owner.isRetired)
        }
    }

    @Test fun explicitlyRetiredOwnerStillAllowsFullOriginalPreservationAndStaysRetired() = runBlocking<Unit> {
        register()
        db.withTransaction { owner.maintenance(db) { owner.retireFastPathForRecovery() } }
        db.withTransaction {
            val before = store.snapshot(recoveryId)
            assertNull(before.boundary)
            sql.execSQL("UPDATE metrics SET name=name WHERE id=?", arrayOf(metric.id))
            store.requireUnchanged(recoveryId, before)
            assertTrue(owner.isRetired); assertEquals(0, count("count_trust_root"))
        }
    }

    private class Rollback : RuntimeException()
    private suspend fun rejectsChange(retired: Boolean = false, expectedControlError: String? = null, change: suspend () -> Unit) {
        if (retired) db.withTransaction { owner.maintenance(db) { owner.retireFastPathForRecovery() } }
        var reached = false
        try {
            db.withTransaction {
                val before = store.snapshot(recoveryId)
                change()
                val failure = runCatching { store.requireUnchanged(recoveryId, before) }.exceptionOrNull()
                assertNotNull(failure)
                if (expectedControlError != null) {
                    assertTrue(failure is IllegalStateException)
                    assertEquals(expectedControlError, failure?.message)
                }
                reached = true
                throw Rollback()
            }
        } catch (_: Rollback) { }
        assertTrue(reached)
    }

    @Test fun unchangedBusinessCannotManufactureAJournalGenerationAdvance() = runBlocking<Unit> {
        register()
        rejectsChange { owner.maintenance(db) {
            sql.execSQL("UPDATE count_trust_control SET generation=generation+1,dirty=1 WHERE id=1")
        } }
    }

    @Test fun controlRollbackCleanPromotionAndStorageRebindingAreRejectedEvenWhenRetired() = runBlocking<Unit> {
        register()
        rejectsChange(retired = true) { owner.maintenance(db) {
            sql.execSQL("UPDATE count_trust_control SET generation=generation-1 WHERE id=1")
        } }
        rejectsChange { owner.maintenance(db) { sql.execSQL("UPDATE count_trust_control SET dirty=0 WHERE id=1") } }
        rejectsChange { owner.maintenance(db) {
            sql.execSQL("UPDATE count_trust_control SET storageId=? WHERE id=1", arrayOf(id(9011)))
        } }
    }

    @Test fun duplicateAndNativeMalformedControlCannotBeNormalizedIntoValidIdentity() = runBlocking<Unit> {
        register()
        val native = CountConsumerReadFixtureAssertions(db, tokens, sessions)
        val before = native.capture()
        rejectsChange(expectedControlError = "COUNT_TRUST_CONTROL_INVALID") { owner.maintenance(db) {
            val anchor = sql.query("SELECT commandRootHash FROM main.count_trust_control WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_STRING, cursor.getType(0))
                cursor.getString(0).also { assertTrue(it.matches(Regex("[0-9a-f]{64}"))); assertFalse(cursor.moveToNext()) }
            }
            sql.execSQL("INSERT INTO count_trust_control(id,storageId,generation,dirty,commandRootHash) " +
                "SELECT 2,?,0,1,commandRootHash FROM main.count_trust_control WHERE id=1", arrayOf(id(9012)))
            sql.query("SELECT id,storageId,generation,dirty,commandRootHash FROM main.count_trust_control ORDER BY id").use { cursor ->
                assertEquals(2, cursor.count)
                assertTrue(cursor.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_INTEGER, cursor.getType(0)); assertEquals(1L, cursor.getLong(0))
                assertEquals(Cursor.FIELD_TYPE_STRING, cursor.getType(4)); assertEquals(anchor, cursor.getString(4))
                assertTrue(cursor.moveToNext()); assertEquals(Cursor.FIELD_TYPE_INTEGER, cursor.getType(0)); assertEquals(2L, cursor.getLong(0))
                assertEquals(Cursor.FIELD_TYPE_STRING, cursor.getType(1)); assertEquals(id(9012), cursor.getString(1))
                assertEquals(Cursor.FIELD_TYPE_INTEGER, cursor.getType(2)); assertEquals(0L, cursor.getLong(2))
                assertEquals(Cursor.FIELD_TYPE_INTEGER, cursor.getType(3)); assertEquals(1L, cursor.getLong(3))
                assertEquals(Cursor.FIELD_TYPE_STRING, cursor.getType(4)); assertEquals(anchor, cursor.getString(4))
                assertFalse(cursor.moveToNext())
            }
        } }
        assertEquals(before, native.capture())
        rejectsChange(expectedControlError = "COUNT_TRUST_CONTROL_INVALID") { owner.maintenance(db) {
            sql.execSQL("UPDATE count_trust_control SET generation='malformed' WHERE id=1")
            sql.query("SELECT generation FROM main.count_trust_control WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_STRING, cursor.getType(0))
                assertEquals("malformed", cursor.getString(0)); assertFalse(cursor.moveToNext())
            }
        } }
        assertEquals(before, native.capture())
    }

    @Test fun rootNodesRoutesAndCommandResultsRemainProtectedContents() = runBlocking<Unit> {
        register()
        val writes = listOf(
            "INSERT INTO count_trust_root VALUES(1,'binding',1,'validator','schema','root',0,0,1,0,X'00')",
            "INSERT INTO count_trust_nodes VALUES('digest',X'00')",
            "INSERT INTO count_trust_routes VALUES('completions','key',NULL,NULL,NULL,NULL,'role','hash')",
            "INSERT INTO count_command_results VALUES('command','input','fact',NULL,'result','committed')"
        )
        for (write in writes) rejectsChange { owner.maintenance(db) { sql.execSQL(write) } }
    }

    @Test fun originalProtectedBusinessRowsStillRejectDespiteValidDirtyAdvance() = runBlocking<Unit> {
        register()
        rejectsChange { sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1") }
        rejectsChange(retired = true) { sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1") }
    }

    @Test fun schemaChangeCannotBeHiddenByRetiringTheOwner() = runBlocking<Unit> {
        register()
        rejectsChange { sql.execSQL("CREATE INDEX count_preservation_test ON habits(name)") }
        assertTrue(owner.isRetired)
    }
}
