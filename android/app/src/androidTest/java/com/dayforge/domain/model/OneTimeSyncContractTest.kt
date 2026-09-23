package com.dayforge.domain.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OneTimeSyncContractTest {
    private val json = Json
    private val fixture: JsonObject by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("next/one-time-sync.json").bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
    }
    private val activity: String get() = fixture.getValue("activity_uuid").jsonPrimitive.content
    private fun state(index: Int) = json.decodeFromJsonElement<OneTimeState>(fixture.getValue("states").jsonArray[index])
    private fun proof(index: Int) = json.decodeFromJsonElement<OneTimeEventProof>(fixture.getValue("proofs").jsonArray[index])
    private fun pending(index: Int) = json.decodeFromJsonElement<PendingOneTimeIntent>(fixture.getValue("pending").jsonArray[index])

    @Test
    fun snapshotsMustProveTheExactImmutableTransition() {
        fixture.getValue("proofs").jsonArray.forEach { raw ->
            assertEquals(raw, json.encodeToJsonElement(json.decodeFromJsonElement<OneTimeEventProof>(raw)))
        }
        val cases = fixture.getValue("invalid_proofs").jsonArray
        assertEquals(9, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val source = fixture.getValue("proofs").jsonArray[case.getValue("proof").jsonPrimitive.int]
            val changed = replace(source, case.getValue("path").jsonArray, case.getValue("value"))
            assertThrows(case.getValue("name").toString(), IllegalArgumentException::class.java) {
                json.decodeFromJsonElement<OneTimeEventProof>(changed)
            }
        }
    }

    @Test
    fun confirmedProjectionNeverRegressesAndCannotCrossActivityOrTombstone() {
        val cases = fixture.getValue("merges").jsonArray
        assertEquals(8, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val current = OneTimeProjection(activity, state(case.getValue("current").jsonPrimitive.int))
            val other = case["other_activity"]?.jsonPrimitive?.boolean == true
            val incoming = OneTimeProjection(
                if (other) fixture.getValue("other_activity_uuid").jsonPrimitive.content else activity,
                state(case.getValue("incoming").jsonPrimitive.int)
            )
            val before = json.encodeToJsonElement(current) to json.encodeToJsonElement(incoming)
            val error = case["error"]?.jsonPrimitive?.content
            if (error != null) {
                assertCode(error) { mergeOneTimeProjection(current, incoming, case["deleted"]?.jsonPrimitive?.boolean == true) }
            } else {
                assertEquals(OneTimeProjection(activity, state(case.getValue("result").jsonPrimitive.int)), mergeOneTimeProjection(current, incoming))
            }
            assertEquals(before, json.encodeToJsonElement(current) to json.encodeToJsonElement(incoming))
        }
    }

    @Test
    fun bootstrapHistoryIsCompleteCausalAndInputOrderIndependent() {
        val cases = fixture.getValue("histories").jsonArray
        assertEquals(12, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val events = case.getValue("events").jsonArray.map { proof(it.jsonPrimitive.int) }
            val before = json.encodeToJsonElement(events)
            val target = if (case["other_activity"]?.jsonPrimitive?.boolean == true)
                fixture.getValue("other_activity_uuid").jsonPrimitive.content else activity
            val checkpoint = OneTimeProjection(target, state(case.getValue("checkpoint").jsonPrimitive.int))
            val error = case["error"]?.jsonPrimitive?.content
            if (error != null) {
                assertCode(error) { rebuildOneTimeHistory(checkpoint, events) }
            } else {
                assertEquals(OneTimeProjection(activity, state(case.getValue("result").jsonPrimitive.int)), rebuildOneTimeHistory(checkpoint, events))
            }
            assertEquals(before, json.encodeToJsonElement(events))
        }
    }

    @Test
    fun optimisticChainRetainsIdsAndSeparatesReplayFromRejectedDependencies() {
        val cases = fixture.getValue("queues").jsonArray
        assertEquals(11, cases.size)
        cases.forEach { raw ->
            val case = raw.jsonObject
            val confirmed = state(case.getValue("confirmed").jsonPrimitive.int)
            val queue = case.getValue("pending").jsonArray.map { pending(it.jsonPrimitive.int) }
            val before = json.encodeToJsonElement(queue)
            val rejected = case.getValue("rejected").jsonPrimitive.contentOrNull
            val error = case["error"]?.jsonPrimitive?.content
            if (error != null) {
                assertCode(error) { projectPendingOneTime(confirmed, queue, rejected) }
            } else {
                assertEquals(case.getValue("result"), json.encodeToJsonElement(projectPendingOneTime(confirmed, queue, rejected)))
            }
            assertEquals(before, json.encodeToJsonElement(queue))
        }
    }

    @Test
    fun requestIdentityTypeTargetAndStoredPolicyMustMatch() {
        val request = fixture.getValue("request").jsonObject
        val payload = request.getValue("payload").jsonObject
        val intent = json.decodeFromJsonElement<OneTimeIntent>(payload.getValue("one_time"))
        val event = request.getValue("entity_uuid").jsonPrimitive.content
        validateOneTimeBinding(event, "check_in", null, intent, "one_and_done")
        validateOneTimeBinding(event, "check_in", null, null, "recurring")
        assertCode("INVALID_PAYLOAD") { validateOneTimeBinding(event, "check_in", null, null, "one_and_done") }
        assertCode("INVALID_PAYLOAD") { validateOneTimeBinding(event, "check_in", null, intent, "recurring") }
        assertCode("INVALID_PAYLOAD") { validateOneTimeBinding(event, "check_in", null, intent, "unknown") }
        assertCode("INVALID_PAYLOAD") { validateOneTimeBinding(activity, "check_in", null, intent, "one_and_done") }
        assertCode("INVALID_PAYLOAD") { validateOneTimeBinding(event, "duration_session", null, intent, "one_and_done") }
        assertCode("INVALID_PAYLOAD") { validateOneTimeBinding(event, "check_in", activity, intent, "one_and_done") }
    }

    @Test
    fun differentOperationCannotReuseAnEventOrForgeAnotherCausalBranch() {
        assertCode("INVALID_PENDING_CHAIN") {
            projectPendingOneTime(state(0), listOf(pending(0).copy(
                intent = pending(0).intent.copy(action = "undo", revertsEventUuid = proof(2).publicId)
            )))
        }
        assertCode("INVALID_PENDING_CHAIN") {
            projectPendingOneTime(state(0), listOf(pending(0), pending(1).copy(intent = pending(0).intent)))
        }
        val other = state(4).headEventUuid
        val forged = proof(1).copy(
            revertsEventUuid = other,
            oneTime = proof(1).oneTime.copy(expectedHeadEventUuid = other, revertsEventUuid = other)
        )
        assertCode("TASK_STATE_CONFLICT") { rebuildOneTimeHistory(OneTimeProjection(activity, state(2)), listOf(proof(0), forged)) }
        assertThrows(IllegalArgumentException::class.java) {
            proof(1).copy(revertsEventUuid = proof(2).publicId,
                oneTime = proof(1).oneTime.copy(revertsEventUuid = proof(2).publicId))
        }
    }

    private fun assertCode(code: String, block: () -> Unit) {
        assertEquals(code, assertThrows(OneTimeTransitionException::class.java, block).code)
    }

    private fun replace(root: JsonElement, path: List<JsonElement>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val key = path.first().jsonPrimitive.content
        val objectRoot = root.jsonObject
        return JsonObject(objectRoot + (key to replace(objectRoot[key] ?: JsonNull, path.drop(1), value)))
    }
}
