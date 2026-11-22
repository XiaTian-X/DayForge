package com.dayforge.data.repository

import com.dayforge.BuildConfig
import com.dayforge.domain.service.CountCommandCostTiming
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRetirementReason
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import io.mockk.mockk
import io.mockk.coEvery
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real formal parent + two-metric T1, fresh source read, actual DataStore actor and original
 * native metadata T2. No performance claim or stand-in for actual UI/Host/kill verification. */
@RunWith(AndroidJUnit4::class)
class CountCommandFormalMetricDeliverySequenceTest : NextObjectEditorFixture() {
    /** Test-only collector propagation; never changes the admitted Job or dispatcher. */
    private class T2CostContext(private val trace: CountCommandCostTiming.Trace) :
        AbstractCoroutineContextElement(Key), ThreadContextElement<CountCommandCostTiming.Trace?> {
        override fun updateThreadContext(context: CoroutineContext) = CountCommandCostTiming.install(trace)
        override fun restoreThreadContext(context: CoroutineContext, oldState: CountCommandCostTiming.Trace?) =
            CountCommandCostTiming.restore(oldState)
        companion object Key : CoroutineContext.Key<T2CostContext>
    }
    private val fault = CountObsoleteFollowUpNativeBoundary()
    private val rejectAfterRoot = java.util.concurrent.atomic.AtomicBoolean()
    private val finalDecrypts = java.util.concurrent.atomic.AtomicInteger()
    override fun tokenCipher() = object : com.dayforge.data.local.TokenCipher {
        override fun encrypt(value: String) = value
        override fun decrypt(value: String): String? {
            if (rejectAfterRoot.get() && fault.rootWrites.get() > 0) {
                finalDecrypts.incrementAndGet(); return null
            }
            return value
        }
    }
    init { storage.isolatedBuilder = { buildDiagnosticFaultDatabase(it, fault) } }
    private lateinit var parent: CountCommandReceipt
    private lateinit var child: CountCommandReceipt
    private lateinit var source: WidgetFactSnapshot
    private lateinit var actor: ActorGate
    private fun reader() = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
    private fun owner() = CountCommandStore(db, tokens, sessions)
    private suspend fun row(ref: CountCommandReference) = owner().resolve(ref.native()).historical
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun prefImage() = dataStore.data.first().asMap()

    /** Equality still covers the entire schema, every table and native cell. Only the
     * failure rendering is bounded, so Android's 64 KiB XML limit cannot hide the difference. */
    private fun assertImageUnchanged(expected: JsonObject, actual: JsonObject) {
        if (expected == actual) return
        val differences = arrayListOf<String>()
        fun summary(value: JsonElement?): String = when (value) {
            null -> "<missing>"
            is JsonObject -> "object(keys=${value.size})"
            is JsonArray -> "array(rows=${value.size})"
            else -> value.toString().take(96)
        }
        fun visit(left: JsonElement?, right: JsonElement?, path: String) {
            if (left == right || differences.size >= 8) return
            when {
                left is JsonObject && right is JsonObject ->
                    (left.keys + right.keys).sorted().forEach { visit(left[it], right[it], "$path/$it") }
                left is JsonArray && right is JsonArray -> {
                    if (left.size != right.size) differences += "$path/length: ${left.size} -> ${right.size}"
                    for (index in 0 until maxOf(left.size, right.size))
                        visit(left.getOrNull(index), right.getOrNull(index), "$path/$index")
                }
                else -> differences += "$path: ${summary(left)} -> ${summary(right)}"
            }
        }
        visit(expected, actual, "")
        fail("Complete native image differs (first paths only): ${differences.joinToString("; ")}")
    }
    private fun businessImage(contents: JsonObject): JsonObject {
        val excluded = setOf("count_command_intents", "count_command_status", "count_command_results", "count_command_nodes", "count_command_recovery_roots")
        val tables = contents.getValue("tables").jsonObject
        val controls = tables.getValue("count_trust_control").jsonArray.map { JsonObject(it.jsonObject - "commandRootHash") }
        return JsonObject(contents + ("tables" to JsonObject(tables.filterKeys { it !in excluded } +
            ("count_trust_control" to JsonArray(controls)))))
    }
    private suspend fun immutableGroup() = listOf(parent.reference, child.reference).map { ref ->
        row(ref).let { it.intent.text() to requireNotNull(it.outcome).text() }
    }
    private suspend fun pending(): Boolean = habit.uuid in preferences.pendingMetricHabitUuids(local(), setOf(habit.uuid)).first()
    override fun isolatedDataStore(data: DataStore<Preferences>): DataStore<Preferences> = ActorGate(data).also { actor = it }

