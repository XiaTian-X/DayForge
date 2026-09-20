package com.dayforge.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class FiniteNumericInputTest {
    @Test
    fun overflow_and_non_finite_input_are_rejected() {
        for (input in listOf("9".repeat(400), "-" + "9".repeat(400), "NaN", "Infinity", "-Infinity", "", "-")) {
            assertNull(input, NumericInputUtils.parseFiniteDouble(input))
        }
    }

    @Test
    fun normal_positive_negative_and_zero_values_are_retained() {
        assertEquals(12.5, NumericInputUtils.parseFiniteDouble("12.5")!!, 0.0)
        assertEquals(-12.5, NumericInputUtils.parseFiniteDouble(" -12.5 ")!!, 0.0)
        assertEquals(0.0, NumericInputUtils.parseFiniteDouble("0")!!, 0.0)
    }
}
