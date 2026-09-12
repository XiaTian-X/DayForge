package com.dayforge.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FiniteNumericInputTest {
    @Test
    fun `overflow and non finite input are rejected`() {
        for (input in listOf("9".repeat(400), "-" + "9".repeat(400), "NaN", "Infinity", "-Infinity", "", "-")) {
            assertNull(input, NumericInputUtils.parseFiniteDouble(input))
        }
    }

    @Test
    fun `normal positive negative and zero values are retained`() {
        assertEquals(12.5, NumericInputUtils.parseFiniteDouble("12.5")!!, 0.0)
        assertEquals(-12.5, NumericInputUtils.parseFiniteDouble(" -12.5 ")!!, 0.0)
        assertEquals(0.0, NumericInputUtils.parseFiniteDouble("0")!!, 0.0)
    }
}
