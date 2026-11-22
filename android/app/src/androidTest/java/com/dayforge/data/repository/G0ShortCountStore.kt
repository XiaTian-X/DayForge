package com.dayforge.data.repository

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

/** G0 only: an existing, accepted plain COUNT day. No first-day, undo, tracking change,
 * challenge, timer, import, foreign fact or structural writer is admitted. The original
 * writer/reader/ACK remain the differential oracle. No production caller uses this owner.
 *
 * Source rows are authoritative; this process-local graph is derived. A verified cold
 * snapshot plus the sealed SQL journal covers membership AND absence. Only exact admitted
 * mutations advance it, after actual COMMIT. Unknown outcomes retire the entire owner.
 */
internal class G0ShortCountStore(private val db:HabitDatabase, private val tokens:TokenManager,
    private val sessions:AccountSessionCoordinator, private val authority:G0AuthorityOwner,
    private val gate:G0DomainSqlGate, private val original:NextCoreRequestStore,
    private val maxFacts:Int=128, private val jointAuthority:Boolean=false) {
    // Explicit G0 admission ceiling, below the original 10,000-source/shadow guards.
    // The smaller injectable ceiling exercises the REAL owner boundary without forging history.
    init {require(maxFacts in 1..128)}
    private lateinit var scope:LocalCoreWriteAccess
    private lateinit var registered:LocalSyncAccess
    private lateinit var habit:HabitEntity
    private lateinit var history:CountHistory
    private lateinit var index:G0DomainProofIndex
    private var epoch=0L
    private var ready=false
    private var poisoned=false
    private var lastFactId=0L
    private var lastQueueId=0L
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
    var afterPrepareForTest:suspend ()->Unit={}
    var afterSendPrepareForTest:suspend ()->Unit={}
    var beforeFinalForTest:suspend ()->Unit={}
    var afterEntryAuthenticationForTest:suspend ()->Unit={}

    class Command internal constructor(val requestId:String,val factUuid:String,
        internal val session:LocalDataSession,internal val habitUuid:String)
    fun newCommand():Command {current();return Command(UUID.randomUUID().toString(),UUID.randomUUID().toString(),scope.session,habit.uuid)}
    data class Saved(val factId:Long,val factUuid:String,val requestId:String)
    class View internal constructor(val habit:HabitEntity,val history:CountHistory,val followUp:WidgetFactSnapshot,
        val factRevision:Long,val proofRevision:Long,val presentationRevision:Long,private val stillCurrent:()->Boolean) {
        fun valid()=runCatching(stillCurrent).getOrDefault(false)
    }

    suspend fun initialize(expected:HabitEntity,schema:List<List<String?>>)=account {
        check(!ready && !poisoned)
        try {
        val lease=authority.lease()
        scope=requireNotNull(tokens.localCoreWriteAccess());registered=requireNotNull(tokens.localSyncAccess()).let {it.copy(capabilities=java.util.Collections.unmodifiableSet(it.capabilities.toSet()))}
        require(registered.session==scope.session && registered.deviceId==scope.capturedDeviceId &&
            registered.capabilities==scope.capabilities && "facts.append" in registered.capabilities && "sync.read" in registered.capabilities)
        authority.finalPermit(lease).use {
            val sql=db.openHelper.writableDatabase
            sql.execSQL("PRAGMA recursive_triggers=ON")
            db.withTransaction {
                // Closed profile: all side tables are accounted for, not just rows found by UUID.
                NextChallengeStore(db).requirePlainInTransaction()
                val empty=G0DomainSqlCatalog.columns.keys-setOf("habits","metrics","completions","count_days",
                    "sync_outbox","sync_control","next_request_origins","next_transmissions","next_acceptances","sync_entity_state")
                for(table in empty) require(sql.query("SELECT * FROM main.`$table`").use { !it.moveToFirst() }) { "G0_COUNT_UNSUPPORTED_SCOPE:$table" }
                db.metricDao().getAllMetricsOnce() // Preserve the original ACK's typed metric decode dependency.
                val all=db.habitDao().getAllHabitsOnce()
                require(all.size==1 && all.single()==expected && expected.habitType==HabitType.COUNTING &&
                    expected.appearance!=null && expected.isActive && !expected.isCountdown && expected.completionPolicy=="recurring" &&
                    expected.parentHabitId==null) { "G0_COUNT_UNSUPPORTED_SCOPE" }
                habit=expected.copy(schedule=(expected.schedule as? com.dayforge.data.model.HabitSchedule.Weekly)?.let {
                    it.copy(daysOfWeek=java.util.Collections.unmodifiableList(it.daysOfWeek.toList()))
                } ?: expected.schedule)
                history=CountHistoryReader(db,tokens,sessions).readInTransaction(habit,LocalDate.now())
                require(history.roundHead==null && history.unknownDates.isEmpty() && history.completions.isNotEmpty())
                require(history.completions.size<=maxFacts) {"G0_COUNT_CAPACITY_UNSUPPORTED"}
                require(db.completionDao().getAllCompletionsOnce().toSet()==history.completions.toSet())
                val days=NextCountDayStore(db).readForHabit(habit)
                require(days.map { it.localDate }.toSet()==history.policies.keys.map { it.toString() }.toSet())
                require(days.all { it.originRequestId!=null && it.capturedDeviceId==registered.deviceId && it.planPredecessorId==null })
                require(history.today in history.policies) { "G0_COUNT_EXISTING_DAY_REQUIRED" }
                val queues=db.syncOutboxDao().getAll()+db.syncOutboxDao().getDeadLetters()
                val ids=sql.query("SELECT * FROM main.`next_request_origins`").use { c->buildList { while(c.moveToNext())add(c.getString(c.getColumnIndexOrThrow("requestId"))) } }
                val seenFacts=hashSetOf<String>()
                for(id in ids) {
                    val origin=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,id))
                    require(origin.kind==NEXT_OPERATION && origin.protocol==5 && origin.accountId==scope.session.authentication.userId &&
                        origin.serverInstanceId==scope.session.serverInstanceId && origin.syncEpoch==scope.session.syncEpoch)
                    require(roundOperationIntent(origin.intentJson)==null && countPlanFrontier(origin.intentJson)==null)
                    val op=decodeNextOperationIntent(origin.intentJson);validateNextSyncOperation(op)
                    require(op.operationId==id && op.entityType=="activity_event" && op.action=="upsert" &&
                        op.payload["event_type"]==JsonPrimitive("count_snapshot") && op.payload["activity_uuid"]==JsonPrimitive(habit.uuid) &&
                        seenFacts.add(op.entityUuid) && history.completions.any { it.uuid==op.entityUuid })
                    origins[id]=origin;operations[id]=op
                    originHashes[id]=rowHash("next_request_origins",id)
                    val receipt=db.nextRequestDao().acceptance(NEXT_OPERATION,id)
                    val transmission=db.nextRequestDao().transmission(NEXT_OPERATION,id)
                    if(receipt!=null) {
                        original.requireAcceptedCountInTransaction(registered,id)
                        receipts[id]=receipt;sent[id]=requireNotNull(transmission).copy(wireBytes=transmission.wireBytes.copyOf())
                        transmissionHashes[id]=rowHash("next_transmissions",id)
                        shadows[op.entityUuid]=requireNotNull(db.syncOutboxDao().getState("activity_event",op.entityUuid))
                    } else {
                        // Sending is implemented here, but cold resumption of an in-flight source
                        // needs its own complete matrix. Fail closed rather than infer it.
                        require(transmission==null) { "G0_COUNT_COLD_INFLIGHT_UNSUPPORTED" }
                        val q=queues.single { it.operationId==id }
                        require(q.id==origin.queueId && q.recordType=="completion" && q.entityUuid==op.entityUuid &&
                            q.wireEntityUuid==op.entityUuid && q.referenceUuid==habit.uuid && q.action=="upsert" &&
                            q.payloadJson==null && q.baseRevision==null && q.basePayloadJson==null && q.attemptedAt==null &&
                            q.attemptCount==0 && q.lastError==null && q.errorCode==null && q.deadLetteredAt==null)
                        require(NextRequestSql.rowHash(sql,"sync_outbox","id=?",arrayOf(q.id))==origin.sourceHash)
                        pending[id]=q
                    }
                }
                require(seenFacts==history.completions.map { it.uuid }.toSet())
                require(queues.toSet()==pending.values.toSet())
                fun members(table:String,column:String)=sql.query("SELECT * FROM main.`$table`").use { c->buildList { while(c.moveToNext())add(c.getString(c.getColumnIndexOrThrow(column))) } }
                require(members("next_transmissions","requestId").sorted()==sent.keys.sorted() && members("next_acceptances","requestId").sorted()==receipts.keys.sorted())
                require(members("sync_entity_state","entityUuid").sorted()==shadows.keys.sorted() &&
                    db.syncOutboxDao().getStatesForType("activity_event").size==shadows.size)
                for(day in days) original.requireAcceptedCountInTransaction(registered,requireNotNull(day.originRequestId))
                NextRequestSql.requireOutboxEnabled(sql)
                lastFactId=history.completions.maxOf { it.id }
                sql.query("SELECT * FROM main.`sqlite_sequence`").use { c->while(c.moveToNext()) {
                    require(c.getType(0)==android.database.Cursor.FIELD_TYPE_STRING && c.getType(1)==android.database.Cursor.FIELD_TYPE_INTEGER && c.getLong(1)>=0)
                    if(c.getString(0)=="completions") {require(c.getLong(1)>=lastFactId);lastFactId=c.getLong(1)}
                    if(c.getString(0)=="sync_outbox")lastQueueId=c.getLong(1)
                } }
                require(lastQueueId >= (origins.values.maxOfOrNull {it.queueId} ?: 0L)) {"G0_COUNT_SEQUENCE_CHANGED"}
                index=G0DomainProofIndex.captureVerifiedBaseline(sql)
                gate.installJournal(schema)
                require(tokens.isCurrentDisplayReadAuthority(scope,registered))
            }
            authority.checkLease(lease);gate.seal();epoch=gate.revision;ready=true
        }
        } catch(error:Throwable) {poisoned=true;throw error}
    }

    private fun current() {
        check(ready && !poisoned) { "G0_COUNT_RECOVERY_REQUIRED" }
        gate.requireSealedConnection();check(epoch==gate.revision) { "G0_COUNT_UNOBSERVED_COMMIT" }
    }
    private suspend fun authenticate() {
        current()
        // The existing predicate checks BOTH captured authorities using one fresh
        // Preferences value and one real access decryption. It is never a cached grant.
        require(if(jointAuthority)tokens.isCurrentDisplayReadAuthority(scope,registered)
            else tokens.localCoreWriteAccess()==scope && tokens.isCurrentSyncAuthority(registered)) { "G0_COUNT_AUTH_CHANGED" }
    }
    private suspend fun entryLease():G0AuthorityOwner.Lease {
        current()
        val lease=if(jointAuthority)authority.lease() else null
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
    private data class Preparation(val first:HabitEntity,val second:CountHistory,val third:G0DomainProofIndex.Witness,
        val factId:Long,val queueId:Long)
    private data class Request(val witness:G0DomainProofIndex.Witness,val origin:NextRequestOriginEntity,
        val operation:SyncV2Operation,val transmission:NextTransmissionEntity?,val receipt:NextAcceptanceEntity?,
        val queue:SyncOutboxEntity?,val shadow:SyncEntityStateEntity?,val habit:HabitEntity,val history:CountHistory,
        val originHash:String,val transmissionHash:String?)
    // Called only while owning the account mutex. No mutable map is read during lock-free work.
    private fun request(id:String):Request {
        current();val op=operations.getValue(id)
        return Request(witness(),origins.getValue(id),op,sent[id]?.let {it.copy(wireBytes=it.wireBytes.copyOf())},
            receipts[id],pending[id],shadows[op.entityUuid],habit,history,originHashes.getValue(id),transmissionHashes[id])
    }
    private fun witness()=index.witness(G0DomainSqlCatalog.columns.keys.mapTo(linkedSetOf()) { G0DomainProofIndex.Dependency.Table(it) })
    private suspend fun <T> commit(lease:G0AuthorityOwner.Lease,witness:G0DomainProofIndex.Witness,
        promote:()->Unit,body:suspend (MutableList<G0DomainJournal.Change>)->T):T=account {
        current();authority.checkLease(lease)
        if(!index.isCurrent(witness))throw PreparationChanged()
        authority.finalPermit(lease).use {
            lateinit var advancement:G0DomainProofIndex.Advancement
            try {
                gate.transaction(db,G0DomainSqlGate.Phase.BUSINESS,committed={ actual ->
                    index.committed(advancement,actual);epoch=gate.revision;promote()
                }) {
                    current();authority.checkLease(lease);index.requireCurrent(witness)
                    val expected=mutableListOf<G0DomainJournal.Change>()
                    val result=body(expected)
                    beforeFinalForTest()
                    gate.certifyChanges { actual ->
                        check(actual.map { it.copy(sequence=0) }==expected) { "G0_COUNT_UNEXPECTED_EFFECT" }
                        advancement=index.prepare(actual)
                    }
                    gate.finalRead { authenticate() }
                    result
                }
            } catch(error:Throwable) { poisoned=true;index.invalidate();throw error }
        }
    }

    // Diagnostic wrapper only: preserve every original account critical section in place.
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
    suspend fun increase(command:Command=newCommand()):Saved=reprepare {increaseOnce(command)}
    private suspend fun increaseOnce(command:Command):Saved {
        val lease=entryLease()
        val before=account {
            current();require(command.session==scope.session && command.habitUuid==habit.uuid)
            require(isContractUuid(command.requestId) && isContractUuid(command.factUuid) && command.requestId !in origins &&
                history.completions.none {it.uuid==command.factUuid}) {"G0_COUNT_COMMAND_ALREADY_EXISTS"}
            require(history.completions.size<maxFacts) {"G0_COUNT_CAPACITY_UNSUPPORTED"}
            Preparation(habit,history,witness(),Math.addExact(lastFactId,1),Math.addExact(lastQueueId,1))
        }
        val zone=ZoneId.systemDefault();val now=System.currentTimeMillis();val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        require(today==before.second.today && before.second.policies[today]!=null) { "G0_COUNT_EXISTING_DAY_REQUIRED" }
        val policy=before.second.policies.getValue(today);require(!policy.isCountdown)
        val fact=CompletionEntity(before.factId,before.first.id,today.atStartOfDay(zone).toInstant().toEpochMilli(),1,
            now,command.factUuid,before.first.uuid,now,zone.id,today.toString())
        val queue=SyncOutboxEntity(before.queueId,command.requestId,"completion",fact.uuid,fact.uuid,"upsert",before.first.uuid,createdAt=now)
        val operation=SyncV2Operation(operationId=queue.operationId,entityType="activity_event",entityUuid=fact.uuid,
            action="upsert",payload=JsonObject(SyncV2Mapper.completion(fact,before.first)+("count_policy" to policy.toJson())))
        val encoded=withContext(Dispatchers.Default) {
            val bytes=encodeSyncRequest(SyncV2Operation.serializer(),operation)
            require(decodeNextOperationIntent(bytes.toString(Charsets.UTF_8))==operation)
            decodeFrozenSyncRequest(encodeSyncRequest(NextSyncPushRequest.serializer(),NextSyncPushRequest(requireNotNull(registered.deviceId),listOf(operation))),NextSyncPushRequest.serializer())
            bytes.toString(Charsets.UTF_8)
        }
        val rate=ActivityRateCalculator.calculate(before.first.schedule,before.first.createdAt,(before.second.quantities.keys+today).toList(),now,zone)
        val nextHabit=before.first.copy(activityRate=rate,activityRateUpdatedAt=now)
        // Finish arithmetic and immutable result construction BEFORE any durable write.
        val nextQuantity=Math.addExact(before.second.quantities[today] ?: 0L,1L)
        val order=compareByDescending<CompletionEntity> {it.recordedLocalDate}
            .thenByDescending {it.actualCompletedAt ?: it.date}.thenByDescending {it.id}
        val ordered=before.second.completions.toMutableList()
        val position=ordered.binarySearch(fact,order)
        check(position<0);ordered.add(-position-1,fact)
        val nextHistory=before.second.copy(completions=ordered.toList(),quantities=before.second.quantities+(today to nextQuantity))
        lateinit var origin:NextRequestOriginEntity
        var hash=""
        afterPrepareForTest()
        return commit(lease,before.third,{
            habit=nextHabit;history=nextHistory
            origins[queue.operationId]=origin;originHashes[queue.operationId]=hash;operations[queue.operationId]=operation
            pending[queue.operationId]=queue;lastFactId=fact.id;lastQueueId=queue.id
            factRevision++;proofRevision++;presentationRevision++
        }) { effects ->
            check(ZoneId.systemDefault()==zone && LocalDate.now(zone)==today) { "G0_COUNT_DATE_CHANGED" }
            val sql=db.openHelper.writableDatabase
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
                scope.session.serverInstanceId,scope.session.syncEpoch,
                NextRequestSql.sourceHash(queue),encoded)
            insert("next_request_origins",originValues(origin));effect(effects,"next_request_origins",null,originValues(origin))
            hash=rowHash("next_request_origins",queue.operationId)
            Saved(fact.id,fact.uuid,queue.operationId)
        }
    }

    suspend fun send(http:NextSyncHttp,id:String):NextCoreDelivery<NextSyncPushResponse>? {
        val trace=G0PhaseTrace.current.get();val started=trace?.now()
        return http.session(registered) { channel ->
        if(started!=null)trace.record("count_send_session_ready",started,true)
        // Retry ONLY the typed pre-transaction witness conflict. No network call is
        // inside this scope; a committed/unknown transmission is never resubmitted here.
        val prepared=timed("count_send_prepare_commit") { reprepare {
            val lease=entryLease()
            val before=account { current();require(id in pending && id !in receipts);request(id) }
            val origin=before.origin;val op=before.operation
            val bytes=before.transmission?.wireBytes?.copyOf() ?: encodeSyncRequest(NextSyncPushRequest.serializer(),NextSyncPushRequest(requireNotNull(registered.deviceId),listOf(op)))
            require(validateNextOperationEnvelope(origin.intentJson,bytes,requireNotNull(registered.deviceId))==op)
            val transmission=before.transmission ?: NextTransmissionEntity(NEXT_OPERATION,id,origin.queueId,5,origin.accountId,
                requireNotNull(registered.session.serverInstanceId),requireNotNull(registered.session.syncEpoch),requireNotNull(registered.deviceId),nextRequestHash(bytes),bytes)
            val existed=before.transmission!=null;var hash=before.transmissionHash
            afterSendPrepareForTest()
            commit(lease,before.witness,{sent[id]=transmission;transmissionHashes[id]=requireNotNull(hash);if(!existed)proofRevision++}) { effects ->
                if(!existed) {insert("next_transmissions",transmissionValues(transmission));effect(effects,"next_transmissions",null,transmissionValues(transmission))}
                hash=rowHash("next_transmissions",id)
            }
            Triple(bytes,lease,requireNotNull(hash))
        } }
        val (bytes,lease,hash)=prepared
        val response=timed("count_send_push") {channel.pushFrozen(bytes.copyOf())}
        timed("count_send_after_response") {
            authenticate()
            account {gate.transaction(db,G0DomainSqlGate.Phase.READ) {current();authority.checkLease(lease);require(transmissionHashes[id]==hash && id in pending)}}
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
        val lease=entryLease();val id=delivery.requestId
        val before=account {request(id)}
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
                history=history.copy(completions=history.completions.map { if(it.uuid==old.uuid)next else it })
                receipts[id]=receipt;pending.remove(id);shadows[old.uuid]=shadow
                if(old!=next)factRevision++;proofRevision++
                // The display excludes storage createdAt. Proof still advances on every ACK.
            }
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

    /** One authenticated committed version supplies card, home quantity and follow-up claim.
     * No cached permission; the caller must clear its visible content on revocation/lifecycle.
     * This first owner exposes a callback, not an enduring publication capability. */
    suspend fun readAndPublish(sourceId:Long?=null,publish:(View)->Unit)=account {
        val lease=entryLease()
        authority.finalPermit(lease).use {
            val view=gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                current();require(LocalDate.now()==history.today) { "G0_COUNT_DATE_CHANGED" }
                val identity=CountActionIdentity(scope.session,habit.id,habit.uuid,scope.capturedDeviceId,NextStructureMapper.writePlan(habit),null)
                val currentHistory=history.copy(actionIdentity=identity,
                    completions=java.util.Collections.unmodifiableList(history.completions.toList()),
                    quantities=java.util.Collections.unmodifiableMap(history.quantities.toMap()),
                    policies=java.util.Collections.unmodifiableMap(history.policies.toMap()),
                    unknownDates=java.util.Collections.unmodifiableSet(history.unknownDates.toSet()))
                val source=sourceId?.let { id->currentHistory.completions.single { it.id==id } }
                val day=source?.businessDate ?: history.today
                val zone=source?.let {ZoneId.of(it.recordedTimezone)} ?: ZoneId.systemDefault()
                val sourceHistory=currentHistory.copy(today=day,todayPolicy=currentHistory.policies[day])
                val follow=recurringFactSnapshot(habit,scope,null,sourceHistory,null,day,zone,source)
                require(tokens.isCurrentDisplayReadAuthority(scope,registered))
                val revision=proofRevision;val publicationEpoch=gate.revision;val date=history.today
                View(habit,currentHistory,follow,factRevision,revision,presentationRevision) {
                    current();authority.checkLease(lease);gate.revision==publicationEpoch && proofRevision==revision && LocalDate.now()==date
                }
            }
            authority.checkLease(lease);currentCoroutineContext().ensureActive();publish(view)
        }
    }

    /** A complete bounded wake plan from the same freshly authenticated read boundary.
     * These IDs are not send authority; each original send/ACK still validates its own
     * request and final credentials. This also rediscovers unsent outbox after UI exit. */
    suspend fun pendingRequestIds():List<String> {
        var result:List<String>?=null
        readAndPublish {
            result=java.util.Collections.unmodifiableList(pending.values.sortedBy {it.id}.map {it.operationId})
        }
        return requireNotNull(result)
    }

    /** Explicit same-command recovery after a NEW cold owner has verified durable sources.
     * Absence is not a success, and this method never submits or repeats the command. */
    suspend fun recover(command:Command):Saved?=account {
        // Preserve the original default refusal order; the candidate binds its
        // entire fresh observation to a lease captured before the snapshot.
        val lease=if(jointAuthority)entryLease().also {
            require(command.session==scope.session && command.habitUuid==habit.uuid)
        } else {
            authenticate();require(command.session==scope.session && command.habitUuid==habit.uuid)
            authority.lease()
        }
        authority.finalPermit(lease).use {
            gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                current();val operation=operations[command.requestId]
                val result=if(operation==null) {
                    require(history.completions.none {it.uuid==command.factUuid});null
                } else {
                    require(operation.entityUuid==command.factUuid)
                    val fact=history.completions.single {it.uuid==command.factUuid}
                    Saved(fact.id,fact.uuid,command.requestId)
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
    private fun factValues(r:CompletionEntity)=listOf(r.id,r.habitId,r.date,r.value,r.actualCompletedAt,r.uuid,r.habitUuid,r.createdAt,r.recordedTimezone,r.recordedLocalDate,r.timeMetadataSource,r.oneTimeAction,r.oneTimeExpectedVersion,r.oneTimeExpectedHeadEventUuid,r.oneTimeRevertsEventUuid)
    private fun queueValues(r:SyncOutboxEntity)=listOf(r.id,r.operationId,r.recordType,r.entityUuid,r.wireEntityUuid,r.action,r.referenceUuid,r.payloadJson,r.baseRevision,r.basePayloadJson,r.attemptedAt,r.attemptCount,r.lastError,r.errorCode,r.deadLetteredAt,r.createdAt)
    private fun originValues(r:NextRequestOriginEntity)=listOf(r.kind,r.requestId,r.queueId,r.protocol,r.accountId,r.serverInstanceId,r.syncEpoch,r.sourceHash,r.intentJson)
    private fun transmissionValues(r:NextTransmissionEntity)=listOf(r.kind,r.requestId,r.queueId,r.protocol,r.accountId,r.serverInstanceId,r.syncEpoch,r.deviceId,r.wireHash,r.wireBytes)
    private fun receiptValues(r:NextAcceptanceEntity)=listOf(r.kind,r.requestId,r.originHash,r.transmissionHash,r.resultHash,r.resultJson)
    private fun shadowValues(r:SyncEntityStateEntity)=listOf(r.entityType,r.entityUuid,r.revision,if(r.deleted)1L else 0L,r.payloadJson,r.payloadHash,r.updatedAt)
}
