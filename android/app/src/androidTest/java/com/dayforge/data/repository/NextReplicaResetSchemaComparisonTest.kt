package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real historical schema, private clear/replace reset and actual SQL fate. Archive fields in
 * the small SQL fixture are contract-shaped sentinels, not file/archive verification; the
 * original complete V18 recovery9 remains the independent archive/catchup/release gate. */
@RunWith(AndroidJUnit4::class)
class NextReplicaResetSchemaComparisonTest : NextReplicaDestinationFixture() {
    private data class Old(val source: ReplicaStorageProof, val schema18: String, val schema19: String)
    private suspend fun raw() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
    private suspend fun previous(): Old {
        var schema19: String? = null
        val old = ReplicaV18SourceFixture.withDatabase(app, storage,
            beforeCurrentUpgrade = { sql -> schema19 = ReplicaProjection.schemaHash(19, ReplicaSqlSchema.read(sql)) }) { sql ->
            val captured = CountCommandMigrationFixture.snapshot(sql).contents
            ReplicaStorageProof(captured, syncPayloadHash(captured.toString())) to ReplicaProjection.schemaHash(18, ReplicaSqlSchema.read(sql))
        }
        assertEquals(20, db.openHelper.writableDatabase.version)
        assertTrue("Actual pre-migration18 image must be preserved completely", old.first.contents == NextReplicaCompatibilityViews.projected(raw()).single {
            it.contents.getValue("user_version") == JsonPrimitive(18)
        }.contents)
        return Old(old.first, old.second, requireNotNull(schema19))
    }
    private suspend fun reset(): Old {
        initialize(); val old = previous(); db.clearAllData()
        db.withTransaction {
            val root = CountCommandStorageInitialization.verifyRootAnchor(db.openHelper.writableDatabase)
            assertEquals(CountCommandRootCodec.Mode.EMPTY, root.header.mode)
            assertEquals(CountCommandRootCodec.Source.AUTHORIZED_CLEAR_REPLACE, root.header.source)
            assertEquals(1L, root.header.revision)
        }
        return old
    }
    private suspend fun fault(statement: String, args: Array<Any?> = emptyArray()) = db.withTransaction {
        requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) { db.openHelper.writableDatabase.execSQL(statement, args) }
    }
    private suspend fun rejectedUnchanged(block: suspend () -> Unit): Throwable {
        val before = raw().contents
        val error = try { block(); null } catch (cancel: CancellationException) { throw cancel }
            catch (failure: IllegalStateException) { failure } catch (failure: IllegalArgumentException) { failure }
        assertNotNull(error); assertTrue("Refusal must retain every actual native field", before == raw().contents)
        return requireNotNull(error)
    }

    @Test(timeout = 150_000) fun actualReplacementComparesRetainedTargetsButCannotManufactureAnOldStorageSource() = runBlocking<Unit> {
        initialize()
        val original = access(); val installation = requireNotNull(tokens.registeredInstallationId())
        val candidate = NextReplicaCandidateValidator(app, tokens, sessions, preferences)
            .validate(original, installation, http.recoveryPreview(original, id(99), installation))
        val old = previous()
        var stage = tokens.replicaStageFixture().copy(newEpoch = candidate.newEpoch, sourceHash = old.source.hash,
            candidateHash = candidate.contentHash, candidateWireHash = candidate.wireHash, candidateCursor = candidate.cursor)
        tokens.persistReplicaStage(null, stage); NextReplicaWriteFence(db).acquire(stage.fence)
        stage = tokens.persistReplicaStage(stage, stage.transition(ReplicaSwitchPhase.FROZEN))
        val archive = ReplicaStageArchive(id(930), "b".repeat(64), 3, "e".repeat(64),
            listOf("input.json", "business.json", "icons.json").map { ReplicaStageArchiveEntry(it, 1, "b".repeat(64)) })
        stage = tokens.persistReplicaStage(stage, stage.transition(ReplicaSwitchPhase.ARCHIVE_VERIFIED, receipt = archive))
        val device = candidate.deviceProof
        val target = ReplicaStageTarget(candidate.projection.hash, old.schema18, device.deviceId, device.capabilityRevision,
            device.capabilities.sorted(), device.isPrimaryEditor, device.structuralEditEnabled)
        stage = tokens.persistReplicaStage(stage, stage.transition(ReplicaSwitchPhase.COMMITTING, destination = target))
        val commits = NextReplicaCommitStore(db, tokens, sessions)
        assertEquals(ReplicaCommitFate.OLD, commits.fate(stage))
        commits.commit(stage, candidate) // Real full replacement, exact final guards and actual outer END.
        assertEquals(ReplicaCommitFate.COMMITTED, commits.fate(stage))
        val after = raw()
        val primary = db.withTransaction { CountCommandStorageInitialization.verifyRootAnchor(db.openHelper.writableDatabase) }
        assertEquals(CountCommandRootCodec.Source.AUTHORIZED_CLEAR_REPLACE, primary.header.source)
        assertEquals(CountCommandRootCodec.Mode.EMPTY, primary.header.mode); assertEquals(1L, primary.header.revision)
        assertTrue(NextReplicaCompatibilityViews.matchesSchema(db, old.schema18))
        assertTrue(NextReplicaCompatibilityViews.matchesSchema(db, old.schema19))
        assertTrue(NextReplicaCompatibilityViews.projected(after).isEmpty())
        assertFalse(NextReplicaSourceClosure.matchesStorage(after, stage.fence, stage.sourceHash))
        assertFalse(NextReplicaSourceClosure.matchesArchiveInput(buildJsonObject { put("business_hash", after.hash) }, after,
            "0".repeat(64), old.source.hash))
        assertEquals(target, stage.target); assertEquals(archive, stage.archive)
        assertEquals(candidate.projection.hash, ReplicaProjection.capture(db).hash)
        assertTrue(after.contents == raw().contents)
        stage = NextReplicaStageStore(db, tokens, sessions).recordSqlCommitted(stage)
        coldReopen()
        val cold = raw().contents
        assertEquals(ReplicaCommitFate.COMMITTED, NextReplicaCommitStore(db, tokens, sessions).fate(stage))
        assertEquals(stage, tokens.activeReplicaStage()); assertTrue(cold == raw().contents)
    }

    @Test(timeout = 150_000) fun invalidMacAndAnchorRemainHardErrorsWithoutRepairOrSchemaMiss() = runBlocking<Unit> {
        val old = reset()
        val root = db.withTransaction { CountCommandNativeRows(db.openHelper.writableDatabase) {}.root() }
        val control = db.withTransaction { CountCommandRootAnchor.read(db.openHelper.writableDatabase) }
        val bad = root.mac.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        fault("UPDATE count_command_recovery_roots SET mac=? WHERE id=1", arrayOf(bad))
        assertEquals("COMMAND_PRIMARY_MAC_INVALID", rejectedUnchanged {
            NextReplicaCompatibilityViews.matchesSchema(db, old.schema18)
        }.message)
        fault("UPDATE count_command_recovery_roots SET mac=? WHERE id=1", arrayOf(root.mac))
        fault("UPDATE count_trust_control SET commandRootHash=? WHERE id=1", arrayOf("0".repeat(64)))
        val error = rejectedUnchanged { NextReplicaCompatibilityViews.matchesSchema(db, old.schema18) }
        assertEquals("COMMAND_PRIMARY_ANCHOR_MISMATCH", error.message)
        fault("UPDATE count_trust_control SET commandRootHash=? WHERE id=1", arrayOf(control.hash))
        assertTrue(NextReplicaCompatibilityViews.matchesSchema(db, old.schema18))
    }

    @Test(timeout = 150_000) fun extraCommandMembersAndEnrolledRootCannotBorrowTheResetSchemaRoute() = runBlocking<Unit> {
        val old = reset(); val before = raw().contents
        val identifier = UUID.randomUUID().toString()
        val additions = listOf(
            "count_command_intents" to "INSERT INTO count_command_intents VALUES('$identifier','original-namespace','original-input',1,'COUNT_ADD','original-input')",
            "count_command_status" to "INSERT INTO count_command_status VALUES('$identifier','original-input',1,'PREPARED','NONE',NULL,NULL,NULL,NULL,'original-status',0)",
            "count_command_nodes" to "INSERT INTO count_command_nodes VALUES('${"a".repeat(64)}',X'0102')",
            "count_command_results" to "INSERT INTO count_command_results VALUES('$identifier','original-input','original-fact',NULL,'original-result','committed')"
        )
        for ((table, insert) in additions) {
            fault(insert)
            assertEquals("COMMAND_INITIAL_ANCHOR_CONTENT_CHANGED", rejectedUnchanged {
                NextReplicaCompatibilityViews.matchesSchema(db, old.schema18)
            }.message)
            fault("DELETE FROM `$table`")
            assertTrue("Only the fixed fault row was removed", before == raw().contents)
        }
        CountCommandStore(db, tokens, sessions).bindEmptyNamespace()
        val bound = raw(); assertTrue(NextReplicaCompatibilityViews.projected(bound).isEmpty())
        assertFalse(NextReplicaCompatibilityViews.matchesSchema(db, old.schema18))
        assertFalse(NextReplicaCompatibilityViews.matchesSchema(db, old.schema19))
        assertTrue(bound.contents == raw().contents)
    }

    @Test(timeout = 150_000) fun changedNativeRemovedIndexCannotHideBehindFilteringAndActualSchemaStillRejects() = runBlocking<Unit> {
        val old = reset()
        val nativeFault = RemovedIndexFault()
        storage.isolatedBuilder = { buildDiagnosticFaultDatabase(it, nativeFault) }
        storage.reopen()
        val before = raw()
        val initial = db.withTransaction { ReplicaSqlSchema.read(db.openHelper.writableDatabase) }
        assertTrue(NextReplicaResetSchemaComparison.matches(before, initial, old.schema18))
        nativeFault.replaceRouteIndex()
        assertEquals(1, nativeFault.changes)
        val changed = db.withTransaction { ReplicaSqlSchema.read(db.openHelper.writableDatabase) }
        assertNotEquals(initial.getValue("index.index_count_trust_routes_entityuuid"), changed.getValue("index.index_count_trust_routes_entityuuid"))
        // Independent actual old raw/new native-PRAGMA observations, not a fabricated map.
        // Production's controlled cookie/generation scope is an additional separate guard.
        assertFalse(NextReplicaResetSchemaComparison.matches(before, changed, old.schema18))
        assertEquals("COMMAND_PRIMARY_BINDING_CHANGED", rejectedUnchanged {
            NextReplicaCompatibilityViews.matchesSchema(db, old.schema18)
        }.message)
    }


    /** Fixed same-native corruption below the real controlled factory. No raw handle or
     * arbitrary SQL escapes; this is not a production-allowed DROP/CREATE operation. */
    private class RemovedIndexFault : SupportSQLiteOpenHelper.Factory {
        private var native: SupportSQLiteDatabase? = null
        var changes = 0; private set
        fun replaceRouteIndex() {
            val raw = requireNotNull(native)
            check(changes == 0 && !raw.inTransaction())
            raw.beginTransaction()
            try {
                raw.execSQL("DROP INDEX index_count_trust_routes_entityUuid")
                raw.execSQL("CREATE INDEX index_count_trust_routes_entityUuid ON count_trust_routes(entityUuid COLLATE NOCASE DESC)")
                raw.setTransactionSuccessful()
            } finally { raw.endTransaction() }
            changes++
        }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            check(configuration.context.packageName == "com.dayforge.testbed")
            fun remember(db: SupportSQLiteDatabase): SupportSQLiteDatabase { native = db; return db }
            val parent = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(remember(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(remember(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(remember(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(remember(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(remember(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(remember(db))
            }
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase get() = remember(helper.writableDatabase)
                override val readableDatabase get() = remember(helper.readableDatabase)
            }
        }
    }
}
