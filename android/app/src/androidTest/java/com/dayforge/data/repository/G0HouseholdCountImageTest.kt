package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Codec-only draft. The first case obtains original-validated data; other fixtures
 * deliberately exercise typed format values that may NOT be valid business states.
 * No case signs a package, grants a lease, activates an index or installs a Store.
 */
@RunWith(AndroidJUnit4::class)
class G0HouseholdCountImageTest:G0HouseholdCountFixture() {
    private val limit=G0HouseholdCountImage.Limits(128,32L*1024*1024)
    private suspend fun image(data:G0HouseholdCountImage.Data,limits:G0HouseholdCountImage.Limits=limit)=
        G0HouseholdCountImage.encode(G0HouseholdCountImage.snapshot(data,limits),limits)
    private fun emptyData():G0HouseholdCountImage.Data {
        // A fixed FORMAT fixture; this never changes device time or enters a real
        // domain operation with a fake business day.
        val today=LocalDate.ofEpochDay(0)
        fun value(index:Int):G0HouseholdCountImage.ObjectData {
            val row=HabitEntity(id=index.toLong(),name="Format $index",habitType=HabitType.COUNTING,
                iconResId=0,colorHex="#123456",schedule=HabitSchedule.Daily,uuid=id(7100+index),
                activityRateUpdatedAt=3,createdAt=1,updatedAt=2)
            return G0HouseholdCountImage.ObjectData(row,G0HouseholdCountImage.History(today,emptyMap(),emptyMap(),
                CountDayPolicy(3,false),emptySet(),emptyList()))
        }
        val objects=listOf(value(1),value(2)).associateBy {it.habit.uuid}
        return G0HouseholdCountImage.Data(today,"UTC",objects,emptyMap(),emptyMap(),emptyMap(),emptyMap(),
            emptyMap(),emptyMap(),emptyMap(),emptyMap(),emptyMap(),emptyMap(),0,0,0)
    }
    private fun richData():G0HouseholdCountImage.Data {
        val base=emptyData();val item=base.objects.values.first();val key=item.habit.uuid;val request=id(7201)
        val row=item.habit.copy(description="NUL\u0000 emoji\uD83D\uDE00 lone\uD800",iconResId=Int.MIN_VALUE,
            targetValue=Int.MAX_VALUE,isCountdown=true,isActive=false,parentHabitId=id(7202),targetCycles=7,
            failMode=FailMode.LOOSE,goalSuccess=true,activityRate=87,activityRateUpdatedAt=Long.MAX_VALUE,
            bestTime=123,completionPolicy="recurring",oneTimeConfirmedVersion=9,oneTimeConfirmedHeadEventUuid=id(7203),
            oneTimeConfirmedCompletionEventUuid=id(7204),appearance=ObjectAppearance(IconReference.Role("habit.test"),"#123456","theme"),
            planMetadata=PlanStructureMetadata("2026-01-01T00:00:00Z",Long.MIN_VALUE,"2026-01-02","2026-12-31",
                "UTC","次","12:34:56",id(7205)))
        val fact=CompletionEntity(id=1,habitId=row.id,date=Long.MIN_VALUE,value=Int.MAX_VALUE,actualCompletedAt=0,
            uuid=id(7206),habitUuid=key,createdAt=Long.MAX_VALUE,recordedTimezone="UTC",recordedLocalDate="1970-01-01",
            timeMetadataSource="captured",oneTimeAction="complete",oneTimeExpectedVersion=7,
            oneTimeExpectedHeadEventUuid=id(7207),oneTimeRevertsEventUuid=id(7208))
        val day=CountDayEntity(row.id,key,"1970-01-01",3,true,fact.uuid,request,"a".repeat(64),id(7209),
            "b".repeat(64),"c".repeat(64),Long.MAX_VALUE,id(4),NEXT_OPERATION)
        val dayKey=G0HouseholdCountImage.DayKey(row.id,day.localDate)
        val operation=SyncV2Operation(request,"activity_event",fact.uuid,"upsert",Long.MAX_VALUE,
            buildJsonObject {put("integer",Long.MAX_VALUE);put("string","1");put("bool",true);put("nil",JsonNull)
                put("nested",buildJsonArray {add(buildJsonObject {put("x","y")})})})
        val origin=NextRequestOriginEntity(NEXT_OPERATION,request,9,5,id(1),id(2),id(3),"d".repeat(64),"raw origin\u0000\uD800")
        val queue=SyncOutboxEntity(9,request,"completion",fact.uuid,fact.uuid,"upsert",key,"raw payload",
            Long.MAX_VALUE,"raw base",11,12,"last error","error-code",13,14)
        val sent=NextTransmissionEntity(NEXT_OPERATION,request,9,5,id(1),id(2),id(3),id(4),"e".repeat(64),byteArrayOf(0,1,-1,127))
        val receipt=NextAcceptanceEntity(NEXT_OPERATION,request,"f".repeat(64),"a".repeat(64),"b".repeat(64),"raw result\u0000\uD800")
        val shadow=SyncEntityStateEntity("activity_event",fact.uuid,Long.MAX_VALUE,true,"raw shadow","c".repeat(64),Long.MIN_VALUE)
        val history=item.history.copy(policies=linkedMapOf(base.today to CountDayPolicy(3,true)),
            quantities=linkedMapOf(base.today to Long.MAX_VALUE),todayPolicy=CountDayPolicy(3,true),
            unknownDates=linkedSetOf(base.today.minusDays(1),base.today.minusDays(2)),completions=listOf(fact))
        return base.copy(objects=base.objects+(key to G0HouseholdCountImage.ObjectData(row,history)),
            days=mapOf(dayKey to day),daySources=mapOf(dayKey to linkedSetOf(request,id(7210))),
            origins=mapOf(request to origin),operations=mapOf(request to operation),originHashes=mapOf(request to "d".repeat(64)),
            pending=mapOf(request to queue),sent=mapOf(request to sent),receipts=mapOf(request to receipt),
            transmissionHashes=mapOf(request to "e".repeat(64)),shadows=mapOf(fact.uuid to shadow),
            lastFactId=Long.MAX_VALUE,lastQueueId=Long.MAX_VALUE,totalFacts=1)
    }
    private fun same(expected:G0HouseholdCountImage.Data,actual:G0HouseholdCountImage.Data) {
        assertEquals(expected.copy(sent=emptyMap()),actual.copy(sent=emptyMap()))
        assertEquals(expected.sent.keys,actual.sent.keys)
        expected.sent.forEach {(key,row)->
            val decoded=actual.sent.getValue(key)
            assertEquals(row.copy(wireBytes=decoded.wireBytes),decoded)
            assertArrayEquals(row.wireBytes,decoded.wireBytes);assertNotSame(row.wireBytes,decoded.wireBytes)
        }
    }
    private suspend fun originalData():G0HouseholdCountImage.Data {
        val seed=seedHouseholdAccepted(8);val (http,_)=channel {successReply(it)}
        val registered=access();val original=sender(http)
        return sessions.exclusive {db.withTransaction {
            val objects=seed.habits.associate {row->row.uuid to G0HouseholdCountImage.ObjectData(row,
                G0HouseholdCountImage.History.from(CountHistoryReader(db,tokens,sessions).readInTransaction(row,seed.today)))}
            val days=seed.habits.flatMap {NextCountDayStore(db).readForHabit(it)}.associateBy {
                G0HouseholdCountImage.DayKey(it.habitId,it.localDate)
            }
            val origins=linkedMapOf<String,NextRequestOriginEntity>();val operations=linkedMapOf<String,SyncV2Operation>()
            val originHashes=linkedMapOf<String,String>();val sent=linkedMapOf<String,NextTransmissionEntity>()
            val receipts=linkedMapOf<String,NextAcceptanceEntity>();val sentHashes=linkedMapOf<String,String>()
            val sources=linkedMapOf<G0HouseholdCountImage.DayKey,MutableSet<String>>()
            val shadows=linkedMapOf<String,SyncEntityStateEntity>()
            for(request in seed.requestIds) {
                original.requireAcceptedCountInTransaction(registered,request)
                val origin=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,request));origins[request]=origin
                val operation=decodeNextOperationIntent(origin.intentJson);operations[request]=operation
                originHashes[request]=requireNotNull(NextRequestSql.rowHash(db,"next_request_origins","kind=? AND requestId=?",arrayOf(NEXT_OPERATION,request)))
                sent[request]=requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION,request))
                receipts[request]=requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION,request))
                sentHashes[request]=requireNotNull(NextRequestSql.rowHash(db,"next_transmissions","kind=? AND requestId=?",arrayOf(NEXT_OPERATION,request)))
                val fact=objects.values.flatMap {it.history.completions}.single {it.uuid==operation.entityUuid}
                sources.getOrPut(G0HouseholdCountImage.DayKey(fact.habitId,fact.recordedLocalDate)) {linkedSetOf()}.add(request)
                shadows[fact.uuid]=requireNotNull(db.syncOutboxDao().getState("activity_event",fact.uuid))
            }
            val sequence=linkedMapOf<String,Long>()
            db.openHelper.writableDatabase.query("SELECT name,seq FROM sqlite_sequence").use {c->while(c.moveToNext())sequence[c.getString(0)]=c.getLong(1)}
            G0HouseholdCountImage.Data(seed.today,seed.zone.id,objects,days,sources,origins,operations,originHashes,
                emptyMap(),sent,receipts,sentHashes,shadows,sequence.getValue("completions"),sequence.getValue("sync_outbox"),8)
        }}
    }

    @Test fun completeEightFactOriginalOutputRoundTripsWithoutAnyDatabaseWriteOrAuthority()=runBlocking<Unit> {
        val data=originalData();val before=pageNativeState()
        val snapshot=G0HouseholdCountImage.snapshot(data,limit)
        val encoded=G0HouseholdCountImage.encode(snapshot,limit)
        val decoded=G0HouseholdCountImage.decode(encoded.encode(),limit)
        same(data,decoded.copyForInstall());assertArrayEquals(encoded.encode(),decoded.encode())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(encoded.encode()),decoded.encodingDigest())
        assertEquals(before,pageNativeState())
        decoded.copyForInstall().objects.values.forEach {
            assertNull(it.history.withoutAuthority().actionIdentity);assertNull(it.history.withoutAuthority().roundHead)
        }
    }

    @Test fun everyEntityFieldNullableValueScheduleAndFixedEnumSurvivesExplicitEncoding()=runBlocking<Unit> {
        val rich=richData();val first=rich.objects.keys.first()
        val schedules=listOf(HabitSchedule.Once(null),HabitSchedule.Once("2026-12-31"),HabitSchedule.Daily,
            HabitSchedule.Weekly(listOf(7,1,3)),HabitSchedule.Monthly(31),HabitSchedule.Custom(29))
        for((index,schedule) in schedules.withIndex()) {
            val objectRow=rich.objects.getValue(first)
            val habit=objectRow.habit.copy(schedule=schedule,habitType=HabitType.entries[index%HabitType.entries.size],
                failMode=if(index%2==0)FailMode.STRICT else FailMode.LOOSE,goalSuccess=when(index%3) {0->null;1->false;else->true},
                appearance=when(index%3) {0->null;1->ObjectAppearance(IconReference.Asset(id(7301)),"#123456","object");else->objectRow.habit.appearance},
                planMetadata=if(index%2==0)null else objectRow.habit.planMetadata)
            val data=rich.copy(objects=rich.objects+(first to objectRow.copy(habit=habit)))
            same(data,G0HouseholdCountImage.decode(image(data).encode(),limit).copyForInstall())
        }
        val nullable=rich.copy(origins=rich.origins.mapValues {it.value.copy(serverInstanceId=null,syncEpoch=null)},
            pending=rich.pending.mapValues {it.value.copy(referenceUuid=null,payloadJson=null,baseRevision=null,basePayloadJson=null,
                attemptedAt=null,lastError=null,errorCode=null,deadLetteredAt=null)},
            shadows=rich.shadows.mapValues {it.value.copy(payloadJson=null,payloadHash=null,deleted=false)},
            days=rich.days.mapValues {it.value.copy(originRequestId=null,originHash=null,planPredecessorId=null,
                planPredecessorOriginHash=null,planPredecessorDependencyHash=null,planQueueWatermark=null,capturedDeviceId=null)},
            objects=rich.objects.mapValues {(_,obj)->obj.copy(history=obj.history.copy(todayPolicy=null,
                completions=obj.history.completions.map {it.copy(actualCompletedAt=null,habitUuid=null,oneTimeAction=null,
                    oneTimeExpectedVersion=null,oneTimeExpectedHeadEventUuid=null,oneTimeRevertsEventUuid=null)}))})
        same(nullable,G0HouseholdCountImage.decode(image(nullable).encode(),limit).copyForInstall())
    }

    @Test fun ownerSnapshotFreezesAllMutableDescendantsBeforeLockFreeEncoding()=runBlocking<Unit> {
        val rich=richData();val key=rich.objects.keys.first();val request=rich.operations.keys.single()
        val weekdays=mutableListOf(1,3);val objects=LinkedHashMap(rich.objects)
        objects[key]=objects.getValue(key).copy(habit=objects.getValue(key).habit.copy(schedule=HabitSchedule.Weekly(weekdays)))
        val ids=linkedSetOf(request,id(7311));val sources=linkedMapOf(rich.daySources.keys.single() to ids)
        val child=linkedMapOf<String,JsonElement>("initial" to JsonPrimitive("yes"))
        val list=mutableListOf<JsonElement>(JsonObject(child))
        val payload=linkedMapOf<String,JsonElement>("nested" to JsonArray(list))
        val operations=linkedMapOf(request to rich.operations.getValue(request).copy(payload=JsonObject(payload)))
        val wire=byteArrayOf(0,4,-1);val sent=linkedMapOf(request to rich.sent.getValue(request).copy(wireBytes=wire))
        val input=rich.copy(objects=objects,daySources=sources,operations=operations,sent=sent)
        val expected=image(input).encode();val snapshot=G0HouseholdCountImage.snapshot(input,limit)
        weekdays.clear();ids.clear();child.clear();list.clear();payload.clear();wire.fill(99);sent.clear();operations.clear();objects.clear()
        val encoded=G0HouseholdCountImage.encode(snapshot,limit)
        assertArrayEquals(expected,encoded.encode())
        val exported=encoded.copyForInstall()
        exported.sent.values.single().wireBytes.fill(88)
        assertTrue(runCatching {(exported.objects as MutableMap<*,*>).clear()}.isFailure)
        val weekly=exported.objects.getValue(key).habit.schedule as HabitSchedule.Weekly
        assertTrue(runCatching {(weekly.daysOfWeek as MutableList<*>).clear()}.isFailure)
        assertTrue(runCatching {(exported.daySources.values.single() as MutableSet<*>).clear()}.isFailure)
        assertTrue(runCatching {(exported.operations.getValue(request).payload as MutableMap<*,*>).clear()}.isFailure)
        assertArrayEquals(expected,encoded.encode())
        assertArrayEquals(byteArrayOf(0,4,-1),encoded.copyForInstall().sent.values.single().wireBytes)
    }

    @Test fun decodingOwnsItsInputAndExposesOnlyDefensiveEncodedAndWireCopies()=runBlocking<Unit> {
        val original=image(richData());val bytes=original.encode();val expected=bytes.copyOf()
        val work=async(start=CoroutineStart.UNDISPATCHED) {G0HouseholdCountImage.decode(bytes,limit)}
        bytes.fill(0)
        val decoded=work.await();assertArrayEquals(expected,decoded.encode())
        val output=decoded.encode();output.fill(1)
        decoded.copyForInstall().sent.values.single().wireBytes.fill(2)
        assertArrayEquals(expected,decoded.encode())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected),decoded.encodingDigest())
        val digest=decoded.encodingDigest();digest.fill(3)
        assertArrayEquals(original.encodingDigest(),decoded.encodingDigest())
    }

    @Test fun fixedHeaderAndMalformedVersionTypesLengthsDatesTruncationAndTailAreStrict()=runBlocking<Unit> {
        val good=image(emptyData()).encode()
        val prefix="44464730434e5449010000000101000000800a00000064000000020600000000000000000400000003005500540043"
        assertEquals(prefix,good.take(prefix.length/2).joinToString("") {"%02x".format(it.toInt() and 255)})
        suspend fun bad(bytes:ByteArray) {assertTrue(runCatching {G0HouseholdCountImage.decode(bytes,limit)}.exceptionOrNull() is G0HouseholdCountImage.Malformed)}
        bad(good.copyOf().apply {this[0]=0})
        bad(good.copyOf().apply {this[12]=2}) // version, not enum ordinal
        bad(good.copyOf().apply {this[8]=127}) // INT tag
        bad(good.copyOf().apply {this[26]=3}) // environment field count
        bad(good.copyOf().apply {for(index in 37..40)this[index]=(-1).toByte()}) // negative UTF-16 length
        bad(good.copyOf().apply {putInt(this,37,Int.MAX_VALUE)})
        bad(good.copyOf().apply {for(index in 28..35)this[index]=(-1).toByte();this[28]=127})
        val boolPolicy=hex("0a000000d30000000201000000030300")
        val boolAt=find(good,boolPolicy)
        bad(good.copyOf().apply {this[boolAt+boolPolicy.lastIndex]=2})
        val enumAt=find(good,hex("0700000002"))
        bad(good.copyOf().apply {putInt(this,enumAt+1,99)})
        for(size in listOf(0,7,8,12,17,26,35,40,46,good.size-1))bad(good.copyOf(size))
        bad(good+byteArrayOf(0))
    }

    @Test fun duplicateMapAndSetMembersAndInvalidSectionCountsNeverCollapseSilently()=runBlocking<Unit> {
        val data=richData();val good=image(data).encode()
        suspend fun bad(bytes:ByteArray) {assertTrue(runCatching {G0HouseholdCountImage.decode(bytes,limit)}.exceptionOrNull() is G0HouseholdCountImage.Malformed)}
        val keys=data.objects.keys.toList();val second=find(good,stringBytes(keys[1]))
        bad(good.copyOf().apply {stringBytes(keys[0]).copyInto(this,second)})
        val ids=data.daySources.values.single().toList()
        val setAt=find(good,hex("08000000e000000002"))
        val secondId=find(good,stringBytes(ids[1]),setAt)
        bad(good.copyOf().apply {stringBytes(ids[0]).copyInto(this,secondId)})
        val unknownAt=find(good,hex("08000000de00000002"))
        bad(good.copyOf().apply {copyInto(this,unknownAt+18,unknownAt+9,unknownAt+18)})
        val objectsAt=find(good,hex("090000006500000002"))
        bad(good.copyOf().apply {putInt(this,objectsAt+5,-1)})
        val oversized=good.copyOf().apply {putInt(this,objectsAt+5,Int.MAX_VALUE)}
        assertTrue(runCatching {G0HouseholdCountImage.decode(oversized,limit)}.exceptionOrNull() is G0HouseholdCountImage.BudgetExceeded)
    }

    @Test fun operationRequiresAllSixExplicitFieldsAndPreservesDecodedNumericTokens()=runBlocking<Unit> {
        val data=richData();val request=data.operations.keys.single();val op=data.operations.getValue(request)
        val good=image(data.copy(operations=mapOf(request to op.copy(baseRevision=null)))).encode()
        // Independent wire fixture: defaults must never manufacture base_revision,
        // and opaque JSON numeric tokens must not be converted through Double.
        val fields=linkedMapOf(
            "operation_id" to "\"$request\"","entity_type" to "\"activity_event\"",
            "entity_uuid" to "\"${op.entityUuid}\"","action" to "\"upsert\"","base_revision" to "null",
            "payload" to """{"large":9007199254740993,"negative_zero":-0,"fraction":1.00,"exponent":1e2,"text":"1e2"}"""
        )
        fun json(values:Map<String,String>)=values.entries.joinToString(",","{","}") {(key,value)->"\"$key\":$value"}
        for(field in fields.keys) {
            val missing=withOperationJson(good,request,json(fields.filterKeys {it!=field}))
            assertTrue("Missing $field must not be defaulted",
                runCatching {G0HouseholdCountImage.decode(missing,limit)}.exceptionOrNull() is G0HouseholdCountImage.Malformed)
        }
        val full=withOperationJson(good,request,json(fields))
        val decoded=G0HouseholdCountImage.decode(full,limit)
        val restored=decoded.copyForInstall().operations.getValue(request)
        assertNull(restored.baseRevision)
        val expected=mapOf("large" to "9007199254740993","negative_zero" to "-0","fraction" to "1.00","exponent" to "1e2")
        expected.forEach {(key,token)->
            assertEquals(token,restored.payload.getValue(key).jsonPrimitive.content)
            assertFalse(restored.payload.getValue(key).jsonPrimitive.isString)
        }
        assertTrue(restored.payload.getValue("text").jsonPrimitive.isString)
        assertArrayEquals(full,decoded.encode())
    }

    @Test fun operationEncodingRejectsLossyStructuredOutputWithoutGrantingBusinessQualification()=runBlocking<Unit> {
        val data=richData();val request=data.operations.keys.single();val op=data.operations.getValue(request)
        val exact=op.copy(baseRevision=null,payload=Json.parseToJsonElement(
            """{"large":9007199254740993,"minimum":-9223372036854775808,"fraction":1.5,"text":"1e2"}""").jsonObject)
        val preserved=data.copy(operations=mapOf(request to exact))
        same(preserved,G0HouseholdCountImage.decode(image(preserved).encode(),limit).copyForInstall())

        // Valid JSON, but the existing serializer rewrites its numeric token. Keep
        // this independent observation so a future codec change cannot silently
        // turn the negative case into a fixture that never exercises the guard.
        val lossy=op.copy(payload=Json.parseToJsonElement("""{"number":1e2}""").jsonObject)
        val wire=encodeSyncRequest(SyncV2Operation.serializer(),lossy)
        val wirePayload=Json.parseToJsonElement(wire.toString(Charsets.UTF_8)).jsonObject.getValue("payload")
        assertNotEquals(lossy.payload,wirePayload)
        assertTrue(runCatching {image(data.copy(operations=mapOf(request to lossy)))}.exceptionOrNull()
            is G0HouseholdCountImage.Malformed)
        // This pure data fixture never calls Store/sign/install; package rejection
        // is not a rollback or a new rejection of an already committed operation.
    }

    @Test fun explicitCapacityAndCombinedEncodingWireBudgetRemainBounded()=runBlocking<Unit> {
        val data=richData();val encoded=image(data)
        assertEquals(encoded.encodedBytes.toLong()+data.sent.values.sumOf {it.wireBytes.size.toLong()},encoded.retainedBytes)
        val exact=G0HouseholdCountImage.Limits(128,encoded.retainedBytes)
        same(data,G0HouseholdCountImage.decode(encoded.encode(),exact).copyForInstall())
        val tooSmall=exact.copy(availableBytes=exact.availableBytes-1)
        assertTrue(runCatching {G0HouseholdCountImage.decode(encoded.encode(),tooSmall)}.exceptionOrNull() is G0HouseholdCountImage.BudgetExceeded)
        assertTrue(runCatching {image(data,tooSmall)}.exceptionOrNull() is G0HouseholdCountImage.BudgetExceeded)
        assertTrue(runCatching {G0HouseholdCountImage.decode(encoded.encode(),limit.copy(availableBytes=0))}.exceptionOrNull() is G0HouseholdCountImage.BudgetExceeded)
        val base=emptyData();var next=1L
        val annual=base.copy(objects=base.objects.mapValues {(_,obj)->obj.copy(history=obj.history.copy(completions=List(368) {
            val index=next++
            CompletionEntity(id=index,habitId=obj.habit.id,date=0,value=1,actualCompletedAt=0,uuid=id(8000+index.toInt()),
                habitUuid=obj.habit.uuid,createdAt=0,recordedTimezone="UTC",recordedLocalDate="1970-01-01")
        }))},lastFactId=736,lastQueueId=736,totalFacts=736)
        val annualLimit=limit.copy(maxFacts=736)
        same(annual,G0HouseholdCountImage.decode(image(annual,annualLimit).encode(),annualLimit).copyForInstall())
        assertTrue(runCatching {image(annual,limit)}.exceptionOrNull() is G0HouseholdCountImage.BudgetExceeded)
        assertTrue(runCatching {G0HouseholdCountImage.decode(encoded.encode(),limit.copy(maxFacts=736))}.exceptionOrNull() is G0HouseholdCountImage.Malformed)
    }

    @Test fun environmentMismatchIsAMissWithoutDestroyingBytesOrRecreatingRuntimeAuthority()=runBlocking<Unit> {
        val data=richData();val encoded=image(data);val bytes=encoded.encode()
        assertTrue(encoded.matchesEnvironment(data.today,data.defaultZone))
        assertFalse(encoded.matchesEnvironment(data.today.plusDays(1),data.defaultZone))
        assertFalse(encoded.matchesEnvironment(data.today,"Asia/Shanghai"))
        assertArrayEquals(bytes,encoded.encode())
        val reopened=G0HouseholdCountImage.decode(bytes,limit)
        assertTrue(reopened.matchesEnvironment(data.today,data.defaultZone))
        reopened.copyForInstall().objects.values.forEach {obj->
            val history=obj.history.withoutAuthority()
            assertNull(history.actionIdentity);assertNull(history.roundHead)
            assertEquals(obj.history.completions,history.completions)
        }
        assertArrayEquals(bytes,reopened.encode())
    }

    private fun putInt(bytes:ByteArray,at:Int,value:Int) {repeat(4) {i->bytes[at+i]=(value ushr ((3-i)*8)).toByte()}}
    private fun hex(value:String)=value.chunked(2).map {it.toInt(16).toByte()}.toByteArray()
    private fun stringBytes(value:String)=ByteArrayOutputStream().also {buffer->DataOutputStream(buffer).use {out->
        out.writeByte(4);out.writeInt(value.length);value.forEach {out.writeChar(it.code)}
    }}.toByteArray()
    private fun withOperationJson(bytes:ByteArray,request:String,json:String):ByteArray {
        val prefix=hex("090000006900000001")+stringBytes(request)
        val at=find(bytes,prefix)+prefix.size
        assertEquals(5,bytes[at].toInt())
        var size=0
        repeat(4) {size=(size shl 8) or (bytes[at+1+it].toInt() and 255)}
        val body=json.toByteArray(Charsets.UTF_8)
        val replacement=byteArrayOf(5)+ByteArray(4).also {putInt(it,0,body.size)}+body
        return bytes.copyOfRange(0,at)+replacement+bytes.copyOfRange(at+5+size,bytes.size)
    }
    private fun find(bytes:ByteArray,part:ByteArray,start:Int=0):Int {
        for(index in start..bytes.size-part.size)if(part.indices.all {bytes[index+it]==part[it]})return index
        error("Missing independent format fixture pattern")
    }
}
