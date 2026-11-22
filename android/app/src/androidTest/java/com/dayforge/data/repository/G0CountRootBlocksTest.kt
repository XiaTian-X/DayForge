package com.dayforge.data.repository

import com.dayforge.data.history.HistoryNodeSession

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.data.repository.G0CountIncrementalBlocks.Current
import com.dayforge.data.repository.G0CountIncrementalBlocks.Pending
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Ref
import com.dayforge.data.history.HistoryBlockCodec.Rejected
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pure representation fixtures on a physical runner. No SQL, key, root authorization or
 * business acceptance is issued here; real P/ACK/original-validator parity is separate.
 * Fixed dates are codec inputs, never a substitute clock for an actual business operation. */
@RunWith(AndroidJUnit4::class)
class G0CountRootBlocksTest {
    private val binding="a".repeat(64)
    private val today=LocalDate.of(2026,10,22)
    private fun id(n:Int)="00000000-0000-4000-8000-"+n.toString().padStart(12,'0')
    private fun habit(n:Int)=HabitEntity(id=n.toLong(),name="count $n",habitType=HabitType.COUNTING,
        iconResId=0,colorHex="#123456",schedule=HabitSchedule.Daily,targetValue=3,uuid=id(n),
        createdAt=1,updatedAt=2,activityRateUpdatedAt=3,completionPolicy="recurring")
    private fun emptyData():G0HouseholdCountImage.Data {
        val objects=listOf(habit(1),habit(2)).associate {h->h.uuid to G0HouseholdCountImage.ObjectData(h,
            G0HouseholdCountImage.History(today,emptyMap(),emptyMap(),CountDayPolicy(3,false),emptySet(),emptyList()))}
        return G0HouseholdCountImage.Data(today,"UTC",objects,emptyMap(),emptyMap(),emptyMap(),emptyMap(),emptyMap(),
            emptyMap(),emptyMap(),emptyMap(),emptyMap(),emptyMap(),0,0,0)
    }
    private suspend fun emptyRoot(includeCurrent:Boolean=true):G0CountIncrementalBlocks.State {
        val snapshot=G0HouseholdCountImage.snapshot(emptyData(),G0HouseholdCountImage.Limits(128,32L*1024*1024))
        return G0CountIncrementalBlocks.firstBuild(binding,snapshot,includeCurrent=includeCurrent)
    }
    private class Memory(root:Ref,initial:Map<String,Bytes>) {
        var root:Ref=root;private set
        val blocks=LinkedHashMap(initial)
        var queries=0;private set
        val lookup=HistoryNodeSession.Lookup {digest,limit->
            queries++;blocks[digest]?.also {assertTrue(it.size<=limit)}
        }
        fun apply(update:G0CountIncrementalBlocks.Update) {
            assertTrue("B3 never carries a full historical map",update.state.blocks.isEmpty())
            blocks.putAll(update.newBlocks);root=update.state.root
        }
    }
    private suspend fun loaded(memory:Memory)=G0CountIncrementalBlocks.readCurrent(binding,memory.root,memory.lookup)
    private suspend fun appended(memory:Memory):Pair<G0CountIncrementalBlocks.Delta,Current> {
        val old=loaded(memory).current;val h=old.habits.getValue(id(1))
        val timestamp=today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val fact=CompletionEntity(id=1,habitId=h.id,date=timestamp,value=1,actualCompletedAt=timestamp,
            uuid=id(201),habitUuid=h.uuid,createdAt=timestamp,recordedTimezone="UTC",recordedLocalDate=today.toString())
        val queue=SyncOutboxEntity(1,id(101),"completion",fact.uuid,fact.uuid,"upsert",h.uuid,createdAt=timestamp)
        val operation=SyncV2Operation(operationId=queue.operationId,entityType="activity_event",entityUuid=fact.uuid,
            action="upsert",payload=JsonObject(SyncV2Mapper.completion(fact,h)+("count_policy" to CountDayPolicy(3,false).toJson())))
        val origin=NextRequestOriginEntity(NEXT_OPERATION,queue.operationId,1,5,id(301),id(302),id(303),
            "b".repeat(64),encodeSyncRequest(SyncV2Operation.serializer(),operation).toString(Charsets.UTF_8))
        val source=G0CountBusinessBlockCodec.Source(origin,"c".repeat(64),pending=queue)
        val day=CountDayEntity(h.id,h.uuid,today.toString(),3,false,fact.uuid,origin.requestId,source.originHash,
            planQueueWatermark=0,capturedDeviceId=id(304))
        val nextHabit=h.copy(activityRate=85,activityRateUpdatedAt=timestamp)
        val next=old.copy(habits=old.habits+(h.uuid to nextHabit),pending=listOf(Pending(origin.requestId,fact.uuid,1,false)))
        val delta=G0CountIncrementalBlocks.Delta(null,fact,null,source,null,day,nextHabit)
        return delta to next
    }
    private suspend fun update(memory:Memory,delta:G0CountIncrementalBlocks.Delta,next:Current):G0CountIncrementalBlocks.Update {
        val before=loaded(memory).current
        return G0CountIncrementalBlocks.advanceFromSource(binding,memory.root,memory.lookup,delta,before,next)
    }
    private suspend fun reject(body:suspend ()->Unit) {
        val failure=runCatching {body()}.exceptionOrNull()
        assertTrue("Expected typed representation refusal, got $failure",failure is Rejected)
    }

