package com.dayforge.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** JSON numeric strings/booleans/floats must not silently coerce into contract integers. */
object ContractIntegerSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("ContractInteger", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)

    override fun deserialize(decoder: Decoder): Int {
        if (decoder !is JsonDecoder) return decoder.decodeInt()
        val primitive = decoder.decodeJsonElement() as? JsonPrimitive
        if (primitive == null || primitive.isString) throw SerializationException("Expected JSON integer")
        return primitive.intOrNull ?: throw SerializationException("Expected 32-bit JSON integer")
    }
}
