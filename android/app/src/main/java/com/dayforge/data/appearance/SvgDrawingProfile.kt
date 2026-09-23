package com.dayforge.data.appearance

import com.dayforge.domain.appearance.SvgGeometry
import com.dayforge.domain.appearance.parseSvgNumbers
import com.dayforge.domain.appearance.svgRequire
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

internal const val SVG_MAX_DASH_WORK = 65_536.0
internal val svgInherited = setOf("fill", "stroke", "fill-opacity", "stroke-opacity", "fill-rule",
    "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-dasharray", "stroke-dashoffset")

internal fun svgNativeNumber(value: Double): Float {
    val rounded = value.toFloat()
    svgRequire(rounded.isFinite() && (value == 0.0 || rounded != 0f), "SVG_DRAW_PRECISION")
    return rounded
}

internal fun svgViewport(attrs: Map<String, String>, width: Int, height: Int): List<Double> {
    val raw = attrs["viewBox"] ?: return listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    val values = parseSvgNumbers(raw)
    val x = values[0]; val y = values[1]; val w = values[2]; val h = values[3]
    var sx = width / w; var sy = height / h
    if (attrs["preserveAspectRatio"] != "none") { sx = minOf(sx, sy); sy = sx }
    return listOf(sx, 0.0, 0.0, sy, (width - w*sx)/2-x*sx, (height - h*sy)/2-y*sy)
}

internal fun svgStrokeWork(tag: String, attrs: Map<String, String>, style: Map<String, String>, geometry: SvgGeometry?): Double {
    fun number(key: String, default: Double = 0.0) = attrs[key]?.let { parseSvgNumbers(it).single() } ?: default
    val width = parseSvgNumbers(style["stroke-width"] ?: "1").single()
    svgNativeNumber(width)
    svgNativeNumber(parseSvgNumbers(style["stroke-miterlimit"] ?: "4").single())
    svgNativeNumber(parseSvgNumbers(style["stroke-dashoffset"] ?: "0").single())
    val raw = style["stroke-dasharray"] ?: "none"
    if (raw == "none") return 0.0
    var intervals = parseSvgNumbers(raw).map { svgNativeNumber(it).toDouble() }
    if (intervals.size % 2 != 0) intervals = intervals + intervals
    val period = minOf(intervals.sum(), intervals.fold(0f) { sum, value -> sum + value.toFloat() }.toDouble())
    if (style["stroke"] == null || style["stroke"] == "none" || width == 0.0 || tag in setOf("svg", "g")) return 0.0
    var contours = 1
    val length = when (tag) {
        "path" -> requireNotNull(geometry).let { contours = it.contours; it.lengthBound }
        "rect" -> 2 * (number("width") + number("height"))
        "circle" -> 2 * Math.PI * number("r")
        "ellipse" -> 2 * Math.PI * max(number("rx"), number("ry"))
        "line" -> hypot(number("x2") - number("x1"), number("y2") - number("y1"))
        else -> {
            val points = parseSvgNumbers(attrs.getValue("points"), points = true)
            var total = (2 until points.size step 2).sumOf { hypot(points[it]-points[it-2], points[it+1]-points[it-1]) }
            if (tag == "polygon") total += hypot(points[points.size-2]-points[0], points.last()-points[1])
            total
        }
    }
    val nativeLength = svgNativeLength(tag, attrs, geometry)
    val work = (ceil(max(length, nativeLength) / period) + contours) * intervals.size
    svgRequire(work.isFinite() && work <= SVG_MAX_DASH_WORK, "SVG_DASH_LIMIT")
    return work
}

/** Bound the actual float path, including quantization around rounding boundaries. */
private fun svgNativeLength(tag: String, attrs: Map<String, String>, geometry: SvgGeometry?): Double {
    fun number(key: String) = attrs[key]?.let { parseSvgNumbers(it).single() } ?: 0.0
    fun rounded(value: Double) = value.toFloat().toDouble()
    return when (tag) {
        "path" -> {
            var x = 0.0; var y = 0.0; var sx = 0.0; var sy = 0.0; var length = 0.0
            requireNotNull(geometry).commands.forEach { command ->
                val values = command.values.map(::rounded)
                if (command.command == 'Z') {
                    length += hypot(x-sx, y-sy); x = sx; y = sy
                } else {
                    if (command.command != 'M') {
                        for (i in values.indices step 2) {
                            length += hypot(values[i]-x, values[i+1]-y)
                            x = values[i]; y = values[i+1]
                        }
                    }
                    x = values[values.size-2]; y = values.last()
                    if (command.command == 'M') { sx = x; sy = y }
                }
            }
            length
        }
        "rect" -> 2 * (rounded(number("x")+number("width"))-rounded(number("x")) +
            rounded(number("y")+number("height"))-rounded(number("y")))
        "circle", "ellipse" -> {
            val rx = if (tag == "circle") number("r") else number("rx")
            val ry = if (tag == "circle") rx else number("ry")
            val width = rounded(number("cx")+rx)-rounded(number("cx")-rx)
            val height = rounded(number("cy")+ry)-rounded(number("cy")-ry)
            Math.PI * max(width, height)
        }
        "line" -> hypot(rounded(number("x2"))-rounded(number("x1")), rounded(number("y2"))-rounded(number("y1")))
        else -> {
            val points = parseSvgNumbers(attrs.getValue("points"), points = true).map(::rounded)
            var length = (2 until points.size step 2).sumOf { hypot(points[it]-points[it-2], points[it+1]-points[it-1]) }
            if (tag == "polygon") length += hypot(points[points.size-2]-points[0], points.last()-points[1])
            length
        }
    }
}
