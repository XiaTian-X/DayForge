package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.AuthenticatedHistoryDirectory
import com.dayforge.data.history.HistoryBlockUpdater
import com.dayforge.data.history.HistoryNodeSession

import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.CountDayEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Code
import com.dayforge.data.history.HistoryBlockCodec.Record
import com.dayforge.data.history.HistoryBlockCodec.Ref
import com.dayforge.data.history.HistoryBlockCodec.Rejected
import com.dayforge.data.history.HistoryBlockPlanner.LeafRange
import com.dayforge.data.history.HistoryBlockPlanner.OrderKey
import com.dayforge.data.history.HistoryBlockPlanner.Partition
import com.dayforge.data.history.HistoryBlockPlanner.Scope
import com.dayforge.data.history.HistoryBlockPlanner.UnitKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive

/** Isolated representation adapter. A State, Delta or authenticated hash grants no business
 * authority. The owner must validate original business OLD/NEW and atomic commit externally.
 * Initial construction is complete; advance visits only affected paths and never copies Image.
 * The legacy B2 path retains/copies its bounded map. B3 uses a transaction-bound source
 * and returns only newly encoded blocks; neither path grants SQL or business authority.
 */
internal object G0CountIncrementalBlocks {
    data class Lifetime(val firstDate:LocalDate?,val qualifiedDayCount:Int,val totalQuantity:Long)
    private data class ObjectInfo(val id:Long,val uuid:String,val planRule:CountDayPolicy,val lifetime:Lifetime)
    private data class DayInfo(val row:CountDayEntity,val quantity:Long,val members:Int)
    private data class Location(val habitId:Long,val habitUuid:String,val date:LocalDate,val factId:Long,val requestId:String)
    data class Allocation(val lastFactId:Long,val lastQueueId:Long,val totalFacts:Int)
    private data class RequestLocation(val factUuid:String,val queueId:Long)
    data class Delta(val oldFact:CompletionEntity?,val newFact:CompletionEntity,
        val oldSource:G0CountBusinessBlockCodec.Source?,val newSource:G0CountBusinessBlockCodec.Source,
        val oldDay:CountDayEntity?,val newDay:CountDayEntity,val habit:HabitEntity)
    data class UpdateStats(val loadedNodes:Int,val loadedBytes:Long,val nodeRequests:Int,val processedNodeBytes:Long,
        val newNodes:Int,val newBytes:Long,val unitEdits:Int,val topLevelEdits:Int,val changedUnits:Set<String>)
    data class Update(val state:State,val newBlocks:Map<String,Bytes>,val stats:UpdateStats)
    class State internal constructor(val binding:String,val root:Ref,blocks:Map<String,Bytes>) {
        val blocks:Map<String,Bytes> = Collections.unmodifiableMap(LinkedHashMap(blocks))
        val rootDigest:String get()=root.digest
    }
    data class Pending(val requestId:String,val factUuid:String,val queueId:Long,val inflight:Boolean)
    /** Only current data, not a permission. The complete two Habit values and pending locators
     * are owned; no mutable weekly list may change the signed representation after capture. */
    class Current(habits:Map<String,HabitEntity>,pending:List<Pending>,val maxFacts:Int,val maxPending:Int) {
        val habits:Map<String,HabitEntity> = Collections.unmodifiableMap(habits.toSortedMap().mapValues {(_,h)->
            h.copy(schedule=when(val schedule=h.schedule) {
                is com.dayforge.data.model.HabitSchedule.Weekly->com.dayforge.data.model.HabitSchedule.Weekly(
                    Collections.unmodifiableList(ArrayList(schedule.daysOfWeek)))
                else->schedule
            })
        })
        val pending:List<Pending> = Collections.unmodifiableList(ArrayList(pending))
        init {
            demand(maxFacts in 1..128 || maxFacts==736)
            demand(maxPending in 1..128 && (maxFacts!=736 || maxPending==6))
            demand(this.habits.size==2 && this.habits.all {(key,h)->key==h.uuid && h.id>0} &&
                this.habits.values.map {it.id}.distinct().size==2)
            demand(this.pending.size<=maxPending && this.pending.all {it.queueId>0 && it.requestId.isNotEmpty() && it.factUuid.isNotEmpty()})
            demand(this.pending.zipWithNext().all {(a,b)->a.queueId<b.queueId})
            demand(this.pending.map {it.requestId}.distinct().size==this.pending.size &&
                this.pending.map {it.factUuid}.distinct().size==this.pending.size)
        }
        fun copy(habits:Map<String,HabitEntity> = this.habits,pending:List<Pending> = this.pending,
            maxFacts:Int=this.maxFacts,maxPending:Int=this.maxPending)=Current(habits,pending,maxFacts,maxPending)
        override fun equals(other:Any?):Boolean=other is Current && habits==other.habits && pending==other.pending &&
            maxFacts==other.maxFacts && maxPending==other.maxPending
        override fun hashCode():Int=31*(31*(31*habits.hashCode()+pending.hashCode())+maxFacts)+maxPending
    }
    data class DayState(val row:CountDayEntity,val quantity:Long,val members:Int)
    data class RequestState(val fact:CompletionEntity,val source:G0CountBusinessBlockCodec.Source,
        val day:DayState,val firstFact:CompletionEntity,val firstSource:G0CountBusinessBlockCodec.Source)
    data class Loaded(val current:Current,val allocation:Allocation,val pending:List<RequestState>)
    data class Capture(val current:Current,val allocation:Allocation,val pending:List<RequestState>,val habit:HabitEntity,
        val days:Map<LocalDate,DayState?>,val request:RequestState?,val newFactAbsent:Boolean?,val newRequestAbsent:Boolean?,
        val dayFacts:List<CompletionEntity>,val dayFactsByDate:Map<LocalDate,List<CompletionEntity>>,
        val daySourcesByDate:Map<LocalDate,List<G0CountBusinessBlockCodec.Source>>,
        val firstFact:CompletionEntity?,val firstSource:G0CountBusinessBlockCodec.Source?)

