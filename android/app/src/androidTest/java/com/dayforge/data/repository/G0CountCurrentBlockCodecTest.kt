package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import com.dayforge.data.repository.G0CountIncrementalBlocks.Current
import com.dayforge.data.repository.G0CountIncrementalBlocks.Pending
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Code
import com.dayforge.data.history.HistoryBlockCodec.Rejected
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-runner format/freeze tests. Synthetic values never grant root, account or
 * business authority. This covers the complete Current representation, not a DB migration. */
@RunWith(AndroidJUnit4::class)
class G0CountCurrentBlockCodecTest {
    private fun id(n:Int)="00000000-0000-4000-8000-"+n.toString().padStart(12,'0')
    private fun habit(n:Int)=HabitEntity(id=n.toLong(),name="name $n",description="nul\u0000lone\ud800中",
        habitType=HabitType.COUNTING,iconResId=Int.MIN_VALUE,colorHex="#123456",schedule=HabitSchedule.Daily,
        targetValue=3,isCountdown=false,isActive=false,uuid=id(n),parentHabitId=id(50),targetCycles=7,
        failMode=FailMode.LOOSE,goalSuccess=true,activityRate=87,activityRateUpdatedAt=Long.MAX_VALUE,
        bestTime=123,createdAt=1,updatedAt=2,completionPolicy="recurring",oneTimeConfirmedVersion=9,
        oneTimeConfirmedHeadEventUuid=id(51),oneTimeConfirmedCompletionEventUuid=id(52),
        appearance=ObjectAppearance(IconReference.Role("habit.test"),"#123456","theme"),
        planMetadata=PlanStructureMetadata("2026-01-01T00:00:00Z",Long.MIN_VALUE,"2026-01-02","2026-12-31",
            "UTC","次","12:34:56",id(53)))
    private fun current()=Current(listOf(habit(1),habit(2)).associateBy {it.uuid},
        listOf(Pending(id(101),id(201),3,false),Pending(id(102),id(202),7,true)),128,128)
    private fun reject(code:Code=Code.MALFORMED,body:()->Unit) {
        val failure=runCatching(body).exceptionOrNull()
        assertTrue("Expected typed $code, got $failure",failure is Rejected)
        assertEquals(code,(failure as Rejected).code)
    }
    private fun intAt(bytes:ByteArray,offset:Int,value:Int)=bytes.copyOf().also {
        it[offset]=(value ushr 24).toByte();it[offset+1]=(value ushr 16).toByte()
        it[offset+2]=(value ushr 8).toByte();it[offset+3]=value.toByte()
    }

    @Test fun everyHabitFieldAndNullableRepresentationRoundTripsExactly() {
        val rich=habit(1)
        val sparse=rich.copy(parentHabitId=null,targetCycles=null,goalSuccess=null,bestTime=null,
            completionPolicy=null,oneTimeConfirmedVersion=null,oneTimeConfirmedHeadEventUuid=null,
            oneTimeConfirmedCompletionEventUuid=null,appearance=null,planMetadata=null)
        for(value in listOf(rich,sparse))assertEquals(value,G0CountCurrentBlockCodec.habit(G0CountCurrentBlockCodec.habit(value)))
        val original=current();val encoded=G0CountCurrentBlockCodec.encode(original)
        val decoded=G0CountCurrentBlockCodec.decode(encoded)
        assertEquals(original,decoded);assertEquals(original.hashCode(),decoded.hashCode())
        assertEquals(encoded,G0CountCurrentBlockCodec.encode(decoded))
    }

    @Test fun allScheduleAndEnumAlternativesUseExactRoundTrips() {
        val schedules=listOf(HabitSchedule.Once(null),HabitSchedule.Once("2026-12-31"),HabitSchedule.Daily,
            HabitSchedule.Weekly(emptyList()),HabitSchedule.Weekly(listOf(7,1,3)),HabitSchedule.Monthly(31),HabitSchedule.Custom(29))
        for((i,schedule) in schedules.withIndex()) {
            val value=habit(1).copy(schedule=schedule,habitType=HabitType.entries[i%HabitType.entries.size],
                failMode=if(i%2==0)FailMode.STRICT else FailMode.LOOSE,goalSuccess=when(i%3) {0->null;1->true;else->false},
                appearance=if(i%2==0)null else ObjectAppearance(IconReference.Asset(id(88)),"#123456","object"))
            assertEquals(value,G0CountCurrentBlockCodec.habit(G0CountCurrentBlockCodec.habit(value)))
        }
    }

