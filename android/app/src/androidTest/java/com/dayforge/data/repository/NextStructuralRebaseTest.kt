package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.dto.NextSyncOperationResult
import com.dayforge.data.api.dto.SyncV2Operation
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pure assertions still execute on the physical testbed, never on JVM/Robolectric. */
@RunWith(AndroidJUnit4::class)
class NextStructuralRebaseTest {
    private val entity = "81000000-0000-4000-8000-000000000011"
    private val endpoint = "81000000-0000-4000-8000-000000000012"
    private val firstId = "81000000-0000-4000-8000-000000000021"
    private val secondId = "81000000-0000-4000-8000-000000000022"
    private val thirdId = "81000000-0000-4000-8000-000000000023"
    private val replacement = "81000000-0000-4000-8000-000000000031"
    private val nextReplacement = "81000000-0000-4000-8000-000000000032"
    private val created = "2026-09-27T15:59:59.123456Z"
    private val stamp = "2026-09-28T00:00:00Z"

    private fun metric() = Json.parseToJsonElement("""{
        "name":"weight","description":"original","unit":"kg","decimal_places":3,"aggregation_type":"average",
        "target_direction":"range","target_value":"10.125","target_value_upper":"20.5","status":"active",
        "appearance":{"icon":{"kind":"asset","asset_id":"$endpoint"},"accent_color":"#123456","icon_tint":"object"}}
    """).jsonObject

