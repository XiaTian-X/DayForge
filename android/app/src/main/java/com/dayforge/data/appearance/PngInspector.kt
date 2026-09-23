package com.dayforge.data.appearance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import androidx.core.graphics.createBitmap
import com.dayforge.domain.model.IconBlob
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

class PngValidationException(val code: String) : IllegalArgumentException(code)

internal data class PngInspection(val width: Int, val height: Int, val depth: Int, val color: Int, val interlace: Int)

private const val MAX_BYTES = 2_097_152
private const val MAX_METADATA_CHUNK = 262_144
private const val MAX_METADATA_TOTAL = 1_048_576
private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
private val depths = mapOf(0 to setOf(1, 2, 4, 8, 16), 2 to setOf(8, 16), 3 to setOf(1, 2, 4, 8),
    4 to setOf(8, 16), 6 to setOf(8, 16))
private val channels = mapOf(0 to 1, 2 to 3, 3 to 1, 4 to 2, 6 to 4)
private val textChunks = setOf("tEXt", "zTXt", "iTXt")
private val beforePalette = setOf("cHRM", "gAMA", "iCCP", "sBIT", "sRGB")
private val beforeData = beforePalette + setOf("PLTE", "tRNS", "bKGD", "hIST", "pHYs")
private val allowed = beforeData + textChunks + setOf("IHDR", "IDAT", "IEND", "tIME")
private val adam7 = listOf(listOf(0, 0, 8, 8), listOf(4, 0, 8, 8), listOf(0, 4, 4, 8), listOf(2, 0, 4, 4),
    listOf(0, 2, 2, 4), listOf(1, 0, 2, 2), listOf(0, 1, 1, 2))

private fun pngRequire(value: Boolean, code: String) {
    if (!value) throw PngValidationException(code)
}

private fun ByteArray.u8(index: Int) = this[index].toInt() and 255
private fun ByteArray.u16(index: Int) = (u8(index) shl 8) or u8(index + 1)
private fun ByteArray.u32(index: Int): Long = (u8(index).toLong() shl 24) or
    (u8(index + 1).toLong() shl 16) or (u8(index + 2).toLong() shl 8) or u8(index + 3).toLong()

internal fun inflatePng(data: ByteArray, limit: Int): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(data)
        val result = ByteArray(limit + 1)
        var count = 0
        while (!inflater.finished()) {
            val read = inflater.inflate(result, count, result.size - count)
            count += read
            pngRequire(count <= limit, "PNG_EXPANSION_LIMIT")
            pngRequire(read > 0 || inflater.finished(), "PNG_DEFLATE")
        }
        pngRequire(inflater.remaining == 0, "PNG_DEFLATE")
        return result.copyOf(count)
    } catch (_: DataFormatException) {
        throw PngValidationException("PNG_DEFLATE")
    } finally {
        inflater.end()
    }
}

private fun keyword(data: ByteArray): ByteArray {
    val end = data.indexOf(0)
    pngRequire(end in 1..79, "PNG_METADATA")
    val name = data.copyOfRange(0, end)
    pngRequire(name.all { (it.toInt() and 255) in 32..126 || (it.toInt() and 255) in 161..255 } &&
        name.first() != 32.toByte() && name.last() != 32.toByte() &&
        (1 until name.size).none { name[it - 1] == 32.toByte() && name[it] == 32.toByte() }, "PNG_METADATA")
    return data.copyOfRange(end + 1, data.size)
}

private fun splitNull(data: ByteArray): Pair<ByteArray, ByteArray> {
    val index = data.indexOf(0)
    pngRequire(index >= 0, "PNG_METADATA")
    return data.copyOfRange(0, index) to data.copyOfRange(index + 1, data.size)
}

private fun utf8(data: ByteArray) {
    pngRequire(0.toByte() !in data, "PNG_METADATA")
    try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data))
    } catch (_: CharacterCodingException) {
        throw PngValidationException("PNG_METADATA")
    }
}

