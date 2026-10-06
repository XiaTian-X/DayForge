package com.dayforge.domain.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.appearance.ThemeInputException
import com.dayforge.data.appearance.ValidatedTheme
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Raw tokens, not re-encoded numeric DTOs: conversion must not erase original type/precision. */
@OptIn(ExperimentalSerializationApi::class)
@RunWith(AndroidJUnit4::class)
class ContractIntegerTest {
    private val json = Json
    private fun fixture(name: String): JsonElement = InstrumentationRegistry.getInstrumentation().context
        .assets.open("next/$name.json").bufferedReader().use { json.parseToJsonElement(it.readText()) }

    private fun replace(root: JsonElement, path: List<String>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        return when (root) {
            is JsonObject -> JsonObject(root + (path.first() to replace(root.getValue(path.first()), path.drop(1), value)))
            is JsonArray -> JsonArray(root.mapIndexed { index, child ->
                if (index == path.first().toInt()) replace(child, path.drop(1), value) else child
            })
            else -> error("Invalid fixture path")
        }
    }

    @Test fun intExtremaAndOrdinaryValuesRoundTripExactlyWithoutFloatingPoint() {
        for (value in listOf(Int.MIN_VALUE, -16_777_217, -1, 0, 1, 16_777_217, Int.MAX_VALUE)) {
            val text = value.toString()
            assertEquals(value, json.decodeFromString(ContractIntegerSerializer, text))
            assertEquals(value, json.decodeFromJsonElement(ContractIntegerSerializer, json.parseToJsonElement(text)))
            assertEquals(text, json.encodeToString(ContractIntegerSerializer, value))
        }
        assertEquals(0, json.decodeFromString(ContractIntegerSerializer, "-0"))
    }

    @Test fun longExtremaAndValuesBeyondDoublePrecisionRemainExact() {
        for (value in listOf(Long.MIN_VALUE, -9_007_199_254_740_993L, -1L, 0L, 1L,
            9_007_199_254_740_991L, 9_007_199_254_740_992L, 9_007_199_254_740_993L, Long.MAX_VALUE)) {
            val text = value.toString()
            assertEquals(value, json.decodeFromString(ContractLongSerializer, text))
            assertEquals(value, json.decodeFromJsonElement(ContractLongSerializer, json.parseToJsonElement(text)))
            assertEquals(text, json.encodeToString(ContractLongSerializer, value))
        }
        assertEquals(0L, json.decodeFromString(ContractLongSerializer, "-0"))
    }

    @Test fun rawExponentsFractionsAndNonNumbersNeverBecomeContractIntegers() {
        val tokens = listOf("0e0", "1e0", "1E+0", "70e-1", "0e50", "-1e0", "1.0", "-0.0",
            "9007199254740993e0", "9223372036854775807e0", "\"1\"", "\"1e0\"", "true", "false", "null", "[]", "{}")
        for (token in tokens) {
            assertThrows(token, SerializationException::class.java) { json.decodeFromString(ContractIntegerSerializer, token) }
            assertThrows(token, SerializationException::class.java) { json.decodeFromString(ContractLongSerializer, token) }
            val tree = json.parseToJsonElement(token)
            assertThrows(token, SerializationException::class.java) { json.decodeFromJsonElement(ContractIntegerSerializer, tree) }
            assertThrows(token, SerializationException::class.java) { json.decodeFromJsonElement(ContractLongSerializer, tree) }
        }
    }

    @Test fun nonCanonicalUnquotedTokensCannotBypassTreeDecoding() {
        for (token in listOf("+1", "01", "-01", "00", "1 ", " 1", "1\n", "1,2", "1x", "NaN", "Infinity", "１")) {
            val tree = JsonUnquotedLiteral(token)
            assertThrows(token, SerializationException::class.java) { json.decodeFromJsonElement(ContractIntegerSerializer, tree) }
            assertThrows(token, SerializationException::class.java) { json.decodeFromJsonElement(ContractLongSerializer, tree) }
        }
        for (token in listOf("+1", "01", "-01", "00")) {
            assertThrows(token, SerializationException::class.java) { json.decodeFromString(ContractIntegerSerializer, token) }
            assertThrows(token, SerializationException::class.java) { json.decodeFromString(ContractLongSerializer, token) }
        }
    }

