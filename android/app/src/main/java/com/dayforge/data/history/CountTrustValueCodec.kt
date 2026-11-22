package com.dayforge.data.history

import com.dayforge.data.history.HistoryBlockCodec.Record
import com.dayforge.data.history.HistoryBlockPlanner.OrderKey
import java.time.LocalDate
import kotlinx.serialization.json.*

/** The original COUNT value framing, shared by ordinary reads and incremental maintenance.
 * Parsed pointers are immutable plans. Neither a pointer nor parsed JSON is business authority.
 */
internal object CountTrustValueCodec {
    data class Pointer(val name: String, val length: Int, val chunks: List<String>) {
        fun chunkLength(index: Int): Int = if (index == chunks.lastIndex)
            length - index * CountTrustIndex.CHUNK_BYTES else CountTrustIndex.CHUNK_BYTES
    }
    fun pointer(found: Record, expectedName: String? = null): Pointer {
        val encoded = Json.parseToJsonElement(found.payload.toByteArray().toString(Charsets.UTF_8)).jsonObject
        require(encoded.keys == setOf("key", "length", "chunks"))
        val name = encoded.getValue("key").jsonPrimitive.also { require(it.isString) }.content
        val id = CountTrustIndex.hash(name.toByteArray(Charsets.UTF_8))
        require((expectedName == null || name == expectedName) && found.sourceId == id &&
            found.key == OrderKey(LocalDate.of(1970, 1, 1), id))
        val length = encoded.getValue("length").jsonPrimitive.int
        require(length in 1..CountTrustIndex.MAX_VALUE_BYTES)
        val chunks = encoded.getValue("chunks").jsonArray.map { it.jsonPrimitive.content }
        require(chunks.size == (length + CountTrustIndex.CHUNK_BYTES - 1) / CountTrustIndex.CHUNK_BYTES)
        return Pointer(name, length, java.util.Collections.unmodifiableList(chunks))
    }
    fun value(bytes: ByteArray): JsonElement? =
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).takeUnless { it == JsonNull }
}
