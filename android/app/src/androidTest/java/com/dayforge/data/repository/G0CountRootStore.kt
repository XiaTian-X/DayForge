package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.HistoryNodeSession

import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.domain.model.*
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.ActivityRateCalculator
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** G0 B3b only. A persisted authenticated Current and operation-local proofs replace
 * the full native index and complete CountHistory. The full original Store is solely
 * the initial issuer/oracle. No caller can install a supplied Current or writable root.
 * Fresh entry/final authority, exact native dependencies, actual journal and END remain.
 */
internal class G0CountRootStore(private val db:HabitDatabase,private val tokens:TokenManager,
    private val sessions:AccountSessionCoordinator,private val authority:G0AuthorityOwner,
    private val gate:G0DomainSqlGate,private val owner:G0RoomColdOwnerFactory,private val seal:G0CountRootSeal) {
    private lateinit var core:LocalCoreWriteAccess
    private lateinit var registered:LocalSyncAccess
    private lateinit var packet:G0CountRootSeal.Packet
    private lateinit var currentState:G0CountIncrementalBlocks.Current
    private var epoch=-1L
    private var ready=false
    @Volatile private var commandContext:Pair<LocalDataSession,Set<String>>?=null
    @Volatile private var retired=false
    var beforeColdFinalForTest:suspend ()->Unit={}
    var afterColdEndForTest:suspend ()->Unit={}
    var afterPrepareForTest:suspend ()->Unit={}
    var beforeFinalForTest:suspend ()->Unit={}
    var beforePublishForTest:suspend ()->Unit={}
    var commits=0;private set
    var lastSourceStats:G0CountBlockNodeSource.Stats?=null;private set
    class Command internal constructor(val requestId:String,val factUuid:String,val habitUuid:String,
        internal val session:LocalDataSession)
    data class Saved(val factId:Long,val factUuid:String,val requestId:String,val habitUuid:String)
    class View internal constructor(val day:G0CountBusinessBlocks.DayRead,val rootDigest:String,
        private val stillCurrent:()->Boolean) {fun valid()=runCatching(stillCurrent).getOrDefault(false)}
    init {require(gate.countRootWriteScope)}
    private fun current() {
        check(ready && !retired) {"G0_ROOT_WRITE_RECOVERY_REQUIRED"}
        gate.requireSealedConnection();check(epoch==gate.revision) {"G0_ROOT_WRITE_UNOBSERVED_COMMIT"}
    }
    private fun retire() {retired=true;commandContext=null;gate.invalidateConnection()}
    private suspend fun fresh(lease:G0AuthorityOwner.Lease) {
        authority.checkLease(lease)
        check(tokens.isCurrentDisplayReadAuthority(core,registered)) {"G0_ROOT_WRITE_AUTH_CHANGED"}
        authority.checkLease(lease)
    }
    private fun header():G0CountRootSeal.Packet {
        val sql=db.openHelper.writableDatabase
        NextRequestSql.requireOutboxEnabled(sql)
        sql.query(G0CountRootSql.readFencePresence).use {check(!it.moveToFirst()) {"G0_ROOT_REPLICA_FENCED"}}
        val marker=sql.query(G0CountRootSql.readMarker).use {checkNotNull(G0CountRootSql.captureMarker(it))}
        check(!marker.dirty) {"G0_ROOT_DIRTY"}
        val found=sql.query(G0CountRootSql.readSeal).use {checkNotNull(G0CountRootSql.captureSeal(it))}
        val root=sql.query(G0CountRootSql.readBoundedRoot).use {checkNotNull(G0CountRootSql.captureRootRow(it))}
        check(found.generation==marker.generation && root.binding==found.binding && root.root==found.root && root.revision==found.revision)
        return found
    }
    private fun sameHeader() {check(cells(header().headerValues())==cells(packet.headerValues())) {"G0_ROOT_WRITE_HEADER_CHANGED"}}
    private suspend fun <T> blocks(body:suspend (HistoryNodeSession.Lookup)->T):T {
        val context=currentCoroutineContext()
        val result=G0CountBlockNodeSource.withSource(db.openHelper.writableDatabase,
            gate.captureBlockReadBoundary(),checkCancelled=context::ensureActive) {body(it)}
        lastSourceStats=result.stats
        return result.value
    }
    suspend fun initialize(schema:List<List<String?>>)=account {
        check(!ready && !retired)
        try {
            val lease=authority.lease()
            core=checkNotNull(tokens.localCoreWriteAccess())
            registered=checkNotNull(tokens.localSyncAccess()).let {it.copy(capabilities=java.util.Collections.unmodifiableSet(it.capabilities.toSet()))}
            require("facts.append" in registered.capabilities && "sync.read" in registered.capabilities)
            val binding=G0CountBlockSql.binding(core,registered)
            authority.checkLease(lease)
            authority.finalPermit(lease).use {
                val completed=gate.ownedColdTransaction(db,schema) {
                    timed("root_integrity") {db.openHelper.writableDatabase.query(G0PersistentCheckpoint.INTEGRITY_SQL).use {
                        check(it.moveToFirst() && it.getString(0)=="ok" && !it.moveToNext()) {"G0_ROOT_INTEGRITY"}
                    }}
                    val found=timed("root_header_match") {header().also {check(seal.match(it,binding))}}
                    val loaded=blocks {G0CountIncrementalBlocks.readCurrent(binding,found.root,it)}
                    require(loaded.current.pending.none {it.inflight}) {"G0_ROOT_COLD_INFLIGHT_UNSUPPORTED"}
                    requireNativeCurrent(loaded)
                    beforeColdFinalForTest();fresh(lease);currentCoroutineContext().ensureActive()
                    (found to loaded.current).also {owner.validated(it)}
                }
                completed.end.requireCandidate(completed.value)
                afterColdEndForTest();authority.checkLease(lease);currentCoroutineContext().ensureActive()
                gate.sealCold(completed.end)
                packet=completed.value.first;currentState=completed.value.second;epoch=gate.revision;ready=true
                commandContext=core.session to java.util.Collections.unmodifiableSet(currentState.habits.keys.toSet())
            }
        } catch(failure:Throwable) {retire();owner.fail(failure)}
    }
    fun newCommand(habitUuid:String):Command {
        val context=checkNotNull(commandContext) {"G0_ROOT_WRITE_RECOVERY_REQUIRED"}
        gate.requireSealedConnection();require(habitUuid in context.second)
        return Command(UUID.randomUUID().toString(),UUID.randomUUID().toString(),habitUuid,context.first)
    }
    private class Changed:IllegalStateException("G0_ROOT_PREPARATION_CHANGED")
    private data class Prepared(val head:G0CountRootSeal.Packet,val lease:G0AuthorityOwner.Lease,
        val capture:G0CountIncrementalBlocks.Capture,val native:G0CountNativeRows.Witness,val habitRaw:List<G0DomainJournal.Cell>,
        val today:LocalDate,val zone:ZoneId,val now:Long)
    private suspend fun prepare(habitUuid:String?,requestId:String?=null,newFactUuid:String?=null,newRequestId:String?=null,expectedLease:G0AuthorityOwner.Lease?=null):Prepared {
        val lease=expectedLease ?: authority.lease()
        return account {
            current();fresh(lease)
            val now=System.currentTimeMillis();val zone=ZoneId.systemDefault();val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val dates=if(requestId==null) {
                val habit=currentState.habits.getValue(requireNotNull(habitUuid))
                ActivityRateCalculator.generateWindowCheckInDays(habit.schedule,habit.createdAt,now,zone)
                    .map {Instant.ofEpochMilli(it).atZone(zone).toLocalDate()}.toSet()
            } else emptySet()
            try {gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                current();sameHeader()
                val captured=blocks {G0CountIncrementalBlocks.capture(packet.binding,packet.root,it,habitUuid,today,dates,requestId,newFactUuid,newRequestId)}
                check(captured.current==currentState)
                val native=captureNative(captured,today,newFactUuid,newRequestId)
                Prepared(packet,lease,captured,native.first,native.second,today,zone,now)
            }} catch(failure:Throwable) {retire();throw failure}
        }
    }
    private fun unchanged(prepared:Prepared) {
        current();authority.checkLease(prepared.lease)
        check(LocalDate.now(prepared.zone)==prepared.today && ZoneId.systemDefault()==prepared.zone) {"G0_ROOT_DATE_CHANGED"}
    }
    private suspend fun <T> commit(before:Prepared,nextCurrent:G0CountIncrementalBlocks.Current,
        delta:()->G0CountIncrementalBlocks.Delta?,body:suspend (MutableList<G0DomainJournal.Change>)->T):T=account {
        current();authority.checkLease(before.lease)
        if(packet!==before.head)throw Changed()
        authority.finalPermit(before.lease).use {
            var staged:G0CountRootSeal.Packet?=null
            try {gate.transaction(db,G0DomainSqlGate.Phase.BUSINESS,committed={
                staged?.let {packet=it;currentState=nextCurrent;commits++}
                epoch=gate.revision
            }) {
                unchanged(before);sameHeader();before.native.verify(db.openHelper.writableDatabase)
                val effects=mutableListOf<G0DomainJournal.Change>()
                val result=body(effects)
                if(effects.isNotEmpty()) {
                    val caller=currentCoroutineContext()
                    G0CountNativeRows.requireIndexableSources(db.openHelper.writableDatabase,caller::ensureActive)
                    val change=requireNotNull(delta())
                    val update=timed("root_write_advance") {blocks {
                        G0CountIncrementalBlocks.advanceFromSource(packet.binding,packet.root,it,change,currentState,nextCurrent)
                    }}
                    val sql=db.openHelper.writableDatabase
                    blocks {lookup->
                        for((digest,bytes) in update.newBlocks.toSortedMap()) {
                            val existing=lookup.load(digest,HistoryBlockCodec.MAX_NODE_BYTES)
                            if(existing==null) {
                                val values=listOf(digest,bytes.toByteArray())
                                sql.execSQL(G0CountBlockSql.insertNode,values.toTypedArray());effect(effects,G0CountBlockSql.nodes,null,values)
                            } else check(existing==bytes)
                        }
                    }
                    val oldRoot=G0CountRootSql.RootRow(packet.binding,packet.root,packet.revision).values()
                    val newRoot=G0CountRootSql.RootRow(packet.binding,update.state.root,Math.addExact(packet.revision,1)).values()
                    sql.execSQL(G0CountBlockSql.updateRoot,(newRoot.drop(1)+listOf(packet.binding,packet.root.digest,packet.revision)).toTypedArray())
                    effect(effects,G0CountBlockSql.root,oldRoot,newRoot)
                    val marker=sql.query(G0CountRootSql.readMarker).use {checkNotNull(G0CountRootSql.captureMarker(it))}
                    check(marker.dirty && marker.generation==Math.addExact(packet.generation,effects.size.toLong()))
                    staged=timed("root_seal_prepare") {seal.prepare(packet.binding,update.state.root,packet.revision+1,marker.generation)}
                    val next=checkNotNull(staged)
                    sql.execSQL(G0CountRootSql.updateHeader,G0CountRootSql.updateValues(next,packet).toTypedArray())
                    sql.execSQL(G0CountRootSql.clearMarker,arrayOf(next.generation))
                } else check(nextCurrent==currentState && delta()==null)
                beforeFinalForTest()
                gate.certifyChanges {actual->
                    if(staged==null)check(actual.isEmpty()) else G0CountRootChanges.validate(
                        G0CountRootChanges.Boundary(cells(packet.headerValues()),cells(G0CountRootSql.Marker(packet.generation,false).values())),
                        cells(checkNotNull(staged).headerValues()),actual,effects)
                }
                gate.finalRead {unchanged(before);fresh(before.lease)}
                result
            }} catch(failure:Throwable) {retire();throw failure}
        }
    }
    private suspend fun <T> reprepare(body:suspend ()->T):T {
        repeat(3) {attempt->try {return body()} catch(e:Changed) {if(attempt==2)throw e}}
        error("G0_ROOT_UNREACHABLE")
    }
    private suspend fun <T> account(body:suspend ()->T):T {
        val trace=G0PhaseTrace.current.get();val start=trace?.now()
        return sessions.exclusive {
            if(start!=null)trace.record("root_account_wait",start,true)
            if(trace==null)body() else trace.stage("root_account_hold",body)
        }
    }
    private suspend fun <T> timed(name:String,body:suspend ()->T):T {
        val trace=G0PhaseTrace.current.get()
        return if(trace==null)body() else trace.stage(name,body)
    }

    suspend fun increase(habitUuid:String):Saved=increase(newCommand(habitUuid))
    suspend fun increase(command:Command):Saved=reprepare {
        require(isContractUuid(command.requestId) && isContractUuid(command.factUuid))
        val before=prepare(command.habitUuid,newFactUuid=command.factUuid,newRequestId=command.requestId)
        require(command.session==core.session)
        val captured=before.capture;val habit=captured.habit;val oldDay=captured.days.getValue(before.today)?.row
        require(captured.newFactAbsent==true && captured.newRequestAbsent==true) {"G0_COUNT_COMMAND_ALREADY_EXISTS"}
        require(captured.allocation.totalFacts<captured.current.maxFacts && captured.current.pending.size<captured.current.maxPending) {"G0_COUNT_CAPACITY_UNSUPPORTED"}
        val policy=oldDay?.policy ?: CountDayPolicy(habit.targetValue,habit.isCountdown)
        require(!policy.isCountdown)
        val fact=CompletionEntity(Math.addExact(captured.allocation.lastFactId,1),habit.id,before.today.atStartOfDay(before.zone).toInstant().toEpochMilli(),1,
            before.now,command.factUuid,habit.uuid,before.now,before.zone.id,before.today.toString())
        val queue=SyncOutboxEntity(Math.addExact(captured.allocation.lastQueueId,1),command.requestId,"completion",fact.uuid,fact.uuid,"upsert",habit.uuid,createdAt=before.now)
        val operation=SyncV2Operation(operationId=queue.operationId,entityType="activity_event",entityUuid=fact.uuid,action="upsert",
            payload=JsonObject(SyncV2Mapper.completion(fact,habit)+("count_policy" to policy.toJson())))
        val encoded=withContext(Dispatchers.Default) {
            val bytes=encodeSyncRequest(SyncV2Operation.serializer(),operation)
            require(decodeNextOperationIntent(bytes.toString(Charsets.UTF_8))==operation)
            decodeFrozenSyncRequest(encodeSyncRequest(NextSyncPushRequest.serializer(),
                NextSyncPushRequest(requireNotNull(registered.deviceId),listOf(operation))),NextSyncPushRequest.serializer())
            bytes.toString(Charsets.UTF_8)
        }
        val rate=ActivityRateCalculator.calculate(habit.schedule,habit.createdAt,
            captured.days.filterValues {it!=null && it.quantity>0}.keys.toList()+before.today,before.now,before.zone)
        val nextHabit=habit.copy(activityRate=rate,activityRateUpdatedAt=before.now)
        val nextCurrent=captured.current.copy(habits=captured.current.habits+(habit.uuid to nextHabit),
            pending=captured.current.pending+G0CountIncrementalBlocks.Pending(command.requestId,fact.uuid,queue.id,false))
        val birth=if(oldDay==null)CountDayEntity(habit.id,habit.uuid,before.today.toString(),policy.targetValue,policy.isCountdown,fact.uuid) else null
        lateinit var origin:NextRequestOriginEntity;var originHash="";var boundDay:CountDayEntity?=null
        afterPrepareForTest()
        commit(before,nextCurrent,{
            G0CountIncrementalBlocks.Delta(null,fact,null,G0CountBusinessBlockCodec.Source(origin,originHash,null,null,null,queue,null),
                oldDay,boundDay ?: requireNotNull(oldDay),nextHabit)
        }) {effects->
            val sql=db.openHelper.writableDatabase
            if(birth!=null) {sql.execSQL(G0HouseholdCountSql.insertDay,dayValues(birth).toTypedArray());effect(effects,"count_days",null,dayValues(birth))}
            suppress(effects,true)
            sql.execSQL(G0CountSql.insert,factValues(fact).toTypedArray());effect(effects,"completions",null,factValues(fact))
            val oldHabit=before.habitRaw.map(::native)
            val newHabit=oldHabit.toMutableList().apply {
                set(G0DomainSqlCatalog.columns.getValue("habits").indexOf("activityRate"),rate)
                set(G0DomainSqlCatalog.columns.getValue("habits").indexOf("activityRateUpdatedAt"),before.now)
            }
            sql.execSQL(G0CountSql.rate,arrayOf<Any>(rate,before.now,habit.id));effect(effects,"habits",oldHabit,newHabit)
            suppress(effects,false)
            insert("sync_outbox",queueValues(queue));effect(effects,"sync_outbox",null,queueValues(queue))
            origin=NextRequestOriginEntity(NEXT_OPERATION,queue.operationId,queue.id,5,core.session.authentication.userId,
                core.session.serverInstanceId,core.session.syncEpoch,NextRequestSql.sourceHash(queue),encoded)
            insert("next_request_origins",originValues(origin));effect(effects,"next_request_origins",null,originValues(origin))
            originHash=rowHash("next_request_origins",queue.operationId)
            if(birth!=null) {
                val bound=birth.copy(originRequestId=queue.operationId,originHash=originHash,planQueueWatermark=queue.id-1,capturedDeviceId=core.capturedDeviceId)
                sql.execSQL(G0HouseholdCountSql.bindDay,arrayOf<Any?>(bound.originRequestId,bound.originHash,bound.planQueueWatermark,
                    bound.capturedDeviceId,bound.habitId,bound.localDate))
                effect(effects,"count_days",dayValues(birth),dayValues(bound));boundDay=bound
            }
            Saved(fact.id,fact.uuid,queue.operationId,habit.uuid)
        }
    }

    // Resolve R→E→object inside the same immutable-root capture as the request. One
    // fresh entry check and one READ; no separate lookup/decryption before preparation.
    private suspend fun prepareRequest(id:String):Prepared=prepare(null,requestId=id)
    suspend fun send(http:NextSyncHttp,id:String):NextCoreDelivery<NextSyncPushResponse>?=http.session(registered) {channel->
        val prepared=reprepare {
            val before=prepareRequest(id);val request=requireNotNull(before.capture.request);val source=request.source
            require(source.pending!=null && source.receipt==null)
            if(source.transmission==null && id!=request.day.row.originRequestId && request.firstSource.receipt==null)
                rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
            val origin=source.origin;val operation=decodeNextOperationIntent(origin.intentJson)
            val bytes=source.transmission?.wireBytes?.copyOf() ?: encodeSyncRequest(NextSyncPushRequest.serializer(),
                NextSyncPushRequest(requireNotNull(registered.deviceId),listOf(operation)))
            require(validateNextOperationEnvelope(origin.intentJson,bytes,requireNotNull(registered.deviceId))==operation)
            val transmission=source.transmission ?: NextTransmissionEntity(NEXT_OPERATION,id,origin.queueId,5,origin.accountId,
                requireNotNull(registered.session.serverInstanceId),requireNotNull(registered.session.syncEpoch),
                requireNotNull(registered.deviceId),nextRequestHash(bytes),bytes)
            val nextCurrent=before.capture.current.copy(pending=before.capture.current.pending.map {if(it.requestId==id)it.copy(inflight=true) else it})
            var hash=source.transmissionHash
            afterPrepareForTest()
            commit(before,nextCurrent,{
                if(source.transmission!=null)null else G0CountIncrementalBlocks.Delta(request.fact,request.fact,source,
                    G0CountBusinessBlockCodec.Source(source.origin,source.originHash,transmission,checkNotNull(hash),source.receipt,source.pending,source.shadow),request.day.row,request.day.row,before.capture.habit)
            }) {effects->
                if(source.transmission==null) {insert("next_transmissions",transmissionValues(transmission));effect(effects,"next_transmissions",null,transmissionValues(transmission))}
                hash=rowHash("next_transmissions",id)
            }
            Triple(bytes,before.lease,checkNotNull(hash))
        }
        val response=channel.pushFrozen(prepared.first.copyOf())
        account {
            current();fresh(prepared.second)
            gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                current();sameHeader();authority.checkLease(prepared.second)
                val request=blocks {G0CountIncrementalBlocks.readRequest(packet.binding,packet.root,it,id)}
                require(request.source.transmissionHash==prepared.third && request.source.pending!=null)
                val caller=currentCoroutineContext()
        val native=G0CountNativeRows.Session(db.openHelper.writableDatabase,caller::ensureActive)
                native.requireSource(request.source,request.fact)
            }
        }
        NextCoreDelivery(registered,id,response,prepared.third)
    }
    suspend fun acknowledge(delivery:NextCoreDelivery<NextSyncPushResponse>):NextOperationAcceptance {
        val response=decodeFrozenSyncRequest(encodeSyncRequest(NextSyncPushResponse.serializer(),delivery.result),NextSyncPushResponse.serializer())
        require(delivery.access==registered && delivery.challengeMetadata==null && delivery.configImportProof==null)
        return reprepare {
            val before=prepareRequest(delivery.requestId);val request=requireNotNull(before.capture.request);val source=request.source
            require(source.transmissionHash==delivery.transmissionProof && source.transmission!=null)
            val operation=decodeNextOperationIntent(source.origin.intentJson)
            require(validateNextOperationEnvelope(source.origin.intentJson,checkNotNull(source.transmission).wireBytes,requireNotNull(registered.deviceId))==operation)
            val result=response.results.single();val change=NextOrdinaryResultMapper.validate(operation,result,requireNotNull(registered.deviceId))
            val normalized=result.copy(status="applied");val resultBytes=encodeSyncRequest(NextSyncOperationResult.serializer(),normalized)
            val resultJson=resultBytes.toString(Charsets.UTF_8);val existing=source.receipt;val replay=existing!=null
            if(replay)require(decodeFrozenSyncRequest(existing!!.resultJson.toByteArray(),NextSyncOperationResult.serializer())==normalized && source.pending==null)
            else require(source.pending!=null && source.shadow==null)
            val old=request.fact;val next=NextCommonFactMapper.completion(change,before.capture.habit,old)
            require(old.copy(createdAt=next.createdAt)==next)
            require(NextCommonFactMapper.countPolicy(change.payload)==request.day.row.policy)
            NextCommonFactProof.requireOriginalPayload(operation.payload,change,requireNotNull(registered.deviceId))
            val shadow=SyncEntityStateEntity("activity_event",old.uuid,change.revision,payloadJson=change.payload.toString(),payloadHash=syncPayloadHash(change.payload.toString()))
            val receipt=existing ?: NextAcceptanceEntity(NEXT_OPERATION,delivery.requestId,source.originHash,checkNotNull(source.transmissionHash),nextRequestHash(resultBytes),resultJson)
            val nextCurrent=before.capture.current.copy(pending=before.capture.current.pending.filter {it.requestId!=delivery.requestId})
            afterPrepareForTest()
            commit(before,nextCurrent,{
                if(replay)null else G0CountIncrementalBlocks.Delta(old,next,source,G0CountBusinessBlockCodec.Source(source.origin,source.originHash,source.transmission,source.transmissionHash,receipt,null,shadow),
                    request.day.row,request.day.row,before.capture.habit)
            }) {effects->
                if(!replay) {
                    suppress(effects,true)
                    val sql=db.openHelper.writableDatabase
                    sql.execSQL(G0CountSql.acceptedTime,arrayOf(next.createdAt,next.id));effect(effects,"completions",factValues(old),factValues(next))
                    val shadowSql=G0DomainSqlCatalog.writes.keys.single {it.startsWith("INSERT OR REPLACE INTO `sync_entity_state`")}
                    sql.execSQL(shadowSql,shadowValues(shadow).toTypedArray());effect(effects,"sync_entity_state",null,shadowValues(shadow))
                    suppress(effects,false)
                    insert("next_acceptances",receiptValues(receipt));effect(effects,"next_acceptances",null,receiptValues(receipt))
                    sql.execSQL("DELETE FROM sync_outbox WHERE id=?",arrayOf(checkNotNull(source.pending).id));effect(effects,"sync_outbox",queueValues(checkNotNull(source.pending)),null)
                }
                if(replay)NextOperationAcceptance.REPLAYED else NextOperationAcceptance.COMMITTED
            }
        }
    }

    suspend fun pendingRequestIds():List<String> {
        var result:List<String> = emptyList()
        val lease=authority.lease()
        account {
            current();fresh(lease)
            authority.finalPermit(lease).use {
                gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                    current();sameHeader();val loaded=blocks {G0CountIncrementalBlocks.readCurrent(packet.binding,packet.root,it)}
                    check(loaded.current==currentState);requireNativeCurrent(loaded)
                    result=java.util.Collections.unmodifiableList(loaded.current.pending.map {it.requestId})
                    fresh(lease)
                }
                authority.checkLease(lease);currentCoroutineContext().ensureActive()
            }
        }
        return result
    }
    suspend fun readDay(habitUuid:String,date:LocalDate,publish:(View)->Unit) {
        val lease=authority.lease()
        data class Read(val head:G0CountRootSeal.Packet,val value:G0CountBusinessBlocks.DayRead,val today:LocalDate,val zone:ZoneId)
        val captured=account {
            current();fresh(lease)
            val today=LocalDate.now();val zone=ZoneId.systemDefault()
            try {gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                current();sameHeader()
                Read(packet,blocks {G0CountIncrementalBlocks.readDayFromSource(packet.binding,packet.root,it,habitUuid,date,today)},today,zone)
            }} catch(failure:Throwable) {retire();throw failure}
        }
        beforePublishForTest()
        account {
            fun unchanged() {
                current();authority.checkLease(lease)
                check(packet===captured.head && LocalDate.now()==captured.today && ZoneId.systemDefault()==captured.zone) {"G0_ROOT_PUBLICATION_CHANGED"}
            }
            unchanged()
            authority.finalPermit(lease).use {
                try {gate.transaction(db,G0DomainSqlGate.Phase.READ) {unchanged();sameHeader();fresh(lease)}}
                catch(failure:Throwable) {retire();throw failure}
                unchanged();currentCoroutineContext().ensureActive()
                publish(View(captured.value,packet.root.digest) {unchanged();true})
            }
        }
    }
    /** Stable identity read only. The unknown owner stays retired; a newly authenticated
     * owner either locates the original command or proves its related native absence. */
    suspend fun recover(command:Command):Saved?=reprepare {
        val prepared=prepare(command.habitUuid,command.requestId,command.factUuid,command.requestId)
        require(command.session==core.session)
        val request=prepared.capture.request
        val result=if(request==null) {
            require(prepared.capture.newFactAbsent==true && prepared.capture.newRequestAbsent==true);null
        } else {
            require(request.fact.uuid==command.factUuid && request.source.origin.requestId==command.requestId && request.fact.habitUuid==command.habitUuid)
            Saved(request.fact.id,request.fact.uuid,command.requestId,command.habitUuid)
        }
        account {
            current();authority.checkLease(prepared.lease)
            if(packet!==prepared.head)throw Changed()
            authority.finalPermit(prepared.lease).use {
                try {gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                    unchanged(prepared);sameHeader();prepared.native.verify(db.openHelper.writableDatabase)
                    fresh(prepared.lease)
                }} catch(failure:Throwable) {retire();throw failure}
                unchanged(prepared);currentCoroutineContext().ensureActive();result
            }
        }
    }
    private suspend fun requireNativeCurrent(loaded:G0CountIncrementalBlocks.Loaded):G0CountNativeRows.Session {
        val caller=currentCoroutineContext()
        val native=G0CountNativeRows.Session(db.openHelper.writableDatabase,caller::ensureActive)
        native.requireCurrent(loaded.current.habits.values.toList(),loaded.pending.map {checkNotNull(it.source.pending)},
            loaded.allocation.lastFactId,loaded.allocation.lastQueueId,loaded.current.maxPending)
        for(request in loaded.pending) {
            native.requireSource(request.source,request.fact)
            native.requireSource(request.firstSource,request.firstFact)
            native.requireDayRow(request.day.row)
        }
        return native
    }
    private suspend fun captureNative(captured:G0CountIncrementalBlocks.Capture,today:LocalDate,
        newFactUuid:String?,newRequestId:String?):Pair<G0CountNativeRows.Witness,List<G0DomainJournal.Cell>> {
        val native=requireNativeCurrent(G0CountIncrementalBlocks.Loaded(captured.current,captured.allocation,captured.pending))
        val habitRow=native.requireHabit(captured.habit)
        for((date,day) in captured.days) {
            native.requireDay(captured.habit.id,captured.habit.uuid,date.toString(),day?.row,captured.dayFactsByDate.getValue(date))
            native.requireDaySources(captured.habit.uuid,date.toString(),captured.daySourcesByDate.getValue(date))
        }
        if(captured.firstSource!=null)native.requireSource(captured.firstSource,checkNotNull(captured.firstFact))
        captured.request?.let {request->
            native.requireSource(request.source,request.fact);native.requireSource(request.firstSource,request.firstFact)
            native.requireDayRow(request.day.row)
        }
        if(newFactUuid!=null && newRequestId!=null && captured.request==null)
            native.requireAbsent(Math.addExact(captured.allocation.lastFactId,1L),newFactUuid,
                Math.addExact(captured.allocation.lastQueueId,1L),newRequestId)
        check(today in captured.days)
        return native.freeze() to habitRow
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
