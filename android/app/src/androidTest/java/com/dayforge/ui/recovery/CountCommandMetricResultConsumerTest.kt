package com.dayforge.ui.recovery

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.*
import com.dayforge.domain.service.CountActionGate
import com.dayforge.domain.service.CountCommandRecoveryWorkflow
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Hilt/Host/Compose result consumer, not Main navigation, Launcher or pixels. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CountCommandMetricResultConsumerTest {
    private val barrier = CountMetricResultReadBarrier()
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also { it.nativeFactory = barrier }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @Inject lateinit var gate: CountActionGate
    @Inject lateinit var preferences: com.dayforge.data.local.PreferencesManager
    @Inject lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private var model: CountCommandRecoveryViewModel? = null
    private var activity: ActivityScenario<ComponentActivity>? = null
    private lateinit var habit: HabitEntity
    private lateinit var parent: CountCommandReceipt
    private lateinit var child: CountCommandReceipt
    @Before fun connect() = runBlocking<Unit> {
        hilt.inject(); epochs.connect(); epochs.login(); epochs.tokens.getOrCreateInstallationId()
        habit = HabitEntity(id = 819, name = "Historical saved group", habitType = HabitType.COUNTING,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 10,
            uuid = UUID.randomUUID().toString())
        epochs.current { graph ->
            val metrics = graph.database.withTransaction {
                val sql = graph.database.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                assertEquals(habit.id, graph.habitDao.insert(habit))
                val selected = (1..2).map { index ->
                    val raw = MetricEntity(name = "Original metric $index", unit = "unit$index", decimalPlaces = 2,
                        iconResId = 1, colorHex = "#123456", uuid = UUID.randomUUID().toString())
                    raw.copy(id = graph.metricDao.insert(raw)).also { metric ->
                        graph.habitMetricLinkDao.insertOrIgnore(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
                            metricId = metric.id, metricUuid = metric.uuid, promptOnComplete = true))
                    }
                }
                sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1"); selected
            }
            parent = requireNotNull(graph.habitRepository.logCompletionWithReceipt(app, habit.id,
                countIdentity = graph.countActionReader.legacy(habit)).command)
            val current = requireNotNull(graph.habitDao.getVisibleHabitById(habit.id))
            val claim = graph.habitPreferenceRepository.read(current).claim
            child = requireNotNull(graph.metricRepository.recordLegacyPromptValuesWithReceipt(
                metrics.mapIndexed { index, metric -> MetricValueDraft(metric.id, index + 2.5, "original saved value") },
                System.currentTimeMillis(), claim, graph.habitPreferenceRepository, current, metrics, parent).command)
            assertEquals(2, graph.metricLogDao.getAllLogsForMetric(metrics[0].id).size + graph.metricLogDao.getAllLogsForMetric(metrics[1].id).size)
            // The old form cannot rebuild after this real original deletion. T1 evidence stays retained.
            graph.habitRepository.deleteHabit(current)
            assertNull(graph.habitDao.getHabitById(habit.id))
        }
    }
    @After fun finish() = runBlocking<Unit> {
        barrier.release(); activity?.close()
        model?.let { vm ->
            val job = withContext(Dispatchers.Main.immediate) { requireNotNull(vm.viewModelScope.coroutineContext[Job]).also { models.clear() } }
            withTimeout(5_000) { job.join() }
        }
    }
    private suspend fun row(reference: CountCommandReference) = epochs.current { graph ->
        CountCommandStore(graph.database, epochs.tokens, epochs.sessions).resolve(reference.native()).historical
    }
    private suspend fun open() {
        withContext(Dispatchers.Main.immediate) {
            model = CountCommandRecoveryViewModel(epochs.host, CountCommandRecoveryWorkflow(app, epochs.host, gate))
                .also { models.put("completed-metrics", it) }
        }
        activity = ActivityScenario.launch(ComponentActivity::class.java).also { scenario -> scenario.onActivity { screen ->
            screen.setContent { MaterialTheme { CountCommandRecoveryPanel(requireNotNull(model)) } }
        } }
        compose.onNodeWithText(app.getString(R.string.count_recovery_open)).performClick()
        withTimeout(45_000) { requireNotNull(model).frames.first { it != null } }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertFalse(requireNotNull(model).failed.value)
    }
    private fun continueParent() = compose.onAllNodesWithText(app.getString(R.string.count_recovery_continue))[0].assertIsEnabled().performClick()
    private suspend fun result() = withTimeout(45_000) {
        requireNotNull(model).frames.first { it?.value?.result is CountCommandPendingRepository.Result.MetricSaved }
    }!!
    private fun nonCommand(contents: JsonObject): JsonObject {
        val names = setOf("count_command_intents", "count_command_status", "count_command_results", "count_command_nodes", "count_command_recovery_roots")
        val tables = contents.getValue("tables").jsonObject
        val control = tables.getValue("count_trust_control").jsonArray.map { row ->
            require(row.jsonObject.keys.containsAll(setOf("id", "storageId", "generation", "dirty", "commandRootHash")))
            JsonObject(row.jsonObject - "commandRootHash")
        }
        return JsonObject(contents + ("tables" to JsonObject(tables.filterKeys { it !in names } + ("count_trust_control" to JsonArray(control)))))
    }
    @Test(timeout = 150_000) fun visibleHistoricalGroupAfterSourceDeletionUsesNoFormAndDeliversBothOriginalIds() = runBlocking<Unit> {
        val input = row(child.reference).intent.text(); val outcome = requireNotNull(row(child.reference).outcome).text()
        open(); val before = epochs.exactMain(); val prefs = dataStore.data.first().asMap(); continueParent()
        val displayed = result(); withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertFalse(requireNotNull(model).failed.value)
        val saved = displayed.value.result as CountCommandPendingRepository.Result.MetricSaved
        assertEquals(2, saved.count); assertTrue(saved.preferenceReady); assertNull(displayed.value.followUp)
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.count_recovery_metrics_saved, 2, 2)).assertExists()
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(parent.reference).status.delivery)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(child.reference).status.delivery)
        assertTrue(nonCommand(before.contents) == nonCommand(epochs.exactMain().contents))
        assertTrue(prefs == dataStore.data.first().asMap())
        assertEquals(input, row(child.reference).intent.text()); assertEquals(outcome, requireNotNull(row(child.reference).outcome).text())
        val completed = epochs.exactMain(); val old = requireNotNull(model).frames.value
        continueParent(); withTimeout(45_000) { requireNotNull(model).frames.first { it !== old } }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertEquals(completed, epochs.exactMain())
    }
    @Test(timeout = 150_000) fun unboundPendingHintShowsSavedHistoryButDoesNotClaimOrPerformDelivery() = runBlocking<Unit> {
        epochs.sessions.exclusive {
            val session = requireNotNull(epochs.tokens.localCoreWriteAccess()).session
            preferences.addPendingMetricHabit(session, habit.uuid)
        }
        open(); val before = epochs.exactMain(); continueParent()
        val displayed = result(); withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        val saved = displayed.value.result as CountCommandPendingRepository.Result.MetricSaved
        assertFalse(saved.preferenceReady); assertTrue(saved.pending); assertNull(displayed.value.followUp)
        compose.onNodeWithText(app.getString(R.string.count_recovery_metrics_hint_unresolved)).assertExists()
        assertEquals(before, epochs.exactMain()); assertTrue(row(parent.reference).status.pending); assertTrue(row(child.reference).status.pending)
        assertTrue(preferences.readHabitPreferences(
            requireNotNull(epochs.tokens.localCoreWriteAccess()).session, habit.uuid).pending)
    }
    @Test(timeout = 150_000) fun closingTheRealPanelAfterActualReadEndPreventsLateModelAndT2() = runBlocking<Unit> {
        open(); val before = epochs.exactMain(); barrier.arm(parent.commandId)
        continueParent()
        assertTrue(withContext(Dispatchers.IO) { barrier.awaitReadEnd() })
        compose.onNodeWithText(app.getString(R.string.count_recovery_close)).assertIsEnabled().performClick()
        barrier.release()
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertEquals(1, barrier.ends); assertNull(requireNotNull(model).frames.value)
        assertEquals(before, epochs.exactMain()); assertTrue(row(parent.reference).status.pending); assertTrue(row(child.reference).status.pending)
        // A new explicit consumer, not the closed session, can finish the same exact group.
        compose.onNodeWithText(app.getString(R.string.count_recovery_open)).performClick()
        withTimeout(45_000) { requireNotNull(model).frames.first { it != null } }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        continueParent(); result(); withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(parent.reference).status.delivery)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(child.reference).status.delivery)
    }
}
