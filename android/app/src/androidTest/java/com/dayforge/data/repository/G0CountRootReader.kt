package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** B3a authenticates and reads a Q snapshot only. It has no native-P reader, writer, command,
 * recovery or conversion-to-Store API. A valid H/marker does not prove current unvisited P.
 * Normal cold open never calls full business audit, Image decoding or a whole node-map load.
 * The retained SQLite integrity_check is an explicit full physical check, not O(log N) I/O.
 */
internal class G0CountRootReader(private val db:HabitDatabase,private val tokens:TokenManager,
    private val sessions:AccountSessionCoordinator,private val authority:G0AuthorityOwner,
    private val gate:G0DomainSqlGate,private val owner:G0RoomColdOwnerFactory,
    private val seal:G0CountRootSeal) {
    private lateinit var core:LocalCoreWriteAccess
    private lateinit var registered:LocalSyncAccess
    private lateinit var packet:G0CountRootSeal.Packet
    private var epoch=-1L
    private var ready=false
    @Volatile private var retired=false
    var beforeColdFinalForTest:suspend ()->Unit={}
    var afterColdEndForTest:suspend ()->Unit={}
    var beforePublishForTest:suspend ()->Unit={}
    var lastSourceStats:G0CountBlockNodeSource.Stats?=null;private set
    class View internal constructor(val day:G0CountBusinessBlocks.DayRead,val rootDigest:String,
        private val stillCurrent:()->Boolean) {
        fun valid():Boolean=runCatching(stillCurrent).getOrDefault(false)
    }
    init {require(gate.countRootScope && gate.countBlockScope)}

    private fun current() {
        check(ready && !retired) {"G0_ROOT_READ_RECOVERY_REQUIRED"}
        gate.requireSealedConnection();check(epoch==gate.revision) {"G0_ROOT_READ_VERSION_CHANGED"}
    }
    private suspend fun fresh(lease:G0AuthorityOwner.Lease) {
        authority.checkLease(lease)
        check(tokens.isCurrentDisplayReadAuthority(core,registered)) {"G0_ROOT_READ_AUTH_CHANGED"}
        authority.checkLease(lease)
    }
    private fun controls() {
        val sql=db.openHelper.writableDatabase
        NextRequestSql.requireOutboxEnabled(sql)
        // The original plain two-count profile admits no replica-fence record. This reads
        // only existence; it does not decode arbitrary recovery payloads from a cold file.
        sql.query(G0CountRootSql.readFencePresence).use {check(!it.moveToFirst()) {"G0_ROOT_REPLICA_FENCED"}}
    }
    private fun capturedHeader():G0CountRootSeal.Packet {
        val sql=db.openHelper.writableDatabase
        controls()
        val marker=sql.query(G0CountRootSql.readMarker).use {checkNotNull(G0CountRootSql.captureMarker(it))}
        check(!marker.dirty) {"G0_ROOT_DIRTY"}
        val found=sql.query(G0CountRootSql.readSeal).use {checkNotNull(G0CountRootSql.captureSeal(it))}
        val root=sql.query(G0CountRootSql.readBoundedRoot).use {checkNotNull(G0CountRootSql.captureRootRow(it))}
        check(found.generation==marker.generation && root.binding==found.binding &&
            root.root==found.root && root.revision==found.revision) {"G0_ROOT_HEADER_MISMATCH"}
        return found
    }
    private fun sameHeader(left:G0CountRootSeal.Packet,right:G0CountRootSeal.Packet):Boolean =
        left.binding==right.binding && left.root==right.root && left.revision==right.revision &&
            left.generation==right.generation && left.authenticationBytes().contentEquals(right.authenticationBytes())

    suspend fun initialize(schema:List<List<String?>>)=sessions.exclusive {
        check(!ready && !retired)
        try {
            val lease=authority.lease()
            core=checkNotNull(tokens.localCoreWriteAccess())
            registered=checkNotNull(tokens.localSyncAccess()).let {
                it.copy(capabilities=Collections.unmodifiableSet(it.capabilities.toSet()))
            }
            val binding=G0CountBlockSql.binding(core,registered)
            check("facts.append" in registered.capabilities && "sync.read" in registered.capabilities)
            authority.checkLease(lease)
            authority.finalPermit(lease).use {
                val completed=gate.ownedColdTransaction(db,schema) {
                    timed("root_integrity") {
                        db.openHelper.writableDatabase.query(G0PersistentCheckpoint.INTEGRITY_SQL).use {
                            check(it.moveToFirst() && it.getString(0)=="ok" && !it.moveToNext()) {"G0_ROOT_INTEGRITY"}
                        }
                    }
                    val candidate=timed("root_header_match") {
                        capturedHeader().also {check(seal.match(it,binding)) {"G0_ROOT_AUTHENTICATION_FAILED"}}
                    }
                    beforeColdFinalForTest();fresh(lease);currentCoroutineContext().ensureActive()
                    owner.validated(candidate)
                    candidate
                }
                completed.end.requireCandidate(completed.value)
                afterColdEndForTest();authority.checkLease(lease);currentCoroutineContext().ensureActive()
                gate.sealCold(completed.end)
                packet=completed.value;epoch=gate.revision;ready=true
            }
        } catch(failure:Throwable) {
            retired=true;gate.invalidateConnection();owner.fail(failure)
        }
    }

    suspend fun readDay(habitUuid:String,date:LocalDate,publish:(View)->Unit) {
        val lease=authority.lease()
        sessions.exclusive {
            try {
                current();fresh(lease)
                val today=LocalDate.now();val zone=ZoneId.systemDefault().id
                fun unchanged() {
                    current();authority.checkLease(lease)
                    check(LocalDate.now()==today && ZoneId.systemDefault().id==zone) {"G0_ROOT_READ_DATE_CHANGED"}
                }
                val captured=gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                    unchanged();check(sameHeader(packet,capturedHeader())) {"G0_ROOT_READ_HEADER_CHANGED"}
                    val caller=currentCoroutineContext()
                    G0CountBlockNodeSource.withSource(db.openHelper.writableDatabase,
                        gate.captureBlockReadBoundary(),checkCancelled=caller::ensureActive) { source ->
                        G0CountIncrementalBlocks.readDayFromSource(packet.binding,packet.root,source,habitUuid,date,today)
                    }
                }
                // A hook/network/authority change between preparation and publication is real:
                // reacquire neither a replacement lease nor a replacement root for this result.
                beforePublishForTest();unchanged()
                authority.finalPermit(lease).use {
                    gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                        unchanged();check(sameHeader(packet,capturedHeader()));fresh(lease)
                    }
                    unchanged();currentCoroutineContext().ensureActive()
                    lastSourceStats=captured.stats
                    publish(View(captured.value,packet.root.digest) {unchanged();true})
                }
            } catch(failure:Throwable) {
                retired=true;gate.invalidateConnection();throw failure
            }
        }
    }

    private suspend fun <T> timed(name:String,body:suspend ()->T):T {
        val trace=G0PhaseTrace.current.get()
        return if(trace==null)body() else trace.stage(name,body)
    }
}
