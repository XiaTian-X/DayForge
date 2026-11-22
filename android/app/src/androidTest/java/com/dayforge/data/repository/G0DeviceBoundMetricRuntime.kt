package com.dayforge.data.repository

import com.dayforge.data.local.LocalSyncAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One authenticated dataset/metric owner; sharing proof state never shares read authorization.
 * Cold work exists only for current callers. A partial/undelivered last cold result is not reusable.
 */
internal class G0DeviceBoundMetricRuntime(
    access:LocalSyncAccess,
    private val owner:CoroutineScope,
    private val initialize:suspend ()->G0ShortMetricStore
) {
    private val binding=access.copy(capabilities=access.capabilities.toSet())
    private val mutex=Mutex()
    private var ready:G0ShortMetricStore?=null
    private var cold:Deferred<G0ShortMetricStore>?=null
    private var waiters=0
    private var failed=false
    suspend fun acquire(access:LocalSyncAccess):G0ShortMetricStore {
        check(access==binding) { "G0_RUNTIME_BINDING_MISMATCH" }
        owner.coroutineContext.ensureActive()
        val pending=mutex.withLock {
            check(!failed) { "G0_RUNTIME_RECOVERY_REQUIRED" }
            ready?.let { return it }
            val task=cold ?: owner.async(Dispatchers.IO,start=CoroutineStart.LAZY) { initialize() }.also { cold=it }
            waiters++;task
        }
        pending.start()
        try {
            val result=pending.await()
            owner.coroutineContext.ensureActive()
            mutex.withLock {
                check(!failed) { "G0_RUNTIME_RECOVERY_REQUIRED" }
                ready=result
            }
            return result
        } catch(failure:Throwable) {
            // A cancelled subscriber does not poison a job still needed by another subscriber.
            if(pending.isCancelled) withContext(NonCancellable) { mutex.withLock { failed=true } }
            throw failure
        } finally {
            withContext(NonCancellable) {
                val abandoned=mutex.withLock {
                    check(waiters>0);waiters--
                    if(waiters==0 && ready==null) {
                        failed=true;pending.cancel();pending
                    } else null
                }
                abandoned?.join() // Do not report cleanup while Room is still unwinding.
            }
        }
    }
    suspend fun waitersForTest():Int=mutex.withLock { waiters }
}
