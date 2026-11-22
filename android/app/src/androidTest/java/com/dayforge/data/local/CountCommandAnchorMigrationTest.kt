package com.dayforge.data.local

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.CountCommandRootAnchor
import com.dayforge.data.repository.CountTrustSeal
import com.dayforge.data.repository.NextReplicaStorageProof
import com.dayforge.data.repository.NextReplicaV19StorageView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exact real v19 source -> production Room20 edge. Requires real KSP20 before admission;
 * no generated schema is constructed by this class. No execution/test result claimed. */
@RunWith(AndroidJUnit4::class)
class CountCommandAnchorMigrationTest : CountCommandMigrationFixture() {
    private class Rollback : RuntimeException()

    @Test(timeout = 150_000) fun frozen19InitializesAnchorOnlyInsideTheAtomicUpgradeThenRestoresTheFence() = runBlocking<Unit> {
        val before = source { sql ->
            sql.execSQL("INSERT INTO next_replica_fence(recoveryId,accountId,serverInstanceId,oldEpoch,newEpoch," +
                "authenticationGeneration,deviceId,capabilityRevision,sourceHash,mode,id) VALUES(?,?,?,?,?,?,?,?,?,'frozen',1)",
                arrayOf<Any>(id(71), id(1), id(2), id(3), id(72), "original-auth", id(4), 1, "f".repeat(64)))
        }
        val current = openCurrent(); val sql = current.openHelper.writableDatabase
        val after = current.withTransaction { NextReplicaStorageProof(current).readInTransaction() }
        assertEquals(before.contents, requireNotNull(NextReplicaV19StorageView.project(after)).contents)
        CountCommandStorageInitialization.verifyMandatory(sql)
        val original = current.withTransaction { CountCommandRootAnchor.read(sql) }
        var ordinaryInvalidation = false
        try {
            current.withTransaction<Unit> { requireNotNull(CountTrustRuntime.forDatabase(current)).maintenance(current) {
                sql.execSQL("UPDATE count_trust_control SET generation=generation+1,dirty=1 WHERE id=1")
                val invalidated = CountCommandRootAnchor.read(sql)
                assertEquals(original.generation + 1, invalidated.generation); assertEquals(original.hash, invalidated.hash)
                ordinaryInvalidation = true
                throw Rollback()
            } }
        } catch (_: Rollback) { }
        assertTrue(ordinaryInvalidation)
        val failure: Throwable? = try {
            current.withTransaction { requireNotNull(CountTrustRuntime.forDatabase(current)).maintenance(current) {
                sql.execSQL("UPDATE count_trust_control SET generation=generation+1,dirty=1,commandRootHash=? WHERE id=1", arrayOf("0".repeat(64)))
            } }; null
        } catch (error: android.database.SQLException) { error }
        assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("SYNC_REPLICA_SWITCH_FENCED") == true })
        assertEquals(original, current.withTransaction { CountCommandRootAnchor.read(sql) })
        assertEquals(after.contents, current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents })
    }

    @Test(timeout = 150_000) fun utf16AnchorHas64CharactersAndRetainsEveryOldCellAcrossActualReopen() = runBlocking<Unit> {
        val before = source(utf16 = true)
        val current = openCurrent(); val sql = current.openHelper.writableDatabase
        sql.query("SELECT typeof(commandRootHash),length(commandRootHash),length(CAST(commandRootHash AS BLOB)) FROM count_trust_control WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals("text", it.getString(0)); assertEquals(64L, it.getLong(1)); assertEquals(128L, it.getLong(2)); assertFalse(it.moveToNext())
        }
        CountCommandStorageInitialization.verifyMandatory(sql)
        val proof = current.withTransaction { NextReplicaStorageProof(current).readInTransaction() }
        assertEquals(before.contents, requireNotNull(NextReplicaV19StorageView.project(proof)).contents)
        val reopened = openCurrent()
        assertEquals(proof.contents, reopened.withTransaction { NextReplicaStorageProof(reopened).readInTransaction().contents })
    }

    @Test(timeout = 150_000) fun approvedOldGuardSpellingAndOldMacBytesRemainExactlyRecoverable() = runBlocking<Unit> {
        val access = requireNotNull(tokens.localCoreWriteAccess())
        var retained: CountTrustSeal.Header? = null
        val before = source { sql ->
            val old = sql.query("SELECT sql FROM sqlite_master WHERE name=?", arrayOf(CountCommandControlSchema.GUARD)).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }
            val changed = old.replace("NEW.dirty=1", "new.`dirty` = 1")
            assertNotEquals(old, changed)
            sql.execSQL("DROP TRIGGER `${CountCommandControlSchema.GUARD}`"); sql.execSQL(changed)
            retained = oldCountRoot(sql, access, dirty = true)
        }
        val current = openCurrent(); val sql = current.openHelper.writableDatabase
        val after = current.withTransaction { NextReplicaStorageProof(current).readInTransaction() }
        assertEquals(before.contents, requireNotNull(NextReplicaV19StorageView.project(after)).contents)
        assertEquals(retained, current.withTransaction { CountTrustSeal.read(sql) })
        CountCommandStorageInitialization.verifyMandatory(sql)
        sql.query("SELECT sql FROM sqlite_master WHERE name=?", arrayOf(CountCommandControlSchema.GUARD)).use {
            assertTrue(it.moveToFirst()); val actual = it.getString(0)
            assertTrue(actual.contains("new.`dirty` = 1 AND NEW.commandRootHash IS OLD.commandRootHash"))
            assertEquals(before.contents.getValue("schema"), requireNotNull(NextReplicaV19StorageView.project(after)).contents.getValue("schema"))
        }
    }
}
