package com.dayforge.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/** Metadata strings must not silently accept JSON numbers or booleans. */
object ContractStringSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("ContractString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String {
        if (decoder !is JsonDecoder) return decoder.decodeString()
        val primitive = decoder.decodeJsonElement() as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw SerializationException("Expected JSON string")
        return primitive.content
    }
}
