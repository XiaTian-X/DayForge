package com.dayforge.data.appearance

import android.util.Xml
import com.dayforge.domain.appearance.SVG_MAX_COMMANDS
import com.dayforge.domain.appearance.SVG_MAX_NUMBER
import com.dayforge.domain.appearance.SVG_MAX_TEXT
import com.dayforge.domain.appearance.SvgValidationException
import com.dayforge.domain.appearance.parseSvgNumbers
import com.dayforge.domain.appearance.parseSvgPath
import com.dayforge.domain.appearance.svgRequire
import com.dayforge.domain.model.IconBlob
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

data class SvgInspection(val width: Int, val height: Int, val elements: Int, val commands: Int)

private const val SVG_NS = "http://www.w3.org/2000/svg"
private const val WSP = " \t\r\n"
private val identity = listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
private val geometry = mapOf(
    "svg" to setOf("width", "height", "viewBox", "version", "preserveAspectRatio"),
    "g" to emptySet(), "path" to setOf("d"), "rect" to setOf("x", "y", "width", "height", "rx", "ry"),
    "circle" to setOf("cx", "cy", "r"), "ellipse" to setOf("cx", "cy", "rx", "ry"),
    "line" to setOf("x1", "y1", "x2", "y2"), "polyline" to setOf("points"), "polygon" to setOf("points")
)
private val required = mapOf("svg" to setOf("width", "height"), "path" to setOf("d"),
    "rect" to setOf("width", "height"), "circle" to setOf("r"), "ellipse" to setOf("rx", "ry"),
    "polyline" to setOf("points"), "polygon" to setOf("points"))
private val common = setOf("id", "fill", "stroke", "fill-opacity", "stroke-opacity", "opacity", "fill-rule",
    "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-dasharray", "stroke-dashoffset", "transform")
private val enums = mapOf("fill-rule" to setOf("nonzero", "evenodd"), "stroke-linecap" to setOf("butt", "round", "square"),
    "stroke-linejoin" to setOf("miter", "round", "bevel"), "version" to setOf("1.1"),
    "preserveAspectRatio" to setOf("none", "xMidYMid meet"))
private val color = Regex("none|#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6}")
private val identifier = Regex("[A-Za-z_][A-Za-z0-9_.:-]{0,127}")
private val transformPattern = Regex("(matrix|translate|scale|rotate|skewX|skewY)[ \\t\\r\\n]*\\(([^()]*)\\)")
private val transformArity = mapOf("matrix" to setOf(6), "translate" to setOf(1, 2), "scale" to setOf(1, 2),
    "rotate" to setOf(1, 3), "skewX" to setOf(1), "skewY" to setOf(1))

private fun matrixProduct(left: List<Double>, right: List<Double>): List<Double> {
    val a = left[0]; val b = left[1]; val c = left[2]; val d = left[3]; val e = left[4]; val f = left[5]
    val g = right[0]; val h = right[1]; val i = right[2]; val j = right[3]; val k = right[4]; val m = right[5]
    val result = listOf(a*g+c*h, b*g+d*h, a*i+c*j, b*i+d*j, a*k+c*m+e, b*k+d*m+f)
    svgRequire(result.all { it.isFinite() && abs(it) <= SVG_MAX_NUMBER }, "SVG_TRANSFORM_LIMIT")
    return result
}

