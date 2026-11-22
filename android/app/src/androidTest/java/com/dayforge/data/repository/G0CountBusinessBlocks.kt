package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.AuthenticatedHistoryDirectory

import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.CountDayEntity
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

/** Representation only. build(FrozenData) never grants authority. Only the Store's private handle
 * registry may associate this output with its validated, unchanged owner and fresh authority.
 * Initial build enumerates the admitted snapshot; queries never reconstruct CountHistory/Image.
 */
internal object G0CountBusinessBlocks {
    data class Summary(val today:LocalDate,val todayQuantity:Long,val completedToday:Boolean,
        val firstDate:LocalDate?,val qualifiedDayCount:Int,val totalQuantity:Long)
    data class ReadStats(val loadedNodes:Int,val loadedBytes:Long,val decodedFacts:Int,val decodedSources:Int,
        val nodeRequests:Int,val processedNodeBytes:Long)
    data class DayRead(val date:LocalDate,val habitId:Long,val habitUuid:String,val quantity:Long,
        val storedPolicy:CountDayPolicy?,val effectivePolicy:CountDayPolicy?,val unknown:Boolean,val day:CountDayEntity?,val facts:List<CompletionEntity>,
        val sourceRequestIds:Set<String>,val summary:Summary,val stats:ReadStats)
    private data class ObjectInfo(val id:Long,val uuid:String,val todayPolicy:CountDayPolicy,val summary:Summary)
    private data class DayInfo(val row:CountDayEntity,val quantity:Long,val members:Int)
    private data class Location(val habitId:Long,val habitUuid:String,val date:LocalDate,val factId:Long,val requestId:String)
    private val indexDate=LocalDate.of(1970,1,1)
    private const val FORMAT=1
    private const val MAX_RECORDS=736
    private const val MAX_TOTAL_BYTES=32L*1024*1024
    private const val INDEX_SOURCE="count-index-v1"
    private val tree=AuthenticatedHistoryDirectory()

    class Built internal constructor(private val binding:String,private val root:Ref,
        private val blocks:Map<String,Bytes>) {
        val rootDigest:String get()=root.digest
        /** Bound to one root. Caller must independently retain Store current/read authority. */
        suspend fun readDay(habitUuid:String,date:LocalDate):DayRead {
            val caller=currentCoroutineContext()
            val reader=Reader(binding,root,blocks,caller::ensureActive)
            val objectRecord=reader.index("O|$habitUuid") ?: bad()
            val info=metaRead(objectRecord.payload,2) { readObject(this) }
            demand(info.uuid==habitUuid)
            val month=YearMonth.from(date)
            val factsRoot=reader.unit("F",habitUuid,month)
            val sourcesRoot=reader.unit("S",habitUuid,month)
            val daysRoot=reader.unit("D",habitUuid,month)
            demand((factsRoot==null)==(sourcesRoot==null) && (factsRoot==null)==(daysRoot==null))
            val dayRecord=daysRoot?.let {reader.point(it,OrderKey(date,dayIdentity(habitUuid,date)))}
            val daily=dayRecord?.let { metaRead(it.payload,4) {
                val quantity=readLong();val members=readInt();demand(quantity>=0 && members in 1..MAX_RECORDS)
                DayInfo(G0CountBusinessBlockCodec.day(bytes()),quantity,members)
            }}
            val factRecords=factsRoot?.let {reader.dayRange(it,date)} ?: emptyList()
            val sourceRecords=sourcesRoot?.let {reader.dayRange(it,date)} ?: emptyList()
            demand(factRecords.size==sourceRecords.size)
            if(daily==null)demand(factRecords.isEmpty())
            val sources=sourceRecords.associate { record ->
                val source=G0CountBusinessBlockCodec.source(record.payload)
                demand(record.sourceId==source.origin.requestId)
                val operation=decodeNextOperationIntent(source.origin.intentJson)
                demand(operation.entityUuid==record.key.stableFactId && operation.operationId==source.origin.requestId)
                record.key.stableFactId to source
            }
            demand(sources.size==sourceRecords.size)
            var quantity=0L
            val requestIds=linkedSetOf<String>()
            val facts=factRecords.map { record ->
                val fact=G0CountBusinessBlockCodec.fact(record.payload)
                demand(fact.uuid==record.key.stableFactId && fact.habitId==info.id && fact.habitUuid==habitUuid &&
                    fact.recordedLocalDate==date.toString() && record.key.recordedBusinessDate==date)
                val source=sources[fact.uuid] ?: bad()
                demand(record.sourceId==source.origin.requestId && requestIds.add(source.origin.requestId))
                val located=reader.index("E|${fact.uuid}") ?: bad()
                val location=metaRead(located.payload,3) {readLocation(this)}
                demand(location==Location(info.id,habitUuid,date,fact.id,source.origin.requestId))
                quantity=Math.addExact(quantity,fact.value.toLong())
                fact
            }
            demand(sources.keys==facts.map {it.uuid}.toSet())
            if(daily!=null) {
                demand(daily.row.habitId==info.id && daily.row.habitUuid==habitUuid && daily.row.localDate==date.toString() &&
                    daily.members==facts.size && daily.quantity==quantity)
                val first=sources[daily.row.firstEventUuid] ?: bad()
                demand(daily.row.originRequestId==first.origin.requestId && daily.row.originHash==first.originHash)
            }
            val policy=daily?.row?.policy ?: if(date==info.summary.today)info.todayPolicy else null
            val sorted=facts.sortedWith(compareByDescending<CompletionEntity>{it.recordedLocalDate}
                .thenByDescending {it.actualCompletedAt ?: it.date}.thenByDescending {it.id})
            return DayRead(date,info.id,habitUuid,quantity,daily?.row?.policy,policy,false,daily?.row,
                Collections.unmodifiableList(sorted),Collections.unmodifiableSet(requestIds),info.summary,
                ReadStats(reader.loads,reader.bytes,facts.size,sources.size,reader.requests,reader.processed))
        }
    }

