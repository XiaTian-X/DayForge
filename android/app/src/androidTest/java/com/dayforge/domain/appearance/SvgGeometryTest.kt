package com.dayforge.domain.appearance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class SvgGeometryTest {
    private fun near(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) -> assertEquals(a, b, 1e-10) }
    }

    @Test
    fun sharedAbsoluteCommandsAndArcGeometry() {
        val cases = InstrumentationRegistry.getInstrumentation().context.assets.open("next/svg-geometry.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        assertEquals(12, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val source = case.getValue("d").jsonPrimitive.content
            val error = case["error"]?.jsonPrimitive?.content
            if (error != null) {
                assertEquals(error, assertThrows(SvgValidationException::class.java) { resolveSvgPath(source) }.code)
            } else {
                val result = resolveSvgPath(source)
                assertEquals(case.getValue("contours").jsonPrimitive.int, result.contours)
                assertEquals(case.getValue("source_commands").jsonPrimitive.int, result.sourceCommands)
                assertTrue(result.lengthBound.isFinite() && result.lengthBound >= 0)
                val commands = case["commands"]?.jsonArray
                if (commands != null) {
                    assertEquals(commands.size, result.commands.size)
                    commands.zip(result.commands).forEach { (expected, actual) ->
                        val values = expected.jsonArray
                        assertEquals(values[0].jsonPrimitive.content.single(), actual.command)
                        near(values.drop(1).map { it.jsonPrimitive.double }, actual.values)
                    }
                } else {
                    val ends = case.getValue("curve_ends").jsonArray
                    val curves = result.commands.drop(1)
                    assertEquals('M', result.commands.first().command)
                    assertEquals(ends.size, curves.size)
                    ends.zip(curves).forEach { (end, actual) ->
                        assertEquals('C', actual.command)
                        near(end.jsonArray.map { it.jsonPrimitive.double }, actual.values.takeLast(2))
                    }
                    near(case.getValue("first_control").jsonArray.map { it.jsonPrimitive.double }, curves.first().values.take(2))
                }
            }
        }
    }

    @Test
    fun lengthBoundUsesControlPolygonWithoutMovetoJumps() {
        val result = resolveSvgPath("M0 0 L3 4 Z M100 100 Q103 104 106 100 C109 104 112 100 115 104")
        assertEquals(35.0, result.lengthBound, 0.0)
        assertEquals(2, result.contours)
        assertEquals(6, result.sourceCommands)
    }

    @Test
    fun exactDerivedRadiusAndFullRelativeCommandBudget() {
        val result = resolveSvgPath("M0 0 A1000000 0.000001 0 0 1 0 2")
        assertEquals(1e12, result.commands.flatMap { it.values }.maxOf { abs(it) }, 0.0)
        val repeated = resolveSvgPath("M0 0" + "l1000000 0".repeat(16383))
        assertEquals(listOf(16_383_000_000.0, 0.0), repeated.commands.last().values)
        assertEquals(16_383_000_000.0, repeated.lengthBound, 0.0)
        assertEquals(16384, repeated.sourceCommands)
    }

    @Test
    fun arcExpansionIsBoundedAndResetsSmoothControl() {
        val result = resolveSvgPath("M0 0 A10 10 0 1 1 0.001 0 S1 1 2 2")
        assertEquals(10, result.commands.size)
        assertEquals(listOf(0.001, 0.0), result.commands[result.commands.lastIndex - 1].values.takeLast(2))
        assertEquals(listOf(0.001, 0.0), result.commands.last().values.take(2))
    }
}
