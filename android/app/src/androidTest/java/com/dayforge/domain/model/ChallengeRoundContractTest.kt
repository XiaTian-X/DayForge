package com.dayforge.domain.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Shared causal values only. Persistence, owner checks and API activation are separate gates. */
@RunWith(AndroidJUnit4::class)
class ChallengeRoundContractTest {
    private val json = Json
    private val fixture: JsonObject by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets.open("next/challenge-rounds.json")
            .bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
    }
    private fun head(index: Int) = json.decodeFromJsonElement<ChallengeRoundHead>(fixture.getValue("heads").jsonArray[index])
    private fun intent(index: Int) = json.decodeFromJsonElement<ChallengeRestartIntent>(fixture.getValue("intents").jsonArray[index])
    private fun record(index: Int) = json.decodeFromJsonElement<ChallengeRoundRecord>(fixture.getValue("records").jsonArray[index])

    @Test fun initialIdentityUsesCanonicalPublicActivityNotTimeOrCurrentHead() {
        val cases = fixture.getValue("initial_identities").jsonArray
        assertEquals(2, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val activity = case.getValue("activity_uuid").jsonPrimitive.content
            val expected = case.getValue("round_uuid").jsonPrimitive.content
            assertEquals(expected, initialChallengeRoundUuid(activity))
            assertEquals(expected, initialChallengeRoundHead(activity).roundUuid)
            assertEquals(expected, initialChallengeRoundUuid(activity))
        }
        listOf("11111111111141118111111111111111", "{11111111-1111-4111-8111-111111111111}", "invalid").forEach {
            assertThrows(IllegalArgumentException::class.java) { initialChallengeRoundUuid(it) }
        }
    }

    @Test fun everyHeadIntentAndRecordRoundTripsExactlyIncludingIntegerLimits() {
        assertEquals(7, fixture.getValue("heads").jsonArray.size)
        assertEquals(5, fixture.getValue("intents").jsonArray.size)
        assertEquals(7, fixture.getValue("records").jsonArray.size)
        fixture.getValue("heads").jsonArray.forEach {
            assertEquals(it, json.encodeToJsonElement(json.decodeFromJsonElement<ChallengeRoundHead>(it)))
        }
        fixture.getValue("intents").jsonArray.forEach {
            assertEquals(it, json.encodeToJsonElement(json.decodeFromJsonElement<ChallengeRestartIntent>(it)))
        }
        fixture.getValue("records").jsonArray.forEach {
            assertEquals(it, json.encodeToJsonElement(json.decodeFromJsonElement<ChallengeRoundRecord>(it)))
        }
    }

    @Test fun everyRestartUsesExactHeadAndPlanButDoesNotReplaceOperationReplay() {
        val cases = fixture.getValue("transitions").jsonArray
        assertEquals(11, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val current = head(case.getValue("head").jsonPrimitive.int)
            val requested = intent(case.getValue("intent").jsonPrimitive.int)
            val before = json.encodeToJsonElement(current) to json.encodeToJsonElement(requested)
            val call = {
                advanceChallenge(current, requested, case.getValue("plan_revision").jsonPrimitive.long,
                    case["unfinished_timer"]?.jsonPrimitive?.boolean ?: false,
                    case["deleted"]?.jsonPrimitive?.boolean ?: false)
            }
            case["error"]?.let { assertCode(it.jsonPrimitive.content) { call() } }
                ?: assertEquals(head(case.getValue("result").jsonPrimitive.int), call())
            assertEquals(before, json.encodeToJsonElement(current) to json.encodeToJsonElement(requested))
        }
    }

    @Test fun oldPagesDoNotRollBackAndNewHeadsCannotSkipTheirPredecessor() {
        val cases = fixture.getValue("deliveries").jsonArray
        assertEquals(8, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val current = head(case.getValue("head").jsonPrimitive.int)
            val incoming = record(case.getValue("record").jsonPrimitive.int)
            val before = json.encodeToJsonElement(current) to json.encodeToJsonElement(incoming)
            case["error"]?.let { assertCode(it.jsonPrimitive.content) { applyChallengeRecord(current, incoming) } }
                ?: assertEquals(head(case.getValue("result").jsonPrimitive.int), applyChallengeRecord(current, incoming))
            assertEquals(before, json.encodeToJsonElement(current) to json.encodeToJsonElement(incoming))
        }
    }

    @Test fun completeRestoreRequiresUniqueCausalHistoryAndDeviceScopedOperationIds() {
        val cases = fixture.getValue("histories").jsonArray
        assertEquals(11, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val records = case.getValue("records").jsonArray.map { record(it.jsonPrimitive.int) }
            val before = json.encodeToJsonElement(records)
            case["error"]?.let { assertCode(it.jsonPrimitive.content) { rebuildChallengeHistory(head(0).activityUuid, records) } }
                ?: assertEquals(head(case.getValue("result").jsonPrimitive.int), rebuildChallengeHistory(head(0).activityUuid, records))
            assertEquals(before, json.encodeToJsonElement(records))
        }
        assertCode("CHALLENGE_ACTIVITY_MISMATCH") { rebuildChallengeHistory(head(5).activityUuid, listOf(record(0), record(1))) }
        assertCode("CHALLENGE_CHAIN_INCOMPLETE") { rebuildChallengeHistory(head(0).activityUuid, listOf(record(3))) }
    }

    @Test fun allSharedMalformedAndCoercibleValuesAreRejected() {
        val cases = fixture.getValue("invalid").jsonArray
        assertEquals(25, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val kind = case.getValue("kind").jsonPrimitive.content
            val root = when (kind) { "head" -> "heads"; "intent" -> "intents"; "record" -> "records"; else -> error(kind) }
            val base = fixture.getValue(root).jsonArray[case.getValue("base").jsonPrimitive.int]
            val changed = replace(base, case.getValue("path").jsonArray, case.getValue("value"))
            assertThrows(case.toString(), IllegalArgumentException::class.java) {
                when (kind) {
                    "head" -> json.decodeFromJsonElement<ChallengeRoundHead>(changed)
                    "intent" -> json.decodeFromJsonElement<ChallengeRestartIntent>(changed)
                    "record" -> json.decodeFromJsonElement<ChallengeRoundRecord>(changed)
                }
            }
        }
    }

    private fun assertCode(expected: String, call: () -> Unit) {
        assertEquals(expected, assertThrows(ChallengeTransitionException::class.java) { call() }.code)
    }

    private fun replace(root: JsonElement, path: List<JsonElement>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val key = path.first().jsonPrimitive.content
        val map = root.jsonObject.toMutableMap()
        map[key] = if (path.size == 1) value else replace(map.getValue(key), path.drop(1), value)
        return JsonObject(map)
    }
}