private fun metadataSize(kind: String, data: ByteArray, color: Int, depth: Int, palette: Int): Int {
    pngRequire(data.size <= MAX_METADATA_CHUNK, "PNG_METADATA_LIMIT")
    if (kind in textChunks || kind == "iCCP") {
        val tail = keyword(data)
        when (kind) {
            "tEXt" -> pngRequire(0.toByte() !in tail, "PNG_METADATA")
            "zTXt", "iCCP" -> {
                pngRequire(tail.firstOrNull() == 0.toByte(), "PNG_METADATA")
                val expanded = inflatePng(tail.copyOfRange(1, tail.size), MAX_METADATA_CHUNK)
                if (kind == "zTXt") {
                    pngRequire(0.toByte() !in expanded, "PNG_METADATA")
                } else {
                    pngRequire(expanded.size >= 132 && expanded.u32(0) == expanded.size.toLong() &&
                        expanded.copyOfRange(36, 40).toString(Charsets.US_ASCII) == "acsp" &&
                        expanded.copyOfRange(16, 20).toString(Charsets.US_ASCII) ==
                        (if (color in setOf(0, 4)) "GRAY" else "RGB "), "PNG_METADATA")
                    val count = expanded.u32(128)
                    pngRequire(count <= 4096 && 132 + 12 * count <= expanded.size, "PNG_METADATA")
                    repeat(count.toInt()) { index ->
                        val offset = expanded.u32(136 + index * 12)
                        val size = expanded.u32(140 + index * 12)
                        pngRequire(offset >= 132 + 12 * count && size > 0 &&
                            offset + size <= expanded.size && offset % 4 == 0L, "PNG_METADATA")
                    }
                }
                return data.size + expanded.size
            }
            else -> {
                pngRequire(tail.size >= 2 && tail.u8(0) in 0..1 && tail.u8(1) == 0, "PNG_METADATA")
                val (language, rest) = splitNull(tail.copyOfRange(2, tail.size))
                val (translated, body) = splitNull(rest)
                pngRequire(language.isEmpty() || Regex("[A-Za-z]{1,8}(?:-[A-Za-z0-9]{1,8})*")
                    .matches(language.toString(Charsets.US_ASCII)), "PNG_METADATA")
                utf8(translated)
                val expanded = if (tail.u8(0) == 1) inflatePng(body, MAX_METADATA_CHUNK) else body
                utf8(expanded)
                return data.size + if (tail.u8(0) == 1) expanded.size else 0
            }
        }
    } else when (kind) {
        "gAMA" -> pngRequire(data.size == 4 && data.u32(0) > 0, "PNG_METADATA")
        "cHRM" -> pngRequire(data.size == 32 && (0 until 8).all { data.u32(it * 4) <= 100000 }, "PNG_METADATA")
        "sRGB" -> pngRequire(data.size == 1 && data.u8(0) <= 3, "PNG_METADATA")
        "pHYs" -> pngRequire(data.size == 9 && data.u8(8) in 0..1, "PNG_METADATA")
        "sBIT" -> pngRequire(data.size == (if (color == 3) 3 else channels.getValue(color)) &&
            data.all { (it.toInt() and 255) in 1..(if (color == 3) 8 else depth) }, "PNG_METADATA")
        "hIST" -> pngRequire(palette > 0 && data.size == 2 * palette, "PNG_METADATA")
        "bKGD" -> {
            pngRequire(data.size == (if (color == 3) 1 else if (color in setOf(0, 4)) 2 else 6), "PNG_METADATA")
            if (color == 3) pngRequire(palette > 0 && data.u8(0) < palette, "PNG_METADATA")
            else pngRequire((0 until data.size / 2).all { data.u16(it * 2) < (1 shl depth) }, "PNG_METADATA")
        }
        "tIME" -> pngRequire(data.size == 7 && data.u8(2) in 1..12 && data.u8(3) in 1..31 &&
            data.u8(4) <= 23 && data.u8(5) <= 59 && data.u8(6) <= 60, "PNG_METADATA")
    }
    return data.size
}

private data class Pass(val columns: Int, val rows: Int, val rowBytes: Int)

private fun passes(info: PngInspection): List<Pass> =
    (if (info.interlace == 1) adam7 else listOf(listOf(0, 0, 1, 1))).mapNotNull { (x, y, dx, dy) ->
        val columns = ((info.width - x + dx - 1) / dx).coerceAtLeast(0)
        val rows = ((info.height - y + dy - 1) / dy).coerceAtLeast(0)
        if (columns == 0 || rows == 0) null
        else Pass(columns, rows, (columns * info.depth * channels.getValue(info.color) + 7) / 8)
    }

