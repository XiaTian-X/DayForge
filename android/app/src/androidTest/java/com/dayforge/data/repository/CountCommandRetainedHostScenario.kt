package com.dayforge.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.util.AtomicFile
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.di.DatabaseModule
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.DeviceCalendar
import com.dayforge.domain.service.DeviceThemeController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Host-only phases, no JUnit test or inherited delete/close rule. Real Hilt Host/Factory,
 * old19 SQLite, DataStore and Keystore are retained unchanged between process invocations.
 * Every checkpoint blocks BEFORE returning from the actual persistence/native END wrapper.
 */
@HiltAndroidTest
@UninstallModules(DatabaseModule::class)
class CountCommandRetainedHostScenario {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val protocol = CountCommandProcessProtocol(app)
    private val initialization = LegacySessionNativeInitializationProbe()
    private val native = CountCommandProcessNativeSpy(protocol, initialization)
    private val hilt = HiltAndroidRule(this)
    private val directory get() = protocol.directory
    private val authFile get() = File(directory, "auth.preferences_pb")
    private val stateFile get() = File(directory, "retained-state.json")
    private val alias get() = "dayforge.test.command.retained.${protocol.run}.${protocol.case.wire}"
    private val generationKey = stringPreferencesKey("auth_session_generation")
    private lateinit var scope: CoroutineScope
    private lateinit var data: LegacyGenerationStore
    private lateinit var cipher: LegacyGenerationCipher
    private lateinit var rooms: DatabaseEpochTestRooms.Configuration
    @Inject internal lateinit var host: DatabaseRuntimeHost
    @Inject internal lateinit var tokens: TokenManager
    @Inject internal lateinit var sessions: AccountSessionCoordinator
    @Inject internal lateinit var icons: AccountIconController
    @Inject internal lateinit var themes: DeviceThemeController
    private var closeAttempted = false
    private var cleanupFailure: Throwable? = null
    private val observationFailure = AtomicReference<Throwable?>()
    private fun id(n: Int) = "aa611000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"