    /** B3 profile must be present and bounded current members agree with authenticated R/E/S.
     * A B2-only root has no C record and cannot silently gain writable capability. */
    suspend fun readCurrent(binding:String,root:Ref,source:HistoryNodeSession.Lookup):Loaded {
        val caller=currentCoroutineContext();val identity=State(binding,root,emptyMap())
        return operationSource(identity,source,true,caller::ensureActive) {ledger->
            loadCurrent(Reader(binding,root,ledger))
        }
    }
    suspend fun readRequest(binding:String,root:Ref,source:HistoryNodeSession.Lookup,id:String):RequestState {
        val caller=currentCoroutineContext();val identity=State(binding,root,emptyMap())
        return operationSource(identity,source,true,caller::ensureActive) {ledger->
            val reader=Reader(binding,root,ledger)
            val current=G0CountCurrentBlockCodec.decode((reader.index("C|current") ?: bad()).payload)
            val found=reader.request(id) ?: bad()
            val habit=current.habits[found.fact.habitUuid] ?: bad()
            demand(habit.id==found.fact.habitId)
            found
        }
    }
    suspend fun capture(binding:String,root:Ref,source:HistoryNodeSession.Lookup,habitUuid:String?,date:LocalDate,
        activityWindowDates:Set<LocalDate> = emptySet(),requestId:String?=null,newFactUuid:String?=null,
        newRequestId:String?=null):Capture {
        demand(activityWindowDates.size<=16)
        val caller=currentCoroutineContext();val identity=State(binding,root,emptyMap())
        return operationSource(identity,source,true,caller::ensureActive) {ledger->
            val reader=Reader(binding,root,ledger);val loaded=loadCurrent(reader)
            val request=requestId?.let {reader.request(it)}
            val selectedUuid=habitUuid ?: request?.fact?.habitUuid ?: bad()
            val habit=loaded.current.habits[selectedUuid] ?: bad()
            val dates=(activityWindowDates+date+listOfNotNull(request?.fact?.let {LocalDate.parse(it.recordedLocalDate)})).sorted()
            val days=linkedMapOf<LocalDate,DayState?>();val facts=linkedMapOf<LocalDate,List<CompletionEntity>>()
            val sources=linkedMapOf<LocalDate,List<G0CountBusinessBlockCodec.Source>>()
            for(day in dates) {
                caller.ensureActive()
                val read=projectDay(binding,root,ledger,selectedUuid,day,date)
                val state=reader.day(selectedUuid,day)?.let {DayState(it.row,it.quantity,it.members)}
                demand(read.quantity==(state?.quantity ?: 0L) && read.facts.size==(state?.members ?: 0))
                // Q is the complete touched-day source set, including accepted requests.
                // Current.pending cannot prove the absence of a retired/orphan origin.
                val expectedFacts=read.facts.associateBy {it.uuid}
                demand(expectedFacts.size==read.facts.size && read.sourceRequestIds.size==read.facts.size)
                val seenFacts=linkedSetOf<String>()
                val daySources=read.sourceRequestIds.sorted().map {id->
                    caller.ensureActive()
                    val found=reader.request(id) ?: bad()
                    demand(found.source.origin.requestId==id && found.fact.habitId==habit.id &&
                        found.fact.habitUuid==selectedUuid && found.fact.recordedLocalDate==day.toString() &&
                        expectedFacts[found.fact.uuid]==found.fact && seenFacts.add(found.fact.uuid))
                    found.source
                }
                demand(seenFacts==expectedFacts.keys)
                days[day]=state;facts[day]=read.facts;sources[day]=Collections.unmodifiableList(daySources)
            }
            val targetDay=days.getValue(date)
            val first=targetDay?.row?.originRequestId?.let {reader.request(it) ?: bad()}
            if(targetDay!=null)demand(first!=null && first.fact.uuid==targetDay.row.firstEventUuid &&
                first.fact.habitUuid==selectedUuid && first.fact.recordedLocalDate==date.toString() &&
                first.source.originHash==targetDay.row.originHash)
            if(request!=null)demand(request.fact.habitUuid==selectedUuid)
            Capture(loaded.current,loaded.allocation,loaded.pending,habit,Collections.unmodifiableMap(days),request,
                newFactUuid?.let {reader.index("E|$it")==null},newRequestId?.let {reader.index("R|$it")==null},
                facts.getValue(date),Collections.unmodifiableMap(facts),Collections.unmodifiableMap(sources),first?.fact,first?.source)
        }
    }
    private suspend fun loadCurrent(reader:Reader):Loaded {
        val current=G0CountCurrentBlockCodec.decode((reader.index("C|current") ?: bad()).payload)
        val allocation=metaRead((reader.index("A|allocation") ?: bad()).payload,5){readAllocation(this)}
        demand(allocation.totalFacts<=current.maxFacts && current.pending.size<=allocation.totalFacts)
        current.habits.forEach {(uuid,habit)->
            val info=reader.objectInfo(uuid)
            demand(info.id==habit.id && info.uuid==habit.uuid && info.planRule==CountDayPolicy(habit.targetValue,habit.isCountdown))
        }
        val pending=current.pending.map {locator->
            val found=reader.request(locator.requestId) ?: bad();val source=found.source
            val queue=source.pending ?: bad()
            demand(source.receipt==null && found.fact.uuid==locator.factUuid && queue.operationId==locator.requestId &&
                queue.id==locator.queueId && (source.transmission!=null)==locator.inflight &&
                source.origin.queueId<=allocation.lastQueueId && found.fact.id<=allocation.lastFactId)
            demand(found.fact.habitUuid in current.habits)
            found
        }
        return Loaded(current,allocation,Collections.unmodifiableList(pending))
    }

    private val indexDate=LocalDate.of(1970,1,1)
    private const val FORMAT=1
    private const val MAX_RECORDS=736
    private const val MAX_TOTAL_BYTES=32L*1024*1024
    private const val INDEX_SOURCE="count-incremental-index-v1"
    private val tree=AuthenticatedHistoryDirectory()

    /** today is only the freshly qualified presentation environment; it never enters block identity. */
    suspend fun readDay(state:State,habitUuid:String,date:LocalDate,today:LocalDate,
        reuseDecodedNodes:Boolean=true):G0CountBusinessBlocks.DayRead {
        val caller=currentCoroutineContext()
        return operation(state,{state.blocks[it]},reuseDecodedNodes,caller::ensureActive) { ledger ->
            projectDay(state.binding,state.root,ledger,habitUuid,date,today)
        }
    }

    /** Representation-only read from a fixed transaction's bytes. The caller owns root
     * authentication, SQL lifetime, fresh authority and actual-END publication. No State map,
     * full builder, primary-table reader or write capability is reconstructed here. */
    suspend fun readDayFromSource(binding:String,root:Ref,source:HistoryNodeSession.Lookup,
        habitUuid:String,date:LocalDate,today:LocalDate):G0CountBusinessBlocks.DayRead {
        val caller=currentCoroutineContext()
        val identity=State(binding,root,emptyMap())
        return HistoryNodeSession.use(identity,root,source) { session ->
            projectDay(binding,root,Ledger(identity,null,caller::ensureActive,session),habitUuid,date,today)
        }
    }

