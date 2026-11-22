package com.dayforge.data.repository

import com.dayforge.data.api.dto.NextSyncPushResponse
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Bounded G0 burst adapter over ORIGINAL unsealed fact send/ACK. No write retry, global
 * outbox scan, new credentials, or authority derived from the UI's committed-fact hint. */
internal class G0CountSyncWorker(private val db:HabitDatabase,private val tokens:TokenManager,
    private val sessions:AccountSessionCoordinator,private val requests:NextCoreRequestStore,
    private val captured:LocalSyncAccess,owner:CoroutineScope,private val capacity:Int=8) {
    data class Fact(val id:Long,val uuid:String,val habitUuid:String)
    class Attempt(val fact:Fact,val enqueued:Long) {
        val send=G0PhaseTrace(driverTiming=false);val ack=G0PhaseTrace(driverTiming=false)
        @Volatile var sendStarted=0L;@Volatile var sendEnded=0L
        @Volatile var ackStarted=0L;@Volatile var ackEnded=0L
        @Volatile var delivery:NextCoreDelivery<NextSyncPushResponse>?=null
        @Volatile var accepted:NextOperationAcceptance?=null
    }
    private val monitor=Any()
    private val queue=Channel<Attempt>(capacity)
    private val seen=linkedSetOf<Fact>()
    private var closed=false
    val attempts=CopyOnWriteArrayList<Attempt>()
    @Volatile var failure:Exception?=null;private set
    internal var beforeAcceptForTest:suspend (Attempt)->Unit={}
    private fun now()=android.os.SystemClock.elapsedRealtimeNanos()
    init {require(capacity in 1..8)}
    private val job=owner.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
        try {
            for(attempt in queue) {
                attempts+=attempt
                attempt.sendStarted=now()
                val delivery=attempt.send.run {
                    val operation=sessions.exclusive {
                        // Bind lookup to the original account. This metadata witness is not
                        // authorization: sendOperation still performs its real fresh checks.
                        check(tokens.localSyncAccess()==captured) {"G0_SYNC_ACCOUNT_CHANGED"}
                        val fact=requireNotNull(db.completionDao().getCompletionById(attempt.fact.id)) {"G0_SYNC_SOURCE_MISSING"}
                        check(fact.uuid==attempt.fact.uuid && fact.habitUuid==attempt.fact.habitUuid && fact.oneTimeAction==null) {"G0_SYNC_SOURCE_CHANGED"}
                        val row=db.syncOutboxDao().getEntityIntents("completion",fact.uuid).single()
                        check(row.referenceUuid==fact.habitUuid && row.deadLetteredAt==null) {"G0_SYNC_SOURCE_CHANGED"}
                        row.operationId
                    }
                    requireNotNull(requests.sendOperation(captured,operation))
                }
                attempt.delivery=delivery;attempt.sendEnded=now()
                currentCoroutineContext().ensureActive();beforeAcceptForTest(attempt)
                attempt.ack.run {
                    attempt.ackStarted=now()
                    try {attempt.accepted=requests.acceptOperation(delivery)} finally {attempt.ackEnded=now()}
                }
            }
        } catch(error:Exception) {
            failure=error
            if(error is CancellationException) throw error
        } finally {synchronized(monitor) {closed=true;queue.close()}}
    }
    /** Non-suspending admission. At most capacity distinct hints over this burst's lifetime.
     * False never means data was dropped: the original durable outbox remains authoritative. */
    fun offer(fact:Fact):Boolean=synchronized(monitor) {
        if(closed || failure!=null || job.isCancelled || job.isCompleted) return false
        if(fact in seen) return true
        if(seen.size>=capacity) return false
        if(!queue.trySend(Attempt(fact,now())).isSuccess) return false
        seen+=fact;true
    }
    fun start() {job.start()}
    suspend fun finish() {
        synchronized(monitor) {closed=true;queue.close()}
        job.start();job.join();failure?.let {throw it}
        if(job.isCancelled) throw CancellationException("G0_SYNC_CANCELLED")
    }
    fun cancel() {synchronized(monitor) {closed=true;queue.close()};job.cancel()}
    suspend fun shutdown() {cancel();job.join()}
}
