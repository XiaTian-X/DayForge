package com.dayforge.data.repository

import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.api.NextSyncReplyInvalid
import com.dayforge.data.api.decodeSyncReply
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Pure, bounded Q data. Parsing or constructing this type NEVER issues authority,
 * activates an index or proves business validity. The package owner must authenticate
 * this exact encoding against its P/H snapshot before installing data after known END.
 * No activity/process/connection lifecycle event deletes or invalidates these bytes.
 */
internal class G0HouseholdCountImage private constructor(
    private val frozen:Data,
    private val encoding:ByteArray
) {
    class Malformed(cause:Throwable?=null):IllegalArgumentException("G0_COUNT_IMAGE_MALFORMED",cause)
    class BudgetExceeded:IllegalStateException("G0_COUNT_IMAGE_BUDGET")
    data class Limits(val maxFacts:Int,val availableBytes:Long) {
        init {
            require(maxFacts in 1..128 || maxFacts==736)
            require(availableBytes in 0..32L*1024*1024)
        }
    }
    data class DayKey(val habitId:Long,val localDate:String)
    /** Owner-local snapshot only. No serialization, suspension or authentication.
     * Its private descendants cannot be borrowed or mutated by encoding callers.
     */
    class FrozenData private constructor(private val data:Data,private val maxFacts:Int,
        private val copyBudget:Long) {
        companion object {
            internal fun capture(data:Data,limits:Limits):FrozenData=checkedSync {
                shape(data,limits)
                FrozenData(freezeData(data,limits.availableBytes) {},limits.maxFacts,limits.availableBytes)
            }
        }
        /** Data-only owned projection copy. This method neither proves business validity nor
         * grants publication authority; only the originating Store may issue a block handle. */
        internal fun copyForProjection():Data=checkedSync {
            freezeData(data,copyBudget) {}
        }
        internal suspend fun image(limits:Limits):G0HouseholdCountImage=checked {
            demand(limits.maxFacts==maxFacts);shape(data,limits)
            val caller=currentCoroutineContext()
            val wireBytes=data.sent.values.sumOf {it.wireBytes.size.toLong()}
            if(wireBytes>limits.availableBytes)throw BudgetExceeded()
            val writer=Writer(limits.availableBytes-wireBytes,caller::ensureActive)
            writeData(writer,data,maxFacts)
            G0HouseholdCountImage(data,writer.finish())
        }
    }
    data class History(
        val today:LocalDate,
        val policies:Map<LocalDate,CountDayPolicy>,
        val quantities:Map<LocalDate,Long>,
        val todayPolicy:CountDayPolicy?,
        val unknownDates:Set<LocalDate>,
        val completions:List<CompletionEntity>
    ) {
        /** The caller must supply a freshly derived actionIdentity when needed. */
        fun withoutAuthority()=CountHistory(today,policies,quantities,todayPolicy,unknownDates,completions,
            roundHead=null,actionIdentity=null)
        companion object {
            fun from(value:CountHistory):History {
                require(value.roundHead==null) {"G0_COUNT_IMAGE_PLAIN_ONLY"}
                return History(value.today,value.policies,value.quantities,value.todayPolicy,
                    value.unknownDates,value.completions)
            }
        }
    }
    data class ObjectData(val habit:HabitEntity,val history:History)
    /** These constructor arguments are data, not verification evidence. freeze() owns
     * every mutable descendant before its first codec suspension. Exported copies can
     * never mutate the image, including transmission ByteArrays and JSON descendants.
     */
    data class Data(
        val today:LocalDate,
        val defaultZone:String,
        val objects:Map<String,ObjectData>,
        val days:Map<DayKey,CountDayEntity>,
        val daySources:Map<DayKey,Set<String>>,
        val origins:Map<String,NextRequestOriginEntity>,
        val operations:Map<String,SyncV2Operation>,
        val originHashes:Map<String,String>,
        val pending:Map<String,SyncOutboxEntity>,
        val sent:Map<String,NextTransmissionEntity>,
        val receipts:Map<String,NextAcceptanceEntity>,
        val transmissionHashes:Map<String,String>,
        val shadows:Map<String,SyncEntityStateEntity>,
        val lastFactId:Long,
        val lastQueueId:Long,
        val totalFacts:Int
    )

    val capturedToday:LocalDate get()=frozen.today
    val capturedDefaultZone:String get()=frozen.defaultZone
    val encodedBytes:Int get()=encoding.size
    /** Exact owned byte buffers retained by this value; strings/DTO/JVM overhead and
     * temporary encoding/decoding/export copies are measured separately. */
    val retainedBytes:Long get()=encoding.size.toLong()+frozen.sent.values.sumOf {it.wireBytes.size.toLong()}
    fun matchesEnvironment(today:LocalDate,defaultZone:String)=
        frozen.today==today && frozen.defaultZone==defaultZone
    /** Copies are temporary allocations, not a promise that JVM heap <= raw budget. */
    fun encode():ByteArray=encoding.copyOf()
    /** Digest of the exact private bytes decoded, not a later reserialization of Q. */
    fun encodingDigest():ByteArray=MessageDigest.getInstance("SHA-256").digest(encoding)
    fun copyForInstall():Data=freezeData(frozen,Long.MAX_VALUE) {}

    companion object {
        private val MAGIC=byteArrayOf(0x44,0x46,0x47,0x30,0x43,0x4e,0x54,0x49) // DFG0CNTI
        const val FORMAT_VERSION=1
        private const val ENVIRONMENT=100
        private const val OBJECTS=101
        private const val DAYS=102
        private const val DAY_SOURCES=103
        private const val ORIGINS=104
        private const val OPERATIONS=105
        private const val ORIGIN_HASHES=106
        private const val PENDING=107
        private const val SENT=108
        private const val RECEIPTS=109
        private const val TRANSMISSION_HASHES=110
        private const val SHADOWS=111
        private const val WATERMARKS=112
        private const val OBJECT=200
        private const val HISTORY=201
        private const val HABIT=202
        private const val COMPLETION=203
        private const val DAY=204
        private const val QUEUE=205
        private const val ORIGIN=206
        private const val TRANSMISSION=207
        private const val RECEIPT=208
        private const val SHADOW=209
        private const val DAY_KEY=210
        private const val POLICY=211
        private const val SCHEDULE=212
        private const val APPEARANCE=213
        private const val PLAN_METADATA=214
        private const val POLICIES=220
        private const val QUANTITIES=221
        private const val UNKNOWN_DATES=222
        private const val FACTS=223
        private const val SOURCE_IDS=224
        private const val WEEKDAYS=225
        private val OPERATION_FIELDS=setOf("operation_id","entity_type","entity_uuid","action","base_revision","payload")

        fun snapshot(data:Data,limits:Limits):FrozenData=FrozenData.capture(data,limits)
        /** Call after releasing the owner; strict operation encoding may suspend. */
        suspend fun encode(snapshot:FrozenData,limits:Limits):G0HouseholdCountImage=snapshot.image(limits)
        suspend fun decode(bytes:ByteArray,limits:Limits):G0HouseholdCountImage=checked {
            if(bytes.size.toLong()>limits.availableBytes)throw BudgetExceeded()
            val owned=bytes.copyOf() // before the first decoder suspension
            val caller=currentCoroutineContext()
            val reader=Reader(owned,limits,caller::ensureActive)
            val data=readData(reader,limits)
            reader.end();shape(data,limits)
            G0HouseholdCountImage(freezeData(data,limits.availableBytes,caller::ensureActive),owned).also {
                if(it.retainedBytes>limits.availableBytes)throw BudgetExceeded()
            }
        }
        private suspend inline fun <T> checked(block:()->T):T=try {block()}
            catch(cancel:CancellationException) {throw cancel}
            catch(budget:BudgetExceeded) {throw budget}
            catch(malformed:Malformed) {throw malformed}
            catch(invalid:IllegalArgumentException) {throw Malformed(invalid)}
            catch(invalid:IndexOutOfBoundsException) {throw Malformed(invalid)}
            catch(invalid:java.time.DateTimeException) {throw Malformed(invalid)}
        private inline fun <T> checkedSync(block:()->T):T=try {block()}
            catch(budget:BudgetExceeded) {throw budget}
            catch(malformed:Malformed) {throw malformed}
            catch(invalid:IllegalArgumentException) {throw Malformed(invalid)}
            catch(invalid:IndexOutOfBoundsException) {throw Malformed(invalid)}
            catch(invalid:java.time.DateTimeException) {throw Malformed(invalid)}

        private fun demand(value:Boolean) {if(!value)throw Malformed()}
        private fun shape(d:Data,limits:Limits) {
            demand(d.objects.size==2 && d.objects.values.map {it.habit.id}.distinct().size==2)
            demand(d.defaultZone.isNotEmpty() && d.defaultZone.length<=64)
            demand(d.objects.all {(key,value)->key==value.habit.uuid && value.history.today==d.today})
            val facts=d.objects.values.sumOf {it.history.completions.size.toLong()}
            if(facts>limits.maxFacts)throw BudgetExceeded()
            demand(d.totalFacts>=0 && facts==d.totalFacts.toLong() && d.lastFactId>=0 && d.lastQueueId>=0)
            listOf(d.days,d.daySources,d.origins,d.operations,d.originHashes,d.pending,d.sent,d.receipts,
                d.transmissionHashes,d.shadows).forEach {if(it.size>limits.maxFacts)throw BudgetExceeded()}
            d.objects.values.forEach {
                val schedule=it.habit.schedule
                if(schedule is HabitSchedule.Weekly && schedule.daysOfWeek.size>7)throw BudgetExceeded()
                if(it.history.policies.size>limits.maxFacts+1 || it.history.quantities.size>limits.maxFacts+1 ||
                    it.history.unknownDates.size>limits.maxFacts+1)throw BudgetExceeded()
            }
            demand(d.days.all {(key,row)->key.habitId==row.habitId && key.localDate==row.localDate})
            d.daySources.values.forEach {if(it.size>limits.maxFacts)throw BudgetExceeded()}
            demand(d.origins.all {(key,row)->key==row.requestId})
            demand(d.operations.all {(key,row)->key==row.operationId})
            demand(d.pending.all {(key,row)->key==row.operationId})
            demand(d.sent.all {(key,row)->key==row.requestId})
            d.sent.values.forEach {if(it.wireBytes.size>SYNC_REQUEST_LIMIT)throw BudgetExceeded()}
            demand(d.receipts.all {(key,row)->key==row.requestId})
            demand(d.shadows.all {(key,row)->key==row.entityUuid})
        }
        private fun <T> frozenList(values:List<T>):List<T> = Collections.unmodifiableList(ArrayList(values))
        private fun <T> frozenSet(values:Set<T>):Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
        private fun <K,V> frozenMap(values:Map<K,V>):Map<K,V> = Collections.unmodifiableMap(LinkedHashMap(values))
        private fun freezeData(d:Data,limit:Long,poll:()->Unit):Data {
            var copied=0L
            fun reserve(size:Long) {if(size<0 || size>limit-copied)throw BudgetExceeded();copied+=size;poll()}
            fun json(value:JsonElement,depth:Int=0):JsonElement {
                demand(depth<=64);reserve(1)
                return when(value) {
                    is JsonObject->{
                        val fields=linkedMapOf<String,JsonElement>()
                        for((key,child) in value) {reserve(key.length.toLong()*2);fields[key]=json(child,depth+1)}
                        JsonObject(frozenMap(fields))
                    }
                    is JsonArray->{
                        val items=mutableListOf<JsonElement>()
                        for(child in value)items+=json(child,depth+1)
                        JsonArray(frozenList(items))
                    }
                    is JsonPrimitive->{reserve(value.content.length.toLong()*2);value}
                }
            }
            val objects=linkedMapOf<String,ObjectData>()
            for((key,value) in d.objects) {
                poll();val h=value.history
                val schedule=value.habit.schedule
                val habit=value.habit.copy(schedule=if(schedule is HabitSchedule.Weekly)
                    HabitSchedule.Weekly(frozenList(schedule.daysOfWeek)) else schedule)
                objects[key]=ObjectData(habit,h.copy(policies=frozenMap(h.policies),quantities=frozenMap(h.quantities),
                    unknownDates=frozenSet(h.unknownDates),completions=frozenList(h.completions)))
            }
            val sent=linkedMapOf<String,NextTransmissionEntity>()
            for((key,row) in d.sent) {reserve(row.wireBytes.size.toLong());sent[key]=row.copy(wireBytes=row.wireBytes.copyOf())}
            val operations=linkedMapOf<String,SyncV2Operation>()
            for((key,row) in d.operations)operations[key]=row.copy(payload=json(row.payload).jsonObject)
            val sources=linkedMapOf<DayKey,Set<String>>()
            for((key,ids) in d.daySources) {poll();sources[key]=frozenSet(ids)}
            return d.copy(objects=frozenMap(objects),days=frozenMap(d.days),daySources=frozenMap(sources),
                origins=frozenMap(d.origins),operations=frozenMap(operations),originHashes=frozenMap(d.originHashes),
                pending=frozenMap(d.pending),sent=frozenMap(sent),receipts=frozenMap(d.receipts),
                transmissionHashes=frozenMap(d.transmissionHashes),shadows=frozenMap(d.shadows))
        }

        private suspend fun writeData(w:Writer,d:Data,maxFacts:Int) {
            w.raw(MAGIC);w.int(FORMAT_VERSION);w.int(maxFacts)
            w.record(ENVIRONMENT,2);w.date(d.today);w.string(d.defaultZone)
            w.map(OBJECTS,d.objects.size)
            for((key,value) in d.objects) {w.string(key);w.record(OBJECT,2);writeHabit(w,value.habit);writeHistory(w,value.history)}
            w.map(DAYS,d.days.size);for((key,value) in d.days) {writeDayKey(w,key);writeDay(w,value)}
            w.map(DAY_SOURCES,d.daySources.size)
            for((key,ids) in d.daySources) {writeDayKey(w,key);w.list(SOURCE_IDS,ids.size);ids.forEach(w::string)}
            w.map(ORIGINS,d.origins.size);for((key,value) in d.origins) {w.string(key);writeOrigin(w,value)}
            w.map(OPERATIONS,d.operations.size)
            for((key,value) in d.operations) {
                val encoded=encodeSyncRequest(SyncV2Operation.serializer(),value)
                // Optional Q must preserve the exact structured result, including
                // numeric token spelling; a lossy encoding is a candidate miss.
                demand(decodeOperation(encoded)==value)
                w.string(key);w.bytes(encoded)
            }
            writeHashes(w,ORIGIN_HASHES,d.originHashes)
            w.map(PENDING,d.pending.size);for((key,value) in d.pending) {w.string(key);writeQueue(w,value)}
            w.map(SENT,d.sent.size);for((key,value) in d.sent) {w.string(key);writeTransmission(w,value)}
            w.map(RECEIPTS,d.receipts.size);for((key,value) in d.receipts) {w.string(key);writeReceipt(w,value)}
            writeHashes(w,TRANSMISSION_HASHES,d.transmissionHashes)
            w.map(SHADOWS,d.shadows.size);for((key,value) in d.shadows) {w.string(key);writeShadow(w,value)}
            w.record(WATERMARKS,3);w.long(d.lastFactId);w.long(d.lastQueueId);w.int(d.totalFacts)
        }
        private suspend fun readData(r:Reader,limits:Limits):Data {
            demand(r.raw(MAGIC.size).contentEquals(MAGIC));demand(r.int()==FORMAT_VERSION)
            demand(r.int()==limits.maxFacts)
            r.record(ENVIRONMENT,2);val today=r.date();val zone=r.string()
            val objects=r.readMap(OBJECTS,2,{r.string()}) {r.record(OBJECT,2);ObjectData(readHabit(r),readHistory(r,limits.maxFacts))}
            val days=r.readMap(DAYS,limits.maxFacts,{readDayKey(r)}) {readDay(r)}
            val sources=r.readMap(DAY_SOURCES,limits.maxFacts,{readDayKey(r)}) {
                r.readSet(SOURCE_IDS,limits.maxFacts) {r.string()}
            }
            val origins=r.readMap(ORIGINS,limits.maxFacts,{r.string()}) {readOrigin(r)}
            val operations=linkedMapOf<String,SyncV2Operation>()
            repeat(r.map(OPERATIONS,limits.maxFacts)) {
                val key=r.string();demand(key !in operations)
                operations[key]=decodeOperation(r.bytes(SYNC_REQUEST_LIMIT))
            }
            val originHashes=r.readMap(ORIGIN_HASHES,limits.maxFacts,{r.string()}) {r.string()}
            val pending=r.readMap(PENDING,limits.maxFacts,{r.string()}) {readQueue(r)}
            val sent=r.readMap(SENT,limits.maxFacts,{r.string()}) {readTransmission(r)}
            val receipts=r.readMap(RECEIPTS,limits.maxFacts,{r.string()}) {readReceipt(r)}
            val transmissionHashes=r.readMap(TRANSMISSION_HASHES,limits.maxFacts,{r.string()}) {r.string()}
            val shadows=r.readMap(SHADOWS,limits.maxFacts,{r.string()}) {readShadow(r)}
            r.record(WATERMARKS,3)
            return Data(today,zone,objects,days,sources,origins,operations,originHashes,pending,sent,receipts,
                transmissionHashes,shadows,r.long(),r.long(),r.int())
        }
        private fun writeHashes(w:Writer,kind:Int,values:Map<String,String>) {
            w.map(kind,values.size);for((key,value) in values) {w.string(key);w.string(value)}
        }
        private suspend fun decodeOperation(bytes:ByteArray):SyncV2Operation=withContext(Dispatchers.Default) {
            val caller=currentCoroutineContext()
            try {
                decodeSyncReply(bytes,SYNC_REQUEST_LIMIT,SyncV2Operation.serializer(),caller::ensureActive,
                    requiredFields=OPERATION_FIELDS)
            } catch(invalid:NextSyncReplyInvalid) {throw Malformed(invalid)}
        }
        private fun writeHistory(w:Writer,h:History) {
            w.record(HISTORY,6);w.date(h.today)
            w.map(POLICIES,h.policies.size);for((date,policy) in h.policies) {w.date(date);writePolicy(w,policy)}
            w.map(QUANTITIES,h.quantities.size);for((date,quantity) in h.quantities) {w.date(date);w.long(quantity)}
            if(h.todayPolicy==null)w.nil() else writePolicy(w,h.todayPolicy)
            w.list(UNKNOWN_DATES,h.unknownDates.size);h.unknownDates.forEach(w::date)
            w.list(FACTS,h.completions.size);h.completions.forEach {writeFact(w,it)}
        }
        private fun readHistory(r:Reader,max:Int):History {
            r.record(HISTORY,6);val today=r.date()
            val policies=r.readMap(POLICIES,max+1,{r.date()}) {readPolicy(r)}
            val quantities=r.readMap(QUANTITIES,max+1,{r.date()}) {r.long()}
            val policy=if(r.nil())null else readPolicy(r)
            val unknown=r.readSet(UNKNOWN_DATES,max+1) {r.date()}
            val completions=r.readList(FACTS,max) {readFact(r)}
            return History(today,policies,quantities,policy,unknown,completions)
        }
        private fun writePolicy(w:Writer,p:CountDayPolicy) {w.record(POLICY,2);w.int(p.targetValue);w.bool(p.isCountdown)}
        private fun readPolicy(r:Reader):CountDayPolicy {r.record(POLICY,2);return CountDayPolicy(r.int(),r.bool())}
        private fun writeDayKey(w:Writer,k:DayKey) {w.record(DAY_KEY,2);w.long(k.habitId);w.string(k.localDate)}
        private fun readDayKey(r:Reader):DayKey {r.record(DAY_KEY,2);return DayKey(r.long(),r.string())}

        private fun writeHabit(w:Writer,h:HabitEntity) {
            w.record(HABIT,26)
            w.long(h.id);w.string(h.name);w.string(h.description)
            w.enum(when(h.habitType) {HabitType.CHECK_IN->1;HabitType.COUNTING->2;HabitType.TIMER->3;HabitType.GOAL->4})
            w.int(h.iconResId);w.string(h.colorHex);writeSchedule(w,h.schedule)
            w.int(h.targetValue);w.bool(h.isCountdown);w.bool(h.isActive);w.string(h.uuid);w.stringOrNull(h.parentHabitId)
            w.intOrNull(h.targetCycles);w.enum(when(h.failMode) {FailMode.STRICT->1;FailMode.LOOSE->2})
            w.boolOrNull(h.goalSuccess);w.int(h.activityRate);w.long(h.activityRateUpdatedAt);w.longOrNull(h.bestTime)
            w.long(h.createdAt);w.long(h.updatedAt);w.stringOrNull(h.completionPolicy);w.intOrNull(h.oneTimeConfirmedVersion)
            w.stringOrNull(h.oneTimeConfirmedHeadEventUuid);w.stringOrNull(h.oneTimeConfirmedCompletionEventUuid)
            writeAppearance(w,h.appearance);writeMetadata(w,h.planMetadata)
        }
        private fun readHabit(r:Reader):HabitEntity {
            r.record(HABIT,26)
            return HabitEntity(id=r.long(),name=r.string(),description=r.string(),habitType=when(r.enum()) {
                1->HabitType.CHECK_IN;2->HabitType.COUNTING;3->HabitType.TIMER;4->HabitType.GOAL;else->throw Malformed()
            },iconResId=r.int(),colorHex=r.string(),schedule=readSchedule(r),targetValue=r.int(),isCountdown=r.bool(),
                isActive=r.bool(),uuid=r.string(),parentHabitId=r.stringOrNull(),targetCycles=r.intOrNull(),
                failMode=when(r.enum()) {1->FailMode.STRICT;2->FailMode.LOOSE;else->throw Malformed()},
                goalSuccess=r.boolOrNull(),activityRate=r.int(),activityRateUpdatedAt=r.long(),bestTime=r.longOrNull(),
                createdAt=r.long(),updatedAt=r.long(),completionPolicy=r.stringOrNull(),oneTimeConfirmedVersion=r.intOrNull(),
                oneTimeConfirmedHeadEventUuid=r.stringOrNull(),oneTimeConfirmedCompletionEventUuid=r.stringOrNull(),
                appearance=readAppearance(r),planMetadata=readMetadata(r))
        }
        private fun writeSchedule(w:Writer,s:HabitSchedule) {
            w.record(SCHEDULE,2)
            when(s) {
                is HabitSchedule.Once->{w.enum(1);w.stringOrNull(s.dueDate)}
                HabitSchedule.Daily->{w.enum(2);w.nil()}
                is HabitSchedule.Weekly->{w.enum(3);w.list(WEEKDAYS,s.daysOfWeek.size);s.daysOfWeek.forEach(w::int)}
                is HabitSchedule.Monthly->{w.enum(4);w.int(s.dayOfMonth)}
                is HabitSchedule.Custom->{w.enum(5);w.int(s.frequencyDays)}
            }
        }
        private fun readSchedule(r:Reader):HabitSchedule {
            r.record(SCHEDULE,2)
            return when(r.enum()) {
                1->HabitSchedule.Once(r.stringOrNull())
                2->{demand(r.nil());HabitSchedule.Daily}
                3->HabitSchedule.Weekly(r.readList(WEEKDAYS,7) {r.int()})
                4->HabitSchedule.Monthly(r.int())
                5->HabitSchedule.Custom(r.int())
                else->throw Malformed()
            }
        }
        private fun writeAppearance(w:Writer,a:ObjectAppearance?) {
            if(a==null) {w.nil();return}
            w.record(APPEARANCE,4)
            when(val icon=a.icon) {
                is IconReference.Role->{w.enum(1);w.string(icon.role)}
                is IconReference.Asset->{w.enum(2);w.string(icon.assetId)}
            }
            w.string(a.accentColor);w.string(a.iconTint)
        }
        private fun readAppearance(r:Reader):ObjectAppearance? {
            if(r.nil())return null
            r.record(APPEARANCE,4)
            val icon=when(r.enum()) {1->IconReference.Role(r.string());2->IconReference.Asset(r.string());else->throw Malformed()}
            return ObjectAppearance(icon,r.string(),r.string())
        }
        private fun writeMetadata(w:Writer,m:PlanStructureMetadata?) {
            if(m==null) {w.nil();return}
            w.record(PLAN_METADATA,8);w.string(m.creationTimestamp);w.long(m.sortOrder);w.stringOrNull(m.startDate)
            w.stringOrNull(m.goalDueDate);w.stringOrNull(m.timezone);w.stringOrNull(m.targetUnit)
            w.stringOrNull(m.preferredLocalTime);w.stringOrNull(m.originAssignmentId)
        }
        private fun readMetadata(r:Reader):PlanStructureMetadata? {
            if(r.nil())return null
            r.record(PLAN_METADATA,8)
            return PlanStructureMetadata(creationTimestamp=r.string(),sortOrder=r.long(),startDate=r.stringOrNull(),
                goalDueDate=r.stringOrNull(),timezone=r.stringOrNull(),targetUnit=r.stringOrNull(),
                preferredLocalTime=r.stringOrNull(),originAssignmentId=r.stringOrNull())
        }
        private fun writeFact(w:Writer,f:CompletionEntity) {
            w.record(COMPLETION,15);w.long(f.id);w.long(f.habitId);w.long(f.date);w.int(f.value);w.longOrNull(f.actualCompletedAt)
            w.string(f.uuid);w.stringOrNull(f.habitUuid);w.long(f.createdAt);w.string(f.recordedTimezone)
            w.string(f.recordedLocalDate);w.string(f.timeMetadataSource);w.stringOrNull(f.oneTimeAction)
            w.intOrNull(f.oneTimeExpectedVersion);w.stringOrNull(f.oneTimeExpectedHeadEventUuid);w.stringOrNull(f.oneTimeRevertsEventUuid)
        }
        private fun readFact(r:Reader):CompletionEntity {
            r.record(COMPLETION,15)
            return CompletionEntity(id=r.long(),habitId=r.long(),date=r.long(),value=r.int(),actualCompletedAt=r.longOrNull(),
                uuid=r.string(),habitUuid=r.stringOrNull(),createdAt=r.long(),recordedTimezone=r.string(),
                recordedLocalDate=r.string(),timeMetadataSource=r.string(),oneTimeAction=r.stringOrNull(),
                oneTimeExpectedVersion=r.intOrNull(),oneTimeExpectedHeadEventUuid=r.stringOrNull(),oneTimeRevertsEventUuid=r.stringOrNull())
        }
        private fun writeDay(w:Writer,d:CountDayEntity) {
            w.record(DAY,14);w.long(d.habitId);w.string(d.habitUuid);w.string(d.localDate);w.int(d.targetValue)
            w.bool(d.isCountdown);w.string(d.firstEventUuid);w.stringOrNull(d.originRequestId);w.stringOrNull(d.originHash)
            w.stringOrNull(d.planPredecessorId);w.stringOrNull(d.planPredecessorOriginHash);w.stringOrNull(d.planPredecessorDependencyHash)
            w.longOrNull(d.planQueueWatermark);w.stringOrNull(d.capturedDeviceId);w.string(d.originKind)
        }
        private fun readDay(r:Reader):CountDayEntity {
            r.record(DAY,14)
            return CountDayEntity(habitId=r.long(),habitUuid=r.string(),localDate=r.string(),targetValue=r.int(),
                isCountdown=r.bool(),firstEventUuid=r.string(),originRequestId=r.stringOrNull(),originHash=r.stringOrNull(),
                planPredecessorId=r.stringOrNull(),planPredecessorOriginHash=r.stringOrNull(),planPredecessorDependencyHash=r.stringOrNull(),
                planQueueWatermark=r.longOrNull(),capturedDeviceId=r.stringOrNull(),originKind=r.string())
        }
        private fun writeQueue(w:Writer,q:SyncOutboxEntity) {
            w.record(QUEUE,16);w.long(q.id);w.string(q.operationId);w.string(q.recordType);w.string(q.entityUuid)
            w.string(q.wireEntityUuid);w.string(q.action);w.stringOrNull(q.referenceUuid);w.stringOrNull(q.payloadJson)
            w.longOrNull(q.baseRevision);w.stringOrNull(q.basePayloadJson);w.longOrNull(q.attemptedAt);w.int(q.attemptCount)
            w.stringOrNull(q.lastError);w.stringOrNull(q.errorCode);w.longOrNull(q.deadLetteredAt);w.long(q.createdAt)
        }
        private fun readQueue(r:Reader):SyncOutboxEntity {
            r.record(QUEUE,16)
            return SyncOutboxEntity(id=r.long(),operationId=r.string(),recordType=r.string(),entityUuid=r.string(),
                wireEntityUuid=r.string(),action=r.string(),referenceUuid=r.stringOrNull(),payloadJson=r.stringOrNull(),
                baseRevision=r.longOrNull(),basePayloadJson=r.stringOrNull(),attemptedAt=r.longOrNull(),attemptCount=r.int(),
                lastError=r.stringOrNull(),errorCode=r.stringOrNull(),deadLetteredAt=r.longOrNull(),createdAt=r.long())
        }
        private fun writeOrigin(w:Writer,o:NextRequestOriginEntity) {
            w.record(ORIGIN,9);w.string(o.kind);w.string(o.requestId);w.long(o.queueId);w.int(o.protocol);w.string(o.accountId)
            w.stringOrNull(o.serverInstanceId);w.stringOrNull(o.syncEpoch);w.string(o.sourceHash);w.string(o.intentJson)
        }
        private fun readOrigin(r:Reader):NextRequestOriginEntity {
            r.record(ORIGIN,9)
            return NextRequestOriginEntity(kind=r.string(),requestId=r.string(),queueId=r.long(),protocol=r.int(),accountId=r.string(),
                serverInstanceId=r.stringOrNull(),syncEpoch=r.stringOrNull(),sourceHash=r.string(),intentJson=r.string())
        }
        private fun writeTransmission(w:Writer,t:NextTransmissionEntity) {
            w.record(TRANSMISSION,10);w.string(t.kind);w.string(t.requestId);w.long(t.queueId);w.int(t.protocol)
            w.string(t.accountId);w.string(t.serverInstanceId);w.string(t.syncEpoch);w.string(t.deviceId)
            w.string(t.wireHash);w.bytes(t.wireBytes)
        }
        private fun readTransmission(r:Reader):NextTransmissionEntity {
            r.record(TRANSMISSION,10)
            return NextTransmissionEntity(kind=r.string(),requestId=r.string(),queueId=r.long(),protocol=r.int(),accountId=r.string(),
                serverInstanceId=r.string(),syncEpoch=r.string(),deviceId=r.string(),wireHash=r.string(),wireBytes=r.wireBytes())
        }
        private fun writeReceipt(w:Writer,a:NextAcceptanceEntity) {
            w.record(RECEIPT,6);w.string(a.kind);w.string(a.requestId);w.string(a.originHash);w.string(a.transmissionHash)
            w.string(a.resultHash);w.string(a.resultJson)
        }
        private fun readReceipt(r:Reader):NextAcceptanceEntity {
            r.record(RECEIPT,6)
            return NextAcceptanceEntity(kind=r.string(),requestId=r.string(),originHash=r.string(),transmissionHash=r.string(),
                resultHash=r.string(),resultJson=r.string())
        }
        private fun writeShadow(w:Writer,s:SyncEntityStateEntity) {
            w.record(SHADOW,7);w.string(s.entityType);w.string(s.entityUuid);w.long(s.revision);w.bool(s.deleted)
            w.stringOrNull(s.payloadJson);w.stringOrNull(s.payloadHash);w.long(s.updatedAt)
        }
        private fun readShadow(r:Reader):SyncEntityStateEntity {
            r.record(SHADOW,7)
            return SyncEntityStateEntity(entityType=r.string(),entityUuid=r.string(),revision=r.long(),deleted=r.bool(),
                payloadJson=r.stringOrNull(),payloadHash=r.stringOrNull(),updatedAt=r.long())
        }

        private const val NULL=0
        private const val INT=1
        private const val LONG=2
        private const val BOOL=3
        private const val STRING=4
        private const val BYTES=5
        private const val DATE=6
        private const val ENUM=7
        private const val LIST=8
        private const val MAP=9
        private const val RECORD=10
        private class Writer(private val limit:Long,private val poll:()->Unit) {
            private val buffer=ByteArrayOutputStream(minOf(limit,8192L).toInt())
            private val output=DataOutputStream(buffer)
            private var size=0L
            private fun reserve(amount:Long) {poll();if(amount<0 || amount>limit-size)throw BudgetExceeded();size+=amount}
            fun raw(bytes:ByteArray) {reserve(bytes.size.toLong());output.write(bytes)}
            fun nil() {reserve(1);output.writeByte(NULL)}
            fun int(value:Int) {reserve(5);output.writeByte(INT);output.writeInt(value)}
            fun long(value:Long) {reserve(9);output.writeByte(LONG);output.writeLong(value)}
            fun bool(value:Boolean) {reserve(2);output.writeByte(BOOL);output.writeByte(if(value)1 else 0)}
            fun enum(value:Int) {reserve(5);output.writeByte(ENUM);output.writeInt(value)}
            fun date(value:LocalDate) {reserve(9);output.writeByte(DATE);output.writeLong(value.toEpochDay())}
            fun string(value:String) {
                reserve(5L+value.length.toLong()*2);output.writeByte(STRING);output.writeInt(value.length)
                value.forEachIndexed {index,char->if(index%256==0)poll();output.writeChar(char.code)}
            }
            fun bytes(value:ByteArray) {reserve(5L+value.size);output.writeByte(BYTES);output.writeInt(value.size);output.write(value)}
            fun stringOrNull(value:String?) {if(value==null)nil() else string(value)}
            fun intOrNull(value:Int?) {if(value==null)nil() else int(value)}
            fun longOrNull(value:Long?) {if(value==null)nil() else long(value)}
            fun boolOrNull(value:Boolean?) {if(value==null)nil() else bool(value)}
            private fun group(type:Int,kind:Int,count:Int) {
                demand(count>=0);reserve(9);output.writeByte(type);output.writeInt(kind);output.writeInt(count)
            }
            fun record(kind:Int,fields:Int)=group(RECORD,kind,fields)
            fun map(kind:Int,count:Int)=group(MAP,kind,count)
            fun list(kind:Int,count:Int)=group(LIST,kind,count)
            fun finish():ByteArray=buffer.toByteArray()
        }
        private class Reader(private val bytes:ByteArray,private val limits:Limits,private val poll:()->Unit) {
            private var offset=0
            private var wireRemaining=limits.availableBytes-bytes.size.toLong()
            private fun need(size:Int) {poll();demand(size>=0 && size<=bytes.size-offset)}
            private fun byte():Int {need(1);return bytes[offset++].toInt() and 255}
            private fun integer():Int {need(4);return (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()}
            private fun integer64():Long {need(8);var result=0L;repeat(8) {result=(result shl 8) or byte().toLong()};return result}
            private fun tag(expected:Int) {demand(byte()==expected)}
            fun raw(size:Int):ByteArray {need(size);return bytes.copyOfRange(offset,offset+size).also {offset+=size}}
            fun nil():Boolean {need(1);return if(bytes[offset].toInt()==NULL) {offset++;true} else false}
            fun int():Int {tag(INT);return integer()}
            fun long():Long {tag(LONG);return integer64()}
            fun bool():Boolean {tag(BOOL);return when(byte()) {0->false;1->true;else->throw Malformed()}}
            fun enum():Int {tag(ENUM);return integer()}
            fun date():LocalDate {tag(DATE);return LocalDate.ofEpochDay(integer64())}
            fun string():String {
                tag(STRING);val size=integer();demand(size>=0 && size.toLong()*2<=bytes.size-offset)
                val chars=CharArray(size)
                for(index in chars.indices)chars[index]=((byte() shl 8) or byte()).toChar()
                return chars.concatToString()
            }
            fun bytes(max:Int):ByteArray {tag(BYTES);val size=integer();demand(size in 0..max);return raw(size)}
            fun wireBytes():ByteArray {
                tag(BYTES);val size=integer();demand(size in 0..SYNC_REQUEST_LIMIT)
                if(size.toLong()>wireRemaining)throw BudgetExceeded()
                wireRemaining-=size
                return raw(size)
            }
            fun stringOrNull():String?=if(nil())null else string()
            fun intOrNull():Int?=if(nil())null else int()
            fun longOrNull():Long?=if(nil())null else long()
            fun boolOrNull():Boolean?=if(nil())null else bool()
            fun record(kind:Int,fields:Int) {tag(RECORD);demand(integer()==kind && integer()==fields)}
            private fun group(type:Int,kind:Int,max:Int):Int {
                tag(type);demand(integer()==kind);val count=integer()
                demand(count>=0);if(count>max)throw BudgetExceeded()
                demand(count<=bytes.size-offset) // every member has at least one type tag
                return count
            }
            fun map(kind:Int,max:Int)=group(MAP,kind,max)
            fun <K,V> readMap(kind:Int,max:Int,key:()->K,value:()->V):Map<K,V> {
                val result=linkedMapOf<K,V>()
                repeat(map(kind,max)) {val k=key();demand(k !in result);result[k]=value()}
                return result
            }
            fun <T> readList(kind:Int,max:Int,item:()->T):List<T> = buildList {
                repeat(group(LIST,kind,max)) {add(item())}
            }
            fun <T> readSet(kind:Int,max:Int,item:()->T):Set<T> {
                val result=linkedSetOf<T>()
                repeat(group(LIST,kind,max)) {demand(result.add(item()))}
                return result
            }
            fun end() {demand(offset==bytes.size && offset.toLong()<=limits.availableBytes)}
        }
    }
}
