package com.dayforge.data.appearance

import android.util.JsonReader
import android.util.JsonToken
import com.dayforge.domain.model.IconBlob
import com.dayforge.domain.model.IconPack
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal const val ICON_ARCHIVE_LIMIT = 33_554_432
internal const val ICON_MANIFEST_LIMIT = 1_048_576

/**
 * Validated bytes, not an installed/authorized account pack. Collections cannot be changed and
 * each blob read returns a fresh array from the same frozen archive, never a reopened source URI.
 */
internal class ValidatedIconPack private constructor(val manifest: IconPack, private val zip: IconPackZip,
    private val blobs: Map<String, IconBlob>) {
    suspend fun readBlob(sha256: String): ByteArray = withContext(Dispatchers.IO) {
        val blob = blobs[sha256] ?: throw IconPackInputException("PACK_ENTRY_MISSING")
        val context = currentCoroutineContext()
        zip.read("blobs/$sha256", blob.byteLength) { context.ensureActive() }
    }

    companion object {
        /** Opens once on IO and always closes. The provider must supply its own blocking-I/O deadline. */
        suspend fun read(openSource: () -> InputStream): ValidatedIconPack = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val checkpoint = { context.ensureActive() }
            val frozen = openSource().use { freezeIconArchive(it, checkpoint) }
            val zip = IconPackZip(frozen, checkpoint)
            val decoded = decodeIconManifest(zip.read("manifest.json", ICON_MANIFEST_LIMIT, checkpoint), checkpoint)
            val manifest = decoded.copy(assets = Collections.unmodifiableList(decoded.assets.toList()),
                roles = Collections.unmodifiableMap(decoded.roles.toMap()))
            val blobs = manifest.assets.flatMap { listOfNotNull(it.light, it.dark) }.associateBy { it.sha256 }
            packRequire(zip.names == setOf("manifest.json") + blobs.keys.map { "blobs/$it" }, "PACK_ENTRIES_MISMATCH")
            for ((hash, expected) in blobs) {
                checkpoint()
                val content = zip.read("blobs/$hash", expected.byteLength, checkpoint)
                // Native decode/render is essential; metadata/hash checks alone are not an image validator.
                val bitmap = if (expected.mediaType == "image/png") decodePng(content, expected) else renderSvg(content, expected)
                try { checkpoint() } finally { bitmap.recycle() }
            }
            ValidatedIconPack(manifest, zip, blobs)
        }
    }
}

/** Read through EOF, spending at most limit + 1 bytes; no available()/Content-Length assumptions. */
internal fun freezeIconArchive(source: InputStream, checkpoint: () -> Unit): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(65_536)
    while (true) {
        checkpoint()
        val requested = minOf(buffer.size, ICON_ARCHIVE_LIMIT + 1 - output.size())
        var count = source.read(buffer, 0, requested)
        packRequire(count in -1..requested, "PACK_READ_INVALID")
        if (count == 0) {
            val single = source.read()
            packRequire(single in -1..255, "PACK_READ_INVALID")
            if (single == -1) count = -1 else { buffer[0] = single.toByte(); count = 1 }
        }
        if (count == -1) return output.toByteArray()
        packRequire(output.size() + count <= ICON_ARCHIVE_LIMIT, "PACK_ARCHIVE_LIMIT")
        output.write(buffer, 0, count)
    }
}

internal fun decodeIconManifest(bytes: ByteArray, checkpoint: () -> Unit): IconPack {
    packRequire(bytes.size <= ICON_MANIFEST_LIMIT, "PACK_MANIFEST_LIMIT")
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: CharacterCodingException) { throw IconPackInputException("PACK_MANIFEST_UTF8") }
    try {
        // Kotlin serialization overwrites duplicate keys; check decoded names before constructing its tree.
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            fun string(value: String) {
                var i = 0
                while (i < value.length) {
                    val ch = value[i++]
                    if (ch.isHighSurrogate()) packRequire(i < value.length && value[i++].isLowSurrogate(), "PACK_MANIFEST_UNICODE")
                    else packRequire(!ch.isLowSurrogate(), "PACK_MANIFEST_UNICODE")
                }
            }
            fun value(depth: Int) {
                checkpoint()
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        packRequire(depth < 16, "PACK_MANIFEST_DEPTH")
                        reader.beginObject()
                        val names = hashSetOf<String>()
                        while (reader.hasNext()) {
                            val name = reader.nextName(); string(name)
                            packRequire(names.add(name), "PACK_MANIFEST_DUPLICATE")
                            value(depth + 1)
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        packRequire(depth < 16, "PACK_MANIFEST_DEPTH")
                        reader.beginArray(); while (reader.hasNext()) value(depth + 1); reader.endArray()
                    }
                    JsonToken.STRING -> string(reader.nextString())
                    JsonToken.NUMBER -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> reader.nextNull()
                    else -> throw IconPackInputException("PACK_MANIFEST_JSON")
                }
            }
            value(0)
            packRequire(reader.peek() == JsonToken.END_DOCUMENT, "PACK_MANIFEST_JSON")
        }
        return Json.decodeFromString<IconPack>(text)
    } catch (error: IconPackInputException) { throw error }
    catch (_: IOException) { throw IconPackInputException("PACK_MANIFEST_JSON") }
    catch (_: SerializationException) { throw IconPackInputException("PACK_MANIFEST_JSON") }
    catch (_: IllegalArgumentException) { throw IconPackInputException("PACK_MANIFEST_INVALID") }
}