    suspend fun build(binding:String,snapshot:G0HouseholdCountImage.FrozenData):Built {
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
            val summary=Summary(data.today,todayQuantity,todayQuantity>=currentPolicy.targetValue,
                quantities.keys.minOrNull(),quantities.count {(date,value)->history.policies[date]?.let {value>=it.targetValue}==true},total)
            addIndex("O|$uuid",metaWrite(2){writeObject(this,ObjectInfo(habit.id,uuid,currentPolicy,summary))})
            for(fact in history.completions) {
                demand(fact.habitId==habit.id && fact.habitUuid==uuid)
                val date=LocalDate.parse(fact.recordedLocalDate);val source=sourceByFact.getValue(fact.uuid)
                add("F",uuid,date,fact.uuid,source.origin.requestId,G0CountBusinessBlockCodec.fact(fact))
                add("S",uuid,date,fact.uuid,source.origin.requestId,G0CountBusinessBlockCodec.source(source))
                addIndex("E|${fact.uuid}",metaWrite(3){writeLocation(this,Location(habit.id,uuid,date,fact.id,source.origin.requestId))})
            }
            for(day in dayRows) {
                val date=LocalDate.parse(day.localDate);val members=byDate.getValue(date)
                val first=sourceByFact[day.firstEventUuid] ?: bad()
                demand(day.habitUuid==uuid && day.originRequestId==first.origin.requestId && day.originHash==first.originHash)
                add("D",uuid,date,dayIdentity(uuid,date),"day-rule-v1",metaWrite(4){writeLong(quantities.getValue(date));writeInt(members.size);bytes(G0CountBusinessBlockCodec.day(day))})
            }
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
            val kind=unit.partition.domain.removePrefix("count-").removeSuffix("-v1")
            addIndex(unitIndex(kind,unit.partition.objectId,unit.month),metaWrite(1){
                text(kind);text(unit.partition.objectId);text(unit.month.toString());writeInt(built.root.height);writeLong(built.root.count);text(built.root.digest)
            })
        }
        val directory=tree.build(unit(binding,"index","scope",YearMonth.from(indexDate)),index);retain(directory)
        return Built(binding,directory.root,Collections.unmodifiableMap(stored))
    }

    private class Reader(val binding:String,val root:Ref,val blocks:Map<String,Bytes>,val poll:()->Unit) {
        var loads=0;var bytes=0L;var requests=0;var processed=0L
        // This cache is local to exactly one immutable root/query; it never caches authority.
        private val cached=HashMap<String,Bytes>()
        private val loader=AuthenticatedHistoryDirectory.Loader {digest,limit->
            poll()
            if(requests>=4096)throw Rejected(Code.BUDGET)
            val value=cached[digest] ?: blocks[digest] ?: bad()
            if(value.size>limit || value.size.toLong()>64L*1024*1024-processed)throw Rejected(Code.BUDGET)
            requests++;processed+=value.size
            if(digest !in cached) {
                if(value.size.toLong()>MAX_TOTAL_BYTES-bytes)throw Rejected(Code.BUDGET)
                loads++;bytes+=value.size;cached[digest]=value
            }
            value.toByteArray()
        }
        fun index(key:String):Record?=point(root,OrderKey(indexDate,key))?.also {demand(it.sourceId==INDEX_SOURCE)}
        fun point(ref:Ref,key:OrderKey):Record?=tree.readPoint(ref,key,loader,checkCancelled=poll).records.singleOrNull()
        fun unit(kind:String,uuid:String,month:YearMonth):Ref? {
            val record=index(unitIndex(kind,uuid,month)) ?: return null
            return metaRead(record.payload,1) {
                demand(text()==kind && text()==uuid && text()==month.toString())
                Ref(LeafRange(unit(binding,kind,uuid,month),null,null),readInt(),readLong(),text())
            }
        }
        fun dayRange(ref:Ref,date:LocalDate):List<Record> {
            val next=if(date.dayOfMonth==date.lengthOfMonth())null else OrderKey(date.plusDays(1),"!")
            return tree.readRange(ref,OrderKey(date,"!"),next,loader,checkCancelled=poll).records
        }
    }

    private fun unit(binding:String,kind:String,uuid:String,month:YearMonth)=
        UnitKey(Partition(Scope(binding),"count-$kind-v1",uuid,null),month)
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
        writeLong(obj.id);text(obj.uuid);policy(obj.todayPolicy);val s=obj.summary
        writeLong(s.today.toEpochDay());writeLong(s.todayQuantity);writeByte(if(s.completedToday)1 else 0)
        writeByte(if(s.firstDate==null)0 else 1);s.firstDate?.let {writeLong(it.toEpochDay())}
        writeInt(s.qualifiedDayCount);writeLong(s.totalQuantity)
    }
    private fun readObject(input:DataInputStream):ObjectInfo=with(input) {
        val id=readLong();val uuid=text();val policy=policy();val today=LocalDate.ofEpochDay(readLong());val quantity=readLong()
        val completed=readUnsignedByte();val firstTag=readUnsignedByte();demand(completed in 0..1 && firstTag in 0..1)
        val first=if(firstTag==1)LocalDate.ofEpochDay(readLong()) else null
        val qualified=readInt();val total=readLong();demand(id>0 && quantity>=0 && qualified>=0 && total>=quantity)
        ObjectInfo(id,uuid,policy,Summary(today,quantity,completed==1,first,qualified,total))
    }
    private fun writeLocation(out:DataOutputStream,value:Location)=with(out) {writeLong(value.habitId);text(value.habitUuid);writeLong(value.date.toEpochDay());writeLong(value.factId);text(value.requestId)}
    private fun readLocation(input:DataInputStream):Location=with(input) {Location(readLong(),text(),LocalDate.ofEpochDay(readLong()),readLong(),text())}
    private fun metaWrite(type:Int,body:DataOutputStream.()->Unit):Bytes {
        val buffer=ByteArrayOutputStream();DataOutputStream(buffer).use {it.writeInt(0x43424d31);it.writeInt(FORMAT);it.writeInt(type);it.body()}
        if(buffer.size()>HistoryBlockCodec.MAX_NODE_BYTES-4096)throw Rejected(Code.BUDGET)
        return Bytes.copyOf(buffer.toByteArray())
    }
    private fun <T> metaRead(bytes:Bytes,type:Int,body:DataInputStream.()->T):T=try {
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {input->
            demand(input.readInt()==0x43424d31 && input.readInt()==FORMAT && input.readInt()==type)
            input.body().also {demand(input.available()==0)}
        }
    } catch(failure:Rejected){throw failure} catch(_:java.io.IOException){bad()} catch(_:java.time.DateTimeException){bad()}
    private fun demand(value:Boolean) {if(!value)bad()}
    private fun bad():Nothing=throw Rejected(Code.MALFORMED)
}