    @Test fun exactWidthsRejectOverflowWithoutRoundingSaturationOrWrapping() {
        for (token in listOf("2147483648", "-2147483649", "9223372036854775807")) {
            assertThrows(token, SerializationException::class.java) { json.decodeFromString(ContractIntegerSerializer, token) }
            assertThrows(token, SerializationException::class.java) {
                json.decodeFromJsonElement(ContractIntegerSerializer, json.parseToJsonElement(token))
            }
        }
        for (token in listOf("9223372036854775808", "-9223372036854775809", "9".repeat(100))) {
            assertThrows(token, SerializationException::class.java) { json.decodeFromString(ContractLongSerializer, token) }
            assertThrows(token, SerializationException::class.java) {
                json.decodeFromJsonElement(ContractLongSerializer, json.parseToJsonElement(token))
            }
        }
    }

    @Test fun onceStateAndIntentCannotCoerceVersionsOrChangeCausalIdentity() {
        val state = """{"version":0,"head_event_uuid":null,"completion_event_uuid":null}"""
        val intent = """{"event_uuid":"a9600000-0000-4000-8000-000000000001","action":"complete","expected_version":0,"expected_head_event_uuid":null,"reverts_event_uuid":null}"""
        assertEquals(0, json.decodeFromString<OneTimeState>(state).version)
        assertEquals(0, json.decodeFromString<OneTimeIntent>(intent).expectedVersion)
        for (token in listOf("0e0", "0.0", "\"0\"", "2147483648")) {
            assertThrows(SerializationException::class.java) { json.decodeFromString<OneTimeState>(state.replace(":0,", ":$token,")) }
            assertThrows(SerializationException::class.java) { json.decodeFromString<OneTimeIntent>(intent.replace(":0,", ":$token,")) }
        }
    }

    @Test fun iconThemeAndAllNestedConfigIntegerFieldsRequireOriginalIntegerTokens() {
        val cases = listOf(
            "icon-pack" to listOf(listOf("format_version"), listOf("revision"), listOf("assets", "0", "light", "byte_length"),
                listOf("assets", "0", "light", "width"), listOf("assets", "0", "light", "height")),
            "theme" to listOf(listOf("format_version"), listOf("revision")),
            "config" to listOf(listOf("format_version"), listOf("nodes", "0", "goal", "target_cycles"),
                listOf("nodes", "1", "activity", "target_value"), listOf("nodes", "1", "activity", "target_cycles"),
                listOf("nodes", "1", "activity", "preferred_minute"), listOf("nodes", "2", "activity", "schedule", "weekdays", "0"),
                listOf("nodes", "3", "activity", "schedule", "every_days"), listOf("nodes", "4", "activity", "schedule", "day_of_month"),
                listOf("metrics", "0", "decimal_places"), listOf("themes", "0", "revision"), listOf("icon_pack", "revision"))
        )
        fun decode(name: String, raw: JsonElement): Any = when (name) {
            "icon-pack" -> json.decodeFromString<IconPack>(raw.toString())
            "theme" -> json.decodeFromString<ThemeDefinition>(raw.toString())
            "config" -> json.decodeFromString<ConfigBundle>(raw.toString())
            else -> error("Unknown fixture")
        }
        for ((name, paths) in cases) {
            val source = fixture(name)
            assertNotNull(decode(name, source))
            for (path in paths) {
                val original = path.fold(source) { value, key ->
                    if (value is JsonArray) value[key.toInt()] else value.jsonObject.getValue(key)
                }.jsonPrimitive.content
                val changed = replace(source, path, JsonUnquotedLiteral("${original}e0"))
                assertThrows("$name:$path", SerializationException::class.java) { decode(name, changed) }
            }
            assertNotNull(decode(name, source))
        }
    }

