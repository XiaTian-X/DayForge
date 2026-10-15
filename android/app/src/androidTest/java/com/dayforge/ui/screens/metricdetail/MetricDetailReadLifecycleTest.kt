package com.dayforge.ui.screens.metricdetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.dao.*
import com.dayforge.data.local.entity.*
import com.dayforge.data.repository.MetricRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MetricDetailReadLifecycleTest {
    @Test fun aggregationSaveRejectsDuplicateInFlightRequestsAndCanRetryAfterFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = mockk<Context>(relaxed = true)
        val metrics = mockk<MetricDao>()
        val logs = mockk<MetricLogDao>()
        val links = mockk<HabitMetricLinkDao>()
        val repository = mockk<MetricRepository>()
        val original = MetricEntity(id = 7, name = "Metric", unit = "kg", iconResId = 1, colorHex = "#123456")
        val metric = MutableStateFlow<MetricEntity?>(original)
        every { metrics.observeMetricById(7) } returns metric
        every { logs.getLogsByMetric(7) } returns MutableStateFlow(emptyList<MetricLogEntity>())
        every { links.getLinksByMetric(7) } returns MutableStateFlow(emptyList<HabitMetricLinkEntity>())
        coEvery { logs.getLatestLog(7) } returns null
        val release = CompletableDeferred<Unit>()
        coEvery { repository.updateMetric(any(), null) } coAnswers {
            release.await()
            throw IllegalStateException("SAVE_FAILED")
        }
        val model = MetricDetailViewModel(context, metrics, logs, links, mockk(), repository,
            SavedStateHandle(mapOf("metricId" to 7L)))
        try {
            runCurrent()
            repeat(20) { model.updateAggregationType("sum") }
            runCurrent()
            coVerify(exactly = 1) { repository.updateMetric(any(), null) }
            assertEquals("average", model.uiState.value.metric?.aggregationType)
            release.complete(Unit); runCurrent()
            assertNotNull(model.uiState.value.errorMessage)
            coEvery { repository.updateMetric(any(), null) } coAnswers { metric.value = firstArg(); Unit }
            model.updateAggregationType("sum"); runCurrent()
            assertEquals("sum", model.uiState.value.metric?.aggregationType)
            coVerify(exactly = 2) { repository.updateMetric(any(), null) }
        } finally {
            model.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun hidingStopsAllReadersButDoesNotCancelAnAcceptedWrite() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = mockk<Context>(relaxed = true)
        val metrics = mockk<MetricDao>()
        val logs = mockk<MetricLogDao>()
        val links = mockk<HabitMetricLinkDao>()
        val habits = mockk<HabitDao>()
        val repository = mockk<MetricRepository>()
        val metric = MutableStateFlow<MetricEntity?>(MetricEntity(id = 7, name = "Metric", unit = "kg",
            decimalPlaces = 1, iconResId = 1, colorHex = "#123456"))
        val records = MutableStateFlow(emptyList<MetricLogEntity>())
        val linked = MutableStateFlow(emptyList<HabitMetricLinkEntity>())
        every { metrics.observeMetricById(7) } returns metric
        every { logs.getLogsByMetric(7) } returns records
        every { links.getLinksByMetric(7) } returns linked
        coEvery { logs.getLatestLog(7) } returns null
        val committed = CompletableDeferred<Unit>()
        coEvery { repository.recordValue(7, 1.0, "", any(), null, null) } coAnswers { committed.await(); 1L }
        val model = MetricDetailViewModel(context, metrics, logs, links, habits, repository,
            SavedStateHandle(mapOf("metricId" to 7L)))
        try {
            runCurrent()
            assertEquals(listOf(1, 1, 1), listOf(metric.subscriptionCount.value, records.subscriptionCount.value, linked.subscriptionCount.value))
            model.toggleValueInput()
            repeat(20) { model.recordValue(1.0, "") }
            assertTrue(model.uiState.value.isRecording) // Admission precedes coroutine dispatch.
            runCurrent()
            assertTrue(model.uiState.value.isRecording)
            model.pauseReads(); runCurrent()
            assertEquals(listOf(0, 0, 0), listOf(metric.subscriptionCount.value, records.subscriptionCount.value, linked.subscriptionCount.value))
            assertEquals("Metric", model.uiState.value.metric?.name)
            metric.value = metric.value!!.copy(name = "Updated")
            committed.complete(Unit); runCurrent()
            assertFalse(model.uiState.value.isRecording)
            coVerify(exactly = 1) { repository.recordValue(7, 1.0, "", any(), null, null) }
            assertEquals("Metric", model.uiState.value.metric?.name)
            model.resumeReads(); runCurrent()
            assertEquals("Updated", model.uiState.value.metric?.name)
            assertEquals(1, metric.subscriptionCount.value)
        } finally {
            model.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
