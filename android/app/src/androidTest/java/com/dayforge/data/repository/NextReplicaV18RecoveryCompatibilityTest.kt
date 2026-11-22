package com.dayforge.data.repository

import android.database.Cursor
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.appearance.AccountIconHttp
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Real on-disk 18 -> 19 -> 20 migration, immutable old frozen stage/archive, production continuation.
 * The v18 oracle is read from actual SQLite before migration, not the compatibility projector.
 */
@RunWith(AndroidJUnit4::class)
class NextReplicaV18RecoveryCompatibilityTest : NextReplicaDestinationFixture() {
    private val history = ReplicaPreparedHistoryRule(storage)
    @get:Rule(order = -3) val preparedHistory: TestRule = history
    private enum class PreparedKind { BOUND, CLOSED }
    private data class PreparedHistory(
        val kind: PreparedKind,
        val value: Committing,
        val stage: ReplicaSwitchStage,
        val requireSameOwner: () -> Unit
    )
    private val preparedInput = AtomicReference<PreparedHistory?>()

    @Before fun prepareExistingHistory() {
        if (!history.selected) return
        val kind = when (history.methodName) {
            "committedV18BoundaryMigratesAndCatchesUpWithoutRewritingOriginalProofs" -> PreparedKind.BOUND
            "closedV18ReleaseBoundaryMigratesResumesAndCompletesWithoutDroppingArchive" -> PreparedKind.CLOSED
            else -> error("UNDECLARED_REPLICA_PREPARED_HISTORY")
        }
        val input = history.prepare {
            timed(Stage.INITIALIZE) { initialize() }; val value = committing18()
            val bound = commit(value).let { committed -> timed(Stage.BIND_DESTINATION) { workflow().bindDestination(committed) } }
            assertEquals(ReplicaSwitchPhase.DESTINATION_BOUND, bound.phase)
            if (kind == PreparedKind.BOUND) freezeHistory(kind, value, bound) else {
                materialReply = { materialResponse(it, emptyList()) }
                val material = timed(Stage.VERIFY_MATERIALS) { workflow().verifyMaterials(bound, runtime(), AccountIconHttp(client, tokens, peer.origin)) }
                    as ReplicaMaterialVerification.Verified
                val releasing = timed(Stage.RECORD_RELEASE) { NextReplicaStageStore(db, tokens, sessions).recordRelease(bound, material.proof) }
                assertEquals(ReplicaSwitchPhase.RELEASING, releasing.phase)
                timed(Stage.CLOSE_FOR_RELEASE) { NextReplicaCommitStore(db, tokens, sessions).closeForRelease(releasing) }
                assertEquals(ReplicaReleaseFate.CLOSED, timed(Stage.RELEASE_FATE) { NextReplicaCommitStore(db, tokens, sessions).releaseFate(releasing) })
                freezeHistory(kind, value, releasing)
            }
        }
        check(history.actualPreparationOwner?.isCompleted == true)
        check(preparedInput.compareAndSet(null, input))
    }

    private fun freezeHistory(kind: PreparedKind, value: Committing, stage: ReplicaSwitchStage): PreparedHistory {
        val originalDb = db; val originalTokens = tokens; val originalDataStore = dataStore
        val originalPreferences = preferences; val originalScope = scope; val originalPeer = peer
        val originalClient = client; val originalDirectory = directory; val originalIconDb = iconDb
        // This immutable, once-consumed lease checks only the preparation/business handoff.
        // The original coldReopen later intentionally replaces DB/preferences/token/client.
        return PreparedHistory(kind, value.copy(bytes = value.bytes.copyOf()), stage) {
            check(db === originalDb && tokens === originalTokens && dataStore === originalDataStore)
            check(preferences === originalPreferences && scope === originalScope && peer === originalPeer)
            check(client === originalClient && directory === originalDirectory && iconDb === originalIconDb)
        }
    }

    private fun takeHistory(kind: PreparedKind): PreparedHistory = requireNotNull(preparedInput.getAndSet(null)).also {
        check(it.kind == kind)
        it.requireSameOwner()
    }

    private data class Legacy(val stage: ReplicaSwitchStage, val storage: ReplicaStorageProof, val schemaHash: String)