    private suspend fun group() {
        tokens.getOrCreateInstallationId(); register()
        val second = db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val candidate = metric.copy(id = 0, uuid = UUID.randomUUID().toString(), name = "Second delivery metric")
            val result = candidate.copy(id = db.metricDao().insert(candidate))
            for (m in listOf(metric, result)) db.habitMetricLinkDao().insertOrIgnore(HabitMetricLinkEntity(
                habitId = habit.id, habitUuid = habit.uuid, metricId = m.id, metricUuid = m.uuid, promptOnComplete = true))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            result
        }
        parent = requireNotNull(creatingHabits().logCompletionWithReceipt(app, habit.id,
            authority = requireNotNull(editor.habit(habit.id).authority), expectedHabitUuid = habit.uuid).command)
        source = reader().read(requireNotNull(db.habitDao().getVisibleHabitById(habit.id)))
        val targets = editor.metrics(listOf(metric.id, second.id))
        child = requireNotNull(creatingMetrics().recordValuesWithReceipt(listOf(MetricValueDraft(metric.id, -0.0, "first"),
            MetricValueDraft(second.id, 4.5, "second")), millis, authority = requireNotNull(targets.first().authority),
            expectedMetrics = targets.map { requireNotNull(it.value) }, promptHabit = source.habit,
            promptFact = source.claim, promptCompletionSource = true, parentCommand = parent).command)
        preferences.addPendingMetricHabit(source.claim.session(), habit.uuid)
        assertEquals(2, count("metric_logs")); assertEquals(1, count("completions")); assertTrue(pending())
        // Original display qualification may persist its COUNT root before its SELECT-only
        // phase. Establish that legitimate source state before the later cancellation/T2
        // full-image baseline; the tested delivery still performs its own fresh source read.
        // This stays inside each original 150-second method, with no preference callback.
        prepareOriginalSourceForDelivery()
        // Executes in the original DataStore transform, after the original source read returned.
        actor.beforeNextReturn = { CountCommandEntryBoundary.requireTopLevel(db); assertFalse(db.inTransaction()) }
    }
    private suspend fun prepareOriginalSourceForDelivery() {
        val cold = image()
        val coldTables = cold.getValue("tables").jsonObject
        val coldControl = coldTables.getValue("count_trust_control").jsonArray.single().jsonObject
        assertEquals(1, coldControl.getValue("dirty").jsonObject.getValue("value").jsonPrimitive.int)
        assertTrue(coldTables.getValue("count_trust_root").jsonArray.isEmpty())
        assertTrue(coldTables.getValue("count_trust_nodes").jsonArray.isEmpty())
        // Existing publisher only: complete its real qualification/read transaction and END.
        // The empty callback cannot remove preferences or acknowledge either command.
        val preferencesBefore = prefImage()
        reader().publishCompletion(source) { }
        val prepared = image()
        val preparedTables = prepared.getValue("tables").jsonObject
        assertEquals(cold.getValue("user_version"), prepared.getValue("user_version"))
        assertEquals(cold.getValue("schema"), prepared.getValue("schema"))
        val expectedControl = JsonObject(coldControl + ("dirty" to buildJsonObject {
            put("sqlite_type", 1); put("value", 0)
        }))
        assertEquals(expectedControl, preparedTables.getValue("count_trust_control").jsonArray.single())
        assertEquals(1, preparedTables.getValue("count_trust_root").jsonArray.size)
        assertTrue(preparedTables.getValue("count_trust_nodes").jsonArray.isNotEmpty())
        // Explicit original command and business evidence, including all native columns.
        // These checks do not replace the later complete-schema/all-table equality oracle.
        for (table in listOf("count_command_intents", "count_command_status", "count_command_results",
            "count_command_nodes", "count_command_recovery_roots", "habits", "metrics", "habit_metric_links",
            "count_days", "completions", "metric_logs", "sync_outbox", "next_request_origins", "local_fact_submissions")) {
            assertEquals("Original source preparation changed $table", coldTables.getValue(table), preparedTables.getValue(table))
        }
        assertEquals(preferencesBefore, prefImage())
    }

    private suspend fun deliver(read: WidgetFactReader = reader(), current: () -> Boolean = { true }) =
        owner().deliverFormalMetricResult(parent, child, source, read, current)
    private suspend fun delivered() {
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(parent.reference).status.delivery)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(child.reference).status.delivery)
        assertEquals(2, count("metric_logs")); assertEquals(1, count("completions")); assertFalse(pending())
    }
    private suspend fun unresolved() {
        assertEquals(CountCommandRecords.Delivery.METRICS_COMMITTED, row(parent.reference).status.delivery)
        assertTrue(row(parent.reference).status.pending); assertTrue(row(child.reference).status.pending)
        assertEquals(2, count("metric_logs")); assertEquals(1, count("completions"))
    }
    private fun reopen() {
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons)
        creator = NextObjectCreator(db, tokens, sessions, icons)
    }

    @Test(timeout = 150_000) fun queuedReaderAndLogoutRunAfterOriginalT2WithoutAnInterphaseTurn() = runBlocking<Unit> {
        group()
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        fun event(stage: String) {
            android.util.Log.i("MetricDeliveryQueue", "event=$stage elapsed_ns=${android.os.SystemClock.elapsedRealtimeNanos() - started}")
        }
        val gate = actor.arm(::event); val order = mutableListOf<String>()
        event("fixture_ready")
        // started is a diagnostic alignment key for the existing queue markers, not a business ID.
        val costs = if (BuildConfig.DEBUG) CountCommandCostTiming.Trace(started) else null
        val costContext: CoroutineContext = if (costs != null) T2CostContext(costs) else EmptyCoroutineContext
        try {
            supervisorScope {
                val operation = async(context = costContext) {
                    event("operation_begin")
                    try { deliver(); order += "delivery"; event("operation_return") }
                    catch (error: Throwable) { event("operation_throw_${error.javaClass.simpleName}"); throw error }
                }
                try {
                    event("entered_window_begin")
                    try { withTimeout(5_000) { gate.entered.await() } }
                    catch (error: Throwable) { event("entered_window_throw_${error.javaClass.simpleName}"); throw error }
                    event("entered_window_return")
                    val queued = async(start = CoroutineStart.UNDISPATCHED) {
                        event("reader_request")
                        try {
                            sessions.exclusive {
                                event("reader_enter")
                                db.withTransaction {
                                    val delivery = db.openHelper.writableDatabase.query(
                                        "SELECT delivery FROM count_command_status WHERE commandId=?", arrayOf(parent.commandId)).use {
                                        check(it.moveToFirst()); it.getString(0)
                                    }
                                    event("reader_observed_$delivery")
                                    assertEquals("DELIVERED", delivery)
                                }
                                order += "reader"
                            }
                            event("reader_return")
                        } catch (error: Throwable) { event("reader_throw_${error.javaClass.simpleName}"); throw error }
                    }
                    val logout = async(start = CoroutineStart.UNDISPATCHED) {
                        event("logout_request")
                        try {
                            sessions.exclusive {
                                event("logout_enter")
                                order += "logout"; tokens.clearTokens()
                                event("logout_clear_return")
                            }
                            event("logout_return")
                        } catch (error: Throwable) { event("logout_throw_${error.javaClass.simpleName}"); throw error }
                    }
                    assertFalse(queued.isCompleted); assertFalse(logout.isCompleted)
                    event("gate_release")
                    gate.release.complete(Unit)
                    event("joined_window_begin")
                    try { withTimeout(5_000) {
                        operation.await(); event("operation_await_return")
                        queued.await(); event("reader_await_return")
                        logout.await(); event("logout_await_return")
                    } } catch (error: Throwable) { event("joined_window_throw_${error.javaClass.simpleName}"); throw error }
                    event("joined_window_return")
                } finally { event("finally_release"); gate.release.complete(Unit) }
            }
            assertEquals(listOf("reader", "logout"), order.filter { it != "delivery" })
            assertTrue("The complete delivery call also returned", "delivery" in order)
            assertNull(tokens.localCoreWriteAccess())
            assertNotNull(rejected { deliver() })
            event("test_return")
        } finally { costs?.flush() }
    }

    @Test(timeout = 150_000) fun ordinarySourceUndoQueuedDuringPreferencesRunsOnlyAfterT2AndCannotBeRepublished() = runBlocking<Unit> {
        group(); val fact = requireNotNull(db.completionDao().getCompletionByUuid(requireNotNull(parent.factUuid)))
        val gate = actor.arm()
        supervisorScope {
            val operation = async { deliver() }
            try {
                withTimeout(5_000) { gate.entered.await() }
                // editor.habit queues its real account turn before its first database read.
                val mutation = async(start = CoroutineStart.UNDISPATCHED) {
                    val current = editor.habit(habit.id)
                    assertEquals(CountCommandRecords.Delivery.DELIVERED, row(parent.reference).status.delivery)
                    creatingHabits().undoCompletion(app, fact.id, authority = requireNotNull(current.authority))
                }
                assertFalse(mutation.isCompleted)
                gate.release.complete(Unit)
                operation.await(); mutation.await()
            } finally { gate.release.complete(Unit) }
        }
        val before = image(); val prefs = prefImage()
        assertNotNull(rejected { deliver() })
        assertImageUnchanged(before, image()); assertEquals(prefs, prefImage())
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(parent.reference).status.delivery)
        assertEquals(2, count("metric_logs"))
    }

    @Test(timeout = 150_000) fun cancellationAfterOriginalPreferenceTransformKeepsLockAndDoesNotChangeT2Metadata() = runBlocking<Unit> {
        group(); val before = image(); val gate = actor.arm(); var returned = false
        supervisorScope {
            val operation = async { deliver(); returned = true }
            try {
                withTimeout(5_000) { gate.entered.await() }
                operation.cancel(CancellationException("FORMAL_DELIVERY_CANCELLED"))
                // Same runBlocking event loop: let its cancellation continuation run while
                // the actual transform gate is still closed before sampling lock ownership.
                yield()
                assertTrue(sessions.tryMaintenance { error("released before real actor completion") }
                    is AccountSessionCoordinator.MaintenanceAttempt.Busy)
                assertFalse(operation.isCompleted)
            } finally { gate.release.complete(Unit) }
            withTimeout(5_000) { operation.join() }
            assertTrue(operation.isCancelled)
        }
        assertFalse(returned); assertImageUnchanged(before, image()); assertFalse(pending()); unresolved()
        val sameParent = parent.reference; val sameChild = child.reference
        reopen(); val fresh = owner(); val proof = requireNotNull(fresh.completedMetricGroup(sameParent.native(), preferences))
        fresh.deliverCompletedMetricGroup(proof, preferences) { true }
        assertEquals(sameParent, parent.reference); assertEquals(sameChild, child.reference); delivered()
    }

    @Test(timeout = 150_000) fun consumerAndRealRevokedSourceLeavePreferencesAndCommandRowsUntouched() = runBlocking<Unit> {
        group(); var before = image(); var prefs = prefImage()
        assertEquals("FACT_PROMPT_EXPIRED", rejected { deliver(current = { false }) }.message)
        assertImageUnchanged(before, image()); assertEquals(prefs, prefImage()); unresolved()
        val fact = requireNotNull(db.completionDao().getCompletionByUuid(requireNotNull(parent.factUuid)))
        creatingHabits().undoCompletion(app, fact.id, authority = requireNotNull(editor.habit(habit.id).authority))
        before = image(); prefs = prefImage()
        assertNotNull(rejected { deliver() })
        assertImageUnchanged(before, image()); assertEquals(prefs, prefImage())
        assertTrue(row(parent.reference).status.pending); assertTrue(row(child.reference).status.pending)
    }

    @Test(timeout = 150_000) fun exactSourceScopeRejectsForeignOwnersChildJobAndPostLifetimeReuse() = runBlocking<Unit> {
        group(); val before = businessImage(image()); val immutable = immutableGroup()
        val sourceReader = mockk<WidgetFactReader>()
        val actualReader = reader()
        lateinit var captured: CountCommandStore.FormalMetricDeliveryScope
        coEvery { sourceReader.publishFormalMetricPreference(any(), any(), any()) } coAnswers {
            captured = firstArg()
            val foreignDatabase = androidx.room.Room.inMemoryDatabaseBuilder(
                app, com.dayforge.data.local.HabitDatabase::class.java).build()
            try {
                assertNotSame(db, foreignDatabase)
                assertFalse(foreignDatabase.isOpen)
                assertEquals("COMMAND_METRIC_SEQUENCE_FOREIGN_SOURCE", rejected {
                    CountCommandStore.requireFormalMetricSource(captured, foreignDatabase, tokens, sessions)
                }.message)
                assertFalse(foreignDatabase.isOpen)
            } finally { foreignDatabase.close() }
            val foreignPreferences = com.dayforge.data.local.PreferencesManager(dataStore)
            val foreignReader = WidgetFactReader(db, tokens, sessions, foreignPreferences, CountHistoryReader(db, tokens, sessions))
            assertEquals("COMMAND_METRIC_PREFERENCE_OWNER_CHANGED", rejected {
                foreignReader.publishFormalMetricPreference(captured, source) { true }
            }.message)
            val foreignTokens = TokenManager(dataStore)
            assertEquals("COMMAND_METRIC_SEQUENCE_FOREIGN_SOURCE", rejected {
                CountCommandStore.requireFormalMetricSource(captured, db, foreignTokens, sessions)
            }.message)
            assertEquals("COMMAND_METRIC_SEQUENCE_FOREIGN_SOURCE", rejected {
                CountCommandStore.requireFormalMetricSource(captured, db, tokens, AccountSessionCoordinator())
            }.message)
            supervisorScope {
                val other = async { rejected { CountCommandStore.requireFormalMetricSource(captured, db, tokens, sessions) } }
                assertEquals("COMMAND_METRIC_SEQUENCE_EXPIRED", other.await().message)
            }
            // Call a separate real participant normally. Suspend-spy callOriginal may
            // replay the intercepted continuation instead of creating this lexical call.
            actualReader.publishFormalMetricPreference(captured, secondArg(), thirdArg())
        }
        deliver(sourceReader); delivered()
        assertImageUnchanged(before, businessImage(image())); assertEquals(immutable, immutableGroup())
        assertEquals("COMMAND_METRIC_SEQUENCE_EXPIRED", rejected {
            CountCommandStore.requireFormalMetricSource(captured, db, tokens, sessions)
        }.message)
    }

    @Test(timeout = 150_000) fun persistedCredentialRemovalAfterPreferencesIsRejectedAtFreshT2Entry() = runBlocking<Unit> {
        group(); val before = image()
        actor.afterNext = { tokens.clearAuthenticationTokens() }
        assertEquals("COMMAND_FRESH_AUTHORITY_REQUIRED", rejected { deliver() }.message)
        assertImageUnchanged(before, image())
        tokens.saveLoginSession("synthetic-next", "synthetic-refresh", "member", id(1), false)
        assertFalse(pending()); unresolved()
        val fresh = owner()
        fresh.deliverCompletedMetricGroup(requireNotNull(fresh.completedMetricGroup(parent.reference.native(), preferences)), preferences) { true }
        delivered()
    }

    @Test(timeout = 150_000) fun nativeT2CancellationRollsBackMetadataAndHistoricalRetryDoesNotResaveMetrics() = runBlocking<Unit> {
        group(); val before = image(); var returned = false
        supervisorScope {
            val operation = launch(start = CoroutineStart.LAZY) {
                withContext(fault.cohort.asContextElement(true)) { deliver(); returned = true }
            }
            fault.cancelAfterRoot(operation); operation.start(); operation.join(); assertTrue(operation.isCancelled)
        }
        assertEquals(1, fault.cancelled.get()); assertTrue(fault.rootWrites.get() > 0)
        assertFalse(returned); assertImageUnchanged(before, image()); assertFalse(pending()); unresolved()
        reopen(); val fresh = owner()
        fresh.deliverCompletedMetricGroup(requireNotNull(fresh.completedMetricGroup(parent.reference.native(), preferences)), preferences) { true }
        delivered()
    }

    @Test(timeout = 150_000) fun freshCipherFailureAfterActualT2RootReachesFinalGuardAndRollsBackWholeMetadata() = runBlocking<Unit> {
        group(); val before = image(); rejectAfterRoot.set(true)
        val failure = try {
            rejected { withContext(fault.cohort.asContextElement(true)) { deliver() } }
        } finally { rejectAfterRoot.set(false) }
        assertEquals("COMMAND_FINAL_AUTHORITY_CHANGED", failure.message)
        assertTrue("The real T2 root was written before fresh decryption failed", fault.rootWrites.get() > 0)
        assertTrue("Final fresh credential decryption actually executed", finalDecrypts.get() > 0)
        assertImageUnchanged(before, image()); assertFalse(pending()); unresolved()
        val fresh = owner()
        fresh.deliverCompletedMetricGroup(requireNotNull(fresh.completedMetricGroup(parent.reference.native(), preferences)), preferences) { true }
        delivered()
    }

    @Test(timeout = 150_000) fun lostActualT2EndNeverReturnsSuccessAndColdRecoveryFindsOriginalDeliveredGroup() = runBlocking<Unit> {
        group(); val old = requireNotNull(CountTrustRuntime.forDatabase(db)); val original = row(child.reference).intent.text()
        val business = businessImage(image()); val immutable = immutableGroup()
        fault.loseAfterRootEnd(); var returned = false
        val failure = rejected { withContext(fault.cohort.asContextElement(true)) { deliver(); returned = true } }
        fault.requireLostEnd(failure); assertFalse(returned)
        assertTrue(CountTrustRetirementReason.UNKNOWN_END in old.retirementReasons)
        reopen(); assertNotSame(old, CountTrustRuntime.forDatabase(db)); delivered()
        assertImageUnchanged(business, businessImage(image())); assertEquals(immutable, immutableGroup())
        val fresh = owner(); val proof = requireNotNull(fresh.completedMetricGroup(child.reference.native(), preferences)); val before = image()
        assertFalse(proof.pending); fresh.deliverCompletedMetricGroup(proof, preferences) { true }
        assertImageUnchanged(before, image()); assertEquals(original, row(child.reference).intent.text())
        reopen(); delivered(); assertEquals(original, row(child.reference).intent.text())
    }

    /** Wrapper keeps the real AndroidX actor. The barrier is AFTER the original transform,
     * before that actor returns and persists; it is not a caller-scope fake DataStore. The
     * separate serializer regression remains required for the after-transform disk boundary. */
    private class ActorGate(private val actual: DataStore<Preferences>) : DataStore<Preferences> {
        override val data get() = actual.data
        class Gate(val event: ((String) -> Unit)? = null) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        }
        private var next: Gate? = null
        var afterNext: (suspend () -> Unit)? = null
        var beforeNextReturn: (suspend () -> Unit)? = null
        fun arm(event: ((String) -> Unit)? = null) = Gate(event).also { check(next == null); next = it }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val gate = next; next = null
            val after = afterNext; afterNext = null
            val beforeReturn = beforeNextReturn; beforeNextReturn = null
            val result = actual.updateData { previous ->
                val value = transform(previous)
                beforeReturn?.invoke()
                gate?.let {
                    it.event?.invoke("preference_transform_done")
                    it.entered.complete(Unit)
                    it.event?.invoke("preference_release_window_begin")
                    try { withTimeout(5_000) { it.release.await() } }
                    catch (error: Throwable) { it.event?.invoke("preference_release_window_throw_${error.javaClass.simpleName}"); throw error }
                    it.event?.invoke("preference_release_window_return")
                }
                value
            }
            gate?.event?.invoke("preference_updateData_return")
            after?.invoke()
            return result
        }
    }
}
