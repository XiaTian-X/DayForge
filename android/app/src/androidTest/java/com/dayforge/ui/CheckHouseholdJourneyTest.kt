package com.dayforge.ui

import android.database.Cursor
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
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
import com.dayforge.data.model.FailMode
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
class CheckHouseholdJourneyTest {
    // Must surround every Hilt/DB/widget rule. Unknown previous worker exit is a hard failure,
    // not a skip, a cleanup success or permission to enter the next test's account/Host.
    @get:Rule(order = 0) val fence = object : ExternalResource() {
        override fun before() { poison.get()?.let { throw AssertionError("Prior AutoSync exit unproven; isolate process before another case", it) } }
    }
    private val native = CheckHouseholdNativeTrace()
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
    @Inject lateinit var themes: DeviceThemeController
    @Inject internal lateinit var http: NextSyncHttp
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private var dashboard: DashboardViewModel? = null
    private var activity: ActivityScenario<ComponentActivity>? = null
    private var peer: CheckHouseholdServer? = null
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
    private val releaseOnDown = AtomicReference<Rect?>()
    private var originalWindowCallback: Pair<Window, Window.Callback>? = null
    private var aFact: String? = null
    private var bFact: String? = null
    private var promptSource: WidgetFactClaim? = null
    private var cancelPreferences: Pair<Boolean, Set<String>>? = null
    private var recordMetrics = false
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
        return File(supplied, "check-household/${if (gated) "dismiss_and_ack" else "record_and_auto"}-${UUID.randomUUID()}")
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
        if (producersJoined && work?.safe != false) {
            // Join the actual eager preferences consumer only after UI/sync callers end.
            if (!clean { if (::themes.isInitialized) withTimeout(5_000) { themes.close() } })
                producersJoined = false
        }
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
        hilt.inject()
        // These legitimate initial preferences writes precede the Host's complete metadata
        // snapshot. CONTROL readiness alone never grants ordinary bootstrap permission.
        withTimeout(45_000) { themes.current() }
        assertEquals(0, epochs.realRoomCreates)
        recordMetrics = !gated
        val server = CheckHouseholdServer(withMetrics = recordMetrics).also { peer = it }
        preferences.setServerUrl(server.server.origin.toString()); preferences.setRemoteServerUrl(null)
        assertEquals(0, epochs.realRoomCreates)
        epochs.connect(); epochs.login()
        val prepared = epochs.host.sessionPreparation.value
        if (prepared !is SessionPreparationState.Satisfied)
            throw DatabaseRuntimeHost.SessionPreparationUnavailable(prepared)
        assertEquals(SessionPreparationDisposition.CURRENT_GENERATION, prepared.disposition)
        assertTrue(epochs.tokens.isCurrentPersonalDisplayStamp(prepared.attempt.stamp))
        sync.sync().getOrThrow() // Sole foreground bootstrap, before Worker/UI observations.
        epochs.current { graph ->
            originalPublication = epochs.host.currentPublication()
            a = requireNotNull(graph.habitDao.getHabitByUuid(server.checks[0].uuid))
            b = requireNotNull(graph.habitDao.getHabitByUuid(server.checks[1].uuid))
            assertEquals(1, graph.database.nextSyncStateDao().rows().single().challengeContract)
            assertTrue(graph.syncOutboxDao.getAll().isEmpty())
            val session = requireNotNull(epochs.tokens.localCoreWriteAccess()).session
            preferences.setNeverAskAgain(session, a.uuid, false)
            preferences.setNeverAskAgain(session, b.uuid, false)
            originalFacts = graph.database.withTransaction {
                NextReplicaStorageProof(graph.database).readInTransaction().contents.getValue("tables").jsonObject
                    .getValue("completions").jsonArray.map { it.jsonObject }
            }
            assertEquals(4, originalFacts.size)
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
                .also { models.put("check-household-real-dashboard", it) }
        }
        activity = ActivityScenario.launch(ComponentActivity::class.java).also { scenario -> scenario.onActivity { screen ->
            observed.observeInput(screen.window) { dashboard?.busyHabitIds?.value?.isNotEmpty() == true }
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
            listOf(a, b).all { habit -> rows.any { it.habit.uuid == habit.uuid && it.actualTodayCount == 0L && it.checkActionIdentity != null && it.sourceEpoch?.isCurrentInMemory() == true } }
        } }
        at("ready")
        observed.seen("ready", requireNotNull(times["ready"])) { it.button(label(R.string.action_check_in), a.name)?.let { node -> node.enabled && node.fullyVisible } == true }
        withTimeout(45_000) { pending.first { it == 0 } }
        native.arm()
        if (!gated) { at("automatic_start"); requireNotNull(scheduler).start() }
    }
    private fun label(id: Int) = app.getString(id)
    private suspend fun add(habit: HabitEntity, name: String, releaseHeld: Boolean): String {
        val observed = requireNotNull(observer); val vm = requireNotNull(dashboard)
        val ready = observed.seen("${name}_ready", now()) { it.button(label(R.string.action_check_in), habit.name)?.let { node -> node.enabled && node.fullyVisible } == true }
        val original = requireNotNull(ready.snapshot.button(label(R.string.action_check_in), habit.name))
        val previous = native.ends.filter { it.kind == CheckHouseholdNativeTrace.Kind.CHECK }.map { it.sequence }.toSet()
        if (releaseHeld) {
            val snapshot = requireNotNull(peer).snapshot()
            check(snapshot.heldRequestId == heldRequest && snapshot.releasedNanos == null && !snapshot.gateTimedOut)
            val running = requireNotNull(work).sample().filter { it.state == WorkInfo.State.RUNNING }
            assertEquals(1, running.size); assertEquals(CountBacklogWork.IMMEDIATE, running.single().name)
            heldWorkId = running.single().id.toString()
            check(releaseOnDown.compareAndSet(null, original.bounds))
        }
        val input = observed.tap(original); times["${name}_input_ns"] = input.started
        withTimeout(45_000) { while (native.ends.none { it.kind == CheckHouseholdNativeTrace.Kind.CHECK && it.sequence !in previous }) delay(25) }
        val end = native.ends.single { it.kind == CheckHouseholdNativeTrace.Kind.CHECK && it.sequence !in previous }
        val uuid = end.factUuids.single(); assertEquals(1, end.originRequests.size)
        times["${name}_native_end_ns"] = end.endAt
        epochs.current { graph ->
            val fact = requireNotNull(graph.completionDao.getCompletionByUuid(uuid))
            assertEquals(habit.id, fact.habitId); assertEquals(habit.uuid, fact.habitUuid); assertEquals(1, fact.value)
        }
        withTimeout(45_000) { vm.habitsWithStats.first { rows -> rows.any {
            it.habit.uuid == habit.uuid && it.completedToday && it.actualTodayCount == 1L && it.sourceEpoch?.isCurrentInMemory() == true
        } } }
        times["${name}_current_row_ns"] = now()
        return uuid
    }
    private suspend fun goalAndPrompt(): com.dayforge.ui.metrics.FactMetricPrompt {
        val vm = requireNotNull(dashboard); val observed = requireNotNull(observer)
        val goal = withTimeout(45_000) { vm.goalDialogFrame.first { it?.value?.show == true && it.value.habitUuid == a.uuid } }!!
        assertTrue(goal.publication.isCurrentInMemory()); assertEquals(3, goal.value.progress); assertEquals(3, goal.value.target)
        val visible = observed.seen("goal_ready", requireNotNull(times["a_input_ns"])) {
            it.button(label(R.string.action_continue_checkin))?.let { node -> node.enabled && node.fullyVisible } == true
        }
        times["goal_candidate_ns"] = visible.observedAt
        val input = observed.tap(requireNotNull(visible.snapshot.button(label(R.string.action_continue_checkin))))
        times["goal_continue_input_ns"] = input.started
        withTimeout(45_000) { while (native.ends.none { it.kind == CheckHouseholdNativeTrace.Kind.PLAN }) delay(25) }
        val end = native.ends.single { it.kind == CheckHouseholdNativeTrace.Kind.PLAN }
        assertEquals(1, end.originRequests.size); times["goal_plan_native_end_ns"] = end.endAt
        val frame = withTimeout(45_000) { vm.postCheckInFrame.first { it?.value?.factPrompt?.source?.completionUuid == aFact } }!!
        val prompt = requireNotNull(frame.value?.factPrompt)
        assertTrue(frame.publication.isCurrentInMemory()); assertEquals(2, requireNotNull(frame.value).linkedMetrics.size)
        assertEquals(a.uuid, prompt.source.habitUuid); assertEquals(aFact, prompt.source.completionUuid)
        assertEquals(requireNotNull(peer).today.toString(), prompt.source.date); assertEquals(requireNotNull(peer).zone.id, prompt.source.timezone)
        assertEquals(prompt.source, requireNotNull(prompt.completion).claim)
        assertEquals(FailMode.LOOSE, prompt.habit.failMode); assertTrue(prompt.habit.isActive)
        assertNull(prompt.command); assertNull(prompt.submittedCommand); assertNull(prompt.submissionPhase)
        promptSource = prompt.source
        val seen = observed.seen("required_prompt", input.started) { snapshot ->
            snapshot.text(label(R.string.post_checkin_title)) && requireNotNull(peer).metrics.all { snapshot.text("${it.name} (${it.unit})") }
        }
        times["prompt_candidate_ns"] = seen.observedAt
        return prompt
    }
    private suspend fun finishMetrics(prompt: com.dayforge.ui.metrics.FactMetricPrompt, save: Boolean) {
        val observed = requireNotNull(observer); val vm = requireNotNull(dashboard)
        requireNotNull(peer).metrics.forEachIndexed { index, metric -> observed.replaceText("${metric.name} (${metric.unit})", (41.125 + index).toString()) }
        observed.dismissImeIfVisible { value -> evidence?.let { File(it, "ime.json").writeText(value.toString()) } }
        if (save) {
            val ready = observed.seen("metric_ready", now()) { it.button(label(R.string.post_checkin_record))?.let { node -> node.enabled && node.fullyVisible } == true }
            val input = observed.tap(requireNotNull(ready.snapshot.button(label(R.string.post_checkin_record))))
            times["metric_input_ns"] = input.started
            withTimeout(45_000) { vm.postCheckInFrame.first { it?.value == null } }
            val end = native.ends.single { it.kind == CheckHouseholdNativeTrace.Kind.METRICS }
            assertEquals(2, end.metricUuids.size); assertEquals(2, end.originRequests.size)
            times["metric_native_end_ns"] = end.endAt
            assertEquals(2, requireNotNull(prompt.submitted).size)
            assertNull(prompt.command); assertNull(prompt.submittedCommand); assertNull(prompt.savedSubmissionForDisplay())
        } else {
            val session = epochs.current { requireNotNull(epochs.tokens.localCoreWriteAccess()).session }
            cancelPreferences = preferences.getNeverAskAgain(session, a.uuid).first() to
                preferences.pendingMetricHabitUuids(session, setOf(a.uuid)).first()
            val uptime = SystemClock.uptimeMillis(); times["prompt_dismiss_input_ns"] = now()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            check(automation.injectInputEvent(KeyEvent(uptime, uptime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true))
            check(automation.injectInputEvent(KeyEvent(uptime, uptime + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true))
            withTimeout(45_000) { vm.postCheckInFrame.first { it?.value == null } }
            assertNull(prompt.submitted); assertNull(prompt.submittedCommand); assertNull(prompt.command)
            assertTrue(native.ends.none { it.kind == CheckHouseholdNativeTrace.Kind.METRICS })
            assertEquals(cancelPreferences, preferences.getNeverAskAgain(session, a.uuid).first() to
                preferences.pendingMetricHabitUuids(session, setOf(a.uuid)).first())
            epochs.current { graph -> assertEquals(0, graph.metricLogDao.countAll()) }
        }
        val seen = observed.seen("next_operation_ready", now()) { snapshot ->
            !snapshot.text(label(R.string.post_checkin_title)) && snapshot.button(label(R.string.action_check_in), b.name)?.let { node -> node.enabled && node.fullyVisible } == true
        }
        times["next_operation_candidate_ns"] = seen.observedAt
    }

    private fun expectedMetricText(index: Int): String {
        val metric = requireNotNull(peer).metrics[index]
        return if (recordMetrics) "${String.format(java.util.Locale.getDefault(), "%.${metric.decimalPlaces}f", 41.125 + index)} ${metric.unit}"
            else "-- ${metric.unit}"
    }
    private suspend fun expandActualMetricSection() {
        val observed = requireNotNull(observer)
        val ready = observed.seen("linked_metrics_ready", now()) {
            it.button(label(R.string.linked_metrics_title), a.name)?.let { node -> node.enabled && node.fullyVisible } == true
        }
        val input = observed.tap(requireNotNull(ready.snapshot.button(label(R.string.linked_metrics_title), a.name)))
        times["expand_metrics_input_ns"] = input.started
        val shown = observed.seen("linked_metrics_current", input.started) { snapshot ->
            requireNotNull(peer).metrics.withIndex().all { (index, metric) -> snapshot.text(metric.name) && snapshot.text(expectedMetricText(index)) }
        }
        times["linked_metrics_candidate_ns"] = shown.observedAt
    }
    private suspend fun waitForDrain() = withTimeout(45_000) {
        while (true) {
            observationFailure.get()?.let { throw AssertionError("Bounded observer failed", it) }
            if (pending.value == 0 && native.ends.sumOf { it.acceptedRequests.size } == (if (recordMetrics) 5 else 3)) break
            delay(100)
        }
    }
    private suspend fun foregroundQuiescent() {
        withTimeout(45_000) { requireNotNull(dashboard).busyHabitIds.first { a.id !in it && b.id !in it } }
        withContext(Dispatchers.Main.immediate) {
            check(requireNotNull(originalPublication).isCurrentInMemory())
            check(requireNotNull(dashboard).postCheckInFrame.value?.value == null)
            check(requireNotNull(dashboard).goalDialogFrame.value?.value?.show != true)
            check(releaseOnDown.get() == null)
        }
        at("foreground_work_quiescent")
    }
    private suspend fun runScene(gated: Boolean) {
        var original: Throwable? = null
        try {
            prepare(gated)
            aFact = add(a, "a", releaseHeld = false)
            finishMetrics(goalAndPrompt(), save = !gated)
            if (gated) {
                requireNotNull(peer).armFirstCheckResponseGate()
                at("manual_worker_enqueue")
                requireNotNull(scheduler).enqueueNow() // The only manual wake, not sync/doWork/ACK.
                heldRequest = withTimeout(45_000) { requireNotNull(peer).held.await() }
                at("response_held")
            }
            bFact = add(b, "b", releaseHeld = gated)
            times["business_input_stopped_ns"] = now()
            withTimeout(45_000) { requireNotNull(dashboard).postCheckInFrame.first { it?.value == null } }
            waitForDrain(); times["outbox_zero_after_all_acks_ns"] = now()
            foregroundQuiescent()
            // Preserve original automatic debounce/retries until actual queues are empty.
            stopScheduling()
            requireNotNull(work).drain(); requireNotNull(work).requireHealthy(expectPeriodic = !gated)
            times["work_drained_ns"] = now()
            terminal()
            at("terminal_metric_expansion")
            expandActualMetricSection()
            val seen = requireNotNull(observer).seen("terminal_current_page", requireNotNull(times["b_input_ns"])) {
                !it.text(label(R.string.post_checkin_title)) &&
                    it.button(label(R.string.habit_card_status_completed), a.name)?.let { node -> node.enabled && node.fullyVisible } == true &&
                    it.button(label(R.string.habit_card_status_completed), b.name)?.let { node -> node.enabled && node.fullyVisible } == true &&
                    it.text(app.getString(R.string.habit_target_progress_format, 3, 3)) &&
                    requireNotNull(peer).metrics.withIndex().all { (index, metric) -> it.text(metric.name) && it.text(expectedMetricText(index)) }
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
        val server = requireNotNull(peer); server.assertTerminal()
        val ends = native.ends.toList()
        assertTrue(native.observationFailures.isEmpty()); assertEquals(1, ends.map { it.nativeIdentity }.toSet().size)
        val checkEnds = ends.filter { it.kind == CheckHouseholdNativeTrace.Kind.CHECK }
        assertEquals(2, checkEnds.size); assertEquals(setOf(aFact, bFact), checkEnds.flatMap { it.factUuids }.toSet())
        assertEquals(1, ends.count { it.kind == CheckHouseholdNativeTrace.Kind.PLAN })
        assertEquals(if (recordMetrics) 1 else 0, ends.count { it.kind == CheckHouseholdNativeTrace.Kind.METRICS })
        val requested = ends.flatMap { it.originRequests }; val accepted = ends.flatMap { it.acceptedRequests }
        assertEquals(if (recordMetrics) 5 else 3, requested.size); assertEquals(requested.size, requested.toSet().size)
        assertEquals(requested.size, accepted.size); assertEquals(requested.toSet(), accepted.toSet())
        assertEquals(server.acceptedKinds().keys, requested.toSet())
        assertTrue(graph.syncOutboxDao.getAll().isEmpty()); assertTrue(graph.database.nextSyncStateDao().rejections().isEmpty())
        val originalIds = originalFacts.map { cell(it, "uuid", Cursor.FIELD_TYPE_STRING).content }.toSet()
        epochs.sessions.exclusive { graph.database.withTransaction {
            val access = requireNotNull(epochs.tokens.localSyncAccess())
            val requests = NextCoreRequestStore(graph.database, epochs.tokens, epochs.sessions, http)
            val structural = NextStructuralCausalStore(graph.database)
            val operations = requested.associateWith { id ->
                if (server.acceptedKinds().getValue(id) == "plan_node") structural.acceptedOperationProof(id, access).first
                else requests.recoveryAcceptedFactProof(access, id).first
            }
            val plan = operations.values.single { it.entityType == "plan_node" }
            assertEquals(server.selected.uuid, plan.entityUuid)
            assertEquals(NextStructureMapper.writePlan(server.selected.copy(failMode = FailMode.LOOSE)), plan.payload)
            val acceptedPlan = structural.acceptedPlanTransition(plan.operationId, access).second
            assertEquals(2L, acceptedPlan.getValue("revision").jsonPrimitive.long)
            val image = NextReplicaStorageProof(graph.database).readInTransaction().contents
            val tables = image.getValue("tables").jsonObject
            val facts = tables.getValue("completions").jsonArray.map { it.jsonObject }
            assertEquals(6, facts.size); assertEquals(originalFacts, facts.filter { cell(it, "uuid", Cursor.FIELD_TYPE_STRING).content in originalIds })
            val newFacts = facts.filter { cell(it, "uuid", Cursor.FIELD_TYPE_STRING).content !in originalIds }
            assertEquals(setOf(aFact, bFact), newFacts.map { cell(it, "uuid", Cursor.FIELD_TYPE_STRING).content }.toSet())
            for ((index, uuid) in listOf(requireNotNull(aFact), requireNotNull(bFact)).withIndex()) {
                val expected = server.checks[index]
                val row = newFacts.single { cell(it, "uuid", Cursor.FIELD_TYPE_STRING).content == uuid }
                assertEquals(expected.uuid, cell(row, "habitUuid", Cursor.FIELD_TYPE_STRING).content)
                assertEquals(1L, cell(row, "value", Cursor.FIELD_TYPE_INTEGER).long)
                assertEquals(server.today.toString(), cell(row, "recordedLocalDate", Cursor.FIELD_TYPE_STRING).content)
                assertEquals(server.zone.id, cell(row, "recordedTimezone", Cursor.FIELD_TYPE_STRING).content)
                val current = requireNotNull(graph.habitDao.getHabitByUuid(expected.uuid))
                val full = RecurringCompletionEvidenceReader(graph.database, epochs.tokens, epochs.sessions).readInTransaction(current, server.today)
                assertEquals(3, full.completions.size); assertEquals(initialChallengeRoundHead(current.uuid), full.roundHead)
                assertEquals(1L, full.completions.filter { it.recordedLocalDate == server.today.toString() }.sumOf { it.value.toLong() })
                if (index == 0) { assertEquals(FailMode.LOOSE, current.failMode); assertTrue(current.isActive) }
                val op = operations.values.single { it.entityUuid == uuid }
                assertEquals("check_in", op.payload.getValue("event_type").jsonPrimitive.content)
                assertFalse(op.payload.containsKey("count_policy")); assertFalse(op.payload.containsKey("value"))
            }
            val logs = tables.getValue("metric_logs").jsonArray.map { it.jsonObject }
            assertEquals(if (recordMetrics) 2 else 0, logs.size)
            if (recordMetrics) for ((index, metric) in server.metrics.withIndex()) {
                val storedMetric = requireNotNull(graph.metricDao.getMetricByUuid(metric.uuid))
                val row = logs.single { cell(it, "metricId", Cursor.FIELD_TYPE_INTEGER).long == storedMetric.id }
                val uuid = cell(row, "uuid", Cursor.FIELD_TYPE_STRING).content
                assertTrue(uuid in ends.single { it.kind == CheckHouseholdNativeTrace.Kind.METRICS }.metricUuids)
                val op = operations.values.single { it.entityUuid == uuid }; assertEquals("metric_observation", op.entityType)
                assertEquals((41.125 + index).toRawBits().toString(16), cell(row, "value", Cursor.FIELD_TYPE_FLOAT).content)
                assertEquals(metric.unit, cell(row, "unit", Cursor.FIELD_TYPE_STRING).content); assertEquals("", cell(row, "note", Cursor.FIELD_TYPE_STRING).content)
                assertEquals(server.zone.id, cell(row, "recordedTimezone", Cursor.FIELD_TYPE_STRING).content)
                assertEquals(server.today.toString(), op.payload.getValue("local_date").jsonPrimitive.content)
                assertEquals(cell(row, "date", Cursor.FIELD_TYPE_INTEGER).long,
                    java.time.Instant.parse(op.payload.getValue("occurred_at").jsonPrimitive.content).toEpochMilli())
                assertEquals(metric.unit, op.payload.getValue("unit").jsonPrimitive.content)
                assertEquals("", op.payload.getValue("note").jsonPrimitive.content)
            }
            if (recordMetrics) assertEquals(1, logs.map { cell(it, "date", Cursor.FIELD_TYPE_INTEGER).long }.toSet().size)
            for (name in listOf("timer_command_outbox", "sync_outbox", "sync_conflicts", "count_command_intents", "count_command_results", "count_command_status"))
                assertTrue("CHECK must not borrow COUNT receipt or leave work: $name", tables.getValue(name).jsonArray.isEmpty())
            assertEquals(requested.size, tables.getValue("next_acceptances").jsonArray.size)
            assertEquals(0L, CountCommandStorageInitialization.verifyRootAnchor(graph.database.openHelper.writableDatabase).header.pending.count)
        } }
        assertEquals(aFact, requireNotNull(promptSource).completionUuid)
        val session = requireNotNull(epochs.tokens.localCoreWriteAccess()).session
        assertFalse(preferences.getNeverAskAgain(session, a.uuid).first())
        if (!recordMetrics) assertEquals(cancelPreferences, preferences.getNeverAskAgain(session, a.uuid).first() to
            preferences.pendingMetricHabitUuids(session, setOf(a.uuid)).first())
        else assertTrue(preferences.pendingMetricHabitUuids(session, setOf(a.uuid)).first().isEmpty())
        val vm = requireNotNull(dashboard)
        withTimeout(45_000) { vm.todayProgress.first { it == (2 to 2) } }; times["home_progress_model_ns"] = now()
        val rows = withTimeout(45_000) { vm.habitsWithStats.first { values -> server.checks.all { expected -> values.any {
            it.habit.uuid == expected.uuid && it.completedToday && it.actualTodayCount == 1L && it.sourceEpoch?.isCurrentInMemory() == true
        } } } }
        assertEquals(3, rows.single { it.habit.uuid == a.uuid }.targetProgress)
        if (recordMetrics) withTimeout(45_000) { vm.metricsWithLatest.first { values -> server.metrics.all { expected -> values.any {
            it.metric.uuid == expected.uuid && it.latestValue == 41.125 + server.metrics.indexOf(expected) && it.sourceEpoch?.isCurrentInMemory() == true
        } } } }
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
        // Uses the original B-input->actual native CHECK business END, not a delayed screenshot endpoint.
        entryOverlap || transactionOverlap
    }
    private fun writeEvidence(error: Throwable?) {
        val directory = evidence ?: return
        work?.writeEvidence()
        val server = peer?.snapshot()
        File(directory, "journey.json").writeText(buildJsonObject {
            put("format", 1); put("class", "com.dayforge.ui.CheckHouseholdJourneyTest"); put("pid", Process.myPid())
            put("stage", stage); put("error_class", error?.javaClass?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("body_error", bodyFailure?.javaClass?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("cleanup_error", cleanupFailure?.javaClass?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("producers_joined", producersJoined); put("poisoned", poison.get() != null)
            put("times", buildJsonObject { times.toSortedMap().forEach { (key, value) -> put(key, value) } })
            put("two_real_check_inputs", listOf(aFact, bFact).count { it != null })
            put("record_metrics", recordMetrics)
            put("facts", JsonArray(listOfNotNull(aFact, bFact).map(::JsonPrimitive)))
            put("typed_CHECK_receipt_claim", false)
            put("metric_source", promptSource?.completionUuid?.let(::JsonPrimitive) ?: JsonNull)
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
                put("fact_uuids", JsonArray(end.factUuids.map(::JsonPrimitive))); put("metric_uuids", JsonArray(end.metricUuids.map(::JsonPrimitive)))
                put("origin_requests", JsonArray(end.originRequests.map(::JsonPrimitive))); put("ack_requests", JsonArray(end.acceptedRequests.map(::JsonPrimitive)))
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
    @Test(timeout = 150_000) fun dismissedRequiredMetricsKeepSavedChecksAndRealAckMeetsNextInput() = runBlocking<Unit> { runScene(true) }
    @Test(timeout = 150_000) fun goalContinuationAndRequiredMetricsReachCurrentPageWithNaturalAutoSync() = runBlocking<Unit> { runScene(false) }
    companion object { private val poison = AtomicReference<Throwable?>() }
}
