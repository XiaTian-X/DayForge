package com.dayforge.domain.appearance

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal const val SVG_MAX_COORDINATE = 1_000_000_000_000.0
internal const val SVG_MAX_EXPANDED_COMMANDS = SVG_MAX_COMMANDS * 8

/** Absolute M/L/C/Q/Z geometry; no platform drawing or partial successful parse. */
internal data class SvgGeometry(
    val commands: List<SvgCommand>, val lengthBound: Double, val contours: Int, val sourceCommands: Int
)

private fun coordinate(value: Double): Double {
    svgRequire(value.isFinite() && abs(value) <= SVG_MAX_COORDINATE, "SVG_GEOMETRY_LIMIT")
    return value
}

private fun norm(x: Double, y: Double): Double {
    val scale = max(abs(x), abs(y))
    if (scale == 0.0) return 0.0
    val a = x / scale; val b = y / scale
    return scale * sqrt(a * a + b * b)
}

/** Avoid overflow/underflow in a*b/c before checking the actual derived coordinate. */
private fun ratioProduct(value: Double, numerator: Double, denominator: Double): Double {
    if (value == 0.0) return 0.0
    val a = Math.getExponent(value); val b = Math.getExponent(numerator); val c = Math.getExponent(denominator)
    val mantissa = Math.scalb(value, -a) * Math.scalb(numerator, -b) / Math.scalb(denominator, -c)
    return coordinate(Math.scalb(mantissa, a + b - c))
}

/** SVG endpoint arc conversion, with corrected radii and at most eight cubic pieces. */
private fun arc(x1: Double, y1: Double, values: List<Double>, x2: Double, y2: Double): List<SvgCommand> {
    if (x1 == x2 && y1 == y2) return emptyList()
    var rx = values[0]; var ry = values[1]
    if (rx == 0.0 || ry == 0.0) return listOf(SvgCommand('L', listOf(x2, y2)))
    val rotation = Math.toRadians(values[2] % 360.0)
    val cosine = cos(rotation); val sine = sin(rotation)
    val dx = (x1 - x2) / 2; val dy = (y1 - y2) / 2
    val xp = cosine * dx + sine * dy
    val yp = -sine * dx + cosine * dy
    val correctedRx = coordinate(norm(xp, ratioProduct(yp, rx, ry)))
    val correctedRy = coordinate(norm(yp, ratioProduct(xp, ry, rx)))
    if (correctedRx > rx || correctedRy > ry) { rx = correctedRx; ry = correctedRy }
    svgRequire(rx > 0 && ry > 0, "SVG_GEOMETRY_LIMIT")
    val nx = xp / rx; val ny = yp / ry
    val distance = norm(nx, ny)
    svgRequire(distance > 0 && distance.isFinite(), "SVG_GEOMETRY_LIMIT")
    val sign = if (values[3] == values[4]) -1.0 else 1.0
    val factor = sign * sqrt(max(0.0, 1 - distance * distance))
    val cxp = rx * (ny / distance) * factor
    val cyp = -ry * (nx / distance) * factor
    val cx = coordinate(cosine * cxp - sine * cyp + (x1 + x2) / 2)
    val cy = coordinate(sine * cxp + cosine * cyp + (y1 + y2) / 2)
    val ux = (xp - cxp) / rx; val uy = (yp - cyp) / ry
    val vx = (-xp - cxp) / rx; val vy = (-yp - cyp) / ry
    val start = atan2(uy, ux)
    var sweep = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
    if (values[4] == 0.0 && sweep > 0) sweep -= 2 * Math.PI
    if (values[4] == 1.0 && sweep < 0) sweep += 2 * Math.PI
    svgRequire(sweep.isFinite(), "SVG_GEOMETRY_LIMIT")
    val count = maxOf(1, ceil(abs(sweep) / (Math.PI / 4)).toInt())
    svgRequire(count <= 8, "SVG_GEOMETRY_LIMIT")
    val step = sweep / count
    fun point(angle: Double): Pair<Double, Double> =
        coordinate(cx + rx * cosine * cos(angle) - ry * sine * sin(angle)) to
            coordinate(cy + rx * sine * cos(angle) + ry * cosine * sin(angle))
    fun derivative(angle: Double): Pair<Double, Double> =
        (-rx * cosine * sin(angle) - ry * sine * cos(angle)) to
            (-rx * sine * sin(angle) + ry * cosine * cos(angle))
    return List(count) { index ->
        val a = start + index * step; val b = a + step
        val p = if (index == 0) x1 to y1 else point(a)
        val q = if (index == count - 1) x2 to y2 else point(b)
        val u = derivative(a); val v = derivative(b)
        val alpha = 4.0 / 3.0 * tan(step / 4)
        SvgCommand('C', listOf(p.first + alpha * u.first, p.second + alpha * u.second,
            q.first - alpha * v.first, q.second - alpha * v.second, q.first, q.second).map(::coordinate))
    }
}

