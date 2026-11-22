package com.dayforge.ui.recovery

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.repository.*
import com.dayforge.domain.service.CountActionGate
import com.dayforge.domain.service.CountCommandRecoveryWorkflow
import com.dayforge.domain.service.SyncManager
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Host/Panel explicit action, actual formal registration/bootstrap/COUNT/ACK/restart.
 * No fixed Coordinator substitute, no screen-pixel or Launcher/process-death claim. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CountCommandExplicitEndConsumerTest {
    private val boundary = CountObsoleteFollowUpNativeBoundary()
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also { it.nativeFactory = boundary }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @Inject lateinit var gate: CountActionGate
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var sync: SyncManager
    @Inject lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private var model: CountCommandRecoveryViewModel? = null
    private var activity: ActivityScenario<ComponentActivity>? = null
    private var peer: CountFollowUpJourneyServer? = null
    private lateinit var parent: CountCommandReceipt
    private suspend fun setup() {
        hilt.inject(); epochs.connect(); epochs.login()
        val server = CountFollowUpJourneyServer().also { peer = it }
        preferences.setServerUrl(server.server.origin.toString()); preferences.setRemoteServerUrl(null)
        sync.sync().getOrThrow()
        epochs.current { graph ->
            assertEquals(1, graph.database.nextSyncStateDao().rows().single().challengeContract)
            val habit = requireNotNull(graph.habitDao.getHabitByUuid(server.selected.uuid))
            val selected = graph.nextObjectEditor.habit(habit.id)
            parent = requireNotNull(graph.habitRepository.logCompletionWithReceipt(app, habit.id,
                authority = requireNotNull(selected.authority), expectedHabitUuid = habit.uuid).command)
            assertTrue(parent.recorded); assertTrue(parent.requiresMetricFollowUp)
        }
        sync.sync().getOrThrow() // Original COUNT actual SEND/ACK, then pull.
        val restart = epochs.current { graph ->
            val habit = requireNotNull(graph.habitDao.getHabitByUuid(server.selected.uuid))
            val selected = graph.nextObjectEditor.habit(habit.id)
            graph.nextChallengeRestartRepository.restart(requireNotNull(selected.value), requireNotNull(selected.authority))
        }
        sync.sync().getOrThrow() // Original local restart send/accept plus contiguous Plan log.
        epochs.current { graph ->
            assertTrue(graph.syncOutboxDao.getAll().isEmpty())
            val row = CountCommandStore(graph.database, epochs.tokens, epochs.sessions).resolve(parent.reference.native()).historical
            assertEquals(CountCommandRecords.Delivery.FOLLOW_UP_PENDING, row.status.delivery)
            val habit = requireNotNull(graph.habitDao.getHabitByUuid(server.selected.uuid))
            assertEquals(restart.head, requireNotNull(graph.nextObjectEditor.habit(habit.id).authority).rounds?.completionRounds?.head(habit.uuid))
            assertEquals(1, graph.completionDao.getAllCompletionsOnce().size)
        }
        server.assertOriginalCountAndAcceptedRestart()
    }
    @After fun finish() = runBlocking<Unit> {
        boundary.release(); activity?.close()
        model?.let { vm ->
            val job = withContext(Dispatchers.Main.immediate) { requireNotNull(vm.viewModelScope.coroutineContext[Job]).also { models.clear() } }
            withTimeout(5_000) { job.join() }
        }
        peer?.close()
    }
    private suspend fun row() = epochs.current { graph -> CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
        .resolve(parent.reference.native()).historical }
    private suspend fun open() {
        withContext(Dispatchers.Main.immediate) {
            model = CountCommandRecoveryViewModel(epochs.host, CountCommandRecoveryWorkflow(app, epochs.host, gate))
                .also { models.put("explicit-followup-end", it) }
        }
        activity = ActivityScenario.launch(ComponentActivity::class.java).also { scenario -> scenario.onActivity { screen ->
            screen.setContent { MaterialTheme { CountCommandRecoveryPanel(requireNotNull(model)) } }
        } }
        compose.onNodeWithText(app.getString(R.string.count_recovery_open)).performClick(); pageReady()
    }
    private suspend fun pageReady() {
        withTimeout(45_000) { requireNotNull(model).frames.first { it != null } }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }; assertFalse(requireNotNull(model).failed.value)
    }
    private suspend fun showDecision() {
        compose.onNodeWithText(app.getString(R.string.count_recovery_continue)).assertIsEnabled().performClick()
        withTimeout(45_000) { requireNotNull(model).frames.first { it?.value?.result is CountCommandPendingRepository.Result.EndAvailable } }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }; assertFalse(requireNotNull(model).failed.value)
        assertNull(requireNotNull(model).frames.value?.value?.followUp)
        compose.onNodeWithText(app.getString(R.string.count_recovery_end_followup)).assertIsEnabled()
    }
    private fun end() = compose.onNodeWithText(app.getString(R.string.count_recovery_end_followup)).assertIsEnabled().performClick()
    private suspend fun ended() {
        withTimeout(45_000) { requireNotNull(model).frames.first { it?.value?.result is CountCommandPendingRepository.Result.FollowUpEnded } }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }; assertFalse(requireNotNull(model).failed.value)
        compose.onNodeWithText(app.getString(R.string.count_recovery_followup_ended)).assertExists()
        assertEquals(CountCommandRecords.Delivery.CLOSED, row().status.delivery)
    }
    private fun nonCommand(contents: JsonObject): JsonObject {
        val excluded = com.dayforge.data.local.CountCommandV20Schema.tables + "count_command_results"
        val tables = contents.getValue("tables").jsonObject
        val controls = tables.getValue("count_trust_control").jsonArray.map { raw ->
            val row = raw.jsonObject
            require(row.keys == setOf("__recovery_rowid", "id", "storageId", "generation", "dirty", "commandRootHash"))
            JsonObject(row - "commandRootHash")
        }
        return JsonObject(contents + ("tables" to JsonObject(tables.filterKeys { it !in excluded } +
            ("count_trust_control" to JsonArray(controls)))))
    }
    @Test(timeout = 150_000) fun acceptedRoundShowsPendingUntilTheRealExplicitEndButtonIsChosen() = runBlocking<Unit> {
        setup(); open(); val before = epochs.exactMain(); val original = row(); val prefs = dataStore.data.first().asMap()
        showDecision(); assertTrue("Complete main proof must remain unchanged", before == epochs.exactMain()); assertTrue(row().status.pending)
        end(); ended()
        assertEquals(original.intent.text(), row().intent.text()); assertEquals(requireNotNull(original.outcome).text(), requireNotNull(row().outcome).text())
        assertTrue("Every non-command cell and control field except the authenticated anchor stays exact", nonCommand(before.contents) == nonCommand(epochs.exactMain().contents)); assertEquals(prefs, dataStore.data.first().asMap())
        val stable = epochs.exactMain(); val previous = requireNotNull(model).frames.value
        compose.onNodeWithText(app.getString(R.string.count_recovery_continue)).performClick()
        withTimeout(45_000) { requireNotNull(model).frames.first { it != null && it !== previous } }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertTrue("Complete main proof must remain unchanged", stable == epochs.exactMain()); assertNull(requireNotNull(model).frames.value?.value?.followUp)
    }
    @Test(timeout = 150_000) fun refreshedPanelRejectsTheOriginalEndCallbackInsteadOfRefreshingItsAuthority() = runBlocking<Unit> {
        setup(); open(); showDecision(); val original = requireNotNull(model).frames.value
        val click = requireNotNull(compose.onNodeWithText(app.getString(R.string.count_recovery_end_followup))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.onNodeWithText(app.getString(R.string.count_recovery_refresh)).assertIsEnabled().performClick()
        withTimeout(45_000) { requireNotNull(model).frames.first { it != null && it !== original } }; pageReady()
        val before = epochs.exactMain()
        val actions = withContext(Dispatchers.Main.immediate) {
            val vm = requireNotNull(model); assertFalse(vm.busy.value); assertFalse(vm.failed.value)
            val job = requireNotNull(vm.viewModelScope.coroutineContext[Job]); val old = job.children.toSet()
            assertTrue(click.invoke()); job.children.filter { it !in old }.toList()
        }
        withTimeout(5_000) { actions.forEach { it.join() } }
        assertTrue(requireNotNull(model).failed.value); assertFalse(requireNotNull(model).busy.value)
        assertTrue("Complete main proof must remain unchanged", before == epochs.exactMain()); assertTrue(row().status.pending)
        showDecision(); end(); ended()
    }
    @Test(timeout = 150_000) fun closingAfterRealMetadataCommitDoesNotPublishThroughTheExpiredPanelSession() = runBlocking<Unit> {
        setup(); open(); showDecision(); val before = epochs.exactMain(); val prefs = dataStore.data.first().asMap()
        boundary.pauseAfterRootEnd(); end()
        assertTrue(withContext(Dispatchers.IO) { boundary.awaitClosingEnd() })
        compose.onNodeWithText(app.getString(R.string.count_recovery_close)).assertIsEnabled().performClick()
        boundary.release(); withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertEquals(1, boundary.pausedEnds.get()); assertNull(requireNotNull(model).frames.value)
        assertEquals(CountCommandRecords.Delivery.CLOSED, row().status.delivery)
        assertTrue("Every non-command cell and control field except the authenticated anchor stays exact", nonCommand(before.contents) == nonCommand(epochs.exactMain().contents)); assertEquals(prefs, dataStore.data.first().asMap())
        // Explicit choice already committed. A new page cannot reopen Goal/metrics or retry it.
        compose.onNodeWithText(app.getString(R.string.count_recovery_open)).performClick(); pageReady()
        assertTrue(requireNotNull(model).frames.value!!.value.page.value.entries.isEmpty())
        assertNull(requireNotNull(model).frames.value?.value?.followUp)
    }
}
