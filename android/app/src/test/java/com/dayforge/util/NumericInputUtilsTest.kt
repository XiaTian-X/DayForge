package com.dayforge.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for NumericInputUtils.
 *
 * Tests the filterNumericInput function that handles:
 * - Decimal places truncation during input
 * - Negative number handling
 * - Multiple decimal point handling
 */
class NumericInputUtilsTest {

    // Test decimal places truncation
    @Test
    fun `filterNumericInput with decimalPlaces=1 truncates extra decimals`() {
        assertEquals("70.5", NumericInputUtils.filterNumericInput("70.55", 1))
    }

    @Test
    fun `filterNumericInput with decimalPlaces=0 removes decimals entirely`() {
        assertEquals("70", NumericInputUtils.filterNumericInput("70.555", 0))
    }

    @Test
    fun `filterNumericInput with decimalPlaces=2 keeps two decimals`() {
        assertEquals("70.55", NumericInputUtils.filterNumericInput("70.559", 2))
    }

    // Test negative number handling
    @Test
    fun `filterNumericInput preserves negative sign at start with decimalPlaces=1`() {
        assertEquals("-70.5", NumericInputUtils.filterNumericInput("-70.55", 1))
    }

    @Test
    fun `filterNumericInput preserves negative sign at start with decimalPlaces=0`() {
        assertEquals("-70", NumericInputUtils.filterNumericInput("-70.5", 0))
    }

    // Test multiple decimal point handling
    @Test
    fun `filterNumericInput with multiple decimals keeps only first with decimalPlaces=2`() {
        assertEquals("70.55", NumericInputUtils.filterNumericInput("70.5.5", 2))
    }

    @Test
    fun `filterNumericInput with multiple decimals truncates after first decimal`() {
        assertEquals("70.5", NumericInputUtils.filterNumericInput("70.5.55", 1))
    }

    // Test multiple minus sign handling
    @Test
    fun `filterNumericInput with double minus at start keeps only one`() {
        assertEquals("-", NumericInputUtils.filterNumericInput("--70", 1))
    }

    @Test
    fun `filterNumericInput with minus not at start removes it`() {
        assertEquals("70", NumericInputUtils.filterNumericInput("70-", 1))
    }

    @Test
    fun `filterNumericInput with minus in middle removes it`() {
        assertEquals("705", NumericInputUtils.filterNumericInput("70-5", 1))
    }

    // Edge cases
    @Test
    fun `filterNumericInput with empty string returns empty`() {
        assertEquals("", NumericInputUtils.filterNumericInput("", 1))
    }

    @Test
    fun `filterNumericInput with just minus sign returns minus`() {
        assertEquals("-", NumericInputUtils.filterNumericInput("-", 1))
    }

    @Test
    fun `filterNumericInput with just decimal point returns decimal`() {
        assertEquals(".", NumericInputUtils.filterNumericInput(".", 1))
    }

    @Test
    fun `filterNumericInput with minus and decimal returns minus decimal`() {
        assertEquals("-.", NumericInputUtils.filterNumericInput("-.", 1))
    }

    @Test
    fun `filterNumericInput with only decimal after decimal places 0 returns empty`() {
        assertEquals("", NumericInputUtils.filterNumericInput(".", 0))
    }

    @Test
    fun `filterNumericInput preserves correct decimals within limit`() {
        assertEquals("70.55", NumericInputUtils.filterNumericInput("70.55", 2))
    }

    @Test
    fun `filterNumericInput with negative and correct decimals preserves both`() {
        assertEquals("-70.55", NumericInputUtils.filterNumericInput("-70.55", 2))
    }
}