    private fun plan(): JsonObject {
        val api = InstrumentationRegistry.getInstrumentation().context.assets.open("next/api.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val base = JsonObject(api.getValue("task").jsonObject + mapOf("created_at" to JsonPrimitive(created),
            "sort_order" to JsonPrimitive(37), "appearance" to metric().getValue("appearance")))
        return change(base, "activity.completion_policy", JsonPrimitive("recurring")).let {
            change(it, "activity.recurrence_rule", Json.parseToJsonElement(
                """{"schema_version":1,"type":"daily","interval":1,"start_date":null}"""))
        }
    }

    private fun goal(): JsonObject = JsonObject(plan() + mapOf(
        "node_kind" to JsonPrimitive("goal"), "activity" to JsonNull,
        "goal" to Json.parseToJsonElement("""{
            "start_date":"2026-01-01","due_date":"2026-12-31","target_cycles":null,
            "failure_policy":{"schema_version":1,"type":"loose"},
            "evaluation_policy":{"schema_version":1,"type":"manual"},"manual_result":null}""")))

    private fun link() = buildJsonObject {
        put("activity_uuid", entity); put("metric_uuid", endpoint); put("coefficient", "1.25")
        put("show_in_activity_detail", true); put("prompt_on_complete", true); put("is_active", true)
    }
    private fun operation(body: JsonObject, id: String = firstId, type: String = "metric", base: Long? = null) =
        SyncV2Operation(id, type, entity, "upsert", base, body)
    private fun accepted(op: SyncV2Operation, body: JsonObject = op.payload, revision: Long = 1,
        status: String = "applied") = NextSyncOperationResult(op.operationId, op.entityType, entity, status, revision,
        entity = JsonObject(body + mapOf("public_id" to JsonPrimitive(entity), "revision" to JsonPrimitive(revision),
            "created_at" to JsonPrimitive(created), "updated_at" to JsonPrimitive(stamp), "deleted_at" to JsonNull)))

    private fun change(body: JsonObject, path: String, value: JsonElement): JsonObject {
        val parts = path.split('.', limit = 2)
        return JsonObject(body + (parts[0] to if (parts.size == 1) value
            else change(body.getValue(parts[0]).jsonObject, parts[1], value)))
    }

    @Test fun newCreationAndRepeatedSameFieldEditsAdvanceTheProvenBaseWithoutMutatingOriginals() {
        val first = operation(metric())
        val second = operation(change(metric(), "name", JsonPrimitive("second")), secondId)
        val old = listOf(first.toString(), second.toString())
        for (status in listOf("applied", "already_applied")) {
            val result = NextStructuralRebase.merge(first, second, accepted(first, status = status), replacement)
            assertEquals(replacement, result.operationId); assertEquals(1L, result.baseRevision)
            assertEquals(JsonPrimitive("second"), result.payload["name"])
            assertEquals(second.payload, result.payload)
        }
        assertEquals(old, listOf(first.toString(), second.toString()))
    }

    @Test fun canonicalRemoteOnlyEditsRemainAndDoNotBecomeSuccessorLocalChanges() {
        val first = operation(metric())
        val second = operation(change(metric(), "name", JsonPrimitive("local")), secondId)
        val server = change(metric(), "description", JsonPrimitive("remote"))
        val result = NextStructuralRebase.merge(first, second, accepted(first, server), replacement)
        assertEquals(JsonPrimitive("local"), result.payload["name"])
        assertEquals(JsonPrimitive("remote"), result.payload["description"])
        assertEquals(JsonPrimitive("original"), second.payload["description"])
        assertEquals(JsonPrimitive("weight"), first.payload["name"])
    }

    @Test fun trueOverlappingDifferencesStopInsteadOfSilentlyPreferringLocal() {
        val first = operation(metric())
        val second = operation(change(metric(), "description", JsonPrimitive("local")), secondId)
        val server = change(metric(), "description", JsonPrimitive("remote"))
        val failure = assertThrows(NextStructuralCausalConflict::class.java) {
            NextStructuralRebase.merge(first, second, accepted(first, server), replacement)
        }
        assertEquals(listOf("description"), failure.fields)
        assertEquals(JsonPrimitive("local"), second.payload["description"])
        assertEquals(JsonPrimitive("remote"), server["description"])
    }

    @Test fun equalOverlappingValuesAndNoOpEditsKeepCanonicalValues() {
        val first = operation(metric(), base = 4)
        val server = change(metric(), "description", JsonPrimitive("same"))
        for (body in listOf(metric(), server)) {
            val result = NextStructuralRebase.merge(first, operation(body, secondId, base = 4),
                accepted(first, server, 4), replacement)
            assertEquals(4L, result.baseRevision)
            assertEquals(server, result.payload)
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun decimalTextNumbersAndExponentsCannotFabricateConflictingChangesOrLoseCanonicalPrecision() {
        val first = operation(metric())
        val variants = listOf(JsonPrimitive("10.1250"), JsonPrimitive(10.125), JsonUnquotedLiteral("1.0125e1"))
        val server = change(metric(), "target_value", JsonPrimitive("11.000"))
        for (value in variants) {
            val second = operation(change(metric(), "target_value", value), secondId)
            assertEquals(JsonPrimitive("11.000"), NextStructuralRebase.merge(first, second,
                accepted(first, server), replacement).payload["target_value"])
        }
        val second = operation(change(metric(), "target_direction", JsonNull), secondId)
        assertEquals(JsonNull, NextStructuralRebase.merge(first, second, accepted(first), replacement).payload["target_direction"])
    }

    @Test fun threeSuccessorsUseOriginalLocalBaselinesButTheActuallySubmittedReplacementReceipt() {
        val first = operation(metric())
        val second = operation(change(metric(), "name", JsonPrimitive("second")), secondId)
        val third = operation(change(second.payload, "name", JsonPrimitive("third")), thirdId)
        val canonicalFirst = change(metric(), "description", JsonPrimitive("remote-only"))
        val secondSubmission = NextStructuralRebase.merge(first, second, accepted(first, canonicalFirst), replacement)
        val final = NextStructuralRebase.merge(second, third, accepted(secondSubmission, revision = 2),
            nextReplacement, submittedPredecessor = secondSubmission)
        assertEquals(nextReplacement, final.operationId); assertEquals(2L, final.baseRevision)
        assertEquals(JsonPrimitive("third"), final.payload["name"])
        assertEquals(JsonPrimitive("remote-only"), final.payload["description"])
        assertEquals(JsonPrimitive("original"), third.payload["description"])
        assertThrows(IllegalArgumentException::class.java) {
            NextStructuralRebase.merge(second, third, accepted(secondSubmission, revision = 2), nextReplacement)
        }
    }

    @Test fun planRecurrenceAndFailurePolicyAreAtomicAndCannotMergeTheirSubfields() {
        val first = operation(plan(), type = "plan_node")
        val interval = Json.parseToJsonElement("""{"schema_version":1,"type":"daily","interval":1,"start_date":"2026-01-01"}""")
        val local = operation(change(plan(), "activity.recurrence_rule", interval), secondId, "plan_node")
        val remote = change(plan(), "activity.recurrence_rule", Json.parseToJsonElement(
            """{"schema_version":1,"type":"weekly","interval":1,"start_date":null,"weekdays":[1,3]}"""))
        assertEquals(listOf("activity.recurrence_rule"), assertThrows(NextStructuralCausalConflict::class.java) {
            NextStructuralRebase.merge(first, local, accepted(first, remote), replacement)
        }.fields)
        val failure = change(plan(), "activity.failure_policy", Json.parseToJsonElement("""{"schema_version":1,"type":"strict"}"""))
        val result = NextStructuralRebase.merge(first, operation(failure, secondId, "plan_node"), accepted(first), replacement)
        assertEquals(failure["activity"], result.payload["activity"])
    }

    @Test fun independentValidMetricRangesAndGoalDatesCannotProduceAnInvalidCombinedWrite() {
        val first = operation(metric())
        val local = operation(change(metric(), "target_value", JsonPrimitive("19")), secondId)
        val remote = change(metric(), "target_value_upper", JsonPrimitive("18"))
        assertThrows(IllegalArgumentException::class.java) { NextStructuralRebase.merge(first, local, accepted(first, remote), replacement) }
        val baseGoal = goal()
        val goalOp = operation(baseGoal, type = "plan_node")
        val localGoal = operation(change(baseGoal, "goal.start_date", JsonPrimitive("2026-11-01")), secondId, "plan_node")
        val remoteGoal = change(baseGoal, "goal.due_date", JsonPrimitive("2026-10-01"))
        assertThrows(IllegalArgumentException::class.java) {
            NextStructuralRebase.merge(goalOp, localGoal, accepted(goalOp, remoteGoal), replacement)
        }
    }

    @Test fun immutablePlanIdentityKindAndCreationTimeCannotChange() {
        val first = operation(plan(), type = "plan_node")
        val second = operation(plan(), secondId, "plan_node")
        assertThrows(IllegalArgumentException::class.java) {
            NextStructuralRebase.merge(first, second.copy(entityUuid = endpoint), accepted(first), replacement)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NextStructuralRebase.merge(first, operation(goal(), secondId, "plan_node"), accepted(first), replacement)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NextStructuralRebase.merge(first, operation(change(plan(), "created_at", JsonPrimitive(stamp)), secondId, "plan_node"), accepted(first), replacement)
        }
        val alternateText = change(plan(), "created_at", JsonPrimitive("2026-09-27T23:59:59.123456+08:00"))
        assertEquals(JsonPrimitive(created), NextStructuralRebase.merge(first,
            operation(alternateText, secondId, "plan_node"), accepted(first), replacement).payload["created_at"])
    }

    @Test fun linkFlagsAndCoefficientsMergeWithoutInventingOrChangingEndpointIds() {
        val first = operation(link(), type = "activity_metric_link")
        val second = operation(change(link(), "prompt_on_complete", JsonPrimitive(false)), secondId, "activity_metric_link")
        val server = change(link(), "coefficient", JsonPrimitive("1.500"))
        val result = NextStructuralRebase.merge(first, second, accepted(first, server), replacement)
        assertEquals(JsonPrimitive(false), result.payload["prompt_on_complete"])
        assertEquals(JsonPrimitive("1.500"), result.payload["coefficient"])
        assertEquals(JsonPrimitive(entity), result.payload["activity_uuid"])
        assertEquals(JsonPrimitive(endpoint), result.payload["metric_uuid"])
        val otherEndpoint = change(link(), "metric_uuid", JsonPrimitive(entity))
        assertThrows(IllegalArgumentException::class.java) {
            NextStructuralRebase.merge(first, operation(otherEndpoint, secondId, "activity_metric_link"), accepted(first), replacement)
        }
    }

    @Test fun onlyCompleteSuccessfulBoundCanonicalResultsCanSupplyTheNewBase() {
        val first = operation(metric(), base = 7)
        val second = operation(metric(), secondId, base = 7)
        val valid = accepted(first, revision = 8)
        val bad = listOf(valid.copy(operationId = thirdId), valid.copy(entityUuid = endpoint),
            valid.copy(entityType = "plan_node"), valid.copy(status = "rejected"), valid.copy(status = "conflict"),
            valid.copy(revision = null), valid.copy(revision = 0), valid.copy(revision = 6), valid.copy(entity = null),
            valid.copy(errorCode = "INVALID_PAYLOAD"), valid.copy(message = "unexpected"),
            valid.copy(baseEntity = metric()), valid.copy(localEntity = metric()),
            valid.copy(conflictingFields = listOf("name")), valid.copy(conflictKind = "overlapping_fields"),
            valid.copy(entity = change(valid.entity!!, "deleted_at", JsonPrimitive(stamp))),
            valid.copy(entity = change(valid.entity!!, "revision", JsonPrimitive("8"))),
            valid.copy(entity = change(valid.entity!!, "extra", JsonPrimitive(true))))
        for (confirmation in bad) assertTrue(confirmation.toString(),
            runCatching { NextStructuralRebase.merge(first, second, confirmation, replacement) }.isFailure)
        for (id in listOf(firstId, secondId, "bad-id")) assertThrows(IllegalArgumentException::class.java) {
            NextStructuralRebase.merge(first, second, valid, id)
        }
    }

    @Test fun factsTimerOnceStatesMetricDeletesAndUnknownFieldsAreNeverAdoptedByStructuralRebasing() {
        val first = operation(metric())
        for (type in listOf("activity_event", "metric_observation", "timer_command", "unknown")) {
            val a = first.copy(entityType = type)
            assertTrue(runCatching { NextStructuralRebase.merge(a, a.copy(operationId = secondId), accepted(a), replacement) }.isFailure)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NextStructuralRebase.merge(first, first.copy(operationId = secondId, action = "delete"), accepted(first), replacement)
        }
        assertTrue(runCatching { NextStructuralRebase.merge(first,
            operation(change(metric(), "unknown", JsonNull), secondId), accepted(first), replacement) }.isFailure)
        val once = change(change(plan(), "activity.completion_policy", JsonPrimitive("one_and_done")),
            "activity.recurrence_rule", Json.parseToJsonElement("""{"schema_version":1,"type":"once","due_date":null}"""))
        val onceOp = operation(once, type = "plan_node")
        val successor = onceOp.copy(operationId = secondId)
        assertEquals(successor.payload, NextStructuralRebase.merge(onceOp, successor, accepted(onceOp), replacement).payload)
        // Stateless once structure may advance; a completion projection is never a writable field.
        for (field in listOf("one_time_state_after", "one_time_completion", "completion_event_uuid")) {
            val stateWrite = successor.copy(payload = change(once, field, buildJsonObject {}))
            assertTrue(runCatching { NextStructuralRebase.merge(onceOp, stateWrite, accepted(onceOp), replacement) }.isFailure)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NextStructuralRebase.merge(first, first.copy(operationId = secondId),
                accepted(first).copy(entity = change(accepted(first).entity!!, "one_time_state_after", buildJsonObject {})), replacement)
        }
    }

    @Test fun explicitPlanAndLinkDeletesUseOnlyTheirOwnBoundLivePredecessorAndKeepExactPolicy() {
        for (body in listOf(plan(), goal(), link())) {
            val type = if (body.containsKey("node_kind")) "plan_node" else "activity_metric_link"
            val first = operation(body, type = type)
            val payload = if (body["node_kind"] == JsonPrimitive("goal"))
                buildJsonObject { put("child_policy", "detach_children") } else buildJsonObject {}
            val delete = first.copy(operationId = secondId, action = "delete", payload = payload)
            val result = NextStructuralRebase.merge(first, delete, accepted(first, revision = 8), replacement)
            assertEquals(replacement, result.operationId); assertEquals(8L, result.baseRevision)
            assertEquals(payload, result.payload); assertEquals(null, delete.baseRevision)
            assertTrue(runCatching { NextStructuralRebase.merge(first, delete,
                accepted(first).copy(entity = change(accepted(first).entity!!, "deleted_at", JsonPrimitive(stamp))), replacement) }.isFailure)
            assertTrue(runCatching { NextStructuralRebase.merge(first, delete.copy(payload = buildJsonObject {
                put("unknown", true)
            }), accepted(first), replacement) }.isFailure)
            val key = if (type == "plan_node") "created_at" else "metric_uuid"
            val changed = change(body, key, JsonPrimitive(if (type == "plan_node") stamp else thirdId))
            val changedResult = accepted(first).copy(entity = change(accepted(first).entity!!, key, changed.getValue(key)))
            assertTrue(runCatching { NextStructuralRebase.merge(first, delete, changedResult, replacement) }.isFailure)
            assertTrue(runCatching { NextStructuralRebase.merge(first, delete, changedResult, replacement,
                first.copy(payload = changed)) }.isFailure)
        }
    }
}
