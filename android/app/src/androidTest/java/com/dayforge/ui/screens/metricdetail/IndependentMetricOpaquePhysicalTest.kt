package com.dayforge.ui.screens.metricdetail

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.repository.MetricDetailSourceReader
import com.dayforge.domain.service.MetricOverviewProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual opaque v4 account, original repository/read adapters as oracle, then real Host UI.
 * Corrupt ciphertext is written only into the testbed's same injected DataStore; no key is deleted. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class IndependentMetricOpaquePhysicalTest {
    @get:Rule(order = 0) val epochs = PhysicalDatabaseEpochFixture()
    @get:Rule(order = 1) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    @Inject lateinit var preferences: DataStore<Preferences>
    private val app: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    private lateinit var activity: ActivityScenario<ComponentActivity>
    private lateinit var model: MetricDetailViewModel
    @Before fun start() = runBlocking<Unit> {
        hilt.inject(); epochs.connect(); epochs.login("account-a")
        assertTrue(epochs.tokens.usesPreferences(preferences))
        epochs.sessions.exclusive {
            epochs.tokens.saveServerIdentity("server-a", "epoch-a")
            epochs.tokens.saveDeviceRegistration("device-a", setOf("sync.read", "facts.append", "structure.write"), true, 1)
        }
        assertNull(epochs.tokens.localCoreWriteAccess())
        assertTrue(requireNotNull(epochs.tokens.localOpaqueMetricAccess()).facts.canAppend)
        activity = ActivityScenario.launch(ComponentActivity::class.java)
        activity.onActivity { it.setContent { MaterialTheme { content.value?.invoke() } } }
    }
    @After fun finish() = runBlocking<Unit> {
        if (::activity.isInitialized) { compose.runOnIdle { content.value = null }; compose.waitForIdle(); activity.close() }
        val job = if (::model.isInitialized) model.viewModelScope.coroutineContext[Job] else null
        withContext(Dispatchers.Main.immediate) { models.clear() }
        withTimeout(5_000) { job?.join() }
    }
    private suspend fun seed() = epochs.current { graph ->
        val metric = MetricEntity(name = "Opaque original", unit = "kg", iconResId = 1, colorHex = "#123456", uuid = "legacy-metric")
        val id = graph.metricRepository.createMetric(metric)
        graph.metricRepository.recordValue(id, 7.5, "old original", expectedMetricUuid = metric.uuid)
        requireNotNull(graph.metricDao.getMetricById(id))
    }
    private fun show(metric: MetricEntity): DatabaseObjectSelection {
        val selection = runBlocking { epochs.metricSelection(metric.id) }
        compose.runOnIdle {
            model = MetricDetailViewModel(app, epochs.host, SavedStateHandle(mapOf("metricId" to metric.id)))
            models.put("metric", model)
            content.value = { MetricDetailScreen(metric.id, {}, viewModel = model, sourceSelection = selection) }
        }
        compose.waitUntil(45_000) { model.uiState.value.let { !it.isLoading && !it.sourceExpired && it.metric?.uuid == metric.uuid } }
        return selection
    }
    private fun openConfirm(): () -> Boolean {
        compose.onNodeWithText(app.getString(R.string.metric_record_value)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.metric_input_enter_value)).performTextInput("12.5")
        return requireNotNull(compose.onNode(hasText(app.getString(R.string.metric_record_value)) and hasClickAction() and hasAnyAncestor(isDialog()))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    }
    private suspend fun invoke(click: () -> Boolean) {
        var jobs = emptyList<Job>()
        compose.runOnIdle {
            val parent = requireNotNull(model.viewModelScope.coroutineContext[Job]); val before = parent.children.toSet()
            assertTrue(click()); jobs = parent.children.filter { it !in before }.toList()
        }
        withTimeout(45_000) { jobs.joinAll() }; compose.waitForIdle()
    }
    @Test(timeout = 150_000) fun originalOpaqueOverviewDetailAndWriteRemainLegalInActualGraph() = runBlocking<Unit> {
        val metric = seed(); val selection = epochs.metricSelection(metric.id)
        epochs.host.withSelection(selection, DatabaseObjectSelection.Kind.METRIC) { graph ->
            val originalOverview = MetricOverviewProvider(graph.metricRepository).observe().first()
            val originalDetail = MetricDetailSourceReader(graph.database, graph.metricRepository).observe(selection).first()
            val actualOverview = graph.metricOverviewProvider.observe().first()
            val actualDetail = graph.metricDetailSourceReader.observe(selection).first()
            assertEquals(originalOverview, actualOverview)
            assertEquals(originalDetail.metric, actualDetail.metric); assertEquals(originalDetail.latest, actualDetail.latest)
            assertEquals(originalDetail.logs, actualDetail.logs); assertEquals(originalDetail.links, actualDetail.links)
        }
        show(metric); invoke(openConfirm())
        compose.waitUntil(45_000) { model.uiState.value.metricSaved && !model.uiState.value.isRecording }
        assertNull(model.uiState.value.metricSaveReference); assertFalse(model.uiState.value.metricSaveUnknown)
        epochs.current { graph ->
            val rows = graph.metricLogDao.getAllLogsForMetric(metric.id)
            assertEquals(2, rows.size); assertEquals(listOf(12.5, 7.5), rows.map { it.value })
            assertEquals(0L, graph.database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM count_command_intents").use { check(it.moveToFirst()); it.getLong(0) })
            assertEquals(2, graph.database.syncOutboxDao().getAll().count { it.recordType == "metric_log" })
        }
    }
    @Test(timeout = 150_000) fun opaqueCapabilityMatrixMatchesOriginalWriterThroughFreshHostSelection() = runBlocking<Unit> {
        val metric = seed()
        epochs.sessions.exclusive { epochs.tokens.saveDeviceRegistration("device-a", setOf("sync.read", "structure.write"), true, 2) }
        assertFalse(requireNotNull(epochs.tokens.localOpaqueMetricAccess()).facts.canAppend)
        val originalSource = epochs.metricSelection(metric.id)
        val original = epochs.host.withSelection(originalSource, DatabaseObjectSelection.Kind.METRIC) { graph ->
            // Reproduce the original production withInput -> legacy recordValue path under
            // a fresh actual Host selection, not an isolated canAppend flag assertion.
            originalSource.requireMetric(requireNotNull(graph.metricDetailSourceReader.visibleMetric(metric.id)))
            val id = graph.metricRepository.recordValue(metric.id, 12.5, "", expectedMetricUuid = metric.uuid)
            requireNotNull(graph.metricLogDao.getById(id))
        }
        show(metric)
        epochs.current { assertEquals(12.5, it.metricOverviewProvider.observe().first().single().latestValue) }
        invoke(openConfirm())
        compose.waitUntil(45_000) { model.uiState.value.metricSaved && !model.uiState.value.isRecording }
        assertFalse(model.uiState.value.metricSaveUnknown); assertNull(model.uiState.value.metricSaveReference)
        epochs.current { graph ->
            val all = graph.metricLogDao.getAllLogsForMetric(metric.id)
            assertEquals(3, all.size)
            val fresh = all.single { it.uuid != original.uuid && it.value == 12.5 }
            assertEquals(original.metricId, fresh.metricId); assertEquals(original.unit, fresh.unit)
            assertEquals(original.value, fresh.value, 0.0); assertEquals(original.note, fresh.note)
            assertEquals(original.recordedTimezone, fresh.recordedTimezone); assertNotEquals(original.uuid, fresh.uuid)
            assertEquals(3, graph.database.syncOutboxDao().getAll().count { it.recordType == "metric_log" })
        }
    }
    @Test(timeout = 150_000) fun unreadableAccessCannotUseOpaqueFallbackEvenWithRetainedUiAndSameOwner() = runBlocking<Unit> {
        val metric = seed(); val selection = show(metric); val click = openConfirm()
        val database = epochs.current { it.database }
        preferences.edit { it[stringPreferencesKey("access_token")] = "enc:v1:not-valid-ciphertext" }
        assertNull(epochs.tokens.localOpaqueMetricAccess()); assertNull(epochs.tokens.localCoreWriteAccess())
        assertNotNull(runCatching { epochs.host.withSelection(selection, DatabaseObjectSelection.Kind.METRIC) {
            it.metricDetailSourceReader.observe(selection).first()
        } }.exceptionOrNull())
        invoke(click)
        assertFalse(model.uiState.value.metricSaved)
        database.withTransaction { assertEquals(1, database.metricLogDao().getAllLogsForMetric(metric.id).size) }
    }
    @Test(timeout = 150_000) fun wrongOwnerEpochAndRetiredHostNeverRetagOriginalOpaqueIntent() = runBlocking<Unit> {
        val metric = seed(); val source = show(metric); val click = openConfirm()
        epochs.sessions.exclusive { epochs.tokens.saveServerIdentity("server-a", "epoch-b") }
        assertFalse(source.publication.isCurrentInMemory())
        assertNotNull(runCatching { epochs.host.withSelection(source, DatabaseObjectSelection.Kind.METRIC) {
            it.metricDetailSourceReader.observe(source).first()
        } }.exceptionOrNull())
        invoke(click)
        epochs.current { assertEquals(1, it.metricLogDao.getAllLogsForMetric(metric.id).size) }
        preferences.edit { it[stringPreferencesKey("sync_account_id")] = "different-owner" }
        assertNull(epochs.tokens.localOpaqueMetricAccess())
        assertNotNull(runCatching { epochs.current { it.metricOverviewProvider.observe().first() } }.exceptionOrNull())
        // Restore the actual account metadata through its original API, then retire physically.
        epochs.sessions.exclusive { epochs.tokens.prepareSyncAccount("account-a") }
        epochs.clearAndAwaitReplacement()
        assertNotNull(runCatching { epochs.host.withSelection(source, DatabaseObjectSelection.Kind.METRIC) {
            it.metricDetailSourceReader.observe(source).first()
        } }.exceptionOrNull())
        invoke(click)
        epochs.current { assertTrue(it.metricLogDao.getAllLogsForMetric(metric.id).isEmpty()) }
    }
}