    @Test fun legacyB2RootCannotGainCurrentOrWritableTransformation()=runBlocking<Unit> {
        val b2=emptyRoot(false);val memory=Memory(b2.root,b2.blocks)
        reject {loaded(memory)}
        reject {G0CountIncrementalBlocks.readRequest(binding,memory.root,memory.lookup,id(101))}
        val b3=emptyRoot();val base=Memory(b3.root,b3.blocks);val (delta,next)=appended(base)
        reject {G0CountIncrementalBlocks.advanceFromSource(binding,memory.root,memory.lookup,delta,loaded(base).current,next)}
        // Conversely the B2 path cannot advance a B3 root while leaving C stale.
        reject {G0CountIncrementalBlocks.advance(b3,delta)}
    }

    @Test fun emptyDayAndWindowAbsenceAreAuthenticatedWithOnlyAQuerySource()=runBlocking<Unit> {
        val initial=emptyRoot();val memory=Memory(initial.root,initial.blocks)
        val copy=memory.blocks.toMap()
        val result=G0CountIncrementalBlocks.capture(binding,memory.root,memory.lookup,id(1),today,
            setOf(today.minusDays(1),today.minusDays(7)),newFactUuid=id(201),newRequestId=id(101))
        assertEquals(setOf(today,today.minusDays(1),today.minusDays(7)),result.days.keys)
        assertTrue(result.days.values.all {it==null});assertTrue(result.dayFactsByDate.values.all {it.isEmpty()})
        assertEquals(result.days.keys,result.daySourcesByDate.keys)
        assertTrue(result.daySourcesByDate.values.all {it.isEmpty()})
        assertEquals(true,result.newFactAbsent);assertEquals(true,result.newRequestAbsent)
        assertNull(result.request);assertNull(result.firstFact);assertNull(result.firstSource)
        assertEquals(0,result.allocation.totalFacts);assertEquals(copy,memory.blocks)
        assertTrue(memory.queries>0)
    }

