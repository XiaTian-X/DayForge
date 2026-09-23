package com.dayforge.data.appearance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.appearance.SvgCommand
import com.dayforge.domain.appearance.SvgValidationException
import com.dayforge.domain.appearance.parseSvgNumbers
import com.dayforge.domain.appearance.parseSvgPath
import com.dayforge.domain.model.IconBlob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Native Android XML parser executes the same independently authored vectors as Python. */
@RunWith(AndroidJUnit4::class)
class SvgInspectorTest {
    private val fixture by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets.open("next/svg.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    }

    private fun blob(data: ByteArray) = IconBlob(
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) },
        data.size, "image/svg+xml", 24, 24
    )

    private fun svg(body: String = "", attrs: String = "") =
        "<svg width=\"24\" height=\"24\" $attrs>$body</svg>".toByteArray()

    private fun rejected(name: String, action: () -> Unit): SvgValidationException =
        assertThrows(name, SvgValidationException::class.java, action)

    @Test
    fun sharedPathsConsumeEveryTokenAndPreserveCommands() {
        val cases = fixture.getValue("paths").jsonArray
        assertEquals(28, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val value = case.getValue("value").jsonPrimitive.content
            if (case["invalid"]?.jsonPrimitive?.boolean == true) {
                rejected(name) { parseSvgPath(value) }
            } else {
                val expected = case.getValue("commands").jsonArray.map { item ->
                    val pair = item.jsonArray
                    SvgCommand(pair[0].jsonPrimitive.content.single(), pair[1].jsonArray.map { it.jsonPrimitive.double })
                }
                assertEquals(name, expected, parseSvgPath(value))
            }
        }
    }

    @Test
    fun sharedDocumentsValidateActualBytesAndEntireXml() {
        val cases = fixture.getValue("svg").jsonArray
        assertEquals(53, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val data = case.getValue("xml").jsonPrimitive.content.toByteArray()
            if (case["invalid"]?.jsonPrimitive?.boolean == true) {
                rejected(name) { inspectSvg(data, blob(data)) }
            } else {
                val result = inspectSvg(data, blob(data))
                assertEquals(name, SvgInspection(24, 24, case.getValue("elements").jsonPrimitive.int,
                    case.getValue("commands").jsonPrimitive.int), result)
            }
        }
    }

    @Test
    fun actualBytesBoundToDescription() {
        val data = svg()
        val expected = blob(data)
        listOf(
            expected.copy(mediaType = "image/png") to "SVG_MEDIA_TYPE",
            expected.copy(byteLength = data.size + 1) to "SVG_BYTE_LENGTH",
            expected.copy(sha256 = "0".repeat(64)) to "SVG_HASH",
            expected.copy(width = 25) to "SVG_DIMENSIONS",
            expected.copy(height = 25) to "SVG_DIMENSIONS"
        ).forEach { (description, code) ->
            assertEquals(code, rejected(code) { inspectSvg(data, description) }.code)
        }
        assertEquals("SVG_BYTE_LENGTH", rejected("empty") { inspectSvg(byteArrayOf(), expected) }.code)
    }

    @Test
    fun invalidEncodingsRejectedAndBomIncludedInHash() {
        listOf(byteArrayOf(0xff.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            svg().toString(Charsets.UTF_8).toByteArray(Charsets.UTF_16)).forEach { data ->
            rejected("encoding") { inspectSvg(data, blob(data)) }
        }
        val data = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + svg()
        assertEquals(1, inspectSvg(data, blob(data)).elements)
        rejected("BOM length") { inspectSvg(data, blob(svg())) }
    }

    @Test
    fun exactByteLimitAndOneMore() {
        val data = svg("<!--" + "a".repeat(524288 - svg("<!---->").size) + "-->")
        assertEquals(524288, data.size)
        assertEquals(1, inspectSvg(data, blob(data)).elements)
        assertEquals("SVG_BYTE_LENGTH", rejected("one extra byte") { inspectSvg(data + 32.toByte(), blob(data)) }.code)
    }

    @Test
    fun elementAndDepthBudgetsInclusive() {
        assertEquals(2048, inspectSvg(svg("<g/>".repeat(2047)), blob(svg("<g/>".repeat(2047)))).elements)
        val tooMany = svg("<g/>".repeat(2048))
        assertEquals("SVG_TREE_LIMIT", rejected("elements") { inspectSvg(tooMany, blob(tooMany)) }.code)
        val deepest = svg("<g>".repeat(15) + "</g>".repeat(15))
        assertEquals(16, inspectSvg(deepest, blob(deepest)).elements)
        val tooDeep = svg("<g>".repeat(16) + "</g>".repeat(16))
        assertEquals("SVG_TREE_LIMIT", rejected("depth") { inspectSvg(tooDeep, blob(tooDeep)) }.code)
    }

    @Test
    fun pathAndWholeDocumentCommandBudgetsInclusive() {
        fun path(count: Int) = "<path d=\"M0 0" + "L1 1".repeat(count - 1) + "\"/>"
        listOf(svg(path(16384)) to svg(path(16385)),
            svg(path(8192) + path(8192)) to svg(path(8192) + path(8193))).forEach { (accepted, invalid) ->
            assertEquals(16384, inspectSvg(accepted, blob(accepted)).commands)
            assertEquals("SVG_COMMAND_LIMIT", rejected("commands") { inspectSvg(invalid, blob(invalid)) }.code)
        }
    }

    @Test
    fun transformBudgetsInclusive() {
        fun group(count: Int) = "<g transform=\"" + "scale(1) ".repeat(count) + "\"/>"
        listOf(svg(group(64)) to svg(group(65)),
            svg(group(64).repeat(4)) to svg(group(64).repeat(4) + group(1))).forEach { (accepted, invalid) ->
            inspectSvg(accepted, blob(accepted))
            assertEquals("SVG_TRANSFORM_LIMIT", rejected("transforms") { inspectSvg(invalid, blob(invalid)) }.code)
        }
    }

    @Test
    fun numericBudgetsAndSeparators() {
        assertEquals(listOf(0.0), parseSvgNumbers("0".repeat(64)))
        assertEquals(listOf(1.0, -2.0, 3.0, -4.0), parseSvgNumbers("1-2 3-4", points = true))
        assertEquals(listOf(1.0, 2.0), parseSvgNumbers("1, 2\n"))
        listOf("0".repeat(65), "1,", "1 2, ", "1,,2", "1-2", "1.2.3", "1 nope").forEach { value ->
            rejected(value) { parseSvgNumbers(value) }
        }
        assertEquals(32768, parseSvgNumbers("0 ".repeat(32768)).size)
        assertEquals("SVG_NUMBER_COUNT", rejected("numbers") { parseSvgNumbers("0 ".repeat(32769)) }.code)
        assertEquals("SVG_TEXT_LIMIT", rejected("text") { parseSvgPath(" ".repeat(524289)) }.code)
    }

    @Test
    fun canvasBudgetsInclusive() {
        listOf("0", "-1", "1025", "24.5", "1em", "NaN").forEach { value ->
            val data = "<svg width=\"$value\" height=\"24\"/>".toByteArray()
            rejected(value) { inspectSvg(data, blob(data)) }
        }
        listOf(1, 1024).forEach { value ->
            val data = "<svg width=\"$value\" height=\"$value\"/>".toByteArray()
            val actual = inspectSvg(data, blob(data).copy(width = value, height = value))
            assertEquals(value, actual.width)
            assertEquals(value, actual.height)
        }
    }
}
