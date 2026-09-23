package com.dayforge.domain.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Shared forward contract only: production Room/HTTP activation has separate acceptance gates. */
@RunWith(AndroidJUnit4::class)
class NextContractTest {
    private val json = Json { classDiscriminator = "kind"; ignoreUnknownKeys = false }

    @Test
    fun sharedTransitionsUseCausalHeadAndDoNotMutateInput() {
        val cases = fixture("one-time-transitions.json").jsonArray
        assertEquals(10, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val state = json.decodeFromJsonElement<OneTimeState>(case.getValue("state"))
            val intent = json.decodeFromJsonElement<OneTimeIntent>(case.getValue("intent"))
            assertEquals(case["state"], json.encodeToJsonElement(state))
            assertEquals(case["intent"], json.encodeToJsonElement(intent))
            val error = case["error"]?.jsonPrimitive?.contentOrNull
            val deleted = case.getValue("deleted").jsonPrimitive.boolean
            if (error == null) {
                assertEquals(name, case["result"], json.encodeToJsonElement(advanceOneTime(state, intent, deleted)))
            } else {
                val failure = assertThrows(name, OneTimeTransitionException::class.java) {
                    advanceOneTime(state, intent, deleted)
                }
                assertEquals(name, error, failure.code)
            }
            assertEquals(name, case["state"], json.encodeToJsonElement(state))
        }
    }

    @Test
    fun packRoundTripsAndAcceptsExactByteAndPixelLimits() {
        val source = fixture("icon-pack.json")
        val pack = json.decodeFromJsonElement<IconPack>(source)
        assertEquals(source, json.encodeToJsonElement(pack))
        assertEquals(pack, json.decodeFromString<IconPack>(json.encodeToString(pack)))
        val bounded = pack.copy(assets = listOf(
            pack.assets[0].copy(light = pack.assets[0].light.copy(byteLength = 524_288)),
            pack.assets[1].copy(light = pack.assets[1].light.copy(byteLength = 2_097_152, width = 1024))
        ))
        assertEquals(bounded, json.decodeFromString<IconPack>(json.encodeToString(bounded)))
    }

    @Test
    fun sharedReferencesKeepIdentityAndReserveTaskRoles() {
        val pack = json.decodeFromJsonElement<IconPack>(fixture("icon-pack.json"))
        val cases = fixture("icon-references.json").jsonArray
        assertEquals(10, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val icon = json.decodeFromJsonElement<IconReference>(case.getValue("icon"))
            assertEquals(case["icon"], json.encodeToJsonElement<IconReference>(icon))
            val index = case.getValue("asset_index")
            val asset = if (index == JsonNull) null else pack.assets[index.jsonPrimitive.int]
            assertEquals(case.toString(), case.getValue("allowed").jsonPrimitive.boolean,
                iconAllowed(icon, case.getValue("one_time").jsonPrimitive.boolean, asset))
        }
    }

    @Test
    fun everySharedInvalidInputIsRejected() {
        val first = fixture("one-time-transitions.json").jsonArray.first().jsonObject
        val bases = first + ("pack" to fixture("icon-pack.json"))
        val cases = fixture("invalid.json").jsonArray
        assertEquals(31, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val base = case.getValue("base").jsonPrimitive.content
            val changed = replace(bases.getValue(base), case.getValue("path").jsonArray, case.getValue("value"))
            val failure = runCatching {
                when (base) {
                    "pack" -> json.decodeFromJsonElement<IconPack>(changed)
                    "state" -> json.decodeFromJsonElement<OneTimeState>(changed)
                    "intent" -> json.decodeFromJsonElement<OneTimeIntent>(changed)
                    else -> error("Unconsumed fixture type: $base")
                }
            }.exceptionOrNull()
            assertTrue(case.getValue("name").toString(), failure is IllegalArgumentException)
        }
    }

    @Test
    fun totalPackBudgetAndCountAreBounded() {
        val pack = json.decodeFromJsonElement<IconPack>(fixture("icon-pack.json"))
        val assets = (0..32).map { index ->
            pack.assets[1].copy(
                assetId = "40000000-0000-0000-0000-" + index.toString(16).padStart(12, '0'),
                light = pack.assets[1].light.copy(
                    sha256 = index.toString(16).padStart(64, '0'), byteLength = 2_097_152
                ), dark = null
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            pack.copy(assets = assets, roles = emptyMap(), placeholderAssetId = null)
        }
        assertEquals(32, pack.copy(assets = assets.take(32), roles = emptyMap(), placeholderAssetId = null).assets.size)
        val tooMany = (0..128).map { index ->
            assets[0].copy(assetId = "40000000-0000-0000-0000-" + index.toString(16).padStart(12, '0'))
        }
        assertThrows(IllegalArgumentException::class.java) {
            pack.copy(assets = tooMany, roles = emptyMap(), placeholderAssetId = null)
        }
    }

    @Test
    fun reducerDoesNotReplaceOperationReplay() {
        val case = fixture("one-time-transitions.json").jsonArray.first().jsonObject
        val state = json.decodeFromJsonElement<OneTimeState>(case.getValue("state"))
        val intent = json.decodeFromJsonElement<OneTimeIntent>(case.getValue("intent"))
        val result = advanceOneTime(state, intent)
        val failure = assertThrows(OneTimeTransitionException::class.java) { advanceOneTime(result, intent) }
        assertEquals("TASK_STATE_CONFLICT", failure.code)
    }

    private fun replace(root: JsonElement, path: List<JsonElement>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val next = path.drop(1)
        return when (root) {
            is JsonObject -> {
                val key = path.first().jsonPrimitive.content
                JsonObject(root + (key to replace(root[key] ?: JsonNull, next, value)))
            }
            is JsonArray -> JsonArray(root.mapIndexed { index, child ->
                if (index == path.first().jsonPrimitive.int) replace(child, next, value) else child
            })
            else -> error("Invalid fixture path: $path")
        }
    }

    private fun fixture(name: String): JsonElement = InstrumentationRegistry.getInstrumentation()
        .context.assets.open("next/$name").bufferedReader().use { json.parseToJsonElement(it.readText()) }
}
