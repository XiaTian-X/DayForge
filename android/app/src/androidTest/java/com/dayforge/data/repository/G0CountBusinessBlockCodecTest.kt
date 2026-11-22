package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.CountDayEntity
import com.dayforge.data.local.entity.NextAcceptanceEntity
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.NextTransmissionEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Code
import com.dayforge.data.history.HistoryBlockCodec.Rejected
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical Android format tests only. Synthetic field combinations deliberately exercise
 * representation fidelity, not the business validator or account/publication authority. */
@RunWith(AndroidJUnit4::class)
class G0CountBusinessBlockCodecTest {
    private val text="exact\u0000\ud800\udc00\ud800中"
    private fun fact()=CompletionEntity(id=17,habitId=29,date=Long.MIN_VALUE,value=Int.MAX_VALUE,
        actualCompletedAt=Long.MAX_VALUE,uuid="event",habitUuid="habit",createdAt=-19,
        recordedTimezone="Asia/Shanghai",recordedLocalDate="2026-10-21",timeMetadataSource=text,
        oneTimeAction="revert",oneTimeExpectedVersion=Int.MIN_VALUE,
        oneTimeExpectedHeadEventUuid="head",oneTimeRevertsEventUuid="previous")
    private fun day()=CountDayEntity(habitId=29,habitUuid="h",localDate="2026-10-21",targetValue=7,
        isCountdown=true,firstEventUuid="event",originRequestId="request",originHash="origin-hash",
        planPredecessorId="plan",planPredecessorOriginHash="plan-origin",
        planPredecessorDependencyHash="dependency",planQueueWatermark=Long.MAX_VALUE,
        capturedDeviceId="device",originKind=text)
    private fun origin(intent:String=text)=NextRequestOriginEntity(kind="sync_operation",requestId="request",
        queueId=37,protocol=5,accountId="account",serverInstanceId="server",syncEpoch="epoch",
        sourceHash="source-hash",intentJson=intent)
    private fun source(wire:ByteArray=byteArrayOf(0,1,127,-128,-1))=G0CountBusinessBlockCodec.Source(
        origin=origin(),originHash="origin-hash",
        transmission=NextTransmissionEntity(kind="sync_operation",requestId="request",queueId=37,
            protocol=5,accountId="account",serverInstanceId="server",syncEpoch="epoch",deviceId="device",
            wireHash="wire-hash",wireBytes=wire),transmissionHash="transmission-hash",
        receipt=NextAcceptanceEntity(kind="sync_operation",requestId="request",originHash="origin-hash",
            transmissionHash="transmission-hash",resultHash="result-hash",resultJson=text),
        pending=SyncOutboxEntity(id=37,operationId="request",recordType="completion",entityUuid="event",
            wireEntityUuid="wire-event",action="upsert",referenceUuid="habit",payloadJson=text,
            baseRevision=Long.MAX_VALUE,basePayloadJson="base",attemptedAt=-1,attemptCount=Int.MAX_VALUE,
            lastError="error",errorCode="code",deadLetteredAt=123,createdAt=456),
        shadow=SyncEntityStateEntity(entityType="activity_event",entityUuid="event",revision=Long.MAX_VALUE,
            deleted=true,payloadJson=text,payloadHash="payload-hash",updatedAt=-789))
    private fun assertSource(expected:G0CountBusinessBlockCodec.Source,actual:G0CountBusinessBlockCodec.Source) {
        assertEquals(expected.origin,actual.origin);assertEquals(expected.originHash,actual.originHash)
        assertEquals(expected.transmissionHash,actual.transmissionHash);assertEquals(expected.receipt,actual.receipt)
        assertEquals(expected.pending,actual.pending);assertEquals(expected.shadow,actual.shadow)
        val left=expected.transmission;val right=actual.transmission
        if(left==null)assertNull(right) else {
            assertNotNull(right);right!!
            assertArrayEquals(left.wireBytes,right.wireBytes)
            assertEquals(left.copy(wireBytes=right.wireBytes),right)
        }
    }
    private fun rejects(code:Code=Code.MALFORMED,body:()->Unit) {
        val failure=runCatching(body).exceptionOrNull()
        assertTrue("Expected typed $code rejection, got $failure",failure is Rejected)
        assertEquals(code,(failure as Rejected).code)
    }
    private fun intAt(bytes:ByteArray,offset:Int,value:Int):ByteArray=bytes.copyOf().also {
        it[offset]=(value ushr 24).toByte();it[offset+1]=(value ushr 16).toByte()
        it[offset+2]=(value ushr 8).toByte();it[offset+3]=value.toByte()
    }

    @Test fun everyFactAndRetainedDayFieldRoundTripsIncludingNullableValues() {
        val complete=fact();assertEquals(complete,G0CountBusinessBlockCodec.fact(G0CountBusinessBlockCodec.fact(complete)))
        val absent=complete.copy(actualCompletedAt=null,habitUuid=null,oneTimeAction=null,
            oneTimeExpectedVersion=null,oneTimeExpectedHeadEventUuid=null,oneTimeRevertsEventUuid=null)
        assertEquals(absent,G0CountBusinessBlockCodec.fact(G0CountBusinessBlockCodec.fact(absent)))
        val completeDay=day();assertEquals(completeDay,G0CountBusinessBlockCodec.day(G0CountBusinessBlockCodec.day(completeDay)))
        val absentDay=completeDay.copy(originRequestId=null,originHash=null,planPredecessorId=null,
            planPredecessorOriginHash=null,planPredecessorDependencyHash=null,planQueueWatermark=null,capturedDeviceId=null)
        assertEquals(absentDay,G0CountBusinessBlockCodec.day(G0CountBusinessBlockCodec.day(absentDay)))
    }