    private suspend fun projectDay(binding:String,root:Ref,ledger:Ledger,habitUuid:String,
        date:LocalDate,today:LocalDate):G0CountBusinessBlocks.DayRead {
        val reader=Reader(binding,root,ledger)
        val info=reader.objectInfo(habitUuid)
        val month=YearMonth.from(date)
        val factsRoot=reader.unit("F",habitUuid,month)
        val sourcesRoot=reader.unit("S",habitUuid,month)
        val daysRoot=reader.unit("D",habitUuid,month)
        demand((factsRoot==null)==(sourcesRoot==null) && (factsRoot==null)==(daysRoot==null))
        val daily=reader.day(habitUuid,date)
        val factRecords=factsRoot?.let {reader.dayRange(it,date)} ?: emptyList()
        val sourceRecords=sourcesRoot?.let {reader.dayRange(it,date)} ?: emptyList()
        demand(factRecords.size==sourceRecords.size)
        if(daily==null)demand(factRecords.isEmpty())
        val sources=sourceRecords.associate {record->
            val source=G0CountBusinessBlockCodec.source(record.payload)
            demand(record.sourceId==source.origin.requestId)
            val operation=decodeNextOperationIntent(source.origin.intentJson)
            demand(operation.entityUuid==record.key.stableFactId && operation.operationId==source.origin.requestId)
            record.key.stableFactId to source
        }
        demand(sources.size==sourceRecords.size)
        var quantity=0L;val requestIds=linkedSetOf<String>()
        val facts=factRecords.map {record->
            val fact=G0CountBusinessBlockCodec.fact(record.payload)
            demand(fact.uuid==record.key.stableFactId && fact.habitId==info.id && fact.habitUuid==habitUuid &&
                fact.recordedLocalDate==date.toString() && record.key.recordedBusinessDate==date)
            val source=sources[fact.uuid] ?: bad()
            demand(record.sourceId==source.origin.requestId && requestIds.add(source.origin.requestId))
            val located=reader.index("E|${fact.uuid}") ?: bad()
            val location=metaRead(located.payload,3) {readLocation(this)}
            demand(location==Location(info.id,habitUuid,date,fact.id,source.origin.requestId))
            val request=reader.index("R|${source.origin.requestId}") ?: bad()
            demand(metaRead(request.payload,6) {readRequest(this)}==RequestLocation(fact.uuid,source.origin.queueId))
            quantity=Math.addExact(quantity,fact.value.toLong());fact
        }
        demand(sources.keys==facts.map {it.uuid}.toSet())
        if(daily!=null) {
            demand(daily.row.habitId==info.id && daily.row.habitUuid==habitUuid && daily.row.localDate==date.toString() &&
                daily.members==facts.size && daily.quantity==quantity)
            val first=sources[daily.row.firstEventUuid] ?: bad()
            demand(daily.row.originRequestId==first.origin.requestId && daily.row.originHash==first.originHash)
        }
        val todayDay=if(today==date)daily else reader.day(habitUuid,today)
        val todayQuantity=todayDay?.quantity ?: 0L
        val todayPolicy=todayDay?.row?.policy ?: info.planRule
        val summary=G0CountBusinessBlocks.Summary(today,todayQuantity,todayQuantity>=todayPolicy.targetValue,
            info.lifetime.firstDate,info.lifetime.qualifiedDayCount,info.lifetime.totalQuantity)
        val policy=daily?.row?.policy ?: if(date==today)info.planRule else null
        val sorted=facts.sortedWith(compareByDescending<CompletionEntity>{it.recordedLocalDate}
            .thenByDescending {it.actualCompletedAt ?: it.date}.thenByDescending {it.id})
        return G0CountBusinessBlocks.DayRead(date,info.id,habitUuid,quantity,daily?.row?.policy,policy,false,daily?.row,
            Collections.unmodifiableList(sorted),Collections.unmodifiableSet(requestIds),summary,
            G0CountBusinessBlocks.ReadStats(ledger.loads,ledger.bytes,facts.size,sources.size,ledger.requests,ledger.processed))
    }

