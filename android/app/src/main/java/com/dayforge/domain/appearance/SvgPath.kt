package com.dayforge.domain.appearance

/** Static SVG syntax; not an XML parser, authorization check or image renderer. */
class SvgValidationException(val code: String) : IllegalArgumentException(code)

data class SvgCommand(val command: Char, val values: List<Double>)

internal const val SVG_MAX_NUMBER = 1_000_000.0
internal const val SVG_MAX_COMMANDS = 16_384
internal const val SVG_MAX_TEXT = 524_288
private const val WSP = " \t\r\n"
private val numberPattern = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
private val arity = mapOf('M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'C' to 6, 'S' to 4,
    'Q' to 4, 'T' to 2, 'A' to 7, 'Z' to 0)

internal fun svgRequire(value: Boolean, code: String) {
    if (!value) throw SvgValidationException(code)
}

internal class SvgNumberScanner(val value: String) {
    var position = 0
    init { svgRequire(value.length <= SVG_MAX_TEXT, "SVG_TEXT_LIMIT") }

    fun whitespace() {
        while (position < value.length && value[position] in WSP) position++
    }

    fun separator() {
        whitespace()
        if (position < value.length && value[position] == ',') {
            position++
            whitespace()
        }
    }

    fun number(flag: Boolean = false, unsigned: Boolean = false): Double {
        if (flag) {
            svgRequire(position < value.length && value[position] in "01", "SVG_PATH_SYNTAX")
            return (value[position++].code - '0'.code).toDouble()
        }
        val found = numberPattern.find(value, position)
        svgRequire(found != null && found.range.first == position, "SVG_NUMBER_SYNTAX")
        val token = requireNotNull(found).value
        svgRequire(!unsigned || token.first() !in "+-", "SVG_PATH_SYNTAX")
        svgRequire(token.length <= 64, "SVG_NUMBER_LIMIT")
        val number = token.toDoubleOrNull()
        svgRequire(number != null && number.isFinite() && kotlin.math.abs(number) <= SVG_MAX_NUMBER,
            "SVG_NUMBER_LIMIT")
        position = found.range.last + 1
        return requireNotNull(number)
    }
}

fun parseSvgNumbers(value: String, maximum: Int = SVG_MAX_COMMANDS * 2, points: Boolean = false): List<Double> {
    val scanner = SvgNumberScanner(value)
    scanner.whitespace()
    val result = mutableListOf<Double>()
    while (scanner.position < value.length) {
        if (result.isNotEmpty()) {
            val previous = scanner.position
            scanner.whitespace()
            if (scanner.position == value.length) break
            if (value[scanner.position] == ',') {
                scanner.position++
                scanner.whitespace()
            }
            svgRequire(scanner.position != previous ||
                (points && result.size % 2 == 1 && value.getOrNull(scanner.position) == '-'), "SVG_NUMBER_SYNTAX")
        }
        svgRequire(result.size < maximum, "SVG_NUMBER_COUNT")
        result.add(scanner.number())
    }
    return result.toList()
}

fun parseSvgPath(value: String): List<SvgCommand> {
    val scanner = SvgNumberScanner(value)
    val result = mutableListOf<SvgCommand>()
    var implicit: Char? = null
    while (true) {
        scanner.whitespace()
        if (scanner.position == value.length) return result.toList()
        val next = value[scanner.position]
        val explicit = next in "MmLlHhVvCcSsQqTtAaZz"
        val command = if (explicit) {
            scanner.position++
            next
        } else implicit ?: throw SvgValidationException("SVG_PATH_SYNTAX")
        val upper = command.uppercaseChar()
        svgRequire(result.isNotEmpty() || upper == 'M', "SVG_PATH_SYNTAX")
        svgRequire(result.size < SVG_MAX_COMMANDS, "SVG_COMMAND_LIMIT")
        val arguments = List(arity.getValue(upper)) { index ->
            if (index != 0 || !explicit) {
                val previous = scanner.position
                scanner.separator()
                svgRequire(upper != 'A' || index != 3 || scanner.position != previous, "SVG_PATH_SYNTAX")
            } else scanner.whitespace()
            scanner.number(flag = upper == 'A' && index in 3..4, unsigned = upper == 'A' && index in 0..1)
        }
        result.add(SvgCommand(command, arguments))
        implicit = when (upper) {
            'Z' -> null
            'M' -> if (command == 'm') 'l' else 'L'
            else -> command
        }
    }
}
