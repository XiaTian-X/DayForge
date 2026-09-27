package com.dayforge.domain.appearance

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SvgNumberScannerTest {
    // Frozen full-input lexical reference: short adversarial inputs compare exact
    // values, rejection codes and cursor position, not merely whether parsing fails.
    private val legacyNumber = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private data class Outcome(val number: Double?, val error: String?, val position: Int)

    private fun legacy(value: String, start: Int, unsigned: Boolean): Outcome {
        return try {
            val found = legacyNumber.find(value, start)
            svgRequire(found != null && found.range.first == start, "SVG_NUMBER_SYNTAX")
            val token = requireNotNull(found).value
            svgRequire(!unsigned || token.first() !in "+-", "SVG_PATH_SYNTAX")
            svgRequire(token.length <= 64, "SVG_NUMBER_LIMIT")
            val number = token.toDoubleOrNull()
            svgRequire(number != null && number.isFinite() && kotlin.math.abs(number) <= SVG_MAX_NUMBER,
                "SVG_NUMBER_LIMIT")
            svgRequire(number != 0.0 || token.lowercase().substringBefore('e').none { it in '1'..'9' },
                "SVG_NUMBER_LIMIT")
            Outcome(number, null, found.range.last + 1)
        } catch (error: SvgValidationException) {
            Outcome(null, error.code, start)
        }
    }

    @Test
    fun boundedWindowPreservesFullTokenGrammarAtEveryLengthBoundary() {
        val sources = mutableListOf("", "x12", " 12", ".", "+", "-", "-0", "+.5", "1.",
            "1e", "1e+", "1e-2", "1e+2", "1e-999", "0e-999", "1000001", "0.0.1", "1-2")
        for (length in 60..70) {
            for (tail in listOf("", ".", ".0", "e", "e+", "e-", "e+0", "e-0", "e+0000", "e-0000", "e+x", "x9")) {
                sources += "0".repeat(length) + tail
                sources += "0." + "0".repeat(length) + "1" + tail
            }
        }
        sources += "0".repeat(120)
        for (source in sources) for (start in listOf(0, 2)) for (unsigned in listOf(false, true)) {
            val text = "x".repeat(start) + source
            val scanner = SvgNumberScanner(text).apply { position = start }
            val actual = try {
                val number = scanner.number(unsigned = unsigned)
                Outcome(number, null, scanner.position)
            } catch (error: SvgValidationException) {
                Outcome(null, error.code, scanner.position)
            }
            assertEquals("source=$source, start=$start, unsigned=$unsigned", legacy(text, start, unsigned), actual)
        }
    }

    @Test
    fun invalidPrefixNeverSearchesPastTheCursorAndFailureDoesNotAdvanceIt() {
        val scanner = SvgNumberScanner("x".repeat(SVG_MAX_TEXT - 1) + "1")
        assertEquals("SVG_NUMBER_SYNTAX", assertThrows(SvgValidationException::class.java) { scanner.number() }.code)
        assertEquals(0, scanner.position)
        scanner.position = SVG_MAX_TEXT - 1
        assertEquals(1.0, scanner.number(), 0.0)
        assertEquals(SVG_MAX_TEXT, scanner.position)
    }

    @Test
    fun independentScannersKeepSeparatorsFlagsAndOffsets() {
        val first = SvgNumberScanner("12, -3.5e+1 01 9")
        val second = SvgNumberScanner(".25 7")
        assertEquals(12.0, first.number(), 0.0)
        assertEquals(0.25, second.number(), 0.0)
        first.separator()
        assertEquals(-35.0, first.number(), 0.0)
        first.separator()
        assertEquals(0.0, first.number(flag = true), 0.0)
        assertEquals(1.0, first.number(flag = true), 0.0)
        second.separator()
        assertEquals(7.0, second.number(), 0.0)
        first.separator()
        assertEquals(9.0, first.number(), 0.0)
        assertEquals(first.value.length, first.position)
        assertEquals(second.value.length, second.position)
    }
}
