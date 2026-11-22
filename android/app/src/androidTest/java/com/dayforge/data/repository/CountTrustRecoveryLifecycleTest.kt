package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.*
import com.dayforge.domain.service.AccountIconController
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Complete lifecycle class, kept separate from three first-run large-capacity cases.
 * All methods retain the ordinary 150-second runner limit; no custom per-method deadline.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustRecoveryLifecycleTest : CountTrustRecoveryFixture() {
    private val cancelAfterCleanup = AtomicBoolean()
    private val cancellationReached = AtomicBoolean()
    private val cancelTarget = AtomicReference<Job?>()
    private val observedDeletes = ConcurrentHashMap.newKeySet<String>()
    private val discardedTables = setOf("count_trust_root", "count_trust_nodes", "count_trust_routes")
    private val callback = object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            if (!cancelAfterCleanup.get()) return
            val text = sqlQuery.trim().lowercase().replace("`", "").replace(Regex("\\s+"), " ")
            for (table in discardedTables) if (text == "delete from $table") observedDeletes.add(table)
            // This is the real fence read AFTER all cleanup SQL and owner retirement, before
            // the second fresh authentication call. Query observation has no maintenance lease.
            if (text == "select * from next_replica_fence order by id" &&
                requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired &&
                cancelAfterCleanup.compareAndSet(true, false)) {
                check(db.inTransaction() && observedDeletes == discardedTables)
                cancellationReached.set(true)
                requireNotNull(cancelTarget.get()).cancel(CancellationException("COUNT_TRUST_TEST_CANCEL_RECOVERY_CLEANUP"))
            }
        }
    }
    init { storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, callback) } }

    @Test fun finalFreshAuthenticationFailureRollsBackEveryCacheChange() = runBlocking<Unit> {
        register(); val access = access(); seedCache(); val before = localRows()
        decryptCalls = 0; failDecryptAt = 2
        assertNotNull(runCatching { preparation().discardCache(access) }.exceptionOrNull())
        failDecryptAt = Int.MAX_VALUE
        assertEquals(2, decryptCalls)
        assertEquals(before, localRows())
        assertTrue(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
    }

    @Test fun staleAuthenticationCannotRetireOrDiscardTheCurrentAccountCache() = runBlocking<Unit> {
        register(); val stale = access(); seedCache(); val before = localRows()
        tokens.saveLoginSession("new-access", "new-refresh", "member", id(1), false)
        assertNotNull(runCatching { preparation().preflight(stale) }.exceptionOrNull())
        assertEquals(before, localRows()); assertFalse(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
        assertNotNull(runCatching { preparation().discardCache(stale) }.exceptionOrNull())
        assertEquals(before, localRows())
        assertFalse(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
    }

    @Test fun unexpectedCacheTriggerCannotTurnRetirementIntoBusinessMutation() = runBlocking<Unit> {
        register(); val expected = access(); seedCache(); val before = localRows()
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TRIGGER unexpected_cache_effect AFTER DELETE ON count_trust_nodes BEGIN UPDATE metrics SET name='must-not-run'; END")
        }
        assertEquals("COUNT_TRUST_RECOVERY_SCHEMA_CHANGED", runCatching { preparation().preflight(expected) }.exceptionOrNull()?.message)
        assertEquals(before, localRows()); assertEquals(metric, db.metricDao().getMetricById(metric.id))
        val failure = runCatching { preparation().discardCache(expected) }.exceptionOrNull()
        assertEquals("COUNT_TRUST_RECOVERY_SCHEMA_CHANGED", failure?.message)
        assertEquals(before, localRows()); assertEquals(metric, db.metricDao().getMetricById(metric.id))
    }

    /** This one positive stage-precondition needs authenticated non-pending command truth.
     * Other fault cases deliberately retain the original seedCache's malformed result row.
     */
    private suspend fun seedDeliveredCommandAndOptionalCache() {
        tokens.getOrCreateInstallationId()
        // Actual metric-value commands do not edit appearance or open material storage.
        // Unexpected material access fails, rather than receiving fabricated permission.
        val materialRequested = AtomicBoolean()
        val icons = AccountIconController({
            materialRequested.set(true); error("RECOVERY_FIXTURE_UNEXPECTED_MATERIAL_ACCESS")
        }, tokens)
        try {
            val editor = NextObjectEditor(db, tokens, sessions, icons)
            val repository = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(),
                db.habitMetricLinkDao(), nextObjectEditor = editor)
            val selected = repository.getMetricForEditing(metric.id)
            val attempt = repository.beginIndependentValue(metric.id, 12.5, "recovery preservation",
                recordedAt = millis, authority = requireNotNull(selected.authority), expectedMetricUuid = metric.uuid)
            val reference = requireNotNull(attempt.reference)
            assertTrue(repository.resolveIndependentAttempt(attempt) is IndependentMetricResolution.Unregistered)
            assertEquals(0, count("count_command_results")); assertEquals(0, count("metric_logs"))
            val saved = repository.executeIndependentValue(attempt) // Original real T0 and T1.
            assertEquals(reference, saved.reference)
            val commands = CountCommandStore(db, tokens, sessions)
            val committed = commands.resolve(reference.native()).historical
            assertEquals(CountCommandRecords.Action.METRIC_APPEND, committed.intent.action)
            assertEquals(CountCommandRecords.Execution.APPLIED, committed.status.execution)
            assertEquals(CountCommandRecords.Delivery.RESULT_PENDING, committed.status.delivery)
            assertNotNull(committed.outcome)
            assertEquals(1L, db.withTransaction { CountCommandRecoveryInventory(db, tokens).pendingInTransaction(access()) })
            repository.acknowledgeIndependentValue(saved) { true } // Original local result T2, not network ACK.
            val delivered = commands.resolve(reference.native()).historical
            assertEquals(CountCommandRecords.Delivery.DELIVERED, delivered.status.delivery)
            assertEquals(committed.intent.text(), delivered.intent.text())
            assertEquals(requireNotNull(committed.outcome).text(), requireNotNull(delivered.outcome).text())
            assertEquals(1, count("count_command_results")); assertEquals(1, count("metric_logs"))
            assertEquals(1, count("sync_outbox"))
            assertEquals(0L, db.withTransaction { CountCommandRecoveryInventory(db, tokens).pendingInTransaction(access()) })
        } finally {
            withTimeout(5_000) { icons.awaitImages() }
            icons.close()
        }
        assertFalse(materialRequested.get())
        // Only optional derived rows are synthetic. Never insert/delete/re-sign primary
        // command rows, nodes, catalog/root, anchor or pending frontier to create this setup.
        db.withTransaction { CountTrustRuntime.maintenance(db) {
            val bytes = ByteArray(64) { 65 }.also { it[0] = 0; it[1] = 0 }
            db.openHelper.writableDatabase.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES(?,?)",
                arrayOf(CountTrustIndex.hash(bytes), bytes))
            db.openHelper.writableDatabase.execSQL("INSERT INTO count_trust_routes VALUES('completions','key',NULL,NULL,NULL,NULL,'derived','hash')")
        } }
        assertEquals(1, count("count_command_results"))
        assertEquals(0L, db.withTransaction { CountCommandRecoveryInventory(db, tokens).pendingInTransaction(access()) })
    }

    @Test fun frozenFenceAndActiveStageKeepAllCacheAndCommandBytes() = runBlocking<Unit> {
        register(); seedDeliveredCommandAndOptionalCache(); val access = access()
        val native = CountConsumerReadFixtureAssertions(db, tokens, sessions)
        suspend fun commandRows() = native.capture().filterKeys { it.startsWith("count_command_") }
        val commands = commandRows()
        assertEquals(setOf("count_command_intents", "count_command_status", "count_command_results",
            "count_command_nodes", "count_command_recovery_roots"), commands.keys)
        assertEquals(1, commands.getValue("count_command_results").size)
        val fence = NextReplicaFenceEntity(id(701), id(1), id(2), id(3), id(704),
            access.session.authentication.generation, requireNotNull(access.deviceId), requireNotNull(access.capabilityRevision).toLong(), "a".repeat(64))
        NextReplicaWriteFence(db).acquire(fence)
        val frozen = localRows()
        assertEquals(commands, commandRows())
        assertNotNull(runCatching { preparation().preflight(access) }.exceptionOrNull())
        assertEquals(frozen, localRows()); assertEquals(commands, commandRows())
        assertNotNull(runCatching { preparation().discardCache(access) }.exceptionOrNull())
        assertEquals(frozen, localRows()); assertEquals(commands, commandRows())
        NextReplicaWriteFence(db).closeBeforeCommit(fence)
        val installation = tokens.getOrCreateInstallationId()
        val stage = ReplicaSwitchStage(id(705), id(1), id(2), id(3), id(704), access.session.authentication.generation,
            requireNotNull(access.deviceId), requireNotNull(access.capabilityRevision), access.capabilities.sorted(), installation,
            "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), 0)
        NextReplicaStageStore(db, tokens, sessions).begin(stage)
        val staged = localRows()
        assertEquals(commands, commandRows())
        assertNotNull(runCatching { preparation().preflight(access) }.exceptionOrNull())
        assertEquals(stage, tokens.activeReplicaStage()); assertEquals(staged, localRows())
        assertEquals(commands, commandRows())
        assertNotNull(runCatching { preparation().discardCache(access) }.exceptionOrNull())
        assertEquals(stage, tokens.activeReplicaStage()); assertEquals(staged, localRows())
        assertEquals(commands, commandRows())
    }

    @Test fun cancellationAfterCacheCleanupRollsBackAllBytesAndKeepsTheOwnerRetired() = runBlocking<Unit> {
        register(); val expected = access(); seedCache()
        // Synthetic local certificate bytes are not an issued trust root. Explicit retirement
        // must still roll back their deletion along with nodes, routes, control and commands.
        db.withTransaction { CountTrustRuntime.maintenance(db) {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO count_trust_root(id,binding,format,validator,schemaHash,rootHash,rootHeight,rootCount,revision,generation,mac) " +
                    "VALUES(1,'retained',1,'retained',?,?,0,1,1,0,?)",
                arrayOf<Any>("a".repeat(64), "b".repeat(64), byteArrayOf(1, 2, 3)))
        } }
        val before = localRows(); val beforeImage = raw().hash
        val runtime = requireNotNull(CountTrustRuntime.forDatabase(db))
        val epoch = runtime.generation
        val published = AtomicBoolean()
        decryptCalls = 0
        supervisorScope {
            val job = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                preparation().discardCache(expected); published.set(true)
            }
            cancelTarget.set(job); cancelAfterCleanup.set(true)
            val failure = runCatching { job.await() }.exceptionOrNull()
            assertTrue("Cleanup cancellation must propagate, not report success: $failure", failure is CancellationException)
        }
        assertTrue("Fault must reach the actual post-cleanup SQL boundary", cancellationReached.get())
        assertEquals(discardedTables, observedDeletes)
        assertEquals("Cancellation must precede final credential decryption", 1, decryptCalls)
        assertFalse(published.get())
        assertEquals(before, localRows()); assertEquals(beforeImage, raw().hash)
        assertTrue(runtime.isRetired); assertEquals(epoch, runtime.generation)
        cancelTarget.set(null)
    }
}
