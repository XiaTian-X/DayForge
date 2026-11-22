package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.data.local.entity.NextSyncStateEntity
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Small SQL-fate integration, with real authenticated preferences and durable stage CAS.
 * Archive receipts are contract-shaped sentinels, not archive/file validation. The original
 * complete v18 recovery class remains the independent full workflow/actual replacement gate.
 */
@RunWith(AndroidJUnit4::class)
class NextReplicaFateSnapshotTest : NextCoreRequestFixture() {
    private enum class Fault { NONE, WRITE, CANCEL, AUTH }
    private class Frame(val fault: Fault = Fault.NONE) {
        val raw = AtomicInteger()
        val cookies = AtomicInteger()
        val reached = AtomicBoolean()
        val nativeTransaction = AtomicBoolean()
        val mutationFailure = AtomicReference<Throwable?>()
    }
    private val active = AtomicReference<Frame?>()
    private val cancelTarget = AtomicReference<Job?>()
    private val failDecrypt = AtomicBoolean()
    private val failedDecrypts = AtomicInteger()
    override fun tokenCipher(): TokenCipher = object : TokenCipher {
        override fun encrypt(value: String) = PlaintextTokenCipher.encrypt(value)
        override fun decrypt(value: String): String? = if (failDecrypt.get()) {
            failedDecrypts.incrementAndGet(); null
        } else PlaintextTokenCipher.decrypt(value)
    }
    private val observer = object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            val frame = active.get() ?: return
            if (sqlQuery == "SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name")
                frame.raw.incrementAndGet()
            if (sqlQuery != "PRAGMA main.schema_version" || frame.cookies.incrementAndGet() != 2 ||
                frame.fault == Fault.NONE || !frame.reached.compareAndSet(false, true)) return
            frame.nativeTransaction.set(db.inTransaction())
            when (frame.fault) {
                Fault.WRITE -> frame.mutationFailure.set(runCatching {
                    db.openHelper.writableDatabase.execSQL("UPDATE metrics SET name='forbidden-snapshot-write' WHERE id=?", arrayOf(metric.id))
                }.exceptionOrNull())
                Fault.CANCEL -> requireNotNull(cancelTarget.get()).cancel()
                Fault.AUTH -> failDecrypt.set(true)
                Fault.NONE -> Unit
            }
        }
    }
    init { storage.isolatedBuilder = { HabitDatabaseProvider.build(it, observer) } }

    private suspend fun raw() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
    private suspend fun hash() = db.withTransaction { observedSchemaHash(ReplicaSqlSchema.read(db.openHelper.writableDatabase), db.openHelper.writableDatabase.version) }
    private fun observedSchemaHash(objects: Map<String, ReplicaSqlSchema.SchemaObject>, version: Int): String =
        syncPayloadHash(buildJsonObject {
            put("version", version)
            put("objects", buildJsonObject {
                for ((key, item) in objects.toSortedMap()) put(key, buildJsonObject {
                    put("type", item.type); put("name", item.name); put("owner", item.owner)
                    put("definition", JsonArray(item.definition.map { JsonArray(it.map(::JsonPrimitive)) }))
                })
            })
        }.toString())

    /** The existing complete source fixture creates a real KSP18 file and copies every
     * original18 native row/rowid. It never leaves20 command objects under a fake18 version.
     * Observe the real old schema, then use the original controlled18->19->20 builder. */
    private suspend fun legacyHash(): String {
        val original = ReplicaV18SourceFixture.withDatabase(app, storage) { sql ->
            assertEquals(18, sql.version)
            observedSchemaHash(ReplicaSqlSchema.read(sql), sql.version)
        }
        assertEquals(20, db.openHelper.writableDatabase.version)
        db.withTransaction {
            val root = CountCommandStorageInitialization.verifyRootAnchor(db.openHelper.writableDatabase)
            assertEquals(CountCommandRootCodec.Mode.EMPTY, root.header.mode)
            assertEquals(CountCommandRootCodec.Source.MIGRATION_19_20, root.header.source)
            assertEquals(1L, root.header.revision)
        }
        return original
    }

    private suspend fun stage(schema: String, committed: Boolean = true): ReplicaSwitchStage {
        register()
        var value = tokens.replicaStageFixture()
        db.nextSyncStateDao().insert(NextSyncStateEntity(value.accountId, value.serverInstanceId,
            if (committed) value.newEpoch else value.oldEpoch, value.deviceId, 1, value.candidateCursor,
            value.candidateHash, value.candidateHash, challengeContract = 1))
        val image = raw()
        value = value.copy(sourceHash = NextReplicaSourceClosure.storage(image, null))
        val target = ReplicaStageTarget(ReplicaProjection.capture(db).hash, schema, value.deviceId,
            value.capabilityRevision, value.capabilities, true, false)
        tokens.persistReplicaStage(null, value)
        NextReplicaWriteFence(db).acquire(value.fence)
        suspend fun advance(next: ReplicaSwitchStage) { value = tokens.persistReplicaStage(value, next) }
        advance(value.transition(ReplicaSwitchPhase.FROZEN))
        val receipt = ReplicaStageArchive(id(930), "b".repeat(64), 3, "e".repeat(64),
            listOf("input.json", "business.json", "icons.json").map { ReplicaStageArchiveEntry(it, 1, "b".repeat(64)) })
        advance(value.transition(ReplicaSwitchPhase.ARCHIVE_VERIFIED, receipt = receipt))
        advance(value.transition(ReplicaSwitchPhase.COMMITTING, destination = target))
        assertEquals(value.original, tokens.localSyncAccess())
        assertTrue(tokens.isCurrentSyncAuthority(value.original))
        return value
    }
    private suspend fun bound(committing: ReplicaSwitchStage): ReplicaSwitchStage {
        val committed = tokens.persistReplicaStage(committing, committing.transition(ReplicaSwitchPhase.SQL_COMMITTED))
        return tokens.persistReplicaStage(committed, committed.transition(ReplicaSwitchPhase.DESTINATION_BOUND)).also {
            assertEquals(it.destination(requireNotNull(tokens.authenticationSnapshot()).session), tokens.replicaDestinationAccess(it))
        }
    }
    private suspend fun releasing(committing: ReplicaSwitchStage): ReplicaSwitchStage {
        val ready = bound(committing)
        return tokens.persistReplicaStage(ready, ready.transition(ReplicaSwitchPhase.RELEASING,
            releasing = ReplicaStageRelease(ready.generation, "f".repeat(64), ready.authenticationGeneration)))
    }

    /** Original full-read oracle: never receives or calls the new snapshot/memo API. */
    private suspend fun oldSchema(expected: String): Boolean {
        val sql = db.openHelper.writableDatabase
        if (observedSchemaHash(ReplicaSqlSchema.read(sql), sql.version) == expected) return true
        val actual = NextReplicaStorageProof(db).readInTransaction()
        val version = actual.contents.getValue("user_version").jsonPrimitive.int
        // Preserve the original independent complete native proof. Extend its strict
        // migration-only validation through the reviewed20->19 edge before the old19->18
        // edge; never use the new snapshot/memo or reset-schema comparison under test.
        val previous19 = when (version) {
            20 -> NextReplicaV19StorageView.project(actual) ?: return false
            19 -> actual
            else -> return false
        }
        if (NextReplicaV18StorageView.projectContents(previous19) == null) return false
        val additions = CountTrustSchema.ownedDefinitions() + NextReplicaFenceSql.localTrustDefinitions().mapValues { "trigger" to it.value }
        val removed = additions.map { (name, value) -> "${value.first}.${name.lowercase(Locale.ROOT)}" }.toSet() +
            setOf("index.sqlite_autoindex_count_trust_nodes_1", "index.sqlite_autoindex_count_trust_routes_1", "index.sqlite_autoindex_count_command_results_1") +
            if (version == 20) CountCommandV20Schema.definitions.map { (name, value) -> "${value.type}.${name.lowercase(Locale.ROOT)}" }.toSet()
            else emptySet()
        // A second actual parsed schema/index-PRAGMA read remains independent, exactly as
        // in the old full-read oracle. The target is18, so control/table/guard are removed,
        // not normalized or re-signed. All original full-image/fate/read-count asserts stay.
        return observedSchemaHash(ReplicaSqlSchema.read(sql).filterKeys { it !in removed }, 18) == expected
    }
    private suspend fun oldMatches(stage: ReplicaSwitchStage, snapshot: ReplicaStageSnapshot): Boolean {
        val state = db.nextSyncStateDao().rows().singleOrNull() ?: return false
        return state.accountId == stage.accountId && state.serverInstanceId == stage.serverInstanceId &&
            state.syncEpoch == stage.newEpoch && state.deviceId == snapshot.target.deviceId && state.cursor == snapshot.cursor &&
            state.challengeContract == 1 && state.bootstrapHash == snapshot.contentHash && state.batchHash == snapshot.contentHash &&
            ReplicaProjection.capture(db).hash == snapshot.target.projectionHash
    }
    private suspend fun oldFate(stage: ReplicaSwitchStage): ReplicaCommitFate = sessions.exclusive { db.withTransaction {
        val fence = ReplicaPresentationRepository(db, tokens, sessions).readFenceInTransaction()
        check(fence == stage.fence)
        require(oldSchema(stage.publishedSnapshot.target.schemaHash))
        if (oldMatches(stage, stage.publishedSnapshot)) ReplicaCommitFate.COMMITTED else {
            require(stage.phase != ReplicaSwitchPhase.CATCHUP_BOUND)
            val state = db.nextSyncStateDao().rows().singleOrNull()
            require(NextReplicaSourceClosure.matchesStorage(NextReplicaStorageProof(db).readInTransaction(), fence, stage.sourceHash) &&
                state != null && state.accountId == stage.accountId && state.serverInstanceId == stage.serverInstanceId && state.syncEpoch == stage.oldEpoch)
            ReplicaCommitFate.OLD
        }
    } }
    private suspend fun oldRelease(stage: ReplicaSwitchStage): ReplicaReleaseFate = sessions.exclusive { db.withTransaction {
        val actual = ReplicaPresentationRepository(db, tokens, sessions).readFenceInTransaction()
        check(tokens.activeReplicaStage() == stage &&
            (actual == stage.fence || actual == stage.fence.copy(mode = NextReplicaFenceEntity.CLOSED)) &&
            oldSchema(stage.publishedSnapshot.target.schemaHash) && oldMatches(stage, stage.publishedSnapshot))
        tokens.replicaDestinationAccess(stage)
        if (actual == stage.fence) ReplicaReleaseFate.FROZEN else ReplicaReleaseFate.CLOSED
    } }
    private suspend fun oldCatchup(stage: ReplicaSwitchStage): ReplicaCatchupFate = sessions.exclusive { db.withTransaction {
        val control = ReplicaPresentationRepository(db, tokens, sessions).inspectWhileAccountLocked()
        check(control.problem == ReplicaPresentationProblem.RECOVERY_PENDING && control.phase == stage.phase &&
            tokens.activeReplicaStage() == stage && db.nextReplicaFenceDao().state() == stage.fence)
        val intent = requireNotNull(stage.catchup)
        require(oldSchema(intent.previous.target.schemaHash))
        when {
            oldMatches(stage, intent.next) -> ReplicaCatchupFate.COMMITTED
            oldMatches(stage, intent.previous) -> ReplicaCatchupFate.PREVIOUS
            else -> error("No complete old fate")
        }
    } }
    private suspend fun <T> observed(frame: Frame = Frame(), block: suspend () -> T): Pair<T, Frame> {
        assertTrue(active.compareAndSet(null, frame))
        return try { block() to frame } finally { active.set(null) }
    }
    private fun store() = NextReplicaCommitStore(db, tokens, sessions)

    @Test fun currentSchemaFateKeepsOneCompleteReadWithoutAnEagerLegacyProof() = runBlocking<Unit> {
        val value = stage(hash())
        assertEquals(ReplicaCommitFate.COMMITTED, oldFate(value))
        val before = raw()
        val (result, frame) = observed { store().fate(value) }
        assertEquals(ReplicaCommitFate.COMMITTED, result); assertEquals(1, frame.raw.get())
        assertEquals(before.contents, raw().contents); assertEquals(value, tokens.activeReplicaStage())
    }

    @Test fun migratedOldFateAgreesWithFullOracleAndCapturesOnlyOnce() = runBlocking<Unit> {
        val value = stage(legacyHash(), committed = false)
        val (original, oldFrame) = observed { oldFate(value) }
        val (candidate, frame) = observed { store().fate(value) }
        assertEquals(ReplicaCommitFate.OLD, original); assertEquals(original, candidate)
        assertEquals(2, oldFrame.raw.get()); assertEquals(1, frame.raw.get())
        // An existing soft retirement is permitted; it does not waive content/fate checks.
        db.withTransaction { CountTrustRuntime.maintenance(db) { requireNotNull(CountTrustRuntime.forDatabase(db)).retireFastPathForRecovery() } }
        assertEquals(original, observed { store().fate(value) }.first)
        assertTrue(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
    }

    @Test fun migratedReleaseRechecksAfterTheRealFenceUpdateInTheSameTransaction() = runBlocking<Unit> {
        val value = stage(legacyHash()); assertEquals(ReplicaCommitFate.COMMITTED, oldFate(value))
        val (committed, initialFrame) = observed { store().fate(value) }
        assertEquals(ReplicaCommitFate.COMMITTED, committed); assertEquals(1, initialFrame.raw.get())
        val release = releasing(value)
        assertEquals(ReplicaReleaseFate.FROZEN, oldRelease(release))
        val (_, closeFrame) = observed { store().closeForRelease(release) }
        assertEquals("Each side of the actual fence update must capture anew", 2, closeFrame.raw.get())
        assertEquals(release.fence.copy(mode = NextReplicaFenceEntity.CLOSED), db.nextReplicaFenceDao().state())
        assertEquals(ReplicaReleaseFate.CLOSED, oldRelease(release))
        val (closed, frame) = observed { store().releaseFate(release) }
        assertEquals(ReplicaReleaseFate.CLOSED, closed); assertEquals(1, frame.raw.get())
        assertEquals(release, tokens.activeReplicaStage()); assertNotNull(tokens.replicaDestinationAccess(release))
    }

    @Test fun catchupPreviousAndCommittedBranchesShareOnlyTheirOwnFullObservation() = runBlocking<Unit> {
        val value = bound(stage(legacyHash()))
        val previous = value.publishedSnapshot
        val changed = previous.copy(target = previous.target.copy(projectionHash = "0".repeat(64)))
        val pending = tokens.persistReplicaStage(value, value.transition(ReplicaSwitchPhase.CATCHUP_COMMITTING,
            pursuit = ReplicaStageCatchup(previous, changed, value.authenticationGeneration)))
        val (original, oldFrame) = observed { oldCatchup(pending) }
        val (candidate, frame) = observed { store().catchupFate(pending) }
        assertEquals(ReplicaCatchupFate.PREVIOUS, original); assertEquals(original, candidate)
        assertEquals(3, oldFrame.raw.get()); assertEquals(1, frame.raw.get())
        val sameContents = previous.copy(wireHash = "e".repeat(64))
        val retry = tokens.persistReplicaStage(pending, pending.transition(ReplicaSwitchPhase.CATCHUP_COMMITTING,
            pursuit = ReplicaStageCatchup(previous, sameContents, value.authenticationGeneration)))
        assertEquals(ReplicaCatchupFate.COMMITTED, oldCatchup(retry))
        val (applied, retryFrame) = observed { store().catchupFate(retry) }
        assertEquals(ReplicaCatchupFate.COMMITTED, applied); assertEquals(1, retryFrame.raw.get())
    }

    @Test fun closedReleaseRejectsReentrantWriteAndLaterTypedDamageStillFailsTheFullOracle() = runBlocking<Unit> {
        val release = releasing(stage(legacyHash()))
        store().closeForRelease(release)
        val before = raw(); val frame = Frame(Fault.WRITE)
        assertEquals(ReplicaReleaseFate.CLOSED, observed(frame) { store().releaseFate(release) }.first)
        assertTrue(frame.reached.get()); assertTrue(frame.nativeTransaction.get()); assertEquals(1, frame.raw.get())
        assertNotNull("A closed fence alone would permit the write; native read scope must deny it", frame.mutationFailure.get())
        assertTrue(requireNotNull(frame.mutationFailure.get()).message.orEmpty().startsWith("COUNT_TRUST_READ_SNAPSHOT"))
        assertEquals(before.contents, raw().contents); assertEquals(metric.name, db.metricDao().getMetricById(metric.id)!!.name)
        db.openHelper.writableDatabase.execSQL("UPDATE next_sync_state SET cursor=20.5 WHERE id=1")
        db.openHelper.writableDatabase.query("SELECT cursor FROM next_sync_state WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_FLOAT, it.getType(0)); assertEquals(20.5, it.getDouble(0), 0.0)
        }
        assertNotNull(runCatching { oldRelease(release) }.exceptionOrNull())
        assertEquals("SYNC_REPLICA_RELEASE_STATE_INVALID", runCatching { store().releaseFate(release) }.exceptionOrNull()?.message)
        assertEquals(release, tokens.activeReplicaStage())
    }

    @Test fun cancellationAfterFullCaptureCannotDeliverCachedFateAndTheNextCallRebuilds() = runBlocking<Unit> {
        val value = stage(legacyHash()); val before = raw(); val frame = Frame(Fault.CANCEL)
        val delivered = AtomicBoolean()
        supervisorScope {
            val job = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                observed(frame) { store().fate(value).also { delivered.set(true) } }
            }
            cancelTarget.set(job)
            assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        }
        cancelTarget.set(null)
        assertTrue(frame.reached.get()); assertTrue(frame.nativeTransaction.get()); assertEquals(1, frame.raw.get())
        assertFalse(delivered.get()); assertEquals(before.contents, raw().contents)
        val (retry, retried) = observed { store().fate(value) }
        assertEquals(ReplicaCommitFate.COMMITTED, retry); assertEquals(1, retried.raw.get())
    }

    @Test fun freshDestinationCredentialFailureStillRejectsAfterTheSharedFullProof() = runBlocking<Unit> {
        val release = releasing(stage(legacyHash())); val before = raw(); val frame = Frame(Fault.AUTH)
        assertEquals(ReplicaReleaseFate.FROZEN, oldRelease(release))
        val error = runCatching { observed(frame) { store().releaseFate(release) } }.exceptionOrNull()
        assertTrue(frame.reached.get()); assertTrue(frame.nativeTransaction.get()); assertEquals(1, frame.raw.get())
        assertTrue(failedDecrypts.get() > 0); assertEquals("SYNC_REPLICA_STAGE_AUTHORITY_CHANGED", error?.message)
        assertEquals("SYNC_REPLICA_STAGE_AUTHORITY_CHANGED", runCatching { oldRelease(release) }.exceptionOrNull()?.message)
        failDecrypt.set(false)
        assertEquals(before.contents, raw().contents); assertEquals(release, tokens.activeReplicaStage())
        assertEquals(ReplicaReleaseFate.FROZEN, store().releaseFate(release))
    }

    private suspend fun destination(value: ReplicaSwitchStage): ReplicaProjection = sessions.exclusive {
        db.withTransaction { store().destinationProjectionInTransaction(value) }
    }

    @Test fun destinationFateAndReturnedProjectionShareOneNativeObservationOnly() = runBlocking<Unit> {
        val value = stage(hash())
        val before = raw()
        val (original, originalFrame) = observed { sessions.exclusive { db.withTransaction {
            check(ReplicaPresentationRepository(db, tokens, sessions).readFenceInTransaction() == value.fence)
            require(oldSchema(value.publishedSnapshot.target.schemaHash))
            check(oldMatches(value, value.publishedSnapshot))
            ReplicaProjection.capture(db)
        } } }
        val (candidate, frame) = observed { destination(value) }
        assertEquals(original.tables, candidate.tables); assertEquals(original.hash, candidate.hash)
        assertEquals(2, originalFrame.raw.get()); assertEquals(1, frame.raw.get())
        assertEquals(before.contents, raw().contents); assertEquals(value, tokens.activeReplicaStage())
        val (again, nextFrame) = observed { destination(value) }
        assertEquals(candidate.tables, again.tables); assertEquals(1, nextFrame.raw.get())
    }

    @Test fun destinationClosedReleaseRetainsWriteBarrierAndDetectsLaterTypedDamage() = runBlocking<Unit> {
        val release = releasing(stage(hash()))
        assertEquals(ReplicaReleaseFate.FROZEN, oldRelease(release))
        val frozen = destination(release)
        store().closeForRelease(release)
        val before = raw(); val frame = Frame(Fault.WRITE)
        val projected = observed(frame) { destination(release) }.first
        assertEquals(frozen.tables, projected.tables); assertEquals(frozen.hash, projected.hash)
        assertTrue(frame.reached.get()); assertTrue(frame.nativeTransaction.get()); assertEquals(1, frame.raw.get())
        assertNotNull(frame.mutationFailure.get())
        assertTrue(requireNotNull(frame.mutationFailure.get()).message.orEmpty().startsWith("COUNT_TRUST_READ_SNAPSHOT"))
        assertEquals(before.contents, raw().contents)
        db.openHelper.writableDatabase.execSQL("UPDATE next_sync_state SET cursor=20.5 WHERE id=1")
        db.openHelper.writableDatabase.query("SELECT cursor FROM next_sync_state WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_FLOAT, it.getType(0)); assertEquals(20.5, it.getDouble(0), 0.0)
        }
        assertNotNull(runCatching { oldRelease(release) }.exceptionOrNull())
        assertEquals("SYNC_REPLICA_RELEASE_STATE_INVALID", runCatching { destination(release) }.exceptionOrNull()?.message)
        assertEquals(release, tokens.activeReplicaStage())
    }

    @Test fun destinationCancellationAfterCaptureCannotDeliverProjection() = runBlocking<Unit> {
        val value = stage(legacyHash()); val before = raw(); val frame = Frame(Fault.CANCEL)
        val delivered = AtomicBoolean()
        supervisorScope {
            val job = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                observed(frame) { destination(value).also { delivered.set(true) } }
            }
            cancelTarget.set(job)
            assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        }
        cancelTarget.set(null)
        assertTrue(frame.reached.get()); assertTrue(frame.nativeTransaction.get()); assertEquals(1, frame.raw.get())
        assertFalse(delivered.get()); assertEquals(before.contents, raw().contents)
        val (retried, retryFrame) = observed { destination(value) }
        assertEquals(value.publishedSnapshot.target.projectionHash, retried.hash); assertEquals(1, retryFrame.raw.get())
    }

    @Test fun destinationFinalFreshAuthorityFailureCannotDeliverProjection() = runBlocking<Unit> {
        val release = releasing(stage(legacyHash())); val before = raw(); val frame = Frame(Fault.AUTH)
        assertEquals(ReplicaReleaseFate.FROZEN, oldRelease(release))
        val failure = try { observed(frame) { destination(release) }; null } catch (error: Exception) { error }
        assertTrue(frame.reached.get()); assertTrue(frame.nativeTransaction.get()); assertEquals(1, frame.raw.get())
        assertTrue(failedDecrypts.get() > 0)
        assertEquals("SYNC_REPLICA_STAGE_AUTHORITY_CHANGED", failure?.message)
        assertEquals("SYNC_REPLICA_STAGE_AUTHORITY_CHANGED", runCatching { oldRelease(release) }.exceptionOrNull()?.message)
        failDecrypt.set(false)
        assertEquals(before.contents, raw().contents); assertEquals(release, tokens.activeReplicaStage())
        assertEquals(release.publishedSnapshot.target.projectionHash, destination(release).hash)
    }

    @Test fun destinationOldMigratedFateCannotBeReturnedAsCommittedProjection() = runBlocking<Unit> {
        val value = stage(legacyHash(), committed = false); val before = raw()
        assertEquals(ReplicaCommitFate.OLD, oldFate(value))
        val frame = Frame()
        val failure = try { observed(frame) { destination(value) }; null } catch (error: Exception) { error }
        assertEquals("SYNC_REPLICA_COMMIT_STATE_INVALID", failure?.message)
        assertEquals(1, frame.raw.get()); assertEquals(before.contents, raw().contents)
        assertEquals(value, tokens.activeReplicaStage())
    }

    @Test fun unownedConnectionRetainsIndependentFullProofReads() = runBlocking<Unit> {
        val value = stage(legacyHash())
        db.close(); HabitDatabaseProvider.clearInstanceForTesting()
        val unowned = Room.databaseBuilder(app, HabitDatabase::class.java, "habit_database")
            .addCallback(SyncSchemaCallback).setQueryCallback(observer, java.util.concurrent.Executor { it.run() }).build()
        try {
            unowned.openHelper.writableDatabase; assertNull(CountTrustRuntime.forDatabase(unowned))
            val (result, frame) = observed { NextReplicaCommitStore(unowned, tokens, sessions).fate(value) }
            assertEquals(ReplicaCommitFate.COMMITTED, result); assertEquals(2, frame.raw.get())
            val (destination, destinationFrame) = observed { sessions.exclusive { unowned.withTransaction {
                NextReplicaCommitStore(unowned, tokens, sessions).destinationProjectionInTransaction(value)
            } } }
            assertEquals(value.publishedSnapshot.target.projectionHash, destination.hash)
            assertEquals("Unowned connections keep separate fate and returned-projection proofs", 3, destinationFrame.raw.get())
        } finally { unowned.close(); storage.reopen() }
        assertEquals(ReplicaCommitFate.COMMITTED, oldFate(value))
    }
    @Test fun pureProjectionIsSharedOnlyInsideItsActualReadonlyScope() = runBlocking<Unit> {
        register()
        val before = raw()
        lateinit var escaped: ReplicaStorageSnapshot
        lateinit var first: ReplicaProjection
        db.withTransaction {
            ReplicaStorageSnapshot.inspect(db) { scope ->
                escaped = requireNotNull(scope)
                first = ReplicaProjection.captureInTransaction(db, scope)
                assertSame(first, ReplicaProjection.captureInTransaction(db, scope))
                assertEquals(ReplicaProjection.captureInTransaction(db).tables, first.tables)
                assertEquals(ReplicaProjection.captureInTransaction(db).hash, first.hash)
                supervisorScope {
                    val wrongJob = async { ReplicaProjection.captureInTransaction(db, scope) }
                    assertEquals("SYNC_REPLICA_SNAPSHOT_EXPIRED", runCatching { wrongJob.await() }.exceptionOrNull()?.message)
                }
            }
            assertEquals("SYNC_REPLICA_SNAPSHOT_EXPIRED", runCatching {
                ReplicaProjection.captureInTransaction(db, escaped)
            }.exceptionOrNull()?.message)
            ReplicaStorageSnapshot.inspect(db) { fresh ->
                val next = ReplicaProjection.captureInTransaction(db, requireNotNull(fresh))
                assertNotSame(first, next); assertEquals(first.hash, next.hash); assertEquals(first.tables, next.tables)
            }
        }
        assertEquals(before.contents, raw().contents)
    }

}