    suspend fun firstBuild(binding:String,snapshot:G0HouseholdCountImage.FrozenData,
        includeCurrent:Boolean=false,maxFacts:Int=128,maxPending:Int=128):State {
        val caller=currentCoroutineContext();caller.ensureActive()
        // FrozenData has already bounded and owned all containers; never enumerate a borrowed Data.
        val data=snapshot.copyForProjection()
        demand(binding.length==64 && binding.all {it in "0123456789abcdef"})
        val objects=data.objects.entries.toList()
        demand(objects.size==2 && objects.map {it.value.habit.id}.distinct().size==2)
        val facts=objects.flatMap {it.value.history.completions}
        demand(facts.size==data.totalFacts && facts.size<=MAX_RECORDS)
        demand(facts.map {it.uuid}.distinct().size==facts.size && facts.map {it.id}.distinct().size==facts.size)
        demand(data.origins.keys==data.operations.keys && data.origins.keys==data.originHashes.keys)
        demand(data.origins.size==facts.size && data.origins.values.map {it.queueId}.distinct().size==facts.size)
        demand(data.sent.keys==data.transmissionHashes.keys && data.receipts.keys.all {it in data.sent})
        demand(data.pending.keys+data.receipts.keys==data.origins.keys && (data.pending.keys intersect data.receipts.keys).isEmpty())
        demand(data.sent.keys.all {it in data.origins} && data.shadows.keys==data.receipts.keys.map {data.operations.getValue(it).entityUuid}.toSet())
        val factsById=facts.associateBy {it.uuid}
        val sourceByFact=linkedMapOf<String,G0CountBusinessBlockCodec.Source>()
        for((request,origin) in data.origins) {
            caller.ensureActive()
            val op=data.operations.getValue(request)
            demand(origin.requestId==request && op.operationId==request && decodeNextOperationIntent(origin.intentJson)==op)
            val fact=factsById[op.entityUuid] ?: bad()
            demand(op.payload["activity_uuid"]==JsonPrimitive(fact.habitUuid) &&
                op.payload["local_date"]==JsonPrimitive(fact.recordedLocalDate))
            val source=G0CountBusinessBlockCodec.Source(origin,data.originHashes.getValue(request),
                data.sent[request],data.transmissionHashes[request],data.receipts[request],data.pending[request],data.shadows[fact.uuid])
            val receipt=source.receipt;val pending=source.pending
            if(receipt!=null) {
                demand(source.transmission!=null && source.shadow!=null && pending==null &&
                    receipt.requestId==request && receipt.originHash==source.originHash && receipt.transmissionHash==source.transmissionHash)
            } else demand(pending!=null && pending.operationId==request && pending.id==origin.queueId && pending.entityUuid==fact.uuid)
            source.transmission?.let {demand(it.requestId==request && it.queueId==origin.queueId)}
            demand(sourceByFact.put(fact.uuid,source)==null)
        }
        demand(sourceByFact.keys==factsById.keys)
        val expectedDaySources=facts.groupBy {G0HouseholdCountImage.DayKey(it.habitId,it.recordedLocalDate)}
            .mapValues {(_,rows)->rows.map {sourceByFact.getValue(it.uuid).origin.requestId}.toSet()}
        demand(data.daySources==expectedDaySources && data.days.keys==expectedDaySources.keys)
        val groups=linkedMapOf<UnitKey,MutableList<Record>>()
        val index=mutableListOf<Record>()
        var remainingPrepared=MAX_TOTAL_BYTES
        fun reserve(record:Record) {
            val size=HistoryBlockCodec.recordEncodedSize(record)
            if(size>remainingPrepared)throw Rejected(Code.BUDGET)
            remainingPrepared-=size
        }
        fun addIndex(id:String,payload:Bytes) {
            val record=indexRecord(id,payload);reserve(record);index.add(record)
        }
        fun add(kind:String,uuid:String,date:LocalDate,id:String,source:String,payload:Bytes) {
            val unit=unit(binding,kind,uuid,YearMonth.from(date))
            val record=Record(OrderKey(date,id),source,payload);reserve(record)
            groups.getOrPut(unit){mutableListOf()}.add(record)
        }
        for((uuid,obj) in objects) {
            caller.ensureActive()
            val habit=obj.habit;val history=obj.history
            demand(uuid==habit.uuid && habit.habitType==HabitType.COUNTING && habit.completionPolicy=="recurring" &&
                habit.parentHabitId==null && !habit.isCountdown && history.today==data.today && history.unknownDates.isEmpty())
            val currentPolicy=history.todayPolicy ?: bad()
            val byDate=history.completions.groupBy {LocalDate.parse(it.recordedLocalDate)}
            val quantities=byDate.mapValues {(_,rows)->
                rows.fold(0L){sum,row->Math.addExact(sum,row.value.toLong())}}
            val dayRows=data.days.values.filter {it.habitId==habit.id}
            demand(history.quantities==quantities && history.policies==dayRows.associate {LocalDate.parse(it.localDate) to it.policy})
            demand(currentPolicy==(history.policies[data.today] ?: CountDayPolicy(habit.targetValue,habit.isCountdown)))
            val total=quantities.values.fold(0L,Math::addExact)
            val todayQuantity=quantities[data.today] ?: 0L
            demand(todayQuantity>=0)
            val lifetime=Lifetime(quantities.keys.minOrNull(),
                quantities.count {(date,value)->history.policies[date]?.let {value>=it.targetValue}==true},total)
            val planRule=CountDayPolicy(habit.targetValue,habit.isCountdown)
            addIndex("O|$uuid",metaWrite(2){writeObject(this,ObjectInfo(habit.id,uuid,planRule,lifetime))})
            for(fact in history.completions) {
                demand(fact.habitId==habit.id && fact.habitUuid==uuid)
                val date=LocalDate.parse(fact.recordedLocalDate);val source=sourceByFact.getValue(fact.uuid)
                add("F",uuid,date,fact.uuid,source.origin.requestId,G0CountBusinessBlockCodec.fact(fact))
                add("S",uuid,date,fact.uuid,source.origin.requestId,G0CountBusinessBlockCodec.source(source))
                addIndex("E|${fact.uuid}",metaWrite(3){writeLocation(this,Location(habit.id,uuid,date,fact.id,source.origin.requestId))})
                addIndex("R|${source.origin.requestId}",metaWrite(6){writeRequest(this,RequestLocation(fact.uuid,source.origin.queueId))})
            }
            for(day in dayRows) {
                val date=LocalDate.parse(day.localDate);val members=byDate.getValue(date)
                val first=sourceByFact[day.firstEventUuid] ?: bad()
                demand(day.habitUuid==uuid && day.originRequestId==first.origin.requestId && day.originHash==first.originHash)
                add("D",uuid,date,dayIdentity(uuid,date),"day-rule-v1",metaWrite(4){writeLong(quantities.getValue(date));writeInt(members.size);bytes(G0CountBusinessBlockCodec.day(day))})
            }
        }
        demand(data.lastFactId>=(facts.maxOfOrNull {it.id} ?: 0L) && data.lastQueueId>=(data.origins.values.maxOfOrNull {it.queueId} ?: 0L))
        addIndex("A|allocation",metaWrite(5){writeAllocation(this,Allocation(data.lastFactId,data.lastQueueId,data.totalFacts))})
        if(includeCurrent) {
            val current=Current(data.objects.mapValues {it.value.habit},data.pending.entries.map {(id,q)->
                Pending(id,data.operations.getValue(id).entityUuid,q.id,id in data.sent)
            }.sortedBy {it.queueId},maxFacts,maxPending)
            demand(data.totalFacts<=maxFacts)
            addIndex("C|current",G0CountCurrentBlockCodec.encode(current))
        }
        val stored=linkedMapOf<String,Bytes>();var remaining=MAX_TOTAL_BYTES
        fun retain(built:AuthenticatedHistoryDirectory.Built) {
            for((digest,bytes) in built.blocks) {
                val previous=stored[digest]
                if(previous!=null)demand(previous==bytes) else {
                    if(bytes.size.toLong()>remaining)throw Rejected(Code.BUDGET)
                    remaining-=bytes.size;stored[digest]=bytes
                }
            }
        }
        for((unit,records) in groups) {
            caller.ensureActive()
            val built=tree.build(unit,records);retain(built)
            val kind=unit.partition.domain.removePrefix("count-incremental-").removeSuffix("-v1")
            addIndex(unitIndex(kind,unit.partition.objectId,unit.month),metaWrite(1){
                text(kind);text(unit.partition.objectId);text(unit.month.toString());writeInt(built.root.height);writeLong(built.root.count);text(built.root.digest)
            })
        }
        val directory=tree.build(unit(binding,"index","scope",YearMonth.from(indexDate)),index);retain(directory)
        return State(binding,directory.root,stored)
    }

    /** Pure optimistic transformation of already-proven data. Every caller OLD is compared with
     * the authenticated old root. No SQL, signing, permission, or unknown-END recovery happens here. */
    suspend fun advance(state:State,delta:Delta,reuseDecodedNodes:Boolean=true):Update =
        advanceInternal(state,delta,reuseDecodedNodes,null,null,null)

    /** B3 keeps only the changed immutable blocks. The returned State carries root identity
     * with an empty map; callers persist newBlocks and use a fresh SQL-bound Lookup next time.
     * Neither this function nor its result retains the source callback after the call. */
    suspend fun advanceFromSource(binding:String,root:Ref,source:HistoryNodeSession.Lookup,delta:Delta,
        oldCurrent:Current,nextCurrent:Current):Update =
        advanceInternal(State(binding,root,emptyMap()),delta,true,source,oldCurrent,nextCurrent)

