package com.dayforge.data.appearance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import com.dayforge.domain.appearance.parseSvgNumbers
import com.dayforge.domain.appearance.SvgValidationException
import com.dayforge.domain.appearance.svgRequire
import com.dayforge.domain.model.IconBlob
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * Render only our fully validated static scene, never arbitrary SVG markup.
 * Native-size sRGB bitmap; caller owns recycling. Null tint preserves original
 * colors; a tint replaces RGB after composing alpha, including tint transparency.
 * No I/O, cache, installation or ready-state mutation. Call off the UI thread.
 */
fun renderSvg(source: ByteArray, expected: IconBlob, tint: Int? = null): Bitmap {
    val document = parseSvgDocument(source, expected)
    val bitmap = createBitmap(document.inspection.width, document.inspection.height,
        Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.SRGB))
    bitmap.density = Bitmap.DENSITY_NONE
    var completed = false
    try {
        val canvas = Canvas(bitmap)
        drawSvgNode(canvas, document.root)
        if (tint != null) canvas.drawColor(tint, PorterDuff.Mode.SRC_IN)
        completed = true
        return bitmap
    } finally {
        if (!completed) bitmap.recycle()
    }
}

/** Caller owns stream closing, I/O dispatch and deadlines, as for the PNG adapter. */
fun renderSvg(source: InputStream, expected: IconBlob, tint: Int? = null): Bitmap {
    svgRequire(expected.mediaType == "image/svg+xml", "SVG_MEDIA_TYPE")
    return renderSvg(readIconBytes(source, expected), expected, tint)
}

private fun drawSvgNode(canvas: Canvas, node: SvgNode) {
    val opacity = node.attrs["opacity"]?.let { parseSvgNumbers(it).single() } ?: 1.0
    if (opacity == 0.0) return
    val checkpoint = canvas.save()
    try {
        val v = node.matrix.map(::svgNativeNumber)
        canvas.concat(Matrix().apply { setValues(floatArrayOf(v[0], v[2], v[4], v[1], v[3], v[5], 0f, 0f, 1f)) })
        // Opacity is NOT inherited. Composite all children / fill and stroke once.
        // Null bounds use the existing bitmap clip; no untrusted geometry allocation.
        if (opacity < 1.0) canvas.saveLayerAlpha(null, (opacity * 255).roundToInt())
        if (node.tag == "svg" || node.tag == "g") {
            node.children.forEach { drawSvgNode(canvas, it) }
        } else {
            val path = svgPath(node)
            path.fillType = if (node.style["fill-rule"] == "evenodd") Path.FillType.EVEN_ODD else Path.FillType.WINDING
            val fill = node.style["fill"] ?: "#000000"
            if (fill != "none" && node.tag != "line") {
                canvas.drawPath(path, paint(node, fill, "fill-opacity").apply { style = Paint.Style.FILL })
            }
            val stroke = node.style["stroke"] ?: "none"
            val width = parseSvgNumbers(node.style["stroke-width"] ?: "1").single()
            if (stroke != "none" && width > 0) {
                canvas.drawPath(path, paint(node, stroke, "stroke-opacity").apply {
                    style = Paint.Style.STROKE
                    strokeWidth = svgNativeNumber(width)
                    strokeCap = when (node.style["stroke-linecap"]) {
                        "round" -> Paint.Cap.ROUND; "square" -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT
                    }
                    strokeJoin = when (node.style["stroke-linejoin"]) {
                        "round" -> Paint.Join.ROUND; "bevel" -> Paint.Join.BEVEL; else -> Paint.Join.MITER
                    }
                    strokeMiter = svgNativeNumber(parseSvgNumbers(node.style["stroke-miterlimit"] ?: "4").single())
                    val dash = node.style["stroke-dasharray"] ?: "none"
                    if (dash != "none") {
                        var intervals = parseSvgNumbers(dash).map(::svgNativeNumber)
                        if (intervals.size % 2 != 0) intervals = intervals + intervals
                        val phase = svgNativeNumber(parseSvgNumbers(node.style["stroke-dashoffset"] ?: "0").single())
                        pathEffect = DashPathEffect(intervals.toFloatArray(), phase)
                    }
                })
            }
        }
    } finally { canvas.restoreToCount(checkpoint) }
}

private fun paint(node: SvgNode, value: String, opacityKey: String): Paint {
    val hex = if (value.length == 4) "#" + value.drop(1).flatMap { listOf(it, it) }.joinToString("") else value
    return Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hex.toColorInt()
        alpha = ((node.style[opacityKey]?.let { parseSvgNumbers(it).single() } ?: 1.0) * 255).roundToInt()
    }
}

private fun svgPath(node: SvgNode): Path {
    fun number(key: String, default: Double = 0.0) = node.attrs[key]?.let { parseSvgNumbers(it).single() } ?: default
    val path = Path()
    when (node.tag) {
        "path" -> requireNotNull(node.geometry).commands.forEach { command ->
            val v = command.values.map { it.toFloat() }
            when (command.command) {
                'M' -> path.moveTo(v[0], v[1]); 'L' -> path.lineTo(v[0], v[1])
                'C' -> path.cubicTo(v[0], v[1], v[2], v[3], v[4], v[5])
                'Q' -> path.quadTo(v[0], v[1], v[2], v[3]); 'Z' -> path.close()
                else -> throw SvgValidationException("SVG_PATH_SYNTAX")
            }
        }
        "rect" -> {
            val x = number("x"); val y = number("y"); val w = number("width"); val h = number("height")
            if (w > 0 && h > 0) {
                val rx = minOf(number("rx", number("ry")), w/2)
                val ry = minOf(number("ry", number("rx")), h/2)
                path.addRoundRect(RectF(x.toFloat(), y.toFloat(), (x+w).toFloat(), (y+h).toFloat()), rx.toFloat(), ry.toFloat(), Path.Direction.CW)
            }
        }
        "circle", "ellipse" -> {
            val x = number("cx"); val y = number("cy")
            val rx = if (node.tag == "circle") number("r") else number("rx")
            val ry = if (node.tag == "circle") rx else number("ry")
            if (rx > 0 && ry > 0) path.addOval(RectF((x-rx).toFloat(), (y-ry).toFloat(), (x+rx).toFloat(), (y+ry).toFloat()), Path.Direction.CW)
        }
        "line" -> { path.moveTo(number("x1").toFloat(), number("y1").toFloat()); path.lineTo(number("x2").toFloat(), number("y2").toFloat()) }
        "polyline", "polygon" -> {
            val points = parseSvgNumbers(node.attrs.getValue("points"), points = true)
            path.moveTo(points[0].toFloat(), points[1].toFloat())
            for (i in 2 until points.size step 2) path.lineTo(points[i].toFloat(), points[i+1].toFloat())
            if (node.tag == "polygon") path.close()
        }
        else -> throw SvgValidationException("SVG_ELEMENT")
    }
    return path
}