    private enum class Stage { INITIALIZE, FROZEN18, ARCHIVE, COMMITTING18, VERIFY_CONFIRMATION,
        RECORD_ARCHIVE, RECORD_COMMITTING, COMMIT_CHAIN, ACTUAL_COMMIT, FATE, RECORD_SQL_COMMITTED,
        BIND_DESTINATION, MIGRATE_BOUNDARY, CATCH_UP, VERIFY_MATERIALS, RECORD_RELEASE, CLOSE_FOR_RELEASE,
        RELEASE_FATE, FINISH, COLD_REOPEN, RESUME, CANCEL, ASSERT_FROZEN }
    private class StageFrame(val label: Stage, val start: Long, val parent: StageFrame?) {
        val deletes = AtomicInteger()
        val guardCreates = AtomicInteger()
        val foreignKeyQueries = AtomicInteger()
        val finalCaptureQueries = AtomicInteger()
        val awaitingFinalCapture = AtomicBoolean()
    }
    private val activeStage = AtomicReference<StageFrame?>()
    private val incompleteStage = AtomicReference<StageFrame?>()
    private val sqlWhitespace = Regex("\\s+")
    private fun stageLog(frame: StageFrame, boundary: String) {
        Log.i("ReplicaV18Stages", "label=${frame.label.name}_$boundary elapsed_ms=${SystemClock.elapsedRealtime() - frame.start}")
        Log.i("ReplicaV18Stages", "label=${frame.label.name}_${boundary}_SQL elapsed_ms=${SystemClock.elapsedRealtime() - frame.start} " +
            "replacement_delete_calls=${frame.deletes.get()} guard_create_calls=${frame.guardCreates.get()} " +
            "fk_query_calls=${frame.foreignKeyQueries.get()} post_fk_capture_queries=${frame.finalCaptureQueries.get()}")
    }
    private suspend fun <T> timed(label: Stage, block: suspend () -> T): T {
        val parent = activeStage.get()
        if (parent == null) incompleteStage.set(null)
        val frame = StageFrame(label, SystemClock.elapsedRealtime(), parent)
        activeStage.set(frame)
        Log.i("ReplicaV18Stages", "label=${label.name}_BEGIN elapsed_ms=0")
        var returned = false
        return try { block().also { returned = true } } finally {
            if (!returned) incompleteStage.compareAndSet(null, frame)
            stageLog(frame, if (returned) "RETURNED" else "UNWOUND")
            activeStage.compareAndSet(frame, frame.parent)
        }
    }
    @After fun reportLastStage() {
        // JUnit can interrupt the runBlocking test while a Room child is still unwinding.
        // Retain both the active stage and first unwound stage; never log exception messages,
        // SQL, bind arguments, credentials, identifiers or archive/input contents.
        try {
            activeStage.get()?.let { stageLog(it, "TEST_FINALLY_ACTIVE") }
            incompleteStage.get()?.let { stageLog(it, "TEST_FINALLY_INCOMPLETE") }
        } finally {
            history.beforeInheritedCleanup()
        }
    }
    // QueryCallback calls only, limited to the fixed SQL spellings emitted by these paths:
    // these are attempts, not committed effects, all reads or lock occupancy. Private native
    // maintenance and separate candidate databases are not counted. Zero/missing matches
    // must never be interpreted as proof that no SQL or validation work occurred.
    private fun mayObserveStageSql(sql: String): Boolean {
        val start = sql.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return false
        fun prefix(first: String, second: String): Boolean {
            if (!sql.regionMatches(start, first, 0, first.length, ignoreCase = true)) return false
            var next = start + first.length
            if (next == sql.length || !sql[next].isWhitespace()) return false
            while (next < sql.length && sql[next].isWhitespace()) next++
            return sql.regionMatches(next, second, 0, second.length, ignoreCase = true)
        }
        return when (sql[start]) {
            'D', 'd' -> prefix("DELETE", "FROM")
            'C', 'c' -> prefix("CREATE", "TRIGGER")
            'P', 'p' -> prefix("PRAGMA", "foreign_key_check")
            'S', 's' -> prefix("SELECT", "COALESCE")
            else -> false
        }
    }
    private fun observeStageSql(text: String) {
        val frame = activeStage.get() ?: return
        if (text.startsWith("delete from ") && text.removePrefix("delete from ").substringBefore(' ') in
            ReplicaProjection.TABLES + CountTrustSchema.LOCAL_TABLES) frame.deletes.incrementAndGet()
        if (text.startsWith("create trigger ")) {
            val name = text.removePrefix("create trigger ").removePrefix("if not exists ").substringBefore(' ')
            if (name in replacementGuards) frame.guardCreates.incrementAndGet()
        }
        if (text == "pragma foreign_key_check") {
            frame.foreignKeyQueries.incrementAndGet()
            if (frame.guardCreates.get() >= replacementGuards.size) frame.awaitingFinalCapture.set(true)
        }
        if (text == "select coalesce(length(cast(sql as blob)),0) from main.sqlite_master" &&
            frame.awaitingFinalCapture.compareAndSet(true, false)) frame.finalCaptureQueries.incrementAndGet()
    }

