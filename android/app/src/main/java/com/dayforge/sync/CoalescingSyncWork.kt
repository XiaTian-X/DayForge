package com.dayforge.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep the running owner and at most one queued successor. A wake during execution must
 * not be lost (KEEP alone would lose it), nor cancel the owner or grow an unbounded chain.
 * No authority is cached here; each worker captures/rechecks its current account itself.
 */
internal class CoalescingSyncWork(private val manager: () -> WorkManager, private val name: String) {
    private val scheduling = Mutex()

    suspend fun enqueue(request: OneTimeWorkRequest) = scheduling.withLock {
        val work = manager()
        val existing = work.getWorkInfosForUniqueWork(name).completed()
        if (existing.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED })
            return@withLock
        val policy = if (existing.any { it.state == WorkInfo.State.RUNNING }) ExistingWorkPolicy.APPEND_OR_REPLACE
            else ExistingWorkPolicy.REPLACE // Only terminal/absent work: do not retain an ever-growing finished chain.
        work.enqueueUniqueWork(name, policy, request).result.completed()
        Unit
    }
}

private suspend fun <T> ListenableFuture<T>.completed(): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try { continuation.resume(get()) }
        catch (error: Exception) { continuation.resumeWithException(error) }
    }, { command -> command.run() })
    // Cancellation of a caller is not permission to cancel already enqueued durable work.
}
