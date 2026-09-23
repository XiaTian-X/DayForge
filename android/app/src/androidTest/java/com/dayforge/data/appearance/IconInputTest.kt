package com.dayforge.data.appearance

import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.model.IconBlob
import com.dayforge.domain.appearance.SvgValidationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class IconInputTest {
    private fun description(data: ByteArray, mediaType: String = "image/png") = IconBlob(
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) },
        data.size, mediaType, 1, 1
    )

    private class Fragmented(data: ByteArray, private val fragment: Int = 17) : ByteArrayInputStream(data) {
        var lastRequest = 0
        var wasClosed = false
        val consumed get() = pos
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(length in 1..65_536)
            lastRequest = length
            return super.read(buffer, offset, minOf(length, fragment))
        }
        override fun available(): Int = error("available() is not a trustworthy length")
        override fun close() { wasClosed = true; super.close() }
    }

    @Test
    fun exactBoundariesAndFragmentedReadsStillRequireEof() {
        listOf(1, 65_535, 65_536, 65_537, 524_288, 2_097_152).forEach { size ->
            val data = ByteArray(size) { 97 }
            val source = Fragmented(data, fragment = 3071)
            assertArrayEquals(data, readIconBytes(source, description(data)))
            assertEquals(size, source.consumed)
            assertEquals(1, source.lastRequest)
            assertFalse(source.wasClosed)
        }
    }

    @Test
    fun extraBytesAreRejectedWithoutConsumingRemainder() {
        val data = "expected".toByteArray()
        listOf(1, 65_536, 2_097_152).forEach { extra ->
            val source = Fragmented(data + ByteArray(extra))
            val error = assertThrows(ImageInputException::class.java) { readIconBytes(source, description(data)) }
            assertEquals("IMAGE_BYTE_LENGTH", error.code)
            assertEquals(data.size + 1, source.consumed)
            assertFalse(source.wasClosed)
        }
    }

    @Test
    fun truncationAndSameLengthCorruptionFail() {
        val expected = description("abcde".toByteArray())
        listOf("", "a", "abcd", "abcdf").forEach { value ->
            val source = Fragmented(value.toByteArray())
            val failure = assertThrows(ImageInputException::class.java) { readIconBytes(source, expected) }
            assertEquals(if (value.length == 5) "IMAGE_HASH" else "IMAGE_BYTE_LENGTH", failure.code)
            assertFalse(source.wasClosed)
        }
    }

    @Test
    fun zeroProgressHasSingleByteFallbackAndFailuresPropagate() {
        val data = "abc".toByteArray()
        val source = object : ByteArrayInputStream(data) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertArrayEquals(data, readIconBytes(source, description(data)))
        listOf(IOException("synthetic failure"), CancellationException("synthetic cancellation")).forEach { error ->
            val failing = object : InputStream() {
                override fun read(): Int = throw error
            }
            assertSame(error, assertThrows(error.javaClass) { readIconBytes(failing, description(data)) })
        }
        listOf(-2, 5).forEach { count ->
            val broken = object : InputStream() {
                override fun read() = -1
                override fun read(buffer: ByteArray, offset: Int, length: Int) = count
            }
            assertEquals("IMAGE_READ_INVALID", assertThrows(ImageInputException::class.java) {
                readIconBytes(broken, description(data))
            }.code)
        }
    }

    @Test
    fun returnedSvgBytesDoNotAliasMutableInput() {
        val original = "<svg width=\"1\" height=\"1\"/>".toByteArray()
        val data = original.copyOf()
        val result = readIconBytes(Fragmented(data, 1), description(data, "image/svg+xml"))
        data.fill(0)
        assertArrayEquals(original, result)
    }

    @Test
    fun svgStreamEntryUsesActualValidator() {
        val valid = "<svg width=\"1\" height=\"1\"/>".toByteArray()
        val source = Fragmented(valid, 1)
        assertEquals(SvgInspection(1, 1, 1, 0), inspectSvg(source, description(valid, "image/svg+xml")))
        assertFalse(source.wasClosed)
        val wrongType = Fragmented(valid)
        assertEquals("SVG_MEDIA_TYPE", assertThrows(SvgValidationException::class.java) {
            inspectSvg(wrongType, description(valid))
        }.code)
        assertEquals(0, wrongType.consumed)
        val invalid = "<svg width=\"1\" height=\"1\"><script/></svg>".toByteArray()
        assertEquals("SVG_ELEMENT", assertThrows(SvgValidationException::class.java) {
            inspectSvg(Fragmented(invalid), description(invalid, "image/svg+xml"))
        }.code)
    }

    @Test
    fun pngStreamEntryDecodesPixelsWithoutRereadingSource() {
        val cases = InstrumentationRegistry.getInstrumentation().context.assets.open("next/png.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        val case = cases.single { it.jsonObject.getValue("name").jsonPrimitive.content == "rgba" }.jsonObject
        val data = Base64.decode(case.getValue("png").jsonPrimitive.content, Base64.DEFAULT)
        val source = Fragmented(data, 1)
        val bitmap = decodePng(source, description(data))
        try {
            assertEquals(Color.RED, bitmap.getPixel(0, 0))
            assertEquals(data.size, source.consumed)
            assertFalse(source.wasClosed)
        } finally { bitmap.recycle() }
        val wrongType = Fragmented(data)
        assertEquals("PNG_MEDIA_TYPE", assertThrows(PngValidationException::class.java) {
            decodePng(wrongType, description(data, "image/svg+xml"))
        }.code)
        assertEquals(0, wrongType.consumed)
        val invalid = data.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        assertEquals("PNG_CRC", assertThrows(PngValidationException::class.java) {
            decodePng(Fragmented(invalid), description(invalid))
        }.code)
    }
}
