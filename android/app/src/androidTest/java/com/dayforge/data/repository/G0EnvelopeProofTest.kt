package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exact text is a type witness, never a replacement for the complete envelope scanner. */
@RunWith(AndroidJUnit4::class)
class G0EnvelopeProofTest : G0SealedDomainFixture() {
    override fun domainOpeningCallback() = G0SchemaOpeningCallback(true)
    private val device = id(5300)
    private suspend fun source(): JsonObject {
        register(); val row=editMetric("Envelope proof")
        return Json.parseToJsonElement(requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,row.operationId)).intentJson).jsonObject
    }
    private fun wire(text:String) = "{\"device_id\":\"$device\",\"operations\":[$text]}"
    private suspend fun attempt(body:suspend ()->Any?):Result<Any?> = try { Result.success(body()) }
        catch(failure:Throwable) { if(failure is CancellationException) throw failure;Result.failure(failure) }
    private suspend fun parity(codec:G0ColdStrictDecoder,text:String,accepted:Boolean) {
        val bytes=text.toByteArray()
        val old=attempt { decodeFrozenSyncRequest(bytes,NextSyncPushRequest.serializer()) }
        val next=attempt { codec.decodeEnvelope(bytes,device) }
        assertEquals(accepted,old.isSuccess);assertEquals(old.isSuccess,next.isSuccess)
        if(old.isSuccess) assertEquals(old.getOrThrow(),next.getOrThrow())
        else assertEquals(InvalidFrozenSyncRequest::class.java,next.exceptionOrNull()!!::class.java)
    }
    @Test(timeout=120_000) fun exactEnvelopeBorrowsOnlySuccessfulSourceProof()=runBlocking<Unit> {
        val operation=source();val codec=G0ColdStrictDecoder();val trace=G0PhaseTrace(false)
        trace.run {
            val bytes=operation.toString().toByteArray()
            codec.decode(bytes,SyncV2Operation.serializer());bytes.fill(0)
            parity(codec,wire(operation.toString()),true)
            assertEquals(2,trace.count("cold_codec_syntax"))
            for(phase in listOf("tree","typed","dto")) assertEquals(1,trace.count("cold_codec_$phase"))
            assertEquals(1,trace.count("cold_codec_text_envelope_reuse"))
            val invalid=JsonObject(operation+("unknown" to JsonPrimitive(true))).toString()
            assertTrue(attempt { codec.decode(invalid.toByteArray(),SyncV2Operation.serializer()) }.isFailure)
            parity(codec,wire(invalid),false)
            parity(codec,wire(operation.toString()),true)
            assertEquals(2,trace.count("cold_codec_text_envelope_reuse"))
        }
    }
    @Test(timeout=120_000) fun wholeEnvelopeDepthRejectionSurvivesValidSourceAndConstructor()=runBlocking<Unit> {
        val operation=source()
        val nested=Json.parseToJsonElement("{\"x\":"+"[".repeat(14)+"0"+"]".repeat(14)+"}")
        val text=JsonObject(operation+("payload" to nested)).toString()
        val source=decodeFrozenSyncRequest(text.toByteArray(),SyncV2Operation.serializer())
        assertEquals(1,NextSyncPushRequest(device,listOf(source)).operations.size)
        val codec=G0ColdStrictDecoder();codec.decode(text.toByteArray(),SyncV2Operation.serializer())
        val trace=G0PhaseTrace(false)
        trace.run { parity(codec,wire(text),false) }
        assertEquals(1,trace.count("cold_codec_syntax"));assertEquals(0,trace.count("cold_codec_text_envelope_reuse"))
        assertFalse(trace.spans.single { it.phase=="cold_codec_syntax" }.ok)
    }
    @Test(timeout=120_000) fun missingExtraMalformedAndChangedMembersKeepOriginalRejections()=runBlocking<Unit> {
        val operation=source();val codec=G0ColdStrictDecoder()
        codec.decode(operation.toString().toByteArray(),SyncV2Operation.serializer())
        val good=Json.parseToJsonElement(wire(operation.toString())).jsonObject
        val bad=listOf(JsonObject(good-"device_id"),JsonObject(good-"operations"),
            JsonObject(good+("extra" to JsonNull)),JsonObject(good+("device_id" to JsonNull)),
            JsonObject(good+("device_id" to JsonPrimitive(1))),JsonObject(good+("operations" to JsonNull)),
            JsonObject(good+("operations" to JsonArray(emptyList()))))
        for(body in bad) parity(codec,body.toString(),false)
        parity(codec,wire(JsonObject(operation+("base_revision" to JsonPrimitive("1"))).toString()),false)
        val text=good.toString()
        for(body in listOf("{\"device_id\":\"$device\","+text.drop(1),text+"{}",text.dropLast(1))) parity(codec,body,false)
        parity(codec,wire(JsonObject(operation+("operation_id" to JsonPrimitive(id(5301)))).toString()),true)
        parity(codec,text,true)
    }
    @Test(timeout=120_000) fun nonidenticalAndLargeEnvelopesUseStrictFallback()=runBlocking<Unit> {
        val operation=source();val codec=G0ColdStrictDecoder();val trace=G0PhaseTrace(false)
        trace.run {
            codec.decode(operation.toString().toByteArray(),SyncV2Operation.serializer())
            val good=wire(operation.toString())
            parity(codec," "+good,true)
            parity(codec,"{\"operations\":[$operation],\"device_id\":\"$device\"}",true)
            parity(codec,good.replace(device,id(5302)),true)
            parity(codec," ".repeat(8193)+good,true)
            parity(codec,"{\"device_id\":\"$device\",\"operations\":[$operation,$operation]}",true)
            assertEquals(0,trace.count("cold_codec_text_envelope_reuse"))
            parity(codec,"\uFEFF"+good,true)
            assertEquals(1,trace.count("cold_codec_text_envelope_reuse"))
        }
    }
    @Test(timeout=120_000) fun disabledMemoAndCancelledCallerCannotBorrowProof()=runBlocking<Unit> {
        val operation=source();val trace=G0PhaseTrace(false)
        trace.run {
            val disabled=G0ColdStrictDecoder(reusePureValues=false)
            disabled.decode(operation.toString().toByteArray(),SyncV2Operation.serializer())
            parity(disabled,wire(operation.toString()),true)
            assertEquals(0,trace.count("cold_codec_text_envelope_reuse"))
            val codec=G0ColdStrictDecoder();codec.decode(operation.toString().toByteArray(),SyncV2Operation.serializer())
            try { withContext(Job().also { it.cancel() }) { codec.decodeEnvelope(wire(operation.toString()).toByteArray(),device) };fail("cancel accepted") }
            catch(_:CancellationException) { Unit }
            assertEquals(0,trace.count("cold_codec_text_envelope_reuse"))
            parity(codec,wire(operation.toString()),true)
            assertEquals(1,trace.count("cold_codec_text_envelope_reuse"))
        }
    }
}
