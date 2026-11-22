package com.dayforge.ui

import android.database.Cursor
import android.os.Build
import android.view.MotionEvent
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.filters.SdkSuppress
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.repository.*
import com.dayforge.data.model.SyncProgress
import com.dayforge.domain.model.initialChallengeRoundHead
import com.dayforge.domain.service.*
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.screens.dashboard.*
import com.dayforge.ui.screens.nested.*
import com.dayforge.widget.CountJourneyWidgetRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Two complete actual-screen diagnostic journeys. Each retains ordinary150s and original
 * interaction budgets. Two observations are NOT P95, release performance or process-cold.
 * Native, model, committed draw and screenshot visibility upper bounds are distinct. */
@HiltAndroidTest
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class CountFormalVisibleJourneyTest {
    private val native = CountJourneyNativeTrace()
    @get:Rule(order = 0) val widgets = CountJourneyWidgetRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also {
        it.nativeFactory = native
        it.beforeCleanup = widgets::assertSafeForHostCleanup
    }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var completion: HabitCompletionCoordinator
    @Inject lateinit var deletion: HabitDeletionCoordinator
    @Inject lateinit var windows: DashboardTimeWindowTicker
    @Inject lateinit var lifecycle: HabitLifecycleCoordinator
    @Inject lateinit var timers: HabitTimerCoordinator
    @Inject lateinit var metrics: LinkedMetricCoordinator
    @Inject lateinit var counts: CountActionGate
    @Inject lateinit var sync: SyncManager
    @Inject internal lateinit var http: NextSyncHttp
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private var model: ViewModel? = null
    private var dashboard: DashboardViewModel? = null
    private var nested: NestedViewModel? = null
    private var activity: ActivityScenario<ComponentActivity>? = null
    private var peer: FormalCountJourneyServer? = null
    private var observer: CountJourneyWindows? = null
    @Volatile private var journeyExited = true
    @Volatile private var diagnosticStartedAt = 0L
    private var evidenceDirectory: File? = null
    private var countInputEvidence: CountJourneyWindows.Input? = null
    private var recordInputEvidence: CountJourneyWindows.Input? = null
    private var recordBoundsEvidence: JsonArray? = null
    @Volatile private var imeEvidence: JsonObject? = null
    private val modelTimes = ConcurrentHashMap<String, Long>()
    private val countSyncAttempt = SyncAttempt("count_commit_wakeup")
    private val metricSyncAttempt = SyncAttempt("metric_commit_wakeup")
    private lateinit var habit: HabitEntity
    private val buttonFrames: StateFlow<DatabaseEpochValue<Map<String, CountButtonModel>>?> get() =
        dashboard?.countButtonFrame ?: requireNotNull(nested).countButtonFrame
    private val promptFrames get() = dashboard?.postCheckInFrame ?: requireNotNull(nested).postCheckInFrame
    private fun now() = SystemClock.elapsedRealtimeNanos()
    /** The existing AGP/UTP additional-output plugin copies this exact argument directory
     * to the host before uninstall, for both passed and failed suites. No private fallback. */
    @Suppress("DEPRECATION") // AGP8.11.1's own API29+ output contract uses this app's media directory.
    private fun beginEvidence(child: Boolean): File {
        check(app.packageName == "com.dayforge.testbed")
        val argument = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) {
            "AGP additionalTestOutputDir is required before this evidence-producing journey"
        }
        val output = File(argument)
        check(output.isAbsolute)
        val owned = app.externalMediaDirs.filterNotNull().map { File(it, "additional_test_output").canonicalFile }
        check(output.canonicalFile in owned) { "Additional output must remain in this testbed's own media directory" }
        val name = "${if (child) "nested" else "dashboard"}-${UUID.randomUUID()}"
        val directory = File(output, "formal-visible-journey/$name")
        check(directory.mkdirs())
        evidenceDirectory = directory
        File(directory, "started.json").writeText(buildJsonObject {
            put("format", 1); put("class", "com.dayforge.ui.CountFormalVisibleJourneyTest")
            put("method", if (child) "nestedFormalCountMetricJourneyWithRealAckOverlap" else "dashboardFormalCountMetricJourneyWithRealAckOverlap")
            put("screen", if (child) "nested" else "dashboard"); put("directory", name)
            put("started_elapsed_ns", now()); put("channel", "AGP_additionalTestOutputDir")
        }.toString())
        return directory
    }
    private fun endEvidence(error: Throwable?) {
        val directory = evidenceDirectory ?: return
        File(directory, "terminal.json").writeText(buildJsonObject {
            put("format", 1); put("body", if (error == null) "returned" else "failed")
            put("stage", stage); put("ended_elapsed_ns", now())
            put("error_class", error?.javaClass?.name?.let(::JsonPrimitive) ?: JsonNull)
            // This is body evidence only. Original JUnit/teardown outcomes remain authoritative.
            put("junit_pass_claim", false)
            put("background_sync", syncAttemptEvidence())
        }.toString())
    }
    private var stage = "not_started"
    private fun stage(value: String) {
        stage = value
        Log.i("CountVisibleJourneyStage", "$value elapsed_ns=${now()}")
    }

    @After fun finish() = runBlocking<Unit> {
        if (widgets.real) { finishRealWidgets(); return@runBlocking }
        observer?.close(); activity?.close()
        model?.let { value ->
            val job = withContext(Dispatchers.Main.immediate) {
                requireNotNull(value.viewModelScope.coroutineContext[Job]).also { models.clear() }
            }
            withTimeout(5_000) { job.join() }
        }
        peer?.close()
    }

    private suspend fun finishRealWidgets() {
        var failure: Throwable? = null
        suspend fun finish(block: suspend () -> Unit): Boolean = try { block(); true } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
            false
        }
        finish { observer?.close() }
        var activityJob: Job? = null
        val activityCaptured = finish { activity?.onActivity { activityJob = requireNotNull(it.lifecycleScope.coroutineContext[Job]) } }
        val activityClosed = finish { activity?.close() }
        val activityJoined = finish { activityJob?.let { withTimeout(5_000) { it.join() } } }
        val modelJoined = finish { model?.let { value ->
            val job = withContext(Dispatchers.Main.immediate) {
                requireNotNull(value.viewModelScope.coroutineContext[Job]).also { models.clear() }
            }
            withTimeout(5_000) { job.join() }
        } }
        finish {
            check(journeyExited && activityCaptured && activityClosed && activityJoined && modelJoined) {
                "Cannot drain real widgets before all journey/Activity/ViewModel producers join"
            }
            // This original synthetic COUNT/GOAL journey has no timed alarm producer.
            // Validate the narrow scene before sealing; zero installed widgets alone is
            // not evidence that the real Focus alarm/authentication/Room work was absent.
            if (peer != null) withTimeout(5_000) { epochs.current { graph ->
                check(graph.habitDao.getVisibleHabitsOnce().none {
                    it.isActive && it.bestTime != null && it.habitType != com.dayforge.data.model.HabitType.GOAL
                }) { "A timed widget alarm producer remains in this calibration scene" }
                check(graph.timeLogDao.getActiveTimeLog() == null) { "An active timer remains in this calibration scene" }
            } }
            widgets.drainAfterProducersJoined()
        }
        finish { widgets.writeEvidence(evidenceDirectory) }
        finish { peer?.close() }
        finish {
            check(diagnosticStartedAt != 0L && now() - diagnosticStartedAt <= 150_000_000_000L) {
                "Original 150s case budget exceeded including real widget cleanup"
            }
        }
        failure?.let { throw it }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    private suspend fun screen(child: Boolean) {
        withContext(Dispatchers.Main.immediate) {
            model = if (child) NestedViewModel(app, epochs.host, completion, deletion, preferences, metrics, lifecycle, timers, counts)
                .also { nested = it }
            else DashboardViewModel(app, epochs.host, completion, deletion, windows, lifecycle, timers, preferences, metrics, counts)
                .also { dashboard = it }
            models.put("formal-visible-journey", requireNotNull(model))
        }
        activity = ActivityScenario.launch(ComponentActivity::class.java)
        // The activity window listener precedes setContent. Later Dialog windows have the
        // explicit existing-frame screenshot path if their first draw was already missed.
        requireNotNull(observer).attach()
        requireNotNull(activity).onActivity { screen ->
            requireNotNull(observer).observeInput(screen.window) { habit.id in counts.busyHabitIds.value }
            screen.setContent { MaterialTheme {
            val size = WindowSizeClass.calculateFromSize(DpSize(400.dp, 800.dp))
            if (child) NestedScreen(requireNotNull(nested), size, {})
            else DashboardScreen(requireNotNull(dashboard), size, {}, {}, {}, {})
        } } }
        requireNotNull(observer).attach()
    }
    private suspend fun selection(quantity: Long): DatabaseObjectSelection = withTimeout(45_000) {
        dashboard?.let { vm ->
            val value = vm.habitsWithStats.first { rows -> rows.any { it.habit.uuid == habit.uuid &&
                it.actualTodayCount == quantity && it.countActionIdentity != null && it.sourceEpoch?.isCurrentInMemory() == true } }
                .single { it.habit.uuid == habit.uuid }
            requireNotNull(value.renderedSelection())
        } ?: requireNotNull(nested).let { vm ->
            val value = vm.topLevelHabitsWithChildren.first { parents -> parents.flatMap { it.children }.any {
                it.habit.uuid == habit.uuid && it.actualTodayCount == quantity && it.countActionIdentity != null &&
                    it.sourceEpoch?.isCurrentInMemory() == true } }.single { parent -> parent.children.any { it.habit.uuid == habit.uuid } }
            assertEquals(if (quantity >= 1L) 1 else 0, value.completedChildren)
            requireNotNull(value.children.single { it.habit.uuid == habit.uuid }.renderedSelection())
        }
    }
    private suspend fun reference(id: String): CountCommandStore.Reference = epochs.current { graph -> graph.database.withTransaction {
        graph.database.openHelper.writableDatabase.query("SELECT intentJson FROM main.count_command_intents WHERE commandId=? LIMIT 2", arrayOf(id)).use {
            check(it.moveToFirst()); val bytes = it.getString(0).toByteArray(); check(!it.moveToNext())
            val input = CountCommandRecords.Intent.decode(bytes, bytes.size) {}
            CountCommandStore.Reference(input.commandId, input.inputHash, input.namespace.hash, input.namespace.dataset)
        }
    } }
    /** One small, independent pre-click observation of all eight original native rows.
     * Explicit current columns preserve rowid, every field and SQLite type; no table is
     * filtered and no full-database scan is added to the timing-sensitive click path. */
    private fun originalFacts(sql: SupportSQLiteDatabase): List<JsonObject> {
        val columns = sql.query("PRAGMA main.table_info(`completions`)").use { cursor -> buildList {
            while (cursor.moveToNext()) add(cursor.getString(1).also { require(it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) })
        } }
        require(columns.isNotEmpty() && "__recovery_rowid" !in columns)
        return sql.query("SELECT rowid AS __recovery_rowid,${columns.joinToString(",") { "`$it`" }} FROM main.completions ORDER BY rowid").use { cursor -> buildList {
            while (cursor.moveToNext()) add(buildJsonObject { for (column in 0 until cursor.columnCount) {
                put(cursor.getColumnName(column), buildJsonObject {
                    val type = cursor.getType(column); put("sqlite_type", type)
                    put("value", when (type) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(column))
                        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(column).toRawBits().toString(16))
                        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(column))
                        Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(replicaStorageBlobHex(cursor.getBlob(column)))
                        else -> error("Invalid synthetic native fact type")
                    })
                })
            } })
        } }
    }
    private fun nativeValue(row: JsonObject, column: String, type: Int): JsonPrimitive {
        val value = row.getValue(column).jsonObject
        assertEquals(setOf("sqlite_type", "value"), value.keys)
        assertEquals(JsonPrimitive(type), value.getValue("sqlite_type"))
        return value.getValue("value").jsonPrimitive
    }
    private data class SyncInterval(val start: Long, val end: Long, val retry: NextSyncRetryRequired?)
    private data class SyncTerminal(val at: Long, val outcome: String, val failure: Throwable?)
    private data class SyncWakeup(val observedAt: Long, val nativeEnd: Long, val sequence: Long)
    private class SyncAttempt(val id: String) {
        @Volatile var waitStarted: Long? = null
        @Volatile var wakeup: SyncWakeup? = null
        @Volatile var start: Long? = null
        @Volatile var resultObserved: Long? = null
        @Volatile var terminal: SyncTerminal? = null
        @Volatile var diagnosticFailure: Throwable? = null
        val progress = CopyOnWriteArrayList<Pair<Long, SyncProgress>>()
    }
    private suspend fun originalSync(attempt: SyncAttempt,
        committed: Deferred<CountJourneyNativeTrace.End>): SyncInterval {
        try {
            attempt.waitStarted = now()
            val signal = committed.await() // Original successful native END, never an input/tap hint.
            attempt.wakeup = SyncWakeup(now(), signal.endAt, signal.sequence)
            val start = now(); attempt.start = start
            val result = sync.sync { value ->
                // Diagnostic failure must not replace the original sync Result or cancellation.
                try { attempt.progress += now() to value }
                catch (diagnostic: Throwable) { if (attempt.diagnosticFailure == null) attempt.diagnosticFailure = diagnostic }
            }
            val end = now(); attempt.resultObserved = end
            val failure = result.exceptionOrNull()
            if (failure != null && (failure !is NextSyncRetryRequired || attempt.diagnosticFailure != null)) throw failure
            attempt.diagnosticFailure?.let { throw it }
            val retry = failure as? NextSyncRetryRequired
            attempt.terminal = SyncTerminal(end, if (retry == null) "success" else "retry_required", retry)
            return SyncInterval(start, end, retry) // Retry remains an unfinished pass, not success.
        } catch (failure: Throwable) {
            try {
                attempt.diagnosticFailure?.let { if (it !== failure) failure.addSuppressed(it) }
                attempt.terminal = SyncTerminal(now(), if (failure is CancellationException) "cancelled" else "failed", failure)
            } catch (diagnostic: Throwable) { if (diagnostic !== failure) failure.addSuppressed(diagnostic) }
            throw failure // Ordinary failures and cancellation retain their original object/structured owner.
        }
    }
    private fun syncAttemptEvidence() = buildJsonObject {
        put("driver", "two_direct_commit_triggered_SyncManager_calls")
        put("real_widget_refresh_worker", widgets.real)
        put("auto_sync_worker_or_workmanager_ack", false)
        put("retry_is_success", false); put("attempt_name_proves_ack_ownership", false)
        put("interval_kind", "whole_sync_call_including_queue_auth_http_and_local_work")
        put("ack_request_key_capture_enabled", CompletionActionTiming.enabled)
        put("attempts", JsonArray(listOf(countSyncAttempt, metricSyncAttempt).map { attempt ->
            val terminal = attempt.terminal
            val start = attempt.start
            val wakeup = attempt.wakeup
            buildJsonObject {
                put("attempt_id", attempt.id); put("trigger", attempt.id)
                put("wait_started_ns", attempt.waitStarted?.let(::JsonPrimitive) ?: JsonNull)
                put("native_commit_end_ns", wakeup?.nativeEnd?.let(::JsonPrimitive) ?: JsonNull)
                put("native_commit_sequence", wakeup?.sequence?.let(::JsonPrimitive) ?: JsonNull)
                put("wakeup_observed_ns", wakeup?.observedAt?.let(::JsonPrimitive) ?: JsonNull)
                put("sync_call_start_ns", start?.let(::JsonPrimitive) ?: JsonNull)
                put("sync_result_observed_ns", attempt.resultObserved?.let(::JsonPrimitive) ?: JsonNull)
                put("terminal_ns", terminal?.at?.let(::JsonPrimitive) ?: JsonNull)
                put("outcome", terminal?.outcome ?: if (start != null) "running" else
                    if (attempt.waitStarted != null) "waiting_for_native_commit" else "not_started")
                put("error_class", terminal?.failure?.javaClass?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("diagnostic_error_class", attempt.diagnosticFailure?.javaClass?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("progress_is_mutex_acquisition", false)
                put("progress", JsonArray(attempt.progress.toList().map { (at, value) -> buildJsonObject {
                    put("at_ns", at)
                    put("stage", when (value) {
                        SyncProgress.Idle -> "idle"
                        is SyncProgress.UploadingChanges -> "uploading_changes"
                        SyncProgress.Downloading -> "downloading"
                        SyncProgress.Recovering -> "recovering"
                        SyncProgress.Success -> "success"
                        SyncProgress.WaitingForMaterials -> "waiting_for_materials"
                        is SyncProgress.Error -> "error"
                    })
                    if (value is SyncProgress.UploadingChanges) { put("current", value.current); put("total", value.total) }
                    if (value is SyncProgress.Error) put("network_failure", value.isNetworkFailure)
                    // Error progress/messages are not classified as retry and do not disclose response bodies.
                } }))
            }
        }))
    }

    private suspend fun journey(child: Boolean) = coroutineScope {
        check(Build.VERSION.SDK_INT >= 29)
        val setupStarted = now(); stage("bootstrap")
        val evidence = beginEvidence(child) // Before the click clocks; still inside the original150s method.
        hilt.inject(); epochs.connect(); epochs.login()
        val server = FormalCountJourneyServer(child).also { peer = it }
        preferences.setServerUrl(server.server.origin.toString()); preferences.setRemoteServerUrl(null)
        preferences.setFilterMode("all")
        sync.sync().getOrThrow() // Genuine production discovery/registration/rounds bootstrap.
        lateinit var bootstrapFacts: List<CompletionEntity>
        lateinit var bootstrapNativeFacts: List<JsonObject>
        epochs.current { graph ->
            assertEquals(1, graph.database.nextSyncStateDao().rows().single().challengeContract)
            habit = requireNotNull(graph.habitDao.getHabitByUuid(server.selected.uuid))
            assertNotNull(habit.appearance); assertNotNull(habit.planMetadata)
            assertNotNull(requireNotNull(graph.nextObjectCreator.captureForNavigation()).rounds)
            bootstrapFacts = graph.completionDao.getAllCompletionsOnce().sortedBy { it.uuid }
            assertEquals(8, bootstrapFacts.size)
            bootstrapNativeFacts = graph.database.withTransaction { originalFacts(graph.database.openHelper.writableDatabase) }
            assertEquals(8, bootstrapNativeFacts.size)
            assertTrue(graph.syncOutboxDao.getAll().isEmpty())
            preferences.setNeverAskAgain(requireNotNull(epochs.tokens.localCoreWriteAccess()).session, habit.uuid, false)
        }
        if (child) preferences.setExpandedParentUuids(setOf(server.goal.uuid))
        val observed = CountJourneyWindows(evidence).also { observer = it }
        stage("screen_open")
        val opened = now(); screen(child)
        stage("screen_returned_selection_zero_wait")
        val originalSelection = selection(0)
        stage("selection_zero_ready_visible_wait")
        val label = app.getString(R.string.action_check_in)
        val ready = observed.seen("ready", opened) { it.button(label, habit.name)?.enabled == true }
        assertTrue(originalSelection.publication.isCurrentInMemory())
        val button = requireNotNull(ready.snapshot.button(label, habit.name))
        stage("count_input")
        native.arm()
        val countSync = async(Dispatchers.Default) { originalSync(countSyncAttempt, native.countCommitted) }
        val metricSync = async(Dispatchers.Default) { originalSync(metricSyncAttempt, native.metricsCommitted) }
        val acceptedModel = async(Dispatchers.Main.immediate) {
            val frame = withTimeout(45_000) { buttonFrames.first {
                it?.value?.get(habit.uuid)?.kind == CountButtonModel.Kind.SAVED_ADD
            } }!!
            val at = now(); modelTimes["count_model_observed_ns"] = at; at to frame
        }
        val preparedPrompt = async(Dispatchers.Main.immediate) {
            val frame = withTimeout(45_000) { promptFrames.first { it?.value?.factPrompt?.command != null } }!!
            val at = now(); modelTimes["prompt_model_observed_ns"] = at; at to frame
        }
        val sharedRow = async(Dispatchers.Main.immediate) {
            val source = selection(1); val at = now(); modelTimes["shared_row_observed_ns"] = at; at to source
        }
        // Subscribe before physical input. A short/conflated busy state is reported as
        // unobserved, never awaited after it may already have ended.
        val gateSamples = CopyOnWriteArrayList<Pair<Long, Boolean>>()
        val gateObserver = launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            counts.busyHabitIds.collect { gateSamples += now() to (habit.id in it) }
        }
        val click = observed.tap(button).also { countInputEvidence = it }
        val mayTouchBusy = withContext(Dispatchers.Main.immediate) {
            habit.id in counts.busyHabitIds.value && !native.countCommitted.isCompleted
        }
        // The precheck is not evidence. Actual Window ACTION_DOWN/UP and the native END
        // timestamps below classify this optional second touch, including a lost race.
        val ignoredBusy = if (mayTouchBusy) observed.touch(button.bounds) else null
        stage("count_feedback_visible_wait")
        val busy = observed.seen("count_feedback", click.started) {
            it.state(app.getString(R.string.widget_count_processing)) ||
                it.state(app.getString(R.string.count_action_saved_add)) || it.text(app.getString(R.string.post_checkin_title))
        }
        stage("count_model_wait")
        val (modelAt, modelFrame) = acceptedModel.await(); assertTrue(modelFrame.publication.isCurrentInMemory())
        val buttonModel = requireNotNull(modelFrame.value[habit.uuid]); val parentId = requireNotNull(buttonModel.commandId)
        stage("prompt_model_wait")
        val (promptModelAt, promptFrame) = preparedPrompt.await()
        assertEquals(parentId, promptFrame.value?.factPrompt?.command?.commandId)
        assertTrue(promptFrame.publication.isCurrentInMemory())
        val promptState = requireNotNull(promptFrame.value); val prompt = requireNotNull(promptState.factPrompt)
        assertEquals(2, promptState.linkedMetrics.size); assertNotNull(prompt.completion)
        val parent = requireNotNull(prompt.command); assertEquals(parentId, parent.commandId)
        stage("required_prompt_visible_wait")
        val promptSeen = observed.seen("metric_prompt", click.started) { snap ->
            snap.text(app.getString(R.string.post_checkin_title)) && server.metrics.all { snap.text("${it.name} (${it.unit})") }
        }
        stage("shared_row_model_wait")
        val (cardModelAt, cardModelSource) = sharedRow.await()
        assertTrue(cardModelSource.publication.isCurrentInMemory())
        // This is a model correspondence only while the modal dialog covers the card.
        // We do not manufacture a visible overall-progress badge that Dashboard never renders.
        stage("metric_input")
        val typingStarted = now()
        server.metrics.forEachIndexed { index, metric ->
            stage(if (index == 0) "first_metric_text_input_wait" else "second_metric_text_input_wait")
            observed.replaceText("${metric.name} (${metric.unit})", (41.125 + index).toString())
        }
        val saveLabel = app.getString(R.string.post_checkin_record)
        stage("actual_ime_back_and_hidden_wait")
        val dismissedIme = observed.dismissImeIfVisible { imeEvidence = it }
        val preservedPrompt = requireNotNull(promptFrames.value)
        assertSame(prompt, preservedPrompt.value?.factPrompt)
        assertSame(promptFrame.publication, preservedPrompt.publication)
        assertTrue(preservedPrompt.publication.isCurrentInMemory())
        stage("typed_metrics_visible_wait")
        val typed = observed.seen("typed_metrics", typingStarted) { snapshot ->
            snapshot.imeVisible == false && snapshot.imeBottom == 0 &&
                snapshot.text(app.getString(R.string.post_checkin_title)) &&
                snapshot.nodes.count { it.editable in setOf("41.125", "42.125") } == 2 &&
                snapshot.button(saveLabel)?.let { it.enabled && it.fullyVisible } == true
        }
        val recordButton = requireNotNull(typed.snapshot.button(saveLabel))
        recordBoundsEvidence = JsonArray(listOf(recordButton.bounds.left, recordButton.bounds.top,
            recordButton.bounds.right, recordButton.bounds.bottom).map(::JsonPrimitive))
        val record = observed.tap(recordButton).also { recordInputEvidence = it }
        stage("metric_feedback_visible_wait")
        val metricBusy = observed.seen("metric_busy", record.started) { snapshot ->
            snapshot.nodes.any { it.editable == "41.125" && !it.enabled } ||
                native.delivered.isCompleted && !snapshot.text(app.getString(R.string.post_checkin_title)) &&
                    snapshot.button(label, habit.name)?.enabled == true
        }
        stage("metric_native_end_wait")
        val childEnd = withTimeout(45_000) { native.metricsCommitted.await() }
        stage("delivery_native_end_wait")
        val deliveryEnd = withTimeout(45_000) { native.delivered.await() }
        stage("prompt_cleared_wait")
        withTimeout(45_000) { promptFrames.first { it?.value == null } }
        stage("gate_idle_wait")
        withTimeout(45_000) { counts.busyHabitIds.first { habit.id !in it } }
        stage("selection_one_wait")
        val finalSelection = selection(1)
        gateObserver.cancelAndJoin()
        stage("next_action_visible_wait")
        val visibleResult = observed.seen("next_action", record.started) { snapshot ->
            snapshot.button(label, habit.name)?.enabled == true && snapshot.text(app.getString(R.string.timer_countup_progress, 1L, 1))
        }
        assertTrue(finalSelection.publication.isCurrentInMemory())
        stage("count_ack_wait")
        val countAck = countSync.await()
        stage("metric_ack_wait")
        val metricAck = metricSync.await()
        val completedSyncAttempts = listOf(countAck, metricAck)
        assertTrue("At least one original direct sync pass must complete successfully",
            completedSyncAttempts.any { it.retry == null })
        assertTrue("At most one of the original two direct passes may remain retry-required",
            completedSyncAttempts.count { it.retry != null } <= 1)
        stage("count_native_end_wait")
        val nativeCount = native.countCommitted.await()
        val secondDispatch = ignoredBusy?.let { observed.dispatches(it) }.orEmpty()
        val secondDown = secondDispatch.singleOrNull { it.action == MotionEvent.ACTION_DOWN }
        val secondUp = secondDispatch.singleOrNull { it.action == MotionEvent.ACTION_UP }
        val secondBeforeT1 = secondDown != null && secondUp != null && secondUp.returnedAt < nativeCount.endAt
        val secondWithinGate = secondDown?.busyBefore == true && secondUp?.busyBefore == true
        val secondClassification = when {
            ignoredBusy == null -> "not_attempted_fast_or_no_busy"
            secondDown == null || secondUp == null -> "not_observed_in_original_activity_window"
            secondWithinGate && secondBeforeT1 -> "actual_busy_dispatch_before_count_t1"
            secondWithinGate -> "actual_busy_dispatch_after_or_across_count_t1"
            else -> "actual_dispatch_outside_observed_busy_gate"
        }
        Log.i("CountVisibleJourneyInput", "second_touch=$secondClassification actual_events=${secondDispatch.size}")
        val childReceipt = requireNotNull(prompt.submittedCommand)
        assertNotEquals(parentId, childReceipt.commandId)
        stage("terminal_oracle")
        val parentRef = reference(parentId); val childRef = reference(childReceipt.commandId)
        epochs.current { graph ->
            val store = CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
            val p = store.resolve(parentRef).historical; val c = store.resolve(childRef).historical
            assertEquals(CountCommandRecords.Execution.APPLIED, p.status.execution)
            assertEquals(CountCommandRecords.Execution.APPLIED, c.status.execution)
            assertEquals(CountCommandRecords.Delivery.DELIVERED, p.status.delivery)
            assertEquals(CountCommandRecords.Delivery.DELIVERED, c.status.delivery)
            assertEquals(childReceipt.commandId, p.status.child); assertEquals(parentId, c.status.parent)
            assertEquals(2, requireNotNull(c.outcome).effects.size)
            assertTrue(store.discover(8).commands.isEmpty())
            val finalFacts = graph.completionDao.getAllCompletionsOnce()
            assertEquals(9, finalFacts.size)
            val originalFactIds = bootstrapFacts.map { it.uuid }.toSet()
            assertEquals(bootstrapFacts, finalFacts.filter { it.uuid in originalFactIds }.sortedBy { it.uuid })
            val parentInput = p.intent.body().getValue("input").jsonObject
            val reservedCount = CountCommandRecords.uuid(parentInput, "reserved_fact_uuid")
            assertEquals(reservedCount, finalFacts.single { it.uuid !in originalFactIds }.uuid)
            assertEquals(habit.uuid, CountCommandRecords.uuid(parentInput, "habit_uuid"))
            assertEquals(1L, CountCommandRecords.integer(parentInput, "quantity"))
            val childInput = c.intent.body().getValue("input").jsonObject
            assertEquals(parentId, CountCommandRecords.uuid(childInput, "parent_command_id"))
            assertEquals(reservedCount, CountCommandRecords.uuid(childInput, "source_fact_uuid"))
            assertEquals(habit.uuid, CountCommandRecords.uuid(childInput, "habit_uuid"))
            assertEquals(server.zone.id, CountCommandRecords.text(childInput, "timezone"))
            val metricInputs = childInput.getValue("metrics").jsonArray.map { it.jsonObject }
            assertEquals(2, metricInputs.size)
            assertEquals(server.metrics.map { it.uuid }.toSet(), metricInputs.map { CountCommandRecords.uuid(it, "metric_uuid") }.toSet())
            assertEquals(2, metricInputs.map { CountCommandRecords.uuid(it, "reserved_fact_uuid") }.toSet().size)
            assertTrue(graph.syncOutboxDao.getAll().isEmpty())
            server.metrics.forEachIndexed { index, metric ->
                val current = requireNotNull(graph.metricDao.getMetricByUuid(metric.uuid))
                assertEquals(41.125 + index, requireNotNull(graph.metricLogDao.getLatestLog(current.id)).value, 0.0)
            }
            epochs.sessions.exclusive { graph.database.withTransaction {
                val access = requireNotNull(epochs.tokens.localSyncAccess())
                val requests = NextCoreRequestStore(graph.database, epochs.tokens, epochs.sessions, http)
                val originalIds = graph.database.openHelper.writableDatabase.query(
                    "SELECT requestId FROM main.next_request_origins WHERE kind=? ORDER BY requestId", arrayOf(NEXT_OPERATION)).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                assertEquals(3, originalIds.size)
                for (id in originalIds) requests.recoveryAcceptedFactProof(access, id)
                val current = requireNotNull(graph.habitDao.getHabitByUuid(habit.uuid))
                val history = RecurringCompletionEvidenceReader(graph.database, epochs.tokens, epochs.sessions).readInTransaction(current, server.today)
                assertEquals(1L, history.completions.filter { it.businessDate == server.today }.sumOf { it.value.toLong() })
                assertEquals(initialChallengeRoundHead(habit.uuid), history.roundHead)
                val root = CountCommandStorageInitialization.verifyRootAnchor(graph.database.openHelper.writableDatabase)
                assertEquals(0L, root.header.pending.count)
                val nativeImage = NextReplicaStorageProof(graph.database).readInTransaction()
                val tables = nativeImage.contents.getValue("tables").jsonObject
                val nativeFacts = tables.getValue("completions").jsonArray.map { it.jsonObject }
                assertEquals(9, nativeFacts.size)
                assertEquals(bootstrapNativeFacts, nativeFacts.filter {
                    nativeValue(it, "uuid", Cursor.FIELD_TYPE_STRING).content in originalFactIds
                })
                val metricRows = tables.getValue("metric_logs").jsonArray.map { it.jsonObject }
                assertEquals(2, metricRows.size) // Whole table: no extra same-value observations.
                assertEquals(metricInputs.map { CountCommandRecords.uuid(it, "reserved_fact_uuid") }.toSet(),
                    metricRows.map { nativeValue(it, "uuid", Cursor.FIELD_TYPE_STRING).content }.toSet())
                server.metrics.forEachIndexed { index, metric ->
                    val input = metricInputs.single { CountCommandRecords.uuid(it, "metric_uuid") == metric.uuid }
                    val row = metricRows.single { nativeValue(it, "uuid", Cursor.FIELD_TYPE_STRING).content == CountCommandRecords.uuid(input, "reserved_fact_uuid") }
                    val expected = 41.125 + index
                    assertEquals(expected.toRawBits().toULong().toString(16).padStart(16, '0'), CountCommandRecords.text(input, "value_bits"))
                    assertEquals("", CountCommandRecords.text(input, "note"))
                    assertEquals(CountCommandRecords.integer(input, "metric_id"), nativeValue(row, "metricId", Cursor.FIELD_TYPE_INTEGER).long)
                    assertEquals(expected.toRawBits().toString(16), nativeValue(row, "value", Cursor.FIELD_TYPE_FLOAT).content)
                    assertEquals(metric.unit, nativeValue(row, "unit", Cursor.FIELD_TYPE_STRING).content)
                    assertEquals("", nativeValue(row, "note", Cursor.FIELD_TYPE_STRING).content)
                    assertEquals(CountCommandRecords.integer(childInput, "recorded_at"), nativeValue(row, "date", Cursor.FIELD_TYPE_INTEGER).long)
                    assertEquals(server.zone.id, nativeValue(row, "recordedTimezone", Cursor.FIELD_TYPE_STRING).content)
                }
                assertEquals(2, tables.getValue("count_command_intents").jsonArray.size)
                assertEquals(2, tables.getValue("count_command_results").jsonArray.size)
                assertEquals(3, tables.getValue("next_acceptances").jsonArray.size)
                assertEquals(9, tables.getValue("next_challenge_births").jsonArray.size)
            } }
        }
        assertTrue("Native observer failures: ${native.observationFailures}", native.observationFailures.isEmpty())
        assertEquals(1, native.ends.count { it.kind == CountJourneyNativeTrace.Kind.COUNT_T1 })
        assertEquals(1, native.ends.count { it.kind == CountJourneyNativeTrace.Kind.METRIC_T1 })
        assertEquals(1, native.ends.count { it.kind == CountJourneyNativeTrace.Kind.T2 })
        assertEquals(2, native.ends.count { it.kind == CountJourneyNativeTrace.Kind.T0 })
        assertEquals(3, native.ends.sumOf { it.acceptances })
        server.assertTerminal(); assertEquals(server.today, LocalDate.now(server.zone))
        val kinds = server.acceptedKinds()
        val ackEnds = native.ends.filter { it.kind == CountJourneyNativeTrace.Kind.ACK }
        assertEquals(kinds.keys, ackEnds.flatMap { it.acceptedRequests }.toSet())
        val countAckEnd = ackEnds.single { end -> end.acceptedRequests.any { kinds[it] == "activity_event" } }
        val metricAckEnds = ackEnds.filter { end -> end.acceptedRequests.any { kinds[it] == "metric_observation" } }
        assertEquals(2, metricAckEnds.size)
        assertEquals(1, native.ends.map { it.nativeIdentity }.toSet().size)
        val foregroundPostCommit = maxOf(modelAt, promptModelAt, cardModelAt)
        val overlap = countAckEnd.beginAt < foregroundPostCommit && countAckEnd.endAt > nativeCount.endAt
        val metricOverlap = metricAckEnds.any { it.beginAt < deliveryEnd.endAt && it.endAt > childEnd.endAt }
        // Preserve the early candidates. This later real screenshot follows all existing
        // business/ACK/native oracles, without sleeping, forcing a frame or changing animation.
        // Only independent inspection of this PNG can qualify pixels at this later time;
        // it can never retroactively certify next_action or the pre-Record candidate.
        stage("terminal_review_candidate")
        val terminalReview = observed.seen("terminal_review", record.started) { snapshot ->
            snapshot.button(label, habit.name)?.enabled == true &&
                snapshot.text(app.getString(R.string.timer_countup_progress, 1L, 1))
        }
        fun ms(end: Long, start: Long) = (end - start) / 1_000_000.0
        val seen = listOf("ready" to ready, "count_feedback" to busy, "metric_prompt" to promptSeen,
            "typed_metrics" to typed, "metric_busy" to metricBusy, "next_action" to visibleResult,
            "terminal_review" to terminalReview)
        val result = buildJsonObject {
            put("kind", "actual_formal_visible_journey"); put("screen", if (nested != null) "nested" else "dashboard")
            put("samples", 1); put("release_measurement", false); put("p95_claim", false); put("history", 8)
            put("setup_ms", ms(opened, setupStarted)); put("first_screen_candidate_capture_ms", ms(ready.observedAt, opened))
            put("count_feedback_candidate_capture_ms", ms(busy.observedAt, click.started)); put("native_count_end_ms", ms(nativeCount.endAt, click.started))
            put("accepted_count_model_observed_ms", ms(modelAt, click.started)); put("shared_card_model_observed_ms", ms(cardModelAt, click.started))
            put("required_prompt_model_observed_ms", ms(promptModelAt, click.started))
            put("required_prompt_candidate_capture_ms", ms(promptSeen.observedAt, click.started))
            put("metric_ime_dismissal", dismissedIme)
            put("typing_ms", ms(record.started, typingStarted)); put("metric_feedback_candidate_capture_ms", ms(metricBusy.observedAt, record.started))
            put("native_metric_end_ms", ms(childEnd.endAt, record.started)); put("native_delivery_end_ms", ms(deliveryEnd.endAt, record.started))
            put("card_next_action_candidate_capture_ms", ms(visibleResult.observedAt, record.started))
            put("busy_second_touch_attempted", ignoredBusy != null)
            put("busy_second_touch_classification", secondClassification)
            put("busy_second_touch_proven_before_t1", secondBeforeT1 && secondWithinGate)
            put("busy_second_touch_ms", ignoredBusy?.let { JsonPrimitive(ms(it.started, click.started)) } ?: JsonNull)
            put("busy_observed_samples", JsonArray(gateSamples.map { (at, busyValue) -> buildJsonObject {
                put("observed_ns", at); put("busy", busyValue)
            } }))
            put("second_touch_actual_dispatch", JsonArray(secondDispatch.map { event -> buildJsonObject {
                put("action", event.action); put("entered_ns", event.enteredAt); put("returned_ns", event.returnedAt)
                put("busy_before", event.busyBefore); put("busy_after", event.busyAfter)
            } }))
            put("second_command_created", false)
            put("count_ack_interval_ms", ms(countAck.end, countAck.start)); put("metric_ack_interval_ms", ms(metricAck.end, metricAck.start))
            put("count_sync_start_ns", countAck.start); put("count_sync_end_ns", countAck.end)
            put("background_sync", syncAttemptEvidence())
            put("metric_sync_start_ns", metricAck.start); put("metric_sync_end_ns", metricAck.end)
            if (CompletionActionTiming.enabled) put("ack_request_links", JsonArray(ackEnds.map { end -> buildJsonObject {
                put("sequence", end.sequence); put("native", end.nativeIdentity)
                put("begin_ns", end.beginAt); put("acquired_ns", end.acquiredAt); put("end_ns", end.endAt)
                put("request_keys", JsonArray(end.acceptedRequests.mapNotNull { request ->
                    CompletionActionTiming.ackRequestKey(request)?.let(::JsonPrimitive)
                }))
            } }))
            put("actual_native_count_ack_postcommit_overlap", overlap); put("actual_native_metric_ack_delivery_overlap", metricOverlap)
            put("native_count_ack_begin_ms", ms(countAckEnd.beginAt, click.started))
            put("native_count_ack_end_ms", ms(countAckEnd.endAt, click.started))
            put("native_count_ack_sqlite_wait_ms", ms(countAckEnd.acquiredAt, countAckEnd.beginAt))
            put("frame_evidence", JsonArray(seen.map { (name, sample) -> buildJsonObject {
                put("name", name); put("kind", sample.evidence); put("snapshot_ns", sample.snapshotAt)
                put("ime_visible", sample.snapshot.imeVisible?.let(::JsonPrimitive) ?: JsonNull)
                put("ime_bottom", sample.snapshot.imeBottom?.let(::JsonPrimitive) ?: JsonNull)
                put("draw_ns", sample.drawAt?.let(::JsonPrimitive) ?: JsonNull)
                put("hardware_commit_ns", sample.committedAt?.let(::JsonPrimitive) ?: JsonNull)
                put("app_buffer_commit_matches_snapshot", sample.appBufferCommitMatchesSnapshot)
                put("screenshot_start_ns", sample.captureStarted); put("screenshot_end_ns", sample.observedAt)
                put("screenshot_ms", ms(sample.observedAt, sample.captureStarted)); put("image", sample.image)
            } }))
            put("evidence_schema", 2)
            put("app_buffer_commit_matches_snapshot", false)
            put("all_frame_commits_observed", seen.all { it.second.committedAt != null })
            put("screenshot_time_is_upper_bound", false)
            put("pixel_qualification", "UNVERIFIED_COMPOSITE_CANDIDATES_REQUIRE_PNG_REVIEW")
            put("unoccluded_next_action_verified", false)
            put("terminal_review_capture_ms_from_record", ms(terminalReview.observedAt, record.started))
            put("record_input_after_typed_capture_ms", ms(record.started, typed.observedAt))
            put("terminal_review_after_original_oracles", true)
            put("card_occluded_during_dialog", true); put("full_terminal_oracle", true)
            put("native_account_or_network_intervals_summed", false)
        }
        check(evidence.isDirectory || evidence.mkdirs())
        File(evidence, "result.json").writeText(result.toString())
        Log.i("CountVisibleJourney", result.toString())
        stage("budget_assessment")
        val violations = buildList {
            if (ms(busy.observedAt, click.started) > 100) add("COUNT feedback100ms")
            if (ms(metricBusy.observedAt, record.started) > 100) add("metric feedback100ms")
            if (ms(nativeCount.endAt, click.started) > 500) add("COUNT commit500ms")
            if (ms(childEnd.endAt, record.started) > 500) add("metric commit500ms")
            if (ms(promptSeen.observedAt, click.started) > 700) add("required prompt700ms")
            if (ms(visibleResult.observedAt, record.started) > 700) add("metric result/shared visible700ms")
            if (ms(ready.observedAt, opened) > 2000) add("first screen2s")
            if (ms(promptSeen.observedAt, click.started) > 2000) add("COUNT result max2s")
            if (ms(visibleResult.observedAt, record.started) > 2000) add("metric result max2s")
        }
        assertTrue("No actual COUNT ACK/postcommit interval overlap; do not label this sample competing", overlap)
        assertTrue("No actual metric ACK/delivery interval overlap; do not label this sample competing", metricOverlap)
        assertTrue("All terminal facts passed; candidate-capture/native budget misses (pixel qualification remains separate): $violations", violations.isEmpty())
    }
    /** Already published memory only: never issue a new read to make a failed selection true. */
    private suspend fun failureModel(): JsonObject = withContext(Dispatchers.Main.immediate) {
        fun row(value: HabitEntity, quantity: Long, identity: Boolean, source: DatabaseEpochPublication?,
            allowed: Boolean, failed: Boolean) = buildJsonObject {
            put("id", value.id); put("uuid", value.uuid); put("name", value.name)
            put("selected_uuid", this@CountFormalVisibleJourneyTest::habit.isInitialized && value.uuid == habit.uuid)
            put("actual_today_count", quantity); put("has_count_identity", identity)
            put("has_source", source != null); put("source_current", source?.isCurrentInMemory()?.let(::JsonPrimitive) ?: JsonNull)
            put("active", value.isActive); put("checkin_allowed", allowed); put("failed", failed)
        }
        fun input(value: CountJourneyWindows.Input?) = value?.let { buildJsonObject {
            put("down_time_uptime_ms", it.downTime); put("started_ns", it.started)
            put("down_returned_ns", it.downReturned); put("up_returned_ns", it.upReturned)
        } } ?: JsonNull
        buildJsonObject {
            put("count_input", input(countInputEvidence)); put("record_input", input(recordInputEvidence))
            put("record_bounds_screen", recordBoundsEvidence ?: JsonNull); put("ime_dismissal", imeEvidence ?: JsonNull)
            put("observed_model_times_ns", buildJsonObject { modelTimes.toSortedMap().forEach { (name, at) -> put(name, at) } })
            put("background_sync", syncAttemptEvidence())
            put("native_observation", buildJsonObject {
                put("count_end_observed", native.countCommitted.isCompleted)
                put("metric_end_observed", native.metricsCommitted.isCompleted)
                put("delivery_end_observed", native.delivered.isCompleted)
                put("observation_failures", JsonArray(native.observationFailures.map(::JsonPrimitive)))
                put("ends", JsonArray(native.ends.toList().map { end -> buildJsonObject {
                    put("kind", end.kind.name); put("sequence", end.sequence); put("native", end.nativeIdentity)
                    put("begin_ns", end.beginAt); put("acquired_ns", end.acquiredAt); put("end_ns", end.endAt)
                    put("facts", end.facts); put("metrics", end.metrics); put("outcomes", end.outcomes)
                    put("status_updates", end.statusUpdates); put("acceptances", end.acceptances)
                    if (CompletionActionTiming.enabled && end.kind == CountJourneyNativeTrace.Kind.ACK) {
                        val keys = end.acceptedRequests.mapNotNull(CompletionActionTiming::ackRequestKey)
                        put("request_keys", JsonArray(keys.map(::JsonPrimitive)))
                        put("request_key_input_count", end.acceptedRequests.size)
                        put("request_key_output_count", keys.size)
                        put("request_key_mapping_complete", keys.size == end.acceptedRequests.size)
                    }
                } }))
            })
            put("expected_count_button_label", app.getString(R.string.action_check_in))
            put("expected_habit_name", if (this@CountFormalVisibleJourneyTest::habit.isInitialized) JsonPrimitive(habit.name) else JsonNull)
            put("dashboard_initialized", dashboard?.isInitialized?.value?.let(::JsonPrimitive) ?: JsonNull)
            put("dashboard_read_error", dashboard?.readError?.value?.let(::JsonPrimitive) ?: JsonNull)
            put("nested_initialized", nested?.isInitialized?.value?.let(::JsonPrimitive) ?: JsonNull)
            put("nested_read_error", nested?.readError?.value?.let(::JsonPrimitive) ?: JsonNull)
            put("dashboard_rows", dashboard?.let { vm -> JsonArray(vm.habitsWithStats.value.map { value ->
                row(value.habit, value.actualTodayCount, value.countActionIdentity != null, value.sourceEpoch, value.isCheckInAllowed, value.hasFailed)
            }) } ?: JsonNull)
            put("nested_children", nested?.let { vm -> JsonArray(vm.topLevelHabitsWithChildren.value.flatMap { it.children }.map { value ->
                row(value.habit, value.actualTodayCount, value.countActionIdentity != null, value.sourceEpoch, value.isCheckInAllowed, value.hasFailed)
            }) } ?: JsonNull)
        }
    }
    private suspend fun diagnostic(child: Boolean) {
        diagnosticStartedAt = now()
        journeyExited = false
        try { journey(child); endEvidence(null) }
        catch (error: Throwable) {
            try { observer?.failureEvidence(stage, failureModel()) }
            catch (diagnosticFailure: Throwable) { error.addSuppressed(diagnosticFailure) }
            try { endEvidence(error) } catch (evidenceFailure: Throwable) { error.addSuppressed(evidenceFailure) }
            Log.e("CountVisibleJourney", "Original journey failed at fixed stage=$stage nested=$child", error)
            throw error
        } finally { journeyExited = true }
    }
    @Test(timeout = 150_000) fun dashboardFormalCountMetricJourneyWithRealAckOverlap() = runBlocking<Unit> { diagnostic(false) }
    @Test(timeout = 150_000) fun nestedFormalCountMetricJourneyWithRealAckOverlap() = runBlocking<Unit> { diagnostic(true) }
}
