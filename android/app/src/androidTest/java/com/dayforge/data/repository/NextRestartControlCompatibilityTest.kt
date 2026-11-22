package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.*
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real physical production Room, original editor/restart/HTTP/ACK/pull participants.
 * Native instrumentation observes an already-executed INSERT, and only negative cases inject
 * corruption there. No fabricated receipt, proof root, control delta or replacement producer.
 */
@RunWith(AndroidJUnit4::class)
class NextRestartControlCompatibilityTest : NextObjectEditorFixture() {
    private val native = RestartInsertFactory()
    private val json = Json { encodeDefaults = true }
    private val history = mutableListOf<ChallengeRoundRecord>()
    private val changes = mutableListOf<SyncV2Change>()
    private var revision = 1L
    private var cursor = 20L
    private lateinit var serverPlan: HabitEntity
    private val pushes = AtomicInteger()
    @Volatile private var frozenBeforeReply: Image? = null

    init {
        storage.isolatedBuilder = { context ->
            val factory = CountTrustOpenHelperFactory(context, native)
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .openHelperFactory(factory).addCallback(SyncSchemaCallback).build()
                .also { CountTrustRuntime.attach(it, factory) }
        }
    }

    private data class Control(val storageId: String, val generation: Long, val dirty: Long)
    private data class Image(val control: Control, val contents: JsonObject)
    private enum class Stage(val table: String) {
        ORIGIN("next_request_origins"), TRANSMISSION("next_transmissions"),
        ACCEPTANCE("next_acceptances"), PLAN("next_restart_plan_proofs")
    }
    private data class Inserted(val stage: Stage, val entry: String, val before: Control, val after: Control)

