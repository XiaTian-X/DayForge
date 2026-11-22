package com.dayforge.data.repository

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.LoginRequest
import com.dayforge.data.api.dto.RefreshRequest
import com.dayforge.data.local.*
import androidx.lifecycle.lifecycleScope
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.*
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.TimerManager
import com.dayforge.domain.service.TimerService
import com.dayforge.widget.BusinessWidgetIsolationRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** Actual production Hilt, foreground service, Room and owned FastAPI/TCP peer.
 * The verification gate always provisions the peer when this full class is discovered.
 * Missing infrastructure is a failure, never a skipped/fake-green Android test.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RealBackendJointTest {
    @get:Rule(order = 0) val widgets: BusinessWidgetIsolationRule = BusinessWidgetIsolationRule { epochs.cleanupCompleted }
    private val native = RetainedJourneyNative()
    @get:Rule(order = 1) val epochs: PhysicalDatabaseEpochFixture = PhysicalDatabaseEpochFixture().also {
        it.nativeFactory = native
        it.beforeCleanup = {
            check(testOwnersClosed) { "REAL_BACKEND_TEST_OWNERS_NOT_JOINED" }
            widgets.owner.requireInstalled()
            runBlocking { closeColdHost() }
        }
    }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val testName = TestName()
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var timerManager: TimerManager
    @Inject lateinit var themes: com.dayforge.domain.service.DeviceThemeController
    @Inject internal lateinit var http: NextSyncHttp
    @Inject lateinit var auth: AuthApi
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    @Inject internal lateinit var factory: DatabaseEpochGraphFactory
    private lateinit var activeHost: DatabaseRuntimeHost
    private var coldHost: DatabaseRuntimeHost? = null
    private var coldDatabase: HabitDatabase? = null
    private var coldFailure: Throwable? = null
    private val methodOwnerMonitor = Any()
    private var methodClosing = false
    private var methodJob: Job? = null
    private var testOwnersClosed = false
    private fun <T> journey(block: suspend CoroutineScope.() -> T): T = runBlocking {
        val running = requireNotNull(currentCoroutineContext()[Job])
        synchronized(methodOwnerMonitor) {
            // JUnit timeout cleanup may run before this worker reaches registration.
            check(!methodClosing && methodJob == null) { "JOINT_METHOD_REGISTRATION_CLOSED" }
            methodJob = running
        }
        block()
    }
    private val graph get() = activeHost.boundGraph()
    private val db get() = graph.database
    private val habits get() = graph.habitRepository
    private val metrics get() = graph.metricRepository
    private val creator get() = graph.nextObjectCreator
    private val business get() = graph.businessSyncRepository
    private val timerWriter get() = graph.nextTimerWriter
    private suspend fun <T> current(block: suspend () -> T): T = activeHost.withGraph {
        check(CountTrustRuntime.forDatabase(it.database) != null)
        block()
    }
    private suspend fun closeColdHost() {
        coldFailure?.let { throw it }
        try {
            coldHost?.takeUnless { it.lifecycleEnded }?.let { shutdownTestHost(it) }
            check(coldHost == null || requireNotNull(coldHost).lifecycleEnded)
            check(coldDatabase?.isOpen != true)
        } catch (failure: Throwable) { coldFailure = failure; throw failure }
    }
    private suspend fun coldReopen() {
        check(coldHost == null)
        val old = current { db }
        shutdownTestHost(activeHost)
        assertTrue(activeHost.lifecycleEnded); assertFalse(old.isOpen)
        assertTrue(requireNotNull(CountTrustRuntime.forDatabase(old)).isGraphCloseConfirmed(old))
        activeHost = DatabaseRuntimeHost(factory, tokens).also { coldHost = it }
        current { coldDatabase = db; assertNotSame(old, db); assertTrue(db.isOpen) }
    }
    private lateinit var activity: ActivityScenario<ComponentActivity>
    private lateinit var account: String
    private fun appearance(role: String) = ObjectAppearance(IconReference.Role(role), "#123456", "theme")

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("dayforgeJointPort")).toInt()
        require(port in 1024..65535)
        hilt.inject(); epochs.connect(); activeHost = epochs.host
        sessions.exclusive { tokens.clearTokens() }
        epochs.clearAndAwaitReplacement()
        preferences.setRemoteServerUrl(null)
        preferences.setActiveServerUrl(null)
        preferences.setServerUrl("http://127.0.0.1:$port/")
        val name = when {
            testName.methodName == "realDurableCountCommandRequiredMetricAckReplayAndColdDelivery" -> "joint-count-command"
            testName.methodName.contains("Countdown") -> "joint-countdown"
            testName.methodName.contains("Forward") -> "joint-forward"
            testName.methodName.contains("Once") -> "joint-once"
            else -> "joint-core"
        }
        val login = auth.login(LoginRequest(name, "TestPassword123!")) // Synthetic seed-only fixture.
        account = login.userId
        val refreshed = auth.refreshToken(RefreshRequest(login.refreshToken))
        assertEquals(account, refreshed.userId)
        sessions.exclusive {
            tokens.saveLoginSession(refreshed.accessToken, refreshed.refreshToken, name, account, false)
        }
        activity = ActivityScenario.launch(ComponentActivity::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        sync()
        current { assertTrue(db.habitDao().getAllHabitsOnce().isEmpty()) }
        assertEquals(account, tokens.authenticationSnapshot()!!.session.userId)
        assertNotNull(requireNotNull(tokens.localSyncAccess()).deviceId)
        assertTrue("structure.write" in requireNotNull(tokens.localSyncAccess()).capabilities)
    }

    @After fun cleanup() = runBlocking<Unit> {
        val ownedMethod = synchronized(methodOwnerMonitor) {
            methodClosing = true
            methodJob
        }
        withTimeout(5_000) { ownedMethod?.cancelAndJoin() }
        check(ownedMethod?.isCompleted != false)
        if (::timerManager.isInitialized) {
            app.stopService(Intent(app, TimerService::class.java))
            awaitState {
                val descriptor = instrumentation.uiAutomation.executeShellCommand(
                    "dumpsys activity services ${app.packageName}/com.dayforge.domain.service.TimerService")
                val dump = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                !dump.contains("ServiceRecord{")
            }
        }
        if (::activity.isInitialized) {
            var activityJob: Job? = null
            activity.onActivity { activityJob = requireNotNull(it.lifecycleScope.coroutineContext[Job]) }
            activity.close()
            withTimeout(5_000) { activityJob?.join() }
            check(activityJob?.isCompleted != false)
        }
        if (::themes.isInitialized) withTimeout(5_000) { themes.close() }
        closeColdHost()
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
        if (::preferences.isInitialized) {
            preferences.setServerUrl(null)
            preferences.setRemoteServerUrl(null)
            preferences.setActiveServerUrl(null)
        }
        testOwnersClosed = true
    }

    private suspend fun awaitState(predicate: suspend () -> Boolean) = withTimeout(15_000) {
        while (!predicate()) delay(50)
    }

    private suspend fun sync() = current<Unit> {
        val progress = mutableListOf<SyncProgress>()
        var committed = 0
        business.sync(progress::add) { committed++ }
        // The repository commits and returns; SyncManager publishes UI Success.
        assertEquals(1, committed)
        assertTrue(progress.contains(SyncProgress.Downloading))
        assertFalse(progress.any { it is SyncProgress.Error })
        assertNotNull(preferences.lastSyncTimestamp.first())
    }

    private suspend fun habit(name: String, type: HabitType, down: Boolean = false, once: Boolean = false,
        parent: String? = null, metric: Long? = null): Long =
        habits.createHabit(name, "", type, 0, "#123456", if (once) HabitSchedule.Once() else HabitSchedule.Daily,
            targetValue = 1, isCountdown = down, parentHabitId = parent,
            targetCycles = if (once) null else 3, failMode = if (once) FailMode.LOOSE else FailMode.STRICT,
            completionPolicy = if (once) "one_and_done" else "recurring",
            appearance = appearance(if (once) "task.shopping" else "habit.exercise"),
            selectedMetricIds = metric?.let { setOf(it) } ?: emptySet(), creationAuthority = creator.capture())

    @Test fun realMixedCoreFactsKeepGoalMetricLinksAndBothCountModes() = journey<Unit> {
        val metricForCommand = current {
        val metric = metrics.createMetric(MetricEntity(name = "joint metric", unit = "kg", decimalPlaces = 3,
            iconResId = 0, colorHex = "#123456", appearance = appearance("metric.weight")), creationAuthority = creator.capture())
        val goalDraft = HabitDraft(name = "joint goal", habitType = HabitType.GOAL, appearance = appearance("goal.default"))
        habits.createGoal(goalDraft, emptyList(), creationAuthority = creator.capture())
        val check = habit("joint check", HabitType.CHECK_IN, parent = goalDraft.id, metric = metric)
        val up = habit("joint up", HabitType.COUNTING, parent = goalDraft.id)
        val down = habit("joint down", HabitType.COUNTING, true, parent = goalDraft.id)
        sync()
        assertEquals(4, db.habitDao().getAllHabitsOnce().size)
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        for (id in listOf(check, up, down)) habits.logCompletion(app, id)
        metrics.recordValue(metric, 12.125, "actual joint")
        sync()
        assertEquals(12.125, db.metricLogDao().getLogsByMetric(metric).first().single().value, 0.0)
        for (id in listOf(check, up, down)) assertEquals(1, db.completionDao().getByHabitOnce(id).size)
        val snapshot = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundBootstrap() })
        assertEquals(4, snapshot.changes.count { it.entityType == "plan_node" })
        assertEquals(3, snapshot.changes.count { it.entityType == "activity_event" })
        assertEquals(1, snapshot.changes.count { it.entityType == "activity_metric_link" })
        assertEquals(1, snapshot.changes.count { it.entityType == "metric_observation" })
        val before = db.completionDao().getByHabitOnce(check)
        sync()
        assertEquals(before, db.completionDao().getByHabitOnce(check))
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        metric
        }
        independentMetricCommand(metricForCommand)
    }

    @Test fun realOnceUndoAndOfflineChallengeRestartRetainOriginalFacts() = journey<Unit> {
        current {
        val check = habit("joint challenge", HabitType.CHECK_IN)
        val once = habit("joint once", HabitType.CHECK_IN, once = true)
        sync()
        habits.logCompletion(app, check)
        habits.logCompletion(app, once)
        sync()
        assertTrue(habits.getOneTimeStatus(once).completed)
        val request = habits.prepareReactivation(requireNotNull(habits.getHabitById(check)))
        assertTrue(request.preservesHistory)
        habits.reactivate(request, app)
        habits.reactivate(request, app) // Same confirmation must not create a second round.
        habits.logCompletion(app, check)
        sync()
        assertEquals(2, db.completionDao().getByHabitOnce(check).size)
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        habits.undoCompletion(app, requireNotNull(habits.getOneTimeStatus(once).completionId))
        sync()
        assertFalse(habits.getOneTimeStatus(once).completed)
        assertNotNull(habits.getHabitById(once))
        val snapshot = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundBootstrap() })
        assertEquals(1, snapshot.checkpoints.single { it.head.activityUuid == habits.getHabitById(check)!!.uuid }.head.generation)
        assertEquals(4, snapshot.changes.count { it.entityType == "activity_event" })
        val before = db.completionDao().getByHabitOnce(check)
        sync()
        assertEquals(before, db.completionDao().getByHabitOnce(check))
        assertFalse(habits.getOneTimeStatus(once).completed)
        }
    }

    @Test fun realDurableCountCommandRequiredMetricAckReplayAndColdDelivery() = journey<Unit> {
        countAndRequiredMetricCommand()
    }

    @Test fun realForwardTimerContinuesAcrossActualSync() = journey<Unit> { current { timed(false) } }
    @Test fun realCountdownTimerContinuesAcrossActualSync() = journey<Unit> { current { timed(true) } }

    private suspend fun timed(countdown: Boolean) {
        val timer = habit("joint timer", HabitType.TIMER, countdown)
        sync()

        // Actual stopwatch time and foreground service; no fabricated timestamps/results.
        timerManager.startTimer(timer, 1, timerWriter.capture(timer))
        awaitState { db.timeLogDao().getActiveTimeLogForHabit(timer) != null }
        sync() // Running sessions are synchronizable, not finished facts.
        val log = requireNotNull(db.timeLogDao().getActiveTimeLogForHabit(timer))
        val running = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundActive() })
        val runningSession = requireNotNull(running.session)
        assertEquals(log.uuid, runningSession.sessionId)
        assertEquals("running", runningSession.state)
        assertNull(runningSession.completedEventId)
        assertTrue(db.timeLogDao().getDayAllocations(log.uuid).isEmpty())
        timerManager.pauseTimer(timer, 1, timerWriter.capture(timer))
        awaitState { db.timeLogDao().getActiveTimeLogForHabit(timer)?.isPaused == true }
        sync()
        val paused = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundActive() })
        assertEquals("paused", requireNotNull(paused.session).state)
        delay(1000)
        timerManager.resumeTimer(timer, 1, timerWriter.capture(timer))
        awaitState { db.timeLogDao().getActiveTimeLogForHabit(timer)?.isPaused == false }
        sync()
        if (countdown) {
            withTimeout(75_000) {
                while (db.timeLogDao().getActiveTimeLogForHabit(timer) != null) delay(100)
            }
        } else {
            withTimeout(75_000) {
                while (timerManager.calculateElapsedSeconds(requireNotNull(db.timeLogDao().getActiveTimeLogForHabit(timer))) < 61) delay(100)
            }
            assertEquals(timer, requireNotNull(timerManager.stopTimer(timer, 1, timerWriter.capture(timer))))
            awaitState { db.timeLogDao().getActiveTimeLogForHabit(timer) == null }
        }
        sync()
        val complete = requireNotNull(db.timeLogDao().getTimeLogByUuid(log.uuid))
        assertNotNull(complete.endTime)
        assertTrue(complete.timerActiveElapsedMillis >= 60_000)
        if (countdown) assertEquals(60_000L, complete.timerActiveElapsedMillis)
        val allocations = db.timeLogDao().getDayAllocations(log.uuid)
        assertEquals(complete.timerActiveElapsedMillis, allocations.sumOf { it.durationMillis })
        val remote = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundStatus(log.uuid) })
        val completedSession = requireNotNull(remote.session)
        assertEquals("completed", completedSession.state)
        assertEquals(complete.timerActiveElapsedMillis, completedSession.activeElapsedMs)
        val snapshot = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundBootstrap() })
        val duration = snapshot.changes.single { it.entityType == "activity_event" && it.entityUuid == log.uuid }.payload
        assertEquals(complete.timerActiveElapsedMillis, duration.getValue("duration_milliseconds").jsonPrimitive.long)
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertTrue(db.timeLogDao().getPendingTimerCommands().isEmpty())
        sync()
        assertEquals(complete, db.timeLogDao().getTimeLogByUuid(log.uuid))
        assertEquals(allocations, db.timeLogDao().getDayAllocations(log.uuid))
        assertEquals(account, tokens.authenticationSnapshot()!!.session.userId)
    }
    private suspend fun command(reference: CountCommandReference) =
        CountCommandStore(db, tokens, sessions).resolve(reference.native()).historical

    /** Actual sender/ACK evidence plus explicit byte-identical HTTP replay. This is the
     * owned FastAPI server, not a scripted response or direct acceptance-table write. */
    private suspend fun acceptedAndReplayed(source: com.dayforge.data.local.entity.SyncOutboxEntity) {
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
        val transmission = requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, source.operationId))
        val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, source.operationId))
        assertEquals(5, origin.protocol); assertEquals(5, transmission.protocol)
        assertEquals(NextRequestSql.sourceHash(source), origin.sourceHash)
        assertEquals(nextRequestHash(transmission.wireBytes), transmission.wireHash)
        assertEquals(NextRequestSql.rowHash(db, "next_request_origins", "kind=? AND requestId=?",
            arrayOf(NEXT_OPERATION, source.operationId)), receipt.originHash)
        assertEquals(NextRequestSql.rowHash(db, "next_transmissions", "kind=? AND requestId=?",
            arrayOf(NEXT_OPERATION, source.operationId)), receipt.transmissionHash)
        val request = Json.decodeFromString<com.dayforge.data.api.dto.RoundSyncPushRequest>(transmission.wireBytes.toString(Charsets.UTF_8))
        assertEquals(source.operationId, request.operations.single().operationId)
        assertEquals(source.entityUuid, request.operations.single().entityUuid)
        val replay = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) {
            it.roundPushFrozen(transmission.wireBytes)
        }).results.single()
        assertEquals(source.operationId, replay.operationId); assertEquals(source.entityUuid, replay.entityUuid)
        assertEquals("already_applied", replay.status)
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
        assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_OPERATION, source.operationId))
        assertArrayEquals(transmission.wireBytes,
            requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, source.operationId)).wireBytes)
    }

    private fun phaseCount(kind: RetainedJourneyNative.Kind) = native.ends.count { it.kind == kind }
    private fun nativePhases(t0: Int, countT1: Int, metricT1: Int, t2: Int, accepted: Set<String>) {
        assertTrue(native.observationFailures.isEmpty())
        assertEquals(t0, phaseCount(RetainedJourneyNative.Kind.T0))
        assertEquals(countT1, phaseCount(RetainedJourneyNative.Kind.COUNT_T1))
        assertEquals(metricT1, phaseCount(RetainedJourneyNative.Kind.METRIC_T1))
        assertEquals(t2, phaseCount(RetainedJourneyNative.Kind.T2))
        val actual = native.ends.filter { it.kind == RetainedJourneyNative.Kind.ACK }.flatMap { it.acceptedRequests }
        assertEquals(accepted, actual.toSet()); assertEquals(accepted.size, actual.size)
    }

    private suspend fun independentMetricCommand(metricId: Long) {
        val before = current { db.metricLogDao().getAllLogsForMetric(metricId) }
        val saved = current {
            val selected = metrics.getMetricForEditing(metricId)
            val metric = requireNotNull(selected.value)
            native.arm()
            val attempt = metrics.beginIndependentValue(metricId, 17.375, "actual M1 backend command",
                authority = requireNotNull(selected.authority), expectedMetricUuid = metric.uuid)
            val reference = requireNotNull(attempt.reference)
            assertTrue(metrics.resolveIndependentAttempt(attempt) is IndependentMetricResolution.Unregistered)
            assertEquals(before, db.metricLogDao().getAllLogsForMetric(metricId))
            val value = metrics.executeIndependentValue(attempt)
            assertEquals(reference, value.reference)
            val row = command(reference)
            assertEquals(CountCommandRecords.Action.METRIC_APPEND, row.intent.action)
            assertEquals(CountCommandRecords.Execution.APPLIED, row.status.execution)
            assertEquals(CountCommandRecords.Delivery.RESULT_PENDING, row.status.delivery)
            assertNull(row.status.parent); assertNull(row.status.child)
            assertEquals(CountCommandEvidence.Profile.METRIC_ROUND_V5, requireNotNull(row.outcome).evidence.profile)
            nativePhases(1, 0, 1, 0, emptySet())
            value
        }
        val reference = requireNotNull(saved.reference)
        val original = current { command(reference).let { it.intent.text() to requireNotNull(it.outcome).text() } }
        val source = current { db.syncOutboxDao().getAll().single().also { assertEquals(saved.factUuid, it.entityUuid) } }
        sync()
        current {
            acceptedAndReplayed(source)
            val remote = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundBootstrap() })
            assertEquals(1, remote.changes.count { it.entityType == "metric_observation" && it.entityUuid == saved.factUuid })
            assertEquals(17.375, remote.changes.single { it.entityUuid == saved.factUuid }.payload.getValue("value").jsonPrimitive.double, 0.0)
            assertEquals(before.size + 1, db.metricLogDao().getAllLogsForMetric(metricId).size)
            assertTrue(db.syncOutboxDao().getAll().isEmpty())
            nativePhases(1, 0, 1, 0, setOf(source.operationId))
        }
        coldReopen()
        current {
            val resolved = metrics.resolveIndependentValue(reference) as IndependentMetricResolution.Saved
            assertEquals(saved.factId, resolved.value.factId); assertEquals(saved.factUuid, resolved.value.factUuid)
            val row = command(reference)
            assertEquals(original.first, row.intent.text()); assertEquals(original.second, requireNotNull(row.outcome).text())
            val display = MetricDisplayReader(db, tokens, sessions, graph.nextObjectEditor).overview().single { it.metric.id == metricId }
            assertEquals(saved.value, display.latestValue!!, 0.0)
            assertEquals(1, display.logs.count { it.uuid == saved.factUuid })
            val publication = activeHost.currentPublication()
            metrics.acknowledgeIndependentValue(resolved.value, publication::isCurrentInMemory)
            val terminal = command(reference)
            assertEquals(CountCommandRecords.Delivery.DELIVERED, terminal.status.delivery); assertFalse(terminal.status.pending)
            metrics.acknowledgeIndependentValue(resolved.value, publication::isCurrentInMemory)
            assertEquals(terminal.status, command(reference).status)
            sync(); acceptedAndReplayed(source)
            assertEquals(before.size + 1, db.metricLogDao().getAllLogsForMetric(metricId).size)
            assertEquals(before.associateBy { it.uuid }, db.metricLogDao().getAllLogsForMetric(metricId).filter { it.uuid != saved.factUuid }.associateBy { it.uuid })
            nativePhases(1, 0, 1, 1, setOf(source.operationId))
        }
    }

    private data class SavedCountChain(val parent: CountCommandReference, val child: CountCommandReference,
        val habitId: Long, val habitUuid: String, val metricId: Long, val factUuid: String,
        val metricFactUuid: String, val sources: List<com.dayforge.data.local.entity.SyncOutboxEntity>,
        val immutable: Map<CountCommandReference, Pair<String, String>>)

    /** Repository publication/required-prompt contract, not a claim of screen pixels. */
    private suspend fun countAndRequiredMetricCommand() {
        val saved = current {
            val metricId = metrics.createMetric(MetricEntity(name = "required command metric", unit = "kg", decimalPlaces = 3,
                iconResId = 0, colorHex = "#123456", appearance = appearance("metric.weight")), creationAuthority = creator.capture())
            val countId = habit("durable linked count", HabitType.COUNTING, metric = metricId)
            sync(); assertTrue(db.syncOutboxDao().getAll().isEmpty())
            val selected = habits.getHabitForEditing(countId)
            val habit = requireNotNull(selected.value)
            native.arm()
            val attempt = graph.nextObjectEditor.beginCountAdd(habit, requireNotNull(selected.authority), Instant.now(), ZoneId.systemDefault(), 1)
            val ref = CountCommandReference(attempt.input.commandId, attempt.input.inputHash, attempt.input.namespace.hash, attempt.input.namespace.dataset)
            try {
                assertEquals(CountCommandRecords.Execution.PREPARED, command(ref).status.execution)
                assertNull(command(ref).outcome)
                assertTrue(db.completionDao().getByHabitOnce(countId).isEmpty()); assertTrue(db.syncOutboxDao().getAll().isEmpty())
                nativePhases(1, 0, 0, 0, emptySet())
                val parent = requireNotNull(habits.logCompletionWithReceipt(app, countId,
                    authority = selected.authority, expectedHabitUuid = habit.uuid, preparedCommand = attempt).command)
                assertEquals(ref, parent.reference); assertTrue(parent.recorded); assertTrue(parent.requiresMetricFollowUp)
                assertEquals(CountCommandRecords.Delivery.FOLLOW_UP_PENDING, command(ref).status.delivery)
                val source = requireNotNull(graph.widgetFactReader.afterRecoveredCount(command(ref).intent.namespace,
                    countId, habit.uuid, requireNotNull(parent.factUuid)).completionSource)
                assertEquals(parent.factUuid, source.claim.completionUuid); assertEquals(1L, requireNotNull(source.count).todayQuantity)
                val page = graph.dashboardReadRepository.readProjection(ZonedDateTime.now())
                assertEquals(1L, page.counts.getValue(countId).todayQuantity)
                var published = false
                assertTrue(graph.dashboardReadRepository.publish(page) { published = true }); assertTrue(published)
                var prompt: CompletionMetricRead? = null
                metrics.publishCompletionPrompt(source) { prompt = it }
                val required = requireNotNull(prompt)
                assertEquals(listOf(metricId), required.metrics.map { requireNotNull(it.value).id })
                assertTrue(required.links.single().promptOnComplete)
                val target = required.metrics.single(); val metric = requireNotNull(target.value)
                val values = listOf(MetricValueDraft(metricId, 23.625, "required original input"))
                val recordedAt = System.currentTimeMillis()
                val childAttempt = graph.nextObjectEditor.beginMetricChild(parent, requireNotNull(target.authority),
                    required.source.habit, required.source.claim, values, listOf(metric), db.habitMetricLinkDao().getAllLinksForHabit(countId),
                    recordedAt, ZoneId.systemDefault().id)
                val childRef = CountCommandReference(childAttempt.input.commandId, childAttempt.input.inputHash,
                    childAttempt.input.namespace.hash, childAttempt.input.namespace.dataset)
                try {
                    assertEquals(CountCommandRecords.Execution.PREPARED, command(childRef).status.execution)
                    assertNull(command(childRef).outcome); assertTrue(db.metricLogDao().getAllLogsForMetric(metricId).isEmpty())
                    val child = requireNotNull(metrics.recordValuesWithReceipt(values, recordedAt, authority = target.authority,
                        expectedMetrics = listOf(metric), promptHabit = required.source.habit, promptFact = required.source.claim,
                        promptCompletionSource = true, parentCommand = parent, preparedCommand = childAttempt).command)
                    assertEquals(childRef, child.reference)
                    assertEquals(CountCommandRecords.Delivery.METRICS_COMMITTED, command(ref).status.delivery)
                    assertEquals(ref.commandId, command(childRef).status.parent)
                    val observation = db.metricLogDao().getAllLogsForMetric(metricId).single()
                    val sources = db.syncOutboxDao().getAll()
                    assertEquals(2, sources.size)
                    assertEquals(setOf(parent.factUuid, observation.uuid), sources.map { it.entityUuid }.toSet())
                    nativePhases(2, 1, 1, 0, emptySet())
                    SavedCountChain(ref, childRef, countId, habit.uuid, metricId, requireNotNull(parent.factUuid), observation.uuid,
                        sources, listOf(ref, childRef).associateWith { command(it).let { row -> row.intent.text() to requireNotNull(row.outcome).text() } })
                } finally { graph.nextObjectEditor.releaseUnexecuted(childAttempt) }
            } finally { graph.nextObjectEditor.releaseUnexecuted(attempt) }
        }
        sync()
        current {
            saved.sources.forEach { acceptedAndReplayed(it) }
            assertTrue(db.syncOutboxDao().getAll().isEmpty())
            nativePhases(2, 1, 1, 0, saved.sources.map { it.operationId }.toSet())
        }
        coldReopen()
        current {
            val parent = (habits.resumeCountCommandAfterCompleteProof(app, saved.parent) as CountCommandRecovery.HistoricalCommitted).receipt
            val child = (metrics.resumeLinkedMetricCommand(saved.child) as CountCommandRecovery.HistoricalCommitted).receipt
            assertEquals(saved.parent, parent.reference); assertEquals(saved.child, child.reference)
            for ((reference, bytes) in saved.immutable) {
                val row = command(reference); assertEquals(bytes.first, row.intent.text()); assertEquals(bytes.second, requireNotNull(row.outcome).text())
            }
            val source = requireNotNull(graph.widgetFactReader.afterRecoveredCount(command(saved.parent).intent.namespace,
                saved.habitId, saved.habitUuid, saved.factUuid).completionSource)
            val publication = activeHost.currentPublication()
            metrics.deliverFormalMetricResult(parent, child, source, publication::isCurrentInMemory)
            val terminal = listOf(saved.parent, saved.child).associateWith { command(it).status }
            assertTrue(terminal.values.all { !it.pending && it.delivery == CountCommandRecords.Delivery.DELIVERED })
            metrics.deliverFormalMetricResult(parent, child, source, publication::isCurrentInMemory)
            assertEquals(terminal, listOf(saved.parent, saved.child).associateWith { command(it).status })
            sync(); saved.sources.forEach { acceptedAndReplayed(it) }
            assertEquals(listOf(saved.factUuid), db.completionDao().getByHabitOnce(saved.habitId).map { it.uuid })
            assertEquals(listOf(saved.metricFactUuid), db.metricLogDao().getAllLogsForMetric(saved.metricId).map { it.uuid })
            val remote = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundBootstrap() })
            assertEquals(1, remote.changes.count { it.entityType == "activity_event" && it.entityUuid == saved.factUuid })
            assertEquals(1, remote.changes.count { it.entityType == "metric_observation" && it.entityUuid == saved.metricFactUuid })
            assertEquals(23.625, remote.changes.single { it.entityUuid == saved.metricFactUuid }.payload.getValue("value").jsonPrimitive.double, 0.0)
            assertTrue(db.syncOutboxDao().getAll().isEmpty())
            nativePhases(2, 1, 1, 1, saved.sources.map { it.operationId }.toSet())
        }
    }
}
