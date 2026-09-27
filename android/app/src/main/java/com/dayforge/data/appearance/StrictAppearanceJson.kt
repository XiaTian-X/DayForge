package com.dayforge.data.appearance

import android.util.JsonReader
import android.util.JsonToken
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Decoded-key checks precede serializers which otherwise overwrite duplicate object keys. */
internal fun strictAppearanceJson(
    bytes: ByteArray, limit: Int, checkpoint: () -> Unit, fail: (String) -> Nothing
): String {
    if (bytes.size > limit) fail("LIMIT")
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: CharacterCodingException) { fail("UTF8") }
    try {
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            fun string(value: String) {
                var i = 0
                while (i < value.length) {
                    val ch = value[i++]
                    if (ch.isHighSurrogate()) {
                        if (i >= value.length || !value[i++].isLowSurrogate()) fail("UNICODE")
                    } else if (ch.isLowSurrogate()) fail("UNICODE")
                }
            }
            fun value(depth: Int) {
                checkpoint()
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        if (depth >= 16) fail("DEPTH")
                        reader.beginObject()
                        val names = hashSetOf<String>()
                        while (reader.hasNext()) {
                            val name = reader.nextName(); string(name)
                            if (!names.add(name)) fail("DUPLICATE")
                            value(depth + 1)
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        if (depth >= 16) fail("DEPTH")
                        reader.beginArray(); while (reader.hasNext()) value(depth + 1); reader.endArray()
                    }
                    JsonToken.STRING -> string(reader.nextString())
                    JsonToken.NUMBER -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> reader.nextNull()
                    else -> fail("JSON")
                }
            }
            value(0)
            if (reader.peek() != JsonToken.END_DOCUMENT) fail("JSON")
        }
    } catch (_: IOException) { fail("JSON") }
    return text
}