    private fun initial(activity: String) = ChallengeRoundRecord(initialChallengeRoundHead(activity), null, null, null)
    private fun metadata() = ChallengeMetadata(1, listOf(
        ChallengeCheckpoint(history.last().head, history.toList()),
        ChallengeCheckpoint(initial(timerHabit.uuid).head, listOf(initial(timerHabit.uuid)))
    ), emptyList())
    private fun plan(row: HabitEntity, version: Long) = SyncV2Change(0, "plan_node", row.uuid, "upsert", version,
        JsonObject(NextStructureMapper.writePlan(row) + mapOf("public_id" to JsonPrimitive(row.uuid),
            "revision" to JsonPrimitive(version), "created_at" to JsonPrimitive(time),
            "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time, id(4))
    private fun merger(http: NextSyncHttp) = NextSyncMergeStore(db, tokens, sessions,
        OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences)),
        NextTimerRequestStore(db, tokens, sessions, sender(http)))
    private fun restart(http: NextSyncHttp) = NextChallengeRestartRepository(db, tokens, sessions, http)
    private suspend fun image() = db.withTransaction {
        Image(rawControl(db.openHelper.writableDatabase), NextReplicaStorageProof(db).readInTransaction().contents)
    }
    private suspend fun control() = db.withTransaction { rawControl(db.openHelper.writableDatabase) }
    private fun assertDelta(before: Control, after: Control, rows: Long) {
        assertEquals(before.storageId, after.storageId)
        assertEquals(Math.addExact(before.generation, rows), after.generation)
        assertEquals(1L, after.dirty)
    }
    private suspend fun propose(http: NextSyncHttp): NextRestartReference {
        val displayed = editor.habit(habit.id)
        return restart(http).restart(requireNotNull(displayed.value), requireNotNull(displayed.authority))
    }
    private suspend fun retire() = db.withTransaction {
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        owner.maintenance(db) { owner.retireFastPathForRecovery() }
    }

    /** The server follows the original restart fixture's actual protocol and monotonically
     * appends one round + one plan frame. A retried request returns its real previous result.
     */
    private suspend fun initialize(): NextSyncHttp {
        habit = habit.copy(targetCycles = 3, isActive = false)
        timerHabit = timerHabit.copy(targetCycles = 3)
        serverPlan = habit
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit); db.habitDao().update(timerHabit)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        history += initial(habit.uuid)
        register()
        val http = channel { input ->
            when {
                input.path.endsWith("/identity") -> reply(input)
                input.path.startsWith("/api/v2/sync/rounds/changes") -> {
                    val from = input.target.substringAfter("cursor=").substringBefore('&').toLong()
                    val meta = metadata()
                    MaterialSocketServer.Reply(json.encodeToString(RoundSyncPullResponse(
                        changes.filter { it.sequence > from }, cursor, false, time, 1, meta.checkpoints, meta.births)).toByteArray())
                }
                else -> {
                    assertEquals("/api/v2/sync/rounds/push", input.path)
                    pushes.incrementAndGet()
                    // sendAndAccept has ended its real freeze transaction before network I/O.
                    frozenBeforeReply = runBlocking { image() }
                    val body = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
                    val operation = body.operations.single()
                    assertEquals("challenge_round", operation.entityType)
                    assertNull(body.contexts.single().head)
                    val intent = json.decodeFromJsonElement<ChallengeRestartIntent>(operation.payload)
                    val previous = history.singleOrNull { it.restartOperationUuid == operation.operationId }
                    val record = previous ?: ChallengeRoundRecord(ChallengeRoundHead(habit.uuid, intent.roundUuid,
                        intent.expectedGeneration + 1), id(4), operation.operationId, intent).also {
                        assertEquals(history.last().head.roundUuid, intent.expectedRoundUuid)
                        assertEquals(revision, intent.expectedPlanRevision)
                        history += it
                        serverPlan = serverPlan.copy(isActive = true)
                        revision++
                        changes += SyncV2Change(++cursor, "challenge_round", it.head.roundUuid, "upsert", 1,
                            json.encodeToJsonElement(it).jsonObject, time, id(4))
                        changes += plan(serverPlan, revision).copy(sequence = ++cursor)
                    }
                    val result = NextSyncOperationResult(operation.operationId, operation.entityType,
                        operation.entityUuid, if (previous == null) "applied" else "already_applied", 1,
                        entity = json.encodeToJsonElement(record).jsonObject)
                    val meta = metadata()
                    MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(listOf(result), 1,
                        meta.checkpoints, meta.births)).toByteArray())
                }
            }
        }.first
        val metricChange = SyncV2Change(0, "metric", metric.uuid, "upsert", 1,
            JsonObject(NextStructureMapper.writeMetric(metric) + mapOf("public_id" to JsonPrimitive(metric.uuid),
                "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time),
                "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
        val meta = metadata()
        merger(http).bootstrap(access(), null, RoundSyncBootstrapResponse(
            listOf(plan(habit, 1), plan(timerHabit, 1), metricChange), cursor, time, emptyList(), 1,
            meta.checkpoints, meta.births))
        native.inserts.clear()
        return http
    }

    private suspend fun page(http: NextSyncHttp): Pair<com.dayforge.data.local.entity.NextSyncStateEntity, RoundSyncPullResponse> {
        val captured = access()
        val state = requireNotNull(merger(http).state(captured, true))
        return state to requireNotNull(http.session(captured) { it.roundPull(state.cursor) })
    }

    @Test fun exactLocalFreezeAckAndPlanDeltasWorkAfterSoftRetirementWithoutIssuingRoot() = runBlocking<Unit> {
        val http = initialize()
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        for (retired in listOf(false, true)) {
            if (retired) retire()
            assertEquals(retired, owner.isRetired)
            val priorInserts = native.inserts.size
            val before = control()
            val reference = propose(http)
            val local = control()
            assertDelta(before, local, 5) // Suppression2 + habit1 + queue1 + origin1.
            assertEquals(NextRestartOutcome.COMMITTED, restart(http).sendAndAccept(access(), reference.operationId))
            val frozen = requireNotNull(frozenBeforeReply).control
            assertDelta(local, frozen, 2) // Materialization + frozen transmission.
            assertDelta(frozen, control(), 4) // One new round + marker + receipt + queue deletion; no births.
            assertEquals(0, count("next_challenge_births"))
            val (state, response) = page(http)
            val priorPlanInserts = native.inserts.count { it.stage == Stage.PLAN }
            merger(http).page(access(), state, response)
            val inserted = native.inserts.filter { it.stage == Stage.PLAN }.drop(priorPlanInserts).single()
            assertDelta(inserted.before, inserted.after, 1) // Only this INSERT, not the entire pull transaction.
            val actualInserts = native.inserts.drop(priorInserts)
            assertEquals(Stage.entries.toSet(), actualInserts.map { it.stage }.toSet())
            assertEquals(4, actualInserts.size)
            assertTrue("Generated Room inserts must actually use the observed step/execute path",
                actualInserts.all { it.entry == "execute" })
            val accepted = db.withTransaction { NextRestartStore(db).acceptedPlan(access(), reference) }
            assertEquals(revision, accepted.first.getValue("revision").jsonPrimitive.long)
            val stable = image(); val sent = pushes.get()
            assertEquals(NextRestartOutcome.REPLAYED, restart(http).sendAndAccept(access(), reference.operationId))
            db.withTransaction {
                NextRestartStore(db).capturePlanFrame(access(), response.changes.single { it.entityType == "plan_node" }, response.metadata())
            }
            assertEquals(stable, image())
            assertEquals(sent, pushes.get())
            assertEquals(priorPlanInserts + 1, native.inserts.count { it.stage == Stage.PLAN })
            assertEquals(retired, owner.isRetired)
            assertEquals(0, count("count_trust_root")); assertEquals(0, count("sync_outbox"))
        }
    }

    @Test fun actualLocalAndFreezeWritesRejectExtraBusinessDmlIncludingNoOpAndRollBackAllRows() = runBlocking<Unit> {
        val http = initialize()
        for (changed in listOf(false, true)) {
            val before = image()
            val touched = corruptAfter(Stage.ORIGIN) { sql -> changeMetric(sql, changed) }
            try {
                invariantRefusal("SYNC_RESTART_SIDE_EFFECT") { propose(http) }
                assertEquals(1, touched.get())
                assertEquals(before, image())
                assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
            } finally { native.afterInsert = null }
        }
        val reference = propose(http)
        for (changed in listOf(false, true)) {
            val before = image()
            val touched = corruptAfter(Stage.TRANSMISSION) { sql -> changeMetric(sql, changed) }
            try {
                invariantRefusal("SYNC_RESTART_SIDE_EFFECT") { restart(http).sendAndAccept(access(), reference.operationId) }
                assertEquals(1, touched.get())
                assertEquals(before, image())
                assertEquals(0, count("next_restart_materializations")); assertEquals(0, count("next_transmissions"))
                assertEquals(0, pushes.get())
            } finally { native.afterInsert = null }
        }
        assertEquals(NextRestartOutcome.COMMITTED, restart(http).sendAndAccept(access(), reference.operationId))
    }

    @Test fun realAckCannotAlterAnyOtherTrustTableOrCommandReceiptAndRetainsCommittedFreezeOnRollback() = runBlocking<Unit> {
        val http = initialize(); val reference = propose(http)
        val mutations = listOf(
            "count_trust_root" to "INSERT INTO count_trust_root VALUES(1,'binding',1,'validator','schema','root',0,0,0,0,X'01')",
            "count_trust_nodes" to "INSERT INTO count_trust_nodes VALUES('digest',X'01')",
            "count_trust_routes" to "INSERT INTO count_trust_routes VALUES('source','key',NULL,NULL,NULL,NULL,'role','hash')",
            "count_command_results" to "INSERT INTO count_command_results VALUES('command','input','fact',NULL,'{}','committed')"
        )
        for ((table, statement) in mutations) {
            frozenBeforeReply = null
            val touched = corruptAfter(Stage.ACCEPTANCE) { sql ->
                sql.execSQL(statement)
                sql.query("SELECT count(*) FROM `$table`").use { assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)) }
            }
            try {
                invariantRefusal { restart(http).sendAndAccept(access(), reference.operationId) }
                assertEquals(1, touched.get())
                assertEquals(requireNotNull(frozenBeforeReply), image())
                assertEquals(0, count("next_acceptances")); assertEquals(1, count("next_transmissions"))
                assertEquals(1, count("sync_outbox")); assertEquals(2, count("next_challenge_rounds"))
            } finally { native.afterInsert = null }
        }
        assertEquals(NextRestartOutcome.COMMITTED, restart(http).sendAndAccept(access(), reference.operationId))
        assertEquals(NextRestartOutcome.REPLAYED, restart(http).sendAndAccept(access(), reference.operationId))
        assertEquals(0, count("count_trust_root"))
    }

    @Test fun authenticatedPlanProofSideEffectsRollBackWholePageButKeepEarlierAckAndAllowSamePageRetry() = runBlocking<Unit> {
        val http = initialize(); val reference = propose(http)
        assertEquals(NextRestartOutcome.COMMITTED, restart(http).sendAndAccept(access(), reference.operationId))
        val (state, response) = page(http)
        for (business in listOf(true, false)) {
            val before = image()
            val touched = corruptAfter(Stage.PLAN) { sql ->
                if (business) changeMetric(sql, false) else {
                    sql.execSQL("INSERT INTO count_trust_nodes VALUES('plan-side-effect',X'02')")
                    sql.query("SELECT body FROM count_trust_nodes WHERE digest='plan-side-effect'").use {
                        assertTrue(it.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_BLOB, it.getType(0))
                        assertArrayEquals(byteArrayOf(2), it.getBlob(0))
                    }
                }
            }
            try {
                invariantRefusal(if (business) "SYNC_RESTART_SIDE_EFFECT" else null) { merger(http).page(access(), state, response) }
                assertEquals(1, touched.get())
                assertEquals(before, image())
                assertEquals(1, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
                assertEquals(0, count("next_restart_plan_proofs")); assertEquals(state, merger(http).state(access(), true))
            } finally { native.afterInsert = null }
        }
        merger(http).page(access(), state, response)
        assertEquals(1, count("next_restart_plan_proofs"))
        assertEquals(revision, db.withTransaction { NextRestartStore(db).acceptedPlan(access(), reference) }.first.getValue("revision").jsonPrimitive.long)
        assertEquals(1, pushes.get()); assertEquals(0, count("count_trust_root"))
    }

    private suspend fun invariantRefusal(expectedMessage: String? = null, block: suspend () -> Unit) {
        val failure = rejected(block)
        assertTrue("Expected an invariant refusal after the actual injected write, got $failure",
            failure is IllegalArgumentException || failure is IllegalStateException)
        if (expectedMessage != null) assertEquals(expectedMessage, failure.message)
    }
    private fun corruptAfter(stage: Stage, action: (SupportSQLiteDatabase) -> Unit): AtomicInteger {
        val reached = AtomicInteger()
        native.afterInsert = { actual, sql -> if (actual == stage) {
            native.afterInsert = null
            assertTrue(sql.inTransaction())
            action(sql) // Count the injection only after native SQL and its independent observation succeed.
            reached.incrementAndGet()
        } }
        return reached
    }
    private fun changeMetric(sql: SupportSQLiteDatabase, changed: Boolean) {
        val before = rawControl(sql)
        sql.execSQL(if (changed) "UPDATE metrics SET name='forbidden restart side effect'" else "UPDATE metrics SET name=name")
        // A changed metric also creates its real outbox row; a no-op still invalidates once.
        assertDelta(before, rawControl(sql), if (changed) 2 else 1)
        sql.query("SELECT name FROM metrics").use {
            assertTrue(it.moveToFirst())
            assertEquals(if (changed) "forbidden restart side effect" else metric.name, it.getString(0))
            assertFalse(it.moveToNext())
        }
    }

    private class RestartInsertFactory : SupportSQLiteOpenHelper.Factory {
        @Volatile var afterInsert: ((Stage, SupportSQLiteDatabase) -> Unit)? = null
        val inserts = CopyOnWriteArrayList<Inserted>()
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var nativeDatabase: SupportSQLiteDatabase? = null
            var adapted: SupportSQLiteDatabase? = null
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
                if (nativeDatabase !== raw) {
                    nativeDatabase = raw
                    adapted = object : SupportSQLiteDatabase by raw {
                        override fun compileStatement(sql: String): SupportSQLiteStatement {
                            val statement = raw.compileStatement(sql)
                            val text = sql.replace("`", "").replace("\"", "").lowercase(Locale.ROOT)
                            if (!text.startsWith("insert ")) return statement
                            val stage = Stage.entries.singleOrNull { "into ${it.table} " in text || "into ${it.table}(" in text }
                                ?: return statement
                            return object : SupportSQLiteStatement by statement {
                                private fun <T> observed(entry: String, body: () -> T): T {
                                    val before = rawControl(raw)
                                    val result = body()
                                    val after = rawControl(raw)
                                    inserts += Inserted(stage, entry, before, after)
                                    afterInsert?.invoke(stage, raw)
                                    return result
                                }
                                // Room 2.8.5's EntityInsertAdapter calls SQLiteStatement.step(),
                                // whose framework bridge uses execute(), even for INSERT.
                                // Each override calls the captured native delegate directly;
                                // never route through the other override and count it twice.
                                override fun execute() { observed("execute") { statement.execute() } }
                                override fun executeInsert(): Long = observed("executeInsert") { statement.executeInsert() }
                            }
                        }
                    }
                }
                return requireNotNull(adapted)
            }
            val original = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(wrap(db))
            }
            val delegate = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by delegate {
                override val writableDatabase get() = wrap(delegate.writableDatabase)
                override val readableDatabase get() = wrap(delegate.readableDatabase)
            }
        }
    }

    private companion object {
        /** Independent typed read; does not invoke the candidate control-comparison helper. */
        fun rawControl(sql: SupportSQLiteDatabase): Control = sql.query("SELECT id,storageId,generation,dirty FROM count_trust_control").use {
            check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 1L)
            check(it.getType(1) == Cursor.FIELD_TYPE_STRING && it.getType(2) == Cursor.FIELD_TYPE_INTEGER && it.getType(3) == Cursor.FIELD_TYPE_INTEGER)
            Control(it.getString(1), it.getLong(2), it.getLong(3)).also { _ -> check(!it.moveToNext()) }
        }
    }
}
