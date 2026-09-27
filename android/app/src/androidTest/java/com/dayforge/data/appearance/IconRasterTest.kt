package com.dayforge.data.appearance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.model.IconAsset
import com.dayforge.domain.model.IconBlob
import com.dayforge.ui.theme.toComposeImage
import com.dayforge.widget.base.toGlanceImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconRasterTest {
    private val red = 0xffff0000.toInt()
    private val blue = 0xff0000ff.toInt()
    private fun svg(width: Int = 4, height: Int = 2, color: String = "#ff0000", opacity: String = "1") =
        """<svg width="$width" height="$height"><rect width="$width" height="$height" fill="$color" opacity="$opacity"/></svg>""".toByteArray()
    private fun png(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            return ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
    private fun blob(bytes: ByteArray, width: Int = 4, height: Int = 2, png: Boolean = false) = IconBlob(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        bytes.size, if (png) "image/png" else "image/svg+xml", width, height)
    private fun asset(light: IconBlob, dark: IconBlob? = null, mode: String = "original") = IconAsset(
        "93000000-0000-4000-8000-000000000001", "test icon", "general", mode, light, dark)

    @Test fun pngAndSvgFitWithoutStretchingAndPublishedPixelsAreImmutableSrgb() {
        for (isPng in listOf(false, true)) {
            val bytes = if (isPng) png(4, 2, red) else svg()
            val descriptor = blob(bytes, png = isPng)
            val raster = renderIcon(bytes, asset(descriptor), false, IconRasterSize(16, 16), blue)
            assertEquals(16, raster.width); assertEquals(16, raster.height)
            assertEquals(Bitmap.Config.ARGB_8888, raster.bitmap.config)
            assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), raster.bitmap.colorSpace)
            assertEquals(Bitmap.DENSITY_NONE, raster.bitmap.density)
            assertFalse(raster.bitmap.isMutable); assertFalse(raster.bitmap.isRecycled)
            assertEquals(0, raster.bitmap.getPixel(8, 0)); assertEquals(0, raster.bitmap.getPixel(8, 15))
            assertEquals(red, raster.bitmap.getPixel(8, 8))
            assertEquals(red, raster.bitmap.getPixel(0, 8)); assertEquals(red, raster.bitmap.getPixel(15, 8))
            assertEquals(descriptor, raster.blob)
            assertThrows(IllegalStateException::class.java) { raster.bitmap.setPixel(8, 8, blue) }
        }
    }

    @Test fun templateUsesAlphaForBothFormatsWhileOriginalIgnoresTint() {
        for (isPng in listOf(false, true)) {
            val bytes = if (isPng) png(4, 2, 0x80ff0000.toInt()) else svg(opacity = "0.5")
            val description = blob(bytes, png = isPng)
            val tinted = renderIcon(bytes, asset(description, mode = "template"), false, IconRasterSize(4, 2), 0x800000ff.toInt())
            assertEquals(0x400000ff, tinted.bitmap.getPixel(2, 1))
            val original = renderIcon(bytes, asset(description), false, IconRasterSize(4, 2), 0)
            assertEquals(0x80ff0000.toInt(), original.bitmap.getPixel(2, 1))
            val invisible = renderIcon(bytes, asset(description, mode = "template"), false, IconRasterSize(4, 2), 0)
            assertEquals(0, invisible.bitmap.getPixel(2, 1))
        }
    }

    @Test fun darkSelectionIsExplicitAndFallbackNeverRecolorsOriginal() {
        val light = svg(); val dark = svg(color = "#0000ff")
        val lightBlob = blob(light); val darkBlob = blob(dark)
        val description = asset(lightBlob, darkBlob)
        assertEquals(lightBlob, selectedIconBlob(description, false))
        assertEquals(darkBlob, selectedIconBlob(description, true))
        assertEquals(blue, renderIcon(dark, description, true, IconRasterSize(4, 2), red).bitmap.getPixel(2, 1))
        val fallback = asset(lightBlob)
        assertEquals(lightBlob, selectedIconBlob(fallback, true))
        assertEquals(red, renderIcon(light, fallback, true, IconRasterSize(4, 2), blue).bitmap.getPixel(2, 1))
        assertThrows(IllegalArgumentException::class.java) { renderIcon(light, description, true, IconRasterSize(4, 2), red) }
    }

    @Test fun pngSamplingIsBoundedAndPreservesOddAndThinImageAspect() {
        for ((width, height) in listOf(1024 to 1024, 1023 to 511, 1 to 1024, 1024 to 1)) {
            val bytes = png(width, height, red); val description = blob(bytes, width, height, true)
            val sampled = decodePngForDisplay(bytes, description, IconRasterSize(16, 16))
            try {
                assertTrue(sampled.width in 1..32); assertTrue(sampled.height in 1..32)
                assertTrue(sampled.byteCount <= 32 * 32 * 4)
            } finally { sampled.recycle() }
            val native = decodePng(bytes, description)
            try {
                assertEquals(width, native.width); assertEquals(height, native.height)
                assertEquals(red, native.getPixel(0, 0))
            } finally { native.recycle() }
            val output = renderIcon(bytes, asset(description), false, IconRasterSize(16, 16), blue)
            assertEquals(16 * 16 * 4, output.bitmap.byteCount)
            if (width > height * 2) assertEquals(0, output.bitmap.getPixel(8, 0))
            if (height > width * 2) assertEquals(0, output.bitmap.getPixel(0, 8))
        }
    }

    @Test fun svgRendersAtDisplayResolutionAndClipsSourceViewportBeforeLetterboxing() {
        val fine = """<svg width="1" height="1"><rect x="0.5" width="0.5" height="1" fill="#ff0000"/></svg>""".toByteArray()
        val output = renderIcon(fine, asset(blob(fine, 1, 1)), false, IconRasterSize(100, 100), blue)
        assertEquals(0, output.bitmap.getPixel(25, 50)); assertEquals(red, output.bitmap.getPixel(75, 50))
        val outside = """<svg width="4" height="2"><rect y="-20" width="4" height="40" fill="#ff0000"/></svg>""".toByteArray()
        val clipped = renderIcon(outside, asset(blob(outside)), false, IconRasterSize(16, 16), blue)
        assertEquals(0, clipped.bitmap.getPixel(8, 0)); assertEquals(0, clipped.bitmap.getPixel(8, 15))
        assertEquals(red, clipped.bitmap.getPixel(8, 8))
    }

    @Test fun boundariesInvalidBytesAndIndependentOutputsNeverFallbackOrMutateSource() {
        for ((w, h) in listOf(0 to 1, 1 to 0, -1 to 1, 1025 to 1, 1 to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { IconRasterSize(w, h) }
        }
        val source = svg(width = 1, height = 1); val saved = source.copyOf()
        val description = asset(blob(source, 1, 1), mode = "template")
        val first = renderIcon(source, description, false, IconRasterSize(1, 1), red)
        val second = renderIcon(source, description, false, IconRasterSize(1024, 1024), blue)
        assertNotSame(first.bitmap, second.bitmap)
        assertEquals(red, first.bitmap.getPixel(0, 0)); assertEquals(blue, second.bitmap.getPixel(512, 512))
        assertEquals(4 * 1024 * 1024, second.bitmap.byteCount); assertArrayEquals(saved, source)
        source[0] = 0
        assertThrows(IllegalArgumentException::class.java) { renderIcon(source, description, false, IconRasterSize(4, 2), red) }
        assertEquals(red, first.bitmap.getPixel(0, 0))
        val unsafe = """<svg width="4" height="2"><script/></svg>""".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { renderIcon(unsafe, asset(blob(unsafe)), false, IconRasterSize(4, 2), red) }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun actualComposeImageAndGlanceRemoteViewsRenderSamePixelsWithoutMainActivity() = runBlocking {
        val source = svg(width = 4, height = 4)
        val output = renderIcon(source, asset(blob(source, 4, 4), mode = "template"), false, IconRasterSize(32, 32), blue)
        val pixels = output.toComposeImage().toPixelMap()
        assertEquals(blue, pixels[16, 16].toArgb())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val views = GlanceRemoteViews().compose(context, DpSize(32.dp, 32.dp)) {
            Image(output.toGlanceImage(), "shared test icon", GlanceModifier.size(32.dp))
        }.remoteViews
        instrumentation.runOnMainSync {
            val root = views.apply(context, FrameLayout(context))
            val px = (32 * context.resources.displayMetrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, px, px)
            val screenshot = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(screenshot))
                assertEquals(blue, screenshot.getPixel(px / 2, px / 2))
            } finally { screenshot.recycle() }
        }
        assertFalse(output.bitmap.isRecycled)
        assertEquals(blue, output.toComposeImage().toPixelMap()[16, 16].toArgb())
    }
}