    private suspend fun advanceInternal(state:State,delta:Delta,reuseDecodedNodes:Boolean,
        sourceLookup:HistoryNodeSession.Lookup?,oldCurrent:Current?,nextCurrent:Current?):Update {
        val caller=currentCoroutineContext();caller.ensureActive()
        val fact=delta.newFact;val oldFact=delta.oldFact;val source=delta.newSource;val oldSource=delta.oldSource
        val row=delta.habit;val date=LocalDate.parse(fact.recordedLocalDate);val month=YearMonth.from(date)
        demand(fact.recordedLocalDate==date.toString() && fact.id>0 && fact.value>0 && fact.habitId==row.id && fact.habitUuid==row.uuid)
        demand(row.habitType==HabitType.COUNTING && row.completionPolicy=="recurring" && row.parentHabitId==null && !row.isCountdown)
        demand((oldFact==null)==(oldSource==null))
        val append=oldFact==null
        if(!append) demand(requireNotNull(oldFact).id==fact.id && oldFact.uuid==fact.uuid && oldFact.habitId==fact.habitId &&
            oldFact.habitUuid==fact.habitUuid && oldFact.recordedLocalDate==fact.recordedLocalDate)
        measured("count_block_sources") {
            checkSource(fact,source)
            oldSource?.let {checkSource(requireNotNull(oldFact),it)}
            checkTransition(oldFact,fact,oldSource,source)
        }
        demand(delta.newDay.habitId==row.id && delta.newDay.habitUuid==row.uuid && delta.newDay.localDate==date.toString())
        if(delta.oldDay!=null)demand(delta.newDay==delta.oldDay)
        else demand(append && delta.newDay.firstEventUuid==fact.uuid && delta.newDay.originRequestId==source.origin.requestId &&
            delta.newDay.originHash==source.originHash)

        val newBlocks=linkedMapOf<String,Bytes>();val auxiliary=linkedMapOf<String,Bytes>()
        val lookup=HistoryNodeSession.Lookup {digest,limit->
            newBlocks[digest] ?: auxiliary[digest] ?: sourceLookup?.load(digest,limit) ?: state.blocks[digest]
        }
        return operationSource(state,lookup,reuseDecodedNodes,caller::ensureActive) { ledger ->
        val reader=Reader(state.binding,state.root,ledger)
        // A B3 root may not flow through B2's old writer, which cannot maintain C.
        if(sourceLookup==null)demand(reader.index("C|current")==null)
        val objectRecord=reader.index("O|${row.uuid}") ?: bad()
        val objectInfo=metaRead(objectRecord.payload,2) {readObject(this)}
        demand(objectInfo.id==row.id && objectInfo.uuid==row.uuid && objectInfo.planRule==CountDayPolicy(row.targetValue,row.isCountdown))
        if(delta.oldDay==null)demand(delta.newDay.policy==objectInfo.planRule)
        measured("count_block_policy") {
            demand(NextCommonFactMapper.countPolicy(decodeNextOperationIntent(source.origin.intentJson).payload)==delta.newDay.policy)
        }
        val oldRoots=listOf("F","S","D").associateWith {reader.unit(it,row.uuid,month)}
        demand(oldRoots.values.map {it==null}.distinct().size==1)
        val oldDayInfo=reader.day(row.uuid,date)
        demand(oldDayInfo?.row==delta.oldDay)
        val key=OrderKey(date,fact.uuid)
        val expectedFact=oldFact?.let {Record(key,requireNotNull(oldSource).origin.requestId,G0CountBusinessBlockCodec.fact(it))}
        val expectedSource=oldSource?.let {Record(key,it.origin.requestId,G0CountBusinessBlockCodec.source(it))}
        val newFactRecord=Record(key,source.origin.requestId,G0CountBusinessBlockCodec.fact(fact))
        val newSourceRecord=Record(key,source.origin.requestId,G0CountBusinessBlockCodec.source(source))
        demand(oldRoots["F"]?.let {reader.point(it,key)}==expectedFact)
        demand(oldRoots["S"]?.let {reader.point(it,key)}==expectedSource)
        val eventKey="E|${fact.uuid}";val requestKey="R|${source.origin.requestId}"
        val oldEvent=reader.index(eventKey);val oldRequest=reader.index(requestKey)
        val location=Location(row.id,row.uuid,date,fact.id,source.origin.requestId)
        val requestLocation=RequestLocation(fact.uuid,source.origin.queueId)
        if(append)demand(oldEvent==null && oldRequest==null)
        else {
            demand(oldEvent!=null && metaRead(oldEvent.payload,3){readLocation(this)}==location)
            demand(oldRequest!=null && metaRead(oldRequest.payload,6){readRequest(this)}==requestLocation)
        }

        var prepared=0L;var newBytes=0L;var unitEdits=0
        val changedUnits=linkedSetOf<String>();val topEdits=mutableListOf<HistoryBlockUpdater.Edit>()
        fun reserve(record:Record?) {if(record!=null) {
            val bytes=HistoryBlockCodec.recordEncodedSize(record)
            if(bytes>8L*1024*1024-prepared)throw Rejected(Code.BUDGET)
            prepared+=bytes
        }}
        fun top(id:String,old:Record?,replacement:Record) {
            reserve(old);reserve(replacement)
            topEdits+=HistoryBlockUpdater.Edit(OrderKey(indexDate,id),old,replacement)
            if(topEdits.size>32)throw Rejected(Code.BUDGET)
        }
        fun retain(patch:HistoryBlockUpdater.Patch) {
            for((digest,bytes) in patch.newBlocks) {
                val old=state.blocks[digest] ?: newBlocks[digest]
                if(old!=null)demand(old==bytes) else {
                    if(newBlocks.size>=256 || bytes.size.toLong()>8L*1024*1024-newBytes)throw Rejected(Code.BUDGET)
                    newBlocks[digest]=bytes;newBytes+=bytes.size
                }
            }
        }
        fun updateUnit(kind:String,recordKey:OrderKey,old:Record?,replacement:Record) {
            if(old==replacement)return
            reserve(old);reserve(replacement)
            val oldRoot=oldRoots[kind] ?: HistoryBlockCodec.encode(HistoryBlockCodec.Leaf(
                LeafRange(unit(state.binding,kind,row.uuid,month),null,null),emptyList())).let {
                auxiliary[it.ref.digest]=it.bytes;it.ref
            }
            val patch=measuredCall("count_block_rewrite_$kind") {
                ledger.apply(oldRoot,listOf(HistoryBlockUpdater.Edit(recordKey,old,replacement)))
            }
            retain(patch);unitEdits++;changedUnits+=kind
            val id=unitIndex(kind,row.uuid,month)
            val next=indexRecord(id,metaWrite(1) {
                text(kind);text(row.uuid);text(month.toString());writeInt(patch.root.height);writeLong(patch.root.count);text(patch.root.digest)
            })
            top(id,reader.index(id),next)
        }
        updateUnit("F",key,expectedFact,newFactRecord)
        updateUnit("S",key,expectedSource,newSourceRecord)
        if(append) {
            val oldQuantity=oldDayInfo?.quantity ?: 0L;val oldMembers=oldDayInfo?.members ?: 0
            val newQuantity=Math.addExact(oldQuantity,fact.value.toLong());val newMembers=Math.addExact(oldMembers,1)
            demand(newMembers<=MAX_RECORDS)
            val oldDayRecord=oldDayInfo?.let {indexDay(it)}
            val newDayInfo=DayInfo(delta.newDay,newQuantity,newMembers)
            updateUnit("D",OrderKey(date,dayIdentity(row.uuid,date)),oldDayRecord,indexDay(newDayInfo))
            val policy=delta.newDay.policy
            val oldQualified=oldDayInfo!=null && oldQuantity>=policy.targetValue
            val newQualified=newQuantity>=policy.targetValue
            val lifetime=objectInfo.lifetime
            val updated=Lifetime(lifetime.firstDate?.let {minOf(it,date)} ?: date,
                Math.addExact(lifetime.qualifiedDayCount,if(newQualified && !oldQualified)1 else 0),
                Math.addExact(lifetime.totalQuantity,fact.value.toLong()))
            top("O|${row.uuid}",objectRecord,indexRecord("O|${row.uuid}",metaWrite(2) {
                writeObject(this,objectInfo.copy(lifetime=updated))
            }))
            top(eventKey,null,indexRecord(eventKey,metaWrite(3){writeLocation(this,location)}))
            top(requestKey,null,indexRecord(requestKey,metaWrite(6){writeRequest(this,requestLocation)}))
            val oldAllocation=reader.index("A|allocation") ?: bad()
            val allocation=metaRead(oldAllocation.payload,5){readAllocation(this)}
            demand(allocation.totalFacts<MAX_RECORDS && fact.id==Math.addExact(allocation.lastFactId,1L) &&
                source.origin.queueId==Math.addExact(allocation.lastQueueId,1L))
            val next=Allocation(fact.id,source.origin.queueId,Math.addExact(allocation.totalFacts,1))
            top("A|allocation",oldAllocation,indexRecord("A|allocation",metaWrite(5){writeAllocation(this,next)}))
        } else {
            // Updates preserve membership/quantity/rule. Their original source closure
            // is replaced, and ACK may additionally normalize storage createdAt only.
            demand(oldDayInfo!=null && oldDayInfo.members>0 && oldDayInfo.quantity>=fact.value.toLong())
        }
        if(sourceLookup!=null) {
            val currentRecord=reader.index("C|current") ?: bad()
            demand(oldCurrent!=null && nextCurrent!=null)
            val actual=G0CountCurrentBlockCodec.decode(currentRecord.payload)
            demand(actual==oldCurrent)
            val oldHabit=actual.habits[row.uuid] ?: bad()
            if(append)demand(oldHabit.copy(activityRate=row.activityRate,activityRateUpdatedAt=row.activityRateUpdatedAt)==row)
            else demand(oldHabit==row)
            val previousPending=actual.pending.singleOrNull {it.requestId==source.origin.requestId}
            if(oldSource==null)demand(previousPending==null)
            else demand(previousPending==oldSource.pending?.let {Pending(source.origin.requestId,fact.uuid,it.id,oldSource.transmission!=null)})
            val nextPending=actual.pending.filterNot {it.requestId==source.origin.requestId}+listOfNotNull(
                source.pending?.let {Pending(source.origin.requestId,fact.uuid,it.id,source.transmission!=null)})
            val expected=actual.copy(habits=actual.habits+(row.uuid to row),pending=nextPending.sortedBy {it.queueId})
            demand(expected==nextCurrent)
            val nextRecord=indexRecord("C|current",G0CountCurrentBlockCodec.encode(expected))
            if(nextRecord!=currentRecord)top("C|current",currentRecord,nextRecord)
        } else demand(oldCurrent==null && nextCurrent==null)
        caller.ensureActive()
        if(topEdits.isEmpty())return@operationSource Update(state,emptyMap(),UpdateStats(ledger.loads,ledger.bytes,ledger.requests,ledger.processed,0,0,0,0,emptySet()))
        val indexPatch=measuredCall("count_block_rewrite_index") {
            ledger.apply(state.root,topEdits)
        }
        retain(indexPatch)
        fun copyState():State {
            if(sourceLookup!=null)return State(state.binding,indexPatch.root,emptyMap())
            var retained=0L
            for(bytes in state.blocks.values) {
                caller.ensureActive()
                if(bytes.size.toLong()>MAX_TOTAL_BYTES-retained)throw Rejected(Code.BUDGET)
                retained+=bytes.size
            }
            if(newBytes>MAX_TOTAL_BYTES-retained)throw Rejected(Code.BUDGET)
            val merged=LinkedHashMap(state.blocks);merged.putAll(newBlocks)
            return State(state.binding,indexPatch.root,merged)
        }
        val trace=G0PhaseTrace.current.get()
        val next=if(trace==null)copyState() else trace.call("count_block_map_copy",::copyState)
        caller.ensureActive()
        Update(next,Collections.unmodifiableMap(LinkedHashMap(newBlocks)),UpdateStats(ledger.loads,ledger.bytes,
            ledger.requests,ledger.processed,newBlocks.size,newBytes,unitEdits,topEdits.size,
            Collections.unmodifiableSet(LinkedHashSet(changedUnits))))
        }
    }

