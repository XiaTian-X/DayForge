package com.dayforge.widget.checkin

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.Job
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.LocalReplicaIdentity
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.CountTrustRecoveryPreparation
import com.dayforge.data.repository.LegacyWidgetRestartClaim
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Same storage, same account generation, identical original claim and data. Only the actual
 * native owner/whole Host graph is replaced by the original recovery preparation path.
 * The original visible Activity button must reject; a newly loaded dialog may act.
 */
@HiltAndroidTest
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WidgetGoalEpochTest {
    @get:Rule(order = 0) val timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 2) val epochs = PhysicalDatabaseEpochFixture()
    @get:Rule(order = 3) val hilt = HiltAndroidRule(this)
    private val effects = StandardTestDispatcher()
    @get:Rule(order = 4) val compose = createEmptyComposeRule(effectContext = effects)
    @Inject lateinit var preferences: com.dayforge.data.local.PreferencesManager
    private lateinit var server: com.dayforge.data.appearance.MaterialSocketServer
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun id(n: Int) = "bc198000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    @Before fun start() = runBlocking<Unit> {
        hilt.inject(); epochs.connect(); epochs.login()
        // The unchanged structural guard may refresh cached capability. Keep its real
        // network collaborator bounded on loopback; a 503 preserves original offline access.
        server = com.dayforge.data.appearance.MaterialSocketServer { _, _ ->
            com.dayforge.data.appearance.MaterialSocketServer.Reply("{}".toByteArray(), 503)
        }
        preferences.setServerUrl(server.origin.toString()); preferences.setRemoteServerUrl(null)
        epochs.sessions.exclusive {
            epochs.tokens.saveServerIdentity(id(2), id(3))
            epochs.tokens.getOrCreateInstallationId()
            epochs.tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write", "facts.append", "timer.control"), true, 1)
        }
    }
    @After fun closeActivities() {
        instrumentation.runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in androidx.test.runner.lifecycle.Stage.entries) monitor.getActivitiesInStage(stage).toList().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { effects.scheduler.runCurrent() }
        if (::server.isInitialized) server.close()
    }
    @Test fun oldConfirmCannotAcquireReplacementOwnerButFreshDialogCanComplete() = runBlocking<Unit> { verify(true) }
    @Test fun oldContinueCannotAcquireReplacementOwnerButFreshDialogCanChangeMode() = runBlocking<Unit> { verify(false) }

    private suspend fun verify(complete: Boolean) {
        val row = HabitEntity(id = 44, uuid = id(44), name = "Goal source owner", habitType = HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, targetCycles = 1, failMode = FailMode.STRICT)
        val claim = epochs.host.withGraph { graph ->
            graph.database.withTransaction { assertEquals(row.id, graph.habitDao.insert(row)) }
            val identity = epochs.sessions.exclusive { graph.database.withTransaction {
                LegacyWidgetRestartClaim.displayed(row, requireNotNull(epochs.tokens.localCoreWriteAccess()),
                    LocalReplicaIdentity.readInTransaction(graph.database))
            } }
            val result = graph.legacyWidgetFactWorkflow.perform(context,
                graph.legacyWidgetFactWorkflow.capture(identity).forAction("toggle"))
            assertTrue(result.result.goalReached)
            result.identity
        }
        val label = context.getString(if (complete) R.string.action_confirm_complete else R.string.action_continue_checkin)
        fun intent() = GoalCompletionActivity.createIntent(context, row.id, "ignored input", 1, 1, legacyIdentity = claim)
        fun awaitButton() = compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(label)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        suspend fun awaitDestroyed(scenario: ActivityScenario<GoalCompletionActivity>, work: Job) {
            withTimeout(5_000) {
                while (scenario.state != Lifecycle.State.DESTROYED) {
                    instrumentation.runOnMainSync { effects.scheduler.runCurrent() }; delay(20)
                }
            }
            withTimeout(5_000) { work.join() }
            assertTrue(work.isCompleted)
        }
        ActivityScenario.launch<GoalCompletionActivity>(intent()).use { scenario ->
            awaitButton()
            // Capture the REAL original semantics callback while the original frame is live.
            // Do not fetch semantics after owner replacement: idle/recomposition may already
            // close the old page, which would fail to exercise submitGoal's source boundary.
            val originalClick = requireNotNull(compose.onNodeWithText(label).fetchSemanticsNode()
                .config[SemanticsActions.OnClick].action)
            lateinit var originalActivity: GoalCompletionActivity
            lateinit var originalWork: Job
            scenario.onActivity { originalActivity = it; originalWork = requireNotNull(it.lifecycleScope.coroutineContext[Job]) }
            val autoAdvance = compose.mainClock.autoAdvance
            compose.mainClock.autoAdvance = false
            try {
            val access = requireNotNull(epochs.tokens.localSyncAccess())
            val old = epochs.current { graph ->
                CountTrustRecoveryPreparation(graph.database, epochs.tokens, epochs.sessions).discardCache(access)
                graph.database
            }
            assertTrue(requireNotNull(CountTrustRuntime.forDatabase(old)).isRetired)
            // discardCache only requests asynchronous replacement. Observe real lifecycle
            // events, releasing each CONTROL lease, until the actual new graph is published.
            val fresh = withTimeout(30_000) {
                combine(epochs.host.controlChanges, epochs.host.lastRebuildFailure) { _, failure -> failure }
                    .map { failure ->
                        failure?.let { throw it }
                        epochs.current { it.database }
                    }.first { it !== old }
            }
            assertNotSame(old, fresh); assertFalse(old.isOpen); assertTrue(fresh.isOpen)
            assertTrue(requireNotNull(CountTrustRuntime.forDatabase(old)).isRetired)
            assertEquals(claim.storageId, epochs.current { graph -> graph.database.withTransaction { LocalReplicaIdentity.readInTransaction(graph.database) } })
            assertEquals(claim.session(), requireNotNull(epochs.tokens.localCoreWriteAccess()).session)
            epochs.current { graph -> assertEquals(claim.plan, com.dayforge.data.repository.SyncV2Mapper.planNode(requireNotNull(graph.habitDao.getHabitById(row.id)))) }
            val before = epochs.exactMain()
            var clicked = false
            instrumentation.runOnMainSync {
                assertFalse(originalActivity.isFinishing); assertFalse(originalActivity.isDestroyed)
                clicked = originalClick()
            }
            assertTrue("Original real OnClick callback was executed", clicked)
            compose.mainClock.autoAdvance = autoAdvance
            awaitDestroyed(scenario, originalWork)
            assertEquals(before, epochs.exactMain())
            epochs.current { graph ->
                val actual = requireNotNull(graph.habitDao.getHabitById(row.id))
                assertTrue(actual.isActive); assertEquals(FailMode.STRICT, actual.failMode)
            }
            } finally { compose.mainClock.autoAdvance = autoAdvance }
        }
        ActivityScenario.launch<GoalCompletionActivity>(intent()).use { scenario ->
            awaitButton()
            lateinit var currentWork: Job
            scenario.onActivity { currentWork = requireNotNull(it.lifecycleScope.coroutineContext[Job]) }
            compose.onNodeWithText(label).performClick(); awaitDestroyed(scenario, currentWork)
        }
        epochs.current { graph ->
            val actual = requireNotNull(graph.habitDao.getHabitById(row.id))
            assertEquals(row.uuid, actual.uuid)
            if (complete) { assertFalse(actual.isActive); assertEquals(FailMode.STRICT, actual.failMode) }
            else { assertTrue(actual.isActive); assertEquals(FailMode.LOOSE, actual.failMode) }
            assertEquals(1, graph.completionDao.getByHabitOnce(row.id).size)
            assertEquals(0, graph.metricLogDao.countAll())
        }
    }

}