    @Test fun everySourceLifecycleFieldRoundTripsWithoutInterpretingItsBusinessValidity() {
        val complete=source();assertSource(complete,G0CountBusinessBlockCodec.source(G0CountBusinessBlockCodec.source(complete)))
        val absent=G0CountBusinessBlockCodec.Source(origin().copy(serverInstanceId=null,syncEpoch=null),text)
        assertSource(absent,G0CountBusinessBlockCodec.source(G0CountBusinessBlockCodec.source(absent)))
        val nullable=source();val queue=requireNotNull(nullable.pending).copy(referenceUuid=null,payloadJson=null,
            baseRevision=null,basePayloadJson=null,attemptedAt=null,lastError=null,errorCode=null,deadLetteredAt=null)
        val sparse=G0CountBusinessBlockCodec.Source(nullable.origin,nullable.originHash,pending=queue,
            shadow=requireNotNull(nullable.shadow).copy(payloadJson=null,payloadHash=null))
        assertSource(sparse,G0CountBusinessBlockCodec.source(G0CountBusinessBlockCodec.source(sparse)))
    }

    @Test fun utf16CodeUnitsAndBinaryWireArePreservedExactlyAndCopiesCannotMutateSource() {
        val wire=byteArrayOf(0,1,127,-128,-1);val expected=wire.copyOf();val value=source(wire)
        wire.fill(42);assertArrayEquals(expected,requireNotNull(value.transmission).wireBytes)
        val first=G0CountBusinessBlockCodec.source(value)
        requireNotNull(value.transmission).wireBytes.fill(43)
        val decoded=G0CountBusinessBlockCodec.source(first)
        assertArrayEquals(text.toCharArray(),decoded.origin.intentJson.toCharArray())
        assertArrayEquals(expected,requireNotNull(decoded.transmission).wireBytes)
        requireNotNull(decoded.transmission).wireBytes.fill(44)
        assertArrayEquals(expected,requireNotNull(decoded.transmission).wireBytes)
        assertEquals(first,G0CountBusinessBlockCodec.source(value))
        val exported=first.toByteArray();exported.fill(0)
        assertSource(value,G0CountBusinessBlockCodec.source(first))
    }

    @Test fun wrongVersionTypeFieldCountAndTrailingBytesAreRejected() {
        val bytes=G0CountBusinessBlockCodec.fact(fact()).toByteArray()
        rejects {G0CountBusinessBlockCodec.fact(Bytes.copyOf(bytes.copyOf().also {it[0]=0}))}
        rejects {G0CountBusinessBlockCodec.fact(Bytes.copyOf(intAt(bytes,8,2)))}
        // 8-byte magic, 4-byte format, RECORD tag, then record id and field count.
        rejects {G0CountBusinessBlockCodec.fact(Bytes.copyOf(intAt(bytes,13,101)))}
        rejects {G0CountBusinessBlockCodec.fact(Bytes.copyOf(intAt(bytes,17,16)))}
        rejects {G0CountBusinessBlockCodec.fact(Bytes.copyOf(bytes+byteArrayOf(0)))}
        rejects {G0CountBusinessBlockCodec.day(Bytes.copyOf(bytes))}
    }

    @Test fun truncationNegativeLengthAndOverBudgetLengthFailBeforeReturningAPartialEntity() {
        val value=fact().copy(actualCompletedAt=null)
        val bytes=G0CountBusinessBlockCodec.fact(value).toByteArray()
        for(size in 0 until bytes.size)rejects {G0CountBusinessBlockCodec.fact(Bytes.copyOf(bytes.copyOf(size)))}
        // 21-byte record prefix + 3 typed Longs + typed Int + NULL = UUID tag at 54, length at 55.
        rejects {G0CountBusinessBlockCodec.fact(Bytes.copyOf(intAt(bytes,55,-1)))}
        rejects(Code.BUDGET) {G0CountBusinessBlockCodec.fact(Bytes.copyOf(intAt(bytes,55,Int.MAX_VALUE)))}
        rejects {G0CountBusinessBlockCodec.fact(Bytes.copyOf(bytes.copyOf().also {it[54]=5}))}
    }

    @Test fun booleanAndNullMarkersHaveSingleUnambiguousEncoding() {
        val bytes=G0CountBusinessBlockCodec.day(day()).toByteArray()
        // 21-byte record prefix + Long + UTF-16 "h" + UTF-16 date + Int => Boolean tag 67/value 68.
        rejects {G0CountBusinessBlockCodec.day(Bytes.copyOf(bytes.copyOf().also {it[68]=2}))}
        val nullFact=G0CountBusinessBlockCodec.fact(fact().copy(actualCompletedAt=null)).toByteArray()
        rejects {G0CountBusinessBlockCodec.fact(Bytes.copyOf(nullFact.copyOf().also {it[53]=7}))}
    }

    @Test fun payloadAndWireBudgetsRejectRatherThanTruncateOrChangeTheCallerInput() {
        val limit=G0CountBusinessBlockCodec.MAX_PAYLOAD_BYTES
        val hugeWire=ByteArray(limit+1){17};val before=hugeWire.copyOf()
        rejects(Code.BUDGET) {source(hugeWire)};assertArrayEquals(before,hugeWire)
        rejects(Code.BUDGET) {G0CountBusinessBlockCodec.fact(Bytes.copyOf(ByteArray(limit+1)))}
        val hugeText="x".repeat(limit)
        val source=G0CountBusinessBlockCodec.Source(origin(hugeText),"hash")
        rejects(Code.BUDGET) {G0CountBusinessBlockCodec.source(source)}
        assertEquals(hugeText,source.origin.intentJson)
    }
}