    private suspend fun <T> measured(name:String,block:suspend ()->T):T {
        val trace=G0PhaseTrace.current.get()
        return if(trace==null)block() else trace.stage(name,block)
    }
    private fun <T> measuredCall(name:String,block:()->T):T {
        val trace=G0PhaseTrace.current.get()
        return if(trace==null)block() else trace.cpuCall(name,block)
    }

    private fun indexDay(value:DayInfo):Record {
        val date=LocalDate.parse(value.row.localDate)
        return Record(OrderKey(date,dayIdentity(value.row.habitUuid,date)),"day-rule-v1",metaWrite(4) {
            writeLong(value.quantity);writeInt(value.members);bytes(G0CountBusinessBlockCodec.day(value.row))
        })
    }
    private suspend fun checkSource(fact:CompletionEntity,source:G0CountBusinessBlockCodec.Source) {
        val origin=source.origin;val operation=decodeNextOperationIntent(origin.intentJson)
        demand(origin.kind==NEXT_OPERATION && origin.protocol==5 && origin.queueId>0 &&
            origin.requestId==operation.operationId && operation.entityUuid==fact.uuid && operation.entityType=="activity_event" &&
            operation.action=="upsert" && operation.payload["event_type"]==JsonPrimitive("count_snapshot") &&
            operation.payload["activity_uuid"]==JsonPrimitive(fact.habitUuid) && operation.payload["local_date"]==JsonPrimitive(fact.recordedLocalDate) &&
            operation.payload["value"]==JsonPrimitive(fact.value))
        val pending=source.pending;val receipt=source.receipt;val transmission=source.transmission;val shadow=source.shadow
        demand((pending==null)!=(receipt==null))
        demand((transmission==null)==(source.transmissionHash==null))
        if(pending!=null)demand(pending.operationId==origin.requestId && pending.id==origin.queueId && pending.entityUuid==fact.uuid &&
            pending.recordType=="completion" && pending.action=="upsert" && pending.wireEntityUuid==fact.uuid &&
            pending.referenceUuid==fact.habitUuid && shadow==null)
        if(receipt!=null)demand(transmission!=null && shadow!=null && receipt.requestId==origin.requestId &&
            receipt.originHash==source.originHash && receipt.transmissionHash==source.transmissionHash &&
            shadow.entityUuid==fact.uuid && shadow.entityType=="activity_event")
        transmission?.let {demand(it.requestId==origin.requestId && it.queueId==origin.queueId &&
            it.accountId==origin.accountId && it.serverInstanceId==origin.serverInstanceId && it.syncEpoch==origin.syncEpoch)}
    }
    private fun checkTransition(oldFact:CompletionEntity?,fact:CompletionEntity,
        old:G0CountBusinessBlockCodec.Source?,next:G0CountBusinessBlockCodec.Source) {
        if(old==null) {
            demand(oldFact==null && next.pending!=null && next.transmission==null && next.receipt==null && next.shadow==null)
            return
        }
        demand(oldFact!=null && old.origin==next.origin && old.originHash==next.originHash)
        val oldWire=old.transmission;val newWire=next.transmission
        if(oldWire!=null) {
            demand(newWire!=null && oldWire.copy(wireBytes=newWire.wireBytes)==newWire &&
                oldWire.wireBytes.contentEquals(newWire.wireBytes) && old.transmissionHash==next.transmissionHash)
        }
        if(old.receipt!=null) {
            demand(G0CountBusinessBlockCodec.source(old)==G0CountBusinessBlockCodec.source(next) && oldFact==fact)
        } else if(next.receipt==null) {
            demand(old.pending==next.pending && oldFact==fact)
            demand(oldWire!=null || newWire!=null || G0CountBusinessBlockCodec.source(old)==G0CountBusinessBlockCodec.source(next))
        } else {
            demand(oldWire!=null && next.pending==null && oldFact?.copy(createdAt=fact.createdAt)==fact)
        }
    }

