package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.domain.service.AccountSessionCoordinator
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production participants, the unchanged unowned two-read path, and a separately
 * framed native typed oracle. No snapshot constructor, fabricated business proof or HTTP ACK.
 */
@RunWith(AndroidJUnit4::class)
class NextReplicaParticipantSnapshotTest : NextReplicaDestinationFixture() {
    private enum class Participant(val firstConsumerSql: String) {
        REQUEST("SELECT id, challengeContract FROM main.next_sync_state"),
        CONFIG("SELECT importId FROM next_config_imports ORDER BY importId"),
        LOCAL("SELECT operationId FROM local_fact_submissions ORDER BY operationId"),
        BUSINESS("SELECT entityType,entityUuid FROM sync_entity_state ORDER BY entityType,entityUuid")
    }
    private enum class Fault { NONE, WRITE, CANCEL, UNKNOWN_END }
    private class Frame(val participant: Participant? = null, val fault: Fault = Fault.NONE,
        val failDecryptAt: Int? = null, val schemaBoundary: Boolean = false) {
        val raw = AtomicInteger()
        val decrypts = AtomicInteger()
        val failedDecrypts = AtomicInteger()
        val reached = AtomicBoolean()
        val inTransaction = AtomicBoolean()
        val generationAdvanced = AtomicBoolean()
        val mutationFailure = AtomicReference<Throwable?>()
    }
    private val active = AtomicReference<Frame?>()
    private val cancelTarget = AtomicReference<Job?>()
    private var unowned = false
    private val nativeFault = EndFaultFactory()
    override fun tokenCipher(): TokenCipher = object : TokenCipher {
        override fun encrypt(value: String) = PlaintextTokenCipher.encrypt(value)
        override fun decrypt(value: String): String? {
            val frame = active.get()
            if (frame != null && frame.decrypts.incrementAndGet() == frame.failDecryptAt) {
                frame.failedDecrypts.incrementAndGet()
                return null
            }
            return PlaintextTokenCipher.decrypt(value)
        }
    }
    private val observer = object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            val frame = active.get() ?: return
            if (sqlQuery == "SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name")
                frame.raw.incrementAndGet()
            // These queries occur in the real business reader AFTER the first full raw read,
            // not in its schema/table paging. Callback throws are never used as injection.
            val schemaBoundary = frame.schemaBoundary && sqlQuery == "PRAGMA integrity_check"
            val participantBoundary = frame.raw.get() > 0 && sqlQuery == frame.participant?.firstConsumerSql
            if ((!schemaBoundary && !participantBoundary) || !frame.reached.compareAndSet(false, true)) return
            frame.inTransaction.set(db.inTransaction())
            when (frame.fault) {
                Fault.WRITE -> frame.mutationFailure.set(runCatching {
                    val sql = db.openHelper.writableDatabase
                    fun generation() = sql.query("SELECT generation FROM count_trust_control WHERE id=1").use {
                        check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER)
                        it.getLong(0).also { _ -> check(!it.moveToNext()) }
                    }
                    val before = generation()
                    // Preserve business predicates so the uncontrolled oracle must reach
                    // its final full raw read. The real dirty trigger changes main contents.
                    sql.execSQL("UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1")
                    frame.generationAdvanced.set(generation() > before)
                }.exceptionOrNull())
                Fault.CANCEL -> requireNotNull(cancelTarget.get()).cancel()
                Fault.UNKNOWN_END -> check(nativeFault.loseEndOn.compareAndSet(null, Thread.currentThread()))
                Fault.NONE -> Unit
            }
        }
    }
    init { storage.isolatedBuilder = { context ->
        if (!unowned) HabitDatabaseProvider.build(context, observer, nativeFault)
        else Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
            .addCallback(SyncSchemaCallback).setQueryCallback(observer, java.util.concurrent.Executor { it.run() }).build()
    } }

    private data class Inputs(val schema: ReplicaSchemaProof, val requests: ReplicaRequestProof,
        val configuration: ReplicaConfigProof, val local: ReplicaLocalFactProof)

    private suspend fun inputs(): Inputs {
        initialize()
        // Enter the actual formal editor at the top level. Its producer owns the account
        // lock and Room transaction; an outer writeRounds would re-enter the same lock.
        val iconController = com.dayforge.domain.service.AccountIconController({ runtime() }, tokens)
        val observationId = try {
            val editor = NextObjectEditor(db, tokens, sessions, iconController)
            val selected = editor.metric(metric.id)
            val actual = requireNotNull(selected.value)
            assertEquals(metric.uuid, actual.uuid); assertNotNull(actual.appearance)
            val repository = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(),
                db.habitMetricLinkDao(), nextObjectEditor = editor)
            repository.recordValue(actual.id, 21.125, "snapshot", millis,
                authority = requireNotNull(selected.authority), expectedMetricUuid = actual.uuid)
        } finally {
            iconController.awaitImages(); iconController.close()
        }
        val observation = db.metricLogDao().getAllLogsForMetric(metric.id).single()
        assertEquals(observationId, observation.id); assertEquals(21.125, observation.value, 0.0)
        assertEquals("snapshot", observation.note); assertEquals(millis, observation.date)
        val queued = db.syncOutboxDao().getAll().single()
        assertEquals("metric_log", queued.recordType); assertEquals(observation.uuid, queued.entityUuid)
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, queued.operationId))
        assertEquals(queued.id, origin.queueId); assertEquals(queued.operationId, origin.requestId)
        assertEquals(1L, db.openHelper.writableDatabase.query("SELECT count(*) FROM main.next_request_origins").use {
            assertTrue(it.moveToFirst()); it.getLong(0).also { _ -> assertFalse(it.moveToNext()) }
        })
        // Unissued, device-local bytes are still part of the complete raw inventory.
        db.withTransaction { CountTrustRuntime.maintenance(db) {
            db.openHelper.writableDatabase.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES(?,?)",
                arrayOf("1".repeat(64), byteArrayOf(0, 1, 127, -1)))
        } }
        val before = oracle()
        val schema = NextReplicaSchemaValidator(app, db, tokens, sessions).validate(access(), before.hash)
        val requests = NextReplicaRequestProofReader(db, tokens, sessions, preferences, http).read(schema)
        assertEquals(1, requests.states.size)
        assertEquals(NextRecoveryKey(NEXT_OPERATION, queued.operationId), requests.states.keys.single())
        assertEquals(NextRecoveryRequestState.NEVER_SENT, requests.states.values.single())
        val config = NextReplicaConfigProofReader(db, tokens, sessions).read(requests)
        val local = NextReplicaLocalFactProofReader(db, tokens, sessions).read(config)
        assertEquals(before.contents, oracle().contents)
        return Inputs(schema, requests, config, local)
    }

    private suspend fun run(participant: Participant, input: Inputs): Any = when (participant) {
        Participant.REQUEST -> NextReplicaRequestProofReader(db, tokens, sessions, preferences, http).read(input.schema)
        Participant.CONFIG -> NextReplicaConfigProofReader(db, tokens, sessions).read(input.requests)
        Participant.LOCAL -> NextReplicaLocalFactProofReader(db, tokens, sessions).read(input.configuration)
        Participant.BUSINESS -> NextReplicaBusinessProofReader(db, tokens, sessions, preferences, http).read(input.local)
    }

    private suspend fun <T> measured(frame: Frame, block: suspend () -> T): T {
        check(active.compareAndSet(null, frame))
        return try { block() } finally { check(active.compareAndSet(frame, null)) }
    }

    private fun signature(value: Any): List<Any?> = when (value) {
        is ReplicaRequestProof -> listOf(value.original, value.storageHash, value.states, value.hasUnsettledRequests, value.activationAuthorized)
        is ReplicaConfigProof -> listOf(signature(value.requests), value.journals, value.hasUnsettledWork, value.activationAuthorized)
        is ReplicaLocalFactProof -> listOf(signature(value.configuration), value.submissions,
            value.prompts.map { listOf(it.row, it.entries, it.completionOperationId, it.observations) }, value.hasPendingDrafts, value.activationAuthorized)
        is ReplicaBusinessProof -> listOf(signature(value.localFacts), value.records, value.unconfirmed,
            value.projectionGaps, value.hasUnfinishedTimer, value.hasProjectionReview, value.activationAuthorized)
        else -> error("Unknown participant result")
    }

    /** Independent original framing over actual SQLite types/rows/DDL. It does not call the
     * candidate snapshot, the production raw reader, or derive expected contents from a memo.
     */
    private suspend fun oracle(): ReplicaStorageProof = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        fun rows(query: String): JsonArray = sql.query(query).use { cursor -> buildJsonArray {
            while (cursor.moveToNext()) add(buildJsonObject {
                for (column in 0 until cursor.columnCount) put(cursor.getColumnName(column), buildJsonObject {
                    val type = cursor.getType(column)
                    put("sqlite_type", type)
                    put("value", when (type) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(column))
                        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(column).toRawBits().toString(16))
                        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(column))
                        Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(cursor.getBlob(column).joinToString("") { "%02x".format(it) })
                        else -> error("Unknown native type")
                    })
                })
            })
        } }
        val names = sql.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        val contents = buildJsonObject {
            put("user_version", sql.version)
            put("schema", rows("SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name"))
            put("tables", buildJsonObject { names.forEach { put(it, rows("SELECT rowid AS __recovery_rowid,* FROM main.`$it` ORDER BY rowid")) } })
        }
        ReplicaStorageProof(contents, syncPayloadHash(contents.toString()))
    }

    @Test fun everyParticipantMatchesIndependentRawOracleAndTheOriginalUnownedDoubleRead() = runBlocking<Unit> {
        val input = inputs(); val before = oracle(); val calls = peer.requests.size
        val expected = linkedMapOf<Participant, List<Any?>>()
        val authenticated = linkedMapOf<Participant, Int>()
        for (participant in Participant.entries) {
            val frame = Frame(participant)
            expected[participant] = signature(measured(frame) { run(participant, input) })
            assertEquals(participant.name, 1, frame.raw.get())
            assertTrue(frame.reached.get()); assertTrue(frame.inTransaction.get())
            assertTrue(frame.decrypts.get() >= 2)
            authenticated[participant] = frame.decrypts.get()
        }
        assertEquals(before.contents, oracle().contents)
        unowned = true
        storage.reopen().openHelper.writableDatabase
        assertNull(CountTrustRuntime.forDatabase(db)); assertEquals(before.contents, oracle().contents)
        for (participant in Participant.entries) {
            val frame = Frame(participant)
            assertEquals(expected.getValue(participant), signature(measured(frame) { run(participant, input) }))
            assertEquals("Uncontrolled $participant must retain both original reads", 2, frame.raw.get())
            assertTrue(frame.reached.get())
            assertEquals("$participant must retain the original fresh authentication work",
                authenticated.getValue(participant), frame.decrypts.get())
        }
        for (participant in Participant.entries) {
            val frame = Frame(participant, Fault.WRITE)
            val failure = runCatching { measured(frame) { run(participant, input) } }.exceptionOrNull()
            assertTrue(frame.reached.get()); assertTrue(frame.inTransaction.get())
            assertNull("Uncontrolled original write must really execute", frame.mutationFailure.get())
            assertTrue(frame.generationAdvanced.get())
            assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", failure?.message)
            assertEquals("Original final raw proof must detect the change", 2, frame.raw.get())
            assertEquals("The rejecting original transaction must fully roll back", before.contents, oracle().contents)
        }
        assertEquals(before.contents, oracle().contents); assertEquals(calls, peer.requests.size)
    }

    @Test fun writesAfterTheFirstFullReadAreActuallyRejectedForAllFourParticipants() = runBlocking<Unit> {
        val input = inputs(); val before = oracle()
        for (participant in Participant.entries) {
            val expected = signature(run(participant, input))
            val frame = Frame(participant, Fault.WRITE)
            assertEquals(expected, signature(measured(frame) { run(participant, input) }))
            assertTrue(frame.reached.get()); assertTrue(frame.inTransaction.get())
            assertEquals(1, frame.raw.get())
            val failure = frame.mutationFailure.get()
            assertTrue("$participant must reach the real SQL boundary: $failure",
                failure is IllegalStateException && failure.message.orEmpty().startsWith("COUNT_TRUST_READ_SNAPSHOT_"))
            assertFalse(frame.generationAdvanced.get())
            assertEquals(before.contents, oracle().contents)
        }
    }

    @Test fun cancellationAfterCaptureCannotDeliverEvidenceOrPoisonTheNextParticipantCall() = runBlocking<Unit> {
        val input = inputs(); val before = oracle()
        for (participant in Participant.entries) {
            val frame = Frame(participant, Fault.CANCEL); val returned = AtomicBoolean()
            val job = async {
                cancelTarget.set(currentCoroutineContext()[Job])
                measured(frame) { run(participant, input) }.also { returned.set(true) }
            }
            try { assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException) }
            finally { cancelTarget.set(null) }
            assertTrue(frame.reached.get()); assertTrue(frame.inTransaction.get())
            assertFalse(returned.get()); assertEquals(1, frame.raw.get())
            assertEquals(before.contents, oracle().contents)
            val retry = Frame(participant)
            measured(retry) { run(participant, input) }
            assertEquals(1, retry.raw.get()); assertTrue(retry.reached.get())
        }
    }

    @Test fun theLastFreshDecryptStillRejectsEachResultAfterItsCompleteFrozenRead() = runBlocking<Unit> {
        val input = inputs(); val before = oracle()
        for (participant in Participant.entries) {
            val baseline = Frame(participant)
            val expected = signature(measured(baseline) { run(participant, input) })
            assertTrue(baseline.decrypts.get() >= 2)
            val frame = Frame(participant, failDecryptAt = baseline.decrypts.get())
            val failure = runCatching { measured(frame) { run(participant, input) } }.exceptionOrNull()
            assertEquals("$participant final fresh authentication", "SYNC_ACCOUNT_CHANGED", failure?.message)
            assertTrue(frame.reached.get()); assertTrue(frame.inTransaction.get())
            assertEquals(1, frame.raw.get()); assertEquals(1, frame.failedDecrypts.get())
            assertEquals(baseline.decrypts.get(), frame.decrypts.get())
            assertEquals(before.contents, oracle().contents)
            val retry = Frame(participant)
            assertEquals(expected, signature(measured(retry) { run(participant, input) }))
            assertEquals(baseline.decrypts.get(), retry.decrypts.get()); assertEquals(1, retry.raw.get())
        }
    }

    private fun archiveReader() = NextReplicaArchiveInputReader(app, db, tokens, sessions, preferences, http,
        iconDb, icons, transfers, files, dataStore, themes, builtIns)

    @Test fun actualArchiveFileCallbackEndsPriorScopesAndAlwaysRebuildsBusinessEvidenceAfterward() = runBlocking<Unit> {
        val input = inputs(); val before = oracle(); val original = input.schema.original
        val frame = Frame(); var entered = false
        measured(frame) { archiveReader().inspect(original, before.hash) { archive, _ ->
            entered = true
            assertEquals(before.contents, archive.storage.contents)
            assertEquals("Shared validated local proof and independent material source precede the callback", 2, frame.raw.get())
        } }
        assertTrue(entered); assertEquals("Post-file business proof is a new participant", 3, frame.raw.get())
        val changed = Frame(); entered = false
        val failure = runCatching { measured(changed) { archiveReader().inspect(original, before.hash) { _, _ ->
            assertEquals(2, changed.raw.get()); entered = true
            // File consumers run outside account/Room: a real committed mutation must be
            // visible to the following business proof, never hidden by its earlier memo.
            db.withTransaction { db.openHelper.writableDatabase.execSQL(
                "UPDATE metrics SET description='changed-after-file-callback' WHERE id=?", arrayOf(metric.id)) }
        } } }.exceptionOrNull()
        assertTrue(entered); assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", failure?.message)
        assertEquals(3, changed.raw.get()); assertNotEquals(before.hash, oracle().hash)
    }

    @Test fun thePrivateStorageObservationCannotEscapeItsJobScopeOrTransaction() = runBlocking<Unit> {
        inputs(); val before = oracle(); lateinit var captured: ReplicaStorageSnapshot
        db.withTransaction {
            ReplicaStorageSnapshot.inspect(db) { snapshot ->
                captured = requireNotNull(snapshot)
                val first = captured.storage(db)
                assertEquals(before.contents, first.contents); assertSame(first, captured.storage(db))
                coroutineScope { async {
                    assertEquals("SYNC_REPLICA_SNAPSHOT_EXPIRED", runCatching { captured.storage(db) }.exceptionOrNull()?.message)
                }.await() }
            }
            assertEquals("SYNC_REPLICA_SNAPSHOT_EXPIRED", runCatching { captured.storage(db) }.exceptionOrNull()?.message)
        }
        db.withTransaction {
            assertEquals("SYNC_REPLICA_SNAPSHOT_EXPIRED", runCatching { captured.storage(db) }.exceptionOrNull()?.message)
        }
        assertEquals(before.contents, oracle().contents)
    }

    private fun pipeline() = NextReplicaLocalProofPipeline(db, tokens, sessions, preferences, http)

    private suspend fun independently(schema: ReplicaSchemaProof): ReplicaBusinessProof {
        val requests = NextReplicaRequestProofReader(db, tokens, sessions, preferences, http).read(schema)
        val config = NextReplicaConfigProofReader(db, tokens, sessions).read(requests)
        val local = NextReplicaLocalFactProofReader(db, tokens, sessions).read(config)
        return NextReplicaBusinessProofReader(db, tokens, sessions, preferences, http).read(local)
    }

    @Test fun localPipelineMatchesFourIndependentParticipantsWithoutReusingTheirAuthorityOrUnownedProof() = runBlocking<Unit> {
        val input = inputs(); val before = oracle(); val calls = peer.requests.size
        val original = Frame(Participant.BUSINESS)
        val expected = signature(measured(original) { independently(input.schema) })
        assertEquals(4, original.raw.get()); assertTrue(original.reached.get())
        val shared = Frame(Participant.BUSINESS)
        assertEquals(expected, signature(measured(shared) { pipeline().read(input.schema) }))
        assertEquals(1, shared.raw.get()); assertTrue(shared.reached.get()); assertTrue(shared.inTransaction.get())
        assertEquals("Each participant retains all of its fresh authentication work", original.decrypts.get(), shared.decrypts.get())
        assertEquals(before.contents, oracle().contents)
        // A later actual transaction has no reusable storage/authority capability.
        val next = Frame(Participant.BUSINESS)
        assertEquals(expected, signature(measured(next) { pipeline().read(input.schema) }))
        assertEquals(1, next.raw.get()); assertEquals(original.decrypts.get(), next.decrypts.get())
        unowned = true
        storage.reopen().openHelper.writableDatabase
        assertNull(CountTrustRuntime.forDatabase(db)); assertEquals(before.contents, oracle().contents)
        val fallback = Frame(Participant.BUSINESS)
        assertEquals(expected, signature(measured(fallback) { pipeline().read(input.schema) }))
        assertEquals("Unowned builders still execute both raw reads for all four participants", 8, fallback.raw.get())
        assertEquals(original.decrypts.get(), fallback.decrypts.get()); assertTrue(fallback.reached.get())
        assertEquals(before.contents, oracle().contents); assertEquals(calls, peer.requests.size)
    }

    @Test fun allFourPipelineBoundariesRejectRealWritesAndCancellationWithinTheSharedSnapshot() = runBlocking<Unit> {
        val input = inputs(); val before = oracle(); val expected = signature(independently(input.schema))
        val calls = peer.requests.size
        for (participant in Participant.entries) {
            val write = Frame(participant, Fault.WRITE)
            assertEquals(expected, signature(measured(write) { pipeline().read(input.schema) }))
            assertTrue(participant.name, write.reached.get()); assertTrue(write.inTransaction.get())
            assertEquals(1, write.raw.get())
            assertEquals("COUNT_TRUST_READ_SNAPSHOT_WRITE_DENIED", write.mutationFailure.get()?.message)
            assertFalse(write.generationAdvanced.get()); assertEquals(before.contents, oracle().contents)
            val cancelled = Frame(participant, Fault.CANCEL); val returned = AtomicBoolean()
            val job = async {
                cancelTarget.set(currentCoroutineContext()[Job])
                measured(cancelled) { pipeline().read(input.schema) }.also { returned.set(true) }
            }
            try { assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException) }
            finally {
                cancelTarget.set(null)
                withContext(NonCancellable) { withTimeout(5_000) { job.cancelAndJoin() } }
            }
            assertTrue(cancelled.reached.get()); assertTrue(cancelled.inTransaction.get())
            assertEquals(1, cancelled.raw.get()); assertFalse(returned.get())
            assertEquals(before.contents, oracle().contents)
        }
        assertEquals(expected, signature(pipeline().read(input.schema)))
        assertEquals(calls, peer.requests.size)
    }

    @Test fun everyPipelineEntryAndFinalDecryptRejectsInsteadOfBorrowingAPreviousParticipantGrant() = runBlocking<Unit> {
        val input = inputs(); val before = oracle(); val calls = peer.requests.size
        var offset = 0
        val freshBoundaries = mutableListOf<Pair<Participant, Int>>()
        for (participant in Participant.entries) {
            val separate = Frame(participant)
            measured(separate) { run(participant, input) }
            assertTrue(separate.decrypts.get() >= 2)
            freshBoundaries += participant to offset + 1
            offset += separate.decrypts.get()
            freshBoundaries += participant to offset
        }
        val baseline = Frame(Participant.BUSINESS)
        val expected = signature(measured(baseline) { pipeline().read(input.schema) })
        assertEquals(offset, baseline.decrypts.get()); assertEquals(1, baseline.raw.get())
        for ((participant, position) in freshBoundaries) {
            val failed = Frame(participant, failDecryptAt = position)
            val error = runCatching { measured(failed) { pipeline().read(input.schema) } }.exceptionOrNull()
            assertEquals("$participant fresh boundary $position", "SYNC_ACCOUNT_CHANGED", error?.message)
            assertEquals(1, failed.failedDecrypts.get()); assertEquals(position, failed.decrypts.get())
            assertEquals(if (position == 1) 0 else 1, failed.raw.get())
            assertEquals(before.contents, oracle().contents)
        }
        assertEquals(expected, signature(pipeline().read(input.schema)))
        assertEquals(calls, peer.requests.size)
    }

    @Test fun nextTransactionRechecksChangedContentsAndTheCompleteBusinessRejection() = runBlocking<Unit> {
        val input = inputs(); val before = oracle(); val calls = peer.requests.size
        pipeline().read(input.schema)
        // Match the original BusinessProofReaderTest fault layout: mutate actual projection
        // bytes without inventing an unissued structural request before the intended proof.
        // The real transaction restores the full outbox control before it can commit.
        suspend fun projectionWrite(statement: String) = db.withTransaction {
            val sql = db.openHelper.writableDatabase
            fun requireOutboxEnabled() = sql.query("SELECT suppressOutbox FROM main.sync_control WHERE id=1 LIMIT 2").use {
                assertTrue(it.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_INTEGER, it.getType(0))
                assertEquals(0L, it.getLong(0)); assertFalse(it.moveToNext())
            }
            requireOutboxEnabled()
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL(statement, arrayOf(metric.id))
            sql.query("SELECT changes()").use {
                assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertFalse(it.moveToNext())
            }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            requireOutboxEnabled()
        }
        fun unchangedRequestInventory(previous: ReplicaStorageProof, current: ReplicaStorageProof) {
            assertEquals(previous.contents.getValue("user_version"), current.contents.getValue("user_version"))
            assertEquals(previous.contents.getValue("schema"), current.contents.getValue("schema"))
            val oldTables = previous.contents.getValue("tables").jsonObject
            val newTables = current.contents.getValue("tables").jsonObject
            for (table in listOf("sync_control", "sync_outbox", "timer_command_outbox", "next_request_origins",
                "next_transmissions", "next_acceptances", "next_rejections", "sqlite_sequence")) {
                assertEquals(table, oldTables.getValue(table), newTables.getValue(table))
            }
        }
        fun metricCell(image: ReplicaStorageProof, column: String): JsonObject =
            image.contents.getValue("tables").jsonObject.getValue("metrics").jsonArray.single {
                it.jsonObject.getValue("id").jsonObject.getValue("value").jsonPrimitive.long == metric.id
            }.jsonObject.getValue(column).jsonObject
        projectionWrite("UPDATE metrics SET description='actual-later-transaction' WHERE id=?")
        val changed = oracle(); assertNotEquals(before.hash, changed.hash)
        unchangedRequestInventory(before, changed)
        assertEquals(Cursor.FIELD_TYPE_STRING, metricCell(changed, "description").getValue("sqlite_type").jsonPrimitive.int)
        assertEquals(JsonPrimitive("actual-later-transaction"), metricCell(changed, "description").getValue("value"))
        val stale = Frame()
        assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", runCatching {
            measured(stale) { pipeline().read(input.schema) }
        }.exceptionOrNull()?.message)
        assertEquals(1, stale.raw.get()); assertEquals(changed.contents, oracle().contents)
        val validatedStale = Frame()
        assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", runCatching {
            measured(validatedStale) { pipeline().readValidated(app, input.schema.original, before.hash) }
        }.exceptionOrNull()?.message)
        assertEquals(1, validatedStale.raw.get()); assertEquals(changed.contents, oracle().contents)
        val schema = NextReplicaSchemaValidator(app, db, tokens, sessions).validate(access(), changed.hash)
        assertEquals(signature(independently(schema)), signature(pipeline().read(schema)))
        assertEquals(changed.contents, oracle().contents)
        // Fresh raw hashes are not business validity: the original domain reader must still
        // reject a physical integer that Room could otherwise coerce to an apparently valid Int.
        projectionWrite("UPDATE metrics SET decimalPlaces=2147483648 WHERE id=?")
        val invalid = oracle(); assertNotEquals(changed.hash, invalid.hash)
        unchangedRequestInventory(changed, invalid)
        assertEquals(Cursor.FIELD_TYPE_INTEGER, metricCell(invalid, "decimalPlaces").getValue("sqlite_type").jsonPrimitive.int)
        assertEquals(JsonPrimitive(2147483648L), metricCell(invalid, "decimalPlaces").getValue("value"))
        val invalidSchema = NextReplicaSchemaValidator(app, db, tokens, sessions).validate(access(), invalid.hash)
        val separateError = runCatching { independently(invalidSchema) }.exceptionOrNull()
        val sharedError = runCatching { pipeline().read(invalidSchema) }.exceptionOrNull()
        assertNotNull(separateError)
        assertEquals(separateError!!::class, sharedError!!::class)
        assertEquals(separateError.message, sharedError.message)
        assertEquals("SYNC_RECOVERY_PROJECTION_INTEGER_INVALID", sharedError.message)
        val validatedError = runCatching { pipeline().readValidated(app, input.schema.original, invalid.hash) }.exceptionOrNull()
        assertNotNull(validatedError); assertEquals(separateError::class, validatedError!!::class)
        assertEquals(separateError.message, validatedError.message)
        assertEquals(invalid.contents, oracle().contents); assertEquals(calls, peer.requests.size)
    }

    @Test fun borrowedParticipantEntryCannotUseAnotherJobDatabaseOrAnEndedSnapshot() = runBlocking<Unit> {
        val input = inputs(); val before = oracle(); lateinit var captured: ReplicaStorageSnapshot
        val foreign = Room.inMemoryDatabaseBuilder(app, HabitDatabase::class.java).build()
        try {
            db.withTransaction { ReplicaStorageSnapshot.inspect(db) { observed ->
                captured = requireNotNull(observed)
                val requests = NextReplicaRequestProofReader(db, tokens, sessions, preferences, http)
                assertEquals(signature(input.requests), signature(requests.readInSnapshot(input.schema, captured)))
                coroutineScope { async {
                    assertEquals("SYNC_REPLICA_SNAPSHOT_EXPIRED", runCatching {
                        requests.readInSnapshot(input.schema, captured)
                    }.exceptionOrNull()?.message)
                }.await() }
                assertEquals("SYNC_REPLICA_SNAPSHOT_EXPIRED", runCatching { captured.requireCurrent(foreign) }.exceptionOrNull()?.message)
                assertEquals(signature(input.requests), signature(requests.readInSnapshot(input.schema, captured)))
            } }
            db.withTransaction {
                assertEquals("SYNC_REPLICA_SNAPSHOT_EXPIRED", runCatching {
                    NextReplicaConfigProofReader(db, tokens, sessions).readInSnapshot(input.requests, captured)
                }.exceptionOrNull()?.message)
                var entered = false
                assertEquals("SYNC_REPLICA_SNAPSHOT_EXPIRED", runCatching {
                    ReplicaFactRecoveryReadScope.withinSnapshot(db, tokens, sessions, input.schema.original, captured) { entered = true }
                }.exceptionOrNull()?.message)
                assertFalse(entered)
            }
            assertEquals(before.contents, oracle().contents)
        } finally { foreign.close() }
    }

    @Test fun validatedPipelineMatchesSeparateSchemaAndLocalProofAndKeepsTheUnownedFallback() = runBlocking<Unit> {
        val input = inputs(); val before = oracle(); val original = input.schema.original
        val calls = peer.requests.size
        val separate = Frame(Participant.BUSINESS)
        val expected = signature(measured(separate) {
            pipeline().read(NextReplicaSchemaValidator(app, db, tokens, sessions).validate(original, before.hash))
        })
        assertEquals(2, separate.raw.get()); assertTrue(separate.reached.get())
        repeat(2) {
            val shared = Frame(Participant.BUSINESS)
            assertEquals(expected, signature(measured(shared) { pipeline().readValidated(app, original, before.hash) }))
            assertEquals("A new call must read its actual contents again", 1, shared.raw.get())
            assertEquals(separate.decrypts.get(), shared.decrypts.get())
            assertTrue(shared.reached.get()); assertTrue(shared.inTransaction.get())
            assertEquals(before.contents, oracle().contents)
        }
        unowned = true; storage.reopen().openHelper.writableDatabase
        assertNull(CountTrustRuntime.forDatabase(db)); assertEquals(before.contents, oracle().contents)
        val fallback = Frame(Participant.BUSINESS)
        assertEquals(expected, signature(measured(fallback) { pipeline().readValidated(app, original, before.hash) }))
        assertEquals("Unowned schema plus four original two-read participants", 9, fallback.raw.get())
        assertEquals(separate.decrypts.get(), fallback.decrypts.get()); assertTrue(fallback.reached.get())
        assertEquals(before.contents, oracle().contents); assertEquals(calls, peer.requests.size)
    }

    @Test fun validatedPipelineRetainsSchemaAndEveryParticipantFreshAuthenticationBoundary() = runBlocking<Unit> {
        val input = inputs(); val before = oracle(); val original = input.schema.original
        val calls = peer.requests.size
        val schema = Frame(); measured(schema) { NextReplicaSchemaValidator(app, db, tokens, sessions).validate(original, before.hash) }
        assertTrue(schema.decrypts.get() >= 2)
        var offset = schema.decrypts.get()
        val boundaries = mutableListOf(1, offset)
        for (participant in Participant.entries) {
            val frame = Frame(participant); measured(frame) { run(participant, input) }
            assertTrue(frame.decrypts.get() >= 2)
            boundaries += offset + 1; offset += frame.decrypts.get(); boundaries += offset
        }
        val baseline = Frame()
        val expected = signature(measured(baseline) { pipeline().readValidated(app, original, before.hash) })
        assertEquals(offset, baseline.decrypts.get()); assertEquals(1, baseline.raw.get())
        for (position in boundaries) {
            val failed = Frame(failDecryptAt = position)
            assertEquals("Fresh authentication $position", "SYNC_ACCOUNT_CHANGED", runCatching {
                measured(failed) { pipeline().readValidated(app, original, before.hash) }
            }.exceptionOrNull()?.message)
            assertEquals(1, failed.failedDecrypts.get()); assertEquals(position, failed.decrypts.get())
            assertEquals(if (position == 1) 0 else 1, failed.raw.get())
            assertEquals(before.contents, oracle().contents)
        }
        assertEquals(expected, signature(pipeline().readValidated(app, original, before.hash)))
        assertEquals(calls, peer.requests.size)
    }

    @Test fun validatedSchemaAndLocalBoundariesDenyWritesAndCancelWithoutPublishingProof() = runBlocking<Unit> {
        val input = inputs(); val before = oracle(); val original = input.schema.original
        val expected = signature(independently(input.schema)); val calls = peer.requests.size
        for (participant in listOf<Participant?>(null) + Participant.entries) {
            val write = Frame(participant, Fault.WRITE, schemaBoundary = participant == null)
            assertEquals(expected, signature(measured(write) { pipeline().readValidated(app, original, before.hash) }))
            assertTrue(write.reached.get()); assertTrue(write.inTransaction.get()); assertEquals(1, write.raw.get())
            assertEquals("COUNT_TRUST_READ_SNAPSHOT_WRITE_DENIED", write.mutationFailure.get()?.message)
            assertFalse(write.generationAdvanced.get()); assertEquals(before.contents, oracle().contents)
            val cancelled = Frame(participant, Fault.CANCEL, schemaBoundary = participant == null)
            val returned = AtomicBoolean()
            val job = async {
                cancelTarget.set(currentCoroutineContext()[Job])
                measured(cancelled) { pipeline().readValidated(app, original, before.hash) }.also { returned.set(true) }
            }
            try { assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException) }
            finally {
                cancelTarget.set(null)
                withContext(NonCancellable) { withTimeout(5_000) { job.cancelAndJoin() } }
            }
            assertTrue(cancelled.reached.get()); assertTrue(cancelled.inTransaction.get()); assertFalse(returned.get())
            assertEquals(if (participant == null) 0 else 1, cancelled.raw.get())
            assertEquals(before.contents, oracle().contents)
        }
        assertEquals(expected, signature(pipeline().readValidated(app, original, before.hash)))
        assertEquals(calls, peer.requests.size)
    }

    @Test fun validatedPipelineRejectsWrongBindingAndBorrowedSnapshotJobDatabaseOrLifetime() = runBlocking<Unit> {
        val input = inputs(); val before = oracle(); val original = input.schema.original
        val foreign = Room.inMemoryDatabaseBuilder(app, HabitDatabase::class.java).build()
        try {
            val validator = NextReplicaSchemaValidator(app, db, tokens, sessions)
            val wrong = listOf(
                NextReplicaLocalProofPipeline(foreign, tokens, sessions, preferences, http),
                NextReplicaLocalProofPipeline(db, TokenManager(dataStore, tokenCipher()), sessions, preferences, http),
                NextReplicaLocalProofPipeline(db, tokens, AccountSessionCoordinator(), preferences, http))
            for (other in wrong) {
                val frame = Frame()
                assertEquals("SYNC_REPLICA_PIPELINE_BINDING_INVALID", runCatching {
                    measured(frame) { validator.validateAndReadLocal(original, before.hash, other) }
                }.exceptionOrNull()?.message)
                assertEquals(0, frame.raw.get()); assertEquals(0, frame.decrypts.get())
            }
            val expected = signature(independently(input.schema)); lateinit var captured: ReplicaStorageSnapshot
            db.withTransaction { ReplicaStorageSnapshot.inspect(db) { snapshot ->
                captured = requireNotNull(snapshot)
                assertEquals(expected, signature(pipeline().readInSnapshot(input.schema, captured)))
                coroutineScope { async {
                    assertEquals("SYNC_REPLICA_SNAPSHOT_EXPIRED", runCatching {
                        pipeline().readInSnapshot(input.schema, captured)
                    }.exceptionOrNull()?.message)
                }.await() }
                assertEquals("SYNC_REPLICA_SNAPSHOT_EXPIRED", runCatching {
                    wrong.first().readInSnapshot(input.schema, captured)
                }.exceptionOrNull()?.message)
                assertEquals(expected, signature(pipeline().readInSnapshot(input.schema, captured)))
            } }
            db.withTransaction {
                assertEquals("SYNC_REPLICA_SNAPSHOT_EXPIRED", runCatching {
                    pipeline().readInSnapshot(input.schema, captured)
                }.exceptionOrNull()?.message)
            }
            assertEquals(before.contents, oracle().contents)
        } finally { foreign.close() }
    }

    @Test fun validatedEntryRetainsSchemaAndPhysicalTypeRefusalBeforeAnyBusinessProof() = runBlocking<Unit> {
        val input = inputs(); val original = input.schema.original; val calls = peer.requests.size
        suspend fun sameRefusal(expected: String) {
            val image = oracle()
            val separate = Frame(); val shared = Frame()
            val oldError = runCatching { measured(separate) {
                pipeline().read(NextReplicaSchemaValidator(app, db, tokens, sessions).validate(original, image.hash))
            } }.exceptionOrNull()
            val newError = runCatching { measured(shared) { pipeline().readValidated(app, original, image.hash) } }.exceptionOrNull()
            assertNotNull(oldError); assertNotNull(newError)
            assertEquals(expected, oldError?.message); assertEquals(oldError!!::class, newError!!::class)
            assertEquals(oldError.message, newError.message)
            assertEquals(0, separate.raw.get()); assertEquals(0, shared.raw.get())
            assertEquals(separate.decrypts.get(), shared.decrypts.get())
            assertEquals(image.contents, oracle().contents)
        }
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE VIEW unreviewed_validated_data AS SELECT * FROM metrics")
        sameRefusal("SYNC_RECOVERY_SCHEMA_INVALID")
        sql.execSQL("DROP VIEW unreviewed_validated_data")
        db.withTransaction {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE habits SET targetValue=CAST('10' AS BLOB) WHERE uuid=?", arrayOf(habit.uuid))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        sameRefusal("SYNC_RECOVERY_SQL_TYPE_INVALID")
        assertEquals(calls, peer.requests.size)
    }

    @Test fun validatedProofCannotReturnAfterTheActualNativeEndReturnIsLost() = runBlocking<Unit> {
        val input = inputs(); val before = oracle(); val original = input.schema.original
        val expected = signature(independently(input.schema)); val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        val fault = Frame(Participant.BUSINESS, Fault.UNKNOWN_END); val returned = AtomicBoolean()
        val failure = runCatching {
            measured(fault) { pipeline().readValidated(app, original, before.hash) }.also { returned.set(true) }
        }.exceptionOrNull()
        assertEquals("REPLICA_VALIDATED_TEST_UNKNOWN_END", failure?.message)
        assertTrue(fault.reached.get()); assertTrue(fault.inTransaction.get()); assertEquals(1, fault.raw.get())
        assertFalse(returned.get()); assertEquals(1, nativeFault.lostEnds.get()); assertNull(nativeFault.loseEndOn.get())
        assertTrue(owner.isRetired)
        storage.reopen().openHelper.writableDatabase
        assertNotSame(owner, CountTrustRuntime.forDatabase(db)); assertEquals(before.contents, oracle().contents)
        assertEquals(expected, signature(pipeline().readValidated(app, original, before.hash)))
    }

    /** Test-only fixed lost-return fault after the real outer END on the observed native
     * transaction thread. Other Room invalidation transactions cannot consume the fault.
     */
    private class EndFaultFactory : SupportSQLiteOpenHelper.Factory {
        val loseEndOn = AtomicReference<Thread?>()
        val lostEnds = AtomicInteger()
        private val monitor = Any()
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null
            var adapted: SupportSQLiteDatabase? = null
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
                if (original !== raw) {
                    original = raw
                    adapted = object : SupportSQLiteDatabase by raw {
                        override fun endTransaction() {
                            raw.endTransaction()
                            if (!raw.inTransaction() && loseEndOn.compareAndSet(Thread.currentThread(), null)) {
                                lostEnds.incrementAndGet(); error("REPLICA_VALIDATED_TEST_UNKNOWN_END")
                            }
                        }
                    }
                }
                requireNotNull(adapted)
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
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase get() = wrap(helper.writableDatabase)
                override val readableDatabase get() = wrap(helper.readableDatabase)
            }
        }
    }
}