private fun transforms(value: String): Pair<List<Double>, Int> {
    var result = identity
    var position = 0
    var count = 0
    while (position < value.length) {
        val previous = position
        while (position < value.length && value[position] in WSP) position++
        if (position == value.length) break
        if (count > 0 && value[position] == ',') {
            position++
            while (position < value.length && value[position] in WSP) position++
        }
        svgRequire(count == 0 || position > previous, "SVG_TRANSFORM_SYNTAX")
        val found = transformPattern.find(value, position)
        svgRequire(found != null && found.range.first == position, "SVG_TRANSFORM_SYNTAX")
        val match = requireNotNull(found)
        val name = match.groupValues[1]
        val args = parseSvgNumbers(match.groupValues[2], maximum = 6)
        svgRequire(args.size in transformArity.getValue(name), "SVG_TRANSFORM_SYNTAX")
        count++
        svgRequire(count <= 64, "SVG_TRANSFORM_LIMIT")
        val matrix = when (name) {
            "matrix" -> args
            "translate" -> listOf(1.0, 0.0, 0.0, 1.0, args[0], args.getOrElse(1) { 0.0 })
            "scale" -> listOf(args[0], 0.0, 0.0, args.getOrElse(1) { args[0] }, 0.0, 0.0)
            "rotate" -> {
                val sine = sin(Math.toRadians(args[0])); val cosine = cos(Math.toRadians(args[0]))
                val x = args.getOrElse(1) { 0.0 }; val y = args.getOrElse(2) { 0.0 }
                listOf(cosine, sine, -sine, cosine, x-cosine*x+sine*y, y-sine*x-cosine*y)
            }
            else -> {
                val angle = Math.toRadians(args[0])
                svgRequire(abs(cos(angle)) > 0.000001, "SVG_TRANSFORM_LIMIT")
                val tangent = tan(angle)
                if (name == "skewX") listOf(1.0, 0.0, tangent, 1.0, 0.0, 0.0)
                else listOf(1.0, tangent, 0.0, 1.0, 0.0, 0.0)
            }
        }
        result = matrixProduct(result, matrix)
        position = match.range.last + 1
    }
    return result to count
}

private data class AttributeInspection(val commands: Int, val matrix: List<Double>, val transforms: Int)

private fun attributes(tag: String, attrs: Map<String, String>): AttributeInspection {
    svgRequire(attrs.keys.all { it in geometry.getValue(tag) || it in common } &&
        attrs.keys.containsAll(required[tag].orEmpty()), "SVG_ATTRIBUTES")
    var commands = 0
    var matrix = identity
    var transformCount = 0
    attrs.forEach { (key, value) ->
        when {
            key == "id" -> svgRequire(identifier.matches(value), "SVG_ATTRIBUTES")
            key in enums -> svgRequire(value in enums.getValue(key), "SVG_ATTRIBUTES")
            key == "fill" || key == "stroke" -> svgRequire(color.matches(value), "SVG_ATTRIBUTES")
            key == "d" -> commands += parseSvgPath(value).size
            key == "transform" -> transforms(value).let { matrix = it.first; transformCount = it.second }
            key == "stroke-dasharray" && value == "none" -> Unit
            else -> {
                val values = parseSvgNumbers(if (tag == "svg" && key in setOf("width", "height")) value.removeSuffix("px") else value,
                    points = key == "points")
                when (key) {
                    "viewBox" -> svgRequire(values.size == 4 && values[2] > 0 && values[3] > 0, "SVG_GEOMETRY")
                    "points" -> {
                        svgRequire(values.size >= (if (tag == "polygon") 6 else 4) && values.size % 2 == 0, "SVG_GEOMETRY")
                        commands += values.size / 2
                    }
                    "stroke-dasharray" -> svgRequire(values.size in 1..64 && values.min() >= 0 && values.max() > 0, "SVG_GEOMETRY")
                    else -> {
                        svgRequire(values.size == 1, "SVG_GEOMETRY")
                        val number = values[0]
                        if (key in setOf("width", "height", "rx", "ry", "r", "stroke-width")) svgRequire(number >= 0, "SVG_GEOMETRY")
                        if (key.endsWith("opacity")) svgRequire(number in 0.0..1.0, "SVG_GEOMETRY")
                        if (key == "stroke-miterlimit") svgRequire(number >= 1, "SVG_GEOMETRY")
                    }
                }
            }
        }
    }
    return AttributeInspection(commands, matrix, transformCount)
}