    private val cancelFinalProjection = AtomicBoolean()
    private val replacementStarted = AtomicBoolean()
    private val replacementForeignKeysChecked = AtomicBoolean()
    private val finalProjectionReached = AtomicBoolean()
    private val finalProjectionInTransaction = AtomicBoolean()
    private val finalProjectionOnStack = AtomicBoolean()
    private val finalLocalRowsCleared = AtomicBoolean()
    private val finalStorageIdentityChanged = AtomicBoolean()
    private val finalObservedEpoch = AtomicReference<String?>()
    private val originalStorageIdentity = AtomicReference<String?>()
    private val finalCancellationTarget = AtomicReference<Job?>()
    private val restoredGuards = ConcurrentHashMap.newKeySet<String>()
    private val replacementGuards = NextReplicaFenceSql.businessAndLocalDefinitions().keys
    private val finalCaptureObserver = object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            val diagnosticCandidate = activeStage.get() != null && mayObserveStageSql(sqlQuery)
            // Ordinary proof queries pay only the prefix check. While cancellation is armed,
            // retain the complete original observer path even for non-diagnostic SQL.
            if (!diagnosticCandidate && !cancelFinalProjection.get()) return
            val text = sqlQuery.trim().replace("`", "").lowercase(java.util.Locale.ROOT)
                .replace(sqlWhitespace, " ")
            if (diagnosticCandidate) observeStageSql(text)
            if (!cancelFinalProjection.get()) return
            if (text == "delete from habits") replacementStarted.set(true)
            if (!replacementStarted.get()) return
            if (text.startsWith("create trigger ")) {
                val name = text.removePrefix("create trigger ").removePrefix("if not exists ").substringBefore(' ')
                if (name in replacementGuards) restoredGuards.add(name)
            }
            if (text == "pragma foreign_key_check" && restoredGuards == replacementGuards)
                replacementForeignKeysChecked.set(true)
            if (replacementForeignKeysChecked.get() &&
                text == "select coalesce(length(cast(sql as blob)),0) from main.sqlite_master" &&
                cancelFinalProjection.compareAndSet(true, false)) {
                // The preceding FK query has returned. This is the first native query of
                // final public capture, after real replacement and guard restoration.
                val sql = db.openHelper.writableDatabase
                finalProjectionInTransaction.set(db.inTransaction())
                finalProjectionOnStack.set(Thread.currentThread().stackTrace.any {
                    it.className.startsWith("com.dayforge.data.repository.ReplicaProjection")
                })
                sql.query("SELECT syncEpoch FROM next_sync_state WHERE id=1").use {
                    if (it.moveToFirst()) finalObservedEpoch.set(it.getString(0))
                }
                finalLocalRowsCleared.set((CountTrustSchema.LOCAL_TABLES - "count_trust_control").all { table ->
                    sql.query("SELECT count(*) FROM `$table`").use { it.moveToFirst() && it.getLong(0) == 0L }
                })
                sql.query("SELECT storageId FROM count_trust_control WHERE id=1").use {
                    finalStorageIdentityChanged.set(it.moveToFirst() && it.getString(0) != originalStorageIdentity.get())
                }
                finalProjectionReached.set(true)
                // Cancellation reaches the original StorageProof.ensureActive path. No
                // observer exception or asynchronous callback propagation is assumed.
                requireNotNull(finalCancellationTarget.get()).cancel(CancellationException("V18_FINAL_CAPTURE_CANCELLED"))
            }
        }
    }
    init { storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, finalCaptureObserver) } }

    private fun reader() = NextReplicaArchiveInputReader(app, db, tokens, sessions, preferences, http,
        iconDb, icons, transfers, files, dataStore, themes, builtIns)
    private suspend fun storageProof() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
    private fun changed(proof: ReplicaStorageProof, contents: JsonObject) = ReplicaStorageProof(contents,
        syncPayloadHash(contents.toString())).also { assertNotEquals(proof.hash, it.hash) }
    private fun manifestHash(manifest: JsonObject, hash: String) =
        syncPayloadHash(JsonObject(manifest + ("business_hash" to JsonPrimitive(hash))).toString())

    private suspend fun frozen18(legacyRollbackWitness: Boolean = false): Legacy = timed(Stage.FROZEN18) {
        val original = access()
        val installation = tokens.registeredInstallationId()!!
        val candidate = NextReplicaCandidateValidator(app, tokens, sessions, preferences)
            .validate(original, installation, http.recoveryPreview(original, id(99), installation))
        val manifest = reader().inspect(original, storageProof().hash) { input, _ -> input.manifest }
        val template = tokens.replicaStageFixture().copy(newEpoch = candidate.newEpoch,
            candidateHash = candidate.contentHash, candidateWireHash = candidate.wireHash, candidateCursor = candidate.cursor)
        return@timed previousDatabase(beforeCurrentUpgrade =
            if (legacyRollbackWitness) ({ sql -> seedLegacyRollbackWitness(sql) }) else null) { sql ->
            val before = observed(sql)
            assertEquals(JsonPrimitive(18), before.contents.getValue("user_version"))
            assertTrue(before.contents.getValue("tables").jsonObject.getValue("next_replica_fence").jsonArray.isEmpty())
            val stage = template.copy(sourceHash = before.hash, inputHash = manifestHash(manifest, before.hash))
            tokens.persistReplicaStage(null, stage)
            val fence = stage.fence
            sql.execSQL("INSERT INTO next_replica_fence(recoveryId,accountId,serverInstanceId,oldEpoch,newEpoch," +
                "authenticationGeneration,deviceId,capabilityRevision,sourceHash,mode,id) VALUES(?,?,?,?,?,?,?,?,?,?,1)",
                arrayOf<Any?>(fence.recoveryId, fence.accountId, fence.serverInstanceId, fence.oldEpoch, fence.newEpoch,
                    fence.authenticationGeneration, fence.deviceId, fence.capabilityRevision, fence.sourceHash, fence.mode))
            val frozen = stage.transition(ReplicaSwitchPhase.FROZEN)
            tokens.persistReplicaStage(stage, frozen)
            Legacy(frozen, observed(sql), schemaHash(sql))
        }.also { legacy ->
            assertEquals(20, db.openHelper.writableDatabase.version)
            assertEquals(legacy.storage.contents, projected18(storageProof())!!.contents)
            assertEquals(legacy.stage, tokens.activeReplicaStage())
            assertNull(tokens.localCoreWriteAccess())
        }
    }

    /** A real historical KSP18 file, not a partially stripped current schema. */
    private suspend fun <T> previousDatabase(beforeCurrentUpgrade: (suspend (SupportSQLiteDatabase) -> Unit)? = null,
        block: suspend (SupportSQLiteDatabase) -> T): T =
        ReplicaV18SourceFixture.withDatabase(app, storage, beforeCurrentUpgrade, block)

    /** The original inactive five-table witness is legal historical19 input. Never insert it
     * underneath an already authenticated20 EMPTY root or fabricate a current Outcome. */
    private fun seedLegacyRollbackWitness(sql: SupportSQLiteDatabase) {
        assertEquals(19, sql.version)
        sql.query("SELECT count(*) FROM main.sqlite_master WHERE name='count_command_recovery_roots'").use {
            assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0))
        }
        sql.beginTransaction()
        try {
            CountTrustSchema.validate(sql, 19)
            val names = NextReplicaFenceSql.localTrustDefinitions().keys
            val guards = sql.query("SELECT name,sql FROM main.sqlite_master WHERE type='trigger' ORDER BY name").use { c ->
                buildMap { while (c.moveToNext()) if (c.getString(0) in names) put(c.getString(0), c.getString(1)) }
            }
            assertEquals(names, guards.keys)
            guards.keys.forEach { sql.execSQL("DROP TRIGGER `$it`") }
            sql.execSQL("INSERT INTO count_trust_root(id,binding,format,validator,schemaHash,rootHash,rootHeight,rootCount,revision,generation,mac) " +
                "VALUES(1,'inactive-recovery-fixture',1,'inactive-fixture',?,?,0,0,0,0,X'0102')", arrayOf("a".repeat(64), "b".repeat(64)))
            sql.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES(?,X'00017FFF')", arrayOf("c".repeat(64)))
            sql.execSQL("INSERT INTO count_trust_routes(sourceTable,sourceKey,ownerUuid,entityUuid,requestId,businessDate,role,payloadHash) " +
                "VALUES('metric_logs','rollback-member',NULL,NULL,NULL,NULL,'inactive',?)", arrayOf("d".repeat(64)))
            sql.execSQL("INSERT INTO count_command_results VALUES('rollback-command','original-input','original-fact',NULL,'original-result','committed')")
            guards.values.forEach(sql::execSQL)
            CountTrustSchema.LOCAL_TABLES.forEach { table -> sql.query("SELECT count(*) FROM main.`$table`").use {
                assertTrue(it.moveToFirst()); assertEquals("Real19 non-empty rollback witness: $table", 1L, it.getLong(0))
            } }
            sql.query("SELECT dirty FROM main.count_trust_control WHERE id=1").use {
                assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0))
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
    }

    private fun projected18(proof: ReplicaStorageProof): ReplicaStorageProof? =
        NextReplicaCompatibilityViews.projected(proof).singleOrNull {
            it.contents.getValue("user_version") == JsonPrimitive(18)
        }

    /** Independent typed SQLite observation of the SMALL fixture before the production migration. */
    private fun observed(sql: SupportSQLiteDatabase): ReplicaStorageProof {
        fun rows(query: String) = sql.query(query).use { c -> buildList {
            while (c.moveToNext()) add(buildJsonObject { for (column in 0 until c.columnCount) put(c.getColumnName(column), buildJsonObject {
                val type = c.getType(column); put("sqlite_type", type)
                put("value", when (type) {
                    Cursor.FIELD_TYPE_NULL -> JsonNull
                    Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(column))
                    Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(column).toRawBits().toString(16))
                    Cursor.FIELD_TYPE_STRING -> JsonPrimitive(c.getString(column))
                    Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(c.getBlob(column).joinToString("") { "%02x".format(it) })
                    else -> error("Invalid fixture cell")
                })
            }) })
        } }
        val names = sql.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        val contents = buildJsonObject {
            put("user_version", sql.version)
            put("schema", JsonArray(rows("SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name")))
            put("tables", buildJsonObject { for (name in names) put(name,
                JsonArray(rows("SELECT rowid AS __recovery_rowid,* FROM main.`$name` ORDER BY rowid"))) })
        }
        return ReplicaStorageProof(contents, syncPayloadHash(contents.toString()))
    }
    private suspend fun schemaHash(sql: SupportSQLiteDatabase) = syncPayloadHash(buildJsonObject {
        put("version", sql.version)
        put("objects", buildJsonObject { for ((key, item) in ReplicaSqlSchema.read(sql).toSortedMap()) put(key, buildJsonObject {
            put("type", item.type); put("name", item.name); put("owner", item.owner)
            put("definition", JsonArray(item.definition.map { JsonArray(it.map(::JsonPrimitive)) }))
        }) })
    }.toString())

    private suspend fun oldArchive(legacy: Legacy): ReplicaSwitchStage = timed(Stage.ARCHIVE) {
        val receipt = reader().inspect(legacy.stage.original, storageProof().hash) { input, borrowed ->
            val manifest = JsonObject(input.manifest + ("business_hash" to JsonPrimitive(legacy.storage.hash)))
            val old = ReplicaArchiveInput(input.business, legacy.storage, input.icons, input.iconFiles, input.themes,
                input.uses, input.missingAssets, manifest)
            assertTrue(NextReplicaSourceClosure.matchesArchiveInput(input, old.hash, legacy.storage.hash))
            ReplicaPrivateArchiveStore(directory).create(old, borrowed) {
                check(tokens.isCurrentSyncAuthority(legacy.stage.original))
            }
        }
        val saved = ReplicaStageArchive(receipt.archiveId, receipt.inputHash, receipt.byteLength, receipt.contentHash,
            receipt.entries.map { ReplicaStageArchiveEntry(it.name, it.byteLength, it.contentHash) })
        return@timed timed(Stage.RECORD_ARCHIVE) { NextReplicaStageStore(db, tokens, sessions).recordArchive(legacy.stage, saved) }
    }

    private data class Committing(val legacy: Legacy, val stage: ReplicaSwitchStage,
        val candidate: ValidatedReplicaCandidate, val bytes: ByteArray)

    /** A real v18 source/archive, full fresh confirmation evidence, then the real durable intent.
     * Later-phase fixtures use the production SQL/stage participants once; they do not repeat
     * the entire workflow confirmation at every boundary or manufacture a committed snapshot.
     */
    private suspend fun committing18(legacyRollbackWitness: Boolean = false): Committing = timed(Stage.COMMITTING18) {
        val old = frozen18(legacyRollbackWitness); val archived = oldArchive(old)
        val candidate = timed(Stage.VERIFY_CONFIRMATION) { workflow().verifyForConfirmation(archived) }.candidate
        val device = candidate.deviceProof
        val target = ReplicaStageTarget(candidate.projection.hash, old.schemaHash, device.deviceId, device.capabilityRevision,
            device.capabilities.sorted(), device.isPrimaryEditor, device.structuralEditEnabled)
        val committing = timed(Stage.RECORD_COMMITTING) { NextReplicaStageStore(db, tokens, sessions).recordCommitting(archived, target) }
        assertEquals(ReplicaCommitFate.OLD, timed(Stage.FATE) { NextReplicaCommitStore(db, tokens, sessions).fate(committing) })
        return@timed Committing(old, committing, candidate, archive(archived).readBytes())
    }

    private suspend fun commit(value: Committing): ReplicaSwitchStage = timed(Stage.COMMIT_CHAIN) {
        timed(Stage.ACTUAL_COMMIT) { NextReplicaCommitStore(db, tokens, sessions).commit(value.stage, value.candidate) }
        assertEquals(ReplicaCommitFate.COMMITTED, timed(Stage.FATE) { NextReplicaCommitStore(db, tokens, sessions).fate(value.stage) })
        return@timed timed(Stage.RECORD_SQL_COMMITTED) { NextReplicaStageStore(db, tokens, sessions).recordSqlCommitted(value.stage) }.also {
            assertEquals(ReplicaSwitchPhase.SQL_COMMITTED, it.phase)
            unchanged(value, it)
        }
    }

    private fun unchanged(value: Committing, stage: ReplicaSwitchStage) {
        assertEquals(value.stage.target, stage.target)
        assertEquals(value.stage.archive, stage.archive)
        assertEquals(value.legacy.stage.sourceHash, stage.sourceHash)
        assertEquals(value.legacy.stage.inputHash, stage.inputHash)
        assertArrayEquals(value.bytes, archive(stage).readBytes())
    }

    /** Observe the genuine old-version disk state at the later legal journal/SQL boundary,
     * then run the production migration. Every old typed row/DDL/rootpage must still match.
     */
    private suspend fun migrateBoundary(stage: ReplicaSwitchStage, oldSchemaHash: String): Unit = timed(Stage.MIGRATE_BOUNDARY) {
        val fence = db.nextReplicaFenceDao().state()
        val projection = ReplicaProjection.capture(db).hash
        val old = previousDatabase { sql ->
            assertEquals(18, sql.version)
            assertEquals(oldSchemaHash, schemaHash(sql))
            assertEquals(stage, tokens.activeReplicaStage())
            observed(sql)
        }
        assertEquals(20, db.openHelper.writableDatabase.version)
        assertEquals(old.contents, requireNotNull(projected18(storageProof())).contents)
        assertEquals(projection, ReplicaProjection.capture(db).hash)
        assertEquals(fence, db.nextReplicaFenceDao().state())
        assertEquals(stage, tokens.activeReplicaStage())
    }

    @Test fun frozenV18SourceSurvivesActualMigrationAndCanArchiveConfirmAndCancel() = recoveryRunBlocking<Unit> {
        timed(Stage.INITIALIZE) { initialize() }; val old = frozen18(); val flow = workflow()
        assertTrue(NextReplicaSourceClosure.matchesStorage(storageProof(), old.stage.fence, old.stage.sourceHash))
        val newIndices = setOf("index.index_count_trust_routes_owneruuid_businessdate",
            "index.index_count_trust_routes_entityuuid", "index.index_count_trust_routes_requestid")
        val currentObjects = db.withTransaction { ReplicaSqlSchema.read(db.openHelper.writableDatabase) }
        assertTrue("All three camel-case Room indices exist under canonical schema keys", currentObjects.keys.containsAll(newIndices))
        val oldNames = old.storage.contents.getValue("schema").jsonArray.map {
            it.jsonObject.getValue("name").jsonObject.getValue("value").jsonPrimitive.content.lowercase(java.util.Locale.ROOT)
        }.toSet()
        assertTrue(newIndices.none { it.removePrefix("index.") in oldNames })
        assertTrue(NextReplicaCompatibilityViews.matchesSchema(db, old.schemaHash))
        assertFalse(NextReplicaCompatibilityViews.matchesSchema(db, "0".repeat(64)))
        assertNotEquals(old.schemaHash, ReplicaProjection.schemaHash(db))
        val archived = timed(Stage.RESUME) { flow.resume(old.stage) }
        assertEquals(ReplicaSwitchPhase.ARCHIVE_VERIFIED, archived.phase)
        assertEquals(old.stage.sourceHash, archived.sourceHash); assertEquals(old.stage.inputHash, archived.inputHash)
        assertFalse(timed(Stage.VERIFY_CONFIRMATION) { flow.verifyForConfirmation(archived) }.activationAuthorized)
        timed(Stage.COLD_REOPEN) { coldReopen() }; assertEquals(archived, timed(Stage.RESUME) { workflow().resume(archived) })
        assertEquals(ReplicaSwitchPhase.ABORTED, timed(Stage.CANCEL) { workflow().cancel(archived) }.phase)
        assertNotNull(tokens.localCoreWriteAccess())
    }

    // The original long scenario is split at durable boundaries, with the same 150 s ceiling.
    // A: old archive + COMMITTING OLD/COMMITTED fate and cold continuation.
    // B: a genuinely committed old stage migrates, catches up, and cold-resumes.
    // C: a genuinely RELEASING/CLOSED old stage migrates, resumes, and completes publication.
    // All three retain source/input/target/archive identity and real phase-transition assertions.
    @Test fun retainedV18ArchiveAndCommittingTargetProveActualCommitAndColdFate() = recoveryRunBlocking<Unit> {
        timed(Stage.INITIALIZE) { initialize() }; val value = committing18()
        timed(Stage.COLD_REOPEN) { coldReopen() }; assertEquals(value.stage, timed(Stage.RESUME) { workflow().resume(value.stage) })
        val committed = commit(value)
        assertEquals(ReplicaCommitFate.COMMITTED, timed(Stage.FATE) { NextReplicaCommitStore(db, tokens, sessions).fate(committed) })
        timed(Stage.COLD_REOPEN) { coldReopen() }; assertEquals(committed, timed(Stage.RESUME) { workflow().resume(committed) })
        unchanged(value, committed)
    }

    @Test fun cancellationDuringFinalProjectionRollsBackBusinessGuardsAndAllLocalProofState() = recoveryRunBlocking<Unit> {
        timed(Stage.INITIALIZE) { initialize() }; val value = committing18(legacyRollbackWitness = true)
        assertEquals(value.stage.original, tokens.localSyncAccess())
        assertTrue(tokens.isCurrentSyncAuthority(value.stage.original))
        assertNull(tokens.localCoreWriteAccess()) // Real durable recovery fence remains active.
        val runtime = requireNotNull(CountTrustRuntime.forDatabase(db))
        // All five rows were committed in real19 before the first20 enrollment.
        // Verify the actual migration's authenticated legacy fingerprint, never re-sign it here.
        db.withTransaction { runtime.maintenance(db) {
            val sql = db.openHelper.writableDatabase
            CountCommandStorageInitialization.verifyMandatory(sql)
            val root = CountCommandStorageInitialization.verifyRootAnchor(sql).header
            assertEquals(CountCommandRootCodec.Mode.LEGACY_UNENROLLED, root.mode)
            assertEquals(CountCommandRootCodec.Source.MIGRATION_19_20, root.source)
            assertEquals(1L, root.legacyCount)
            sql.query("SELECT storageId,dirty FROM count_trust_control WHERE id=1").use {
                assertTrue(it.moveToFirst()); originalStorageIdentity.set(it.getString(0)); assertEquals(1L, it.getLong(1))
            }
        } }
        val before = db.withTransaction { observed(db.openHelper.writableDatabase) }
        val local = before.contents.getValue("tables").jsonObject
        CountTrustSchema.LOCAL_TABLES.forEach { assertEquals("Non-empty rollback witness: $it", 1, local.getValue(it).jsonArray.size) }
        assertEquals(ReplicaCommitFate.OLD, timed(Stage.FATE) { NextReplicaCommitStore(db, tokens, sessions).fate(value.stage) })
        val failure = try {
            cancelFinalProjection.set(true)
            supervisorScope {
                val committing = async(Dispatchers.IO) {
                    finalCancellationTarget.set(currentCoroutineContext()[Job])
                    timed(Stage.ACTUAL_COMMIT) { NextReplicaCommitStore(db, tokens, sessions).commit(value.stage, value.candidate) }
                }
                runCatching { committing.await() }.exceptionOrNull()
            }
        } finally { cancelFinalProjection.set(false); finalCancellationTarget.set(null) }
        assertTrue("Must reach actual final public projection, not an earlier preflight", finalProjectionReached.get())
        assertTrue(finalProjectionOnStack.get()); assertTrue(finalProjectionInTransaction.get())
        assertEquals(replacementGuards, restoredGuards)
        assertEquals(value.stage.newEpoch, finalObservedEpoch.get())
        assertTrue(finalLocalRowsCleared.get()); assertTrue(finalStorageIdentityChanged.get())
        assertTrue("Original cancellation must leave no committed replacement: $failure", failure is CancellationException)
        val after = db.withTransaction { observed(db.openHelper.writableDatabase) }
        assertEquals(before.contents, after.contents); assertEquals(before.hash, after.hash)
        assertEquals(value.stage, tokens.activeReplicaStage()); assertEquals(value.stage.fence, db.nextReplicaFenceDao().state())
        assertEquals(value.stage.original, tokens.localSyncAccess()); assertTrue(tokens.isCurrentSyncAuthority(value.stage.original))
        assertEquals(ReplicaCommitFate.OLD, timed(Stage.FATE) { NextReplicaCommitStore(db, tokens, sessions).fate(value.stage) })
        unchanged(value, value.stage)
        assertTrue("Conservative retirement must not be undone by SQL rollback", runtime.isRetired)
        timed(Stage.COLD_REOPEN) { coldReopen() }
        assertEquals(before.contents, db.withTransaction { observed(db.openHelper.writableDatabase) }.contents)
        assertEquals(ReplicaCommitFate.OLD, timed(Stage.FATE) { NextReplicaCommitStore(db, tokens, sessions).fate(value.stage) })
        assertEquals(value.stage, tokens.activeReplicaStage()); unchanged(value, value.stage)
    }

    @RetainedPreparedJourney
    @Test fun committedV18BoundaryMigratesAndCatchesUpWithoutRewritingOriginalProofs() = history.business {
        recoveryRunBlocking<Unit> {
            val input = takeHistory(PreparedKind.BOUND); val value = input.value; val bound = input.stage
            migrateBoundary(bound, value.legacy.schemaHash)
            changedDestination("After migration catchup", 21)
            val caughtUp = timed(Stage.CATCH_UP) { workflow().catchUp(bound) }
            assertEquals(ReplicaSwitchPhase.CATCHUP_BOUND, caughtUp.phase)
            assertEquals(value.legacy.schemaHash, caughtUp.publishedSnapshot.target.schemaHash)
            unchanged(value, caughtUp)
            assertEquals("After migration catchup", db.habitDao().getHabitByUuid(habit.uuid)!!.name)
            timed(Stage.COLD_REOPEN) { coldReopen() }; assertEquals(caughtUp, timed(Stage.RESUME) { workflow().resume(caughtUp) }); timed(Stage.ASSERT_FROZEN) { assertFrozen(caughtUp) }
        }
    }

    @RetainedPreparedJourney
    @Test fun closedV18ReleaseBoundaryMigratesResumesAndCompletesWithoutDroppingArchive() = history.business {
        recoveryRunBlocking<Unit> {
            val input = takeHistory(PreparedKind.CLOSED); val value = input.value; val releasing = input.stage
            migrateBoundary(releasing, value.legacy.schemaHash)
            timed(Stage.COLD_REOPEN) { coldReopen() }; assertEquals(releasing, timed(Stage.RESUME) { workflow().resume(releasing) })
            val outcome = timed(Stage.FINISH) { workflow().finishRecovery(releasing, runtime(), AccountIconHttp(client, tokens, peer.origin)) }
            assertTrue(outcome is ReplicaCompletionOutcome.Completed)
            val completed = (outcome as ReplicaCompletionOutcome.Completed).stage
            assertEquals(value.legacy.schemaHash, completed.publishedSnapshot.target.schemaHash)
            assertEquals(NextReplicaFenceEntity.CLOSED, db.nextReplicaFenceDao().state()!!.mode)
            assertFalse(tokens.replicaSwitchPending()); assertNotNull(tokens.localCoreWriteAccess())
            unchanged(value, completed)
        }
    }

    @Test fun legacyArchiveStillRequiresExactStoredBytesAndFreshAuthority() = recoveryRunBlocking<Unit> {
        timed(Stage.INITIALIZE) { initialize() }; val old = frozen18(); val archived = oldArchive(old)
        val file = archive(archived); val bytes = file.readBytes()
        val corrupt = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        file.writeBytes(corrupt)
        assertNotNull(rejected { timed(Stage.VERIFY_CONFIRMATION) { workflow().verifyForConfirmation(archived) } })
        assertEquals(archived, tokens.activeReplicaStage()); assertEquals(archived.fence, db.nextReplicaFenceDao().state())
        file.writeBytes(bytes)
        tokens.clearAuthenticationTokens()
        tokens.saveLoginSession("synthetic-relogin", "synthetic-refresh", "member", archived.accountId, false)
        assertNotNull(rejected { timed(Stage.VERIFY_CONFIRMATION) { workflow().verifyForConfirmation(archived) } })
        assertEquals(ReplicaSwitchPhase.ABORTED, timed(Stage.CANCEL) { workflow().cancel(archived) }.phase)
    }

    @Test fun changedOriginalRowsRootpagesAndMetadataCannotHideBehindAdditiveView() = recoveryRunBlocking<Unit> {
        timed(Stage.INITIALIZE) { initialize() }; val old = frozen18(); val proof = storageProof()
        val tables = proof.contents.getValue("tables").jsonObject
        val habits = tables.getValue("habits").jsonArray
        val first = habits.first().jsonObject
        val badName = buildJsonObject { put("sqlite_type", Cursor.FIELD_TYPE_STRING); put("value", "tampered") }
        val changedHabit = JsonArray(listOf(JsonObject(first + ("name" to badName))) + habits.drop(1))
        val badRows = changed(proof, JsonObject(proof.contents + ("tables" to JsonObject(tables + ("habits" to changedHabit)))))
        assertFalse(NextReplicaSourceClosure.matchesStorage(badRows, old.stage.fence, old.stage.sourceHash))
        val schema = proof.contents.getValue("schema").jsonArray
        val oldIndex = schema.indexOfFirst { it.jsonObject.getValue("name").jsonObject.getValue("value") == JsonPrimitive("habits") }
        val row = schema[oldIndex].jsonObject
        val rootpage = buildJsonObject { put("sqlite_type", Cursor.FIELD_TYPE_INTEGER); put("value", 99999) }
        val badSchema = JsonArray(schema.mapIndexed { index, value -> if (index == oldIndex) JsonObject(row + ("rootpage" to rootpage)) else value })
        assertFalse(NextReplicaSourceClosure.matchesStorage(changed(proof, JsonObject(proof.contents + ("schema" to badSchema))),
            old.stage.fence, old.stage.sourceHash))
        assertFalse(NextReplicaSourceClosure.matchesStorage(proof, old.stage.fence, "f".repeat(64)))
        assertNotNull(rejected { NextReplicaSourceClosure.matchesStorage(proof, old.stage.fence.copy(deviceId = id(808)), old.stage.sourceHash) })
    }

    @Test fun missingAlteredExtraOwnedSchemaWrongIdentityAndOtherVersionsNeverQualify() = recoveryRunBlocking<Unit> {
        timed(Stage.INITIALIZE) { initialize() }
        previousDatabase { assertEquals(18, it.version) }
        val current = storageProof()
        // The current public compatibility chain must contain both exact old versions.
        assertEquals(setOf(18, 19), NextReplicaCompatibilityViews.projected(current).map {
            it.contents.getValue("user_version").jsonPrimitive.int
        }.toSet())
        // Preserve the original single19->18 negative matrix on its actual supported input.
        val proof = requireNotNull(NextReplicaV19StorageView.project(current))
        assertEquals(JsonPrimitive(19), proof.contents.getValue("user_version"))
        val schema = proof.contents.getValue("schema").jsonArray
        assertNotNull(NextReplicaV18StorageView.projectContents(proof))
        fun name(row: JsonElement) = row.jsonObject.getValue("name").jsonObject.getValue("value").jsonPrimitive.content
        val owned = schema.first { name(it) == "count_trust_nodes" }.jsonObject
        val trigger = schema.first { name(it) == "count_trust_dirty_habits_update" }.jsonObject
        val brokenSql = JsonObject(trigger.getValue("sql").jsonObject + ("value" to JsonPrimitive("CREATE TRIGGER broken AFTER UPDATE ON habits BEGIN SELECT 1; END")))
        for (bad in listOf(
            JsonArray(schema.filterNot { name(it) == "count_trust_nodes" }),
            JsonArray(schema.map { if (it == trigger) JsonObject(trigger + ("sql" to brokenSql)) else it }),
            JsonArray(schema + JsonObject(owned + ("name" to JsonObject(owned.getValue("name").jsonObject + ("value" to JsonPrimitive("extra_local_object"))))))
        )) assertNotNull(rejected { NextReplicaV18StorageView.project(changed(proof, JsonObject(proof.contents + ("schema" to bad)))) })
        val tables = proof.contents.getValue("tables").jsonObject
        val master = tables.getValue("room_master_table").jsonArray.single().jsonObject
        val wrong = JsonObject(master + ("identity_hash" to JsonObject(master.getValue("identity_hash").jsonObject + ("value" to JsonPrimitive("unknown")))))
        assertNotNull(rejected { NextReplicaV18StorageView.project(changed(proof, JsonObject(proof.contents +
            ("tables" to JsonObject(tables + ("room_master_table" to JsonArray(listOf(wrong)))))))) })
        for (version in listOf(17, 18, 20)) assertNull(NextReplicaV18StorageView.project(changed(proof,
            JsonObject(proof.contents + ("user_version" to JsonPrimitive(version))))))
        val legacy = frozen18()
        previousDatabase(beforeCurrentUpgrade = { sql ->
            assertEquals(19, sql.version)
            val before = observed(sql)
            sql.execSQL("CREATE INDEX replica_compat_unowned_index ON habits(name)")
            try {
                val actual19 = observed(sql)
                val old18 = requireNotNull(NextReplicaV18StorageView.project(actual19))
                assertTrue(old18.contents.getValue("schema").jsonArray.any { name(it) == "replica_compat_unowned_index" })
                val retained = ReplicaSqlSchema.read(sql).filterKeys { it !in NextReplicaV18StorageView.removedSchemaKeys }
                assertTrue(retained.containsKey("index.replica_compat_unowned_index"))
                assertFalse("An unrelated added index must remain in the actual19->18 schema comparison",
                    ReplicaProjection.schemaHash(18, retained) == legacy.schemaHash)
                assertFalse(NextReplicaSourceClosure.matchesStorage(actual19, legacy.stage.fence, legacy.stage.sourceHash))
                assertEquals(actual19.contents, observed(sql).contents)
            } finally { sql.execSQL("DROP INDEX replica_compat_unowned_index") }
            assertEquals(before.contents, observed(sql).contents)
        }) { assertEquals(18, it.version) }
        assertEquals(20, db.openHelper.writableDatabase.version)
        db.openHelper.writableDatabase.execSQL("CREATE INDEX replica_compat_unowned_index ON habits(name)")
        db.openHelper.writableDatabase.query("SELECT sql FROM main.sqlite_master WHERE type='index' AND name='replica_compat_unowned_index'").use {
            assertTrue(it.moveToFirst()); assertTrue(it.getString(0).contains("habits(name)")); assertFalse(it.moveToNext())
        }
        val damaged20 = storageProof()
        val schemaFailure = rejected { NextReplicaCompatibilityViews.matchesSchema(db, legacy.schemaHash) }
        assertTrue(schemaFailure is IllegalArgumentException); assertEquals("COMMAND_PRIMARY_BINDING_CHANGED", schemaFailure.message)
        assertTrue(schemaFailure.stackTrace.any { it.className.startsWith("com.dayforge.data.repository.CountCommandSeal") })
        assertEquals(damaged20.contents, storageProof().contents)
        val storageFailure = rejected { NextReplicaSourceClosure.matchesStorage(storageProof(), legacy.stage.fence, legacy.stage.sourceHash) }
        assertTrue(storageFailure is IllegalArgumentException); assertEquals("COMMAND_PRIMARY_BINDING_CHANGED", storageFailure.message)
        assertTrue(storageFailure.stackTrace.any { it.className.startsWith("com.dayforge.data.repository.CountCommandSeal") })
        assertEquals(damaged20.contents, storageProof().contents)
    }

    @Test fun rawArchiveHashesMustAgreeOnOneVersionAndUnrelatedManifestBytesCannotChange() = recoveryRunBlocking<Unit> {
        timed(Stage.INITIALIZE) { initialize() }; val old = frozen18(); val proof = storageProof()
        val manifest = buildJsonObject { put("business_hash", proof.hash); put("account_id", old.stage.accountId); put("icon_database_hash", "a".repeat(64)) }
        val currentHash = syncPayloadHash(manifest.toString()); val oldHash = manifestHash(manifest, old.storage.hash)
        assertTrue(NextReplicaSourceClosure.matchesArchiveInput(manifest, proof, currentHash, proof.hash))
        assertTrue(NextReplicaSourceClosure.matchesArchiveInput(manifest, proof, oldHash, old.storage.hash))
        assertFalse(NextReplicaSourceClosure.matchesArchiveInput(manifest, proof, oldHash, proof.hash))
        assertFalse(NextReplicaSourceClosure.matchesArchiveInput(manifest, proof, currentHash, old.storage.hash))
        assertFalse(NextReplicaSourceClosure.matchesArchiveInput(JsonObject(manifest + ("icon_database_hash" to JsonPrimitive("b".repeat(64)))),
            proof, oldHash, old.storage.hash))
    }
}
