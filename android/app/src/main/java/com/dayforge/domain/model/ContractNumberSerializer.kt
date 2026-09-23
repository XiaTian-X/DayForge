package com.dayforge.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Portable settings keep existing finite Double semantics without accepting quoted numbers. */
object ContractNumberSerializer : KSerializer<Double> {
    override val descriptor = PrimitiveSerialDescriptor("ContractNumber", PrimitiveKind.DOUBLE)
    override fun serialize(encoder: Encoder, value: Double) {
        if (!value.isFinite()) throw SerializationException("Expected finite JSON number")
        encoder.encodeDouble(value)
    }
    override fun deserialize(decoder: Decoder): Double {
        val value = if (decoder is JsonDecoder) {
            val primitive = decoder.decodeJsonElement() as? JsonPrimitive
            if (primitive == null || primitive.isString) throw SerializationException("Expected JSON number")
            primitive.doubleOrNull ?: throw SerializationException("Expected JSON number")
        } else decoder.decodeDouble()
        if (!value.isFinite()) throw SerializationException("Expected finite JSON number")
        return value
    }
}
