package com.dayforge.data.repository

import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.room.withTransaction
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import java.time.LocalDate
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.service.CompletionActionTiming
import com.dayforge.domain.service.CountCommandCostTiming
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import com.dayforge.data.local.entity.MetricEntity
import java.util.UUID
import kotlinx.serialization.json.*
import com.dayforge.domain.model.CountDisplayProjection
import com.dayforge.domain.service.FailureCheckerUtils
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountMetricSourceReadTest : CountMetricSourceReadFixture() {
    private val t1Native = MetricT1NativeFixture(sourceSpy)
    init { storage.isolatedBuilder = { context ->
        buildDiagnosticFaultDatabase(context, t1Native, storage.queryCallback)
    } }

    @Test fun actualWriterReusesOneRootAndOriginalRoundInputWithoutRemovingAnyFreshGuard() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs()
        val old = readSource(inputs, reuse = false)
        assertMetricScopeRolledBack(inputs)
        val shared = readSource(inputs, reuse = true)
        assertFiniteSourceMatchesOriginal(old.view, shared.view)
        assertEquals(3, old.schemas); assertEquals(1, shared.schemas)
        assertEquals("Original source path still performs15 access proofs", 15, old.accessDecrypts)
        assertEquals(old.accessDecrypts, shared.accessDecrypts)
        assertEquals(0, old.refreshDecrypts); assertEquals(0, shared.refreshDecrypts)
        assertEquals(0, old.unknownDecrypts); assertEquals(0, shared.unknownDecrypts)
        assertEquals(old.accessDecrypts, old.decrypts); assertEquals(old.decrypts, shared.decrypts)
        assertMetricScopeRolledBack(inputs)
    }

    @Test fun realRepositoryTwoMetricSaveReachesSharedSourceThenOriginalFullProducerAndActualEnd() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs()
        val oldSource = readSource(inputs, reuse = false)
        val metrics = inputs.metrics.values.toList()
        val authority = requireNotNull(editor.metrics(metrics.map { it.id }).first().authority)
        val drafts = metrics.mapIndexed { i, row -> MetricValueDraft(row.id, 7.25 + i, "real source read $i") }
        val recordedAt = System.currentTimeMillis(); val capturedZone = ZoneId.systemDefault().id
        resetCredentialCounts(); sourceSpy.begin()
        val ids: List<Long>; val sample: SourceWindowNativeSpy.Snapshot
        val began = System.currentTimeMillis()
        try { ids = creatingMetrics().recordValues(drafts, recordedAt, authority,
            expectedMetrics = metrics, promptHabit = inputs.currentHabit, promptFact = inputs.claim,
            promptCompletionSource = true) }
        finally { sample = sourceSpy.end() }
        val ended = System.currentTimeMillis()
        assertFalse(sample.overflow); assertEquals(1, sample.count(SourceWindowNativeSpy.Kind.SCHEMA_SNAPSHOT))
        // Keep all16 original access proofs; the3 unused refresh decryptions are absent.
        assertCredentialCounts(access = 16, refresh = 0)
        assertActualRows(inputs, drafts, ids, recordedAt, began, ended, capturedZone)
        val after = readSource(inputs, reuse = true)
        assertFiniteSourceMatchesOriginal(oldSource.view, after.view)
    }

    @Test fun legalPriorWriteFallsBackOnceWithoutReusingOldRootOrLosingOriginalSave() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val writer = producer(); val scope = writer.captureRounds()
        var callbacks = 0
        val ids = writer.writeRoundsWithMetricSource(scope) {
            CountTrustKernel.withMetricSourceRead(db) { read ->
                assertNotNull(read); widgets().requireCompletionInTransaction(inputs.claim, metricSourceRead = read)
            }
            val saved = insertKnown(inputs)
            CountTrustKernel.withMetricSourceRead(db) { read ->
                callbacks++; assertNull("Already inserted facts require the original current-state reader", read)
                widgets().requireCompletionInTransaction(inputs.claim, metricSourceRead = read)
            }
            saved
        }
        assertEquals(1, callbacks); assertMetricScopeCommitted(inputs, ids)
    }

    @Test fun ordinaryWriterAndStandaloneKeepOriginalGuardsWithoutOptionalSeed() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val writer = producer(); var calls = 0
        writer.writeRounds(writer.captureRounds()) {
            CountTrustKernel.withMetricSourceRead(db) { read ->
                calls++; assertNull(read); widgets().requireCompletionInTransaction(inputs.claim, metricSourceRead = read)
            }
        }
        CountTrustKernel.withMetricSourceRead(db) { read -> calls++; assertNull(read) }
        assertEquals(2, calls); assertMetricScopeRolledBack(inputs)
    }

    /** These use a recovered real APPLIED parent and the original T0 child ticket. The
     * pre-existing four tests deliberately exercise the non-command/fallback source path. */
    @Test(timeout = 150_000) fun realParentEmptyThenSingleMetricRetainsIndependentDaoAndActualEnd() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val parent = actualParent(inputs)
        val beforeEmpty = nativeImage().capture()
        val empty = creatingMetrics().recordValuesWithReceipt(emptyList(), parentCommand = parent)
        assertTrue(empty.value.isEmpty()); assertNull(empty.command)
        nativeImage().assertUnchanged(beforeEmpty)
        // Recovery canonicalizes nested JSON; equality must not depend on property order.
        val reordered = inputs.claim.copy(plan = JsonObject(inputs.claim.plan.entries.reversed().associate { it.toPair() }))
        assertEquals(inputs.claim, reordered); assertNotEquals(inputs.claim.encode(), reordered.encode())
        lateinit var prepared: FormalMetric
        val (result, sample) = observeMetricInput(1) {
            prepared = prepareFormal(inputs.copy(claim = reordered), parent, inputs.metrics.values.take(1))
            t1Native.arm(prepared.metrics.first().id)
            submitFormal(prepared) to t1Native.finished()
        }
        assertActualMetricInsertEntries(sample, 1)
        assertEquals(1, sample.ends); assertEquals(3, sample.dao); assertTrue(sample.typed >= 1)
        assertEquals(2, sample.daoBeforeFirstMetricInsertAttempt)
        assertEquals(1, sample.daoAfterFirstMetricInsertAttempt)
        assertEquals(sample.dao, sample.daoBeforeFirstMetricInsertAttempt + sample.daoAfterFirstMetricInsertAttempt)
        assertEquals(1, sample.metricInsertAttempts)
        assertFalse(sample.faultTouched)
        assertFormalResult(prepared, result)
    }

    @Test(timeout = 150_000) fun realParentThreeMetricsPreservesEveryReservedFactSourceAndActualEnd() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val parent = actualParent(inputs)
        val prototype = inputs.metrics.values.first()
        val third = creatingMetrics().createMetric(prototype.copy(id = 0, uuid = UUID.randomUUID().toString(),
            name = "third independent metric"), selectedHabitIds = setOf(inputs.currentHabit.id),
            creationAuthority = creator.capture(), expectedHabitUuids = mapOf(inputs.currentHabit.id to inputs.currentHabit.uuid))
        val selected = inputs.metrics.values.toList() + requireNotNull(db.metricDao().getMetricById(third))
        val cold = observeQualificationWithoutRepair(inputs)
        assertEquals(1L, cold.dirty); assertFalse(cold.qualified)
        // A structure writer legitimately dirtied the COUNT proof. Exercise the real ordinary
        // read/qualification before asserting this method's WARM pure-input contract.
        val summary = CountHistoryReader(db, tokens, sessions).readSummary(inputs.currentHabit,
            LocalDate.parse(inputs.claim.date))
        assertEquals(1L, summary.todayQuantity)
        assertEquals(requireNotNull(db.completionDao().getCompletionByUuid(requireNotNull(inputs.claim.completionUuid))).id,
            requireNotNull(summary.todayLatestCompletionId))
        val warm = observeQualificationWithoutRepair(inputs)
        assertEquals(0L, warm.dirty); assertTrue(warm.qualified)
        assertEquals(cold.generation, warm.generation)
        lateinit var prepared: FormalMetric
        val (result, sample) = observeMetricInput(3) {
            prepared = prepareFormal(inputs, parent, selected)
            t1Native.arm(selected.first().id)
            submitFormal(prepared) to t1Native.finished()
        }
        assertActualMetricInsertEntries(sample, 3)
        assertEquals(1, sample.ends); assertEquals(9, sample.dao); assertTrue(sample.typed >= 3)
        assertEquals(6, sample.daoBeforeFirstMetricInsertAttempt)
        assertEquals(3, sample.daoAfterFirstMetricInsertAttempt)
        assertEquals(sample.dao, sample.daoBeforeFirstMetricInsertAttempt + sample.daoAfterFirstMetricInsertAttempt)
        assertEquals(3, sample.metricInsertAttempts)
        assertFalse(sample.faultTouched)
        assertFormalResult(prepared, result)
    }

    @Test(timeout = 150_000) fun realParentMatchingForgedFirstDaoAndCallerStillReachesOriginalLateRejection() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val parent = actualParent(inputs)
        val original = inputs.metrics.values.first()
        val forged = original.copy(updatedAt = Math.addExact(original.updatedAt, 1L))
        // updatedAt is intentionally absent from the original writeMetric projection.
        assertEquals(NextStructureMapper.writeMetric(original), NextStructureMapper.writeMetric(forged))
        val prepared = prepareFormal(inputs, parent, listOf(forged))
        val before = nativeImage().capture()
        t1Native.arm(original.id, MetricT1NativeFixture.Fault.FIRST_DAO_UPDATED_AT)
        val error = rejected { submitFormal(prepared) }
        val sample = t1Native.finished()
        assertTrue(sample.faultTouched); assertEquals(1, sample.ends)
        assertEquals("Both the actual first DAO and original late DAO must execute", 2, sample.dao)
        assertTrue("The original independent typed metric row must be reached", sample.typed >= 1)
        assertEquals(0, sample.metricInsertAttempts)
        assertTrue(error is IllegalStateException)
        assertTrue(error.stackTrace.any { it.className.startsWith("com.dayforge.data.repository.CountTrustKernel") })
        nativeImage().assertUnchanged(before)
        assertNull(CountCommandStore(db, tokens, sessions).resolve(prepared.command.input.reference()).historical.outcome)
    }

    @Test(timeout = 150_000) fun realParentTypedCursorCloseMutationRejectsBeforeAnyMetricInsert() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val parent = actualParent(inputs)
        val prepared = prepareFormal(inputs, parent, inputs.metrics.values.take(1))
        val before = nativeImage().capture()
        t1Native.arm(prepared.metrics.first().id, MetricT1NativeFixture.Fault.AFTER_TYPED_METRIC_UPDATED_AT)
        rejected { submitFormal(prepared) }
        val sample = t1Native.finished()
        assertTrue("Fixed native UPDATE must execute after typed cursor.close", sample.faultTouched)
        assertEquals(1, sample.ends); assertTrue(sample.typed >= 1)
        assertEquals("Original late DAO rejects before the first business INSERT; later rollback is insufficient", 0, sample.metricInsertAttempts)
        nativeImage().assertUnchanged(before)
        assertNull(CountCommandStore(db, tokens, sessions).resolve(prepared.command.input.reference()).historical.outcome)
    }

    @Test(timeout = 150_000) fun realParentCallerDraftMutationCannotRewriteFrozenCommandOrPartiallyCommit() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val parent = actualParent(inputs)
        val prepared = prepareFormal(inputs, parent, inputs.metrics.values.take(1))
        val oldIntent = prepared.command.input.text()
        val changed = prepared.drafts.toMutableList().also { it[0] = it[0].copy(value = 999.125) }
        val before = nativeImage().capture()
        t1Native.arm(prepared.metrics.first().id)
        rejected { submitFormal(prepared, changed) }
        val sample = t1Native.finished()
        assertEquals(1, sample.ends); assertTrue(sample.typed >= 1)
        assertEquals(oldIntent, prepared.command.input.text())
        nativeImage().assertUnchanged(before)
        assertNull(CountCommandStore(db, tokens, sessions).resolve(prepared.command.input.reference()).historical.outcome)
    }

    @Test(timeout = 150_000) fun foreignReaderCoordinatorPreservesOriginalLegalSourceContract() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val parent = actualParent(inputs)
        lateinit var prepared: FormalMetric
        val foreignSessions = com.dayforge.domain.service.AccountSessionCoordinator()
        assertNotSame(sessions, foreignSessions)
        val reader = WidgetFactReader(db, tokens, foreignSessions, preferences,
            CountHistoryReader(db, tokens, foreignSessions), CheckHistoryReader(db, tokens, foreignSessions))
        val repository = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(),
            db.habitMetricLinkDao(), nextObjectEditor = editor, widgetFactReader = reader)
        val (result, sample) = observeMetricInput(1, issued = false) {
            prepared = prepareFormal(inputs, parent, inputs.metrics.values.take(1))
            t1Native.arm(prepared.metrics.first().id)
            val saved = repository.recordValuesWithReceipt(prepared.drafts, prepared.recordedAt, prepared.authority,
                expectedMetrics = prepared.metrics, promptHabit = inputs.currentHabit, promptFact = inputs.claim,
                promptCompletionSource = true, parentCommand = parent, preparedCommand = prepared.command)
            saved to t1Native.finished()
        }
        // Original source ownership checks bind DB/tokens/child scope. A different coordinator
        // must not make that old legal read a new error. Both pre-INSERT independent DAO
        // checks and the original post-INSERT/pre-END source mapping read remain.
        // Actual existing collector counters separately establish no sealed-input/payload reuse;
        // they do not authorize this source or replace the original native/domain assertions.
        assertActualMetricInsertEntries(sample, 1)
        assertEquals(1, sample.ends); assertEquals(3, sample.dao); assertTrue(sample.typed >= 1)
        assertEquals(2, sample.daoBeforeFirstMetricInsertAttempt)
        assertEquals(1, sample.daoAfterFirstMetricInsertAttempt)
        assertEquals(sample.dao, sample.daoBeforeFirstMetricInsertAttempt + sample.daoAfterFirstMetricInsertAttempt)
        assertEquals(1, sample.metricInsertAttempts); assertFalse(sample.faultTouched)
        assertFormalResult(prepared, result)
    }

    @Test(timeout = 150_000) fun realParentThreeMetricsAfterStructureWritePreservesColdFallbackAndCompleteResult() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val parent = actualParent(inputs)
        val prototype = inputs.metrics.values.first()
        val third = creatingMetrics().createMetric(prototype.copy(id = 0, uuid = UUID.randomUUID().toString(),
            name = "third cold-path metric"), selectedHabitIds = setOf(inputs.currentHabit.id),
            creationAuthority = creator.capture(), expectedHabitUuids = mapOf(inputs.currentHabit.id to inputs.currentHabit.uuid))
        val selected = inputs.metrics.values.toList() + requireNotNull(db.metricDao().getMetricById(third))
        val cold = observeQualificationWithoutRepair(inputs)
        assertEquals(1L, cold.dirty); assertFalse(cold.qualified)
        lateinit var prepared: FormalMetric
        // Preserve the original 474 sequence: structure write directly to T0/T1, with no
        // qualifying read. It must still save all three through the complete original path.
        val (result, sample) = observeMetricInput(3, issued = false, definitionReused = 0) {
            prepared = prepareFormal(inputs, parent, selected)
            t1Native.arm(selected.first().id)
            submitFormal(prepared) to t1Native.finished()
        }
        assertActualMetricInsertEntries(sample, 3)
        assertEquals(1, sample.ends); assertEquals(3, sample.metricInsertAttempts)
        assertFalse(sample.faultTouched)
        assertTrue("The real metric DAO must actually execute", sample.dao >= selected.size)
        assertEquals(sample.dao, sample.daoBeforeFirstMetricInsertAttempt + sample.daoAfterFirstMetricInsertAttempt)
        assertFormalResult(prepared, result)
    }

    /** Calibrate the real DAO/Room compatibility execution path before using this probe's
     * zero-attempt observations as evidence. No DAO-read count is converted into an INSERT. */
    private fun assertActualMetricInsertEntries(sample: MetricT1NativeFixture.Sample, n: Int) {
        assertEquals(n, sample.metricInsertAttempts)
        assertEquals(n, sample.metricInsertEntries.getValue(MetricT1NativeFixture.InsertEntry.STATEMENT_EXECUTE))
        assertEquals(sample.metricInsertAttempts, sample.metricInsertEntries.values.sum())
        for (entry in MetricT1NativeFixture.InsertEntry.entries) {
            if (entry != MetricT1NativeFixture.InsertEntry.STATEMENT_EXECUTE)
                assertEquals("This actual Room DAO must use execute(), not $entry", 0, sample.metricInsertEntries.getValue(entry))
        }
    }

    private data class MetricQualification(val generation: Long, val dirty: Long, val qualified: Boolean)
    /** Observation only: original authenticated open never invokes qualification/prepare.
     * Verify the whole MAIN image so this witness cannot secretly repair the prerequisite. */
    private suspend fun observeQualificationWithoutRepair(inputs: MetricScopeInputs): MetricQualification {
        val before = nativeImage().capture()
        val result = sessions.exclusive { db.withTransaction {
            val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
            assertFalse(owner.isRetired)
            val access = requireNotNull(tokens.localCoreWriteAccess())
            val observed = owner.maintenance(db) {
                val sql = db.openHelper.readableDatabase
                CountTrustSchema.requireControl(sql)
                val control = sql.query("SELECT generation,dirty FROM count_trust_control WHERE id=1").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(android.database.Cursor.FIELD_TYPE_INTEGER, it.getType(0))
                    assertEquals(android.database.Cursor.FIELD_TYPE_INTEGER, it.getType(1))
                    val pair = it.getLong(0) to it.getLong(1)
                    assertFalse(it.moveToNext()); pair
                }
                val header = CountTrustSeal.read(sql)
                val opened = CountTrustKernel(db, tokens, sessions).open(access)
                MetricQualification(control.first, control.second,
                    opened != null && control.second == 0L && header?.generation == control.first &&
                        opened.formal && opened.observations && opened.eligible(inputs.currentHabit) &&
                        opened.index.get("global_common") == JsonPrimitive(true))
            }
            assertEquals(access, tokens.localCoreWriteAccess())
            observed
        } }
        nativeImage().assertUnchanged(before)
        return result
    }

    /** Existing collector only: the real T0 ticket is issued INSIDE the original operation
     * context, so adding a Trace never moves a live ticket into another Job. Log visibility is
     * awaited only after this entire real operation returned and its original flush ran. */
    private suspend fun <T> observeMetricInput(n: Int, issued: Boolean = true,
        definitionReused: Int = n, block: suspend () -> T): T {
        assertTrue("This branch-coverage gate requires the existing diagnostic build", CompletionActionTiming.enabled)
        var operation: Long? = null
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        val result = CompletionActionTiming.withOperation(CompletionActionTiming.Operation.LINKED_METRICS) {
            operation = CompletionActionTiming.currentOperationId()
            assertNotNull(operation)
            block()
        }
        val returned = android.os.SystemClock.elapsedRealtimeNanos()
        assertNull(CompletionActionTiming.currentOperationId())
        val id = requireNotNull(operation)
        fun linesForOperation(): List<String> {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "logcat -d -v raw -t 200 --pid=${Process.myPid()} -s CountCommandCostTiming:D CompletionActionTiming:D")
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().filter { it.startsWith("operation_id=$id ") }
            }
        }
        val lines = withTimeout(5_000) {
            var observed = linesForOperation()
            while (observed.none { " phase=T1 " in it && " part=2 " in it && " FINAL_AUTHORITY=1," in it } ||
                observed.none { " operation=LINKED_METRICS stage=OPERATION " in it }) {
                delay(50); observed = linesForOperation()
            }
            observed
        }
        val operationLine = lines.single { " operation=LINKED_METRICS stage=OPERATION " in it }
        assertTrue("The whole real command must return before checking counts", " outcome=returned " in operationLine)
        val operationFields = operationLine.split(' ').map { it.split('=', limit = 2) }
            .filter { it.size == 2 }.associate { it[0] to it[1] }
        assertTrue(operationFields.getValue("stage_start_ns").toLong() in started..returned)
        assertTrue(operationFields.getValue("stage_end_ns").toLong() in started..returned)
        val costs = lines.filter { " phase=T1 " in it }
        assertTrue(costs.all { " balanced_at_flush=true " in it &&
            " columns=count,total_ns,failed,max_ns,first_start_ns,last_end_ns" in it })
        val expected = linkedMapOf(
            CountCommandCostTiming.Step.METRIC_INPUT_ISSUED to if (issued) 1 else 0,
            CountCommandCostTiming.Step.METRIC_DEFINITION_REUSED to definitionReused,
            CountCommandCostTiming.Step.METRIC_OBSERVATION_BUILT to if (issued) n else 0,
            CountCommandCostTiming.Step.METRIC_OBSERVATION_REUSED to if (issued) n else 0
        )
        // These appended fields share the already observed complete FINAL_AUTHORITY part.
        // Its absence would be a timeout/failure, never evidence that a count is zero.
        assertEquals(1, costs.count { " part=2 " in it })
        for ((step, count) in expected) {
            assertEquals("Collector part contract changed", 2, step.ordinal / 18)
            val entries = costs.flatMap { it.split(' ') }.filter { it.startsWith("${step.name}=") }
            if (count == 0) assertTrue("Unexpected real branch $step: $entries", entries.isEmpty())
            else {
                assertEquals("Expected exactly one flushed counter for $step", 1, entries.size)
                val fields = entries.single().substringAfter('=').split(',').map(String::toLong)
                assertEquals(6, fields.size)
                assertEquals("Actual original T1 branch count for $step", count.toLong(), fields[0])
                assertEquals("The observed branch must have returned", 0L, fields[2])
                assertTrue(fields[1] >= 0L && fields[3] >= 0L && fields[4] in started..returned &&
                    fields[5] in fields[4]..returned)
            }
        }
        return result
    }

    private suspend fun actualParent(inputs: MetricScopeInputs): CountCommandReceipt {
        val store = CountCommandStore(db, tokens, sessions)
        val page = store.discover(32)
        assertTrue(page.complete)
        val actual = page.commands.single { it.historical.intent.action == CountCommandRecords.Action.COUNT_ADD &&
            it.historical.outcome?.primaryFactUuid == inputs.claim.completionUuid }
        val recovered = store.recover(actual.reference)
        assertTrue(recovered is CountCommandStore.Recovery.Committed)
        return CountCommandReceipt((recovered as CountCommandStore.Recovery.Committed).value).also {
            assertTrue(it.recorded); assertEquals(inputs.claim.completionUuid, it.factUuid)
        }
    }
    private data class FormalMetric(val inputs: MetricScopeInputs, val parent: CountCommandReceipt,
        val metrics: List<MetricEntity>, val drafts: List<MetricValueDraft>, val authority: ObjectEditAuthority,
        val recordedAt: Long, val zone: String, val command: NextObjectEditor.CountAttempt)
    private suspend fun prepareFormal(inputs: MetricScopeInputs, parent: CountCommandReceipt,
        metrics: List<MetricEntity>): FormalMetric {
        val authority = requireNotNull(editor.metrics(metrics.map { it.id }).first().authority)
        val drafts = metrics.mapIndexed { i, row -> MetricValueDraft(row.id, 17.125 + i, "formal T1 $i") }
        val time = System.currentTimeMillis(); val zone = ZoneId.systemDefault().id
        val links = metrics.map { requireNotNull(db.habitMetricLinkDao().getLink(inputs.currentHabit.id, it.id)) }
        val command = editor.beginMetricChild(parent, authority, inputs.currentHabit, inputs.claim,
            drafts, metrics, links, time, zone)
        return FormalMetric(inputs, parent, metrics, drafts, authority, time, zone, command)
    }
    private suspend fun submitFormal(value: FormalMetric, drafts: List<MetricValueDraft> = value.drafts) =
        creatingMetrics().recordValuesWithReceipt(drafts, value.recordedAt, value.authority,
            expectedMetrics = value.metrics, promptHabit = value.inputs.currentHabit, promptFact = value.inputs.claim,
            promptCompletionSource = true, parentCommand = value.parent, preparedCommand = value.command)
    private fun CountCommandRecords.Intent.reference() = CountCommandStore.Reference(commandId, inputHash,
        namespace.hash, namespace.dataset)
    private suspend fun assertFormalResult(prepared: FormalMetric, result: CountMutationResult<List<Long>>) {
        val n = prepared.metrics.size
        assertEquals(n, result.value.size); assertEquals(n, result.value.toSet().size)
        val receipt = requireNotNull(result.command)
        assertEquals(prepared.command.input.commandId, receipt.commandId); assertTrue(receipt.recorded)
        val frozen = prepared.command.input.body().getValue("input").jsonObject.getValue("metrics").jsonArray
            .map { it.jsonObject }.associateBy { CountCommandRecords.integer(it, "metric_id") }
        val rows = result.value.map { requireNotNull(db.metricLogDao().getById(it)) }
        for ((row, draft) in rows.zip(prepared.drafts)) {
            val metric = prepared.metrics.single { it.id == draft.metricId }
            assertEquals(draft.metricId, row.metricId); assertEquals(prepared.recordedAt, row.date)
            assertEquals(draft.value.toRawBits(), row.value.toRawBits()); assertEquals(draft.note, row.note)
            assertEquals(metric.unit, row.unit); assertEquals(prepared.zone, row.recordedTimezone)
            assertEquals(CountCommandRecords.uuid(frozen.getValue(row.metricId), "reserved_fact_uuid"), row.uuid)
        }
        val sources = db.syncOutboxDao().getAll().filter { it.recordType == "metric_log" }
        assertEquals(rows.map { it.uuid }.toSet(), sources.map { it.entityUuid }.toSet()); assertEquals(n, sources.size)
        sources.forEach { source ->
            val origin = originalIntent(source)
            assertEquals(source.id, origin.queueId)
            assertEquals(producer().operation(source), requireNotNull(roundOperationIntent(origin.intentJson)).operation)
        }
        val stored = CountCommandStore(db, tokens, sessions).resolve(receipt.committed.reference).historical
        assertEquals(CountCommandOutcome.Kind.APPLIED, requireNotNull(stored.outcome).kind)
        assertEquals(n, requireNotNull(stored.outcome).effects.size)
        assertEquals(rows.map { it.uuid }.toSet(), requireNotNull(stored.outcome).effects.map { it.factUuid }.toSet())
        assertEquals(n, requireNotNull(stored.outcome).origins.size)
        assertEquals(prepared.command.input.text(), stored.intent.text())
    }

    /** Independent old-summary oracle: never copy any field from the actual compact projection.
     * The source contract needs no card statistics; full history remains on the ordinary reader.
     */
    private fun assertFiniteSourceMatchesOriginal(original: WidgetFactSnapshot, actual: WidgetFactSnapshot) {
        val summary = requireNotNull(original.count)
        assertNull("The ordinary reader must retain its original full-summary contract", original.countDisplay)
        val expectedProjection = CountDisplayProjection(
            today = summary.today,
            zoneId = original.claim.timezone,
            todayQuantity = summary.quantities[summary.today] ?: 0L,
            todayPolicy = summary.todayPolicy,
            todayLatestCompletionId = summary.todayLatestCompletionId,
            qualifiedDayCount = summary.qualifiedDates.size,
            hasFailed = FailureCheckerUtils.countHasFailed(original.habit, summary, ZoneId.of(original.claim.timezone)),
            roundHead = summary.roundHead,
            actionIdentity = summary.actionIdentity,
            card = null
        )
        assertEquals(original.completed, expectedProjection.completedToday)
        assertEquals(original.targetProgress, expectedProjection.qualifiedDayCount)
        assertEquals(original.hasFailed, expectedProjection.hasFailed)
        assertNull("The finite source must not materialize the full history summary", actual.count)
        assertNotNull(actual.countDisplay)
        // Data-class equality covers complete habit, claim, completed/progress/failure flags,
        // all ten projection constructor fields, and the required absence of card statistics.
        assertEquals("Metric source reuse must preserve every original observable value",
            original.copy(count = null, countDisplay = expectedProjection), actual)
    }
}