/** Verifies immutable input bytes and the complete static profile. Does not render/install/upload. */
fun inspectSvg(source: ByteArray, expected: IconBlob): SvgInspection {
    svgRequire(expected.mediaType == "image/svg+xml", "SVG_MEDIA_TYPE")
    svgRequire(source.size <= SVG_MAX_TEXT && source.size == expected.byteLength, "SVG_BYTE_LENGTH")
    val data = source.copyOf()
    val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    svgRequire(digest == expected.sha256, "SVG_HASH")
    val decoded = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString().removePrefix("\uFEFF")
    } catch (_: CharacterCodingException) { throw SvgValidationException("SVG_ENCODING") }
    svgRequire("<!DOCTYPE" !in decoded && "<!ENTITY" !in decoded, "SVG_XML_FORBIDDEN")
    if (decoded.startsWith("<?xml") && decoded.getOrNull(5)?.let { it in WSP } == true) {
        val declaration = decoded.substringBefore("?>")
        val encoding = Regex("encoding[ \\t\\r\\n]*=[ \\t\\r\\n]*['\"]([^'\"]+)['\"]").find(declaration)?.groupValues?.get(1)
        val version = Regex("version[ \\t\\r\\n]*=[ \\t\\r\\n]*['\"]([^'\"]+)['\"]").find(declaration)?.groupValues?.get(1)
        svgRequire(version == "1.0", "SVG_ENCODING")
        svgRequire(encoding == null || encoding.uppercase() == "UTF-8", "SVG_ENCODING")
    }
    var elements = 0
    var commands = 0
    var transformCount = 0
    var width = 0
    var height = 0
    val stack = mutableListOf<Pair<String, List<Double>>>()
    val handler = object : DefaultHandler() {
        override fun startPrefixMapping(prefix: String?, uri: String?) {
            svgRequire(uri.orEmpty() in setOf("", SVG_NS), "SVG_NAMESPACE")
        }

        override fun startElement(uri: String, tag: String, qName: String, raw: Attributes) {
            svgRequire(uri in setOf("", SVG_NS) && tag in geometry, "SVG_ELEMENT")
            svgRequire((stack.isEmpty() && elements == 0 && tag == "svg") ||
                (stack.isNotEmpty() && stack.last().first in setOf("svg", "g") && tag != "svg"), "SVG_STRUCTURE")
            elements++
            svgRequire(elements <= 2048 && stack.size < 16, "SVG_TREE_LIMIT")
            val attrs = mutableMapOf<String, String>()
            for (index in 0 until raw.length) {
                svgRequire(raw.getURI(index).isNullOrEmpty(), "SVG_ATTRIBUTES")
                val name = raw.getLocalName(index)
                svgRequire(name !in attrs, "SVG_ATTRIBUTES")
                attrs[name] = raw.getValue(index)
            }
            val inspected = attributes(tag, attrs)
            commands += inspected.commands
            transformCount += inspected.transforms
            svgRequire(commands <= SVG_MAX_COMMANDS, "SVG_COMMAND_LIMIT")
            svgRequire(transformCount <= 256, "SVG_TRANSFORM_LIMIT")
            if (stack.isEmpty()) {
                val dimensions = listOf("width", "height").map { parseSvgNumbers(attrs.getValue(it).removeSuffix("px")).single() }
                svgRequire(dimensions.all { it % 1.0 == 0.0 && it in 1.0..1024.0 }, "SVG_DIMENSIONS")
                width = dimensions[0].toInt()
                height = dimensions[1].toInt()
                svgRequire(width == expected.width && height == expected.height, "SVG_DIMENSIONS")
            }
            stack.add(tag to matrixProduct(stack.lastOrNull()?.second ?: identity, inspected.matrix))
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            svgRequire(stack.isNotEmpty(), "SVG_STRUCTURE")
            stack.removeAt(stack.lastIndex)
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            svgRequire((start until start + length).all { chars[it] in WSP }, "SVG_TEXT")
        }

        override fun processingInstruction(target: String?, data: String?) {
            throw SvgValidationException("SVG_XML_FORBIDDEN")
        }

        override fun skippedEntity(name: String?) {
            throw SvgValidationException("SVG_XML_FORBIDDEN")
        }
    }
    try {
        // Android's native SAX parser rejects malformed XML that PullParser
        // tolerates, including control characters inside otherwise ignored comments.
        // No DTD can reach it: strict UTF-8 and declaration rejection happen first.
        Xml.parse(decoded, handler)
    } catch (_: SAXException) {
        throw SvgValidationException("SVG_XML_SYNTAX")
    }
    svgRequire(elements > 0 && stack.isEmpty(), "SVG_STRUCTURE")
    return SvgInspection(width, height, elements, commands)
}

/** Reads once with bounds before XML parsing. Caller still owns the stream. */
fun inspectSvg(source: InputStream, expected: IconBlob): SvgInspection {
    svgRequire(expected.mediaType == "image/svg+xml", "SVG_MEDIA_TYPE")
    return inspectSvg(readIconBytes(source, expected), expected)
}
