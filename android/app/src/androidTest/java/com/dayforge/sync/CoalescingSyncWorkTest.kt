package com.dayforge.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.*
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

internal fun <T> readyWorkFuture(value: T): ListenableFuture<T> = object : ListenableFuture<T> {
    override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)
    override fun get(): T = value
    override fun get(timeout: Long, unit: TimeUnit): T = value
    override fun isDone() = true
    override fun isCancelled() = false
    override fun cancel(mayInterruptIfRunning: Boolean) = false
}

internal fun stubSyncWorkQueries(manager: WorkManager) {
    every { manager.getWorkInfosForUniqueWork(any()) } returns readyWorkFuture(emptyList())
    every { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns
        mockk<Operation> { every { result } returns readyWorkFuture(Operation.SUCCESS) }
}

@RunWith(AndroidJUnit4::class)
class CoalescingSyncWorkTest {
    @Test fun runningOwnerKeepsOneSuccessorAndFurtherWakeupsDoNotCancelOrAccumulate() = runTest {
        val manager = mockk<WorkManager>()
        val states = mutableListOf(WorkInfo.State.RUNNING)
        var appended = 0
        every { manager.getWorkInfosForUniqueWork("sync") } answers {
            readyWorkFuture(states.map { state -> mockk<WorkInfo> { every { this@mockk.state } returns state } })
        }
        every { manager.enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE, any<OneTimeWorkRequest>()) } answers {
            appended++; states.add(WorkInfo.State.BLOCKED)
            mockk<Operation> { every { result } returns readyWorkFuture(Operation.SUCCESS) }
        }
        val scheduler = CoalescingSyncWork({ manager }, "sync")
        repeat(20) { scheduler.enqueue(OneTimeWorkRequestBuilder<AutoSyncWorker>().build()) }
        assertEquals(1, appended)
        assertEquals(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED), states)
        states.clear(); states.add(WorkInfo.State.RUNNING) // Successor now owns the work.
        scheduler.enqueue(OneTimeWorkRequestBuilder<AutoSyncWorker>().build())
        assertEquals(2, appended) // New durable changes during the successor get a follow-up too.
        verify(exactly = 0) { manager.cancelUniqueWork(any()) }
    }

    @Test fun pendingRetryIsNotReplacedAndTerminalWorkCanBeScheduledAgain() = runTest {
        val manager = mockk<WorkManager>()
        stubSyncWorkQueries(manager)
        var state = WorkInfo.State.ENQUEUED
        every { manager.getWorkInfosForUniqueWork("sync") } answers {
            readyWorkFuture(listOf(mockk<WorkInfo> { every { this@mockk.state } returns state }))
        }
        val scheduler = CoalescingSyncWork({ manager }, "sync")
        scheduler.enqueue(OneTimeWorkRequestBuilder<AutoSyncWorker>().build())
        verify(exactly = 0) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
        state = WorkInfo.State.FAILED
        scheduler.enqueue(OneTimeWorkRequestBuilder<AutoSyncWorker>().build())
        verify(exactly = 1) { manager.enqueueUniqueWork("sync", ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>()) }
    }
}