    fun runScenarioPhase() {
        PhysicalDatabaseRule.requireProcessCleanupClear()
        check(protocol.case in CountCommandProcessProtocol.RETAINED_CASES)
        check(active == null && DatabaseRuntimeHosts.snapshot().all { it.lifecycleEnded })
        val body = object : Statement() {
            override fun evaluate() {
                runBlocking {
                    var failure: Throwable? = null
                    try { hilt.inject(); withTimeout(150_000) { runPhase() } }
                    catch (error: Throwable) { failure = error; throw error }
                    finally {
                        try { withContext(NonCancellable) { closeOwners() } }
                        catch (cleanup: Throwable) { if (failure == null) throw cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup) }
                    }
                }
            }
        }
        var primary: Throwable? = null
        try {
            runBlocking { openFiles() }
            rooms = DatabaseEpochTestRooms.install(null, native)
            active = this
            hilt.apply(isolated(body),
                Description.createTestDescription(javaClass, "retainedProcessPhase")).evaluate()
        } catch (failure: Throwable) {
            primary = failure; PhysicalDatabaseRule.preserveProcessCleanupFailure(failure); throw failure
        } finally {
            // SIGKILL cannot execute this. Termination cleanup never deletes any file/key.
            try { runBlocking { closeOwners() } }
            catch (cleanup: Throwable) { if (primary == null) throw cleanup else if (primary !== cleanup) primary.addSuppressed(cleanup) }
        }
    }

    /** Same fixed widget scheduler isolation; release only after the real owners drained.
     * A failed/unknown close leaves both resources and the isolation in place for fail-stop. */
    private fun isolated(body: Statement) = object : Statement() {
        override fun evaluate() {
            mockkObject(com.dayforge.widget.WidgetRefreshScheduler)
            every { com.dayforge.widget.WidgetRefreshScheduler.request(any()) } returns null
            var failure: Throwable? = null
            try { body.evaluate() } catch (error: Throwable) { failure = error }
            try {
                cleanupFailure?.let { throw it }
                check(closeAttempted && active == null) { "RETAINED_PROCESS_OWNERS_NOT_CLOSED" }
                unmockkObject(com.dayforge.widget.WidgetRefreshScheduler)
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
            failure?.let { throw it }
        }
    }

    private suspend fun openFiles() {
        check(app.packageName == "com.dayforge.testbed")
        val preparing = protocol.phase == CountCommandProcessProtocol.Phase.PREPARE
        if (preparing) {
            check(!authFile.exists() && !stateFile.exists())
            check(!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
            for (name in listOf("habit_database", com.dayforge.data.appearance.AccountIconDatabase.NAME)) {
                check(app.deleteDatabase(name) || !app.getDatabasePath(name).exists())
            }
        } else {
            protocol.priorCheckpoint()
            check(authFile.isFile && stateFile.isFile && app.getDatabasePath("habit_database").isFile)
            check(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
            check(state().getValue("cipher_alias") == JsonPrimitive(alias))
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        data = LegacyGenerationStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { authFile }))
        cipher = LegacyGenerationCipher(alias)
        if (preparing) {
            seedLegacyGenerationPreferences(data, cipher)
            assertNull(generation())
            val old = createOldFile()
            writeState(buildJsonObject {
                put("run", protocol.run); put("case", protocol.case.wire); put("nonce", protocol.nonce)
                put("cipher_alias", alias); put("old_rows", old.first)
                put("legacy_count", old.second.first); put("legacy_hash", old.second.second)
                put("seed_preferences_hash", preferencesHash(excludeGeneration = true))
                put("seed_preference_keys", JsonArray(data.data.first().asMap().keys.map { JsonPrimitive(it.name) }.sortedBy { it.content }))
            })
        }
        data.committedChanges.set(0)
    }

    private suspend fun runPhase() {
        val preparing = protocol.phase == CountCommandProcessProtocol.Phase.PREPARE
        // The real eager Theme owner may initialize its own preferences. Settle it before
        // the complete baseline/target hook, without changing any original auth/sync field.
        withTimeout(45_000) { themes.current() }
        assertEquals(0, rooms.creates.get())
        val seedNames = state().getValue("seed_preference_keys").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(state().getValue("seed_preferences_hash"), JsonPrimitive(preferencesHash(onlyNames = seedNames)))
        if (preparing) {
            assertNull(generation()); assertNull(tokens.localCoreWriteAccess())
            updateState("old_preferences_hash" to JsonPrimitive(preferencesHash(excludeGeneration = true)))
            data.committedChanges.set(0)
        }
        if (preparing && protocol.case == CountCommandProcessProtocol.Case.POST_GENERATION_PERSIST) {
            data.observeNextGenerationChange(afterPersistence = { observeGenerationPersistence() })
        }
        // Real production Host/Factory performs the control read/END and metadata preparation.
        host.withGraph { graph ->
            observationFailure.get()?.let { throw IllegalStateException("RETAINED_GENERATION_OBSERVATION_FAILED", it) }
            assertTrue(host.sessionPreparation.value is SessionPreparationState.Satisfied)
            assertEquals(1, rooms.creates.get()); assertTrue(graph.database.isOpen)
            val originalGeneration = requireNotNull(generation())
            val access = requireNotNull(tokens.localCoreWriteAccess())
            assertEquals(legacyGenerationId(1), access.session.authentication.userId)
            assertEquals(originalGeneration, access.session.authentication.generation)
            assertEquals(state().getValue("old_preferences_hash"), JsonPrimitive(preferencesHash(true)))
            assertLegacy(graph.database)
            if (preparing) {
                updateState("generation" to JsonPrimitive(originalGeneration), "preferences_hash" to JsonPrimitive(preferencesHash()))
                assertEquals(1, data.committedChanges.get())
                val commands = CountCommandStore(graph.database, tokens, sessions)
                if (protocol.case == CountCommandProcessProtocol.Case.POST_RETAINED_ENROLL_END) {
                    updateState("before_enrollment_business_hash" to JsonPrimitive(syncPayloadHash(businessImage(image(graph.database)).toString())))
                    native.armRetained(); commands.ensureForwardEnrollment()
                    error("RETAINED_ENROLLMENT_CHECKPOINT_NOT_REACHED")
                }
                check(protocol.case != CountCommandProcessProtocol.Case.POST_GENERATION_PERSIST) { "GENERATION_CHECKPOINT_NOT_REACHED" }
                commands.ensureForwardEnrollment()
                prepareIndependent(graph)
            } else {
                assertEquals(state().getValue("generation"), JsonPrimitive(originalGeneration))
                assertEquals(state().getValue("preferences_hash"), JsonPrimitive(preferencesHash()))
                assertEquals(0, data.committedChanges.get()) // Cold read must not rotate/persist generation again.
                when (protocol.case) {
                    CountCommandProcessProtocol.Case.POST_GENERATION_PERSIST -> {
                        assertEquals(protocol.priorCheckpoint().getValue("generation"), JsonPrimitive(originalGeneration))
                        assertEquals(protocol.priorCheckpoint().getValue("preferences_hash"), JsonPrimitive(preferencesHash()))
                        assertEquals(state().getValue("checkpoint_image_hash"), JsonPrimitive(syncPayloadHash(image(graph.database).toString())))
                        assertEquals("LEGACY_UNENROLLED", root(graph.database).mode)
                        assertEmptyCommandsAndFacts(graph.database)
                    }
                    CountCommandProcessProtocol.Case.POST_RETAINED_ENROLL_END -> {
                        val checkpoint = protocol.priorCheckpoint(); val actual = root(graph.database)
                        assertEquals("ACCOUNT_BOUND_RETAINED", actual.mode); assertEquals(3, actual.format); assertEquals(2L, actual.revision)
                        assertEquals(checkpoint.getValue("storage_id"), JsonPrimitive(actual.storageId))
                        assertEquals(checkpoint.getValue("dataset_id"), JsonPrimitive(actual.datasetId))
                        assertEquals(checkpoint.getValue("legacy_hash"), JsonPrimitive(actual.legacyHash))
                        assertEmptyCommandsAndFacts(graph.database)
                        val before = image(graph.database)
                        assertEquals(state().getValue("before_enrollment_business_hash"), JsonPrimitive(syncPayloadHash(businessImage(before).toString())))
                        CountCommandStore(graph.database, tokens, sessions).ensureForwardEnrollment()
                        assertEquals(before, image(graph.database))
                    }
                    else -> recoverIndependent(graph)
                }
                assertLegacy(graph.database)
                val stable = image(graph.database)
                host.withGraph { assertSame(graph, it) }
                val page = CountCommandStore(graph.database, tokens, sessions).discover(32)
                assertEquals(1L, page.retainedCount); assertTrue(page.complete); assertTrue(page.commands.isEmpty())
                assertEquals(stable, image(graph.database))
                assertEquals(access, tokens.localCoreWriteAccess())
                assertEquals(originalGeneration, generation()); assertEquals(0, data.committedChanges.get())
                protocol.terminalRetained(true, true, true, true, true, true, true)
            }
        }
    }

    /** Observation failures never escape an asynchronous persistence callback as JUnit
     * AssertionError. The bound phase reports them after the real Host call returns; an
     * unqualified checkpoint is never written and therefore can never authorize host kill. */
    private suspend fun observeGenerationPersistence() {
        try {
            check(initialization.selected.get()) { "RETAINED_GENERATION_CONTROL_NOT_OBSERVED" }
            check(initialization.actualEnds.get() == 1) { "RETAINED_GENERATION_END_NOT_OBSERVED" }
            check(data.committedChanges.get() == 1) { "RETAINED_GENERATION_PERSISTENCE_NOT_SINGLE" }
            val generation = requireNotNull(generation())
            check(state().getValue("old_preferences_hash") == JsonPrimitive(preferencesHash(true))) {
                "RETAINED_GENERATION_OTHER_PREFERENCES_CHANGED"
            }
            val room = rooms.createdRooms.single()
            check(room.isOpen && !room.inTransaction()) { "RETAINED_GENERATION_NATIVE_NOT_KNOWN_OPEN" }
            val snapshot = image(room)
            updateState("generation" to JsonPrimitive(generation), "preferences_hash" to JsonPrimitive(preferencesHash()),
                "checkpoint_image_hash" to JsonPrimitive(syncPayloadHash(snapshot.toString())))
            protocol.checkpointGeneration(generation, preferencesHash(), initialization.actualEnds.get())
        } catch (cancelled: CancellationException) {
            observationFailure.compareAndSet(null, cancelled)
            throw cancelled
        } catch (failure: Throwable) {
            observationFailure.compareAndSet(null, failure)
        }
    }

    private suspend fun prepareIndependent(graph: DatabaseEpochGraph): Nothing {
        val db = graph.database
        val value = MetricEntity(uuid = id(20), name = "Retained process value", unit = "kg", decimalPlaces = 3,
            iconResId = 0, colorHex = "#123456")
        val metric = db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            value.copy(id = db.metricDao().insert(value)).also {
                db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            }
        }
        val attempt = graph.metricRepository.beginIndependentValue(metric.id, 4.25, "retained-process", expectedMetricUuid = metric.uuid)
        val intent = requireNotNull(attempt.intent); val ref = requireNotNull(attempt.reference)
        updateState("intent" to intent.body(), "reference" to Json.parseToJsonElement(ref.encode()),
            "before_command_business_hash" to JsonPrimitive(syncPayloadHash(businessImage(image(db), t0 = true).toString())))
        if (protocol.case != CountCommandProcessProtocol.Case.POST_INDEPENDENT_T2_END) native.armRetained()
        val saved = graph.metricRepository.executeIndependentValue(attempt)
        check(protocol.case == CountCommandProcessProtocol.Case.POST_INDEPENDENT_T2_END) { "M1_NATIVE_CHECKPOINT_NOT_REACHED" }
        updateState("before_delivery_business_hash" to JsonPrimitive(syncPayloadHash(businessImage(image(db)).toString())))
        native.armRetained(); graph.metricRepository.acknowledgeIndependentValue(saved) { true }
        error("M1_T2_NATIVE_CHECKPOINT_NOT_REACHED")
    }

    private suspend fun recoverIndependent(graph: DatabaseEpochGraph) {
        val db = graph.database; val repository = graph.metricRepository
        val body = CountCommandRecords.canonical(state().getValue("intent")).toByteArray(Charsets.UTF_8)
        val intent = CountCommandRecords.Intent.decode(body, body.size) {}
        val ref = CountCommandReference.decode(state().getValue("reference").toString())
        assertEquals(ref, CountCommandReference.decode(protocol.priorCheckpoint().getValue("reference").toString()))
        assertEquals(CountCommandReference(intent.commandId, intent.inputHash, intent.namespace.hash, intent.namespace.dataset), ref)
        val commands = CountCommandStore(db, tokens, sessions)
        val original = commands.resolve(ref.native()).historical
        assertEquals(intent.text(), original.intent.text()); assertEquals(CountCommandRecords.Action.METRIC_APPEND, original.intent.action)
        assertNull(original.status.parent); assertNull(original.status.child)
        val atT0 = protocol.case == CountCommandProcessProtocol.Case.POST_INDEPENDENT_T0_END
        val delivered = protocol.case == CountCommandProcessProtocol.Case.POST_INDEPENDENT_T2_END
        assertEquals(if (atT0) CountCommandRecords.Execution.PREPARED else CountCommandRecords.Execution.APPLIED, original.status.execution)
        assertEquals(if (delivered) CountCommandRecords.Delivery.DELIVERED else if (atT0)
            CountCommandRecords.Delivery.PENDING_RESOLUTION else CountCommandRecords.Delivery.RESULT_PENDING, original.status.delivery)
        assertEquals(if (atT0) 0L else 1L, count(db, "metric_logs")); assertEquals(if (atT0) 0L else 1L, count(db, "sync_outbox"))
        assertEquals(if (atT0) 1L else 2L, count(db, "count_command_results"))
        val before = image(db)
        if (atT0) assertEquals(state().getValue("before_command_business_hash"), JsonPrimitive(syncPayloadHash(businessImage(before, t0 = true).toString())))
        if (delivered) assertEquals(state().getValue("before_delivery_business_hash"), JsonPrimitive(syncPayloadHash(businessImage(before).toString())))
        val observed = repository.resolveIndependentValue(ref)
        if (atT0) assertTrue(observed is IndependentMetricResolution.Unresolved)
        else assertTrue(observed is IndependentMetricResolution.Saved)
        assertEquals(before, image(db))
        val saved = (repository.resolveIndependentValue(ref, true) as IndependentMetricResolution.Saved).value
        assertEquals(ref, saved.reference)
        if (!atT0) assertEquals(before, image(db))
        val frozen = IndependentMetricInput.input(intent)
        val row = requireNotNull(db.metricLogDao().getById(saved.factId))
        assertEquals(CountCommandRecords.uuid(frozen, "reserved_fact_uuid"), row.uuid)
        assertEquals(CountCommandRecords.integer(frozen, "metric_id"), row.metricId)
        assertEquals(IndependentMetricInput.value(frozen).toRawBits(), row.value.toRawBits())
        assertEquals(CountCommandRecords.integer(frozen, "recorded_at"), row.date)
        assertEquals(CountCommandRecords.text(frozen, "timezone"), row.recordedTimezone)
        assertEquals(CountCommandRecords.text(frozen, "note"), row.note); assertEquals("kg", row.unit)
        val queue = db.syncOutboxDao().getAll().single()
        assertEquals(row.uuid, queue.entityUuid); assertEquals(row.uuid, queue.wireEntityUuid)
        assertEquals("metric_log", queue.recordType); assertEquals("upsert", queue.action)
        assertEquals(CountCommandRecords.text(frozen, "metric_uuid"), queue.referenceUuid)
        assertNull(queue.payloadJson); assertNull(queue.attemptedAt); assertEquals(0, queue.attemptCount)
        val effect = commands.resolve(ref.native()).historical
        assertEquals(queue.operationId, requireNotNull(effect.outcome).primaryRequestId)
        IndependentMetricEvidence.requireLegacy(intent, requireNotNull(effect.outcome))
        val beforeDelivery = image(db)
        repository.acknowledgeIndependentValue(saved) { true }
        assertEquals(businessImage(beforeDelivery), businessImage(image(db)))
        if (delivered) assertEquals(beforeDelivery, image(db))
        val terminal = commands.resolve(ref.native()).historical
        assertEquals(CountCommandRecords.Delivery.DELIVERED, terminal.status.delivery)
        assertEquals(1L, count(db, "metric_logs")); assertEquals(1L, count(db, "sync_outbox"))
        assertEquals(1L, count(db, "count_command_intents")); assertEquals(1L, count(db, "count_command_status"))
        assertEquals(2L, count(db, "count_command_results"))
        assertEquals(0L, count(db, "completions")); assertEquals(0L, count(db, "next_request_origins"))
        val stable = image(db)
        val again = (repository.resolveIndependentValue(ref, true) as IndependentMetricResolution.Saved).value
        assertEquals(saved.factUuid, again.factUuid)
        repository.acknowledgeIndependentValue(again) { true }
        assertEquals(stable, image(db))
    }

    private suspend fun createOldFile(): Pair<JsonElement, Pair<Long, String>> = withContext(Dispatchers.IO) {
        check(!app.getDatabasePath("habit_database").exists())
        val schema = CountCommandMigrationFixture.asset(19)
        FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app).name("habit_database")
            .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                        val name = entity.getValue("tableName").jsonPrimitive.content
                        fun ddl(value: JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", name)
                        db.execSQL(ddl(entity.getValue("createSql")))
                        entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(ddl(it.jsonObject.getValue("createSql"))) }
                    }
                    schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                    CountTrustSchema.install(db); SyncSchemaCallback.onOpen(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Expected historical19 creation")
            }).build()).use { helper ->
            val sql = helper.writableDatabase; assertEquals(19, sql.version)
            sql.beginTransaction()
            try {
                sql.execSQL("INSERT INTO count_command_results VALUES('legacy-native-id','old-input','old-fact',NULL,'retained old result','committed')")
                val image = CountCommandMigrationFixture.snapshot(sql).contents.getValue("tables").jsonObject.getValue("count_command_results")
                val fingerprint = CountCommandLegacyNative.fingerprint(sql); assertEquals(1L, fingerprint.first)
                sql.setTransactionSuccessful(); image to fingerprint
            } finally { sql.endTransaction() }
        }
    }
    private fun root(db: HabitDatabase) = CountCommandNativeRows(db.openHelper.writableDatabase) {}.root()
    private suspend fun assertLegacy(db: HabitDatabase) = db.withTransaction {
        CountCommandStorageInitialization.verifyMandatory(db.openHelper.writableDatabase)
        val header = root(db)
        assertEquals(state().getValue("legacy_count"), JsonPrimitive(header.legacyCount))
        assertEquals(state().getValue("legacy_hash"), JsonPrimitive(header.legacyHash))
        val all = CountCommandMigrationFixture.snapshot(db.openHelper.writableDatabase).contents.getValue("tables").jsonObject
            .getValue("count_command_results").jsonArray
        val old = JsonArray(all.filter { it.jsonObject.getValue("commandId").jsonObject.getValue("value") == JsonPrimitive("legacy-native-id") })
        assertEquals(state().getValue("old_rows"), old)
    }
    private fun assertEmptyCommandsAndFacts(db: HabitDatabase) {
        for (table in listOf("count_command_intents", "count_command_status", "metric_logs", "sync_outbox", "completions", "next_request_origins"))
            assertEquals(0L, count(db, table))
        assertEquals(1L, count(db, "count_command_results"))
    }
    private fun count(db: HabitDatabase, table: String): Long {
        check(table in setOf("count_command_intents", "count_command_status", "count_command_results", "metric_logs", "sync_outbox", "completions", "next_request_origins"))
        return db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
            check(it.moveToFirst()); it.getLong(0).also { _ -> check(!it.moveToNext()) }
        }
    }
    /** Complete schema/native image, only the declared command envelope cells excluded.
     * COUNT storage/generation/dirty and all business/origin/outbox rows remain included. */
    private fun businessImage(image: JsonObject, t0: Boolean = false): JsonObject {
        val tables = image.getValue("tables").jsonObject.toMutableMap()
        for (name in listOf("count_command_recovery_roots", "count_command_nodes", "count_command_status")) tables.remove(name)
        if (t0) tables.remove("count_command_intents")
        tables["count_trust_control"] = JsonArray(tables.getValue("count_trust_control").jsonArray.map {
            JsonObject(it.jsonObject - "commandRootHash")
        })
        return JsonObject(image + ("tables" to JsonObject(tables)))
    }
    private suspend fun image(db: HabitDatabase): JsonObject = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun generation() = data.data.first()[generationKey]
    private suspend fun preferencesHash(excludeGeneration: Boolean = false, onlyNames: Set<String>? = null): String {
        val values = data.data.first().asMap().entries.filter {
            (!excludeGeneration || it.key != generationKey) && (onlyNames == null || it.key.name in onlyNames)
        }.sortedBy { it.key.name }
        val frozen = buildJsonObject { for ((key, value) in values) put(key.name, when (value) {
            is String -> JsonPrimitive(value); is Boolean -> JsonPrimitive(value)
            is Set<*> -> JsonArray(value.map { JsonPrimitive(it as String) }.sortedBy { it.content })
            else -> error("UNEXPECTED_RETAINED_PREFERENCES_TYPE")
        }) }
        return syncPayloadHash(frozen.toString())
    }
    private fun state(): JsonObject {
        check(stateFile.isFile && stateFile.length() in 1..1_048_576)
        return CountCommandRecords.parse(stateFile.readBytes(), 1_048_576, {}).also {
            check(it["run"] == JsonPrimitive(protocol.run) && it["case"] == JsonPrimitive(protocol.case.wire) && it["nonce"] == JsonPrimitive(protocol.nonce))
        }
    }
    private fun updateState(vararg fields: Pair<String, JsonElement>) = writeState(JsonObject(state() + fields))
    private fun writeState(value: JsonObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8); check(bytes.size <= 1_048_576)
        val atomic = AtomicFile(stateFile); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    private suspend fun closeOwners() {
        cleanupFailure?.let { throw it }
        if (closeAttempted) return
        closeAttempted = true
        try {
            if (::themes.isInitialized) withTimeout(5_000) { themes.close() }
            if (::host.isInitialized) shutdownTestHost(host)
            else check(DatabaseRuntimeHosts.snapshot().all { it.lifecycleEnded }) { "RETAINED_HOST_INJECTION_OWNER_UNKNOWN" }
            if (::icons.isInitialized) { icons.awaitImages(); icons.close() }
            if (::scope.isInitialized) withTimeout(5_000) { scope.coroutineContext[Job]!!.cancelAndJoin() }
            if (::rooms.isInitialized) DatabaseEpochTestRooms.release(rooms)
            check(active == null || active === this); active = null
        } catch (error: Throwable) {
            cleanupFailure = error; PhysicalDatabaseRule.preserveProcessCleanupFailure(error); throw error
        }
    }
    companion object { @Volatile private var active: CountCommandRetainedHostScenario? = null }
    @Module @InstallIn(SingletonComponent::class)
    object PreferencesModule {
        @Provides @Singleton fun data(): DataStore<Preferences> = requireNotNull(active).data
        @Provides @Singleton fun cipher(): TokenCipher = requireNotNull(active).cipher
        @Provides @Singleton fun preferences(data: DataStore<Preferences>, calendar: DeviceCalendar, tokens: TokenManager) = PreferencesManager(data, calendar, tokens)
        @Provides @Singleton fun connectivity(@ApplicationContext context: Context): ConnectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
}
