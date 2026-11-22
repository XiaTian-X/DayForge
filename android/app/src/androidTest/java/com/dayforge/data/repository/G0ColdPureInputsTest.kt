package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0ColdPureInputsTest : G0SealedDomainFixture() {
    override fun domainOpeningCallback() = G0SchemaOpeningCallback(true)
    private suspend fun seed(): String {
        register();val row=editMetric("Accepted")
        val (http,_)=channel { successReply(it) }
        val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery))
        return row.operationId
    }
    private fun candidate() = G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,prepareColdInputs=true)
    @Test fun exactBytesAndExactSerializerOnlyWithDefensiveCopyAndInvalidFallback()=runBlocking<Unit> {
        val operationId=seed();val original=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,operationId)).intentJson.toByteArray()
        val bytes=original.copyOf()
        val input=G0ColdPureInputs.Input(G0ColdPureInputs.Kind.INTENT,bytes)
        val cache=withContext(Dispatchers.Default) { G0ColdPureInputs.build(listOf(input,
            G0ColdPureInputs.Input(G0ColdPureInputs.Kind.WIRE,"{}".toByteArray()))) }
        bytes.fill(0)
        assertEquals(decodeFrozenSyncRequest(original,SyncV2Operation.serializer()),cache.find(original,SyncV2Operation.serializer()))
        assertNull(cache.find(original,NextSyncOperationResult.serializer()))
        assertNull(cache.find(bytes,SyncV2Operation.serializer()))
        assertNull(cache.find("{}".toByteArray(),NextSyncPushRequest.serializer()))
        val codec=G0ColdStrictDecoder(predecoded=cache)
        assertTrue(rejected { codec.decodeEnvelope("{}".toByteArray(),id(4)) } is InvalidFrozenSyncRequest)
        assertEquals(decodeFrozenSyncRequest(original,SyncV2Operation.serializer()),codec.decode(original,SyncV2Operation.serializer()))
    }
    @Test fun fullEnvelopeDepthCannotBorrowStandaloneSourceProof()=runBlocking<Unit> {
        val operationId=seed();val source=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,operationId)).intentJson
        val body=kotlinx.serialization.json.Json.parseToJsonElement(source) as kotlinx.serialization.json.JsonObject
        val nested=kotlinx.serialization.json.Json.parseToJsonElement("{\"x\":"+"[".repeat(14)+"0"+"]".repeat(14)+"}")
        val text=kotlinx.serialization.json.JsonObject(body+("payload" to nested)).toString()
        val envelope="{\"device_id\":\"${id(4)}\",\"operations\":[$text]}".toByteArray()
        val cache=G0ColdPureInputs.build(listOf(G0ColdPureInputs.Input(G0ColdPureInputs.Kind.INTENT,text.toByteArray()),
            G0ColdPureInputs.Input(G0ColdPureInputs.Kind.WIRE,envelope)),id(4))
        assertNotNull(cache.find(text.toByteArray(),SyncV2Operation.serializer()))
        assertNull(cache.find(envelope,NextSyncPushRequest.serializer()))
        assertTrue(rejected { G0ColdStrictDecoder(predecoded=cache).decodeEnvelope(envelope,id(4)) } is InvalidFrozenSyncRequest)
    }
    @Test fun mismatchedOrderingAndDeviceHintsUseFullStrictFallback()=runBlocking<Unit> {
        val operationId=seed()
        val source=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,operationId)).intentJson
        val second=source.replace("Accepted","Different")
        assertNotEquals(source,second)
        val wire="{\"device_id\":\"${id(4)}\",\"operations\":[$second]}".toByteArray()
        val inputs=listOf(G0ColdPureInputs.Input(G0ColdPureInputs.Kind.INTENT,source.toByteArray()),
            G0ColdPureInputs.Input(G0ColdPureInputs.Kind.WIRE,wire))
        for(device in listOf(id(4),id(99),"invalid")) {
            val cache=G0ColdPureInputs.build(inputs,device)
            assertEquals(decodeFrozenSyncRequest(wire,NextSyncPushRequest.serializer()),cache.find(wire,NextSyncPushRequest.serializer()))
            assertNotEquals(cache.find(source.toByteArray(),SyncV2Operation.serializer()),cache.find(wire,NextSyncPushRequest.serializer())!!.operations.single())
        }
        val beyondLimit=List(384) { G0ColdPureInputs.Input(G0ColdPureInputs.Kind.WIRE,"{}".toByteArray()) }+inputs
        assertNull(G0ColdPureInputs.build(beyondLimit,id(4)).find(source.toByteArray(),SyncV2Operation.serializer()))
    }
    @Test fun captureBoundsCandidatesBeforeSkippingOversizedValues()=runBlocking<Unit> {
        val operationId=seed();val original=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,operationId))
        db.withTransaction {
            repeat(128) { i -> db.nextRequestDao().insertOrigin(original.copy(
                requestId="00000000-0000-4000-8000-${i.toString(16).padStart(12,'0')}",queueId=-(i+1L),intentJson="x".repeat(8193))) }
            db.nextRequestDao().insertOrigin(original.copy(requestId="ffffffff-ffff-4fff-8fff-ffffffffffff",queueId=-129L))
        }
        val trace=G0PhaseTrace(driverTiming=false)
        val inputs=trace.run { db.withTransaction { NextEvidenceReadSession.read(db) { G0ColdPureInputs.capture(db) } } }
        assertEquals(130,trace.count("cold_pure_candidate")) // 128 sources, one wire and one result.
        assertTrue(inputs.none { it.kind==G0ColdPureInputs.Kind.INTENT })
        assertEquals(2,inputs.size)
        assertTrue(inputs.all { it.bytes.size<=8192 })
        // These fabricated rows remain invalid evidence. A bounded hint must not bless them.
        val store=candidate()
        rejected { store.initialize(access(),trustedDomainSchema(true)) }
        assertEquals("G0_SHORT_RECOVERY_REQUIRED",rejected { store.read(access()) }.message)
    }
    @Test fun pureCompileCannotBorrowEvidenceSessionAndCancellationIsPreserved()=runBlocking<Unit> {
        seed()
        db.withTransaction { NextEvidenceReadSession.read(db) {
            assertEquals("G0_PURE_INPUT_SESSION_TRANSFER",rejected { G0ColdPureInputs.build(emptyList()) }.message)
            assertNotNull(NextEvidenceReadSession.current(db))
        } }
        val cancelled=Job().apply { cancel() }
        try { withContext(cancelled) { G0ColdPureInputs.build(emptyList()) };fail("cancelled") }
        catch(_:CancellationException) { Unit }
    }
    @Test fun stalePreparedPayloadCannotHideChangedOrigin()=runBlocking<Unit> {
        seed();val store=candidate()
        store.afterColdPreparationForTest={
            db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson='{}'")
        }
        rejected { store.initialize(access(),trustedDomainSchema(true)) }
        assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
        assertEquals("G0_SHORT_RECOVERY_REQUIRED",rejected { store.read(access()) }.message)
    }
    @Test fun newHistoryAfterCaptureUsesFullCurrentProofAndLeavesNoMissingMember()=runBlocking<Unit> {
        seed();val store=candidate()
        store.afterColdPreparationForTest={ editMetric("Later pending") }
        store.initialize(access(),trustedDomainSchema(true))
        assertEquals("Later pending",store.read(access()).name);assertEquals(1,store.read(access()).pending)
        storage.isolatedBuilder=null;storage.reopen()
        assertEquals(2,count("next_request_origins"));assertEquals(1,count("next_acceptances"));assertEquals(1,count("sync_outbox"))
    }
    @Test fun cancelledPreparationAndRevokedAuthorityDoNotPublishColdState()=runBlocking<Unit> {
        seed();val cancelled=candidate();val sentinel=CancellationException("PURE_CANCEL")
        cancelled.afterColdPreparationForTest={ throw sentinel }
        try { cancelled.initialize(access(),trustedDomainSchema(true));fail("cancelled") }
        catch(actual:CancellationException) { assertSame(sentinel,actual) }
        assertEquals("G0_SHORT_RECOVERY_REQUIRED",rejected { cancelled.read(access()) }.message)
        val revoked=candidate()
        revoked.afterColdPreparationForTest={ tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2) }
        rejected { revoked.initialize(access(),trustedDomainSchema(true)) }
        assertEquals("G0_SHORT_RECOVERY_REQUIRED",rejected { revoked.read(access()) }.message)
        assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun uncertainColdCommitStillPoisonsPublication()=runBlocking<Unit> {
        seed();val store=candidate()
        store.afterColdPreparationForTest={ endFaults.failAfterNextEnd.set(true) }
        assertEquals("G0_COMMIT_RESULT_LOST",rejected { store.initialize(access(),trustedDomainSchema(true)) }.message)
        assertEquals("G0_SHORT_RECOVERY_REQUIRED",rejected { store.read(access()) }.message)
        storage.isolatedBuilder=null;storage.reopen()
        assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
}