private fun validateRows(raw: ByteArray, passes: List<Pass>, depth: Int, color: Int, palette: Int) {
    var position = 0
    passes.forEach { pass ->
        var previous = ByteArray(pass.rowBytes)
        repeat(pass.rows) {
            val filtering = raw.u8(position++)
            pngRequire(filtering <= 4, "PNG_FILTER")
            if (color == 3) {
                val row = raw.copyOfRange(position, position + pass.rowBytes)
                row.indices.forEach { index ->
                    val left = if (index == 0) 0 else row.u8(index - 1)
                    val above = previous.u8(index)
                    val corner = if (index == 0) 0 else previous.u8(index - 1)
                    val predictor = when (filtering) {
                        1 -> left
                        2 -> above
                        3 -> (left + above) / 2
                        4 -> {
                            val p = left + above - corner
                            val a = kotlin.math.abs(p - left)
                            val b = kotlin.math.abs(p - above)
                            val c = kotlin.math.abs(p - corner)
                            if (a <= b && a <= c) left else if (b <= c) above else corner
                        }
                        else -> 0
                    }
                    row[index] = ((row.u8(index) + predictor) and 255).toByte()
                }
                repeat(pass.columns) { pixel ->
                    val shift = 8 - depth - pixel * depth % 8
                    pngRequire(((row.u8(pixel * depth / 8) shr shift) and ((1 shl depth) - 1)) < palette, "PNG_PALETTE")
                }
                previous = row
            }
            position += pass.rowBytes
        }
    }
    pngRequire(position == raw.size, "PNG_SCANLINES")
}

/** Call only with a private immutable-for-this-operation copy, also used for decoding. */
internal fun inspectPngStructure(data: ByteArray, expected: IconBlob): PngInspection {
    pngRequire(expected.mediaType == "image/png", "PNG_MEDIA_TYPE")
    pngRequire(data.size <= MAX_BYTES && data.size == expected.byteLength, "PNG_BYTE_LENGTH")
    val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    pngRequire(hash == expected.sha256, "PNG_HASH")
    pngRequire(data.size >= signature.size && signature.indices.all { data[it] == signature[it] }, "PNG_SIGNATURE")
    var position = 8
    var chunks = 0
    var metadata = 0
    var palette = 0
    var info: PngInspection? = null
    val seen = mutableSetOf<String>()
    val compressed = ByteArrayOutputStream()
    var dataEnded = false
    while (position < data.size) {
        pngRequire(position + 12 <= data.size, "PNG_TRUNCATED")
        val rawLength = data.u32(position)
        pngRequire(rawLength <= data.size - position - 12, "PNG_TRUNCATED")
        val length = rawLength.toInt()
        val kindBytes = data.copyOfRange(position + 4, position + 8)
        val kind = kindBytes.toString(Charsets.US_ASCII)
        val body = data.copyOfRange(position + 8, position + 8 + length)
        val crc = CRC32().apply { update(kindBytes); update(body) }.value
        pngRequire(crc == data.u32(position + 8 + length), "PNG_CRC")
        pngRequire(kind !in setOf("acTL", "fcTL", "fdAT"), "PNG_ANIMATION")
        pngRequire(kind in allowed, "PNG_CHUNK")
        chunks++
        pngRequire(chunks <= 4096, "PNG_CHUNK_LIMIT")
        pngRequire(chunks != 1 || kind == "IHDR", "PNG_ORDER")
        pngRequire(kind !in seen || kind in textChunks || kind == "IDAT", "PNG_DUPLICATE")
        pngRequire(!(kind in beforeData && "IDAT" in seen), "PNG_ORDER")
        pngRequire(!(kind in beforePalette && "PLTE" in seen), "PNG_ORDER")
        if (kind == "IHDR") {
            pngRequire(chunks == 1 && length == 13, "PNG_HEADER")
            pngRequire(body.u32(0) == expected.width.toLong() && body.u32(4) == expected.height.toLong(), "PNG_DIMENSIONS")
            info = PngInspection(expected.width, expected.height, body.u8(8), body.u8(9), body.u8(12))
            pngRequire(info.color in depths && info.depth in depths.getValue(info.color) &&
                body.u8(10) == 0 && body.u8(11) == 0 && info.interlace in 0..1, "PNG_HEADER")
        } else {
            val description = requireNotNull(info)
            val color = description.color
            val depth = description.depth
            when (kind) {
                "PLTE" -> {
                    pngRequire(color !in setOf(0, 4) && length in 1..768 && length % 3 == 0, "PNG_PALETTE")
                    pngRequire(seen.none { it in setOf("tRNS", "bKGD", "hIST") }, "PNG_ORDER")
                    palette = length / 3
                    pngRequire(color != 3 || palette <= (1 shl depth), "PNG_PALETTE")
                }
                "tRNS" -> {
                    pngRequire(color in setOf(0, 2, 3), "PNG_TRANSPARENCY")
                    if (color == 3) pngRequire(palette > 0 && length in 1..palette, "PNG_TRANSPARENCY")
                    else {
                        pngRequire(length == (if (color == 0) 2 else 6), "PNG_TRANSPARENCY")
                        pngRequire((0 until length / 2).all { body.u16(it * 2) < (1 shl depth) }, "PNG_TRANSPARENCY")
                    }
                }
                "IDAT" -> {
                    pngRequire(!dataEnded && (color != 3 || palette > 0), "PNG_ORDER")
                    compressed.write(body)
                }
                "IEND" -> pngRequire(length == 0 && "IDAT" in seen && position + 12 == data.size, "PNG_END")
                else -> {
                    pngRequire(!(kind == "sRGB" && "iCCP" in seen || kind == "iCCP" && "sRGB" in seen), "PNG_METADATA")
                    metadata += metadataSize(kind, body, color, depth, palette)
                    pngRequire(metadata <= MAX_METADATA_TOTAL, "PNG_METADATA_LIMIT")
                }
            }
        }
        if (kind != "IDAT" && "IDAT" in seen) dataEnded = true
        seen.add(kind)
        position += length + 12
    }
    pngRequire("IEND" in seen, "PNG_END")
    val inspected = requireNotNull(info)
    val passes = passes(inspected)
    val expectedLength = passes.sumOf { it.rows * (it.rowBytes + 1) }
    val raw = inflatePng(compressed.toByteArray(), expectedLength)
    pngRequire(raw.size == expectedLength, "PNG_SCANLINES")
    validateRows(raw, passes, inspected.depth, inspected.color, palette)
    return inspected
}

