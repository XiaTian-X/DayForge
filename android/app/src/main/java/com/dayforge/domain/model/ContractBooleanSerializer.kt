package com.dayforge.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Quoted booleans are not booleans in the portable JSON contract. */
object ContractBooleanSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("ContractBoolean", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)

    override fun deserialize(decoder: Decoder): Boolean {
        if (decoder !is JsonDecoder) return decoder.decodeBoolean()
        val primitive = decoder.decodeJsonElement() as? JsonPrimitive
        if (primitive == null || primitive.isString) throw SerializationException("Expected JSON boolean")
        return primitive.booleanOrNull ?: throw SerializationException("Expected JSON boolean")
    }
}
