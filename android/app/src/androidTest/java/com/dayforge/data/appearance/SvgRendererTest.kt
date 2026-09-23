package com.dayforge.data.appearance

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.appearance.SvgValidationException
import com.dayforge.domain.model.IconBlob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.io.ByteArrayInputStream
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class SvgRendererTest {
    private fun svg(body: String = "", attrs: String = "") = "<svg width=\"24\" height=\"24\" $attrs>$body</svg>".toByteArray()
    private fun blob(data: ByteArray) = IconBlob(
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) },
        data.size, "image/svg+xml", 24, 24
    )

    @Test
    fun sharedScenesHaveRealPixelsAndFailClosedBeforeDrawing() {
        val cases = InstrumentationRegistry.getInstrumentation().context.assets.open("next/svg-drawing.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        assertEquals(40, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val data = svg(case["body"]?.jsonPrimitive?.content ?: "", case["attrs"]?.jsonPrimitive?.content ?: "")
            val error = case["error"]?.jsonPrimitive?.content
            if (error != null) {
                assertEquals(name, error, assertThrows(SvgValidationException::class.java) { renderSvg(data, blob(data)) }.code)
            } else {
                val bitmap = renderSvg(data, blob(data))
                try {
                    assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
                    assertEquals(Bitmap.DENSITY_NONE, bitmap.density)
                    assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), bitmap.colorSpace)
                    case.getValue("pixels").jsonArray.forEach { value ->
                        val pixel = value.jsonArray.map { it.jsonPrimitive.int }
                        val actual = bitmap.getPixel(pixel[0], pixel[1])
                        assertEquals("$name alpha at ${pixel.take(2)}", pixel[5], Color.alpha(actual))
                        if (pixel[5] > 0) {
                            listOf(Color.red(actual), Color.green(actual), Color.blue(actual)).zip(pixel.slice(2..4)).forEach { (a, b) ->
                                assertTrue("$name channel $a != $b", abs(a-b) <= 1)
                            }
                        }
                    }
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test
    fun tintIsAppliedOnceAfterAlphaCompositionAndOriginalRemainsUntouched() {
        val data = svg("<g opacity=\".5\"><rect width=\"16\" height=\"24\" fill=\"#f00\"/><rect x=\"8\" width=\"16\" height=\"24\" fill=\"#00f\"/></g>")
        val original = renderSvg(data, blob(data))
        val tinted = renderSvg(data, blob(data), Color.argb(128, 0, 255, 0))
        val transparent = renderSvg(data, blob(data), Color.TRANSPARENT)
        try {
            assertEquals(Color.argb(128, 255, 0, 0), original.getPixel(4,12))
            assertEquals(Color.argb(128, 0, 0, 255), original.getPixel(12,12))
            listOf(4,12,20).forEach { x -> assertEquals(Color.argb(64, 0, 255, 0), tinted.getPixel(x,12)) }
            assertEquals(Color.TRANSPARENT, transparent.getPixel(12,12))
            tinted.eraseColor(Color.YELLOW)
            assertEquals(Color.argb(128, 0, 0, 255), original.getPixel(12,12))
        } finally { original.recycle(); tinted.recycle(); transparent.recycle() }
    }

    @Test
    fun documentDashBudgetAndViewportBoundsAreInclusive() {
        fun line(length: Int) = "<line x2=\"$length\" stroke=\"#000\" stroke-dasharray=\"1 1\"/>"
        val data = svg(line(65534))
        val rendered = renderSvg(data, blob(data))
        rendered.recycle()
        listOf(line(65535), line(32768).repeat(2)).forEach { body ->
            val invalid = svg(body)
            assertEquals("SVG_DASH_LIMIT", assertThrows(SvgValidationException::class.java) { renderSvg(invalid, blob(invalid)) }.code)
        }
        val boundary = svg(attrs = "viewBox=\"0 0 .000024 .000024\"")
        renderSvg(boundary, blob(boundary)).recycle()
        val tooLarge = svg("<g transform=\"scale(1.000001)\"/>", "viewBox=\"0 0 .000024 .000024\"")
        assertEquals("SVG_TRANSFORM_LIMIT", assertThrows(SvgValidationException::class.java) { renderSvg(tooLarge, blob(tooLarge)) }.code)
    }

    @Test
    fun rendererVerifiesByteIdentityAndClipsLayersToCanvas() {
        val data = svg("<rect width=\"24\" height=\"24\"/>")
        val wrongHash = blob(data).copy(sha256 = "0".repeat(64))
        assertEquals("SVG_HASH", assertThrows(SvgValidationException::class.java) { renderSvg(data, wrongHash) }.code)
        val large = svg("<g opacity=\".5\">".repeat(14) + "<rect x=\"-1000000\" y=\"-1000000\" width=\"1000000\" height=\"1000000\"/>" + "</g>".repeat(14))
        val bitmap = renderSvg(large, blob(large))
        try {
            assertEquals(24, bitmap.width)
            assertEquals(24, bitmap.height)
            assertEquals(24 * 24 * 4, bitmap.allocationByteCount)
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(12,12))
        } finally { bitmap.recycle() }
    }

    @Test
    fun boundedStreamRendersOnceAndPreservesCallerOwnership() {
        val data = svg("<rect width=\"24\" height=\"24\" fill=\"#f00\"/>")
        var closed = false
        val source = object : ByteArrayInputStream(data) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(length, 1))
            override fun close() { closed = true; super.close() }
        }
        val result = renderSvg(source, blob(data))
        try {
            assertEquals(Color.RED, result.getPixel(12,12))
            assertEquals(-1, source.read())
            assertTrue(!closed)
        } finally { result.recycle(); source.close() }
        val tooLong = ByteArrayInputStream(data + byteArrayOf(32))
        assertEquals("IMAGE_BYTE_LENGTH", assertThrows(ImageInputException::class.java) { renderSvg(tooLong, blob(data)) }.code)
        val wrongType = ByteArrayInputStream(data)
        assertEquals("SVG_MEDIA_TYPE", assertThrows(SvgValidationException::class.java) {
            renderSvg(wrongType, blob(data).copy(mediaType = "image/png"))
        }.code)
        assertEquals(data.first().toInt(), wrongType.read())
    }

    @Test
    fun maximumCanvasAndLayerDepthRemainClippedAndDrawVisiblePixels() {
        val data = ("<svg width=\"1024\" height=\"1024\" opacity=\".99\">" +
            "<g opacity=\".99\">".repeat(14) +
            "<rect width=\"1024\" height=\"1024\" fill=\"#f00\" opacity=\".99\"/>" +
            "</g>".repeat(14) + "</svg>").toByteArray()
        val bitmap = renderSvg(data, blob(data).copy(width = 1024, height = 1024))
        try {
            assertEquals(1024 * 1024 * 4, bitmap.allocationByteCount)
            val pixel = bitmap.getPixel(512,512)
            assertEquals(255, Color.red(pixel))
            assertEquals(0, Color.green(pixel))
            assertEquals(0, Color.blue(pixel))
            // 16 applications of rounded .99 alpha; tolerate native integer compositing.
            assertTrue(Color.alpha(pixel) in 205..220)
        } finally { bitmap.recycle() }
    }

    @Test
    fun largeCorrectedArcDrawsInsideSmallViewport() {
        val data = svg("<path d=\"M0 0 A1000000 .000001 0 0 1 0 2 Z\" fill=\"#f00\"/>")
        val bitmap = renderSvg(data, blob(data))
        try {
            assertEquals(Color.RED, bitmap.getPixel(12,1))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(12,12))
        } finally { bitmap.recycle() }
    }

    @Test
    fun joinsMiterLimitAndRotationUseNativePaintAndMatrixSemantics() {
        listOf("miter", "round", "bevel").forEach { join ->
            val data = svg("<path d=\"M4 20 L4 4 L20 4\" fill=\"none\" stroke=\"#f00\" stroke-width=\"8\" stroke-linejoin=\"$join\"/>")
            val bitmap = renderSvg(data, blob(data))
            try {
                val alpha = Color.alpha(bitmap.getPixel(1,1))
                when (join) {
                    "miter" -> assertEquals(255, alpha)
                    "bevel" -> assertEquals(0, alpha)
                    else -> assertTrue(alpha > 0)
                }
                assertEquals(Color.RED, bitmap.getPixel(4,12))
            } finally { bitmap.recycle() }
        }
        val limited = svg("<path d=\"M4 20 L4 4 L20 4\" fill=\"none\" stroke=\"#f00\" stroke-width=\"8\" stroke-miterlimit=\"1\"/>")
        val bevel = renderSvg(limited, blob(limited))
        try { assertEquals(Color.TRANSPARENT, bevel.getPixel(1,1)) } finally { bevel.recycle() }
        val rotated = svg("<g transform=\"translate(12 12) rotate(90)\"><rect x=\"1\" y=\"1\" width=\"6\" height=\"4\" fill=\"#00f\"/></g>")
        val image = renderSvg(rotated, blob(rotated))
        try {
            assertEquals(Color.BLUE, image.getPixel(9,15))
            assertEquals(Color.TRANSPARENT, image.getPixel(15,9))
        } finally { image.recycle() }
    }
}
