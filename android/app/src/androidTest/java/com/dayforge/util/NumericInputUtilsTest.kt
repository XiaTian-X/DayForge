package com.dayforge.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

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
@RunWith(AndroidJUnit4::class)
class NumericInputUtilsTest {

    // Test decimal places truncation
    @Test
    fun filterNumericInput_with_decimalPlaces_1_truncates_extra_decimals() {
        assertEquals("70.5", NumericInputUtils.filterNumericInput("70.55", 1))
    }

    @Test
    fun filterNumericInput_with_decimalPlaces_0_removes_decimals_entirely() {
        assertEquals("70", NumericInputUtils.filterNumericInput("70.555", 0))
    }

    @Test
    fun filterNumericInput_with_decimalPlaces_2_keeps_two_decimals() {
        assertEquals("70.55", NumericInputUtils.filterNumericInput("70.559", 2))
    }

    // Test negative number handling
    @Test
    fun filterNumericInput_preserves_negative_sign_at_start_with_decimalPlaces_1() {
        assertEquals("-70.5", NumericInputUtils.filterNumericInput("-70.55", 1))
    }

    @Test
    fun filterNumericInput_preserves_negative_sign_at_start_with_decimalPlaces_0() {
        assertEquals("-70", NumericInputUtils.filterNumericInput("-70.5", 0))
    }

    // Test multiple decimal point handling
    @Test
    fun filterNumericInput_with_multiple_decimals_keeps_only_first_with_decimalPlaces_2() {
        assertEquals("70.55", NumericInputUtils.filterNumericInput("70.5.5", 2))
    }

    @Test
    fun filterNumericInput_with_multiple_decimals_truncates_after_first_decimal() {
        assertEquals("70.5", NumericInputUtils.filterNumericInput("70.5.55", 1))
    }

    // Test multiple minus sign handling
    @Test
    fun filterNumericInput_with_double_minus_at_start_keeps_only_one() {
        assertEquals("-", NumericInputUtils.filterNumericInput("--70", 1))
    }

    @Test
    fun filterNumericInput_with_minus_not_at_start_removes_it() {
        assertEquals("70", NumericInputUtils.filterNumericInput("70-", 1))
    }

    @Test
    fun filterNumericInput_with_minus_in_middle_removes_it() {
        assertEquals("705", NumericInputUtils.filterNumericInput("70-5", 1))
    }

    // Edge cases
    @Test
    fun filterNumericInput_with_empty_string_returns_empty() {
        assertEquals("", NumericInputUtils.filterNumericInput("", 1))
    }

    @Test
    fun filterNumericInput_with_just_minus_sign_returns_minus() {
        assertEquals("-", NumericInputUtils.filterNumericInput("-", 1))
    }

    @Test
    fun filterNumericInput_with_just_decimal_point_returns_decimal() {
        assertEquals(".", NumericInputUtils.filterNumericInput(".", 1))
    }

    @Test
    fun filterNumericInput_with_minus_and_decimal_returns_minus_decimal() {
        assertEquals("-.", NumericInputUtils.filterNumericInput("-.", 1))
    }

    @Test
    fun filterNumericInput_with_only_decimal_after_decimal_places_0_returns_empty() {
        assertEquals("", NumericInputUtils.filterNumericInput(".", 0))
    }

    @Test
    fun filterNumericInput_preserves_correct_decimals_within_limit() {
        assertEquals("70.55", NumericInputUtils.filterNumericInput("70.55", 2))
    }

    @Test
    fun filterNumericInput_with_negative_and_correct_decimals_preserves_both() {
        assertEquals("-70.55", NumericInputUtils.filterNumericInput("-70.55", 2))
    }
}