internal fun resolveSvgPath(value: String): SvgGeometry {
    val result = mutableListOf<SvgCommand>()
    var x = 0.0; var y = 0.0; var sx = 0.0; var sy = 0.0
    var previous = ' '
    var cubic: Pair<Double, Double>? = null
    var quadratic: Pair<Double, Double>? = null
    var length = 0.0
    var contours = 0
    fun append(command: Char, values: List<Double>) {
        values.forEach(::coordinate)
        svgRequire(result.size < SVG_MAX_EXPANDED_COMMANDS, "SVG_GEOMETRY_LIMIT")
        if (command == 'Z') length += norm(x - sx, y - sy)
        else if (command != 'M') {
            var px = x; var py = y
            values.chunked(2).forEach { pair ->
                length += norm(pair[0] - px, pair[1] - py)
                px = pair[0]; py = pair[1]
            }
        }
        result.add(SvgCommand(command, values))
        if (command == 'Z') { x = sx; y = sy }
        else { x = values[values.size - 2]; y = values.last() }
        if (command == 'M') { sx = x; sy = y; contours++ }
    }
    val tokens = parseSvgPath(value)
    tokens.forEach { token ->
        val code = token.command.uppercaseChar()
        val values = token.values
        val relative = token.command.isLowerCase()
        fun point(index: Int) = coordinate(values[index] + if (relative) x else 0.0) to
            coordinate(values[index + 1] + if (relative) y else 0.0)
        var nextCubic: Pair<Double, Double>? = null
        var nextQuadratic: Pair<Double, Double>? = null
        when (code) {
            'Z' -> append('Z', emptyList())
            'M', 'L' -> point(0).let { append(code, listOf(it.first, it.second)) }
            'H' -> append('L', listOf(values[0] + if (relative) x else 0.0, y))
            'V' -> append('L', listOf(x, values[0] + if (relative) y else 0.0))
            'C' -> {
                val a = point(0); val b = point(2); val end = point(4)
                nextCubic = b
                append('C', listOf(a.first, a.second, b.first, b.second, end.first, end.second))
            }
            'S' -> {
                val control = cubic
                val a = if (previous in "CS" && control != null) (2*x-control.first) to (2*y-control.second) else x to y
                val b = point(0); val end = point(2)
                nextCubic = b
                append('C', listOf(a.first, a.second, b.first, b.second, end.first, end.second))
            }
            'Q' -> {
                val a = point(0); val end = point(2)
                nextQuadratic = a
                append('Q', listOf(a.first, a.second, end.first, end.second))
            }
            'T' -> {
                val control = quadratic
                val a = if (previous in "QT" && control != null) (2*x-control.first) to (2*y-control.second) else x to y
                val end = point(0)
                nextQuadratic = a
                append('Q', listOf(a.first, a.second, end.first, end.second))
            }
            'A' -> {
                val end = point(5)
                arc(x, y, values, end.first, end.second).forEach { append(it.command, it.values) }
                x = end.first; y = end.second
            }
            else -> throw SvgValidationException("SVG_PATH_SYNTAX")
        }
        previous = code
        cubic = nextCubic
        quadratic = nextQuadratic
    }
    return SvgGeometry(result.toList(), length, contours, tokens.size)
}