    @Test fun assetQuotaAndCatalogLongsRejectExponentConversionButKeepExactHighPrecisionValues() {
        val large = 9_007_199_254_740_993L
        val quota = """{"byte_limit":$large,"reserved_bytes":0,"asset_limit":1000,"reserved_assets":0,"metadata_byte_limit":8388608,"reserved_metadata_bytes":0}"""
        assertEquals(large, json.decodeFromString<AppearanceQuota>(quota).byteLimit)
        val quotaTree = json.parseToJsonElement(quota).jsonObject
        for ((field, value) in quotaTree) {
            val changed = JsonObject(quotaTree + (field to JsonUnquotedLiteral("${value.jsonPrimitive.content}e0")))
            assertThrows(field, SerializationException::class.java) { json.decodeFromString<AppearanceQuota>(changed.toString()) }
        }
        val context = """{"server_instance_id":"a9600000-0000-4000-8000-000000000002","sync_epoch":"a9600000-0000-4000-8000-000000000003","device_id":"a9600000-0000-4000-8000-000000000004"}"""
        val asset = fixture("icon-pack").jsonObject.getValue("assets").jsonArray.first()
        val page = json.parseToJsonElement("""{"context":$context,"entries":[{"kind":"asset","sequence":$large,"asset":$asset}],"next_cursor":$large,"through_sequence":$large,"has_more":false}""")
        val decoded = json.decodeFromJsonElement<AppearanceCatalogPage>(page)
        assertEquals(large, decoded.nextCursor); assertEquals(large, decoded.entries.single().sequence)
        for (path in listOf(listOf("next_cursor"), listOf("through_sequence"), listOf("entries", "0", "sequence"))) {
            val changed = replace(page, path, JsonUnquotedLiteral("${large}e0"))
            assertThrows(path.toString(), SerializationException::class.java) {
                json.decodeFromString<AppearanceCatalogPage>(changed.toString())
            }
        }
        assertEquals(decoded, json.decodeFromString<AppearanceCatalogPage>(page.toString()))
    }

    @Test fun legitimateFloatingPointMetricTargetsAndLinkCoefficientsKeepTheirExistingMeaning() {
        val source = fixture("config")
        val baseline = json.decodeFromJsonElement<ConfigBundle>(source)
        val changed = replace(replace(replace(source, listOf("metrics", "0", "target_value"), JsonUnquotedLiteral("7.05e1")),
            listOf("metrics", "0", "target_value_upper"), JsonUnquotedLiteral("9.025e1")),
            listOf("links", "0", "coefficient"), JsonUnquotedLiteral("1.25e0"))
        val decoded = json.decodeFromString<ConfigBundle>(changed.toString())
        assertEquals(baseline, decoded)
        assertEquals(70.5, decoded.metrics.single().targetValue!!, 0.0)
        assertEquals(90.25, decoded.metrics.single().targetValueUpper!!, 0.0)
        assertEquals(1.25, decoded.links.single().coefficient, 0.0)
        assertNull(decoded.nodes.last().activity!!.targetCycles)
        assertNull(decoded.nodes.last().activity!!.preferredMinute)
    }

    @Test fun actualThemeInputRejectsCoercedRevisionClosesSourceAndKeepsOriginalBytes() = runBlocking<Unit> {
        val source = fixture("theme").toString().toByteArray()
        val baseline = ValidatedTheme.read { ByteArrayInputStream(source) }
        assertArrayEquals(source, baseline.exportBytes())
        for (token in listOf("1e0", "1.0", "2147483648")) {
            val changed = JsonObject(fixture("theme").jsonObject + ("revision" to JsonUnquotedLiteral(token))).toString().toByteArray()
            var opened = 0; var closed = 0
            val failure = try {
                ValidatedTheme.read { opened++; object : ByteArrayInputStream(changed) {
                    override fun close() { closed++; super.close() }
                } }
                throw AssertionError("Must reject")
            } catch (error: ThemeInputException) { error }
            assertEquals("THEME_JSON", failure.code); assertEquals(1, opened); assertEquals(1, closed)
            assertArrayEquals(source, baseline.exportBytes())
        }
    }
}
