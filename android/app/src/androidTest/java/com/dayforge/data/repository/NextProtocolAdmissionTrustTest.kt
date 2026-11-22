package com.dayforge.data.repository

import android.database.Cursor
import android.os.CancellationSignal
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustOpenHelperFactory
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.SyncSchemaCallback
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.PhysicalDatabaseRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Empty-profile admission may recognize only the exact local invalidation control. */
@RunWith(AndroidJUnit4::class)
class NextProtocolAdmissionTrustTest {
    private val fault = NativeFaultFactory()
    @get:Rule val storage = PhysicalDatabaseRule().apply {
        isolatedBuilder = { context ->
            val factory = CountTrustOpenHelperFactory(context, fault)
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .openHelperFactory(factory).addCallback(SyncSchemaCallback).build()
                .also { CountTrustRuntime.attach(it, factory) }
        }
    }
    private val db get() = storage.database
    private val sql get() = db.openHelper.writableDatabase
    private val owner get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    private class Rollback : RuntimeException()
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().hash }

    @Test fun freshAndReopenedEmptyDatabaseAdmitsWithoutCreatingAProofOrChangingAnyBytes() = runBlocking<Unit> {
        repeat(2) { attempt ->
            if (attempt > 0) storage.reopen()
            val before = image()
            db.withTransaction { NextProtocolAdmission.requireRoundsOrEmpty(db, null) }
            assertEquals(before, image())
            assertFalse(owner.isRetired)
            for (table in CountTrustSchema.LOCAL_TABLES - "count_trust_control")
                sql.query("SELECT 1 FROM `$table` LIMIT 1").use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun clearedRetiredOwnerCanAdmitAnEmptyProfileWithoutRegainingFastPathAuthority() = runBlocking<Unit> {
        db.clearAllData()
        val sameOwner = owner
        assertTrue(sameOwner.isRetired)
        val before = image()
        db.withTransaction { NextProtocolAdmission.requireRoundsOrEmpty(db, null) }
        assertEquals(before, image())
        assertSame(sameOwner, owner)
        assertTrue(owner.isRetired)
        assertNotNull(runCatching { owner.requireUsable() }.exceptionOrNull())
    }

    @Test fun everyLocalCacheAndCommandRowAndOrphanOriginalStillPreventsEmptyAdmission() = runBlocking<Unit> {
        val inserts = listOf(
            "INSERT INTO count_trust_nodes VALUES('orphan',X'01')",
            "INSERT INTO count_trust_routes VALUES('source','key',NULL,NULL,NULL,NULL,'role','hash')",
            "INSERT INTO count_command_results VALUES('command','input','fact',NULL,'result','committed')",
            "INSERT INTO count_trust_root VALUES(1,'binding',1,'validator','schema','root',0,0,0,0,X'01')",
            "INSERT INTO next_request_origins VALUES('sync_operation','orphan',1,5,'account',NULL,NULL,'hash','{}')"
        )
        for (statement in inserts) rejectedMutation(statement, "SYNC_CONTROLLED_REBUILD_REQUIRED")
    }

    @Test fun malformedControlOrMissingDirtyTriggerCannotBeExemptedAsBookkeeping() = runBlocking<Unit> {
        val mutations = listOf(
            "DELETE FROM count_trust_control",
            "UPDATE count_trust_control SET storageId='invalid'",
            "UPDATE count_trust_control SET generation=1.5",
            "UPDATE count_trust_control SET dirty=X'01'",
            "INSERT INTO count_trust_control SELECT 2,storageId,generation,dirty,commandRootHash FROM count_trust_control",
            "DROP TRIGGER `${CountTrustSchema.dirtyTriggers.keys.first()}`"
        )
        for (statement in mutations) rejectedMutation(statement, null)
    }

    @Test fun validControlDoesNotPermitSuppressedOutboxOnOtherwiseEmptyDatabase() = runBlocking<Unit> {
        rejectedMutation("UPDATE sync_control SET suppressOutbox=1 WHERE id=1", null)
    }

    @Test fun missingOrMalformedPrimaryRootAndAnchorCannotBecomeBookkeeping() = runBlocking<Unit> {
        for (statement in listOf(
            "DELETE FROM count_command_recovery_roots",
            "UPDATE count_command_recovery_roots SET mac=zeroblob(32)",
            "UPDATE count_command_recovery_roots SET revision=revision+1",
            "UPDATE count_command_recovery_roots SET datasetId='invalid'",
            "UPDATE count_trust_control SET commandRootHash=NULL",
            "UPDATE count_trust_control SET commandRootHash='${"0".repeat(64)}'",
            "UPDATE count_trust_control SET commandRootHash=X'01'"
        )) rejectedMutation(statement, null)
    }

    @Test fun everyNewPrimaryMemberAndUnboundNodeStillPreventsEmptyAdmission() = runBlocking<Unit> {
        for (statement in listOf(
            "INSERT INTO count_command_intents VALUES('orphan','namespace','input',1,'COUNT_ADD','{}')",
            "INSERT INTO count_command_status VALUES('orphan','input',1,'PREPARED','PENDING_RESOLUTION',NULL,NULL,NULL,NULL,'{}',0)",
            "INSERT INTO count_command_nodes VALUES('unbound',X'01')"
        )) rejectedMutation(statement, "SYNC_CONTROLLED_REBUILD_REQUIRED")
    }

    @Test fun rootAnchorAndBusinessControlCannotChangeAfterGenesisWasClassified() = runBlocking<Unit> {
        for (change in NativeFaultFactory.Late.entries) {
            val before = image(); val rollback = Rollback()
            val outcome = runCatching { db.withTransaction {
                fault.arm(change)
                val rejected = runCatching { NextProtocolAdmission.requireRoundsOrEmpty(db, null) }.exceptionOrNull()
                assertEquals(1, fault.lateHits)
                assertNotNull("Late native change must be rejected", rejected)
                val expected = when (change) {
                    NativeFaultFactory.Late.ROOT -> "COMMAND_PRIMARY_MAC_INVALID"
                    NativeFaultFactory.Late.ANCHOR -> "COMMAND_PRIMARY_ANCHOR_MISMATCH"
                    NativeFaultFactory.Late.CONTROL -> "SYNC_CONTROLLED_REBUILD_REQUIRED"
                }
                assertEquals(expected, rejected?.message)
                throw rollback
            } }.exceptionOrNull()
            assertSame(rollback, outcome)
            assertEquals(before, image())
        }
    }

    private suspend fun rejectedMutation(statement: String, expectedMessage: String?) {
        val before = image()
        val rollback = Rollback()
        val outcome = runCatching {
            db.withTransaction {
                if (statement.startsWith("DROP TRIGGER ")) {
                    // The public controlled path must still reject DDL, even in maintenance.
                    val denied = runCatching { owner.maintenance(db) { sql.execSQL(statement) } }.exceptionOrNull()
                    assertEquals("COUNT_TRUST_SCHEMA_DDL_DENIED", denied?.message)
                    // Only this negative fixture's private native delegate installs corruption
                    // inside the SAME actual Room transaction; rollback must restore its DDL.
                    val native = requireNotNull(fault.database)
                    assertTrue(native.inTransaction())
                    native.execSQL(statement)
                    native.query("SELECT name FROM main.sqlite_master WHERE type='trigger' AND name=?",
                        arrayOf(CountTrustSchema.dirtyTriggers.keys.first())).use { assertFalse(it.moveToFirst()) }
                } else owner.maintenance(db) { sql.execSQL(statement) }
                // The actual mutation must succeed; failure during setup is not the rejection.
                val failure = runCatching { NextProtocolAdmission.requireRoundsOrEmpty(db, null) }.exceptionOrNull()
                assertNotNull("Admission must reject the actually installed row/schema change", failure)
                if (expectedMessage != null) assertEquals(expectedMessage, failure!!.message)
                else assertTrue("Expected invariant refusal, got $failure", failure is IllegalArgumentException || failure is IllegalStateException)
                throw rollback
            }
        }.exceptionOrNull()
        assertSame("Mutation, assertion or cleanup errors must not impersonate the expected rollback", rollback, outcome)
        assertEquals(before, image())
    }

    /** This test-owned delegate exposes no handle through production code. */
    private class NativeFaultFactory : SupportSQLiteOpenHelper.Factory {
        enum class Late { ROOT, ANCHOR, CONTROL }
        var database: SupportSQLiteDatabase? = null; private set
        var lateHits = 0; private set
        private var late: Late? = null
        fun arm(value: Late) { check(late == null); late = value; lateHits = 0 }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null; var retained: SupportSQLiteDatabase? = null
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
                database = raw
                if (original !== raw) {
                    original = raw
                    retained = object : SupportSQLiteDatabase by raw {
                        override fun query(query: String): Cursor = query(SimpleSQLiteQuery(query))
                        override fun query(query: String, bindArgs: Array<out Any?>): Cursor = query(SimpleSQLiteQuery(query, bindArgs))
                        override fun query(query: SupportSQLiteQuery): Cursor = query(query, null)
                        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
                            val cursor = if (cancellationSignal == null) raw.query(query) else raw.query(query, cancellationSignal)
                            try {
                            val text = query.sql.lowercase(java.util.Locale.ROOT).replace("`", "").replace("\"", "")
                            if (text == "select 1 from main.count_command_nodes limit 1") late?.let { selected ->
                                check(raw.inTransaction()); late = null
                                raw.execSQL(when (selected) {
                                    Late.ROOT -> "UPDATE count_command_recovery_roots SET revision=revision+1 WHERE id=1"
                                    Late.ANCHOR -> "UPDATE count_trust_control SET commandRootHash='${"0".repeat(64)}' WHERE id=1"
                                    Late.CONTROL -> "UPDATE count_trust_control SET generation=generation+1,dirty=1 WHERE id=1"
                                })
                                raw.query("SELECT changes()").use { check(it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext()) }
                                lateHits++
                            }
                            return cursor
                            } catch (failure: Throwable) { cursor.close(); throw failure }
                        }
                    }
                }
                return requireNotNull(retained)
            }
            val parent = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
            }
            val delegate = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by delegate {
                override val writableDatabase get() = wrap(delegate.writableDatabase)
                override val readableDatabase get() = wrap(delegate.readableDatabase)
            }
        }
    }
}
