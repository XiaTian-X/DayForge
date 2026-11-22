package com.dayforge.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.dayforge.sync.readyWorkFuture
import com.dayforge.widget.checkin.CheckInWidgetReceiver
import com.dayforge.widget.counting.CountingWidgetReceiver
import com.dayforge.widget.focus.FocusWidgetReceiver
import com.dayforge.widget.motivation.MotivationWidgetReceiver
import com.dayforge.widget.progress.ProgressWidgetReceiver
import com.dayforge.widget.timer.TimerWidgetReceiver
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.*
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual receiver and WidgetRefreshScheduler bodies. Controlled WorkManager Operation
 * acknowledges enqueue only; this class never claims a successor worker ran or completed. */
@RunWith(AndroidJUnit4::class)
class WidgetLastConsumerQueueTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = mockk<WorkManager>(relaxed = true)
    private lateinit var context: Context
    private val requests = mutableListOf<OneTimeWorkRequest>()
    @Before fun setup() {
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? {
                check(name != ALARM_SERVICE) { "Broadcast must enqueue, never touch alarms directly" }
                return super.getSystemService(name)
            }
        }
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns manager
        every { manager.enqueueUniqueWork(WidgetRefreshScheduler.WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE, capture(requests)) } answers {
            mockk<Operation> { every { result } returns readyWorkFuture(Operation.SUCCESS) }
        }
    }
    @After fun cleanup() { unmockkStatic(WorkManager::class) }
    private fun receivers() = listOf<GlanceAppWidgetReceiver>(CheckInWidgetReceiver(), CountingWidgetReceiver(),
        TimerWidgetReceiver(), ProgressWidgetReceiver(), MotivationWidgetReceiver(), FocusWidgetReceiver()).map { spyk(it) }

    @OptIn(ExperimentalCoroutinesApi::class, androidx.glance.ExperimentalGlanceApi::class)
    @Test(timeout = 150_000) fun everyFinalDeletionUsesTheSameDurableIndependentPayloadFreeQueue() = runTest {
        val owner = Job(); val dispatcher = StandardTestDispatcher(testScheduler); val receivers = receivers()
        receivers.forEach { receiver ->
            every { receiver.coroutineContext } returns (owner + dispatcher)
            every { receiver.goAsync() } returns mockk<BroadcastReceiver.PendingResult>(relaxed = true)
        }
        try {
            receivers.forEach { it.onDisabled(context) }
            assertEquals(6, requests.size); assertEquals(6, requests.map { it.id }.distinct().size)
            requests.forEach {
                assertEquals(WidgetRefreshWorker::class.java.name, it.workSpec.workerClassName)
                assertEquals(Data.EMPTY, it.workSpec.input)
                assertEquals(NetworkType.NOT_REQUIRED, it.workSpec.constraints.requiredNetworkType)
                assertEquals(BackoffPolicy.EXPONENTIAL, it.workSpec.backoffPolicy)
                assertEquals(10_000, it.workSpec.backoffDelayDuration)
            }
            verify(exactly = 6) { manager.enqueueUniqueWork(WidgetRefreshScheduler.WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE, any<OneTimeWorkRequest>()) }
            verify(exactly = 0) { manager.cancelUniqueWork(any()) }
        } finally { owner.cancel(); runCurrent(); owner.join() }
    }
    @OptIn(ExperimentalCoroutinesApi::class, androidx.glance.ExperimentalGlanceApi::class)
    @Test(timeout = 150_000) fun lastDeletionKeepsTheOriginalBestEffortEnqueueFailureAndCancellationContract() = runTest {
        val owner = Job(); val dispatcher = StandardTestDispatcher(testScheduler); val receiver = spyk(FocusWidgetReceiver())
        every { receiver.coroutineContext } returns (owner + dispatcher)
        every { receiver.goAsync() } returns mockk<BroadcastReceiver.PendingResult>(relaxed = true)
        try {
            every { WorkManager.getInstance(any<Context>()) } throws IllegalStateException("unavailable")
            receiver.onDisabled(context) // Original request() logs and returns null; broadcast has no success API.
            assertTrue(requests.isEmpty())
            every { WorkManager.getInstance(any<Context>()) } returns manager
            val future = mockk<ListenableFuture<Operation.State.SUCCESS>>()
            every { future.addListener(any(), any()) } answers { secondArg<java.util.concurrent.Executor>().execute(firstArg()) }
            every { future.get() } throws ExecutionException(IOException("enqueue failed"))
            every { manager.enqueueUniqueWork(WidgetRefreshScheduler.WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE, capture(requests)) } returns mockk<Operation> { every { result } returns future }
            receiver.onDisabled(context); verify(exactly = 1) { future.get() }
            every { future.get() } throws CancellationException("enqueue cancelled")
            receiver.onDisabled(context); verify(exactly = 2) { future.get() }
            assertEquals(2, requests.size); verify(exactly = 0) { manager.cancelUniqueWork(any()) }
        } finally { owner.cancel(); runCurrent(); owner.join() }
    }
}
