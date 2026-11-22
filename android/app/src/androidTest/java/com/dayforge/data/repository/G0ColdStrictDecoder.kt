package com.dayforge.data.repository

import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.strictAppearanceJson
import com.dayforge.domain.model.isContractIntegerToken
import com.dayforge.domain.model.isContractUuid
import java.util.IdentityHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

/** G0 only. Same byte/duplicate/depth/Unicode scanner, JSON parser and DTO constructors as the
 * original codec. Memoize only executable scalar-check rules for fixed code-owned descriptors,
 * plus one bounded, successfully decoded operation subtree. Never cache SQL evidence or authorization. The owner replaces per block.
 * Generic decoding retains every scalar check. Exact known-device envelopes can borrow the
 * successful source type proof only AFTER scanning the entire envelope, including its depth.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class G0ColdStrictDecoder(
    private val cacheRules: Boolean = true,
    private val measureStages: Boolean = true,
    private val reusePureValues: Boolean = true,
    private val predecoded: G0ColdPureInputs? = null
) {
    private fun interface Rule { fun check(value:JsonElement, checkpoint:()->Unit) }
    private val rules=IdentityHashMap<SerialDescriptor,Rule>()
    private var operationTree: JsonElement? = null
    private var operationValue: SyncV2Operation? = null
    private var operationText: String? = null
    // Only exact code-owned serializers may enter this path. All input syntax and typed
    // checkpoints have already run. Unknown/missing outer fields use the generated decoder.
    // The real envelope constructor still enforces device UUID and nonempty batch limits.
    @Suppress("UNCHECKED_CAST")
    private fun <T> dto(serializer: KSerializer<T>, value: JsonElement): T {
        if (reusePureValues && serializer === NextSyncPushRequest.serializer() && value is JsonObject &&
            value.keys == setOf("device_id", "operations")) {
            val operations = value["operations"] as? JsonArray
            val saved = operationValue
            if (saved != null && operations?.size == 1 && operations[0] == operationTree) {
                val result = NextSyncPushRequest(value.getValue("device_id").jsonPrimitive.content, listOf(saved))
                phase("operation_reuse") { Unit }
                return result as T
            }
        }
        val decoded = Json.decodeFromJsonElement(serializer, value)
        if (reusePureValues && serializer === SyncV2Operation.serializer()) {
            operationTree = value
            operationValue = decoded as SyncV2Operation
        }
        return decoded
    }
    private fun <T> phase(name:String,block:()->T):T {
        val trace=G0PhaseTrace.current.get()
        return if(!measureStages || trace==null) block() else trace.call("cold_codec_$name",block)
    }
    suspend fun <T> decode(bytes:ByteArray,serializer:KSerializer<T>):T = decodeOwned(bytes,serializer,null)
    suspend fun decodeEnvelope(bytes:ByteArray,device:String):NextSyncPushRequest =
        decodeOwned(bytes,NextSyncPushRequest.serializer(),device)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> decodeOwned(bytes:ByteArray,serializer:KSerializer<T>,device:String?):T {
        val caller=currentCoroutineContext();caller.ensureActive()
        predecoded?.find(bytes,serializer)?.let { value ->
            return phase("predecoded_hit") { value }
        }
        // Keep the original dispatcher and bounded codec for larger input.
        if(bytes.size>8192) return decodeFrozenSyncRequest(bytes,serializer)
        try {
            val text=phase("syntax") { strictAppearanceJson(bytes,SYNC_REQUEST_LIMIT,{caller.ensureActive()},{throw NextSyncReplyInvalid()}) }
            // Exact full-text equality proves the outer required fields, sole member and all
            // source scalar types. Never interpolate an unchecked device or caller-supplied DTO.
            // Keep the FULL scanner above: a valid standalone source can exceed the depth limit
            // when nested in an envelope. Non-identical representations use the original path.
            val sourceText=operationText; val sourceValue=operationValue
            if(device!=null && serializer === NextSyncPushRequest.serializer() && isContractUuid(device) &&
                sourceText!=null && sourceValue!=null &&
                phase("text_compare") { text == "{\"device_id\":\"$device\",\"operations\":[$sourceText]}" }) {
                caller.ensureActive()
                return phase("text_envelope_reuse") {
                    phase("operation_reuse") { NextSyncPushRequest(device,listOf(sourceValue)) as T }
                }
            }
            val value=phase("tree") { Json.parseToJsonElement(text) }
            phase("typed") { rule(serializer.descriptor).check(value) { caller.ensureActive() } }
            val result=phase("dto") { dto(serializer,value) }
            if(reusePureValues && serializer === SyncV2Operation.serializer()) operationText=text
            return result
        } catch (_:NextSyncReplyInvalid) { throw InvalidFrozenSyncRequest() }
        catch (_:SerializationException) { throw InvalidFrozenSyncRequest() }
        catch (_:IllegalArgumentException) { throw InvalidFrozenSyncRequest() }
        catch (_:NoSuchElementException) { throw InvalidFrozenSyncRequest() }
        catch (_:java.time.DateTimeException) { throw InvalidFrozenSyncRequest() }
    }
    private fun rule(descriptor:SerialDescriptor):Rule {
        if(cacheRules) rules[descriptor]?.let { return it }
        val nullable=descriptor.isNullable
        val opaque=descriptor.serialName.startsWith("kotlinx.serialization.json.")
        val kind=descriptor.kind
        val fields=if(kind==StructureKind.CLASS || kind==StructureKind.OBJECT)
            List(descriptor.elementsCount) { descriptor.getElementName(it) } else emptyList()
        val result=Rule { raw,checkpoint ->
            checkpoint()
            if(raw==JsonNull) require(nullable)
            else if(!opaque) when(kind) {
                PrimitiveKind.STRING,SerialKind.ENUM -> require(raw is JsonPrimitive && raw.isString)
                PrimitiveKind.BOOLEAN -> require(raw is JsonPrimitive && !raw.isString && raw.booleanOrNull!=null)
                PrimitiveKind.INT -> require(raw is JsonPrimitive && !raw.isString && isContractIntegerToken(raw.content) && raw.content.toIntOrNull()!=null)
                PrimitiveKind.LONG -> require(raw is JsonPrimitive && !raw.isString && isContractIntegerToken(raw.content) && raw.content.toLongOrNull()!=null)
                StructureKind.LIST -> { require(raw is JsonArray);raw.forEach { rule(descriptor.getElementDescriptor(0)).check(it,checkpoint) } }
                StructureKind.MAP -> {
                    require(raw is JsonObject && descriptor.getElementDescriptor(0).kind==PrimitiveKind.STRING)
                    raw.forEach { (key,value) -> rule(descriptor.getElementDescriptor(0)).check(JsonPrimitive(key),checkpoint);rule(descriptor.getElementDescriptor(1)).check(value,checkpoint) }
                }
                StructureKind.CLASS,StructureKind.OBJECT -> {
                    require(raw is JsonObject)
                    fields.forEachIndexed { index,name -> raw[name]?.let { rule(descriptor.getElementDescriptor(index)).check(it,checkpoint) } }
                }
                else -> error("Unsupported sync response descriptor")
            }
        }
        if(cacheRules && rules.size<128) rules[descriptor]=result
        return result
    }
}
