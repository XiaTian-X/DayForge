package com.dayforge.data.repository

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
import com.dayforge.domain.model.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real generated Room + production controlled factory and permanent triggers. The delegate
 * injects native corruption only for negative cases, after a real marker UPDATE has completed;
 * it never provides an authority, root, business result or replacement ACK implementation.
 */
@RunWith(AndroidJUnit4::class)
class NextChallengeTrustSideEffectTest : NextCoreRequestFixture() {
    private val fault = MarkerFaultFactory()
    private val json = Json { encodeDefaults = true }
    init {
        storage.isolatedBuilder = { context ->
            val factory = CountTrustOpenHelperFactory(context, fault)
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .openHelperFactory(factory).addCallback(SyncSchemaCallback).build()
                .also { CountTrustRuntime.attach(it, factory) }
        }
    }
    private fun initial(activity: String) = ChallengeRoundRecord(initialChallengeRoundHead(activity), null, null, null)
    private fun restarted() = ChallengeRoundRecord(ChallengeRoundHead(habit.uuid, id(301), 1), id(4), id(302),
        ChallengeRestartIntent(habit.uuid, id(301), initialChallengeRoundUuid(habit.uuid), 0, 1))
    private fun metadata(restart: Boolean = false, birth: Boolean = false) = ChallengeMetadata(1,
        listOf(ChallengeCheckpoint(if (restart) restarted().head else initial(habit.uuid).head,
            if (restart) listOf(initial(habit.uuid), restarted()) else listOf(initial(habit.uuid))),
            ChallengeCheckpoint(initial(timerHabit.uuid).head, listOf(initial(timerHabit.uuid)))),
        if (birth) listOf(ChallengeBirth("activity_event", id(210), initial(habit.uuid).head)) else emptyList())
    private fun canonical(type: String, identity: String, body: JsonObject) = SyncV2Change(0, type, identity, "upsert", 1,
        JsonObject(body + mapOf("public_id" to JsonPrimitive(identity), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)

    private suspend fun initialize(): NextSyncHttp {
        register()
        val http = channel { input ->
            if (input.path.endsWith("/identity")) reply(input)
            else {
                assertEquals("/api/v2/sync/rounds/push", input.path)
                val result = json.decodeFromString<NextSyncPushResponse>(successReply(input).bytes.toString(Charsets.UTF_8))
                val meta = metadata()
                MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(result.results, 1,
                    meta.checkpoints, meta.births)).toByteArray())
            }
        }.first
        val requests = sender(http)
        val timers = NextTimerRequestStore(db, tokens, sessions, requests)
        NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions,
            OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timers), timers)
            .bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(
                canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
                canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
                canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))), 20, time, emptyList(), 1,
                metadata().checkpoints, emptyList()))
        return http
    }
    private suspend fun queuedObservation(): String {
        val writer = producer()
        writer.writeRounds(writer.captureRounds()) { metrics().recordValue(metric.id, 21.125, "side-effect proof", millis) }
        return db.syncOutboxDao().getAll().last().operationId
    }
    private suspend fun contents() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun control() = db.withTransaction { requireNotNull(CountTrustRecoveryControl.read(db)) }
    private suspend fun ack(incoming: ChallengeMetadata = metadata()) =
        db.withTransaction { NextChallengeStore(db).acknowledgeInTransaction(access(), incoming) }
    private suspend fun retire() = db.withTransaction {
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        owner.maintenance(db) { owner.retireFastPathForRecovery() }
    }
    private suspend fun markerFault(statement: String) {
        val before = contents()
        val reached = AtomicInteger()
        fault.afterMarker = { native -> native.execSQL(statement); reached.incrementAndGet() }
        try {
            rejected { ack() }
            assertEquals("The real marker UPDATE must have completed before injecting corruption", 1, reached.get())
            assertEquals(before, contents())
        } finally { fault.afterMarker = null }
    }

    @Test fun realRoundAckAndReplayWorkBeforeAndAfterSoftRetirement() = runBlocking<Unit> {
        val http = initialize()
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        for (retired in listOf(false, true)) {
            if (retired) retire()
            assertEquals(retired, owner.isRetired)
            val request = queuedObservation()
            val original = requireNotNull(originalIntent(db.syncOutboxDao().getAll().single()))
            val store = sender(http)
            val delivery = requireNotNull(store.sendOperation(access(), request))
            assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
            assertEquals(NextOperationAcceptance.REPLAYED, store.acceptOperation(delivery))
            assertEquals(original, db.nextRequestDao().origin(NEXT_OPERATION, request))
            assertEquals(0, count("sync_outbox"))
            assertEquals(retired, owner.isRetired)
            assertEquals(0, count("count_trust_root"))
        }
        assertEquals(2, count("next_acceptances"))
    }

    @Test fun exactNewRoundBirthAndSameValueMarkerDmlKeepAllOtherTablesUntouched() = runBlocking<Unit> {
        initialize()
        val original = contents()
        val before = control()
        assertEquals(metadata(true, true), ack(metadata(true, true)))
        val after = control()
        assertEquals(before.storageId, after.storageId)
        assertEquals(before.generation + 3L, after.generation) // One round, one birth, one marker UPDATE.
        assertEquals(1L, after.dirty)
        val changed = setOf("next_challenge_rounds", "next_challenge_births", "next_challenge_state", "count_trust_control")
        val current = contents()
        assertEquals(original.filterKeys { it != "tables" }, current.filterKeys { it != "tables" })
        assertEquals(original.getValue("tables").jsonObject.filterKeys { it !in changed },
            current.getValue("tables").jsonObject.filterKeys { it !in changed })
        retire()
        assertEquals(metadata(true, true), ack(metadata(true, true)))
        assertEquals(after.copy(generation = after.generation + 1L), control())
        assertTrue(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
        assertEquals(0, count("count_trust_root"))
    }

    @Test fun postMarkerControlIdentityTypesMembershipAndExactDeltaCannotBeForged() = runBlocking<Unit> {
        initialize()
        for (statement in listOf(
            "UPDATE count_trust_control SET storageId='00000000-0000-4000-8000-000000000001'",
            "UPDATE count_trust_control SET storageId=CAST(storageId AS BLOB)",
            "UPDATE count_trust_control SET generation=generation+1",
            "UPDATE count_trust_control SET generation=generation-1",
            "UPDATE count_trust_control SET generation=0.5",
            "UPDATE count_trust_control SET dirty=0",
            "UPDATE count_trust_control SET dirty=0.5",
            "UPDATE count_trust_control SET dirty=2",
            "UPDATE count_trust_control SET id=2",
            "DELETE FROM count_trust_control",
            "INSERT INTO count_trust_control VALUES(2,'00000000-0000-4000-8000-000000000001',0,1)"
        )) markerFault(statement)
        assertEquals(metadata(), ack())
    }

    @Test fun invalidInitialControlAndGenerationOverflowRejectBeforeAnyMarkerUpdate() = runBlocking<Unit> {
        initialize()
        for (statement in listOf("UPDATE count_trust_control SET generation=9223372036854775807",
            "UPDATE count_trust_control SET generation=-1", "UPDATE count_trust_control SET dirty=0.5",
            "DELETE FROM count_trust_control")) {
            val before = contents(); val updates = fault.markerUpdates.get()
            val installed = AtomicInteger()
            rejected { db.withTransaction {
                fault.native().execSQL(statement)
                installed.incrementAndGet()
                NextChallengeStore(db).acknowledgeInTransaction(access(), metadata())
            } }
            assertEquals(1, installed.get())
            assertEquals(updates, fault.markerUpdates.get())
            assertEquals(before, contents())
        }
    }

    @Test fun missingExtraAndModifiedPermanentDirtyTriggersRejectBeforeSidecarWrites() = runBlocking<Unit> {
        initialize()
        val name = "${CountTrustSchema.DIRTY_PREFIX}next_challenge_state_update"
        val expected = CountTrustSchema.dirtyTriggers.getValue(name)
        val variants = listOf(
            listOf("DROP TRIGGER `$name`"),
            listOf("CREATE TRIGGER `${CountTrustSchema.DIRTY_PREFIX}unexpected` AFTER UPDATE ON next_challenge_state BEGIN SELECT 1; END"),
            listOf("DROP TRIGGER `$name`", expected.replace("generation=generation+1", "generation=generation+2"))
        )
        for (statements in variants) {
            val before = contents(); val updates = fault.markerUpdates.get()
            val installed = AtomicInteger()
            rejected { db.withTransaction {
                statements.forEach(fault.native()::execSQL)
                installed.incrementAndGet()
                NextChallengeStore(db).acknowledgeInTransaction(access(), metadata())
            } }
            assertEquals(1, installed.get())
            assertEquals(updates, fault.markerUpdates.get())
            assertEquals(before, contents())
        }
        assertEquals(metadata(), ack())
    }

    @Test fun additionalSidecarAndBusinessWritesIncludingNoOpCannotHideInControlDelta() = runBlocking<Unit> {
        initialize()
        for (statement in listOf("UPDATE next_challenge_rounds SET recordHash=recordHash",
            "UPDATE metrics SET name=name", "UPDATE metrics SET name='forbidden side effect'")) {
            val before = contents(); val updates = fault.markerUpdates.get()
            rejected { db.withTransaction {
                // Native test corruption, then ordinary DAO ACK. Both DDL and DML must roll back.
                fault.native().execSQL("CREATE TRIGGER ack_side_effect AFTER UPDATE ON next_challenge_state BEGIN $statement; END")
                NextChallengeStore(db).acknowledgeInTransaction(access(), metadata())
            } }
            assertTrue(fault.markerUpdates.get() > updates)
            assertEquals(before, contents())
        }
        assertEquals(metadata(), ack())
    }

    @Test fun allOtherTrustTablesAndCommandReceiptsKeepTheirCompleteSideEffectProof() = runBlocking<Unit> {
        initialize()
        for (statement in listOf(
            "INSERT INTO count_trust_root VALUES(1,'binding',1,'validator','schema','root',0,0,0,0,X'01')",
            "INSERT INTO count_trust_nodes VALUES('digest',X'01')",
            "INSERT INTO count_trust_routes VALUES('source','key',NULL,NULL,NULL,NULL,'role','hash')",
            "INSERT INTO count_command_results VALUES('command','input','fact',NULL,'{}','committed')"
        )) markerFault(statement)
        assertEquals(metadata(), ack())
    }

    @Test fun originalMarkerAndRoundValidationStillRejectCorruptionAndAllowCleanRetry() = runBlocking<Unit> {
        initialize()
        markerFault("UPDATE next_challenge_state SET metadataHash='${"0".repeat(64)}'")
        markerFault("UPDATE next_challenge_rounds SET recordJson='{}' WHERE roundUuid='${initial(habit.uuid).head.roundUuid}'")
        assertEquals(metadata(), ack())
        assertEquals(0, count("count_trust_root"))
    }

    @Test fun preparedRealRoundAckCannotCommitAfterFreshAuthorityIsRevoked() = runBlocking<Unit> {
        val http = initialize(); val request = queuedObservation()
        val store = sender(http); val delivery = requireNotNull(store.sendOperation(access(), request))
        val before = contents()
        tokens.saveDeviceRegistration(id(4), setOf("sync.read"), true, 2)
        rejected { store.acceptOperation(delivery) }
        assertEquals(before, contents())
        assertEquals(1, count("sync_outbox")); assertEquals(0, count("next_acceptances"))
    }

    private class MarkerFaultFactory : SupportSQLiteOpenHelper.Factory {
        @Volatile var afterMarker: ((SupportSQLiteDatabase) -> Unit)? = null
        val markerUpdates = AtomicInteger()
        private var rawDatabase: SupportSQLiteDatabase? = null
        fun native(): SupportSQLiteDatabase = requireNotNull(rawDatabase)
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var adapted: SupportSQLiteDatabase? = null
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
                if (rawDatabase !== raw) {
                    rawDatabase = raw
                    adapted = object : SupportSQLiteDatabase by raw {
                        override fun compileStatement(sql: String): SupportSQLiteStatement {
                            val original = raw.compileStatement(sql)
                            val text = sql.replace("`", "").lowercase(Locale.ROOT)
                            if (!text.startsWith("update ") || " next_challenge_state set " !in text) return original
                            return object : SupportSQLiteStatement by original {
                                private fun observed() {
                                    markerUpdates.incrementAndGet()
                                    afterMarker?.also { afterMarker = null }?.invoke(raw)
                                }
                                // Room 2.8's generated SQLiteStatement.step() reaches execute(),
                                // then reads SELECT changes(). Legacy adapters use UpdateDelete.
                                // Both delegate directly to the original statement: one hook per
                                // successful execution, never during compilation or binding.
                                override fun execute() {
                                    original.execute()
                                    observed()
                                }
                                override fun executeUpdateDelete(): Int {
                                    val changed = original.executeUpdateDelete()
                                    observed()
                                    return changed
                                }
                            }
                        }
                    }
                }
                return requireNotNull(adapted)
            }
            val parent = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
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
