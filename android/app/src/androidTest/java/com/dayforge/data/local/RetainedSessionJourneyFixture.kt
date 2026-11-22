package com.dayforge.data.local

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.data.repository.*
import com.dayforge.domain.service.*
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.metrics.LinkedMetricPromptState
import com.dayforge.ui.components.PostCheckInDialog
import com.dayforge.ui.recovery.CountCommandRecoveryPanel
import com.dayforge.ui.recovery.CountCommandRecoveryViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** A single real Host and preferences owner. Historical files exist BEFORE first Factory.
 * No provider replacement, clear/login, current-root signing, or hand-filled protocol state.
 * All rows and credentials are synthetic; actual production permissions remain in force. */
abstract class RetainedSessionJourneyFixture {
    private val stages = RetainedJourneyStages { auth.preserve(it) }
    @get:Rule(order = 0) val timeout: TestRule = stages
    protected val preparesSeparateJourney: Boolean get() = stages.requiresPreparation
    protected fun <T> prepareJourney(block: suspend () -> T): T = stages.prepare(block)
    protected fun preparedJourney(block: () -> Unit) = stages.business(block)
    @get:Rule(order = 2) internal val auth = LegacySessionHostPreferences()
    @get:Rule(order = 1) val widgetIsolation = TestRule { base: Statement, _: Description -> object : Statement() {
        override fun evaluate() {
            var failure: Throwable? = null
            mockkObject(com.dayforge.widget.WidgetRefreshScheduler)
            every { com.dayforge.widget.WidgetRefreshScheduler.request(any()) } returns null
            try { base.evaluate() } catch (error: Throwable) { failure = error }
            try {
                auth.requireCleanupCompleted()
                unmockkObject(com.dayforge.widget.WidgetRefreshScheduler)
            } catch (error: Throwable) {
                auth.preserve(error); failure = preserveLegacySessionMethodFailure(failure, error)
            }
            failure?.let { throw it }
        }
    } }
    internal val native = RetainedJourneyNative()
    @get:Rule(order = 3) val epochs = PhysicalDatabaseEpochFixture().also {
        it.nativeFactory = native; auth.bind(it)
        val original = it.beforeCleanup
        it.beforeCleanup = { stages.requireClear(); original?.invoke(); runBlocking { closeConsumers() } }
    }
    @get:Rule(order = 4) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 5) val compose = createEmptyComposeRule()
    @Inject lateinit var settings: PreferencesManager
    @Inject lateinit var metrics: LinkedMetricCoordinator
    @Inject lateinit var sync: SyncManager
    @Inject lateinit var gate: CountActionGate
    @Inject lateinit var themes: DeviceThemeController
    internal val app: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private val modelOwners = mutableListOf<ViewModel>()
    private var activity: ActivityScenario<ComponentActivity>? = null
    private var consumersClosed = false
    private var closeFailure: Throwable? = null
    private var originalRows: JsonElement? = null
    private var originalFingerprint: Pair<Long, String>? = null
    private var initialRoot: CountCommandRootCodec.Header? = null
    private val generationKey = stringPreferencesKey("auth_session_generation")

    @Before fun connectOwners() { hilt.inject(); epochs.connect() }
    @After fun finishOwners() = runBlocking<Unit> { stages.cleanup(); auth.drainMethod(); closeConsumers() }

    private suspend fun closeConsumers() {
        closeFailure?.let { throw it }
        if (consumersClosed) return
        try {
            var activityJob: Job? = null
            activity?.onActivity { activityJob = requireNotNull(it.lifecycleScope.coroutineContext[Job]) }
            activity?.close(); activity = null
            withTimeout(5_000) { activityJob?.join() }
            val jobs = withContext(Dispatchers.Main.immediate) {
                modelOwners.map { requireNotNull(it.viewModelScope.coroutineContext[Job]) }.also { models.clear() }
            }
            withTimeout(5_000) { jobs.forEach { it.join() } }
            check(jobs.all { it.isCompleted })
            if (::themes.isInitialized) withTimeout(5_000) { themes.close() }
            // current()/Ready alone does not terminate the eager preferences consumer.
            consumersClosed = true
        } catch (error: Throwable) { closeFailure = error; auth.preserve(error); throw error }
    }

    internal suspend fun generation(): String? = auth.store.data.first()[generationKey]
    internal suspend fun <T> current(block: suspend (DatabaseEpochGraph) -> T): T = epochs.host.withGraph(block = block)
    internal suspend fun image() = epochs.exactMain()
    internal fun owner(graph: DatabaseEpochGraph) = CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
    internal suspend fun row(reference: CountCommandReference) = current { owner(it).resolve(reference.native()).historical }

    /** Real KSP19 input, not a version pragma on a current database. No old/current root is
     * fabricated; current20 migration is solely responsible for signing its old raw stream. */
    internal suspend fun historical(localUnregistered: Boolean = false, closedDonor: RetainedV19Donor? = null) {
        check(originalRows == null)
        if (closedDonor == null) check(epochs.realRoomCreates == 0) else closedDonor.requireSourceClosed(epochs)
        // Real theme initialization may legitimately edit the shared preferences. Complete
        // it BEFORE the complete baseline/target observer, retaining all original assertions.
        withTimeout(45_000) { themes.current() }
        assertEquals(if (closedDonor == null) 0 else 1, epochs.realRoomCreates)
        assertNull(generation()); assertNull(epochs.tokens.localCoreWriteAccess())
        assertNotNull(epochs.tokens.localOpaqueMetricAccess())
        if (localUnregistered) {
            auth.store.edit { values ->
                // sync_account_id is the original local account binding, NOT registration.
                values.asMap().keys.filter { it.name.startsWith("sync_") && it.name != "sync_account_id" }
                    .forEach { values.remove(it) }
            }
            assertNull(epochs.tokens.localSyncAccess()); assertNull(generation())
        }
        check(!app.getDatabasePath("habit_database").exists())
        val schema = CountCommandMigrationFixture.asset(19)
        withContext(Dispatchers.IO) {
            FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app).name("habit_database")
                .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema.getValue("entities").jsonArray.map { it.jsonObject }.forEach { entity ->
                            val name = entity.getValue("tableName").jsonPrimitive.content
                            fun ddl(value: JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", name)
                            db.execSQL(ddl(entity.getValue("createSql")))
                            entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(ddl(it.jsonObject.getValue("createSql"))) }
                        }
                        schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                        closedDonor?.copyOriginalBusinessIntoNew19(db)
                        CountTrustSchema.install(db); SyncSchemaCallback.onOpen(db)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Expected new historical19")
                }).build()).use { helper ->
                val sql = helper.writableDatabase
                assertEquals(19, sql.version)
                sql.beginTransaction()
                try {
                    sql.execSQL("INSERT INTO count_command_results VALUES('legacy-native-id','old-input','old-fact',NULL,'retained old result','committed')")
                    closedDonor?.requireBusinessEquals(sql)
                    originalRows = resultRows(sql)
                    originalFingerprint = CountCommandLegacyNative.fingerprint(sql)
                    assertEquals(1L, requireNotNull(originalFingerprint).first)
                    sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    sql.setTransactionSuccessful()
                } finally { sql.endTransaction() }
            }
        }
        assertEquals(if (closedDonor == null) 0 else 1, epochs.realRoomCreates); assertNull(generation())
        auth.store.committedChanges.set(0)
    }

    internal suspend fun readyAndEnroll(failFirstMetadata: Boolean = false) {
        val prefsBefore = auth.store.data.first().asMap()
        data class Observation(val selections: Int, val ends: Int)
        val selectionsBefore = native.controlSelections.get()
        val endsBefore = native.controlEnds.get()
        val observed = AtomicReference<Observation?>()
        auth.store.observeNextGenerationChange(afterTransform = {
            // Only copy evidence in the real actor. Assertions belong to the owned test;
            // an observation failure must not kill an unrelated process-scoped coroutine.
            observed.set(Observation(native.controlSelections.get(), native.controlEnds.get()))
            if (failFirstMetadata) throw IOException("retained-joint-metadata-persistence")
        })
        fun requireObservation() {
            val evidence = requireNotNull(observed.get()) { "GENERATION_TARGET_TRANSFORM_NOT_OBSERVED" }
            assertTrue("Target control SELECT must be consumed", evidence.selections > selectionsBefore)
            assertTrue("Target actual native END must precede generation persistence", evidence.ends > endsBefore)
        }
        if (failFirstMetadata) {
            epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { }
            requireObservation()
            val failure = epochs.host.sessionPreparation.value as SessionPreparationState.RetryableFailure
            assertEquals("retained-joint-metadata-persistence", failure.cause.message)
            assertNull(generation()); assertEquals(prefsBefore, auth.store.data.first().asMap())
            val before = image()
            var entered = false
            val denied = try { current { entered = true }; null }
            catch (error: DatabaseRuntimeHost.SessionPreparationUnavailable) { error }
            assertNotNull(denied); assertFalse(entered); assertEquals(before, image())
            assertTrue(epochs.host.retrySessionPreparation(failure.attempt) is SessionPreparationRetry.Completed)
        }
        current { }
        requireObservation()
        current { graph ->
            assertTrue(epochs.host.sessionPreparation.value is SessionPreparationState.Satisfied)
            assertEquals(20, graph.database.openHelper.writableDatabase.version)
            val authRoot = CountCommandStorageInitialization.verifyRootAnchor(graph.database.openHelper.writableDatabase)
            assertEquals(CountCommandRootCodec.Mode.LEGACY_UNENROLLED, authRoot.header.mode)
            assertEquals(1L, authRoot.header.revision)
            assertEquals(requireNotNull(originalFingerprint).first, authRoot.header.legacyCount)
            assertEquals(requireNotNull(originalFingerprint).second, authRoot.header.legacyHash)
            initialRoot = authRoot.header
        }
        assertNotNull(generation()); assertNotEquals("legacy", generation()); assertEquals(1, auth.store.committedChanges.get())
        assertEquals(prefsBefore, auth.store.data.first().asMap().filterKeys { it != generationKey })
        native.arm()
        // Only this entire table was asserted here. Keep every native cell and rowid,
        // without constructing and strict-decoding an otherwise unused all-domain RAW.
        val before = resultRows()
        current { owner(it).ensureForwardEnrollment() }
        current { graph ->
            val root = CountCommandStorageInitialization.verifyRootAnchor(graph.database.openHelper.writableDatabase).header
            assertEquals(CountCommandRootCodec.Mode.ACCOUNT_BOUND_RETAINED, root.mode); assertEquals(3, root.format)
            assertEquals(2L, root.revision)
        }
        assertEquals(before, resultRows())
        assertTrue(native.ends.any { it.kind == RetainedJourneyNative.Kind.ENROLLMENT })
        assertRetained()
    }

    internal suspend fun assertRetained() = current { graph ->
        graph.database.withTransaction {
        val sql = graph.database.openHelper.writableDatabase
        val authenticated = CountCommandStorageInitialization.verifyRootAnchor(sql)
        val initial = requireNotNull(initialRoot)
        assertEquals(initial.storageId, authenticated.header.storageId); assertEquals(initial.datasetId, authenticated.header.datasetId)
        assertEquals(initial.legacyCount, authenticated.header.legacyCount); assertEquals(initial.legacyHash, authenticated.header.legacyHash)
        assertEquals(CountCommandRootCodec.Mode.ACCOUNT_BOUND_RETAINED, authenticated.header.mode)
        val context = currentCoroutineContext()
        CountCommandRetainedPartition.verify(sql, authenticated) { context.ensureActive() }
        val all = resultRows(sql)
        val old = all.filter { it.jsonObject.getValue("commandId").jsonObject.getValue("value") == JsonPrimitive("legacy-native-id") }
        assertEquals(requireNotNull(originalRows), JsonArray(old))
        }
        val page = owner(graph).discover(32)
        assertEquals(1L, page.retainedCount)
        assertFalse(page.commands.any { it.reference.commandId == "legacy-native-id" })
    }

    /** The exact original migration-snapshot native framing for the only table asserted
     * at these sites. Fixed MAIN SQL cannot choose a smaller set of columns or a namespace.
     * Full image()/donor proofs remain where their complete contents are actually asserted. */
    private fun resultRows(sql: SupportSQLiteDatabase): JsonArray =
        sql.query("SELECT rowid AS __recovery_rowid,* FROM main.count_command_results ORDER BY rowid").use { cursor ->
            buildJsonArray {
                while (cursor.moveToNext()) add(buildJsonObject {
                    for (index in 0 until cursor.columnCount) put(cursor.getColumnName(index), buildJsonObject {
                        put("sqlite_type", cursor.getType(index))
                        put("value", when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> JsonNull
                            Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
                            Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(index).toRawBits().toString(16))
                            Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(index))
                            Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(cursor.getBlob(index).joinToString("") { "%02x".format(it.toInt() and 255) })
                            else -> error("Unknown native cell")
                        })
                    })
                })
            }
        }

    private suspend fun resultRows(): JsonArray = current { graph -> graph.database.withTransaction {
        resultRows(graph.database.openHelper.writableDatabase)
    } }

    internal data class Objects(val habit: HabitEntity, val metrics: List<MetricEntity>)
    internal suspend fun legacyObjects(): Objects = current { graph -> graph.database.withTransaction {
        val habit = HabitEntity(uuid = legacyGenerationId(90), name = "Retained COUNT", habitType = HabitType.COUNTING,
            iconResId = 0, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 1)
        val savedHabit = habit.copy(id = graph.habitDao.insert(habit))
        val values = (0..1).map { i ->
            val value = MetricEntity(uuid = legacyGenerationId(91 + i), name = "Retained metric $i", unit = "kg", iconResId = 0, colorHex = "#123456")
            value.copy(id = graph.metricDao.insert(value)).also { metric ->
                graph.habitMetricLinkDao.insertOrIgnore(HabitMetricLinkEntity(habitId = savedHabit.id, habitUuid = savedHabit.uuid,
                    metricId = metric.id, metricUuid = metric.uuid, promptOnComplete = true))
            }
        }
        Objects(savedHabit, values)
    } }

    internal suspend fun assertDashboard(habit: HabitEntity, quantity: Long) = current { graph ->
        val batch = graph.dashboardReadRepository.readProjection(ZonedDateTime.now())
        val cards = graph.dashboardHabitListBuilder.project(batch)
        val selected = cards.single { it.habit.uuid == habit.uuid }
        assertEquals(quantity, selected.actualTodayCount)
        assertEquals(quantity, batch.completions.count { it.habitUuid == habit.uuid }.toLong())
        var published = false
        assertTrue(graph.dashboardReadRepository.publish(batch) { published = true })
        assertTrue(published)
    }

    internal fun showLinkedPrompt(frame: DatabaseEpochValue<LinkedMetricPromptState?>): CompletableDeferred<Boolean> {
        val state = requireNotNull(frame.value)
        check(activity == null)
        val submitted = CompletableDeferred<Boolean>()
        activity = ActivityScenario.launch(ComponentActivity::class.java).also { scenario -> scenario.onActivity {
            it.setContent { MaterialTheme {
                PostCheckInDialog(state.habitName, state.linkedMetrics,
                    onRecord = { values, neverAsk ->
                        try {
                            val saved = epochs.host.withSourceGraph(frame.publication) {
                                metrics.recordMetricValues(state.habitId, values,
                                    expectedPreferenceClaim = state.preferenceClaim, frame = frame, neverAskAgain = neverAsk)
                            }
                            check(saved) { "Retained original linked submission was rejected" }
                            submitted.complete(saved)
                        } catch (error: Throwable) { submitted.completeExceptionally(error); throw error }
                    }, onSkip = { error("Unexpected skip in fixed linked submission") },
                    onDismiss = { error("Unexpected dismissal in fixed linked submission") },
                    promptIdentity = requireNotNull(state.habitPrompt?.command).reference.commandId)
            } }
        } }
        compose.waitUntil(45_000) {
            compose.onAllNodes(hasText(app.getString(R.string.post_checkin_record)) and hasClickAction() and hasAnyAncestor(isDialog()))
                .fetchSemanticsNodes().size == 1
        }
        return submitted
    }

    internal suspend fun showPanel(): CountCommandRecoveryViewModel {
        val model = withContext(Dispatchers.Main.immediate) {
            CountCommandRecoveryViewModel(epochs.host, CountCommandRecoveryWorkflow(app, epochs.host, gate)).also {
                models.put("retained-recovery-${modelOwners.size}", it); modelOwners += it
            }
        }
        check(activity == null)
        activity = ActivityScenario.launch(ComponentActivity::class.java).also { scenario -> scenario.onActivity {
            it.setContent { MaterialTheme { CountCommandRecoveryPanel(model) } }
        } }
        compose.onNodeWithText(app.getString(R.string.count_recovery_open)).performClick()
        compose.waitUntil(45_000) { model.frames.value != null && !model.busy.value }
        assertFalse(model.failed.value)
        return model
    }

    @Suppress("DEPRECATION")
    internal fun capturePanel(label: String) {
        require(label.matches(Regex("[a-z_]+")))
        val supplied = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")))
        val owned = app.externalMediaDirs.filterNotNull().map { File(it, "additional_test_output").canonicalFile }
        check(app.packageName == "com.dayforge.testbed" && supplied.isAbsolute && supplied.canonicalFile in owned)
        val output = File(supplied, "retained-joint-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        var complete = false
        android.util.Log.i("RetainedJourneyEvidence", "label=$label event=start")
        try {
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                val file = File(output, "$label.png")
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                // Compression success AND stream close must precede a complete PNG framing.
                // Keep any failed/partial file as evidence, never replace it with an empty pass.
                RandomAccessFile(file, "r").use { input ->
                    check(input.length() > 20)
                    val signature = ByteArray(8).also(input::readFully)
                    check(signature.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
                    input.seek(input.length() - 12)
                    val end = ByteArray(12).also(input::readFully)
                    check(end.contentEquals(byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, 0xae.toByte(), 0x42, 0x60, 0x82.toByte())))
                }
            } finally { bitmap.recycle() }
            complete = true
        } finally {
            android.util.Log.i("RetainedJourneyEvidence", "label=$label event=end complete=$complete elapsed_ms=${(android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000}")
        }
    }
}