    @Test fun appendSendAndAckAtomicallyAdvanceCurrentAndOnlyReturnNewNodes()=runBlocking<Unit> {
        val initial=emptyRoot();val memory=Memory(initial.root,initial.blocks)
        val untouched=memory.blocks.toMap();val (append,next)=appended(memory)
        val first=update(memory,append,next)
        assertTrue(first.newBlocks.isNotEmpty());assertTrue(first.state.blocks.isEmpty())
        assertEquals(untouched,memory.blocks);memory.apply(first)
        val pending=loaded(memory)
        assertEquals(1,pending.allocation.totalFacts);assertEquals(1L,pending.allocation.lastFactId)
        assertEquals(1,pending.pending.size);assertFalse(pending.current.pending.single().inflight)
        val captured=G0CountIncrementalBlocks.capture(binding,memory.root,memory.lookup,id(1),today,
            setOf(today.minusDays(1)),requestId=id(101),newFactUuid=id(201),newRequestId=id(101))
        assertEquals(false,captured.newFactAbsent);assertEquals(false,captured.newRequestAbsent)
        assertEquals(listOf(append.newFact),captured.dayFacts)
        assertEquals(listOf(append.newFact),captured.dayFactsByDate.getValue(today))
        assertEquals(setOf(id(101)),captured.daySourcesByDate.getValue(today).map {it.origin.requestId}.toSet())
        assertNotNull(captured.daySourcesByDate.getValue(today).single().pending)
        assertTrue(captured.daySourcesByDate.getValue(today.minusDays(1)).isEmpty())
        assertEquals(append.newFact,captured.firstFact);assertEquals(append.newDay,captured.days.getValue(today)?.row)
        val source=append.newSource
        val transmission=NextTransmissionEntity(NEXT_OPERATION,id(101),1,5,id(301),id(302),id(303),id(304),
            "d".repeat(64),byteArrayOf(1,2,3))
        val sentSource=G0CountBusinessBlockCodec.Source(source.origin,source.originHash,transmission,"e".repeat(64),pending=source.pending)
        val send=G0CountIncrementalBlocks.Delta(append.newFact,append.newFact,source,sentSource,append.newDay,append.newDay,append.habit)
        val sentCurrent=next.copy(pending=next.pending.map {it.copy(inflight=true)})
        memory.apply(update(memory,send,sentCurrent));assertTrue(loaded(memory).current.pending.single().inflight)
        val normalized=append.newFact.copy(createdAt=append.newFact.createdAt+1)
        val receipt=NextAcceptanceEntity(NEXT_OPERATION,id(101),source.originHash,"e".repeat(64),"f".repeat(64),"{}")
        val shadow=SyncEntityStateEntity("activity_event",normalized.uuid,1,payloadJson="{}",payloadHash="a".repeat(64))
        val accepted=G0CountBusinessBlockCodec.Source(source.origin,source.originHash,transmission,"e".repeat(64),receipt,null,shadow)
        val ack=G0CountIncrementalBlocks.Delta(append.newFact,normalized,sentSource,accepted,append.newDay,append.newDay,append.habit)
        memory.apply(update(memory,ack,sentCurrent.copy(pending=emptyList())))
        val done=loaded(memory);assertTrue(done.current.pending.isEmpty());assertTrue(done.pending.isEmpty())
        val request=G0CountIncrementalBlocks.readRequest(binding,memory.root,memory.lookup,id(101))
        assertEquals(normalized,request.fact);assertEquals(receipt,request.source.receipt);assertNull(request.source.pending)
        assertEquals(request.fact,request.firstFact)
        val later=G0CountIncrementalBlocks.capture(binding,memory.root,memory.lookup,null,today.plusDays(1),requestId=id(101))
        assertEquals(setOf(today,today.plusDays(1)),later.days.keys)
        assertEquals(listOf(normalized),later.dayFactsByDate.getValue(today))
        assertTrue(later.dayFactsByDate.getValue(today.plusDays(1)).isEmpty())
        assertEquals(normalized,later.request?.fact)
        // Current.pending is empty after ACK, yet the old day's accepted source remains
        // in the authenticated complete day set for native origin/shadow range comparison.
        assertTrue(later.current.pending.isEmpty())
        val retired=later.daySourcesByDate.getValue(today).single()
        assertEquals(id(101),retired.origin.requestId);assertNull(retired.pending);assertEquals(receipt,retired.receipt)
        assertTrue(later.daySourcesByDate.getValue(today.plusDays(1)).isEmpty())
        assertTrue(runCatching {(later.daySourcesByDate as MutableMap<LocalDate,List<G0CountBusinessBlockCodec.Source>>).clear()}.isFailure)
        assertTrue(runCatching {(later.daySourcesByDate.getValue(today) as MutableList<G0CountBusinessBlockCodec.Source>).clear()}.isFailure)
        val day=G0CountIncrementalBlocks.readDayFromSource(binding,memory.root,memory.lookup,id(1),today,today)
        assertEquals(1L,day.quantity);assertEquals(listOf(normalized),day.facts)
        assertTrue(untouched.all {(digest,bytes)->memory.blocks[digest]==bytes})
    }

    @Test fun currentCannotOmitPendingOrChangeAnotherHabitAlongsideAnAppend()=runBlocking<Unit> {
        val initial=emptyRoot();val memory=Memory(initial.root,initial.blocks);val old=loaded(memory).current
        val before=memory.blocks.toMap();val (delta,next)=appended(memory)
        reject {update(memory,delta,next.copy(pending=emptyList()))}
        val other=next.habits.getValue(id(2)).copy(name="unauthorized change")
        reject {update(memory,delta,next.copy(habits=next.habits+(other.uuid to other)))}
        reject {G0CountIncrementalBlocks.advanceFromSource(binding,memory.root,memory.lookup,delta,
            old.copy(maxPending=6),next)}
        reject {update(memory,delta,next.copy(maxFacts=127))}
        assertEquals(before,memory.blocks);assertEquals(initial.root,memory.root)
        memory.apply(update(memory,delta,next))
        reject {update(memory,delta,next)}
    }

    @Test fun missingCorruptAndCrossRootBytesCannotProduceAValidWorkset()=runBlocking<Unit> {
        val initial=emptyRoot();val memory=Memory(initial.root,initial.blocks)
        val noBytes=HistoryNodeSession.Lookup {_,_->null}
        reject {G0CountIncrementalBlocks.readCurrent(binding,memory.root,noBytes)}
        val rootBytes=memory.blocks.getValue(memory.root.digest).toByteArray();rootBytes[rootBytes.lastIndex]=(rootBytes.last().toInt() xor 1).toByte()
        val corrupt=HistoryNodeSession.Lookup {digest,_->if(digest==memory.root.digest)Bytes.copyOf(rootBytes) else memory.blocks[digest]}
        reject {G0CountIncrementalBlocks.readCurrent(binding,memory.root,corrupt)}
        reject {G0CountIncrementalBlocks.readCurrent("b".repeat(64),memory.root,memory.lookup)}
        val (delta,next)=appended(memory);memory.apply(update(memory,delta,next))
        // The authenticated old root cannot borrow NEW bytes when its old bytes are missing.
        val onlyNew=memory.blocks.filterKeys {it !in initial.blocks}
        reject {G0CountIncrementalBlocks.readCurrent(binding,initial.root,HistoryNodeSession.Lookup {digest,_->onlyNew[digest]})}
    }
}
