package com.dayforge.data.appearance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import com.dayforge.domain.model.IconAsset
import com.dayforge.domain.model.IconBlob

/** Physical pixels, not dp. The caller also limits concurrent renders and total widget image bytes. */
internal data class IconRasterSize(val width: Int, val height: Int) {
    init { require(width in 1..1024 && height in 1..1024) }
    fun fit(sourceWidth: Int, sourceHeight: Int): RectF {
        val scale = minOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val w = sourceWidth * scale
        val h = sourceHeight * scale
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }
}

/**
 * Immutable published pixels. Compose/Glance retain this bitmap; never recycle it after publication.
 * Release references normally when consumers/cache no longer need it. This is not an account cache.
 */
internal class IconRaster internal constructor(internal val bitmap: Bitmap, val blob: IconBlob) {
    init { require(!bitmap.isMutable && !bitmap.isRecycled) }
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
}

internal fun selectedIconBlob(asset: IconAsset, dark: Boolean): IconBlob =
    if (dark) asset.dark ?: asset.light else asset.light

/**
 * Blocking, off-main rendering of caller-authorized metadata and bytes. No file/network I/O,
 * ownership inference, ready mutation or silent missing/corrupt-image fallback.
 */
internal fun renderIcon(
    source: ByteArray, asset: IconAsset, dark: Boolean, size: IconRasterSize, templateTint: Int
): IconRaster {
    val blob = selectedIconBlob(asset, dark)
    val tint = templateTint.takeIf { asset.colorMode == "template" }
    val mutable = if (blob.mediaType == "image/svg+xml") {
        renderSvgAtSize(source, blob, size, tint)
    } else {
        val decoded = decodePngForDisplay(source, blob, size)
        var target: Bitmap? = null
        try {
            val output = createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888, true,
                ColorSpace.get(ColorSpace.Named.SRGB))
            target = output
            output.density = Bitmap.DENSITY_NONE
            Canvas(output).apply {
                drawBitmap(decoded, null, size.fit(blob.width, blob.height), Paint(Paint.FILTER_BITMAP_FLAG))
                if (tint != null) drawColor(tint, PorterDuff.Mode.SRC_IN)
            }
            output
        } catch (error: Throwable) {
            target?.recycle()
            throw error
        } finally { decoded.recycle() }
    }
    try {
        val frozen = requireNotNull(mutable.copy(Bitmap.Config.ARGB_8888, false))
        frozen.density = Bitmap.DENSITY_NONE
        return IconRaster(frozen, blob)
    } finally { mutable.recycle() }
}
