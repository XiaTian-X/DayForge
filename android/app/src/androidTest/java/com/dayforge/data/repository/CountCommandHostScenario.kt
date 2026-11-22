package com.dayforge.data.repository

import android.database.Cursor
import android.util.AtomicFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.appearance.*
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** A host-controlled complete COUNT group. There is deliberately no PhysicalDatabaseRule:
 * RECOVER must open the original installation, SQLite file, auth DataStore and Keystore key.
 * Profile: original formal-v5/plain recurring producer, not the round-contract/F2 fixture.
 * Metric cases use the actual fixed production prompt coordinator and a double-value Save.
 * This is not a Hilt/Activity/OS-task or UI-pixel test.
 */
internal class CountCommandHostScenario {
    private val widgetRefresh = com.dayforge.widget.IsolatedWidgetRefreshRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val protocol = CountCommandProcessProtocol(app)
    private val native = CountCommandProcessNativeSpy(protocol)
    private val sessions = AccountSessionCoordinator()
    private lateinit var db: HabitDatabase
    private lateinit var tokens: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var icons: AccountIconController
    private lateinit var iconDatabase: AccountIconDatabase
    private lateinit var editor: NextObjectEditor
    private lateinit var habits: HabitRepository
    private lateinit var habit: HabitEntity
    private lateinit var factReader: WidgetFactReader
    private lateinit var metricRepository: MetricRepository
    private var selectedMetrics = emptyList<MetricEntity>()
    private lateinit var authScope: CoroutineScope
    private val stateFile get() = File(protocol.directory, "driver-state.json")
    private val authDirectory get() = File(protocol.directory, "auth")
    private val alias get() = "dayforge.test.command.process.${protocol.run}.${protocol.case.wire}"
    private fun uuid(n: Int) = "aa390000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"

    fun runScenarioPhase() {
        // Apply the same existing fixed scheduler isolation explicitly. There is no @Test,
        // @Ignore or ordinary-JUnit discovery filter for this host-only scenario.
        widgetRefresh.apply(object : org.junit.runners.model.Statement() {
            override fun evaluate() { runBlocking { withTimeout(150_000) { runPhase() } } }
        }, org.junit.runner.Description.createSuiteDescription("CountCommandHostScenario")).evaluate()
    }

    private suspend fun runPhase() {
        check(protocol.case in CountCommandProcessNativeSpy.COUNT_CASES + CountCommandProcessNativeSpy.METRIC_CASES)
        try {
            open()
            if (protocol.case in CountCommandProcessNativeSpy.COUNT_CASES) {
                if (protocol.phase == CountCommandProcessProtocol.Phase.PREPARE) prepare() else recover()
            } else {
                if (protocol.phase == CountCommandProcessProtocol.Phase.PREPARE) prepareMetrics() else recoverMetrics()
            }
        } finally {
            // On an expected SIGKILL this code cannot run. On recovery it closes handles
            // but retains every file/key for host evidence and an explicit later reset.
            if (::icons.isInitialized) { icons.awaitImages(); icons.close() }
            if (::iconDatabase.isInitialized) iconDatabase.close()
            if (::authScope.isInitialized) authScope.coroutineContext[Job]!!.cancelAndJoin()
            if (::db.isInitialized) db.close()
            HabitDatabaseProvider.clearInstanceForTesting()
        }
    }