    @Test fun currentOwnsMapsPendingAndNestedWeekdaysBeforeEncoding() {
        val weekdays=mutableListOf(1,3,5);val first=habit(1).copy(schedule=HabitSchedule.Weekly(weekdays))
        val habits=linkedMapOf(first.uuid to first,habit(2).uuid to habit(2))
        val pending=mutableListOf(Pending(id(101),id(201),3,false))
        val frozen=Current(habits,pending,128,128);val before=G0CountCurrentBlockCodec.encode(frozen)
        weekdays.clear();habits.clear();pending.clear()
        assertEquals(before,G0CountCurrentBlockCodec.encode(frozen))
        assertEquals(listOf(1,3,5),(frozen.habits.getValue(first.uuid).schedule as HabitSchedule.Weekly).daysOfWeek)
        assertTrue(runCatching {(frozen.pending as MutableList<Pending>).clear()}.isFailure)
        assertTrue(runCatching {(frozen.habits as MutableMap<String,HabitEntity>).clear()}.isFailure)
        val exported=before.toByteArray();exported.fill(0)
        assertEquals(frozen,G0CountCurrentBlockCodec.decode(before))
    }

    @Test fun capacityMembershipOrderAndDuplicatePendingAreRejected() {
        val original=current()
        reject {original.copy(maxFacts=129)};reject {original.copy(maxFacts=736,maxPending=128)}
        reject {original.copy(maxPending=1)};reject {original.copy(habits=original.habits.filterKeys {it==id(1)})}
        reject {original.copy(habits=original.habits+(id(3) to habit(3)))}
        reject {original.copy(pending=original.pending.reversed())}
        reject {original.copy(pending=listOf(original.pending[0],original.pending[0].copy(queueId=8)))}
        reject {original.copy(pending=listOf(original.pending[0],original.pending[1].copy(factUuid=id(201))))}
        reject {original.copy(pending=listOf(original.pending[0].copy(queueId=0)))}
        assertEquals(736,Current(original.habits,emptyList(),736,6).maxFacts)
    }

    @Test fun wrongMagicFormatRecordFieldCountAndTrailingBytesFailClosed() {
        val bytes=G0CountCurrentBlockCodec.encode(current()).toByteArray()
        reject {G0CountCurrentBlockCodec.decode(Bytes.copyOf(bytes.copyOf().also {it[0]=0}))}
        reject {G0CountCurrentBlockCodec.decode(Bytes.copyOf(intAt(bytes,8,2)))}
        reject {G0CountCurrentBlockCodec.decode(Bytes.copyOf(intAt(bytes,13,201)))}
        reject {G0CountCurrentBlockCodec.decode(Bytes.copyOf(intAt(bytes,17,6)))}
        reject {G0CountCurrentBlockCodec.decode(Bytes.copyOf(bytes+byteArrayOf(0)))}
        reject {G0CountCurrentBlockCodec.habit(Bytes.copyOf(bytes))}
    }

    @Test fun everyTruncationAndMalformedProfileLengthRejectsBeforePartialCurrent() {
        val bytes=G0CountCurrentBlockCodec.encode(current()).toByteArray()
        for(size in 0 until bytes.size)reject {G0CountCurrentBlockCodec.decode(Bytes.copyOf(bytes.copyOf(size)))}
        // 8-byte magic + 4-byte version + record tag/id/field count => profile STRING tag at 21.
        reject {G0CountCurrentBlockCodec.decode(Bytes.copyOf(intAt(bytes,22,-1)))}
        reject(Code.BUDGET) {G0CountCurrentBlockCodec.decode(Bytes.copyOf(intAt(bytes,22,Int.MAX_VALUE)))}
        reject {G0CountCurrentBlockCodec.decode(Bytes.copyOf(bytes.copyOf().also {it[21]=5}))}
    }

    @Test fun payloadAndWeekdayBudgetsRejectWithoutMutatingInput() {
        val original=current();val limit=G0CountBusinessBlockCodec.MAX_PAYLOAD_BYTES
        reject(Code.BUDGET) {G0CountCurrentBlockCodec.decode(Bytes.copyOf(ByteArray(limit+1)))}
        val huge=habit(1).copy(name="x".repeat(limit))
        reject(Code.BUDGET) {G0CountCurrentBlockCodec.habit(huge)}
        assertEquals(limit,huge.name.length)
        val weeks=mutableListOf(1,2,3,4,5,6,7,1)
        reject(Code.BUDGET) {G0CountCurrentBlockCodec.habit(habit(1).copy(schedule=HabitSchedule.Weekly(weeks)))}
        assertEquals(8,weeks.size);assertEquals(2,original.pending.size)
    }
}
