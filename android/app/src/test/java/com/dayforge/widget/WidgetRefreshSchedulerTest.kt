package com.dayforge.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class WidgetRefreshSchedulerTest {
    private val manager = mockk<WorkManager>(relaxed = true)
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(context) } returns manager
    }

    @After fun teardown() { unmockkStatic(WorkManager::class) }

    @Test fun `bursts append independent payload-free refresh requests without cancellation`() {
        val requests = mutableListOf<OneTimeWorkRequest>()
        every { manager.enqueueUniqueWork(WidgetRefreshScheduler.WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE, capture(requests)) } returns mockk(relaxed = true)
        repeat(3) { assertNotNull(WidgetRefreshScheduler.request(context)) }
        assertEquals(3, requests.map { it.id }.distinct().size)
        requests.forEach {
            assertEquals(WidgetRefreshWorker::class.java.name, it.workSpec.workerClassName)
            assertEquals(Data.EMPTY, it.workSpec.input)
            assertEquals(NetworkType.NOT_REQUIRED, it.workSpec.constraints.requiredNetworkType)
            assertEquals(BackoffPolicy.EXPONENTIAL, it.workSpec.backoffPolicy)
            assertEquals(10_000, it.workSpec.backoffDelayDuration)
        }
        verify(exactly = 0) { manager.cancelUniqueWork(any()) }
    }

    @Test fun `enqueue failure does not throw after a business write`() {
        every { WorkManager.getInstance(context) } throws IllegalStateException("unavailable")
        assertNull(WidgetRefreshScheduler.request(context))
    }
}
