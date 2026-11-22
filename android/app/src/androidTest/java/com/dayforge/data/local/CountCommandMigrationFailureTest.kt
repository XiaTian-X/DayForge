package com.dayforge.data.local

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountCommandMigrationFailureTest : CountCommandMigrationFixture() {
    @Test(timeout = 150_000) fun actualOldNativeRowMutationDuringMigrationRollsBackTheWholeUpgrade() = runBlocking<Unit> {
        val before = source(); val fault = CountCommandMigrationNativeFault(CountCommandMigrationNativeFault.Fault.OLD_ROW_AFTER_FIRST_NEW_TABLE)
        room = fault.build(context)
        val failure = runCatching { requireNotNull(room).openHelper.writableDatabase }.exceptionOrNull()
        assertEquals(1, fault.hits); assertNotNull(failure)
        assertTrue(fault.observedOriginalFailure); assertTrue(fault.containsOriginalFailure(failure))
        assertTrue("Actual Framework retry must remain failed on this fault owner", fault.retryRejections >= 1)
        assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("COMMAND_MIGRATION_CHANGED_OLD_ROWS") == true })
        room?.close(); HabitDatabaseProvider.clearInstanceForTesting()
        FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name("habit_database")
            .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("Existing file required")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Rollback must retain19")
            }).build()).use { helper ->
            val sql = helper.writableDatabase
            assertEquals(19, sql.version); assertEquals(before.contents, snapshot(sql).contents)
        }
        val retried = openCurrent(); val after = retried.withTransaction { NextReplicaStorageProof(retried).readInTransaction() }
        assertEquals(before.contents, requireNotNull(NextReplicaV19StorageView.project(after)).contents)
    }
    @Test(timeout = 150_000) fun failureAfterRealFrameworkMigrationReopenRetainsOneRootAndRecoversOnNewOwner() = runBlocking<Unit> {
        val before = source(); val fault = CountCommandMigrationNativeFault(CountCommandMigrationNativeFault.Fault.AFTER_COMMITTED_REOPEN)
        room = fault.build(context)
        val failure = runCatching { requireNotNull(room).openHelper.writableDatabase }.exceptionOrNull()
        assertEquals(1, fault.hits); assertNotNull(failure)
        assertTrue(fault.observedOriginalFailure); assertTrue(fault.containsOriginalFailure(failure))
        assertTrue("Actual Framework retry must remain failed on this fault owner", fault.retryRejections >= 1)
        assertTrue(generateSequence(failure) { it.cause }.any { it.message == "FIXED_AFTER_COMMITTED_MIGRATION_REOPEN" })
        room?.close(); HabitDatabaseProvider.clearInstanceForTesting()
        val current = openCurrent(); val first = current.withTransaction { NextReplicaStorageProof(current).readInTransaction() }
        assertEquals(before.contents, requireNotNull(NextReplicaV19StorageView.project(first)).contents)
        val root = CountCommandNativeRows(current.openHelper.writableDatabase) {}.root()
        assertEquals(1L, root.revision); assertEquals("MIGRATION_19_20", root.initializationSource)
        val reopened = openCurrent(); val next = reopened.withTransaction { NextReplicaStorageProof(reopened).readInTransaction() }
        assertEquals(first.contents, next.contents)
        assertFalse(requireNotNull(CountTrustRuntime.forDatabase(reopened)).isRetired)
        // This is actual committed-upgrade/open-return loss; it is not a process-death claim.
    }

    @Test(timeout = 150_000) fun transientAfterCommittedReopenKeepsFrameworkRetryWithoutDuplicatingRootOrBusiness() = runBlocking<Unit> {
        val before = source()
        val fault = CountCommandMigrationNativeFault(CountCommandMigrationNativeFault.Fault.AFTER_COMMITTED_REOPEN_ONCE)
        val current = fault.build(context).also { room = it }
        assertTrue(current.openHelper.writableDatabase.isOpen)
        assertEquals(1, fault.hits); assertTrue(fault.observedOriginalFailure); assertEquals(0, fault.retryRejections)
        val after = current.withTransaction { NextReplicaStorageProof(current).readInTransaction() }
        assertEquals(before.contents, requireNotNull(NextReplicaV19StorageView.project(after)).contents)
        val sql = current.openHelper.writableDatabase
        val root = CountCommandNativeRows(sql) {}.root()
        assertEquals(1L, root.revision); assertEquals("MIGRATION_19_20", root.initializationSource)
        CountCommandStorageInitialization.verifyMandatory(sql)
        assertEquals(after.contents, current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents })
        val freshOwner = openCurrent()
        assertEquals(after.contents, freshOwner.withTransaction { NextReplicaStorageProof(freshOwner).readInTransaction().contents })
        assertFalse(requireNotNull(CountTrustRuntime.forDatabase(freshOwner)).isRetired)
        // The transient owner's SQL-open success does not reset any retired trust capability.
    }
}
