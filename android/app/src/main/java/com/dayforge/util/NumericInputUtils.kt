package com.dayforge.util

/**
 * Utility object for numeric input filtering.
 *
 * Provides functions to filter and validate numeric input strings,
 * handling decimal places, negative numbers, and multiple decimal points.
 */
object NumericInputUtils {

    /** Reject overflow and non-finite values that cannot be synchronized as JSON numbers. */
    fun parseFiniteDouble(input: String): Double? =
        input.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    /**
     * Filter numeric input based on decimal places, preserving negative sign.
     *
     * Behavior:
     * - decimalPlaces = 0: only integers (0-9), negative sign allowed at start
     * - decimalPlaces > 0: allow one decimal point, max decimalPlaces digits after
     * - Negative sign allowed only at start
     * - Multiple decimal points: only first is kept
     * - Excess decimals are silently truncated
     *
     * @param input The input string to filter
     * @param decimalPlaces Maximum decimal places allowed (0 for integers only)
     * @return Filtered string respecting the decimal places constraint
     */
    fun filterNumericInput(input: String, decimalPlaces: Int): String {
        if (input.isEmpty()) return ""

        val allowedDecimalPlaces = decimalPlaces.coerceAtLeast(0)
        val isNegative = input.startsWith('-')
        val absInput = if (isNegative) input.substring(1) else input

        // Keep a lone minus while the user is in the middle of typing a negative value.
        if (isNegative && (absInput.isEmpty() || absInput.startsWith('-'))) {
            return "-"
        }

        val numericInput = absInput.filter { it.isDigit() || it == '.' }
        val firstDecimalIndex = numericInput.indexOf('.')

        val filtered = if (allowedDecimalPlaces == 0) {
            numericInput.substringBefore('.').filter(Char::isDigit)
        } else if (firstDecimalIndex >= 0) {
            val integerPart = numericInput.substring(0, firstDecimalIndex).filter(Char::isDigit)
            val fractionalPart = numericInput.substring(firstDecimalIndex + 1)
                .filter(Char::isDigit)
                .take(allowedDecimalPlaces)
            "$integerPart.$fractionalPart"
        } else {
            numericInput.filter(Char::isDigit)
        }

        return if (isNegative) "-$filtered" else filtered
    }
}