/** Returns an owned, density-independent sRGB bitmap. Caller must release/cache it responsibly. */
fun decodePng(source: ByteArray, expected: IconBlob): Bitmap {
    pngRequire(source.size <= MAX_BYTES && source.size == expected.byteLength, "PNG_BYTE_LENGTH")
    val data = source.copyOf()
    val inspected = inspectPngStructure(data, expected)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    pngRequire(bounds.outWidth == inspected.width && bounds.outHeight == inspected.height &&
        bounds.outMimeType == "image/png", "PNG_DECODE")
    val options = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    }
    val result = BitmapFactory.decodeByteArray(data, 0, data.size, options)
        ?: throw PngValidationException("PNG_DECODE")
    if (result.width != inspected.width || result.height != inspected.height ||
        result.config == Bitmap.Config.HARDWARE || result.byteCount > inspected.width * inspected.height * 8) {
        result.recycle()
        throw PngValidationException("PNG_DECODE")
    }
    result.density = Bitmap.DENSITY_NONE
    val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
    if (result.config == Bitmap.Config.ARGB_8888 && result.colorSpace == srgb) return result
    // Preferences are not guarantees: 16-bit PNGs can produce extended-sRGB F16.
    // Render into an explicit bounded sRGB surface, without density scaling or
    // changing the original validated bytes/hash. Both allocations are bounded.
    var normalized: Bitmap? = null
    try {
        val target = createBitmap(inspected.width, inspected.height, Bitmap.Config.ARGB_8888, true, srgb)
        normalized = target
        target.density = Bitmap.DENSITY_NONE
        Canvas(target).drawBitmap(result, 0f, 0f, null)
        return target
    } catch (error: Throwable) {
        normalized?.recycle()
        throw error
    } finally {
        result.recycle()
    }
}

/** Bounded stream variant. Caller owns both stream lifetime and returned bitmap. */
fun decodePng(source: InputStream, expected: IconBlob): Bitmap {
    pngRequire(expected.mediaType == "image/png", "PNG_MEDIA_TYPE")
    return decodePng(readIconBytes(source, expected), expected)
}
