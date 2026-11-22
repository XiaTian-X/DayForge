package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0StrictDecoderTest : G0SealedDomainFixture() {
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    private suspend fun inputs():List<Pair<ByteArray,KSerializer<*>>> {
        register();val row=editMetric("Strict codec")
        val (http,_)=channel { successReply(it) }
        val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery))
        return listOf(requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,row.operationId)).intentJson.toByteArray() to SyncV2Operation.serializer(),
            requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION,row.operationId)).wireBytes to NextSyncPushRequest.serializer(),
            requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION,row.operationId)).resultJson.toByteArray() to NextSyncOperationResult.serializer())
    }
    private val alternateModes=listOf(G0ColdStrictDecoder(false,false),G0ColdStrictDecoder(false,true),G0ColdStrictDecoder(true,false))
    private suspend fun attempt(block:suspend ()->Any?):Result<Any?> = try { Result.success(block()) }
        catch(e:Throwable) { if(e is CancellationException) throw e;Result.failure(e) }
    private suspend fun equal(bytes:ByteArray,serializer:KSerializer<*>,codec:G0ColdStrictDecoder,accept:Boolean?=null) {
        val old=attempt { decodeFrozenSyncRequest(bytes,serializer) }
        for(mode in listOf(codec)+alternateModes) {
            val next=attempt { mode.decode(bytes,serializer) }
            assertEquals("acceptance parity",old.isSuccess,next.isSuccess)
            if(accept!=null) assertEquals("expected acceptance",accept,old.isSuccess)
            if(old.isSuccess) assertEquals(old.getOrThrow(),next.getOrThrow())
            else { assertEquals(InvalidFrozenSyncRequest::class.java,old.exceptionOrNull()!!::class.java);assertEquals(old.exceptionOrNull()!!::class.java,next.exceptionOrNull()!!::class.java) }
        }
    }
    @Test(timeout=120_000) fun repeatedValidInputsAndChangedValuesMatchOriginalWithoutInputCache()=runBlocking<Unit> {
        val codec=G0ColdStrictDecoder();val inputs=inputs();val trace=G0PhaseTrace(false)
        trace.run { repeat(3) { for((bytes,serializer) in inputs) equal(bytes,serializer,codec,true) } }
        for(phase in listOf("syntax","tree","typed","dto")) assertEquals(18,trace.count("cold_codec_$phase"))
        val (bytes,serializer)=inputs.first();val body=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        equal(JsonObject(body+("operation_id" to JsonPrimitive(id(4200)))).toString().toByteArray(),serializer,codec,true)
        equal(bytes,serializer,codec,true)
    }
    @Test(timeout=120_000) fun strictSyntaxAndUnicodeRejectionsMatchAfterRulesAreWarm()=runBlocking<Unit> {
        val codec=G0ColdStrictDecoder()
        for((bytes,serializer) in inputs()) {
            equal(bytes,serializer,codec,true)
            val text=bytes.toString(Charsets.UTF_8);val first=Json.parseToJsonElement(text).jsonObject.keys.first()
            val escaped=first.map { "\\u"+it.code.toString(16).padStart(4,'0') }.joinToString("")
            val invalid=listOf(ByteArray(0),byteArrayOf(0xc3.toByte(),0x28),
                (text+"{}").toByteArray(),text.dropLast(1).toByteArray(),
                ("{\"$escaped\":null,"+text.drop(1)).toByteArray(),
                ("{\"$first\":\"\\uD800\","+text.drop(1)).toByteArray(),
                ("{\"extra\":"+"[".repeat(17)+"0"+"]".repeat(17)+","+text.drop(1)).toByteArray(),
                text.replaceFirst(":",":/*comment*/").toByteArray(),
                text.replaceFirst(":",":NaN,").toByteArray())
            for(value in invalid) equal(value,serializer,codec,false)
            equal(bytes,serializer,codec,true)
        }
    }
    @Test(timeout=120_000) fun typedScalarsUnknownFieldsAndNullableResultsMatchOriginal()=runBlocking<Unit> {
        val codec=G0ColdStrictDecoder()
        for((bytes,serializer) in inputs()) {
            val body=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            for((key,value) in body) {
                val changes=when(value) {
                    is JsonObject -> listOf(JsonPrimitive("object"),JsonArray(emptyList()))
                    is JsonArray -> listOf(JsonPrimitive("array"),JsonObject(emptyMap()))
                    JsonNull -> listOf(JsonPrimitive(true),JsonObject(emptyMap()))
                    else -> listOf(JsonNull,JsonPrimitive(true),JsonPrimitive(2),JsonPrimitive("2"),JsonPrimitive(2.5))
                }
                for(changed in changes) equal(JsonObject(body+(key to changed)).toString().toByteArray(),serializer,codec)
            }
            equal(JsonObject(body+("unknown" to JsonPrimitive(true))).toString().toByteArray(),serializer,codec,false)
        }
    }
    @Test(timeout=120_000) fun originalSizeBoundaryAndLargeFallbackRemainStrict()=runBlocking<Unit> {
        val codec=G0ColdStrictDecoder()
        for((bytes,serializer) in inputs()) {
            for(size in listOf(8192,8193,SYNC_REQUEST_LIMIT)) {
                val large=" ".repeat(size-bytes.size).toByteArray()+bytes
                equal(large,serializer,codec,true)
            }
            equal(" ".repeat(SYNC_REQUEST_LIMIT+1).toByteArray(),serializer,codec,false)
        }
    }
    @Test(timeout=120_000) fun cancellationIsNotConvertedToInvalidDataAndDoesNotPoisonNextDecode()=runBlocking<Unit> {
        val codec=G0ColdStrictDecoder();val (bytes,serializer)=inputs().first()
        val job=Job();job.cancel()
        for(mode in listOf(codec)+alternateModes) {
            try { withContext(job) { mode.decode(bytes,serializer) };fail("cancelled input accepted") }
            catch(_:CancellationException) { Unit }
        }
        equal(bytes,serializer,codec,true)
    }
    @Test(timeout=120_000) fun stageSwitchOnlyChangesDiagnostics()=runBlocking<Unit> {
        val inputs=inputs()
        for(cache in listOf(false,true)) for(timing in listOf(false,true)) {
            val codec=G0ColdStrictDecoder(cache,timing);val trace=G0PhaseTrace(false)
            trace.run { repeat(2) { for((bytes,serializer) in inputs)
                assertEquals(decodeFrozenSyncRequest(bytes,serializer),codec.decode(bytes,serializer)) } }
            for(phase in listOf("syntax","tree","typed","dto")) assertEquals(if(timing) 6 else 0,trace.count("cold_codec_$phase"))
            assertEquals(1,trace.count("operation"))
        }
    }

}
