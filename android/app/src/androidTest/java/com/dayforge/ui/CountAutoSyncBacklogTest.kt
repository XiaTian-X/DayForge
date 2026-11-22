package com.dayforge.ui

import android.database.Cursor
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.dayforge.R
import com.dayforge.data.api.NetworkMonitor
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.repository.*
import com.dayforge.domain.model.initialChallengeRoundHead
import com.dayforge.domain.service.*
import com.dayforge.sync.AutoSyncCoordinator
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.screens.dashboard.DashboardScreen
import com.dayforge.ui.screens.dashboard.DashboardTimeWindowTicker
import com.dayforge.ui.screens.dashboard.DashboardViewModel
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Two fixed real-WorkManager/real-UI functional journeys. Debug+coverage observations are
 * not the old release UI2/P95 acceptance, and loopback is not NAS/launcher acceptance. */
@HiltAndroidTest
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class CountAutoSyncBacklogTest {
    // Must surround every Hilt/DB/widget rule. Unknown previous worker exit is a hard failure,
    // not a skip, a cleanup success or permission to enter the next test's account/Host.
    @get:Rule(order = 0) val fence = object : ExternalResource() {
        override fun before() { poison.get()?.let { throw AssertionError("Prior AutoSync exit unproven; isolate process before another case", it) } }
    }
    private val native = CountBacklogNativeTrace()
    @get:Rule(order = 1) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 2) val epochs = PhysicalDatabaseEpochFixture().also { fixture ->
        fixture.nativeFactory = native
        fixture.beforeCleanup = {
            if (!producersJoined || work?.safe == false || poison.get() != null) {
                val failure = AssertionError("Cannot release tokens/Host while AutoSync exit is unproven", poison.get())
                poison.compareAndSet(null, failure)
                throw failure
            }
        }
    }
    @get:Rule(order = 3) val hilt = HiltAndroidRule(this)
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var completion: HabitCompletionCoordinator
    @Inject lateinit var deletion: HabitDeletionCoordinator
    @Inject lateinit var windows: DashboardTimeWindowTicker
    @Inject lateinit var lifecycle: HabitLifecycleCoordinator
    @Inject lateinit var timers: HabitTimerCoordinator
    @Inject lateinit var metrics: LinkedMetricCoordinator
    @Inject lateinit var counts: CountActionGate
    @Inject lateinit var sync: SyncManager
    @Inject lateinit var network: NetworkMonitor
    @Inject lateinit var icons: AccountIconController
    @Inject internal lateinit var http: NextSyncHttp
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private var dashboard: DashboardViewModel? = null
    private var activity: ActivityScenario<ComponentActivity>? = null
    private var peer: CountBacklogServer? = null
    private var observer: CountJourneyWindows? = null
    private var work: CountBacklogWork? = null
    private var scheduleScope: CoroutineScope? = null
    private var scheduler: AutoSyncCoordinator? = null
    private val observationFailure = AtomicReference<Throwable?>()
    private val pending = MutableStateFlow(-1)
    private val pendingSamples = CopyOnWriteArrayList<Pair<Long, Int>>()
    private val times = ConcurrentHashMap<String, Long>()
    private var evidence: File? = null
    @Volatile private var producersJoined = false
    private var bodyFailure: Throwable? = null
    private var cleanupFailure: Throwable? = null
    private var stage = "not_started"
    private var heldRequest: String? = null
    private var heldWorkId: String? = null
    private var ackOverlap: Boolean? = null
    private var recordTouch: JsonObject? = null
    private val releaseOnDown = AtomicReference<Rect?>()
    private var originalWindowCallback: Pair<Window, Window.Callback>? = null
    private var aRef: CountCommandStore.Reference? = null
    private var bRef: CountCommandStore.Reference? = null
    private var childRef: CountCommandStore.Reference? = null
    private lateinit var a: HabitEntity
    private lateinit var b: HabitEntity
    private var originalFacts = emptyList<JsonObject>()
    private var originalPublication: DatabaseEpochPublication? = null
    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun at(label: String) { stage = label; times[label] = now() }

    @Suppress("DEPRECATION")
    private fun output(gated: Boolean): File {
        val supplied = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")))
        val owned = app.externalMediaDirs.filterNotNull().map { File(it, "additional_test_output").canonicalFile }
        check(app.packageName == "com.dayforge.testbed" && supplied.isAbsolute && supplied.canonicalFile in owned)
        return File(supplied, "count-auto-sync/${if (gated) "gated" else "natural"}-${UUID.randomUUID()}")
            .also { check(it.mkdirs()); evidence = it }
    }
    private suspend fun stopScheduling() {
        scheduleScope?.coroutineContext?.get(Job)?.let { job -> withTimeout(5_000) { job.cancelAndJoin() } }
    }
    private suspend fun closeUi() {
        originalWindowCallback?.let { (window, original) -> withContext(Dispatchers.Main.immediate) { window.callback = original } }
        originalWindowCallback = null
        observer?.close(); observer = null
        var job: Job? = null
        activity?.onActivity { job = requireNotNull(it.lifecycleScope.coroutineContext[Job]) }
        activity?.close(); activity = null
        job?.let { withTimeout(5_000) { it.join() } }
        dashboard?.let { vm ->
            val modelJob = withContext(Dispatchers.Main.immediate) {
                requireNotNull(vm.viewModelScope.coroutineContext[Job]).also { models.clear() }
            }
            withTimeout(5_000) { modelJob.join() }; dashboard = null
        }
    }
    @After fun finish() = runBlocking<Unit> {
        var error: Throwable? = null
        suspend fun clean(block: suspend () -> Unit): Boolean = try { block(); true } catch (failure: Throwable) {
            if (error == null) error = failure else error!!.addSuppressed(failure)
            false
        }
        peer?.releaseHeldResponse()
        val uiClosed = clean { closeUi() }
        val schedulingStopped = clean { stopScheduling() }
        producersJoined = uiClosed && schedulingStopped
        if (producersJoined) clean { work?.let { if (!it.safe) it.drain() } }
        if (!producersJoined || work?.safe == false)
            poison.compareAndSet(null, error ?: AssertionError("AutoSync/producer exit unknown"))
        cleanupFailure = error
        clean { work?.writeEvidence() }
        clean { writeEvidence(bodyFailure ?: error) }
        // The private server is not closed out from under an unproven worker. The original
        // failed runner must stop the isolated process; the next method's first rule fails.
        if (poison.get() == null) clean { peer?.close(); peer = null }
        error?.let { throw it }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    private suspend fun prepare(gated: Boolean) {
        at("prepare")
        val directory = output(gated)
        hilt.inject(); epochs.connect(); epochs.login()
        val server = CountBacklogServer().also { peer = it }
        preferences.setServerUrl(server.server.origin.toString()); preferences.setRemoteServerUrl(null)
        sync.sync().getOrThrow() // Sole foreground bootstrap, before Worker/UI observations.
        epochs.current { graph ->
            originalPublication = epochs.host.currentPublication()
            a = requireNotNull(graph.habitDao.getHabitByUuid(server.counts[0].uuid))
            b = requireNotNull(graph.habitDao.getHabitByUuid(server.counts[1].uuid))
            assertEquals(1, graph.database.nextSyncStateDao().rows().single().challengeContract)
            assertTrue(graph.syncOutboxDao.getAll().isEmpty())
            val session = requireNotNull(epochs.tokens.localCoreWriteAccess()).session
            preferences.setNeverAskAgain(session, a.uuid, false)
            preferences.setNeverAskAgain(session, b.uuid, false)
            originalFacts = graph.database.withTransaction {
                NextReplicaStorageProof(graph.database).readInTransaction().contents.getValue("tables").jsonObject
                    .getValue("completions").jsonArray.map { it.jsonObject }
            }
            assertEquals(8, originalFacts.size)
            val handler = CoroutineExceptionHandler { _, error -> observationFailure.compareAndSet(null, error) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler).also { scheduleScope = it }
            // Existing production start/debounce/coalescer, owned real-DAO constructor.
            // This fixed-Host test does not claim the Injected Host.observe replacement bridge.
            scheduler = AutoSyncCoordinator(app, graph.syncOutboxDao, graph.timeLogDao, network, scope, icons.businessRequests)
            scope.launch { graph.syncOutboxDao.observePendingCount().collect { value ->
                check(pendingSamples.size < 128)
                pendingSamples += now() to value; pending.value = value
            } }
        }
        work = CountBacklogWork(app, directory)
        requireNotNull(work).begin() // Keep safe=false visible even if prior-work admission throws.
        requireNotNull(scheduleScope).launch {
            while (isActive) { requireNotNull(work).sample(); delay(50) }
        }
        val observed = CountJourneyWindows(directory).also { observer = it }
        withContext(Dispatchers.Main.immediate) {
            dashboard = DashboardViewModel(app, epochs.host, completion, deletion, windows, lifecycle, timers, preferences, metrics, counts)
                .also { models.put("auto-sync-real-dashboard", it) }
        }
        activity = ActivityScenario.launch(ComponentActivity::class.java).also { scenario -> scenario.onActivity { screen ->
            observed.observeInput(screen.window) { counts.busyHabitIds.value.isNotEmpty() }
            val original = requireNotNull(screen.window.callback)
            originalWindowCallback = screen.window to original
            screen.window.callback = object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    val bound = releaseOnDown.get()
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && bound != null) {
                        if (bound.contains(Offset(event.rawX, event.rawY)) && releaseOnDown.compareAndSet(bound, null)) {
                            times["gate_actual_down_ns"] = now(); server.releaseHeldResponse()
                        } else observationFailure.compareAndSet(null, AssertionError("Gate release input was not the original B control"))
                    }
                    return original.dispatchTouchEvent(event)
                }
            }
            screen.setContent { MaterialTheme {
                DashboardScreen(requireNotNull(dashboard), WindowSizeClass.calculateFromSize(DpSize(400.dp, 800.dp)), {}, {}, {}, {})
            } }
        } }
        observed.attach()
        withTimeout(45_000) { requireNotNull(dashboard).habitsWithStats.first { rows ->
            listOf(a, b).all { habit -> rows.any { it.habit.uuid == habit.uuid && it.actualTodayCount == 0L && it.countActionIdentity != null && it.sourceEpoch?.isCurrentInMemory() == true } }
        } }
        at("ready")
        observed.seen("ready", requireNotNull(times["ready"])) { it.button(label(R.string.action_check_in), a.name)?.let { node -> node.enabled && node.fullyVisible } == true }
        withTimeout(45_000) { pending.first { it == 0 } }
        native.arm()
        if (!gated) { at("automatic_start"); requireNotNull(scheduler).start() }
    }
    private fun label(id: Int) = app.getString(id)
    private suspend fun reference(id: String): CountCommandStore.Reference = epochs.current { graph -> graph.database.withTransaction {
        graph.database.openHelper.writableDatabase.query("SELECT intentJson FROM count_command_intents WHERE commandId=? LIMIT 2", arrayOf(id)).use { cursor ->
            check(cursor.moveToFirst()); val bytes = cursor.getString(0).toByteArray(Charsets.UTF_8); check(!cursor.moveToNext())
            val intent = CountCommandRecords.Intent.decode(bytes, bytes.size) { }
            CountCommandStore.Reference(intent.commandId, intent.inputHash, intent.namespace.hash, intent.namespace.dataset)
        }
    } }
    private suspend fun add(habit: HabitEntity, name: String, releaseHeld: Boolean): CountCommandStore.Reference = coroutineScope {
        val observed = requireNotNull(observer); val vm = requireNotNull(dashboard)
        val ready = observed.seen("${name}_ready", now()) { it.button(label(R.string.action_check_in), habit.name)?.let { node -> node.enabled && node.fullyVisible } == true }
        val original = requireNotNull(ready.snapshot.button(label(R.string.action_check_in), habit.name))
        val accepted = async(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            val frame = withTimeout(45_000) { vm.countButtonFrame.first {
                it?.value?.get(habit.uuid)?.kind == CountButtonModel.Kind.SAVED_ADD
            } }!!
            check(frame.publication.isCurrentInMemory()); now() to requireNotNull(frame.value.getValue(habit.uuid).commandId)
        }
        try {
            if (releaseHeld) {
                val snapshot = requireNotNull(peer).snapshot()
                check(snapshot.heldRequestId == heldRequest && snapshot.releasedNanos == null && !snapshot.gateTimedOut)
                val running = requireNotNull(work).sample().filter { it.state == WorkInfo.State.RUNNING }
                assertEquals(1, running.size); assertEquals(CountBacklogWork.IMMEDIATE, running.single().name)
                heldWorkId = running.single().id.toString()
                check(releaseOnDown.compareAndSet(null, original.bounds))
            }
            val input = observed.tap(original); times["${name}_input_ns"] = input.started
            val (acceptedAt, id) = accepted.await(); times["${name}_saved_model_ns"] = acceptedAt
            val ref = reference(id)
            withTimeout(45_000) {
                while (native.ends.none { it.kind == CountBacklogNativeTrace.Kind.COUNT_T1 && id in it.outcomeIds }) delay(25)
            }
            times["${name}_native_end_ns"] = native.ends.single { it.kind == CountBacklogNativeTrace.Kind.COUNT_T1 && id in it.outcomeIds }.endAt
            withTimeout(45_000) { vm.habitsWithStats.first { rows -> rows.any {
                it.habit.uuid == habit.uuid && it.actualTodayCount == 1L && it.sourceEpoch?.isCurrentInMemory() == true
            } } }
            times["${name}_current_row_ns"] = now()
            ref
        } finally { withTimeout(5_000) { accepted.cancelAndJoin() } }
    }
    private suspend fun recordRequiredMetrics(parent: CountCommandStore.Reference) {
        val vm = requireNotNull(dashboard); val observed = requireNotNull(observer); val server = requireNotNull(peer)
        val frame = withTimeout(45_000) { vm.postCheckInFrame.first { it?.value?.factPrompt?.command?.commandId == parent.commandId } }!!
        val prompt = requireNotNull(frame.value?.factPrompt)
        assertTrue(frame.publication.isCurrentInMemory()); assertEquals(2, requireNotNull(frame.value).linkedMetrics.size)
        val seen = observed.seen("required_prompt", requireNotNull(times["a_input_ns"])) { snapshot ->
            snapshot.text(label(R.string.post_checkin_title)) && server.metrics.all { snapshot.text("${it.name} (${it.unit})") }
        }
        times["prompt_candidate_ns"] = seen.observedAt
        server.metrics.forEachIndexed { index, metric -> observed.replaceText("${metric.name} (${metric.unit})", (41.125 + index).toString()) }
        observed.dismissImeIfVisible { value -> evidence?.let { File(it, "ime.json").writeText(value.toString()) } }
        val title = label(R.string.post_checkin_title)
        val action = label(R.string.post_checkin_record)
        fun recordButton(snapshot: CountJourneyWindows.Snapshot): CountJourneyWindows.Node? {
            val currentFrame = vm.postCheckInFrame.value
            check(currentFrame?.publication === frame.publication && frame.publication.isCurrentInMemory() &&
                currentFrame?.value?.factPrompt === prompt) { "Original required metric prompt expired" }
            if (snapshot.window != seen.snapshot.window || !snapshot.focused ||
                snapshot.imeVisible != false || snapshot.imeBottom != 0 || !snapshot.text(title) ||
                snapshot.nodes.mapNotNull { it.editable }.sorted() != listOf("41.125", "42.125")) return null
            return snapshot.button(action)?.takeIf { it.click && it.enabled && it.fullyVisible }
        }
        val input = withTimeout(45_000) {
            observed.seen("metric_ready", now()) { recordButton(it) != null }
            observed.tapCurrentDialog(seen.snapshot.window, ::recordButton) { recordTouch = it }
        }
        times["metric_input_ns"] = input.started
        withTimeout(45_000) { vm.postCheckInFrame.first { it?.value == null } }
        val child = requireNotNull(prompt.submittedCommand)
        childRef = reference(child.commandId)
        assertEquals(parent.commandId, requireNotNull(prompt.command).commandId)
        assertEquals(2, requireNotNull(prompt.savedSubmissionForDisplay()).values.size)
        times["metric_native_end_ns"] = native.ends.single { it.kind == CountBacklogNativeTrace.Kind.METRIC_T1 && child.commandId in it.outcomeIds }.endAt
        observed.seen("next_operation_ready", input.started) { snapshot ->
            !snapshot.text(label(R.string.post_checkin_title)) && snapshot.button(label(R.string.action_check_in), b.name)?.let { node -> node.enabled && node.fullyVisible } == true }
        times["next_operation_candidate_ns"] = now()
    }

    private suspend fun waitForDrain() = withTimeout(45_000) {
        while (true) {
            observationFailure.get()?.let { throw AssertionError("Bounded observer failed", it) }
            if (pending.value == 0 && native.ends.sumOf { it.acceptedRequests.size } == 4) break
            delay(100)
        }
    }
    private suspend fun foregroundQuiescent() {
        withTimeout(45_000) { counts.busyHabitIds.first { a.id !in it && b.id !in it } }
        val parent = requireNotNull(aRef); val child = requireNotNull(childRef); val other = requireNotNull(bRef)
        withTimeout(45_000) {
            while (native.ends.none { it.kind == CountBacklogNativeTrace.Kind.T2 && parent.commandId in it.statusIds && child.commandId in it.statusIds } ||
                native.ends.none { it.kind == CountBacklogNativeTrace.Kind.T2 && other.commandId in it.statusIds }) delay(25)
        }
        withContext(Dispatchers.Main.immediate) {
            check(requireNotNull(originalPublication).isCurrentInMemory()) // Empty busy cannot be due to account invalidation.
            check(requireNotNull(dashboard).postCheckInFrame.value?.value == null)
            check(releaseOnDown.get() == null)
        }
        at("foreground_commands_quiescent")
        // UI read observers remain alive for final presentation; no further input is injected.
        // This is not a claim that the Activity/ViewModel lifetime has already joined.
    }
    private suspend fun runScene(gated: Boolean) {
        var original: Throwable? = null
        try {
            prepare(gated)
            aRef = add(a, "a", releaseHeld = false)
            recordRequiredMetrics(requireNotNull(aRef))
            if (gated) {
                requireNotNull(peer).armFirstMetricResponseGate()
                at("manual_worker_enqueue")
                requireNotNull(scheduler).enqueueNow() // The only manual wake, not sync/doWork/ACK.
                heldRequest = withTimeout(45_000) { requireNotNull(peer).held.await() }
                at("response_held")
            }
            bRef = add(b, "b", releaseHeld = gated)
            times["input_stopped_ns"] = now()
            withTimeout(45_000) { requireNotNull(dashboard).postCheckInFrame.first { it?.value == null } }
            waitForDrain(); times["outbox_zero_after_all_acks_ns"] = now()
            foregroundQuiescent()
            // Preserve original automatic debounce/retries until actual queues are empty.
            stopScheduling()
            requireNotNull(work).drain(); requireNotNull(work).requireHealthy(expectPeriodic = !gated)
            times["work_drained_ns"] = now()
            terminal()
            val seen = requireNotNull(observer).seen("terminal_current_page", requireNotNull(times["b_input_ns"])) {
                !it.text(label(R.string.post_checkin_title)) && it.button(label(R.string.action_check_in), b.name)?.let { node -> node.enabled && node.fullyVisible } == true &&
                    it.text(app.getString(R.string.timer_countup_progress, 1L, 1)) && it.text(app.getString(R.string.timer_countup_progress, 1L, 7))
            }
            times["terminal_page_candidate_ns"] = seen.observedAt
            if (gated) {
                ackOverlap = localOverlap(requireNotNull(heldRequest), requireNotNull(times["b_input_ns"]), requireNotNull(times["b_native_end_ns"]))
                check(ackOverlap == true) { "Local ACK missed the conservative B-input-to-commit window (not proof of no later contention); no extra trial is allowed" }
            }
            observationFailure.get()?.let { throw AssertionError("Bounded observer failed", it) }
        } catch (error: Throwable) { original = error; bodyFailure = error; throw error }
        finally {
            try { writeEvidence(original) } catch (diagnostic: Throwable) {
                if (original == null) throw diagnostic else original.addSuppressed(diagnostic)
            }
        }
    }

    private fun cell(row: JsonObject, name: String, type: Int): JsonPrimitive {
        val value = row.getValue(name).jsonObject
        assertEquals(type, value.getValue("sqlite_type").jsonPrimitive.int)
        return value.getValue("value").jsonPrimitive
    }
    private suspend fun terminal() = epochs.current { graph ->
        at("terminal_oracle")
        val server = requireNotNull(peer)
        server.assertTerminal()
        val a = requireNotNull(aRef); val b = requireNotNull(bRef); val child = requireNotNull(childRef)
        val store = CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
        val aa = store.resolve(a).historical; val bb = store.resolve(b).historical; val cc = store.resolve(child).historical
        for (row in listOf(aa, bb, cc)) {
            assertEquals(CountCommandRecords.Execution.APPLIED, row.status.execution); assertFalse(row.status.pending)
        }
        assertEquals(CountCommandRecords.Delivery.DELIVERED, aa.status.delivery)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, cc.status.delivery)
        assertEquals(child.commandId, aa.status.child); assertEquals(a.commandId, cc.status.parent)
        assertNull(bb.status.child); assertTrue(store.discover(8).commands.isEmpty())
        val expectedCommands = setOf(a.commandId, b.commandId, child.commandId)
        val ends = native.ends.toList()
        val countEnds = ends.filter { it.kind == CountBacklogNativeTrace.Kind.COUNT_T1 }
        assertEquals(2, countEnds.size)
        assertEquals(setOf(a.commandId, b.commandId), countEnds.map { it.outcomeIds.single() }.toSet())
        val metricEnd = ends.single { it.kind == CountBacklogNativeTrace.Kind.METRIC_T1 }
        assertEquals(listOf(child.commandId), metricEnd.outcomeIds); assertEquals(2, metricEnd.metrics)
        val t0Ids = ends.filter { it.kind == CountBacklogNativeTrace.Kind.T0 }.flatMap { it.intentIds }
        assertEquals(3, t0Ids.size); assertEquals(expectedCommands, t0Ids.toSet())
        val deliveries = ends.filter { it.kind == CountBacklogNativeTrace.Kind.T2 }
        assertTrue(deliveries.any { a.commandId in it.statusIds && child.commandId in it.statusIds })
        assertTrue(deliveries.any { b.commandId in it.statusIds })
        assertTrue(deliveries.all { it.statusIds.all(expectedCommands::contains) })
        assertTrue(native.observationFailures.isEmpty())
        assertEquals(1, ends.map { it.nativeIdentity }.toSet().size) // Wrapper identity, not Linux TID or physical WAL connection.
        val originalIds = originalFacts.map { cell(it, "uuid", Cursor.FIELD_TYPE_STRING).content }.toSet()
        val countInputs = listOf(aa, bb).map { it.intent.body().getValue("input").jsonObject }
        val childInput = cc.intent.body().getValue("input").jsonObject
        assertEquals(a.commandId, CountCommandRecords.uuid(childInput, "parent_command_id"))
        assertEquals(CountCommandRecords.uuid(countInputs[0], "reserved_fact_uuid"), CountCommandRecords.uuid(childInput, "source_fact_uuid"))
        assertEquals(server.selected.uuid, CountCommandRecords.uuid(childInput, "habit_uuid"))
        val metricInputs = childInput.getValue("metrics").jsonArray.map { it.jsonObject }
        assertEquals(server.metrics.map { it.uuid }.toSet(), metricInputs.map { CountCommandRecords.uuid(it, "metric_uuid") }.toSet())
        assertEquals(2, requireNotNull(cc.outcome).effects.size)
        assertTrue(graph.syncOutboxDao.getAll().isEmpty()); assertTrue(graph.database.nextSyncStateDao().rejections().isEmpty())
        epochs.sessions.exclusive { graph.database.withTransaction {
            val access = requireNotNull(epochs.tokens.localSyncAccess())
            val requests = NextCoreRequestStore(graph.database, epochs.tokens, epochs.sessions, http)
            val ids = graph.database.openHelper.writableDatabase.query(
                "SELECT requestId FROM next_request_origins WHERE kind=? ORDER BY requestId", arrayOf(NEXT_OPERATION)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertEquals(4, ids.size); assertEquals(server.acceptedKinds().keys, ids.toSet())
            for (id in ids) requests.recoveryAcceptedFactProof(access, id)
            val accepted = ends.filter { it.kind == CountBacklogNativeTrace.Kind.ACK }.flatMap { it.acceptedRequests }
            assertEquals(4, accepted.size); assertEquals(ids.toSet(), accepted.toSet())
            val image = NextReplicaStorageProof(graph.database).readInTransaction().contents
            val tables = image.getValue("tables").jsonObject
            val facts = tables.getValue("completions").jsonArray.map { it.jsonObject }
            assertEquals(10, facts.size)
            assertEquals(originalFacts, facts.filter { cell(it, "uuid", Cursor.FIELD_TYPE_STRING).content in originalIds })
            val newFacts = facts.filter { cell(it, "uuid", Cursor.FIELD_TYPE_STRING).content !in originalIds }
            assertEquals(countInputs.map { CountCommandRecords.uuid(it, "reserved_fact_uuid") }.toSet(),
                newFacts.map { cell(it, "uuid", Cursor.FIELD_TYPE_STRING).content }.toSet())
            for ((index, input) in countInputs.withIndex()) {
                assertEquals(server.counts[index].uuid, CountCommandRecords.uuid(input, "habit_uuid"))
                assertEquals(1L, CountCommandRecords.integer(input, "quantity"))
                val row = newFacts.single { cell(it, "uuid", Cursor.FIELD_TYPE_STRING).content == CountCommandRecords.uuid(input, "reserved_fact_uuid") }
                assertEquals(server.counts[index].uuid, cell(row, "habitUuid", Cursor.FIELD_TYPE_STRING).content)
                assertEquals(1L, cell(row, "value", Cursor.FIELD_TYPE_INTEGER).long)
                assertEquals(server.today.toString(), cell(row, "recordedLocalDate", Cursor.FIELD_TYPE_STRING).content)
                assertEquals(server.zone.id, cell(row, "recordedTimezone", Cursor.FIELD_TYPE_STRING).content)
                val current = requireNotNull(graph.habitDao.getHabitByUuid(server.counts[index].uuid))
                val history = RecurringCompletionEvidenceReader(graph.database, epochs.tokens, epochs.sessions).readInTransaction(current, server.today)
                assertEquals(1L, history.completions.filter { it.recordedLocalDate == server.today.toString() }.sumOf { it.value.toLong() })
                assertEquals(initialChallengeRoundHead(current.uuid), history.roundHead)
            }
            val logs = tables.getValue("metric_logs").jsonArray.map { it.jsonObject }
            assertEquals(2, logs.size)
            for ((index, metric) in server.metrics.withIndex()) {
                val input = metricInputs.single { CountCommandRecords.uuid(it, "metric_uuid") == metric.uuid }
                val row = logs.single { cell(it, "uuid", Cursor.FIELD_TYPE_STRING).content == CountCommandRecords.uuid(input, "reserved_fact_uuid") }
                val value = 41.125 + index
                assertEquals(value.toRawBits().toULong().toString(16).padStart(16, '0'), CountCommandRecords.text(input, "value_bits"))
                assertEquals(value.toRawBits().toString(16), cell(row, "value", Cursor.FIELD_TYPE_FLOAT).content)
                assertEquals(metric.unit, cell(row, "unit", Cursor.FIELD_TYPE_STRING).content)
                assertEquals("", cell(row, "note", Cursor.FIELD_TYPE_STRING).content)
                assertEquals(CountCommandRecords.integer(childInput, "recorded_at"), cell(row, "date", Cursor.FIELD_TYPE_INTEGER).long)
                assertEquals(server.zone.id, cell(row, "recordedTimezone", Cursor.FIELD_TYPE_STRING).content)
            }
            for (name in listOf("timer_command_outbox", "sync_outbox", "sync_conflicts")) assertTrue(tables.getValue(name).jsonArray.isEmpty())
            assertEquals(3, tables.getValue("count_command_intents").jsonArray.size)
            assertEquals(3, tables.getValue("count_command_results").jsonArray.size)
            assertEquals(4, tables.getValue("next_acceptances").jsonArray.size)
            assertEquals(0L, CountCommandStorageInitialization.verifyRootAnchor(graph.database.openHelper.writableDatabase).header.pending.count)
        } }
        at("all_terminal_facts_passed")
    }
    private suspend fun localOverlap(request: String, inputAt: Long, committedAt: Long): Boolean = withContext(Dispatchers.IO) {
        val key = requireNotNull(CompletionActionTiming.ackRequestKey(request))
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "logcat -d -v raw -t 1000 --pid=${Process.myPid()} -s CompletionActionTiming:D")
        val text = ParcelFileDescriptor.AutoCloseInputStream(fd).use { stream ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) { val n = stream.read(buffer); if (n < 0) break; check(out.size() + n <= 524288); out.write(buffer, 0, n) }
            out.toString(Charsets.UTF_8.name())
        }
        check(text.lineSequence().count() < 1000) { "ACK log tail may be truncated" }
        File(requireNotNull(evidence), "ack-stage.log").writeText(text)
        val marks = text.lineSequence().filter { it.startsWith("kind=ACK_STAGE ") }.map { line ->
            line.split(' ').map { it.split('=', limit = 2) }.filter { it.size == 2 }.associate { it[0] to it[1] }
        }.filter { it["request_key"] == key }.toList()
        check(marks.any { it["stage"] == "PUSH_RESULT_AVAILABLE" }) { "Held request lacked actual HTTP result marker" }
        check(marks.any { it["stage"] == "ACCEPT_REQUESTED" }) { "Held request lacked actual ACK request marker" }
        check(marks.any { it["stage"] == "ACCOUNT_ACQUIRED" }) { "Held request lacked actual account acquisition marker" }
        val nativeAck = native.ends.filter { request in it.acceptedRequests }
        assertEquals(1, nativeAck.size)
        val entryOverlap = marks.any { mark -> mark["stage"] in setOf("ACCEPT_REQUESTED", "ACCOUNT_ACQUIRED") &&
            mark["elapsed_realtime_ns"]?.toLongOrNull()?.let { it in inputAt..committedAt } == true }
        val transactionOverlap = nativeAck.any { it.beginAt < committedAt && it.endAt > inputAt }
        // Uses the original B-input->actual native T1 END, not a delayed screenshot endpoint.
        entryOverlap || transactionOverlap
    }
    private fun writeEvidence(error: Throwable?) {
        val directory = evidence ?: return
        work?.writeEvidence()
        val server = peer?.snapshot()
        File(directory, "journey.json").writeText(buildJsonObject {
            put("format", 1); put("class", "com.dayforge.ui.CountAutoSyncBacklogTest"); put("pid", Process.myPid())
            put("record_touch", recordTouch ?: JsonNull)
            put("stage", stage); put("error_class", error?.javaClass?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("body_error", bodyFailure?.javaClass?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("cleanup_error", cleanupFailure?.javaClass?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("producers_joined", producersJoined); put("poisoned", poison.get() != null)
            put("times", buildJsonObject { times.toSortedMap().forEach { (key, value) -> put(key, value) } })
            put("two_real_count_inputs", listOf(aRef, bRef).count { it != null })
            put("commands", JsonArray(listOfNotNull(aRef, bRef, childRef).map { JsonPrimitive(it.commandId) }))
            put("held_request", heldRequest?.let(::JsonPrimitive) ?: JsonNull)
            put("held_running_work", heldWorkId?.let(::JsonPrimitive) ?: JsonNull)
            put("local_ack_entered_b_input_to_commit", ackOverlap?.let(::JsonPrimitive) ?: JsonNull)
            put("counter_peak_observed_lower_bound", pendingSamples.maxOfOrNull { it.second } ?: -1)
            put("pending_samples", JsonArray(pendingSamples.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) }))
            put("observation_failure", observationFailure.get()?.javaClass?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("native_failures", JsonArray(native.observationFailures.map(::JsonPrimitive)))
            put("native", JsonArray(native.ends.map { end -> buildJsonObject {
                put("kind", end.kind.name); put("sequence", end.sequence); put("native_identity", end.nativeIdentity)
                put("begin_ns", end.beginAt); put("acquired_ns", end.acquiredAt); put("end_ns", end.endAt)
                put("intent_ids", JsonArray(end.intentIds.map(::JsonPrimitive))); put("outcome_ids", JsonArray(end.outcomeIds.map(::JsonPrimitive)))
                put("status_ids", JsonArray(end.statusIds.map(::JsonPrimitive))); put("ack_requests", JsonArray(end.acceptedRequests.map(::JsonPrimitive)))
            } }))
            put("server", server?.let { value -> buildJsonObject {
                put("gate_armed", value.gateArmed); put("gate_timed_out", value.gateTimedOut)
                put("held_ns", value.heldNanos?.let(::JsonPrimitive) ?: JsonNull)
                put("release_requested_ns", value.releaseRequestedNanos?.let(::JsonPrimitive) ?: JsonNull)
                put("released_to_transport_ns", value.releasedNanos?.let(::JsonPrimitive) ?: JsonNull)
                put("requests", JsonArray(value.requests.map { request -> buildJsonObject {
                    put("sequence", request.sequence); put("path", request.path)
                    put("id", request.requestId?.let(::JsonPrimitive) ?: JsonNull)
                    put("entity", request.entityUuid?.let(::JsonPrimitive) ?: JsonNull)
                    put("received_ns", request.receivedNanos)
                    put("response_ready_ns", request.responseReadyNanos?.let(::JsonPrimitive) ?: JsonNull)
                    put("release_to_transport_ns", request.responseReleasedNanos?.let(::JsonPrimitive) ?: JsonNull)
                    put("replayed", request.replayed)
                } }))
            } } ?: JsonNull)
            put("screenshot_endpoints_are_candidates_not_first_visible", true)
            put("old_ui2_budget_or_overlap_pass_claim", false)
            put("junit_pass_claim", false)
        }.toString())
    }
    @Test(timeout = 150_000) fun workerResponseAtNextInputKeepsOriginalGroupsAndNaturallyDrains() = runBlocking<Unit> { runScene(true) }
    @Test(timeout = 150_000) fun automaticWakeupsDrainFiniteTwoHabitSequenceWithoutManualSync() = runBlocking<Unit> { runScene(false) }
    companion object { private val poison = AtomicReference<Throwable?>() }
}
