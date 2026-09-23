package com.dayforge.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

object ContractLongSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("ContractLong", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long {
        if (decoder !is JsonDecoder) return decoder.decodeLong()
        val value = decoder.decodeJsonElement() as? JsonPrimitive
        if (value == null || value.isString) throw SerializationException("Expected JSON integer")
        return value.longOrNull ?: throw SerializationException("Expected 64-bit JSON integer")
    }
}
