package com.dayforge.data.appearance

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.model.IconBlob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.CRC32
import java.util.zip.Adler32
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

@RunWith(AndroidJUnit4::class)
class PngInspectorTest {
    private fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(data)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                check(count > 0)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally { deflater.end() }
    }

    private fun chunk(kind: String, body: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { stream ->
            val name = kind.toByteArray(Charsets.US_ASCII)
            stream.writeInt(body.size)
            stream.write(name)
            stream.write(body)
            stream.writeInt(CRC32().apply { update(name); update(body) }.value.toInt())
        }
    }.toByteArray()

    private fun png(size: Int = 1, after: ByteArray = byteArrayOf(), compressed: ByteArray? = null): ByteArray {
        val header = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(size); stream.writeInt(size)
                stream.write(byteArrayOf(8, 6, 0, 0, 0))
            }
        }.toByteArray()
        val raw = ByteArray((size * 4 + 1) * size)
        repeat(size) { y -> repeat(size) { x ->
            val offset = y * (size * 4 + 1) + x * 4 + 1
            raw[offset] = 255.toByte()
            raw[offset + 3] = 255.toByte()
        } }
        return byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", header) +
            chunk("IDAT", compressed ?: compress(raw)) + after + chunk("IEND", byteArrayOf())
    }

    private fun blob(data: ByteArray, size: Int = 1) = IconBlob(
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) },
        data.size, "image/png", size, size
    )

    private fun rejects(data: ByteArray, code: String, expected: IconBlob = blob(data)) {
        assertEquals(code, assertThrows(PngValidationException::class.java) { decodePng(data, expected) }.code)
    }

    private fun accepts(data: ByteArray, size: Int = 1) {
        val bitmap = decodePng(data, blob(data, size))
        try {
            assertEquals(size, bitmap.width)
            assertEquals(size, bitmap.height)
            assertEquals(Color.RED, bitmap.getPixel(size - 1, size - 1))
        } finally { bitmap.recycle() }
    }

    @Test
    fun sharedBytesDecodePixelsOrFailWithExpectedCategory() {
        val cases = InstrumentationRegistry.getInstrumentation().context.assets.open("next/png.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        assertEquals(46, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val data = Base64.decode(case.getValue("png").jsonPrimitive.content, Base64.DEFAULT)
            val expected = IconBlob(
                MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) },
                data.size, "image/png", case.getValue("width").jsonPrimitive.int, case.getValue("height").jsonPrimitive.int
            )
            val error = case["error"]?.jsonPrimitive?.content
            if (error != null) {
                val failure = assertThrows(name, PngValidationException::class.java) { decodePng(data, expected) }
                assertEquals(name, error, failure.code)
            } else {
                val bitmap = try {
                    decodePng(data, expected)
                } catch (error: PngValidationException) {
                    throw AssertionError("Valid PNG $name must decode", error)
                }
                try {
                    assertEquals(name, expected.width, bitmap.width)
                    assertEquals(name, expected.height, bitmap.height)
                    assertEquals(name, Bitmap.Config.ARGB_8888, bitmap.config)
                    assertEquals(name, ColorSpace.get(ColorSpace.Named.SRGB), bitmap.colorSpace)
                    assertEquals(name, Bitmap.DENSITY_NONE, bitmap.density)
                    val pixels = case.getValue("rgba").jsonArray.map { it.jsonPrimitive.int }
                    for (index in 0 until expected.width * expected.height) {
                        val actual = bitmap.getPixel(index % expected.width, index / expected.width)
                        val offset = index * 4
                        assertEquals(name, pixels[offset + 3], Color.alpha(actual))
                        // Android premultiplies RGB; fully transparent hidden RGB is
                        // not displayable and is not part of a template's alpha mask.
                        if (pixels[offset + 3] != 0) {
                            assertEquals(name, pixels[offset], Color.red(actual))
                            assertEquals(name, pixels[offset + 1], Color.green(actual))
                            assertEquals(name, pixels[offset + 2], Color.blue(actual))
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun exactInflateLimitAndCompleteStream() {
        fun deflate(input: ByteArray): ByteArray {
            val deflater = Deflater()
            try {
                deflater.setInput(input)
                deflater.finish()
                val buffer = ByteArray(128)
                val size = deflater.deflate(buffer)
                check(deflater.finished())
                return buffer.copyOf(size)
            } finally {
                deflater.end()
            }
        }
        val compressed = deflate("abc".toByteArray())
        assertEquals("abc", inflatePng(compressed, 3).toString(Charsets.UTF_8))
        assertEquals(0, inflatePng(deflate(byteArrayOf()), 0).size)
        listOf(compressed + deflate(byteArrayOf()), compressed.copyOf(compressed.size - 1)).forEach { data ->
            assertEquals("PNG_DEFLATE", assertThrows(PngValidationException::class.java) { inflatePng(data, 3) }.code)
        }
    }

    @Test
    fun exactCanvasAndDescriptionBoundaries() {
        accepts(png())
        accepts(png(size = 1024), size = 1024)
        val data = png()
        val expected = blob(data)
        rejects(data, "PNG_HASH", expected.copy(sha256 = "0".repeat(64)))
        rejects(data, "PNG_BYTE_LENGTH", expected.copy(byteLength = data.size + 1))
        rejects(data, "PNG_DIMENSIONS", expected.copy(width = 2))
        rejects(data, "PNG_DIMENSIONS", expected.copy(height = 2))
        rejects(data, "PNG_MEDIA_TYPE", expected.copy(mediaType = "image/svg+xml"))
        rejects(byteArrayOf(), "PNG_BYTE_LENGTH", expected)
    }

    @Test
    fun metadataAndChunkBudgetsAreInclusive() {
        val metadata = "a\u0000".toByteArray() + ByteArray(262142) { 'x'.code.toByte() }
        val four = ByteArrayOutputStream().also { output -> repeat(4) { output.write(chunk("tEXt", metadata)) } }.toByteArray()
        accepts(png(after = four))
        rejects(png(after = four + chunk("tEXt", byteArrayOf(97, 0))), "PNG_METADATA_LIMIT")
        rejects(png(after = chunk("tEXt", metadata + 120.toByte())), "PNG_METADATA_LIMIT")
        accepts(png(after = chunk("zTXt", byteArrayOf(97, 0, 0) + compress(ByteArray(262144) { 120 }))))
        val chunks = ByteArrayOutputStream().also { output -> repeat(4093) { output.write(chunk("tEXt", byteArrayOf(97, 0))) } }.toByteArray()
        accepts(png(after = chunks))
        rejects(png(after = chunks + chunk("tEXt", byteArrayOf(97, 0))), "PNG_CHUNK_LIMIT")
    }

    @Test
    fun exactByteBudgetRejectsOneMoreByte() {
        val emptyBlock = byteArrayOf(0, 0, 0, 255.toByte(), 255.toByte())
        val pixels = byteArrayOf(0, 255.toByte(), 0, 0, 255.toByte())
        val checksum = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { it.writeInt(Adler32().apply { update(pixels) }.value.toInt()) }
        }.toByteArray()
        val suffix = byteArrayOf(1, 5, 0, 250.toByte(), 255.toByte()) + pixels + checksum
        val overhead = png(compressed = byteArrayOf(120, 1) + suffix, after = chunk("tEXt", byteArrayOf(97, 0))).size
        val count = (2097152 - overhead) / emptyBlock.size
        val padding = (2097152 - overhead) % emptyBlock.size
        val compressed = ByteArrayOutputStream().also { output ->
            output.write(byteArrayOf(120, 1))
            repeat(count) { output.write(emptyBlock) }
            output.write(suffix)
        }.toByteArray()
        val data = png(compressed = compressed, after = chunk("tEXt", byteArrayOf(97, 0) + ByteArray(padding) { 120 }))
        assertEquals(2097152, data.size)
        accepts(data)
        rejects(data + 120.toByte(), "PNG_BYTE_LENGTH", blob(data))
    }
}
