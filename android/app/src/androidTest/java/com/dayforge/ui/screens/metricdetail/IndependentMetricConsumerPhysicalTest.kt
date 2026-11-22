package com.dayforge.ui.screens.metricdetail

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.entity.MetricEntity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Host/Compose source and explicit input. It does not replace HTTP/unknown-END tests. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class IndependentMetricConsumerPhysicalTest {
    private val nativeFault = com.dayforge.data.repository.IndependentMetricNativeFault()
    @get:Rule(order = 0) val epochs = PhysicalDatabaseEpochFixture().also { it.nativeFactory = nativeFault }
    @get:Rule(order = 1) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    private val app: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    private lateinit var activity: ActivityScenario<ComponentActivity>
    private lateinit var model: MetricDetailViewModel
    private var delivery: MetricValueDelivery = RepositoryMetricValueDelivery
    @Before fun start() = runBlocking<Unit> {
        hilt.inject(); epochs.connect(); epochs.login()
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
        val row = MetricEntity(name = "Independent physical", unit = "kg", decimalPlaces = 3,
            iconResId = 1, colorHex = "#123456", uuid = "af552000-0000-4000-8000-000000000001")
        val id = graph.metricRepository.createMetric(row)
        requireNotNull(graph.metricDao.getMetricById(id))
    }
    private fun show(metric: MetricEntity) {
        val selection = runBlocking { epochs.metricSelection(metric.id) }
        compose.runOnIdle {
            model = MetricDetailViewModel(app, null, null, null, null, null,
                SavedStateHandle(mapOf("metricId" to metric.id)), epochs.host, delivery)
            models.put("metric", model)
            content.value = { MetricDetailScreen(metric.id, {}, viewModel = model, sourceSelection = selection) }
        }
        compose.waitUntil(45_000) { !model.uiState.value.isLoading && !model.uiState.value.sourceExpired && model.uiState.value.metric?.uuid == metric.uuid }
    }
    @Test(timeout = 150_000) fun actualConfirmPublishesSavedAndCompletesOnlyItsOwnDelivery() = runBlocking<Unit> {
        val metric = seed(); show(metric)
        compose.onNodeWithText(app.getString(R.string.metric_record_value)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.metric_input_enter_value)).performTextInput("12.5")
        compose.onNodeWithText(app.getString(R.string.metric_input_note_optional)).performTextInput("actual independent")
        compose.onNode(hasText(app.getString(R.string.metric_record_value)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(45_000) { model.uiState.value.let { it.metricSaved && !it.isRecording && !it.metricSavePendingDelivery && it.totalLogCount == 1L && it.trendLogs?.size == 1 } }
        compose.onNodeWithText(app.getString(R.string.independent_metric_saved)).assertExists()
        val frame = model.uiState.value
        assertFalse(frame.metricSaveUnknown); assertNull(frame.metricSaveReference)
        epochs.current { graph ->
            val saved = graph.metricLogDao.getAllLogsForMetric(metric.id).single()
            assertEquals(12.5, saved.value, 0.0); assertEquals("actual independent", saved.note)
            assertEquals(1, graph.database.syncOutboxDao().getAll().count { it.recordType == "metric_log" && it.entityUuid == saved.uuid })
            assertEquals(listOf(saved), frame.trendLogs)
            assertEquals(1L, frame.totalLogCount)
        }
    }
    @Test(timeout = 150_000) fun firstHistoryPageCannotReplaceCompleteOriginalChartInputOrItsOrder() = runBlocking<Unit> {
        val metric = seed()
        val now = System.currentTimeMillis()
        epochs.current { graph ->
            // Calibrate the ORIGINAL legal legacy repository; no fake typed ticket or source.
            val values = listOf(1.0e16, -1.0e16, 1.0) + List(12) { it.toDouble() }
            values.forEachIndexed { n, value -> graph.metricRepository.recordValue(metric.id, value, "original-$n", now + n * 1_000L,
                expectedMetricUuid = metric.uuid) }
            graph.metricRepository.recordValue(metric.id, 99.0, "retained older", now - 60L * 86_400_000L,
                expectedMetricUuid = metric.uuid)
        }
        show(metric)
        val original = epochs.current { it.metricLogDao.getAllLogsForMetric(metric.id) }
        val frame = model.uiState.value
        assertEquals(16L, frame.totalLogCount)
        assertEquals(original.take(10), frame.logs)
        assertEquals("Full DESC input, including future/old rows, remains the old chart's input", original, frame.trendLogs)
        assertTrue(requireNotNull(frame.trendLogs).any { it.date > now })
        assertEquals(original.first().date, frame.latestLogDate)
        assertEquals(original.first().value, frame.latestValue)
        assertFalse(frame.metricSaved)
    }
    private class LateDelivery(private val failA: Boolean) : MetricValueDelivery {
        val aOutside = CompletableDeferred<Unit>()
        val bOutside = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        lateinit var aJob: Job
        lateinit var bJob: Job
        lateinit var aSaved: com.dayforge.data.repository.IndependentMetricSaved
        lateinit var bSaved: com.dayforge.data.repository.IndependentMetricSaved
        private var calls = 0
        override suspend fun deliver(host: com.dayforge.data.local.DatabaseRuntimeHost,
            selection: com.dayforge.data.local.DatabaseObjectSelection,
            saved: com.dayforge.data.repository.IndependentMetricSaved, isConsumerCurrent: () -> Boolean) {
            when (++calls) {
                1 -> {
                    aJob = requireNotNull(currentCoroutineContext()[Job]); aSaved = saved
                    RepositoryMetricValueDelivery.deliver(host, selection, saved, isConsumerCurrent)
                    // The original delegate has returned: account and Host leases are gone.
                    // Delay only its notification to the real VM, not SQLite END.
                    aOutside.complete(Unit); releaseA.await()
                    if (failA) error("FIXED_LATE_A_DELIVERY_NOTIFICATION_LOST")
                }
                2 -> {
                    bJob = requireNotNull(currentCoroutineContext()[Job]); bSaved = saved
                    bOutside.complete(Unit); releaseB.await()
                    RepositoryMetricValueDelivery.deliver(host, selection, saved, isConsumerCurrent)
                }
                else -> error("UNEXPECTED_AUTOMATIC_DELIVERY_REPLAY")
            }
        }
    }
    private fun confirm(value: String) {
        compose.onNodeWithText(app.getString(R.string.metric_record_value)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.metric_input_enter_value)).performTextInput(value)
        compose.onNode(hasText(app.getString(R.string.metric_record_value)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
    }
    private suspend fun lateT2DoesNotOverwriteB(failA: Boolean) {
        val held = LateDelivery(failA); delivery = held
        val metric = seed(); show(metric); confirm("1.5")
        try {
            withTimeout(45_000) { held.aOutside.await() }
            // These real operations can only finish after A released both original scopes.
            withTimeout(5_000) { epochs.sessions.exclusive { }; epochs.current { } }
            val a = requireNotNull(held.aSaved.reference)
            epochs.current { graph ->
                assertEquals(com.dayforge.data.repository.CountCommandRecords.Delivery.DELIVERED,
                    com.dayforge.data.repository.CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
                        .resolve(a.native()).historical.status.delivery)
            }
            compose.waitUntil(45_000) { model.uiState.value.metricSaved && !model.uiState.value.isRecording }
            confirm("2.5")
            withTimeout(45_000) { held.bOutside.await() }
            val b = requireNotNull(held.bSaved.reference)
            val visibleB = model.uiState.value
            assertNotEquals(a, b); assertEquals(b, visibleB.metricSaveReference)
            assertEquals(2.5, visibleB.metricSavedValue); assertTrue(visibleB.metricSavePendingDelivery)
            compose.onNodeWithText(app.getString(R.string.independent_metric_saved_value, "2.5", "kg")).assertExists()
            held.releaseA.complete(Unit)
            withTimeout(5_000) { held.aJob.join() }
            compose.waitForIdle()
            val afterA = model.uiState.value
            assertEquals(b, afterA.metricSaveReference); assertEquals(2.5, afterA.metricSavedValue)
            assertTrue(afterA.metricSavePendingDelivery); assertFalse(afterA.metricSaveUnknown)
            epochs.current { graph ->
                assertEquals(2, graph.metricLogDao.getAllLogsForMetric(metric.id).size)
                assertEquals(com.dayforge.data.repository.CountCommandRecords.Delivery.RESULT_PENDING,
                    com.dayforge.data.repository.CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
                        .resolve(b.native()).historical.status.delivery)
            }
            held.releaseB.complete(Unit)
            withTimeout(45_000) { held.bJob.join() }
            compose.waitUntil(45_000) { !model.uiState.value.metricSavePendingDelivery }
            assertEquals(2.5, model.uiState.value.metricSavedValue); assertNull(model.uiState.value.metricSaveReference)
            epochs.current { graph ->
                assertEquals(com.dayforge.data.repository.CountCommandRecords.Delivery.DELIVERED,
                    com.dayforge.data.repository.CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
                        .resolve(b.native()).historical.status.delivery)
                assertEquals(2, graph.metricLogDao.getAllLogsForMetric(metric.id).size)
            }
        } finally { held.releaseA.complete(Unit); held.releaseB.complete(Unit) }
    }
    @Test(timeout = 150_000) fun delayedSuccessfulAT2AfterActualScopesCloseCannotClearSavedB() = runBlocking<Unit> {
        lateT2DoesNotOverwriteB(false)
    }
    @Test(timeout = 150_000) fun delayedFailedAT2NotificationAfterActualScopesCloseCannotReplaceSavedB() = runBlocking<Unit> {
        lateT2DoesNotOverwriteB(true)
    }

    private suspend fun typedMetric() = epochs.current { graph ->
        val row = MetricEntity(name = "Independent typed", unit = "kg", decimalPlaces = 3,
            iconResId = 1, colorHex = "#123456", uuid = "af559000-0000-4000-8000-000000000001",
            appearance = com.dayforge.domain.model.ObjectAppearance(
                com.dayforge.domain.model.IconReference.Role("metric.custom"), "#123456", "object"))
        requireNotNull(graph.metricDao.getMetricById(graph.metricRepository.createMetric(row)))
    }
    private suspend fun preparedRejected(metric: MetricEntity): com.dayforge.data.repository.CountCommandReference {
        show(metric)
        nativeFault.arm(com.dayforge.data.repository.IndependentMetricNativeFault.Mode.BEFORE_VALUE_EXECUTE)
        confirm("12.5")
        compose.waitUntil(45_000) { model.uiState.value.let { it.metricSaveUnknown && !it.isRecording } }
        assertEquals(1, nativeFault.hits); assertTrue(nativeFault.executeEntries > 0)
        val reference = requireNotNull(model.uiState.value.metricSaveReference)
        assertTrue(model.uiState.value.showValueInput)
        val before = epochs.current { graph ->
            assertTrue(graph.metricLogDao.getAllLogsForMetric(metric.id).isEmpty())
            com.dayforge.data.repository.CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
                .resolve(reference.native()).historical
        }
        assertEquals(com.dayforge.data.repository.CountCommandRecords.Execution.PREPARED, before.status.execution)
        assertNull(before.outcome)
        // Close the actual modal first. Dismiss is not cancellation, and the page action
        // must subsequently be visible and physically clickable rather than called behind it.
        compose.onNode(hasText(app.getString(R.string.action_cancel)) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(45_000) { !model.uiState.value.showValueInput }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertTrue(model.uiState.value.metricSaveUnknown)
        assertEquals(reference, model.uiState.value.metricSaveReference)
        assertFalse(model.uiState.value.metricEntryEnded); assertFalse(model.uiState.value.metricSaved)
        epochs.current { graph ->
            val after = com.dayforge.data.repository.CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
                .resolve(reference.native()).historical
            assertEquals(before.member(), after.member())
            assertEquals(com.dayforge.data.repository.CountCommandRecords.Execution.PREPARED, after.status.execution)
            assertNull(after.outcome); assertTrue(graph.metricLogDao.getAllLogsForMetric(metric.id).isEmpty())
        }
        return reference
    }
    private suspend fun clickEndAndJoin() {
        assertFalse(model.uiState.value.showValueInput)
        var before = emptySet<Job>()
        compose.runOnIdle { before = requireNotNull(model.viewModelScope.coroutineContext[Job]).children.toSet() }
        compose.onNodeWithText(app.getString(R.string.independent_metric_check_end))
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        var jobs = emptyList<Job>()
        compose.runOnIdle { jobs = requireNotNull(model.viewModelScope.coroutineContext[Job]).children.filter { it !in before }.toList() }
        withTimeout(45_000) { jobs.joinAll() }; compose.waitForIdle()
    }
    @Test(timeout = 150_000) fun explicitEndAfterLegalTypedDefinitionChangeReleasesInputWithoutRecordingAValue() = runBlocking<Unit> {
        val metric = typedMetric(); val reference = preparedRejected(metric)
        epochs.current { graph ->
            val current = graph.metricRepository.getMetricForEditing(metric.id)
            graph.metricRepository.updateMetric(requireNotNull(current.value).copy(unit = "lb"), current.authority)
        }
        compose.waitUntil(45_000) { model.uiState.value.metric?.unit == "lb" }
        compose.onNodeWithText(app.getString(R.string.independent_metric_check_end)).assertIsEnabled()
        clickEndAndJoin()
        compose.waitUntil(45_000) { model.uiState.value.let { it.metricEntryEnded && !it.isRecording && !it.metricSavePendingDelivery } }
        compose.onNodeWithText(app.getString(R.string.independent_metric_ended)).assertExists()
        assertFalse(model.uiState.value.metricSaved); assertFalse(model.uiState.value.metricSaveUnknown)
        epochs.current { graph ->
            assertTrue(graph.metricLogDao.getAllLogsForMetric(metric.id).isEmpty())
            val result = com.dayforge.data.repository.CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
                .resolve(reference.native()).historical
            assertEquals(com.dayforge.data.repository.CountCommandRecords.Execution.CANCELLED, result.status.execution)
            assertEquals(com.dayforge.data.repository.CountCommandRecords.Delivery.DELIVERED, result.status.delivery)
            assertNull(result.outcome)
        }
        confirm("3.5")
        compose.waitUntil(45_000) { model.uiState.value.metricSaved && !model.uiState.value.isRecording }
        epochs.current { graph -> assertEquals("lb", graph.metricLogDao.getAllLogsForMetric(metric.id).single().unit) }
    }
    @Test(timeout = 150_000) fun explicitEndCannotClearPreparedWhenReservedFactEvidenceIsPresent() = runBlocking<Unit> {
        val metric = typedMetric(); val reference = preparedRejected(metric)
        epochs.current { graph ->
            val command = com.dayforge.data.repository.CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
                .resolve(reference.native()).historical.intent
            val input = com.dayforge.data.repository.IndependentMetricInput.input(command)
            val fact = com.dayforge.data.repository.CountCommandRecords.uuid(input, "reserved_fact_uuid")
            // A deliberately damaged native state, not a claimed legitimate original save.
            graph.metricLogDao.insert(com.dayforge.data.local.entity.MetricLogEntity(metricId = metric.id,
                uuid = fact, date = System.currentTimeMillis(), value = 99.0, unit = metric.unit))
        }
        clickEndAndJoin()
        assertFalse(model.uiState.value.metricEntryEnded); assertTrue(model.uiState.value.metricSaveUnknown)
        epochs.current { graph ->
            val result = com.dayforge.data.repository.CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
                .resolve(reference.native()).historical
            assertEquals(com.dayforge.data.repository.CountCommandRecords.Execution.PREPARED, result.status.execution)
            assertNull(result.outcome); assertEquals(1, graph.metricLogDao.getAllLogsForMetric(metric.id).size)
        }
    }

}