    private suspend fun open() {
        check(app.packageName == "com.dayforge.testbed")
        val recovering = protocol.phase == CountCommandProcessProtocol.Phase.RECOVER
        HabitDatabaseProvider.clearInstanceForTesting()
        if (recovering) {
            protocol.priorCheckpoint() // Requires a different actual application PID.
            check(stateFile.isFile && authDirectory.isDirectory && File(authDirectory, "auth.preferences_pb").isFile)
            check(app.getDatabasePath("habit_database").isFile && app.getDatabasePath(AccountIconDatabase.NAME).isFile)
            val state = state()
            check(state.getValue("cipher_alias") == JsonPrimitive(alias))
        } else {
            check(!stateFile.exists() && !authDirectory.exists()) { "COMMAND_HOST_CASE_ALREADY_STARTED" }
            app.deleteDatabase("habit_database"); app.deleteDatabase(AccountIconDatabase.NAME)
            check(authDirectory.mkdirs())
        }
        authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = authScope,
            produceFile = { File(authDirectory, "auth.preferences_pb") })
        tokens = TokenManager(data, AndroidKeystoreTokenCipher(alias))
        preferences = PreferencesManager(data, tokens = tokens)
        if (!recovering) {
            tokens.saveLoginSession("synthetic-command-access", "synthetic-command-refresh", "member", uuid(1), false)
            tokens.saveServerIdentity(uuid(2), uuid(3))
            tokens.saveDeviceRegistration(uuid(4), setOf("sync.read", "structure.write", "facts.append", "timer.control"), true, 1)
        }
        requireNotNull(tokens.localCoreWriteAccess()) { "COMMAND_HOST_ORIGINAL_KEY_OR_SESSION_MISSING" }
        db = native.build(app).also(HabitDatabaseProvider::setInstanceForTesting)
        iconDatabase = AccountIconDatabase.open(app)
        check(iconDatabase.openHelper.writableDatabase.isOpen)
        val metadata = AccountIconRepository(iconDatabase, tokens, sessions)
        val store = AccountIconStore(metadata, AccountIconFiles(authDirectory))
        val imports = AccountIconImport(metadata, store, AccountIconTransfers(iconDatabase, metadata, store))
        icons = AccountIconController({ AccountIconRuntime(metadata, store, AccountIconRenderer(metadata, store), imports,
            AccountIconDocuments(imports, app.contentResolver), iconDatabase::close) }, tokens)
        editor = NextObjectEditor(db, tokens, sessions, icons)
        val counts = CountHistoryReader(db, tokens, sessions)
        val checks = CheckHistoryReader(db, tokens, sessions)
        factReader = WidgetFactReader(db, tokens, sessions, preferences, counts, checks)
        metricRepository = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(),
            db.habitMetricLinkDao(), nextObjectEditor = editor, widgetFactReader = factReader)
        habits = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db, nextObjectEditor = editor,
            countHistoryReader = counts, checkHistoryReader = checks,
            recurringHabitReader = RecurringHabitReader(db, tokens, sessions, editor, counts, checks),
            widgetFactReader = factReader,
            countActions = CountActionReader(db, tokens, sessions))
        if (!recovering) {
            val zone = ZoneId.systemDefault(); val born = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)
            db.withTransaction {
                val sql = db.openHelper.writableDatabase
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                val value = HabitEntity(uuid = uuid(10), name = "Host durable count", habitType = HabitType.COUNTING,
                    iconResId = 0, colorHex = "#000000", schedule = HabitSchedule.Daily, targetValue = 10,
                    failMode = FailMode.LOOSE, createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli(),
                    completionPolicy = "recurring", appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"),
                    planMetadata = PlanStructureMetadata(born.toString(), 0, null, null, zone.id, null, null, null))
                habit = value.copy(id = db.habitDao().insert(value))
                if (protocol.case in CountCommandProcessNativeSpy.METRIC_CASES) {
                    selectedMetrics = (0..1).map { index ->
                        val metric = MetricEntity(uuid = uuid(30 + index), name = "Host metric $index", unit = "kg", decimalPlaces = 3,
                            iconResId = 0, colorHex = "#000000", createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli(),
                            appearance = ObjectAppearance(IconReference.Role("metric.custom"), "#123456", "object"))
                        metric.copy(id = db.metricDao().insert(metric)).also { saved ->
                            db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
                                metricId = saved.id, metricUuid = saved.uuid, promptOnComplete = true, uuid = uuid(40 + index),
                                createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli()))
                        }
                    }
                }
                sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            }
        } else {
            val input = expectedIntent().body().getValue("input").jsonObject
            habit = requireNotNull(db.habitDao().getHabitById(CountCommandRecords.integer(input, "habit_id")))
            check(habit.uuid == CountCommandRecords.uuid(input, "habit_uuid"))
            if (protocol.case in CountCommandProcessNativeSpy.METRIC_CASES) {
                selectedMetrics = state().getValue("metrics").jsonArray.map { saved ->
                    val expected = saved.jsonObject
                    requireNotNull(db.metricDao().getMetricById(CountCommandRecords.integer(expected, "id"))).also { metric ->
                        check(metric.uuid == CountCommandRecords.uuid(expected, "uuid") &&
                            NextStructureMapper.writeMetric(metric) == expected.getValue("definition"))
                    }
                }
            }
        }
    }

    private suspend fun prepare(): Nothing {
        // Warm the same original read/selection dependencies before the measured fault
        // chain. This work is still inside this150s driver phase and host deadline.
        CountHistoryReader(db, tokens, sessions).readSummary(habit, java.time.LocalDate.now())
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val now = Instant.now(); val zone = ZoneId.systemDefault()
        val input = CountCommandInputs.add(db, UUID.randomUUID().toString(), habit, authority, now, zone, 1)
        val before = image()
        writeState(buildJsonObject {
            put("format", 1); put("run", protocol.run); put("case", protocol.case.wire); put("nonce", protocol.nonce)
            put("profile", "formal-v5-plain-recurring"); put("cipher_alias", alias)
            put("intent", input.body()); put("before_native", before)
        })
        native.armCount()
        val command = editor.beginCountAdd(habit, authority, now, zone, 1, preparedInput = input)
        habits.logCompletionWithReceipt(app, habit.id, authority = authority, expectedHabitUuid = habit.uuid,
            preparedCommand = command)
        error("COMMAND_HOST_NATIVE_FAULT_NOT_REACHED")
    }

    private suspend fun recover() {
        val checkpoint = protocol.priorCheckpoint()
        val expected = expectedIntent()
        val reference = CountCommandReference.decode(checkpoint.getValue("reference").toString())
        assertEquals(CountCommandReference(expected.commandId, expected.inputHash, expected.namespace.hash, expected.namespace.dataset), reference)
        val fresh = requireNotNull(tokens.localCoreWriteAccess())
        assertEquals(expected.namespace.account, fresh.session.authentication.userId)
        val beforeResolution = image()
        if (protocol.case == CountCommandProcessProtocol.Case.PRE_T0_END) {
            // This exact checkpoint is a test oracle for a transaction that never ended.
            // It does not expose a general missing-pending => NeverCommitted recovery API.
            assertEquals(state().getValue("before_native"), beforeResolution)
            assertEquals(0, count("count_command_intents")); assertEquals(0, count("count_command_results"))
            assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
            assertEquals(0L, CountHistoryReader(db, tokens, sessions).readSummary(habit, java.time.LocalDate.now()).todayQuantity)
            assertEquals(0, count("count_command_status")); assertEquals(0, count("metric_logs"))
            assertNotNull(tokens.localCoreWriteAccess())
            protocol.terminal(true, true, true, true, true, true, true)
            return
        }
        val committedBeforeRecovery = protocol.case == CountCommandProcessProtocol.Case.POST_T1_END
        assertEquals(if (committedBeforeRecovery) 1 else 0, count("completions"))
        assertEquals(if (committedBeforeRecovery) 1 else 0, count("count_command_results"))
        recoveryStage("COUNT_RESUME_ENTERED")
        val recovered = habits.resumeCountCommand(app, reference)
        recoveryStage("COUNT_RESUME_RETURNED")
        assertTrue(recovered is CountCommandRecovery.HistoricalCommitted)
        val receipt = (recovered as CountCommandRecovery.HistoricalCommitted).receipt
        assertEquals(reference, receipt.reference); assertTrue(receipt.recorded)
        if (committedBeforeRecovery) assertEquals(beforeResolution, image())
        val input = expected.body().getValue("input").jsonObject
        val fact = db.completionDao().getAllCompletionsOnce().single()
        assertEquals(CountCommandRecords.uuid(input, "reserved_fact_uuid"), fact.uuid)
        assertEquals(Instant.parse(CountCommandRecords.text(input, "occurred_at")).toEpochMilli(), fact.actualCompletedAt)
        assertEquals(CountCommandRecords.text(input, "timezone"), fact.recordedTimezone)
        assertEquals(1, fact.value); assertEquals(habit.id, fact.habitId); assertEquals(habit.uuid, fact.habitUuid)
        val source = db.syncOutboxDao().getAll().single()
        assertEquals(fact.uuid, source.entityUuid)
        val access = requireNotNull(tokens.localSyncAccess())
        val origin = db.withTransaction { NextOriginalRequestProofStore(db).liveOrigin(access, NEXT_OPERATION, source.operationId, requirePermission = false) }
        assertEquals(source.id, origin.queueId); assertEquals(NextRequestSql.sourceHash(source), origin.sourceHash)
        recoveryStage("COUNT_SUMMARY_ENTERED")
        val summary = RecurringDisplayReadScope.withSnapshot(db, tokens, sessions,
            AccountSessionCoordinator.Work.FACT_HISTORY_READ) { scope ->
            val current = requireNotNull(db.habitDao().getHabitById(habit.id))
            // A resumed COUNT may update only these two fields of the originally opened row.
            assertEquals(habit.copy(activityRate = current.activityRate,
                activityRateUpdatedAt = current.activityRateUpdatedAt), current)
            recoveryStage("COUNT_ENTITY_COMPARED")
            CountHistoryReader(db, tokens, sessions).readSummaryInTransaction(current,
                java.time.LocalDate.parse(CountCommandRecords.text(input, "local_date")), scope)
        }
        recoveryStage("COUNT_SUMMARY_RETURNED")
        assertEquals(1L, summary.todayQuantity); assertEquals(fact.id, summary.todayLatestCompletionId)
        val commands = CountCommandStore(db, tokens, sessions)
        val located = commands.resolve(reference.native())
        assertEquals(expected.text(), located.historical.intent.text())
        assertEquals(CountCommandRecords.Execution.APPLIED, located.historical.status.execution)
        assertEquals(CountCommandRecords.Delivery.FOLLOW_UP_PENDING, located.historical.status.delivery)
        assertNull(located.historical.status.child); assertEquals(0, count("metric_logs"))
        val pending = commands.discover(8)
        assertTrue(pending.complete); assertEquals(1, pending.commands.size)
        assertEquals(reference.commandId, pending.commands.single().historical.intent.commandId)
        val stable = image()
        val again = habits.resumeCountCommand(app, reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(reference, again.receipt.reference); assertEquals(stable, image())
        assertEquals(1, count("count_command_intents")); assertEquals(1, count("count_command_results"))
        assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox")); assertEquals(1, count("next_request_origins"))
        assertEquals(fresh, tokens.localCoreWriteAccess())
        protocol.terminal(true, true, true, true, true, true, true)
    }

    /** Fixed recovery-only diagnostics. They cannot satisfy a host terminal assertion. */
    private fun recoveryStage(stage: String) {
        check(protocol.phase == CountCommandProcessProtocol.Phase.RECOVER)
        require(stage in setOf("COUNT_RESUME_ENTERED", "COUNT_RESUME_RETURNED", "COUNT_SUMMARY_ENTERED",
            "COUNT_ENTITY_COMPARED", "COUNT_SUMMARY_RETURNED"))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("df_command_stage", stage)
        })
    }

    private fun metricCoordinator() = com.dayforge.ui.metrics.LinkedMetricCoordinator(app, preferences,
        metricRepository, habits, factReader = factReader)

    private fun metricValues() = selectedMetrics.mapIndexed { index, metric ->
        com.dayforge.ui.components.MetricValueInput(metric.id, 31.125 + index, "host durable metric $index")
    }

    private suspend fun prepareMetrics(): Nothing {
        CountHistoryReader(db, tokens, sessions).readSummary(habit, java.time.LocalDate.now())
        val originalClaim = factReader.read(habit).claim
        val coordinator = metricCoordinator()
        val request = coordinator.captureCompletionPrompt()
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val now = Instant.now(); val zone = ZoneId.systemDefault()
        val input = CountCommandInputs.add(db, UUID.randomUUID().toString(), habit, authority, now, zone, 1)
        val attempt = editor.beginCountAdd(habit, authority, now, zone, 1, preparedInput = input)
        val parent = requireNotNull(habits.logCompletionWithReceipt(app, habit.id, authority = authority,
            expectedHabitUuid = habit.uuid, preparedCommand = attempt).command)
        check(parent.recorded && parent.factUuid != null)
        val source = requireNotNull(factReader.afterCountCommand(originalClaim, parent.factUuid).completionSource)
        check(source.claim.completionUuid == parent.factUuid)
        coordinator.showCompletionPrompt(source, request, command = parent)
        val shown = requireNotNull(coordinator.postCheckInState.value)
        val prompt = requireNotNull(shown.factPrompt)
        check(prompt.command === parent && shown.linkedMetrics.map { it.metricId }.toSet() == selectedMetrics.map { it.id }.toSet())
        val values = metricValues()
        val beforeNative = image()
        writeState(buildJsonObject {
            put("format", 1); put("run", protocol.run); put("case", protocol.case.wire); put("nonce", protocol.nonce)
            put("profile", "formal-v5-plain-recurring-double-metric"); put("cipher_alias", alias)
            put("intent", input.body()); put("parent_reference", Json.parseToJsonElement(parent.reference.encode()))
            put("source_claim", Json.parseToJsonElement(source.claim.encode())); put("before_native", beforeNative)
            put("metrics", buildJsonArray { selectedMetrics.forEach { metric ->
                val value = values.single { it.metricId == metric.id }
                add(buildJsonObject {
                    put("id", metric.id); put("uuid", metric.uuid); put("definition", NextStructureMapper.writeMetric(metric))
                    put("value_bits", value.value.toRawBits().toULong().toString(16).padStart(16, '0')); put("note", value.note)
                })
            } })
        })
        native.armMetrics(parent.reference)
        withContext(Dispatchers.Main.immediate) {
            check(coordinator.recordMetricValues(habit.id, values, expectedFactPrompt = prompt))
        }
        error("COMMAND_HOST_NATIVE_FAULT_NOT_REACHED")
    }

    private suspend fun recoverMetrics() {
        val checkpoint = protocol.priorCheckpoint()
        val state = state()
        val parentInput = expectedIntent()
        val parentRef = CountCommandReference.decode(state.getValue("parent_reference").toString())
        assertEquals(CountCommandReference(parentInput.commandId, parentInput.inputHash, parentInput.namespace.hash,
            parentInput.namespace.dataset), parentRef)
        val childRef = CountCommandReference.decode(checkpoint.getValue("reference").toString())
        assertNotEquals(parentRef.commandId, childRef.commandId)
        assertEquals(parentRef.namespaceHash, childRef.namespaceHash); assertEquals(parentRef.datasetId, childRef.datasetId)
        val access = requireNotNull(tokens.localCoreWriteAccess())
        assertEquals(parentInput.namespace.account, access.session.authentication.userId)
        val commands = CountCommandStore(db, tokens, sessions)
        val beforeResolution = image()
        val parentResult = habits.resumeCountCommand(app, parentRef)
        assertTrue(parentResult is CountCommandRecovery.HistoricalCommitted)
        val parent = (parentResult as CountCommandRecovery.HistoricalCommitted).receipt
        assertEquals(parentRef, parent.reference); assertTrue(parent.recorded)
        val childBefore = commands.resolve(childRef.native()).historical
        assertEquals(CountCommandRecords.Action.LINKED_METRICS, childBefore.intent.action)
        val frozen = childBefore.intent.body().getValue("input").jsonObject
        assertEquals(parent.commandId, CountCommandRecords.uuid(frozen, "parent_command_id"))
        assertEquals(parent.factUuid, CountCommandRecords.uuid(frozen, "source_fact_uuid"))
        val originalClaim = WidgetFactClaim.decode(state.getValue("source_claim").toString())
        assertEquals(originalClaim, CountCommandInputs.restoredWidget(frozen, "source_claim"))
        val savedMetrics = state.getValue("metrics").jsonArray.map { it.jsonObject }
        val frozenMetrics = frozen.getValue("metrics").jsonArray.map { it.jsonObject }
        assertEquals(2, savedMetrics.size); assertEquals(2, frozenMetrics.size)
        savedMetrics.forEach { expected ->
            val row = frozenMetrics.single { CountCommandRecords.integer(it, "metric_id") == CountCommandRecords.integer(expected, "id") }
            assertEquals(expected.getValue("uuid"), row.getValue("metric_uuid"))
            for (key in listOf("definition", "value_bits", "note")) assertEquals(expected.getValue(key), row.getValue(key))
        }
        val alreadyApplied = protocol.case != CountCommandProcessProtocol.Case.POST_METRIC_T0_END
        val alreadyDelivered = protocol.case == CountCommandProcessProtocol.Case.POST_T2_END
        assertEquals(if (alreadyApplied) 2 else 0, count("metric_logs"))
        assertEquals(if (alreadyApplied) 2 else 1, count("count_command_results"))
        assertEquals(2, count("count_command_intents")); assertEquals(1, count("completions"))
        assertEquals(if (alreadyApplied) CountCommandRecords.Execution.APPLIED else CountCommandRecords.Execution.PREPARED,
            childBefore.status.execution)
        if (alreadyApplied) {
            val historical = metricRepository.resumeLinkedMetricCommand(childRef)
            assertTrue(historical is CountCommandRecovery.HistoricalCommitted)
            assertEquals(childRef, (historical as CountCommandRecovery.HistoricalCommitted).receipt.reference)
        }
        assertEquals("Parent and committed-child resolution cannot rewrite the retained image", beforeResolution, image())
        val beforePage = commands.discover(8)
        assertTrue(beforePage.complete)
        assertEquals(if (alreadyDelivered) emptySet<String>() else setOf(parentRef.commandId, childRef.commandId),
            beforePage.commands.map { it.reference.commandId }.toSet())
        val source = factReader.completionSource(originalClaim)
        val coordinator = metricCoordinator()
        coordinator.showCompletionPrompt(source, coordinator.captureCompletionPrompt(), command = parent)
        val shown = requireNotNull(coordinator.postCheckInState.value)
        val prompt = requireNotNull(shown.factPrompt)
        assertEquals(selectedMetrics.map { it.id }.toSet(), shown.linkedMetrics.map { it.metricId }.toSet())
        val values = savedMetrics.map { saved -> com.dayforge.ui.components.MetricValueInput(
            CountCommandRecords.integer(saved, "id"), Double.fromBits(java.lang.Long.parseUnsignedLong(
                CountCommandRecords.text(saved, "value_bits"), 16)), CountCommandRecords.text(saved, "note")) }
        val beforeSave = image(); val beforeBusiness = businessImage()
        assertTrue(withContext(Dispatchers.Main.immediate) {
            coordinator.recordMetricValues(habit.id, values, expectedFactPrompt = prompt)
        })
        val child = requireNotNull(prompt.submittedCommand)
        assertEquals(childRef, child.reference)
        if (alreadyApplied) assertEquals("Committed metric recovery cannot append business effects", beforeBusiness, businessImage())
        if (alreadyDelivered) assertEquals("Committed T2 retry must be fully read-only", beforeSave, image())
        val p = commands.resolve(parentRef.native()).historical
        val m = commands.resolve(childRef.native()).historical
        assertEquals(parentInput.text(), p.intent.text()); assertEquals(childBefore.intent.text(), m.intent.text())
        assertEquals(childRef.commandId, p.status.child); assertEquals(parentRef.commandId, m.status.parent)
        assertEquals(CountCommandRecords.Execution.APPLIED, m.status.execution)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, p.status.delivery)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, m.status.delivery)
        assertEquals(2, requireNotNull(m.outcome).effects.size)
        val terminalPage = commands.discover(8); assertTrue(terminalPage.complete); assertTrue(terminalPage.commands.isEmpty())
        assertEquals(2, count("metric_logs")); assertEquals(1, count("completions")); assertEquals(3, count("sync_outbox"))
        assertEquals(2, count("count_command_intents")); assertEquals(2, count("count_command_results")); assertEquals(2, count("count_command_status"))
        assertEquals(3, count("next_request_origins"))
        val logs = selectedMetrics.flatMap { db.metricLogDao().getAllLogsForMetric(it.id) }
        assertEquals(2, logs.size)
        frozenMetrics.forEach { saved ->
            val log = logs.single { it.metricId == CountCommandRecords.integer(saved, "metric_id") }
            val metric = selectedMetrics.single { it.id == log.metricId }
            assertEquals(CountCommandRecords.uuid(saved, "reserved_fact_uuid"), log.uuid)
            assertEquals(java.lang.Long.parseUnsignedLong(CountCommandRecords.text(saved, "value_bits"), 16), log.value.toRawBits())
            assertEquals(CountCommandRecords.text(saved, "note"), log.note); assertEquals(metric.unit, log.unit)
            assertEquals(CountCommandRecords.integer(frozen, "recorded_at"), log.date)
            assertEquals(CountCommandRecords.text(frozen, "timezone"), log.recordedTimezone)
        }
        val sources = db.syncOutboxDao().getAll(); val sync = requireNotNull(tokens.localSyncAccess())
        sessions.exclusive { db.withTransaction {
            val original = NextOriginalRequestProofStore(db)
            sources.forEach { row ->
                val origin = original.liveOrigin(sync, NEXT_OPERATION, row.operationId)
                assertEquals(row.id, origin.queueId); assertEquals(NextRequestSql.sourceHash(row), origin.sourceHash)
                val operation = decodeNextOperationIntent(origin.intentJson)
                if (row.recordType == "metric_log") {
                    val log = logs.single { it.uuid == row.entityUuid }; val metric = selectedMetrics.single { it.id == log.metricId }
                    assertEquals("metric_observation", operation.entityType)
                    assertEquals(log.uuid, operation.entityUuid); assertEquals(SyncV2Mapper.metricObservation(log, metric.uuid), operation.payload)
                } else { assertEquals("completion", row.recordType); assertEquals(parent.factUuid, row.entityUuid) }
            }
        } }
        val parentFrozen = parentInput.body().getValue("input").jsonObject
        val summary = CountHistoryReader(db, tokens, sessions).readSummary(habit,
            java.time.LocalDate.parse(CountCommandRecords.text(parentFrozen, "local_date")))
        assertEquals(1L, summary.todayQuantity)
        val stable = image()
        assertTrue(withContext(Dispatchers.Main.immediate) {
            coordinator.recordMetricValues(habit.id, values, expectedFactPrompt = prompt)
        })
        assertEquals(stable, image()); assertEquals(2, count("metric_logs")); assertEquals(3, count("sync_outbox"))
        assertEquals(access, tokens.localCoreWriteAccess())
        protocol.terminal(true, true, true, true, true, true, true)
    }

    /** T2 may change only authenticated command metadata/anchor; all business and derived
     * tables, including generation/dirty and unrelated native values, remain exact. */
    private suspend fun businessImage(): JsonObject = db.withTransaction {
        val full = image()
        val control = db.openHelper.writableDatabase.query(
            "SELECT id,storageId,generation,dirty FROM count_trust_control WHERE id=1 LIMIT 2").use { c ->
            check(c.moveToFirst())
            buildJsonArray {
                for (i in 0..3) add(buildJsonArray { add(c.getType(i)); add(if (i == 1) JsonPrimitive(c.getString(i)) else JsonPrimitive(c.getLong(i))) })
            }.also { check(!c.moveToNext()) }
        }
        JsonObject(full.filterKeys { it !in setOf("count_command_intents", "count_command_status", "count_command_results",
            "count_command_recovery_roots", "count_command_nodes", "count_trust_control") } + ("control_business" to control))
    }

    private fun count(table: String): Int {
        check(table in setOf("completions", "sync_outbox", "count_command_intents", "count_command_results", "count_command_status", "metric_logs", "next_request_origins"))
        return db.openHelper.writableDatabase.query("SELECT count(*) FROM `$table`").use { check(it.moveToFirst()); it.getInt(0) }
    }
    private fun state(): JsonObject {
        check(stateFile.isFile && stateFile.length() in 1..1_048_576)
        return CountCommandRecords.parse(stateFile.readBytes(), 1_048_576, {}).also {
            check(it.getValue("run") == JsonPrimitive(protocol.run) && it.getValue("case") == JsonPrimitive(protocol.case.wire) &&
                it.getValue("nonce") == JsonPrimitive(protocol.nonce))
        }
    }
    private fun expectedIntent(): CountCommandRecords.Intent {
        val bytes = CountCommandRecords.canonical(state().getValue("intent")).toByteArray(Charsets.UTF_8)
        return CountCommandRecords.Intent.decode(bytes, bytes.size, {})
    }
    private fun writeState(value: JsonObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8); check(bytes.size <= 1_048_576)
        val file = AtomicFile(stateFile); val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
    private suspend fun image(): JsonObject = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        val tables = sql.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        buildJsonObject { tables.forEach { table ->
            check(table.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")))
            val rows = sql.query("SELECT rowid,* FROM main.`$table` ORDER BY rowid").use { c -> buildJsonArray {
                while (c.moveToNext()) add(buildJsonArray { for (i in 0 until c.columnCount) add(buildJsonArray {
                    add(c.getType(i)); add(when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i).toRawBits())
                        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(c.getString(i))
                        Cursor.FIELD_TYPE_BLOB -> buildJsonObject { val blob = c.getBlob(i); put("bytes", blob.size); put("sha256", CountTrustIndex.hash(blob)) }
                        else -> error("COMMAND_HOST_NATIVE_TYPE")
                    })
                }) })
            } }
            put(table, buildJsonObject { put("rows", rows.size); put("sha256", CountTrustIndex.hash(rows.toString().toByteArray(Charsets.UTF_8))) })
        } }
    }
}
