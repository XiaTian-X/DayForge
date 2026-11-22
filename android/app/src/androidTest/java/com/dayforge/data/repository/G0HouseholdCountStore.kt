package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec

import androidx.room.withTransaction
import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.ActivityRateCalculator
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Explicit G0 household candidate, derived from the single-object short-count kernel.
 * The original G0ShortCountStore remains a separate unchanged control, and the original
 * producer, complete readers and ACK verifier remain the differential oracle. This is
 * ONE owner/index/global ID allocator over exactly two ordinary count objects, not two
 * short stores sharing a connection. No production caller uses this class.
 *
 * A complete cold snapshot validates every fact/source/day before this derived directory
 * is sealed. All warm writes revalidate its full membership/absence witness and exact
 * journal effects. Only confirmed COMMIT promotes it. First-day birth is allowed only
 * where that complete directory proves no retained start; undo, structure changes,
 * server-unbound day rules and cold outstanding transmissions remain out of scope.
 */
internal class G0HouseholdCountStore(private val db:HabitDatabase,private val tokens:TokenManager,
    private val sessions:AccountSessionCoordinator,private val authority:G0AuthorityOwner,
    private val gate:G0DomainSqlGate,private val original:NextCoreRequestStore,
    private val maxFacts:Int=128,private val jointAuthority:Boolean=true,
    private val capacityProfile:CapacityProfile=CapacityProfile.DEFAULT_128,
    private val completionPromptProfile:G0CompletionPromptProof.StaticOneLink?=null,
    private val certificate:G0CountCertificate?=null,private val certificateLookup:Boolean=true,
    private val incrementalBlockScope:Boolean=false,private val rootSeal:G0CountRootSeal?=null,
    private val rootWriteScope:Boolean=false) {
    /** Explicit experiment admission only. Both profiles retain the same complete
     * two-object/domain proof; this does not enlarge pending queues or proof batches. */
    enum class CapacityProfile(val limit:Int) {
        DEFAULT_128(128), ANNUAL_730_PLUS_6(736)
    }
    init {
        require(incrementalBlockScope==gate.countBlockScope && (!incrementalBlockScope || certificate==null))
        require((rootSeal!=null)==gate.countRootScope && (rootSeal==null || incrementalBlockScope))
        require(rootWriteScope==gate.countRootWriteScope && (!rootWriteScope || rootSeal!=null))
        require(when(capacityProfile) {
            CapacityProfile.DEFAULT_128->maxFacts in 1..128
            CapacityProfile.ANNUAL_730_PLUS_6->maxFacts==736
        }) {"G0_COUNT_CAPACITY_PROFILE_UNSUPPORTED"}
    }
    private data class ObjectState(val habit:HabitEntity,val history:CountHistory)
    private val objects=linkedMapOf<String,ObjectState>()
    private lateinit var admitted:Set<String>
    private val days=linkedMapOf<Pair<Long,String>,CountDayEntity>()
    private val daySources=linkedMapOf<Pair<Long,String>,MutableSet<String>>()
    private lateinit var scope:LocalCoreWriteAccess
    private lateinit var registered:LocalSyncAccess
    private lateinit var index:G0DomainProofIndex
    private var rootSealPacket:G0CountRootSeal.Packet?=null
    private var epoch=0L
    private var ready=false
    private var poisoned=false
    private var lastFactId=0L
    private var lastQueueId=0L
    private var totalFacts=0
    private val origins=linkedMapOf<String,NextRequestOriginEntity>()
    private val pending=linkedMapOf<String,SyncOutboxEntity>()
    private val sent=linkedMapOf<String,NextTransmissionEntity>()
    private val receipts=linkedMapOf<String,NextAcceptanceEntity>()
    private val shadows=linkedMapOf<String,SyncEntityStateEntity>()
    private val originHashes=linkedMapOf<String,String>()
    private val transmissionHashes=linkedMapOf<String,String>()
    private val operations=linkedMapOf<String,SyncV2Operation>()
    private var factRevision=0L
    private var proofRevision=0L
    private var presentationRevision=0L
    private var completionPromptProof:G0CompletionPromptProof?=null
    private data class CommandContext(val session:LocalDataSession,val members:Set<String>)
    @Volatile private var commandContext:CommandContext?=null
    var afterPrepareForTest:suspend ()->Unit={}
    var afterSendPrepareForTest:suspend ()->Unit={}
    var beforeFinalForTest:suspend ()->Unit={}
    var afterEntryAuthenticationForTest:suspend ()->Unit={}
    var beforeCompletionPromptFinalForTest:suspend ()->Unit={}
    var afterCertificatePreparedForTest:suspend ()->Unit={}
    var beforeCertificateFinalForTest:suspend ()->Unit={}
    var beforeCompletionPromptPublishForTest:suspend ()->Unit={}

    class Command internal constructor(val requestId:String,val factUuid:String,
        internal val session:LocalDataSession,val habitUuid:String)
    /** Allocates identity only, including while a legitimate commit is promoting.
     * The immutable context is published once after cold readiness. No gate epoch,
     * business proof or authentication is borrowed; increase(command) must acquire
     * the owner and freshly validate everything before preparing a write.
     */
    fun newCommand(habitUuid:String):Command {
        val context=checkNotNull(commandContext) {"G0_COUNT_RECOVERY_REQUIRED"}
        gate.requireSealedConnection()
        require(habitUuid in context.members) {"G0_HOUSEHOLD_OBJECT_NOT_ADMITTED"}
        return Command(UUID.randomUUID().toString(),UUID.randomUUID().toString(),context.session,habitUuid)
    }
    data class Saved(val factId:Long,val factUuid:String,val requestId:String,val habitUuid:String)
    data class Card(val habit:HabitEntity,val history:CountHistory,val followUp:WidgetFactSnapshot)
    class View internal constructor(val cards:Map<String,Card>,val factRevision:Long,
        val proofRevision:Long,val presentationRevision:Long,private val stillCurrent:()->Boolean) {
        fun valid()=runCatching(stillCurrent).getOrDefault(false)
    }

    /** An identity only. Constructing one outside this Store never installs it in its
     * private slot and therefore cannot authorize data supplied by a caller. */
    class CountBlocks internal constructor()
    class CountBlockView internal constructor(val day:G0CountBusinessBlocks.DayRead,
        private val stillCurrent:()->Boolean) {
        fun valid()=runCatching(stillCurrent).getOrDefault(false)
    }
    private data class CountBlockState(val handle:CountBlocks,val built:G0CountBusinessBlocks.Built,
        val lease:G0AuthorityOwner.Lease,val epoch:Long,val proofRevision:Long,
        val today:LocalDate,val zone:String)
    @Volatile private var countBlockState:CountBlockState?=null
    /** Runs after the owner's immutable capture and before block construction. It cannot
     * mutate the captured data; tests use it to retain stale-candidate counterexamples. */
    var afterCountBlockSnapshotForTest:suspend ()->Unit={}
    var beforeCountBlockPublishForTest:suspend ()->Unit={}

    private fun requireCountBlockState(handle:CountBlocks):CountBlockState {
        current()
        val state=checkNotNull(countBlockState) { "G0_COUNT_BLOCKS_UNAVAILABLE" }
        check(state.handle===handle && state.epoch==epoch && state.proofRevision==proofRevision &&
            state.today==LocalDate.now() && state.zone==ZoneId.systemDefault().id) { "G0_COUNT_BLOCKS_STALE" }
        authority.checkLease(state.lease)
        return state
    }

    /** G0 first-build adapter only: a full validated Store snapshot is encoded once.
     * No caller-owned Data enters the authority path, no business SQL is written, and
     * no failed candidate poisons the existing Store or replaces its current handle. */
    suspend fun prepareCountBlocks():CountBlocks {
        data class Capture(val snapshot:G0HouseholdCountImage.FrozenData,val contextHash:String,
            val lease:G0AuthorityOwner.Lease,val epoch:Long,val proofRevision:Long,
            val today:LocalDate,val zone:String)
        val captured=account {
            val lease=entryLeaseOwned()
            val data=imageData()
            check(data.today==LocalDate.now()) { "G0_COUNT_DATE_CHANGED" }
            val limits=G0HouseholdCountImage.Limits(maxFacts,G0FrozenDomainInputs.DEFAULT_MAX_BYTES)
            val snapshot=G0HouseholdCountImage.snapshot(data,limits)
            Capture(snapshot,certificateContext(data.objects.values.map {it.habit},data.today,data.defaultZone),
                lease,epoch,proofRevision,data.today,data.defaultZone)
        }
        afterCountBlockSnapshotForTest()
        val built=withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            G0CountBusinessBlocks.build(captured.contextHash,captured.snapshot).also {
                currentCoroutineContext().ensureActive()
            }
        }
        return account {
            fun requireCapture() {
                current();authority.checkLease(captured.lease)
                check(epoch==captured.epoch && proofRevision==captured.proofRevision &&
                    captured.today==LocalDate.now() && captured.zone==ZoneId.systemDefault().id &&
                    certificateContext(objects.values.map {it.habit},captured.today,captured.zone)==captured.contextHash) {
                    "G0_COUNT_BLOCKS_STALE"
                }
            }
            requireCapture()
            authority.finalPermit(captured.lease).use {
                authenticate();requireCapture()
                currentCoroutineContext().ensureActive()
                val handle=CountBlocks()
                countBlockState=CountBlockState(handle,built,captured.lease,captured.epoch,
                    captured.proofRevision,captured.today,captured.zone)
                handle
            }
        }
    }

    /** Reads only the selected authenticated representation. It never certifies Q and
     * then queries naked P, and it never reuses capture authentication for a later read. */
    suspend fun readCountBlockDay(handle:CountBlocks,habitUuid:String,date:LocalDate,
        publish:(CountBlockView)->Unit) {
        data class Read(val state:CountBlockState,val lease:G0AuthorityOwner.Lease)
        val read=account {
            val lease=entryLeaseOwned()
            val state=requireCountBlockState(handle)
            require(habitUuid in admitted) { "G0_HOUSEHOLD_OBJECT_NOT_ADMITTED" }
            Read(state,lease)
        }
        val day=withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            read.state.built.readDay(habitUuid,date).also {currentCoroutineContext().ensureActive()}
        }
        beforeCountBlockPublishForTest()
        account {
            check(requireCountBlockState(handle)===read.state) { "G0_COUNT_BLOCKS_STALE" }
            authority.checkLease(read.lease)
            authority.finalPermit(read.lease).use {
                // Memory epochs alone do not observe commits on another SQLite connection.
                // Validate the connection in a short read, then publish after its actual END.
                gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                    authenticate();authority.checkLease(read.lease)
                    check(requireCountBlockState(handle)===read.state) { "G0_COUNT_BLOCKS_STALE" }
                }
                authority.checkLease(read.lease)
                check(requireCountBlockState(handle)===read.state) { "G0_COUNT_BLOCKS_STALE" }
                currentCoroutineContext().ensureActive()
                val view=CountBlockView(day) {
                    authority.checkLease(read.lease)
                    requireCountBlockState(handle)===read.state
                }
                publicationEscaped=true
                publish(view)
            }
        }
    }

    private data class IncrementalState(val data:G0CountIncrementalBlocks.State,val header:List<Any?>,
        val epoch:Long,val proofRevision:Long)
    @Volatile private var incrementalState:IncrementalState?=null
    var incrementalFullBuilds=0;private set
    var incrementalImageCaptures=0;private set
    var incrementalUpdates=0;private set
    var incrementalWrittenNodes=0L;private set
    var incrementalWrittenBytes=0L;private set
    var incrementalLastUpdateStats:G0CountIncrementalBlocks.UpdateStats?=null;private set
    class IncrementalView internal constructor(val day:G0CountBusinessBlocks.DayRead,val rootDigest:String,
        private val stillCurrent:()->Boolean) {fun valid()=runCatching(stillCurrent).getOrDefault(false)}

    /** B2 first-build only. Existing disk roots are deliberately not cold credentials.
     * A new owner may run original recovery, but cannot write until a qualified block
     * state is installed. B3 must supply authenticated recovery/rebuild separately. */
    suspend fun installIncrementalCountBlocks() {
        check(incrementalBlockScope)
        data class Capture(val lease:G0AuthorityOwner.Lease,val witness:G0DomainProofIndex.Witness,
            val snapshot:G0HouseholdCountImage.FrozenData,val binding:String,val today:LocalDate,val zone:String)
        val captured=account {
            val lease=entryLeaseOwned();check(incrementalState==null)
            val data=imageData();check(data.today==LocalDate.now())
            incrementalImageCaptures++
            Capture(lease,witness(),timed("count_block_snapshot") {G0HouseholdCountImage.snapshot(data,
                G0HouseholdCountImage.Limits(maxFacts,G0FrozenDomainInputs.DEFAULT_MAX_BYTES))},
                G0CountBlockSql.binding(scope,registered),data.today,data.defaultZone)
        }
        afterCountBlockSnapshotForTest()
        incrementalFullBuilds++
        val built=timed("count_block_build") {withContext(Dispatchers.Default) {
            G0CountIncrementalBlocks.firstBuild(captured.binding,captured.snapshot,includeCurrent=rootWriteScope,
                maxFacts=maxFacts,maxPending=if(capacityProfile==CapacityProfile.ANNUAL_730_PLUS_6)6 else 128)
        }}
        commit(captured.lease,captured.witness,{},installBlocks=built) {
            check(LocalDate.now()==captured.today && ZoneId.systemDefault().id==captured.zone)
        }
    }

    private fun currentIncremental():IncrementalState {
        current()
        return checkNotNull(incrementalState) {"G0_INCREMENTAL_BLOCKS_REQUIRED"}.also {
            check(it.epoch==epoch && it.proofRevision==proofRevision &&
                it.data.binding==G0CountBlockSql.binding(scope,registered)) {"G0_INCREMENTAL_BLOCKS_STALE"}
        }
    }
    suspend fun readIncrementalCountDay(habitUuid:String,date:LocalDate,publish:(IncrementalView)->Unit) {
        data class Capture(val lease:G0AuthorityOwner.Lease,val state:IncrementalState,val today:LocalDate,val zone:String)
        val captured=account {
            val lease=entryLeaseOwned();require(habitUuid in admitted)
            Capture(lease,currentIncremental(),LocalDate.now(),ZoneId.systemDefault().id)
        }
        val day=withContext(Dispatchers.Default) {
            G0CountIncrementalBlocks.readDay(captured.state.data,habitUuid,date,captured.today)
        }
        beforeCountBlockPublishForTest()
        account {
            fun checkRead() {
                authority.checkLease(captured.lease)
                check(currentIncremental()===captured.state && LocalDate.now()==captured.today &&
                    ZoneId.systemDefault().id==captured.zone) {"G0_INCREMENTAL_BLOCKS_STALE"}
            }
            checkRead()
            authority.finalPermit(captured.lease).use {
                gate.transaction(db,G0DomainSqlGate.Phase.READ) {authenticate();checkRead()}
                checkRead();currentCoroutineContext().ensureActive()
                publicationEscaped=true
                publish(IncrementalView(day,captured.state.data.root.digest) {checkRead();true})
            }
        }
    }

    /** Q writes are in the same transaction and the same exact typed journal as P.
     * B2 retains obsolete content-addressed nodes (bounded experimental storage); no GC
     * and no cold authority are inferred from these unsigned rows. */
    private fun persistBlockUpdate(state:G0CountIncrementalBlocks.State,newBlocks:Map<String,HistoryBlockCodec.Bytes>,
        previous:IncrementalState?,effects:MutableList<G0DomainJournal.Change>):List<Any?> {
        val sql=db.openHelper.writableDatabase
        if(previous==null) {
            sql.query(G0CountBlockSql.readRoot).use {check(!it.moveToFirst()) {"G0_BLOCK_COLD_ROOT_NOT_ADMITTED"}}
            sql.query(G0CountBlockSql.countNodes).use {check(it.moveToFirst() && it.getLong(0)==0L) {"G0_BLOCK_COLD_ROOT_NOT_ADMITTED"}}
        }
        for((digest,content) in newBlocks) {
            val values=listOf(digest,content.toByteArray())
            sql.execSQL(G0CountBlockSql.insertNode,values.toTypedArray())
            effect(effects,G0CountBlockSql.nodes,null,values)
        }
        val revision=Math.addExact((previous?.header?.last() as? Long) ?: 0L,1L)
        val header=listOf<Any?>(1L,state.binding,state.root.height.toLong(),state.root.count,state.root.digest,revision)
        if(previous==null)sql.execSQL(G0CountBlockSql.insertRoot,header.toTypedArray())
        else sql.execSQL(G0CountBlockSql.updateRoot,(header.drop(1)+
            listOf(previous.header[1],previous.header[4],previous.header[5])).toTypedArray())
        effect(effects,G0CountBlockSql.root,previous?.header,header)
        return header
    }

    private fun readRootMarker():G0CountRootSql.Marker = db.openHelper.writableDatabase
        .query(G0CountRootSql.readMarker).use {checkNotNull(G0CountRootSql.captureMarker(it))}

    /** A warm owner compares its own known-committed H; only a new cold read owner performs
     * key matching. Neither missing H nor dirty P can be silently re-enrolled on a warm write. */
    private fun rootSealBoundary():G0CountRootChanges.Boundary {
        val marker=readRootMarker();val previous=rootSealPacket
        val sql=db.openHelper.writableDatabase
        if(previous==null) {
            check(incrementalState==null && marker.dirty)
            sql.query(G0CountRootSql.readSeal).use {check(!it.moveToFirst()) {"G0_ROOT_EXISTING_SEAL_NOT_ISSUED"}}
        } else {
            val actual=sql.query(G0CountRootSql.readSeal).use {checkNotNull(G0CountRootSql.captureSeal(it))}
            check(cells(actual.headerValues())==cells(previous.headerValues()) &&
                !marker.dirty && marker.generation==previous.generation) {"G0_ROOT_WARM_SEAL_CHANGED"}
        }
        return G0CountRootChanges.Boundary(previous?.headerValues()?.let(::cells),cells(marker.values()))
    }
    private fun persistRootSeal(next:G0CountRootSeal.Packet) {
        val sql=db.openHelper.writableDatabase;val previous=rootSealPacket
        if(previous==null)sql.execSQL(G0CountRootSql.insertHeader,next.headerValues().toTypedArray())
        else sql.execSQL(G0CountRootSql.updateHeader,G0CountRootSql.updateValues(next,previous).toTypedArray())
        sql.execSQL(G0CountRootSql.clearMarker,arrayOf(next.generation))
        check(readRootMarker()==G0CountRootSql.Marker(next.generation,false))
    }

    private fun freezeHabit(value:HabitEntity)=value.copy(schedule=
        (value.schedule as? com.dayforge.data.model.HabitSchedule.Weekly)?.let {
            it.copy(daysOfWeek=java.util.Collections.unmodifiableList(it.daysOfWeek.toList()))
        } ?: value.schedule)

    private data class PersistentSeed(val revision:Long,val inputHash:String,val textHash:String,
        val retainedInputBytes:Long,val tables:Map<String,G0FrozenDomainInputs.Table>)
    private var persistentSeed:PersistentSeed?=null
    private var publicationEscaped=false
    var certificateHit=false;private set
    var certificateEncodedBytes=0;private set
    var certificateMaintenanceBytes=0L;private set

    private fun imageData():G0HouseholdCountImage.Data {
        val today=objects.values.first().history.today
        return G0HouseholdCountImage.Data(today,ZoneId.systemDefault().id,
            objects.mapValues {G0HouseholdCountImage.ObjectData(it.value.habit,G0HouseholdCountImage.History.from(it.value.history))},
            days.mapKeys {G0HouseholdCountImage.DayKey(it.key.first,it.key.second)},
            daySources.mapKeys {G0HouseholdCountImage.DayKey(it.key.first,it.key.second)},
            origins,operations,originHashes,pending,sent,receipts,transmissionHashes,shadows,lastFactId,lastQueueId,totalFacts)
    }
    private fun installImage(data:G0HouseholdCountImage.Data) {
        check(objects.isEmpty() && origins.isEmpty() && days.isEmpty())
        data.objects.forEach {(key,value)->objects[key]=ObjectState(value.habit,value.history.withoutAuthority())}
        days.putAll(data.days.mapKeys {it.key.habitId to it.key.localDate})
        daySources.putAll(data.daySources.mapKeys {it.key.habitId to it.key.localDate}.mapValues {it.value.toMutableSet()})
        origins.putAll(data.origins);operations.putAll(data.operations);originHashes.putAll(data.originHashes)
        pending.putAll(data.pending);sent.putAll(data.sent);receipts.putAll(data.receipts)
        transmissionHashes.putAll(data.transmissionHashes);shadows.putAll(data.shadows)
        lastFactId=data.lastFactId;lastQueueId=data.lastQueueId;totalFacts=data.totalFacts
    }
    private fun certificateContext(expected:List<HabitEntity>,today:LocalDate=LocalDate.now(),zone:String=ZoneId.systemDefault().id)=
        G0CountCertificate.context(scope,registered,today,zone,maxFacts,expected.map {it.id to it.uuid})
    private suspend fun restoreCertificate(expected:List<HabitEntity>,input:G0FrozenDomainInputs):ColdPending? {
        val proof=requireNotNull(certificate)
        val remaining=G0FrozenDomainInputs.DEFAULT_MAX_BYTES-input.encodedBytes
        if(remaining<=0)return null
        val bytes=proof.match(input,certificateContext(expected),remaining) ?: return null
        val image=try {G0HouseholdCountImage.decode(bytes,G0HouseholdCountImage.Limits(maxFacts,remaining))}
            catch(_:G0HouseholdCountImage.Malformed) {return null}
            catch(_:G0HouseholdCountImage.BudgetExceeded) {return null}
        if(!image.matchesEnvironment(LocalDate.now(),ZoneId.systemDefault().id))return null
        val data=image.copyForInstall()
        if(data.objects.values.map {it.habit}.toSet()!=expected.toSet())return null
        if(!G0CountTemporalCheck.matches(data))return null
        val staged=G0DomainProofIndex.stageFrozen(input)
        installImage(data)
        certificateHit=true;certificateEncodedBytes=image.encodedBytes
        return ColdPending(input,staged,null,null)
    }
    private fun retainPersistentSeed(input:G0FrozenDomainInputs?) {
        persistentSeed=if(certificate==null || input==null) null else PersistentSeed(gate.revision,input.digestHex,
            requireNotNull(input.textDigestHex),input.encodedBytes,G0CountCertificateSql.tables.keys.associateWith {input.tables.getValue(it)})
    }
    /** Optional first-sign experiment only, before any View escapes. No input rescan after a business change:
     * its known cold P root becomes unavailable and this method returns false. P3 must
     * establish incremental P/Q maintenance before claiming natural post-write hits.
     */
    suspend fun persistColdCertificate():Boolean {
        val signer=requireNotNull(certificate)
        val entry=authority.lease()
        data class Capture(val seed:PersistentSeed,val context:String,val snapshot:G0HouseholdCountImage.FrozenData,
            val limit:G0HouseholdCountImage.Limits,val lease:G0AuthorityOwner.Lease)
        val captured=account {
            val lease=entryLeaseOwned(entry)
            if(publicationEscaped)return@account null
            val seed=persistentSeed ?: return@account null
            if(seed.revision!=gate.revision)return@account null
            val data=imageData()
            if(data.today!=LocalDate.now())return@account null
            val remaining=G0FrozenDomainInputs.DEFAULT_MAX_BYTES-seed.retainedInputBytes
            if(remaining<=0)return@account null
            val limit=G0HouseholdCountImage.Limits(maxFacts,remaining)
            val snapshot=try {timed("certificate_snapshot") {G0HouseholdCountImage.snapshot(data,limit)}}
                catch(_:G0HouseholdCountImage.BudgetExceeded) {return@account null}
                catch(_:G0HouseholdCountImage.Malformed) {return@account null}
            Capture(seed,certificateContext(data.objects.values.map {it.habit},data.today,data.defaultZone),snapshot,limit,lease)
        } ?: return false
        val image=try {timed("certificate_encode") {withContext(Dispatchers.Default) {G0HouseholdCountImage.encode(captured.snapshot,captured.limit)}}}
            catch(_:G0HouseholdCountImage.BudgetExceeded) {return false}
            catch(_:G0HouseholdCountImage.Malformed) {return false}
        val encoded=timed("certificate_export") {image.encode()}
        val available=captured.limit.availableBytes-image.retainedBytes
        val packet=timed("certificate_sign") {withContext(Dispatchers.IO) {
            signer.prepare(captured.context,captured.seed.inputHash,captured.seed.textHash,encoded,available)
        }} ?: return false
        afterCertificatePreparedForTest()
        return timed("certificate_commit") {account {
            current();authority.checkLease(captured.lease)
            if(publicationEscaped || persistentSeed!==captured.seed || gate.revision!=captured.seed.revision ||
                certificateContext(objects.values.map {it.habit})!=captured.context)return@account false
            authority.finalPermit(captured.lease).use {
                try {
                    gate.transaction(db,G0DomainSqlGate.Phase.MAINTENANCE,committed={
                        epoch=gate.revision
                        // A subsequent issuance needs a new owned capture of Q/H. This
                        // first-sign experiment is one-shot per cold owner, not a queue.
                        persistentSeed=null
                        certificateMaintenanceBytes=packet.retainedBytes
                    }) {
                        current();authority.checkLease(captured.lease)
                        val expected=mutableListOf<G0DomainJournal.Change>()
                        fun nativeRow(row:G0FrozenDomainInputs.Row)=row.cells.map {cell->when(cell.type) {
                            android.database.Cursor.FIELD_TYPE_NULL->null
                            android.database.Cursor.FIELD_TYPE_INTEGER->cell.number
                            android.database.Cursor.FIELD_TYPE_FLOAT->java.lang.Double.longBitsToDouble(requireNotNull(cell.number))
                            android.database.Cursor.FIELD_TYPE_STRING->cell.text
                            android.database.Cursor.FIELD_TYPE_BLOB->cell.bytes()
                            else->error("G0_CERTIFICATE_CELL")
                        }}
                        val sql=db.openHelper.writableDatabase
                        for((table,statement) in listOf(G0CountCertificateSql.table to G0CountCertificateSql.deleteHeader,
                            G0CountCertificateSql.chunkTable to G0CountCertificateSql.deleteChunks)) {
                            sql.execSQL(statement)
                            captured.seed.tables.getValue(table).rows.forEach {row->effect(expected,table,nativeRow(row),null)}
                        }
                        repeat(packet.chunkCount) {index->
                            currentCoroutineContext().ensureActive()
                            val values=packet.chunkValues(index)
                            sql.execSQL(G0CountCertificateSql.insertChunk,values.toTypedArray())
                            effect(expected,G0CountCertificateSql.chunkTable,null,values)
                        }
                        val header=packet.headerValues()
                        sql.execSQL(G0CountCertificateSql.insertHeader,header.toTypedArray())
                        effect(expected,G0CountCertificateSql.table,null,header)
                        beforeCertificateFinalForTest()
                        gate.certifyChanges {actual->
                            // DELETE visitation order is not a SQL contract. Match the
                            // complete typed multiset, never just the final row counts.
                            val normalized=actual.map {it.copy(sequence=0)}.toMutableList()
                            for(change in expected)check(normalized.remove(change)) {"G0_CERTIFICATE_UNEXPECTED_EFFECT"}
                            check(normalized.isEmpty()) {"G0_CERTIFICATE_EXTRA_EFFECT"}
                        }
                        gate.finalRead {authenticate()}
                    }
                    true
                } catch(failure:Throwable) {
                    poisoned=true;commandContext=null;index.invalidate();persistentSeed=null
                    throw failure
                }
            }
        }}
    }

    private data class ColdPending(val input:G0FrozenDomainInputs?,
        val staged:G0DomainProofIndex.StagedBaseline?,val captured:G0DomainProofIndex?,
        val prompt:G0CompletionPromptProof?)
    var coldEncodedBytes=0L; private set
    var coldCapturedRows=0L; private set
    var sharedColdInputUsed=false; private set
    var afterColdFreezeForTest:suspend ()->Unit={}
    var beforeColdFinalForTest:suspend ()->Unit={}
    var afterColdEndForTest:suspend ()->Unit={}

    private fun G0FrozenDomainInputs.members(table:String,column:String):List<String> {
        val data=tables.getValue(table);val offset=data.columns.indexOf(column);check(offset>=0)
        return data.rows.map {requireNotNull(it.cells[offset].text)}
    }
    private suspend fun validateCold(expectedRows:List<HabitEntity>,input:G0FrozenDomainInputs?):ColdPending {
        val sql=db.openHelper.writableDatabase
        NextChallengeStore(db).requirePlainInTransaction()
        val empty=G0DomainSqlCatalog.columns.keys-setOf("habits","metrics","completions","count_days",
            "sync_outbox","sync_control","next_request_origins","next_transmissions","next_acceptances","sync_entity_state")-
            (if(completionPromptProfile==null)emptySet() else setOf("habit_metric_links"))
        for(table in empty)require(input?.tables?.getValue(table)?.rows?.isEmpty() ?: sql.query("SELECT * FROM main.`$table`").use {!it.moveToFirst()}) {"G0_COUNT_UNSUPPORTED_SCOPE:$table"}
        val metrics=db.metricDao().getAllMetricsOnce()
        require(metrics.map {it.uuid}.distinct().size==metrics.size && metrics.map {it.name}.distinct().size==metrics.size)
        val all=db.habitDao().getAllHabitsOnce()
        require(expectedRows.size==2 && all.size==2 && all.toSet()==expectedRows.toSet() &&
            all.map {it.id}.distinct().size==2 && all.map {it.uuid}.distinct().size==2 &&
            all.map {it.name}.distinct().size==2 && all.all {
                it.habitType==HabitType.COUNTING && it.appearance!=null && it.isActive && !it.isCountdown &&
                    it.completionPolicy=="recurring" && it.parentHabitId==null
            }) {"G0_HOUSEHOLD_UNSUPPORTED_SCOPE"}
        val today=LocalDate.now()
        for(value in all.sortedBy {it.id}) {
            val habit=freezeHabit(value)
            val history=timed("count_complete_history") {
                CountHistoryReader(db,tokens,sessions).readInTransaction(habit,today)
            }
            require(history.roundHead==null && history.unknownDates.isEmpty())
            // The original reader establishes that an absent current rule is
            // an unstarted day, not lost/undone/legacy retained evidence.
            require(history.todayPolicy!=null)
            val captured=NextCountDayStore(db).readForHabit(habit)
            require(captured.map {it.localDate}.toSet()==history.policies.keys.map {it.toString()}.toSet())
            for(day in captured) {
                require(day.originRequestId!=null && day.capturedDeviceId==registered.deviceId &&
                    day.planPredecessorId==null && day.planPredecessorOriginHash==null && day.planPredecessorDependencyHash==null)
                check(days.put(day.habitId to day.localDate,day)==null)
            }
            objects[habit.uuid]=ObjectState(habit,history)
        }
        val facts=objects.values.flatMap {it.history.completions}
        totalFacts=facts.size
        require(totalFacts<=maxFacts) {"G0_COUNT_CAPACITY_UNSUPPORTED"}
        require(facts.map {it.id}.distinct().size==facts.size && facts.map {it.uuid}.distinct().size==facts.size)
        require(db.completionDao().getAllCompletionsOnce().toSet()==facts.toSet())
        val queues=db.syncOutboxDao().getAll()+db.syncOutboxDao().getDeadLetters()
        val ids=input?.members("next_request_origins","requestId") ?: sql.query("SELECT * FROM main.`next_request_origins`").use {c->buildList {
            while(c.moveToNext())add(c.getString(c.getColumnIndexOrThrow("requestId")))
        }}
        val seenFacts=hashSetOf<String>()
        for(id in ids) {
            val origin=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,id))
            require(origin.kind==NEXT_OPERATION && origin.protocol==5 && origin.queueId>0 &&
                origin.accountId==scope.session.authentication.userId && origin.serverInstanceId==scope.session.serverInstanceId &&
                origin.syncEpoch==scope.session.syncEpoch)
            require(roundOperationIntent(origin.intentJson)==null && countPlanFrontier(origin.intentJson)==null)
            val op=decodeNextOperationIntent(origin.intentJson);validateNextSyncOperation(op)
            val state=objects[op.payload["activity_uuid"]?.jsonPrimitive?.content]
            require(op.operationId==id && op.entityType=="activity_event" && op.action=="upsert" &&
                op.payload["event_type"]==JsonPrimitive("count_snapshot") && state!=null && seenFacts.add(op.entityUuid))
            val fact=state.history.completions.single {it.uuid==op.entityUuid}
            require(fact.habitId==state.habit.id && fact.habitUuid==state.habit.uuid &&
                op.payload["local_date"]==JsonPrimitive(fact.recordedLocalDate))
            origins[id]=origin;operations[id]=op;originHashes[id]=rowHash("next_request_origins",id)
            daySources.getOrPut(fact.habitId to fact.recordedLocalDate) {linkedSetOf()}.add(id)
            val receipt=db.nextRequestDao().acceptance(NEXT_OPERATION,id)
            val transmission=db.nextRequestDao().transmission(NEXT_OPERATION,id)
            if(receipt!=null) {
                original.requireAcceptedCountInTransaction(registered,id)
                receipts[id]=receipt;sent[id]=requireNotNull(transmission).copy(wireBytes=transmission.wireBytes.copyOf())
                transmissionHashes[id]=rowHash("next_transmissions",id)
                shadows[op.entityUuid]=requireNotNull(db.syncOutboxDao().getState("activity_event",op.entityUuid))
            } else {
                require(transmission==null) {"G0_COUNT_COLD_INFLIGHT_UNSUPPORTED"}
                val q=queues.single {it.operationId==id}
                require(q.id==origin.queueId && q.recordType=="completion" && q.entityUuid==op.entityUuid &&
                    q.wireEntityUuid==op.entityUuid && q.referenceUuid==state.habit.uuid && q.action=="upsert" &&
                    q.payloadJson==null && q.baseRevision==null && q.basePayloadJson==null && q.attemptedAt==null &&
                    q.attemptCount==0 && q.lastError==null && q.errorCode==null && q.deadLetteredAt==null)
                require(NextRequestSql.rowHash(sql,"sync_outbox","id=?",arrayOf(q.id))==origin.sourceHash)
                pending[id]=q
            }
        }
        require(seenFacts==facts.map {it.uuid}.toSet() && queues.toSet()==pending.values.toSet())
        fun members(table:String,column:String)=input?.members(table,column) ?: sql.query("SELECT * FROM main.`$table`").use {c->buildList {
            while(c.moveToNext())add(c.getString(c.getColumnIndexOrThrow(column)))
        }}
        require(members("next_transmissions","requestId").sorted()==sent.keys.sorted() &&
            members("next_acceptances","requestId").sorted()==receipts.keys.sorted())
        require(members("sync_entity_state","entityUuid").sorted()==shadows.keys.sorted() &&
            db.syncOutboxDao().getStatesForType("activity_event").size==shadows.size)
        require(daySources.keys==days.keys)
        for(day in days.values) {
            val first=requireNotNull(day.originRequestId)
            val operation=operations.getValue(first)
            require(first in daySources.getValue(day.habitId to day.localDate) &&
                day.originHash==originHashes.getValue(first) && operation.entityUuid==day.firstEventUuid &&
                operation.payload["activity_uuid"]==JsonPrimitive(day.habitUuid) &&
                operation.payload["local_date"]==JsonPrimitive(day.localDate) && NextCommonFactMapper.countPolicy(operation.payload)==day.policy)
            // Both branches have already passed the original complete reader
            // and original day read. A legitimate local first birth may still
            // be unsent; receipt absence alone is not day-rule corruption.
            require(first in receipts || first in pending && first !in sent)
        }
        if(capacityProfile==CapacityProfile.ANNUAL_730_PLUS_6) {
            // These are the independently proven directory members above,
            // never unverified receipt row counts. With totalFacts<=736,
            // 730 accepted sources leave at most six pending/new commands
            // for this owner; the existing runtime/worker queue stays bounded.
            require(pending.size<=6) {"G0_COUNT_ANNUAL_PENDING_LIMIT"}
            require(receipts.size>=730) {"G0_COUNT_ANNUAL_ACCEPTED_BASE_REQUIRED"}
        }
        NextRequestSql.requireOutboxEnabled(sql)
        lastFactId=facts.maxOfOrNull {it.id} ?: 0L
        sql.query("SELECT * FROM main.`sqlite_sequence`").use {c->while(c.moveToNext()) {
            require(c.getType(0)==android.database.Cursor.FIELD_TYPE_STRING &&
                c.getType(1)==android.database.Cursor.FIELD_TYPE_INTEGER && c.getLong(1)>=0)
            if(c.getString(0)=="completions") {require(c.getLong(1)>=lastFactId);lastFactId=c.getLong(1)}
            if(c.getString(0)=="sync_outbox")lastQueueId=c.getLong(1)
        }}
        require(lastQueueId>=(origins.values.maxOfOrNull {it.queueId} ?: 0L)) {"G0_COUNT_SEQUENCE_CHANGED"}
        val staged=input?.let { G0DomainProofIndex.stageFrozen(it) }
        val capturedIndex=if(staged==null) G0DomainProofIndex.captureVerifiedBaseline(sql) else null
        val capturedPrompt=completionPromptProfile?.let { profile ->
            G0CompletionPromptProof.capture(db,tokens,sessions,scope.session,profile,all,requireNotNull(capturedIndex))
        }
        val countDays=input?.tables?.getValue("count_days")?.rows?.size ?: sql.query("SELECT * FROM main.`count_days`").use {c->var count=0;while(c.moveToNext())count++;count}
        require(countDays==days.size)
        for(day in days.values) {
            val key=dayKey(day.habitId,day.localDate)
            val row=if(staged!=null) staged.row(key) else requireNotNull(capturedIndex).row(key)
            require(row==cells(dayValues(day)))
        }
        return ColdPending(input,staged,capturedIndex,capturedPrompt)
    }
    private suspend fun captureColdAuthority() {
        scope=requireNotNull(tokens.localCoreWriteAccess())
        registered=requireNotNull(tokens.localSyncAccess()).let {
            it.copy(capabilities=java.util.Collections.unmodifiableSet(it.capabilities.toSet()))
        }
        require(registered.session==scope.session && registered.deviceId==scope.capturedDeviceId &&
            registered.capabilities==scope.capabilities && "facts.append" in registered.capabilities && "sync.read" in registered.capabilities)
    }
    private fun installCold(pending:ColdPending,end:G0RoomColdOwnerFactory.KnownEnd?) {
        index=pending.staged?.activate(requireNotNull(end)) ?: requireNotNull(pending.captured)
        completionPromptProof=pending.prompt
    }
    suspend fun initialize(expected:List<HabitEntity>,schema:List<List<String?>>) {
        val expectedRows=expected.map(::freezeHabit)
        account {
            check(!ready && !poisoned)
            try {
                val lease=authority.lease();captureColdAuthority()
                authority.finalPermit(lease).use {
                    db.openHelper.writableDatabase.execSQL("PRAGMA recursive_triggers=ON")
                    val pending=db.withTransaction {
                        val result=validateCold(expectedRows,null)
                        gate.installJournal(schema)
                        require(tokens.isCurrentDisplayReadAuthority(scope,registered))
                        result
                    }
                    authority.checkLease(lease);installCold(pending,null)
                    gate.seal();epoch=gate.revision
                    admitted=java.util.Collections.unmodifiableSet(objects.keys.toSet());ready=true
                    commandContext=CommandContext(scope.session,admitted)
                }
            } catch(failure:Throwable) {poisoned=true;commandContext=null;throw failure}
        }
    }
    /** Explicit G0 existing-DB candidate. The original initialization remains the control.
     * No warm certificate or result escapes before the owner confirms actual END.
     */
    suspend fun initializeOwned(expected:List<HabitEntity>,schema:List<List<String?>>,
        owner:G0RoomColdOwnerFactory,shareInput:Boolean,
        maxInputBytes:Long=G0FrozenDomainInputs.DEFAULT_MAX_BYTES) {
        val expectedRows=expected.map(::freezeHabit)
        account {
            check(!ready && !poisoned)
            try {
                require(completionPromptProfile==null) { "G0_COLD_OWNER_PROMPT_PROFILE_UNSUPPORTED" }
                val lease=authority.lease();captureColdAuthority()
                authority.finalPermit(lease).use {
                    val completed=gate.ownedColdTransaction(db,schema) {
                        // This explicit cold candidate covers the existing plain count profile.
                        // The original initialize path keeps the independently tested prompt profile.
                        val input=if(shareInput)
                            timed("cold_frozen_capture") {G0FrozenDomainInputs.capture(db,gate,schema,maxInputBytes,countCertificateScope=certificate!=null)} else null
                        sharedColdInputUsed=input!=null
                        coldEncodedBytes=input?.encodedBytes ?: 0L;coldCapturedRows=input?.scannedRows ?: 0L
                        afterColdFreezeForTest()
                        // The control fixture refreshes these selected IDs before initialize.
                        // Do the same inside the protected snapshot, never by opening early.
                        val currentExpected=expectedRows.map {requireNotNull(db.habitDao().getHabitById(it.id))}
                        val restored=if(input!=null && certificate!=null && certificateLookup)
                            timed("count_certificate_restore") {restoreCertificate(currentExpected,input)} else null
                        val pending=restored ?: validateCold(currentExpected,input)
                        beforeColdFinalForTest()
                        require(tokens.isCurrentDisplayReadAuthority(scope,registered))
                        authority.checkLease(lease)
                        currentCoroutineContext().ensureActive()
                        owner.validated(pending,input)
                        pending
                    }
                    completed.end.requireCandidate(completed.value)
                    afterColdEndForTest()
                    authority.checkLease(lease);currentCoroutineContext().ensureActive()
                    installCold(completed.value,completed.end)
                    gate.sealCold(completed.end);epoch=gate.revision
                    retainPersistentSeed(completed.value.input)
                    admitted=java.util.Collections.unmodifiableSet(objects.keys.toSet());ready=true
                    commandContext=CommandContext(scope.session,admitted)
                }
            } catch(failure:Throwable) {poisoned=true;commandContext=null;gate.invalidateConnection();owner.fail(failure)}
        }
    }

    private fun current() {
        check(ready && !poisoned) { "G0_COUNT_RECOVERY_REQUIRED" }
        gate.requireSealedConnection();check(epoch==gate.revision) { "G0_COUNT_UNOBSERVED_COMMIT" }
    }
    /** Retains the old entry's exact uncoordinated guard for the deterministic
     * counterexample. This diagnostic returns no lease, data, or usable authority.
     * Business entry points must instead observe current() under account ownership.
     */
    internal fun probeUncoordinatedCurrentForTest()=current()
    private suspend fun authenticate() {
        current()
        // The existing predicate checks BOTH captured authorities using one fresh
        // Preferences value and one real access decryption. It is never a cached grant.
        require(if(jointAuthority)tokens.isCurrentDisplayReadAuthority(scope,registered)
            else tokens.localCoreWriteAccess()==scope && tokens.isCurrentSyncAuthority(registered)) { "G0_COUNT_AUTH_CHANGED" }
    }
    /** Caller already owns account. Do not reacquire its non-reentrant mutex here.
     * This keeps authority/context and the following preparation snapshot on one
     * completed owner promotion while preserving fresh entry decryption.
     */
    private suspend fun entryLeaseOwned(captured:G0AuthorityOwner.Lease?=null):G0AuthorityOwner.Lease {
        current()
        val lease=if(jointAuthority)captured ?: authority.lease() else null
        if(lease!=null)authority.checkLease(lease)
        authenticate()
        afterEntryAuthenticationForTest()
        if(lease!=null) {
            // A transition, even one restored to identical values, invalidates this
            // observation. Never acquire a replacement lease after reading credentials.
            authority.checkLease(lease)
            return lease
        }
        return authority.lease()
    }
    private data class Preparation(val habit:HabitEntity,val history:CountHistory,val witness:G0DomainProofIndex.Witness,
        val day:CountDayEntity?,val factId:Long,val queueId:Long)
    private data class Request(val witness:G0DomainProofIndex.Witness,val origin:NextRequestOriginEntity,
        val operation:SyncV2Operation,val transmission:NextTransmissionEntity?,val receipt:NextAcceptanceEntity?,
        val queue:SyncOutboxEntity?,val shadow:SyncEntityStateEntity?,val habit:HabitEntity,val history:CountHistory,
        val day:CountDayEntity,val firstAccepted:Boolean,val originHash:String,val transmissionHash:String?)
    // All directories are captured together under the one account owner.
    private fun request(id:String):Request {
        current();val op=operations.getValue(id)
        val state=objects.getValue(op.payload.getValue("activity_uuid").jsonPrimitive.content)
        val day=days.getValue(state.habit.id to op.payload.getValue("local_date").jsonPrimitive.content)
        require(day.policy==NextCommonFactMapper.countPolicy(op.payload))
        return Request(witness(),origins.getValue(id),op,sent[id]?.let {it.copy(wireBytes=it.wireBytes.copyOf())},
            receipts[id],pending[id],shadows[op.entityUuid],state.habit,state.history,day,
            requireNotNull(day.originRequestId) in receipts,originHashes.getValue(id),transmissionHashes[id])
    }
    private fun dayKey(habitId:Long,date:String)=G0DomainProofIndex.Key("count_days",cells(listOf(habitId,date)))
    private fun requireFirstBirth(before:Request) {
        // Original NextCountOrderingStore applies only before the FIRST frozen
        // transmission. Exact already-frozen retransmission keeps its original rules.
        if(before.transmission==null && before.origin.requestId!=before.day.originRequestId && !before.firstAccepted)
            rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
    }
    private fun witness()=index.witness(G0DomainSqlCatalog.columns.keys.mapTo(linkedSetOf()) { G0DomainProofIndex.Dependency.Table(it) })
    private suspend fun <T> commit(lease:G0AuthorityOwner.Lease,witness:G0DomainProofIndex.Witness,
        promote:()->Unit,blockDelta:(()->G0CountIncrementalBlocks.Delta)?=null,
        installBlocks:G0CountIncrementalBlocks.State?=null,
        body:suspend (MutableList<G0DomainJournal.Change>)->T):T=account {
        current();authority.checkLease(lease)
        if(!index.isCurrent(witness))throw PreparationChanged()
        val previous=if(incrementalBlockScope && installBlocks==null)currentIncremental() else null
        if(installBlocks!=null)check(incrementalBlockScope && incrementalState==null &&
            installBlocks.binding==G0CountBlockSql.binding(scope,registered))
        authority.finalPermit(lease).use {
            lateinit var advancement:G0DomainProofIndex.Advancement
            var stagedBlocks:G0CountIncrementalBlocks.State?=null
            var stagedHeader:List<Any?>?=null
            var stagedNodes=0;var stagedBytes=0L
            var stagedStats:G0CountIncrementalBlocks.UpdateStats?=null
            var stagedSeal:G0CountRootSeal.Packet?=null
            try {
                gate.transaction(db,G0DomainSqlGate.Phase.BUSINESS,committed={ actual ->
                    index.committed(advancement,actual.filter {it.table in G0DomainSqlCatalog.columns});epoch=gate.revision
                    if(actual.isNotEmpty())persistentSeed=null
                    promote()
                    stagedSeal?.let {rootSealPacket=it}
                    if(stagedBlocks!=null) {
                        incrementalState=IncrementalState(requireNotNull(stagedBlocks),requireNotNull(stagedHeader),epoch,proofRevision)
                        incrementalWrittenNodes+=stagedNodes;incrementalWrittenBytes+=stagedBytes
                        incrementalLastUpdateStats=stagedStats
                        if(installBlocks==null)incrementalUpdates++
                    } else if(previous!=null && (previous.epoch!=epoch || previous.proofRevision!=proofRevision))
                        incrementalState=previous.copy(epoch=epoch,proofRevision=proofRevision)
                }) {
                    current();authority.checkLease(lease);index.requireCurrent(witness)
                    val rootBoundary=if(rootSeal!=null)rootSealBoundary() else null
                    val expected=mutableListOf<G0DomainJournal.Change>()
                    val result=body(expected)
                    if(installBlocks!=null) {
                        check(expected.isEmpty()) {"G0_BLOCK_INSTALL_CHANGED_PRIMARY"}
                        stagedBlocks=installBlocks
                        stagedHeader=timed("count_block_persist") {persistBlockUpdate(installBlocks,installBlocks.blocks,null,expected)}
                        stagedNodes=installBlocks.blocks.size;stagedBytes=installBlocks.blocks.values.sumOf {it.size.toLong()}
                    } else if(previous!=null && expected.isNotEmpty()) {
                        val update=timed("count_block_advance") {
                            G0CountIncrementalBlocks.advance(previous.data,checkNotNull(blockDelta) {"G0_BLOCK_DELTA_REQUIRED"}.invoke())
                        }
                        stagedBlocks=update.state
                        stagedStats=update.stats
                        stagedHeader=timed("count_block_persist") {persistBlockUpdate(update.state,update.newBlocks,previous,expected)}
                        stagedNodes=update.newBlocks.size;stagedBytes=update.newBlocks.values.sumOf {it.size.toLong()}
                    }
                    if(rootSeal!=null && stagedBlocks!=null) {
                        if(rootWriteScope)G0CountNativeRows.requireIndexableSources(db.openHelper.writableDatabase)
                        val marker=readRootMarker()
                        check(marker.dirty && marker.generation==Math.addExact(
                            (checkNotNull(rootBoundary).marker[1].value as Long),expected.size.toLong())) {"G0_ROOT_GENERATION_CHANGED"}
                        stagedSeal=timed("root_seal_prepare") {rootSeal.prepare(checkNotNull(stagedBlocks).binding,
                            checkNotNull(stagedBlocks).root,checkNotNull(stagedHeader).last() as Long,marker.generation)}
                        persistRootSeal(checkNotNull(stagedSeal))
                    }
                    beforeFinalForTest()
                    gate.certifyChanges { actual ->
                        if(stagedSeal!=null)G0CountRootChanges.validate(checkNotNull(rootBoundary),
                            cells(checkNotNull(stagedSeal).headerValues()),actual,expected)
                        else check(actual.map { it.copy(sequence=0) }==expected) { "G0_COUNT_UNEXPECTED_EFFECT" }
                        // Q is NOT silently ignored: the combined exact typed change list above
                        // includes every expected node/header effect, then P's original index
                        // receives its own rows. No arbitrary Q write can pass this boundary.
                        advancement=index.prepare(actual.filter {it.table in G0DomainSqlCatalog.columns})
                    }
                    gate.finalRead { authenticate() }
                    result
                }
            } catch(error:Throwable) { poisoned=true;commandContext=null;index.invalidate();throw error }
        }
    }

    // Mutable qualification/preparation and promotion share this owner. Entry decryption
    // is included in its measured hold; pure calculation and network waits stay outside.
    private suspend fun <T> account(body:suspend ()->T):T {
        val trace=G0PhaseTrace.current.get();val queued=trace?.now()
        return sessions.exclusive {
            if(queued!=null)trace.record("count_account_wait",queued,true)
            if(trace==null)body() else trace.stage("count_account_hold",body)
        }
    }
    private suspend fun <T> timed(name:String,body:suspend ()->T):T {
        val trace=G0PhaseTrace.current.get()
        return if(trace==null)body() else trace.stage(name,body)
    }

    private class PreparationChanged:IllegalStateException("G0_COUNT_PREPARATION_CHANGED")
    // Only this typed PRE-TRANSACTION conflict is retried. SQL, auth, cancellation and
    // unknown COMMIT exceptions are never retried. Every attempt authenticates anew.
    private suspend fun <T> reprepare(body:suspend ()->T):T {
        repeat(3) { attempt ->
            try {return body()} catch(error:PreparationChanged) {if(attempt==2)throw error}
        }
        error("G0_COUNT_UNREACHABLE")
    }
    suspend fun increase(habitUuid:String):Saved=increase(newCommand(habitUuid))
    suspend fun increase(command:Command):Saved=reprepare {increaseOnce(command)}
    private suspend fun increaseOnce(command:Command):Saved {
        // Capture only the atomic authority lifetime before queueing. Mutable domain
        // qualification waits for the owner, but a queued intent cannot rebase an ABA.
        val entry=if(jointAuthority)authority.lease() else null
        val (lease,before)=account {
            val lease=entryLeaseOwned(entry)
            current();require(command.session==scope.session && command.habitUuid in admitted)
            require(isContractUuid(command.requestId) && isContractUuid(command.factUuid) && command.requestId !in origins &&
                objects.values.none {state->state.history.completions.any {it.uuid==command.factUuid}}) {"G0_COUNT_COMMAND_ALREADY_EXISTS"}
            require(totalFacts<maxFacts) {"G0_COUNT_CAPACITY_UNSUPPORTED"}
            val state=objects.getValue(command.habitUuid);val date=state.history.today.toString()
            val day=days[state.habit.id to date]
            require((day==null)==(state.history.policies[state.history.today]==null))
            if(day==null) {
                require(state.history.today !in state.history.unknownDates && state.history.todayQuantity==0L &&
                    daySources[state.habit.id to date].isNullOrEmpty() && index.row(dayKey(state.habit.id,date))==null)
            } else require(index.row(dayKey(day.habitId,day.localDate))==cells(dayValues(day)))
            lease to Preparation(state.habit,state.history,witness(),day,Math.addExact(lastFactId,1),Math.addExact(lastQueueId,1))
        }
        val zone=ZoneId.systemDefault();val now=System.currentTimeMillis()
        val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        require(today==before.history.today) {"G0_COUNT_DATE_CHANGED"}
        val policy=before.day?.policy ?: requireNotNull(before.history.todayPolicy)
        require(!policy.isCountdown)
        val habit=before.habit
        val fact=CompletionEntity(before.factId,habit.id,today.atStartOfDay(zone).toInstant().toEpochMilli(),1,
            now,command.factUuid,habit.uuid,now,zone.id,today.toString())
        val queue=SyncOutboxEntity(before.queueId,command.requestId,"completion",fact.uuid,fact.uuid,"upsert",habit.uuid,createdAt=now)
        val operation=SyncV2Operation(operationId=queue.operationId,entityType="activity_event",entityUuid=fact.uuid,
            action="upsert",payload=JsonObject(SyncV2Mapper.completion(fact,habit)+("count_policy" to policy.toJson())))
        val encoded=withContext(Dispatchers.Default) {
            val bytes=encodeSyncRequest(SyncV2Operation.serializer(),operation)
            require(decodeNextOperationIntent(bytes.toString(Charsets.UTF_8))==operation)
            decodeFrozenSyncRequest(encodeSyncRequest(NextSyncPushRequest.serializer(),
                NextSyncPushRequest(requireNotNull(registered.deviceId),listOf(operation))),NextSyncPushRequest.serializer())
            bytes.toString(Charsets.UTF_8)
        }
        val rate=ActivityRateCalculator.calculate(habit.schedule,habit.createdAt,(before.history.quantities.keys+today).toList(),now,zone)
        val nextHabit=habit.copy(activityRate=rate,activityRateUpdatedAt=now)
        val nextQuantity=Math.addExact(before.history.quantities[today] ?: 0L,1L)
        val order=compareByDescending<CompletionEntity> {it.recordedLocalDate}
            .thenByDescending {it.actualCompletedAt ?: it.date}.thenByDescending {it.id}
        val ordered=before.history.completions.toMutableList();val position=ordered.binarySearch(fact,order)
        check(position<0);ordered.add(-position-1,fact)
        val nextHistory=before.history.copy(completions=ordered.toList(),quantities=before.history.quantities+(today to nextQuantity),
            policies=before.history.policies+(today to policy),todayPolicy=policy)
        val birth=if(before.day==null)CountDayEntity(habit.id,habit.uuid,today.toString(),policy.targetValue,policy.isCountdown,fact.uuid) else null
        lateinit var origin:NextRequestOriginEntity
        var hash="";var boundDay:CountDayEntity?=null
        afterPrepareForTest()
        return commit(lease,before.witness,{
            objects[habit.uuid]=ObjectState(nextHabit,nextHistory)
            boundDay?.let {days[it.habitId to it.localDate]=it}
            daySources.getOrPut(habit.id to today.toString()) {linkedSetOf()}.add(queue.operationId)
            origins[queue.operationId]=origin;originHashes[queue.operationId]=hash;operations[queue.operationId]=operation
            pending[queue.operationId]=queue;lastFactId=fact.id;lastQueueId=queue.id;totalFacts++
            factRevision++;proofRevision++;presentationRevision++
        },blockDelta={
            G0CountIncrementalBlocks.Delta(null,fact,null,
                G0CountBusinessBlockCodec.Source(origin,hash,null,null,null,queue,null),
                before.day,boundDay ?: requireNotNull(before.day),nextHabit)
        }) {effects->
            check(ZoneId.systemDefault()==zone && LocalDate.now(zone)==today) {"G0_COUNT_DATE_CHANGED"}
            val sql=db.openHelper.writableDatabase
            if(birth!=null) {
                check(index.row(dayKey(habit.id,today.toString()))==null)
                sql.execSQL(G0HouseholdCountSql.insertDay,dayValues(birth).toTypedArray())
                effect(effects,"count_days",null,dayValues(birth))
            }
            suppress(effects,true)
            sql.execSQL(G0CountSql.insert,factValues(fact).toTypedArray());effect(effects,"completions",null,factValues(fact))
            val oldHabit=requireNotNull(index.row(G0DomainProofIndex.Key("habits",cells(listOf(habit.id))))).map(::native)
            val newHabit=oldHabit.toMutableList().apply {
                set(G0DomainSqlCatalog.columns.getValue("habits").indexOf("activityRate"),rate)
                set(G0DomainSqlCatalog.columns.getValue("habits").indexOf("activityRateUpdatedAt"),now)
            }
            sql.execSQL(G0CountSql.rate,arrayOf<Any>(rate,now,habit.id));effect(effects,"habits",oldHabit,newHabit)
            suppress(effects,false)
            insert("sync_outbox",queueValues(queue));effect(effects,"sync_outbox",null,queueValues(queue))
            origin=NextRequestOriginEntity(NEXT_OPERATION,queue.operationId,queue.id,5,scope.session.authentication.userId,
                scope.session.serverInstanceId,scope.session.syncEpoch,NextRequestSql.sourceHash(queue),encoded)
            insert("next_request_origins",originValues(origin));effect(effects,"next_request_origins",null,originValues(origin))
            hash=rowHash("next_request_origins",queue.operationId)
            if(birth!=null) {
                // The closed profile proves there is no structural predecessor/frontier.
                // This is the original local birth binding, using the GLOBAL queue order.
                val bound=birth.copy(originRequestId=queue.operationId,originHash=hash,
                    planQueueWatermark=queue.id-1,capturedDeviceId=scope.capturedDeviceId)
                sql.execSQL(G0HouseholdCountSql.bindDay,arrayOf<Any?>(bound.originRequestId,bound.originHash,
                    bound.planQueueWatermark,bound.capturedDeviceId,bound.habitId,bound.localDate))
                effect(effects,"count_days",dayValues(birth),dayValues(bound));boundDay=bound
            }
            Saved(fact.id,fact.uuid,queue.operationId,habit.uuid)
        }
    }

    suspend fun send(http:NextSyncHttp,id:String):NextCoreDelivery<NextSyncPushResponse>? {
        val trace=G0PhaseTrace.current.get();val started=trace?.now()
        return http.session(registered) { channel ->
        if(started!=null)trace.record("count_send_session_ready",started,true)
        // Retry ONLY the typed pre-transaction witness conflict. No network call is
        // inside this scope; a committed/unknown transmission is never resubmitted here.
        val prepared=timed("count_send_prepare_commit") { reprepare {
            val entry=if(jointAuthority)authority.lease() else null
            val (lease,before)=account {
                val lease=entryLeaseOwned(entry)
                current();require(id in pending && id !in receipts)
                lease to request(id)
            }
            requireFirstBirth(before)
            val origin=before.origin;val op=before.operation
            val bytes=before.transmission?.wireBytes?.copyOf() ?: encodeSyncRequest(NextSyncPushRequest.serializer(),NextSyncPushRequest(requireNotNull(registered.deviceId),listOf(op)))
            require(validateNextOperationEnvelope(origin.intentJson,bytes,requireNotNull(registered.deviceId))==op)
            val transmission=before.transmission ?: NextTransmissionEntity(NEXT_OPERATION,id,origin.queueId,5,origin.accountId,
                requireNotNull(registered.session.serverInstanceId),requireNotNull(registered.session.syncEpoch),requireNotNull(registered.deviceId),nextRequestHash(bytes),bytes)
            val existed=before.transmission!=null;var hash=before.transmissionHash
            afterSendPrepareForTest()
            commit(lease,before.witness,{sent[id]=transmission;transmissionHashes[id]=requireNotNull(hash);if(!existed)proofRevision++},blockDelta={
                val fact=before.history.completions.single {it.uuid==op.entityUuid}
                G0CountIncrementalBlocks.Delta(fact,fact,
                    G0CountBusinessBlockCodec.Source(origin,before.originHash,before.transmission,before.transmissionHash,
                        before.receipt,before.queue,before.shadow),
                    G0CountBusinessBlockCodec.Source(origin,before.originHash,transmission,requireNotNull(hash),
                        before.receipt,before.queue,before.shadow),before.day,before.day,before.habit)
            }) { effects ->
                if(!existed) {insert("next_transmissions",transmissionValues(transmission));effect(effects,"next_transmissions",null,transmissionValues(transmission))}
                hash=rowHash("next_transmissions",id)
            }
            Triple(bytes,lease,requireNotNull(hash))
        } }
        val (bytes,lease,hash)=prepared
        val response=timed("count_send_push") {channel.pushFrozen(bytes.copyOf())}
        timed("count_send_after_response") {
            account {
                authenticate()
                gate.transaction(db,G0DomainSqlGate.Phase.READ) {current();authority.checkLease(lease);require(transmissionHashes[id]==hash && id in pending)}
            }
        }
        NextCoreDelivery(registered,id,response,requireNotNull(hash))
        }
    }

    suspend fun acknowledge(delivery:NextCoreDelivery<NextSyncPushResponse>):NextOperationAcceptance {
        // Freeze caller-owned response BEFORE the first suspension.
        val bytes=encodeSyncRequest(NextSyncPushResponse.serializer(),delivery.result)
        val response=decodeFrozenSyncRequest(bytes,NextSyncPushResponse.serializer())
        require(delivery.access==registered && delivery.challengeMetadata==null && delivery.configImportProof==null)
        return reprepare {acknowledgeOnce(delivery,response)}
    }
    private suspend fun acknowledgeOnce(delivery:NextCoreDelivery<NextSyncPushResponse>,response:NextSyncPushResponse):NextOperationAcceptance {
        val id=delivery.requestId
        val entry=if(jointAuthority)authority.lease() else null
        val (lease,before)=account {
            val lease=entryLeaseOwned(entry)
            lease to request(id)
        }
        require(before.transmissionHash==delivery.transmissionProof && before.transmission!=null)
        val origin=before.origin;val operation=before.operation
        require(validateNextOperationEnvelope(origin.intentJson,requireNotNull(before.transmission).wireBytes,requireNotNull(registered.deviceId))==operation)
        val result=response.results.single()
        val change=NextOrdinaryResultMapper.validate(operation,result,requireNotNull(registered.deviceId))
        val normalized=result.copy(status="applied")
        val resultBytes=encodeSyncRequest(NextSyncOperationResult.serializer(),normalized)
        val resultJson=resultBytes.toString(Charsets.UTF_8)
        val existing=before.receipt
        val replay=existing!=null
        if(replay) require(decodeFrozenSyncRequest(existing!!.resultJson.toByteArray(),NextSyncOperationResult.serializer())==normalized && before.queue==null)
        else require(before.queue!=null && before.shadow==null)
        val old=before.history.completions.single { it.uuid==operation.entityUuid }
        val next=NextCommonFactMapper.completion(change,before.habit,old)
        require(old.copy(createdAt=next.createdAt)==next)
        require(NextCommonFactMapper.countPolicy(change.payload)==before.history.policies[old.businessDate])
        NextCommonFactProof.requireOriginalPayload(operation.payload,change,requireNotNull(registered.deviceId))
        val shadow=SyncEntityStateEntity("activity_event",old.uuid,change.revision,payloadJson=change.payload.toString(),payloadHash=syncPayloadHash(change.payload.toString()))
        val receipt=existing ?: NextAcceptanceEntity(NEXT_OPERATION,id,before.originHash,requireNotNull(before.transmissionHash),nextRequestHash(resultBytes),resultJson)
        val queue=before.queue
        afterPrepareForTest()
        return commit(lease,before.witness,{
            if(!replay) {
                objects[before.habit.uuid]=ObjectState(before.habit,before.history.copy(
                    completions=before.history.completions.map {if(it.uuid==old.uuid)next else it}))
                receipts[id]=receipt;pending.remove(id);shadows[old.uuid]=shadow
                if(old!=next)factRevision++;proofRevision++
                // The display excludes storage createdAt. Proof still advances on every ACK.
            }
        },blockDelta={
            G0CountIncrementalBlocks.Delta(old,next,
                G0CountBusinessBlockCodec.Source(origin,before.originHash,before.transmission,before.transmissionHash,
                    before.receipt,before.queue,before.shadow),
                G0CountBusinessBlockCodec.Source(origin,before.originHash,before.transmission,before.transmissionHash,
                    receipt,null,shadow),before.day,before.day,before.habit)
        }) { effects ->
            if(!replay) {
                suppress(effects,true)
                db.openHelper.writableDatabase.execSQL(G0CountSql.acceptedTime,arrayOf(next.createdAt,next.id))
                effect(effects,"completions",factValues(old),factValues(next))
                val sql=G0DomainSqlCatalog.writes.keys.single {it.startsWith("INSERT OR REPLACE INTO `sync_entity_state`")}
                db.openHelper.writableDatabase.execSQL(sql,shadowValues(shadow).toTypedArray());effect(effects,"sync_entity_state",null,shadowValues(shadow))
                suppress(effects,false)
                insert("next_acceptances",receiptValues(receipt));effect(effects,"next_acceptances",null,receiptValues(receipt))
                db.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox WHERE id=?",arrayOf(requireNotNull(queue).id))
                effect(effects,"sync_outbox",queueValues(queue),null)
            }
            if(replay)NextOperationAcceptance.REPLAYED else NextOperationAcceptance.COMMITTED
        }
    }

    /** One authenticated committed snapshot supplies BOTH cards, home inputs and
     * per-object follow-up claims. A source can never be borrowed from the other card. */
    suspend fun readAndPublish(sourceIds:Map<String,Long> = emptyMap(),publish:(View)->Unit) {
        val requested=sourceIds.toMap()
        account {
            val lease=entryLeaseOwned();require(requested.keys.all {it in admitted})
            authority.finalPermit(lease).use {
                val view=gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                    current();val today=LocalDate.now()
                    require(objects.values.all {it.history.today==today}) {"G0_COUNT_DATE_CHANGED"}
                    val cards=objects.mapValues { (uuid,state)->
                        val habit=state.habit;val history=state.history
                        val identity=CountActionIdentity(scope.session,habit.id,habit.uuid,scope.capturedDeviceId,NextStructureMapper.writePlan(habit),null)
                        val frozen=history.copy(actionIdentity=identity,
                            completions=java.util.Collections.unmodifiableList(history.completions.toList()),
                            quantities=java.util.Collections.unmodifiableMap(history.quantities.toMap()),
                            policies=java.util.Collections.unmodifiableMap(history.policies.toMap()),
                            unknownDates=java.util.Collections.unmodifiableSet(history.unknownDates.toSet()))
                        val source=requested[uuid]?.let {id->frozen.completions.single {it.id==id}}
                        val day=source?.businessDate ?: frozen.today
                        val zone=source?.let {ZoneId.of(it.recordedTimezone)} ?: ZoneId.systemDefault()
                        // A new unstarted day has the original reader's effective plan
                        // rule, despite having no durable count_days row before a write.
                        val forSource=if(source==null)frozen else frozen.copy(today=day,todayPolicy=frozen.policies[day])
                        Card(habit,frozen,recurringFactSnapshot(habit,scope,null,forSource,null,day,zone,source))
                    }
                    require(tokens.isCurrentDisplayReadAuthority(scope,registered))
                    val revision=proofRevision;val publicationEpoch=gate.revision
                    View(java.util.Collections.unmodifiableMap(cards),factRevision,revision,presentationRevision) {
                        current();authority.checkLease(lease)
                        gate.revision==publicationEpoch && proofRevision==revision && LocalDate.now()==today
                    }
                }
                authority.checkLease(lease);currentCoroutineContext().ensureActive()
                publicationEscaped=true
                publish(view)
            }
        }
    }
    suspend fun pendingRequestIds():List<String> {
        var result:List<String>?=null
        readAndPublish {result=java.util.Collections.unmodifiableList(pending.values.sortedBy {it.id}.map {it.operationId})}
        return requireNotNull(result)
    }

    /** One exact completion claim, not a latest-fact lookup or a cached authorization.
     * The existing full cold count proof and confirmed incremental writes cover history.
     * Only the fixed metadata/absence/prefs profile is added; no writer receives this result.
     */
    suspend fun prepareCompletionPrompt(saved:Saved,source:WidgetFactSnapshot,
        publish:(G0CompletionPromptProof.Prepared?)->Unit) {
        // Strictly freeze caller-owned JSON before any suspension. The claim remains a
        // selector until authenticated against the current owner and live fact directory.
        val claimed=WidgetFactClaim.decode(source.claim.encode())
        val savedIdentity=saved.copy()
        require(source.habit.id==claimed.habitId && source.habit.uuid==claimed.habitUuid &&
            NextStructureMapper.writePlan(source.habit)==claimed.plan) { "G0_PROMPT_SOURCE_SNAPSHOT_CHANGED" }
        account {
            val lease=entryLeaseOwned()
            val proof=requireNotNull(completionPromptProof) { "G0_PROMPT_PROFILE_DISABLED" }
            authority.finalPermit(lease).use {
                val prepared=gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                    current();authority.checkLease(lease)
                    proof.requireCurrent(db,tokens,scope.session,index)
                    require(claimed.session()==scope.session && claimed.deviceId==scope.capturedDeviceId) { "FACT_WIDGET_STALE_ACCOUNT" }
                    require(savedIdentity.habitUuid==claimed.habitUuid && savedIdentity.factUuid==claimed.completionUuid &&
                        savedIdentity.habitUuid in admitted) { "G0_PROMPT_SAVED_SOURCE_CHANGED" }
                    val state=objects.getValue(savedIdentity.habitUuid)
                    val habit=state.habit
                    require(habit.id==claimed.habitId && habit.uuid==claimed.habitUuid &&
                        NextStructureMapper.writePlan(habit)==claimed.plan) { "FACT_WIDGET_PLAN_CHANGED" }
                    val operation=requireNotNull(operations[savedIdentity.requestId]) { "G0_PROMPT_SAVED_SOURCE_CHANGED" }
                    val origin=requireNotNull(origins[savedIdentity.requestId]) { "G0_PROMPT_SAVED_SOURCE_CHANGED" }
                    require(operation.operationId==savedIdentity.requestId && operation.entityUuid==savedIdentity.factUuid &&
                        operation.payload["activity_uuid"]==JsonPrimitive(habit.uuid) && origin.accountId==scope.session.authentication.userId &&
                        origin.serverInstanceId==scope.session.serverInstanceId && origin.syncEpoch==scope.session.syncEpoch) {
                        "G0_PROMPT_SAVED_SOURCE_CHANGED"
                    }
                    // A retained source alone is insufficient: the corresponding live fact
                    // must still exist under this exact object/id/UUID/date/timezone.
                    val fact=requireNotNull(state.history.completions.singleOrNull {
                        it.id==savedIdentity.factId && it.uuid==savedIdentity.factUuid && it.habitId==habit.id && it.habitUuid==habit.uuid &&
                            it.recordedLocalDate==claimed.date && it.recordedTimezone==claimed.timezone
                    }) { "COMPLETION_SOURCE_CHANGED" }
                    val today=LocalDate.now()
                    require(state.history.today==today) { "G0_COUNT_DATE_CHANGED" }
                    val date=LocalDate.parse(claimed.date);val zone=ZoneId.of(claimed.timezone)
                    val history=state.history.copy(today=date,todayPolicy=state.history.policies[date],
                        actionIdentity=CountActionIdentity(scope.session,habit.id,habit.uuid,scope.capturedDeviceId,NextStructureMapper.writePlan(habit),null),
                        completions=java.util.Collections.unmodifiableList(state.history.completions.toList()),
                        quantities=java.util.Collections.unmodifiableMap(state.history.quantities.toMap()),
                        policies=java.util.Collections.unmodifiableMap(state.history.policies.toMap()),
                        unknownDates=java.util.Collections.unmodifiableSet(state.history.unknownDates.toSet()))
                    val verified=recurringFactSnapshot(habit,scope,null,history,null,date,zone,fact)
                    require(verified.claim==claimed) { "G0_PROMPT_CLAIM_CHANGED" }
                    // In particular, no latest-source or completedToday requirement is added.
                    beforeCompletionPromptFinalForTest()
                    proof.requireCurrent(db,tokens,scope.session,index)
                    require(tokens.isCurrentDisplayReadAuthority(scope,registered)) { "G0_COUNT_AUTH_CHANGED" }
                    authority.checkLease(lease)
                    val revision=proofRevision;val publicationEpoch=gate.revision
                    proof.prepare(verified) {
                        current();authority.checkLease(lease)
                        gate.revision==publicationEpoch && proofRevision==revision && LocalDate.now()==today
                    }
                }
                // No UI/SQL from the callback. The caller synchronously checks its own page,
                // command and generation, and publishes memory only after real READ end.
                beforeCompletionPromptPublishForTest()
                authority.checkLease(lease);current();currentCoroutineContext().ensureActive()
                require(prepared==null || prepared.valid()) { "G0_PROMPT_PUBLICATION_CHANGED" }
                publish(prepared)
            }
        }
    }

    /** Recovery only observes the SAME command after a new complete cold proof. It
     * never retries a write; an unknown original owner remains permanently retired. */
    suspend fun recover(command:Command):Saved?=account {
        val lease=if(jointAuthority)entryLeaseOwned().also {
            require(command.session==scope.session && command.habitUuid in admitted)
        } else {
            authenticate();require(command.session==scope.session && command.habitUuid in admitted);authority.lease()
        }
        authority.finalPermit(lease).use {
            gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                current();val operation=operations[command.requestId]
                val result=if(operation==null) {
                    require(objects.values.none {state->state.history.completions.any {it.uuid==command.factUuid}});null
                } else {
                    require(operation.entityUuid==command.factUuid && operation.payload["activity_uuid"]==JsonPrimitive(command.habitUuid))
                    val fact=objects.getValue(command.habitUuid).history.completions.single {it.uuid==command.factUuid}
                    Saved(fact.id,fact.uuid,command.requestId,command.habitUuid)
                }
                require(tokens.isCurrentDisplayReadAuthority(scope,registered));result
            }
        }
    }

    private suspend fun rowHash(table:String,id:String)=requireNotNull(NextRequestSql.rowHash(db.openHelper.writableDatabase,table,"kind=? AND requestId=?",arrayOf(NEXT_OPERATION,id)))
    private fun insert(table:String,values:List<Any?>)=db.openHelper.writableDatabase.execSQL(
        G0DomainSqlCatalog.writes.keys.single {it.startsWith("INSERT OR ABORT INTO `$table`")},values.toTypedArray())
    private fun suppress(e:MutableList<G0DomainJournal.Change>,on:Boolean) {
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=${if(on)1 else 0} WHERE id=1")
        effect(e,"sync_control",listOf(1L,if(on)0L else 1L),listOf(1L,if(on)1L else 0L))
    }
    private fun effect(e:MutableList<G0DomainJournal.Change>,table:String,old:List<Any?>?,new:List<Any?>?) {
        e+=G0DomainJournal.Change(0,table,if(old==null)"INSERT" else if(new==null)"DELETE" else "UPDATE",old?.let(::cells),new?.let(::cells))
    }
    private fun cells(v:List<Any?>)=v.map {when(it) {
        null->G0DomainJournal.Cell(0,null);is String->G0DomainJournal.Cell(3,it);is Int->G0DomainJournal.Cell(1,it.toLong())
        is Long->G0DomainJournal.Cell(1,it);is Double->G0DomainJournal.Cell(2,java.lang.Double.doubleToRawLongBits(it))
        is ByteArray->G0DomainJournal.Cell(4,it.toList());else->error("G0_COUNT_NATIVE_TYPE")
    }}
    private fun native(c:G0DomainJournal.Cell):Any?=when(c.type) {2->java.lang.Double.longBitsToDouble(c.value as Long);4->(c.value as List<*>).map {it as Byte}.toByteArray();else->c.value}
    private fun dayValues(r:CountDayEntity)=listOf(r.habitId,r.habitUuid,r.localDate,r.targetValue,if(r.isCountdown)1L else 0L,
        r.firstEventUuid,r.originRequestId,r.originHash,r.planPredecessorId,r.planPredecessorOriginHash,
        r.planPredecessorDependencyHash,r.planQueueWatermark,r.capturedDeviceId,r.originKind)
    private fun factValues(r:CompletionEntity)=listOf(r.id,r.habitId,r.date,r.value,r.actualCompletedAt,r.uuid,r.habitUuid,r.createdAt,r.recordedTimezone,r.recordedLocalDate,r.timeMetadataSource,r.oneTimeAction,r.oneTimeExpectedVersion,r.oneTimeExpectedHeadEventUuid,r.oneTimeRevertsEventUuid)
    private fun queueValues(r:SyncOutboxEntity)=listOf(r.id,r.operationId,r.recordType,r.entityUuid,r.wireEntityUuid,r.action,r.referenceUuid,r.payloadJson,r.baseRevision,r.basePayloadJson,r.attemptedAt,r.attemptCount,r.lastError,r.errorCode,r.deadLetteredAt,r.createdAt)
    private fun originValues(r:NextRequestOriginEntity)=listOf(r.kind,r.requestId,r.queueId,r.protocol,r.accountId,r.serverInstanceId,r.syncEpoch,r.sourceHash,r.intentJson)
    private fun transmissionValues(r:NextTransmissionEntity)=listOf(r.kind,r.requestId,r.queueId,r.protocol,r.accountId,r.serverInstanceId,r.syncEpoch,r.deviceId,r.wireHash,r.wireBytes)
    private fun receiptValues(r:NextAcceptanceEntity)=listOf(r.kind,r.requestId,r.originHash,r.transmissionHash,r.resultHash,r.resultJson)
    private fun shadowValues(r:SyncEntityStateEntity)=listOf(r.entityType,r.entityUuid,r.revision,if(r.deleted)1L else 0L,r.payloadJson,r.payloadHash,r.updatedAt)
}

/** Separate opt-in SQL capability; countWrites alone must not admit day creation. */
internal object G0HouseholdCountSql {
    const val insertDay="INSERT OR ABORT INTO count_days (habitId,habitUuid,localDate,targetValue,isCountdown,firstEventUuid,originRequestId,originHash,planPredecessorId,planPredecessorOriginHash,planPredecessorDependencyHash,planQueueWatermark,capturedDeviceId,originKind) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
    const val bindDay="UPDATE count_days SET originRequestId=?,originHash=?,planQueueWatermark=?,capturedDeviceId=? WHERE habitId=? AND localDate=?"
    val writes=mapOf(insertDay to "VVVVVVVVVVVVVV",bindDay to "SSISIS")
}
