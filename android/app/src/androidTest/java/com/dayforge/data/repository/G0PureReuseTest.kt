package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Differential tests against unchanged generated codecs and the production metric mapper. */
@RunWith(AndroidJUnit4::class)
class G0PureReuseTest : G0SealedDomainFixture() {
    override fun domainOpeningCallback() = G0SchemaOpeningCallback(true)
    private suspend fun source(): JsonObject {
        register(); val row = editMetric("Pure reuse")
        return Json.parseToJsonElement(requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,row.operationId)).intentJson).jsonObject
    }
    private fun wire(operation: JsonElement, device: JsonElement = JsonPrimitive(id(5100))) =
        buildJsonObject { put("device_id",device);put("operations",JsonArray(listOf(operation))) }
    private suspend fun attempt(block: suspend () -> Any?): Result<Any?> = try { Result.success(block()) }
        catch (failure: Throwable) { if(failure is CancellationException) throw failure; Result.failure(failure) }
    private suspend fun parity(codec: G0ColdStrictDecoder, body: ByteArray, accepted: Boolean? = null) {
        val expected=attempt { decodeFrozenSyncRequest(body,NextSyncPushRequest.serializer()) }
        val actual=attempt { codec.decode(body,NextSyncPushRequest.serializer()) }
        assertEquals(expected.isSuccess,actual.isSuccess)
        accepted?.let { assertEquals(it,expected.isSuccess) }
        if(expected.isSuccess) assertEquals(expected.getOrThrow(),actual.getOrThrow())
        else assertEquals(InvalidFrozenSyncRequest::class.java,actual.exceptionOrNull()!!::class.java)
    }
    @Test(timeout=120_000) fun onlySuccessfulExactOperationSubtreeCanBeReused()=runBlocking<Unit> {
        val operation=source();val codec=G0ColdStrictDecoder();val trace=G0PhaseTrace(false)
        trace.run {
            parity(codec,wire(operation).toString().toByteArray(),true)
            assertEquals(0,trace.count("cold_codec_operation_reuse"))
            codec.decode(operation.toString().toByteArray(),SyncV2Operation.serializer())
            parity(codec,wire(operation).toString().toByteArray(),true)
            assertEquals(1,trace.count("cold_codec_operation_reuse"))
            val changed=JsonObject(operation+("operation_id" to JsonPrimitive(id(5101))))
            parity(codec,wire(changed).toString().toByteArray(),true)
            assertEquals(1,trace.count("cold_codec_operation_reuse"))
            val invalid=JsonObject(operation+("unknown" to JsonPrimitive(true)))
            assertTrue(attempt { codec.decode(invalid.toString().toByteArray(),SyncV2Operation.serializer()) }.isFailure)
            parity(codec,wire(invalid).toString().toByteArray(),false)
            parity(codec,wire(operation).toString().toByteArray(),true)
            assertEquals(2,trace.count("cold_codec_operation_reuse"))
            val disabled=G0ColdStrictDecoder(reusePureValues=false)
            disabled.decode(operation.toString().toByteArray(),SyncV2Operation.serializer())
            parity(disabled,wire(operation).toString().toByteArray(),true)
            assertEquals(2,trace.count("cold_codec_operation_reuse"))
        }
    }
    @Test(timeout=120_000) fun exactSubtreeDoesNotHideOuterOrInnerRejections()=runBlocking<Unit> {
        val operation=source();val codec=G0ColdStrictDecoder()
        codec.decode(operation.toString().toByteArray(),SyncV2Operation.serializer())
        val good=wire(operation)
        val invalid=listOf(JsonObject(good+("unknown" to JsonNull)),JsonObject(good-"device_id"),
            JsonObject(good-"operations"),wire(operation,JsonPrimitive("invalid")),wire(operation,JsonNull),
            wire(operation,JsonPrimitive(1)),JsonObject(good+("operations" to JsonNull)),
            JsonObject(good+("operations" to JsonArray(emptyList()))),
            wire(JsonObject(operation+("base_revision" to JsonPrimitive("1")))),
            wire(JsonObject(operation+("unknown" to JsonPrimitive(true)))))
        for(body in invalid) parity(codec,body.toString().toByteArray(),false)
        for(count in listOf(2,100,101)) parity(codec,JsonObject(good+("operations" to JsonArray(List(count) { operation }))).toString().toByteArray(),count<=100)
        val text=good.toString()
        for(body in listOf("{\"device_id\":\"${id(5100)}\","+text.drop(1),text+"{}",text.dropLast(1)))
            parity(codec,body.toByteArray(),false)
        parity(codec,good.toString().toByteArray(),true)
    }
    @Test(timeout=120_000) fun reorderedKeysLargeFallbackAndCancellationKeepOriginalAcceptance()=runBlocking<Unit> {
        val operation=source();val codec=G0ColdStrictDecoder();val trace=G0PhaseTrace(false)
        trace.run {
            codec.decode(operation.toString().toByteArray(),SyncV2Operation.serializer())
            val reordered=JsonObject(operation.entries.reversed().associate { it.toPair() })
            parity(codec,wire(reordered).toString().toByteArray(),true)
            assertEquals(1,trace.count("cold_codec_operation_reuse"))
            parity(codec,(" ".repeat(8193)+wire(operation)).toByteArray(),true)
            assertEquals(1,trace.count("cold_codec_operation_reuse"))
            val cancelled=Job().also { it.cancel() }
            try { withContext(cancelled) { codec.decode(wire(operation).toString().toByteArray(),NextSyncPushRequest.serializer()) };fail("cancel accepted") }
            catch (_:CancellationException) { Unit }
            parity(codec,wire(operation).toString().toByteArray(),true)
            assertEquals(2,trace.count("cold_codec_operation_reuse"))
        }
    }
    private suspend fun entity(): JsonObject {
        register();val row=editMetric("Times");val (http,_)=channel { successReply(it) }
        val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery))
        return Json.parseToJsonElement(requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION,row.operationId)).resultJson).jsonObject.getValue("entity").jsonObject
    }
    private suspend fun normalized(normalizer:G0ColdMetricNormalization,body:JsonObject,accepted:Boolean) {
        val revision=body.getValue("revision").jsonPrimitive.long
        val old=attempt { NextStructureMapper.readMetric(body,metric.uuid,revision) }
        val next=attempt { normalizer.read(body,metric.uuid,revision) }
        assertEquals(accepted,old.isSuccess);assertEquals(old.isSuccess,next.isSuccess)
        if(old.isSuccess) assertEquals(old.getOrThrow(),next.getOrThrow())
    }
    @Test(timeout=120_000) fun exactTimestampMemoHasBoundedEvictionAndNoCrossValueReuse()=runBlocking<Unit> {
        val body=entity();val normalizer=G0ColdMetricNormalization();val trace=G0PhaseTrace(false)
        fun at(day:Int)=JsonObject(body+mapOf("created_at" to JsonPrimitive("2026-10-${day.toString().padStart(2,'0')}T00:00:00Z"),"updated_at" to JsonPrimitive("2026-10-${day.toString().padStart(2,'0')}T00:00:00Z")))
        trace.run {
            for(day in 1..5) normalized(normalizer,at(day),true)
            assertEquals(5,trace.count("cold_normalize_instant_parse"));assertEquals(5,trace.count("cold_normalize_instant_reuse"))
            normalized(normalizer,at(1),true)
            assertEquals(6,trace.count("cold_normalize_instant_parse"))
            normalized(G0ColdMetricNormalization(false),at(1),true)
            assertEquals(8,trace.count("cold_normalize_instant_parse"))
        }
    }
    @Test(timeout=120_000) fun warmTimeMemoRetainsTypeUtcRangeAndRemainingFieldChecks()=runBlocking<Unit> {
        val body=entity();val normalizer=G0ColdMetricNormalization()
        normalized(normalizer,body,true)
        for(key in listOf("created_at","updated_at")) for(value in listOf(JsonNull,JsonPrimitive(1),JsonPrimitive("bad"),
            JsonPrimitive("0000-01-01T00:00:00Z"),JsonPrimitive("+10000-01-01T00:00:00Z"),JsonPrimitive("2026-02-30T00:00:00Z")))
            normalized(normalizer,JsonObject(body+(key to value)),false)
        for(text in listOf("2026-10-01T00:00:00Z","2026-10-01T08:00:00+08:00","2026-10-01T00:00:00.123456789Z"))
            normalized(normalizer,JsonObject(body+("updated_at" to JsonPrimitive(text))),true)
        normalized(normalizer,JsonObject(body+("public_id" to JsonPrimitive(id(5102)))),false)
        normalized(normalizer,JsonObject(body+("unknown" to JsonPrimitive(true))),false)
        normalized(normalizer,body,true)
    }
}