    /** The candidate is opt-in and only lives for this read/advance. The fixed lookup sees the
     * immutable base State plus the operation's append-only content-addressed staged maps. */
    private suspend fun <T> operation(state:State,lookup:(String)->Bytes?,reuse:Boolean,poll:()->Unit,
        body:suspend (Ledger)->T):T {
        return operationSource(state,HistoryNodeSession.Lookup {digest,limit->
            poll();lookup(digest)?.also {if(it.size>limit)throw Rejected(Code.BUDGET)}
        },reuse,poll,body)
    }
    private suspend fun <T> operationSource(state:State,lookup:HistoryNodeSession.Lookup,reuse:Boolean,poll:()->Unit,
        body:suspend (Ledger)->T):T {
        val raw:(String)->Bytes?={digest->lookup.load(digest,HistoryBlockCodec.MAX_NODE_BYTES)}
        if(!reuse)return body(Ledger(state,raw,poll,null))
        return HistoryNodeSession.use(state,state.root,lookup) { session ->body(Ledger(state,raw,poll,session)) }
    }

    private class Ledger(val state:State,val lookup:((String)->Bytes?)?,val poll:()->Unit,
        val session:HistoryNodeSession.Session?) {
        private var rawLoads=0;private var rawBytes=0L;private var rawRequests=0;private var rawProcessed=0L
        // Session hits remain logical visits. Only strict decode misses are unique loads here;
        // count_block_node_decode retains separate CPU spans for both paired paths.
        val loads:Int get()=session?.stats()?.decodedNodes ?: rawLoads
        val bytes:Long get()=session?.stats()?.decodedBytes ?: rawBytes
        val requests:Int get()=session?.stats()?.visits ?: rawRequests
        val processed:Long get()=session?.stats()?.processedBytes ?: rawProcessed
        private val cached=HashMap<String,Bytes>()
        val loader=AuthenticatedHistoryDirectory.Loader {digest,limit->
            check(session==null)
            poll()
            if(rawRequests>=4096)throw Rejected(Code.BUDGET)
            val value=cached[digest] ?: requireNotNull(lookup).invoke(digest) ?: bad()
            if(value.size>limit || value.size.toLong()>64L*1024*1024-rawProcessed)throw Rejected(Code.BUDGET)
            rawRequests++;rawProcessed+=value.size
            if(digest !in cached) {
                if(value.size.toLong()>MAX_TOTAL_BYTES-rawBytes)throw Rejected(Code.BUDGET)
                rawLoads++;rawBytes+=value.size;cached[digest]=value
            }
            value.toByteArray()
        }
        fun tree(root:Ref)=session?.tree(state,root)
        fun apply(root:Ref,edits:List<HistoryBlockUpdater.Edit>):HistoryBlockUpdater.Patch {
            val scoped=tree(root)
            return if(scoped==null)HistoryBlockUpdater.apply(root,edits,loader,checkCancelled=poll)
                else HistoryBlockUpdater.apply(root,edits,scoped,checkCancelled=poll)
        }
    }
    private class Reader(val binding:String,val root:Ref,val ledger:Ledger) {
        init {demand(root.range==LeafRange(unit(binding,"index","scope",YearMonth.from(indexDate)),null,null))}
        fun index(key:String):Record?=point(root,OrderKey(indexDate,key))?.also {demand(it.sourceId==INDEX_SOURCE)}
        fun point(ref:Ref,key:OrderKey):Record?=measuredCall("count_block_lookup") {
            val scoped=ledger.tree(ref)
            (if(scoped==null)tree.readPoint(ref,key,ledger.loader,checkCancelled=ledger.poll)
                else tree.readPoint(ref,key,scoped,checkCancelled=ledger.poll)).records.singleOrNull()
        }
        fun objectInfo(uuid:String):ObjectInfo {
            val record=index("O|$uuid") ?: bad()
            return metaRead(record.payload,2) {readObject(this)}.also {demand(it.uuid==uuid)}
        }
        fun unit(kind:String,uuid:String,month:YearMonth):Ref? {
            val record=index(unitIndex(kind,uuid,month)) ?: return null
            return metaRead(record.payload,1) {
                demand(text()==kind && text()==uuid && text()==month.toString())
                Ref(LeafRange(unit(binding,kind,uuid,month),null,null),readInt(),readLong(),text())
            }
        }
        fun day(uuid:String,date:LocalDate):DayInfo? {
            val ref=unit("D",uuid,YearMonth.from(date)) ?: return null
            val record=point(ref,OrderKey(date,dayIdentity(uuid,date))) ?: return null
            demand(record.sourceId=="day-rule-v1")
            return metaRead(record.payload,4) {
                val quantity=readLong();val members=readInt();demand(quantity>0 && members in 1..MAX_RECORDS)
                DayInfo(G0CountBusinessBlockCodec.day(bytes()),quantity,members)
            }.also {demand(it.row.habitUuid==uuid && it.row.localDate==date.toString())}
        }
        private data class RequestCore(val fact:CompletionEntity,val source:G0CountBusinessBlockCodec.Source,val day:DayInfo)
        private val requests=linkedMapOf<String,RequestCore?>()
        private suspend fun requestCore(id:String):RequestCore? {
            if(id in requests)return requests[id]
            val record=index("R|$id") ?: return null.also {requests[id]=null}
            val request=metaRead(record.payload,6){readRequest(this)}
            val located=index("E|${request.factUuid}") ?: bad()
            val location=metaRead(located.payload,3){readLocation(this)}
            demand(location.requestId==id)
            val key=OrderKey(location.date,request.factUuid)
            val factRef=unit("F",location.habitUuid,YearMonth.from(location.date)) ?: bad()
            val sourceRef=unit("S",location.habitUuid,YearMonth.from(location.date)) ?: bad()
            val factRecord=point(factRef,key) ?: bad();val sourceRecord=point(sourceRef,key) ?: bad()
            demand(factRecord.sourceId==id && sourceRecord.sourceId==id)
            val fact=G0CountBusinessBlockCodec.fact(factRecord.payload)
            val source=G0CountBusinessBlockCodec.source(sourceRecord.payload)
            demand(fact.id==location.factId && fact.uuid==request.factUuid && fact.habitId==location.habitId &&
                fact.habitUuid==location.habitUuid && fact.recordedLocalDate==location.date.toString() &&
                source.origin.requestId==id && source.origin.queueId==request.queueId)
            val info=objectInfo(location.habitUuid);demand(info.id==fact.habitId)
            checkSource(fact,source)
            val day=day(location.habitUuid,location.date) ?: bad()
            demand(day.row.habitId==fact.habitId && day.quantity>=fact.value.toLong())
            demand(NextCommonFactMapper.countPolicy(decodeNextOperationIntent(source.origin.intentJson).payload)==day.row.policy)
            val found=RequestCore(fact,source,day);requests[id]=found;return found
        }
        suspend fun request(id:String):RequestState? {
            val current=requestCore(id) ?: return null
            val day=current.day
            val firstId=day.row.originRequestId ?: bad()
            val first=requestCore(firstId) ?: bad()
            demand(first.fact.uuid==day.row.firstEventUuid && first.fact.habitId==current.fact.habitId &&
                first.fact.habitUuid==current.fact.habitUuid && first.fact.recordedLocalDate==current.fact.recordedLocalDate &&
                first.source.originHash==day.row.originHash && first.day==day)
            return RequestState(current.fact,current.source,DayState(day.row,day.quantity,day.members),first.fact,first.source)
        }

