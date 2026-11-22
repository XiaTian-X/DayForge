package com.dayforge.data.repository

import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.NextSyncPushResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** G0 bounded window over the sealed count owner. Hints never authorize a send. Full or
 * cancelled admission leaves durable outbox intact. Any send/ACK exception stops this
 * window; unknown results are NOT replayed automatically. At most 128 IDs per window. */
internal class G0ShortCountSyncWorker(private val store:G0ShortCountStore,private val http:NextSyncHttp,
    owner:CoroutineScope,private val capacity:Int=8) {
    class Attempt(val requestId:String,val enqueued:Long) {
        val send=G0PhaseTrace(driverTiming=false);val ack=G0PhaseTrace(driverTiming=false)
        @Volatile var sendStarted=0L;@Volatile var sendEnded=0L
        @Volatile var ackStarted=0L;@Volatile var ackEnded=0L
        @Volatile var delivery:NextCoreDelivery<NextSyncPushResponse>?=null
        @Volatile var accepted:NextOperationAcceptance?=null
    }
    data class Depth(val at:Long,val outstanding:Int)
    private val monitor=Any();private val queue=Channel<Attempt>(capacity)
    private val seen=linkedSetOf<String>();private var outstanding=0;private var closed=false
    val attempts=CopyOnWriteArrayList<Attempt>();val depths=CopyOnWriteArrayList<Depth>()
    @Volatile var failure:Exception?=null;private set
    internal var beforeSendForTest:suspend (Attempt)->Unit={}
    internal var beforeAcceptForTest:suspend (Attempt)->Unit={}
    private fun now()=android.os.SystemClock.elapsedRealtimeNanos()
    init {require(capacity in 1..8)}
    private val job=owner.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
        try {
            for(attempt in queue) {
                currentCoroutineContext().ensureActive();attempts+=attempt;beforeSendForTest(attempt)
                attempt.sendStarted=now()
                try {attempt.delivery=attempt.send.run {requireNotNull(store.send(http,attempt.requestId))}}
                finally {attempt.sendEnded=now()}
                currentCoroutineContext().ensureActive();beforeAcceptForTest(attempt)
                attempt.ackStarted=now()
                try {attempt.accepted=attempt.ack.run {store.acknowledge(requireNotNull(attempt.delivery))}}
                finally {attempt.ackEnded=now()}
                synchronized(monitor) {outstanding--;depths+=Depth(now(),outstanding)}
            }
        } catch(error:Exception) {failure=error;if(error is CancellationException)throw error}
        finally {synchronized(monitor) {closed=true;queue.close()}}
    }
    fun offer(requestId:String):Boolean=synchronized(monitor) {
        if(closed || failure!=null || job.isCancelled || job.isCompleted)return false
        if(requestId in seen)return true
        if(outstanding>=capacity || seen.size>=128)return false
        if(!queue.trySend(Attempt(requestId,now())).isSuccess)return false
        seen+=requestId;outstanding++;depths+=Depth(now(),outstanding);true
    }
    fun start() {job.start()}
    suspend fun finish() {
        synchronized(monitor) {closed=true;queue.close()};job.start();job.join()
        failure?.let {throw it};if(job.isCancelled)throw CancellationException("G0_COUNT_SYNC_CANCELLED")
        check(synchronized(monitor) {outstanding==0}) {"G0_COUNT_SYNC_NOT_DRAINED"}
    }
    fun cancel() {synchronized(monitor) {closed=true;queue.close()};job.cancel()}
    suspend fun shutdown() {cancel();job.join()}
}
