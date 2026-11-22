package com.dayforge.data.local

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual primary metadata and real KSP19 migration; no synthetic MAC/authority or T1. */
@RunWith(AndroidJUnit4::class)
class NextProtocolAdmissionCommandGenesisTest : CountCommandMigrationFixture() {
    private suspend fun proof(database: HabitDatabase) = database.withTransaction {
        NextReplicaStorageProof(database).readInTransaction()
    }
    private suspend fun rejected(database: HabitDatabase) {
        val before = proof(database)
        val failure = try {
            database.withTransaction { NextProtocolAdmission.requireRoundsOrEmpty(database, null) }; null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IllegalStateException) { error }
        catch (error: IllegalArgumentException) { error }
        assertEquals("SYNC_CONTROLLED_REBUILD_REQUIRED", failure?.message)
        assertEquals(before.contents, proof(database).contents)
    }

    @Test(timeout = 150_000) fun boundEmptyCatalogAndActualPreparedObligationAreNeverEmptyBookkeeping() = runBlocking<Unit> {
        val database = openCurrent(); val sql = database.openHelper.writableDatabase
        val store = CountCommandStore(database, tokens, sessions)
        store.bindEmptyNamespace()
        val bound = CountCommandStorageInitialization.verifyRootAnchor(sql)
        assertEquals(CountCommandRootCodec.Mode.ACCOUNT_BOUND, bound.header.mode)
        assertEquals(0L, bound.header.pending.count); assertEquals(0L, requireNotNull(bound.header.catalog).count)
        rejected(database)
        val access = access()
        val namespace = CountCommandRecords.Namespace(access.session.authentication.userId,
            access.session.serverInstanceId, access.session.syncEpoch, access.capturedDeviceId,
            bound.header.storageId, bound.header.datasetId)
        assertNull(database.completionDao().getCompletionById(Long.MAX_VALUE))
        val selection = buildJsonObject { put("requested_completion_id", Long.MAX_VALUE); put("present", false) }
        val body = buildJsonObject {
            put("format", 1); put("command_id", id(401)); put("namespace", namespace.body())
            put("original_auth_generation", access.session.authentication.generation)
            put("action", "COUNT_UNDO"); put("capture", "VERIFIED_NO_EFFECT")
            put("input", buildJsonObject {
                put("habit_uuid", id(402)); put("requested_completion_id", Long.MAX_VALUE)
                put("reason", "EXPLICIT_TARGET_ABSENT"); put("selector", "repository-explicit-id/v1")
                put("selection", selection)
                put("dependency_hash", CountCommandRecords.hash("no-effect-selection", CountCommandRecords.canonical(selection)))
            })
        }
        // T0 accepts an immutable ordinary input, not a T1 business/execution permission.
        // No business row is needed or inserted merely to manufacture this pending obligation.
        val bytes = CountCommandRecords.canonical(body).toByteArray(Charsets.UTF_8)
        val intent = CountCommandRecords.Intent.decode(bytes, bytes.size) {}
        val registered = store.register(intent)
        assertFalse(registered.alreadyRegistered); store.releaseUnadmitted(requireNotNull(registered.ticket))
        val pending = store.discover(1)
        assertTrue(pending.complete); assertNull(pending.continuation)
        assertEquals(intent.text(), pending.commands.single().historical.intent.text())
        assertEquals(CountCommandRecords.Execution.PREPARED, pending.commands.single().historical.status.execution)
        assertNull(pending.commands.single().historical.outcome)
        for (table in listOf("habits", "metrics", "completions", "sync_outbox", "timer_command_outbox", "next_sync_state"))
            sql.query("SELECT 1 FROM main.`$table` LIMIT 1").use { assertFalse(it.moveToFirst()) }
        rejected(database)
        assertEquals(intent.text(), store.resolve(registered.reference).historical.intent.text())
        assertEquals(1L, CountCommandStorageInitialization.verifyRootAnchor(sql).header.pending.count)
    }

    @Test(timeout = 150_000) fun actualOldEmptyGenesisAdmitsButAuthenticatedLegacyOutcomeNeverDoes() = runBlocking<Unit> {
        for (mode in listOf("empty", "legacy")) {
            room?.close(); HabitDatabaseProvider.clearInstanceForTesting(); context.deleteDatabase("habit_database")
            source(19) { old ->
                old.execSQL("DELETE FROM next_transmissions"); old.execSQL("DELETE FROM next_rejections")
                old.execSQL("UPDATE count_trust_control SET generation=generation+1,dirty=0 WHERE id=1")
                if (mode == "legacy") old.execSQL("INSERT INTO count_command_results VALUES('legacy','input','fact',NULL,'retained','committed')")
            }
            val database = openCurrent(); val sql = database.openHelper.writableDatabase
            val before = proof(database)
            val root = CountCommandStorageInitialization.verifyRootAnchor(sql)
            assertEquals(CountCommandRootCodec.Source.MIGRATION_19_20, root.header.source)
            assertEquals(1L, root.header.revision)
            val control = CountCommandRootAnchor.read(sql)
            assertTrue(control.generation > 0L); assertEquals(0L, control.dirty)
            if (mode == "legacy") {
                assertEquals(CountCommandRootCodec.Mode.LEGACY_UNENROLLED, root.header.mode)
                assertEquals(1L, root.header.legacyCount)
                rejected(database)
            } else {
                assertEquals(CountCommandRootCodec.Mode.EMPTY, root.header.mode)
                database.withTransaction { NextProtocolAdmission.requireRoundsOrEmpty(database, null) }
                assertTrue(CountCommandNativeRows.sameRoot(root.native(), CountCommandStorageInitialization.verifyRootAnchor(sql).native()))
                assertEquals(control, CountCommandRootAnchor.read(sql))
            }
            assertEquals(before.contents, proof(database).contents)
        }
    }

    @Test(timeout = 150_000) fun unknownOldBusinessTableRejectsUpgradeAndPreservesCompleteOldFile() = runBlocking<Unit> {
        val before = source(19) { old ->
            old.execSQL("DELETE FROM next_transmissions"); old.execSQL("DELETE FROM next_rejections")
            old.execSQL("UPDATE count_trust_control SET generation=generation+1,dirty=0 WHERE id=1")
            old.execSQL("CREATE TABLE future_business(value TEXT)")
            old.execSQL("INSERT INTO future_business VALUES('must remain visible to empty admission')")
        }
        assertEquals(19, before.contents.getValue("user_version").jsonPrimitive.int)
        val failure = runCatching { openCurrent() }.exceptionOrNull()
        assertNotNull("The real current provider must reject unknown old schema", failure)
        assertTrue(generateSequence(failure) { it.cause }.any { it.message == "COMMAND_MIGRATION_UNKNOWN_OLD_TABLE" })
        room?.close(); HabitDatabaseProvider.clearInstanceForTesting()
        // Inspect the retained old file through a version19 helper; never retry it as current20.
        FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("habit_database").callback(object : SupportSQLiteOpenHelper.Callback(19) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("Existing rejected source required")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    error("Rejected upgrade must retain19")
            }).build()).use { helper ->
            val sql = helper.writableDatabase
            assertEquals(19, sql.version)
            assertEquals(before.contents, snapshot(sql).contents)
            sql.query("SELECT value FROM main.future_business").use {
                assertTrue(it.moveToFirst())
                assertEquals("must remain visible to empty admission", it.getString(0))
                assertFalse(it.moveToNext())
            }
            sql.query("SELECT 1 FROM main.sqlite_master WHERE name='count_command_recovery_roots'").use {
                assertFalse(it.moveToFirst())
            }
        }
    }
}