        fun dayRange(ref:Ref,date:LocalDate):List<Record> {
            val next=if(date.dayOfMonth==date.lengthOfMonth())null else OrderKey(date.plusDays(1),"!")
            return measuredCall("count_block_lookup") {
                val scoped=ledger.tree(ref)
                (if(scoped==null)tree.readRange(ref,OrderKey(date,"!"),next,ledger.loader,checkCancelled=ledger.poll)
                    else tree.readRange(ref,OrderKey(date,"!"),next,scoped,checkCancelled=ledger.poll)).records
            }
        }
    }

    private fun unit(binding:String,kind:String,uuid:String,month:YearMonth)=
        UnitKey(Partition(Scope(binding),"count-incremental-$kind-v1",uuid,null),month)
    private fun dayIdentity(uuid:String,date:LocalDate)="D|$uuid|$date"
    private fun unitIndex(kind:String,uuid:String,month:YearMonth)="U|$kind|$uuid|$month"
    private fun indexRecord(id:String,payload:Bytes)=Record(OrderKey(indexDate,id),INDEX_SOURCE,payload)
    private fun DataOutputStream.text(value:String) {demand(value.length in 1..128);writeInt(value.length);value.forEach {writeChar(it.code)}}
    private fun DataInputStream.text():String {val size=readInt();demand(size in 1..128 && size<=available()/2);return CharArray(size){readChar()}.concatToString()}
    private fun DataOutputStream.bytes(value:Bytes) {writeInt(value.size);write(value.toByteArray())}
    private fun DataInputStream.bytes():Bytes {val n=readInt();demand(n in 0..HistoryBlockCodec.MAX_NODE_BYTES && n<=available());return Bytes.copyOf(ByteArray(n).also(::readFully))}
    private fun DataOutputStream.policy(value:CountDayPolicy) {writeInt(value.targetValue);writeByte(if(value.isCountdown)1 else 0)}
    private fun DataInputStream.policy():CountDayPolicy {val target=readInt();val direction=readUnsignedByte();demand(target>0 && direction in 0..1);return CountDayPolicy(target,direction==1)}
    private fun writeObject(out:DataOutputStream,obj:ObjectInfo)=with(out) {
        writeLong(obj.id);text(obj.uuid);policy(obj.planRule);val s=obj.lifetime
        writeByte(if(s.firstDate==null)0 else 1);s.firstDate?.let {writeLong(it.toEpochDay())}
        writeInt(s.qualifiedDayCount);writeLong(s.totalQuantity)
    }
    private fun readObject(input:DataInputStream):ObjectInfo=with(input) {
        val id=readLong();val uuid=text();val policy=policy();val firstTag=readUnsignedByte();demand(firstTag in 0..1)
        val first=if(firstTag==1)LocalDate.ofEpochDay(readLong()) else null
        val qualified=readInt();val total=readLong();demand(id>0 && qualified>=0 && total>=0 && (first==null)==(total==0L))
        ObjectInfo(id,uuid,policy,Lifetime(first,qualified,total))
    }
    private fun writeAllocation(out:DataOutputStream,value:Allocation)=with(out) {
        writeLong(value.lastFactId);writeLong(value.lastQueueId);writeInt(value.totalFacts)
    }
    private fun readAllocation(input:DataInputStream):Allocation=with(input) {
        Allocation(readLong(),readLong(),readInt()).also {demand(it.lastFactId>=0 && it.lastQueueId>=0 && it.totalFacts in 0..MAX_RECORDS)}
    }
    private fun writeRequest(out:DataOutputStream,value:RequestLocation)=with(out) {text(value.factUuid);writeLong(value.queueId)}
    private fun readRequest(input:DataInputStream):RequestLocation=with(input) {
        RequestLocation(text(),readLong()).also {demand(it.queueId>0)}
    }
    private fun writeLocation(out:DataOutputStream,value:Location)=with(out) {writeLong(value.habitId);text(value.habitUuid);writeLong(value.date.toEpochDay());writeLong(value.factId);text(value.requestId)}
    private fun readLocation(input:DataInputStream):Location=with(input) {Location(readLong(),text(),LocalDate.ofEpochDay(readLong()),readLong(),text())}
    private fun metaWrite(type:Int,body:DataOutputStream.()->Unit):Bytes {
        val buffer=ByteArrayOutputStream();DataOutputStream(buffer).use {it.writeInt(0x43494d31);it.writeInt(FORMAT);it.writeInt(type);it.body()}
        if(buffer.size()>HistoryBlockCodec.MAX_NODE_BYTES-4096)throw Rejected(Code.BUDGET)
        return Bytes.copyOf(buffer.toByteArray())
    }
    private fun <T> metaRead(bytes:Bytes,type:Int,body:DataInputStream.()->T):T=try {
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {input->
            demand(input.readInt()==0x43494d31 && input.readInt()==FORMAT && input.readInt()==type)
            input.body().also {demand(input.available()==0)}
        }
    } catch(failure:Rejected){throw failure} catch(_:java.io.IOException){bad()} catch(_:java.time.DateTimeException){bad()}
    private fun demand(value:Boolean) {if(!value)bad()}
    private fun bad():Nothing=throw Rejected(Code.MALFORMED)
}
