package com.dayforge.data.repository

import com.dayforge.data.local.*
import com.dayforge.data.model.HabitType
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.model.CheckHistory
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.ZonedDateTime
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** First read-only integration. Original complete validators, not a certificate for other writes.
 * Plain profile with no replica-switch history only; other scopes are explicitly rejected.
 */
internal class G0RecurringReadOwner(private val db:HabitDatabase,private val tokens:TokenManager,
    private val sessions:AccountSessionCoordinator,private val authority:G0AuthorityOwner,private val gate:G0DomainSqlGate,private val sharedScope:Boolean=false,private val reuseEvidence:Boolean=false,private val coldSeed:()->G0ColdRecurringProof.Seed?={null}) {
    data class Content(val habits:List<HabitEntity>,val counts:Map<Long,CountHistory>,val checks:Map<Long,CheckHistory>)
    class Batch internal constructor(internal val issuer:G0RecurringReadOwner,internal val content:Content,
        internal val access:LocalCoreWriteAccess,internal val registered:LocalSyncAccess?,
        internal val lease:G0AuthorityOwner.Lease,internal val revision:Long,internal val time:ZonedDateTime,internal val stamp:G0DomainSqlGate.ReadStamp)
    class Publication internal constructor(val content:Content,private val current:()->Boolean) {
        fun valid():Boolean=current()
    }
    private val closed=AtomicBoolean()
    internal var nowForTest:()->ZonedDateTime={ ZonedDateTime.now() }
    private data class Cached(val ids:List<Long>,val batch:Batch,val proof:G0ObservedReadProof)
    private val reading=Mutex()
    private val cacheGuard=Any()
    private var cached:Cached?=null
    internal var lastCaptureFailure:String?=null;private set
    internal var fullReads=0;private set
    internal var reuseHits=0;private set
    internal var coldHits=0;private set
    internal val proofQueries get()=synchronized(cacheGuard) { cached?.proof?.queryCount ?: 0 }
    internal var captureForTest:()->G0ObservedReadProof.Capture={ G0ObservedReadProof.Capture() }
    internal var beforeReuseFinalForTest:suspend ()->Unit={}
    init { require(!reuseEvidence || sharedScope) }
    fun close() { synchronized(cacheGuard) { closed.set(true);cached=null } }
    private fun current(batch:Batch):Boolean = !closed.get() && runCatching {
        authority.checkLease(batch.lease);gate.requireSealedConnection()
        val now=nowForTest()
        gate.revision==batch.revision && now.zone==batch.time.zone && now.toLocalDate()==batch.time.toLocalDate()
    }.getOrDefault(false)
    var afterReadForTest:suspend ()->Unit={}
    private val counts=CountHistoryReader(db,tokens,sessions)
    private val checks=CheckHistoryReader(db,tokens,sessions)
    private val replica=ReplicaPresentationRepository(db,tokens,sessions)
    private suspend fun requireSupportedReplica() {
        val witness=tokens.replicaPresentationWitness()
        check(!witness.pointerPresent && witness.history.isEmpty() && replica.readFenceInTransaction()==null) { "G0_RECURRING_REPLICA_SCOPE_UNSUPPORTED" }
        NextChallengeStore(db).requirePlainInTransaction()
    }
    private suspend fun content(requested:List<Long>,time:ZonedDateTime,displayScope:RecurringDisplayReadScope?=null)=
        G0RecurringContent.load(db,tokens,sessions,requested,time,displayScope)
    private fun checkAccess(core:LocalCoreWriteAccess,registered:LocalSyncAccess?) {
        check(core.capabilities==null || "sync.read" in core.capabilities)
        check(registered==null || registered.session==core.session && registered.deviceId==core.capturedDeviceId && registered.capabilities==core.capabilities)
    }
    private suspend fun sharedRead(requested:List<Long>,time:ZonedDateTime):Batch =
        sessions.exclusive(AccountSessionCoordinator.Work.DASHBOARD_READ) {
            check(!closed.get()) { "G0_RECURRING_OWNER_CLOSED" }
            val lease=authority.lease()
            authority.finalPermit(lease).use {
                // Reject unsupported replica/round profiles before the original scope attempts
                // their wider SQL. Same account lock and authority permit span both transactions.
                gate.transaction(db,G0DomainSqlGate.Phase.READ) { requireSupportedReplica() }
                val batch=gate.adoptReadTransaction(db) {
                    RecurringDisplayReadScope.withSnapshotWhileAccountLocked(db,tokens,sessions) { display ->
                        val core=display.borrow(db,tokens).first
                        val registered=tokens.localSyncAccess();checkAccess(core,registered)
                        val result=content(requested,time,display)
                        afterReadForTest()
                        check(!closed.get()) { "G0_RECURRING_OWNER_CLOSED" }
                        Batch(this@G0RecurringReadOwner,result,core,registered,lease,gate.revision,time,gate.readStamp())
                    }
                }
                // The ORIGINAL scope has just performed real final decryption. The permit
                // prevents persisted authority changes; the lease rejects even reserved changes.
                authority.checkLease(lease);check(!closed.get()) { "G0_RECURRING_OWNER_CLOSED" }
                currentCoroutineContext().ensureActive();batch
            }
        }
    suspend fun read(ids:List<Long>,time:ZonedDateTime):Batch=withContext(Dispatchers.IO) {
        val requested=ids.toList();require(requested.isNotEmpty() && requested.distinct().size==requested.size)
        reading.withLock {
            try {
                if(reuseEvidence) {
                    val existing=synchronized(cacheGuard) { cached }
                    val seed=if(existing==null) coldSeed()?.borrow(this@G0RecurringReadOwner,db,tokens,sessions,authority,gate,requested,time) else null
                    val prior=existing ?: seed?.let { Cached(requested,it.first,it.second) }
                    if(prior!=null) tryReuse(prior,requested,time)?.let { batch ->
                        synchronized(cacheGuard) { if(!closed.get()) cached=prior.copy(batch=batch) }
                        reuseHits++;if(seed!=null) coldHits++;return@withLock batch
                    }
                }
                fullReads++
                if(!reuseEvidence) return@withLock readFully(requested,time)
                val recording=captureForTest()
                val result=G0ObservedReadProof.capture(recording) { readFully(requested,time) }
                val proof=recording.finish();lastCaptureFailure=recording.reason
                synchronized(cacheGuard) { if(!closed.get()) cached=proof?.let { Cached(requested,result,it) } }
                result
            } catch(error:Throwable) { synchronized(cacheGuard) { cached=null };throw error }
        }
    }
    private suspend fun tryReuse(prior:Cached,ids:List<Long>,time:ZonedDateTime):Batch? {
        if(closed.get() || prior.ids!=ids || prior.batch.time.zone!=time.zone || prior.batch.time.toLocalDate()!=time.toLocalDate()) return null
        if(runCatching { authority.checkLease(prior.batch.lease) }.isFailure) return null
        val lease=authority.lease()
        // Fresh entry decryption before taking the account/database resources, never a cached grant.
        val core=requireNotNull(tokens.localCoreWriteAccess());val registered=tokens.localSyncAccess()
        checkAccess(core,registered)
        return sessions.exclusive(AccountSessionCoordinator.Work.DASHBOARD_READ) {
            authority.checkLease(lease);check(!closed.get()) { "G0_RECURRING_OWNER_CLOSED" }
            if(core!=prior.batch.access || registered!=prior.batch.registered) return@exclusive null
            authority.finalPermit(lease).use {
                gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                    requireSupportedReplica()
                    if(!prior.proof.unchanged(gate.sqlClient(),gate.changedTables(prior.batch.stamp))) return@transaction null
                    beforeReuseFinalForTest()
                    require(tokens.isCurrentDisplayReadAuthority(core,registered));authority.checkLease(lease)
                    check(!closed.get()) { "G0_RECURRING_OWNER_CLOSED" };currentCoroutineContext().ensureActive()
                    Batch(this@G0RecurringReadOwner,prior.batch.content,core,registered,lease,gate.revision,time,gate.readStamp())
                }
            }
        }
    }
    private suspend fun readFully(ids:List<Long>,time:ZonedDateTime):Batch=withContext(Dispatchers.IO) {
        val requested=ids.toList();require(requested.isNotEmpty() && requested.distinct().size==requested.size)
        if(sharedScope) return@withContext sharedRead(requested,time)
        sessions.exclusive(AccountSessionCoordinator.Work.DASHBOARD_READ) {
            check(!closed.get()) { "G0_RECURRING_OWNER_CLOSED" }
            val lease=authority.lease()
            val core=requireNotNull(tokens.localCoreWriteAccess())
            val registered=tokens.localSyncAccess()
            checkAccess(core,registered)
            authority.finalPermit(lease).use {
                gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                    requireSupportedReplica()
                    NextEvidenceReadSession.read(db) {
                        val result=content(requested,time)
                        afterReadForTest()
                        require(tokens.isCurrentDisplayReadAuthority(core,registered));authority.checkLease(lease)
                        check(!closed.get()) { "G0_RECURRING_OWNER_CLOSED" }
                        currentCoroutineContext().ensureActive()
                        Batch(this@G0RecurringReadOwner,result,core,registered,lease,gate.revision,time,gate.readStamp())
                    }
                }
            }
        }
    }
    /** Callback is synchronous publication only, never a SQL/write permission. UI must observe
     * authority/connection changes and test valid() again at rendering, as for metric publication.
     */
    suspend fun publish(batch:Batch,accept:(Publication)->Unit):Boolean=withContext(Dispatchers.IO) {
        check(batch.issuer===this@G0RecurringReadOwner)
        sessions.exclusive(AccountSessionCoordinator.Work.DASHBOARD_PUBLISH) {
            if(!current(batch)) return@exclusive false
            authority.finalPermit(batch.lease).use {
                gate.transaction(db,G0DomainSqlGate.Phase.READ) { requireSupportedReplica() }
                if(!tokens.isCurrentDisplayReadAuthority(batch.access,batch.registered) || !current(batch)) return@exclusive false
                currentCoroutineContext().ensureActive()
                accept(Publication(batch.content) { current(batch) });true
            }
        }
    }
}
