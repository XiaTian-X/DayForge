package com.dayforge.data.api

import com.dayforge.data.appearance.strictAppearanceJson
import com.dayforge.domain.model.isContractIntegerToken
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import okhttp3.Response

private val syncCharsetParameter = Regex("""\s*charset\s*=\s*(?:"[^"]+"|[a-zA-Z0-9_-]+)\s*""", RegexOption.IGNORE_CASE)

/** Do not mistake the library's default for proof of an UNKNOWN explicit charset. */
internal fun requireSyncJsonType(response: Response) {
    val raw = response.headers.values("Content-Type").singleOrNull() ?: throw NextSyncReplyInvalid()
    val type = response.body?.contentType() ?: throw NextSyncReplyInvalid()
    if (type.type != "application" || type.subtype != "json") throw NextSyncReplyInvalid()
    if (';' in raw) {
        val parameter = raw.substringAfter(';')
        if (!syncCharsetParameter.matches(parameter) ||
            type.charset() != Charsets.UTF_8) throw NextSyncReplyInvalid()
    }
}

/** Check typed wire scalars before legacy DTO serializers can coerce them.
 * Opaque entity JSON remains domain input; this is not a second domain mapper.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun <T> decodeSyncReply(bytes: ByteArray, limit: Int, serializer: KSerializer<T>, checkpoint: () -> Unit,
    requiredFields: Set<String> = emptySet()): T {
    val json = Json
    val text = strictAppearanceJson(bytes, limit, checkpoint, { throw NextSyncReplyInvalid() })
    return try {
        val value = json.parseToJsonElement(text)
        if (requiredFields.isNotEmpty()) require(value is JsonObject && value.keys.containsAll(requiredFields))
        fun typed(raw: JsonElement, descriptor: SerialDescriptor) {
            checkpoint()
            if (raw == JsonNull) {
                require(descriptor.isNullable)
                return
            }
            // JSON object/entity payloads have their own strict domain validators.
            if (descriptor.serialName.startsWith("kotlinx.serialization.json.")) return
            when (descriptor.kind) {
                PrimitiveKind.STRING, SerialKind.ENUM -> require(raw is JsonPrimitive && raw.isString)
                PrimitiveKind.BOOLEAN -> require(raw is JsonPrimitive && !raw.isString && raw.booleanOrNull != null)
                PrimitiveKind.INT -> require(raw is JsonPrimitive && !raw.isString &&
                    isContractIntegerToken(raw.content) && raw.content.toIntOrNull() != null)
                PrimitiveKind.LONG -> require(raw is JsonPrimitive && !raw.isString &&
                    isContractIntegerToken(raw.content) && raw.content.toLongOrNull() != null)
                StructureKind.LIST -> {
                    require(raw is JsonArray)
                    raw.forEach { typed(it, descriptor.getElementDescriptor(0)) }
                }
                StructureKind.CLASS, StructureKind.OBJECT -> {
                    require(raw is JsonObject)
                    repeat(descriptor.elementsCount) { index ->
                        raw[descriptor.getElementName(index)]?.let { typed(it, descriptor.getElementDescriptor(index)) }
                    }
                }
                else -> error("Unsupported sync response descriptor")
            }
        }
        typed(value, serializer.descriptor)
        json.decodeFromJsonElement(serializer, value)
    } catch (_: SerializationException) { throw NextSyncReplyInvalid() }
    catch (_: IllegalArgumentException) { throw NextSyncReplyInvalid() }
    catch (_: NoSuchElementException) { throw NextSyncReplyInvalid() }
    catch (_: java.time.DateTimeException) { throw NextSyncReplyInvalid() }
}
