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
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.*
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.TimerManager
import com.dayforge.domain.service.TimerService
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
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
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val testName = TestName()
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var metrics: MetricRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var business: BusinessSyncRepository
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var timerManager: TimerManager
    @Inject lateinit var timerWriter: NextTimerWriter
    @Inject internal lateinit var http: NextSyncHttp
    @Inject lateinit var auth: AuthApi
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val db get() = storage.database
    private lateinit var activity: ActivityScenario<ComponentActivity>
    private lateinit var account: String
    private fun appearance(role: String) = ObjectAppearance(IconReference.Role(role), "#123456", "theme")

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("dayforgeJointPort")).toInt()
        require(port in 1024..65535)
        hilt.inject()
        sessions.exclusive { tokens.clearTokens() }
        habits.clearAllData(app)
        preferences.setRemoteServerUrl(null)
        preferences.setActiveServerUrl(null)
        preferences.setServerUrl("http://127.0.0.1:$port/")
        val name = when {
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
        assertTrue(db.habitDao().getAllHabitsOnce().isEmpty())
        assertEquals(account, tokens.authenticationSnapshot()!!.session.userId)
        assertNotNull(requireNotNull(tokens.localSyncAccess()).deviceId)
        assertTrue("structure.write" in requireNotNull(tokens.localSyncAccess()).capabilities)
    }

    @After fun cleanup() = runBlocking<Unit> {
        if (::timerManager.isInitialized) {
            app.stopService(Intent(app, TimerService::class.java))
            awaitState {
                val descriptor = instrumentation.uiAutomation.executeShellCommand(
                    "dumpsys activity services ${app.packageName}/com.dayforge.domain.service.TimerService")
                val dump = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                !dump.contains("ServiceRecord{")
            }
        }
        if (::activity.isInitialized) activity.close()
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
        if (::preferences.isInitialized) {
            preferences.setServerUrl(null)
            preferences.setRemoteServerUrl(null)
            preferences.setActiveServerUrl(null)
        }
    }

    private suspend fun awaitState(predicate: suspend () -> Boolean) = withTimeout(15_000) {
        while (!predicate()) delay(50)
    }

    private suspend fun sync() {
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

    @Test fun realMixedCoreFactsKeepGoalMetricLinksAndBothCountModes() = runBlocking<Unit> {
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
    }

    @Test fun realOnceUndoAndOfflineChallengeRestartRetainOriginalFacts() = runBlocking<Unit> {
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

    @Test fun realForwardTimerContinuesAcrossActualSync() = runBlocking<Unit> { timed(false) }
    @Test fun realCountdownTimerContinuesAcrossActualSync() = runBlocking<Unit> { timed(true) }

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
}
