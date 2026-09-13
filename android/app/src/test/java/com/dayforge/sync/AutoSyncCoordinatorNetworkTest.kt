package com.dayforge.sync

import android.content.Context
import android.net.Network
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.dayforge.data.api.NetworkMonitor
import com.dayforge.data.local.dao.SyncOutboxDao
import com.dayforge.data.local.dao.TimeLogDao
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoSyncCoordinatorNetworkTest {
    private val context = mockk<Context>()
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val monitor = mockk<NetworkMonitor>()
    private val state = MutableStateFlow(NetworkMonitor.Snapshot())
    private val outbox = mockk<SyncOutboxDao>()
    private val timers = mockk<TimeLogDao>()

    @Before fun setup() {
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(context) } returns workManager
        every { monitor.state } returns state
        every { outbox.observePendingCount() } returns emptyFlow()
        every { timers.observePendingTimerCommandCount() } returns emptyFlow()
    }

    @After fun teardown() { unmockkStatic(WorkManager::class) }

    private fun connected(revision: Long) = NetworkMonitor.Snapshot(
        listOf(NetworkMonitor.Path(mockk<Network>(), true, false)), revision = revision
    )

    private fun enqueues(count: Int) {
        verify(exactly = count) {
            workManager.enqueueUniqueWork("dayforge-auto-sync", ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
    }

    @Test fun `network bursts debounce and reconnect schedules exactly one attempt`() = runTest {
        val coordinator = AutoSyncCoordinator(context, outbox, timers, monitor, backgroundScope)
        coordinator.start()
        coordinator.start()
        runCurrent()
        enqueues(1)
        state.value = connected(1)
        runCurrent()
        advanceTimeBy(1_500)
        state.value = connected(2)
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        enqueues(1)
        advanceTimeBy(500)
        runCurrent()
        enqueues(2)
        state.value = NetworkMonitor.Snapshot(revision = 3)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        enqueues(2)
        state.value = connected(4)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        enqueues(3)
    }

    @Test fun `last network loss cancels pending wakeup while another path keeps retry enabled`() = runTest {
        AutoSyncCoordinator(context, outbox, timers, monitor, backgroundScope).start()
        runCurrent()
        state.value = connected(1)
        runCurrent()
        advanceTimeBy(1_000)
        state.value = NetworkMonitor.Snapshot(revision = 2)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        enqueues(1)
        state.value = connected(3)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        enqueues(2)
    }
}
