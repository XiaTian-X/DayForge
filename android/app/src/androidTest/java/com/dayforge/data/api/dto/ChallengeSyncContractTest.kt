package com.dayforge.data.api.dto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Shared portable wire, original identities and complete lineage; not server authority. */
@RunWith(AndroidJUnit4::class)
class ChallengeSyncContractTest {
    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open("next/$name.json")
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    private val wire by lazy { fixture("challenge-sync") }
    private val rounds by lazy { fixture("challenge-rounds") }
    private val json = Json { encodeDefaults = true }
    private fun request(key: String) = Json.decodeFromJsonElement<RoundSyncPushRequest>(wire.getValue(key))
    private fun head(index: Int) = Json.decodeFromJsonElement<ChallengeRoundHead>(rounds.getValue("heads").jsonArray[index])
    private fun record(index: Int) = Json.decodeFromJsonElement<ChallengeRoundRecord>(rounds.getValue("records").jsonArray[index])
    private fun metadata() = ChallengeMetadata(1, listOf(ChallengeCheckpoint(head(1), listOf(record(0), record(1)))),
        listOf(ChallengeBirth("activity_event", request("fact_request").operations.single().entityUuid, head(1))))
    private fun replace(raw: JsonObject, key: String, value: JsonElement) = JsonObject(raw + (key to value))
    private fun invalid(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java) { block() } }

    @Test fun incrementalRecurringPlanAndDurationFactRequireCompleteRoundAttribution() {
        val plan = SyncV2Change(1, "plan_node", head(0).activityUuid, "upsert", 1, buildJsonObject {
            put("node_kind", "activity"); put("activity", buildJsonObject { put("completion_policy", "recurring") })
        }, "2026-10-08T00:00:00Z")
        invalid { RoundSyncPullResponse(listOf(plan), 1, false, plan.changedAt, 1, emptyList(), emptyList()) }
        val event = SyncV2Change(1, "activity_event", head(2).roundUuid, "upsert", 1, buildJsonObject {
            put("activity_uuid", head(0).activityUuid); put("event_type", "duration_session")
            put("metadata", buildJsonObject { put("timer_session_id", head(3).roundUuid) })
        }, plan.changedAt)
        val history = metadata().checkpoints
        val birth = ChallengeBirth("activity_event", event.entityUuid, head(1))
        invalid { RoundSyncPullResponse(listOf(event), 1, false, event.changedAt, 1, history, listOf(birth)) }
        invalid { RoundSyncPullResponse(listOf(event), 1, false, event.changedAt, 1, history,
            listOf(birth, ChallengeBirth("timer_session", head(3).roundUuid, head(0)))) }
    }

    @Test fun sharedRequestsRoundTripExactlyAndLeaveOriginalObjectsUnchanged() {
        for (key in listOf("restart_request", "fact_request")) {
            val original = wire.getValue(key)
            val typed = request(key)
            assertEquals(original, json.encodeToJsonElement(typed))
            assertEquals(original.jsonObject.getValue("operations"), json.encodeToJsonElement(typed.operations))
            assertEquals(original, wire.getValue(key))
        }
        val original = wire.getValue("timer_request")
        val typed = Json.decodeFromJsonElement<RoundTimerCommandBatchRequest>(original)
        assertEquals(original, json.encodeToJsonElement(typed))
        assertEquals(original.jsonObject.getValue("commands"), json.encodeToJsonElement(typed.commands))
        typed.commands.single().startPolicy!!.validate()
        assertEquals(original, wire.getValue("timer_request"))
    }

    @Test fun explicitProfileRejectsMissingCoercibleUnknownAndOutOfRangeMarkers() {
        val push = wire.getValue("fact_request").jsonObject
        val timer = wire.getValue("timer_request").jsonObject
        for (value in listOf(JsonPrimitive("1"), JsonPrimitive(1.0), JsonPrimitive(true), JsonPrimitive(0), JsonPrimitive(2), JsonNull)) {
            invalid { Json.decodeFromJsonElement<RoundSyncPushRequest>(replace(push, "challenge_contract", value)) }
            invalid { Json.decodeFromJsonElement<RoundTimerCommandBatchRequest>(replace(timer, "challenge_contract", value)) }
            invalid { Json.decodeFromJsonElement<ChallengeMetadata>(replace(json.encodeToJsonElement(metadata()).jsonObject,
                "challenge_contract", value)) }
        }
        invalid { Json.decodeFromJsonElement<RoundSyncPushRequest>(JsonObject(push - "challenge_contract")) }
        invalid { Json.decodeFromJsonElement<RoundSyncPushRequest>(replace(push, "extra", JsonPrimitive(true))) }
        invalid { Json.decodeFromJsonElement<NextSyncPushRequest>(push) }
    }

    @Test fun everyOriginalSourceRequiresExactlyOneContextAndUniqueAffectedActivities() {
        val body = request("fact_request")
        val source = body.contexts.single()
        invalid { body.copy(contexts = emptyList()) }
        invalid { body.copy(contexts = listOf(source, source)) }
        invalid { body.copy(operations = body.operations + body.operations) }
        invalid { body.copy(contexts = listOf(source.copy(sourceUuid = head(0).activityUuid))) }
        invalid { source.copy(affectedHeads = listOf(head(0), head(1))) }
        invalid { source.copy(legacyInitial = true) }
        invalid { source.copy(head = head(0), legacyInitial = true, affectedHeads = listOf(head(1))) }
        invalid { source.copy(affectedHeads = List(1001) { head(0) }) }
        val legacy = source.copy(head = head(0), legacyInitial = true)
        assertEquals(legacy, Json.decodeFromJsonElement<ChallengeSourceContext>(json.encodeToJsonElement(legacy)))
        invalid { Json.decodeFromJsonElement<ChallengeSourceContext>(replace(json.encodeToJsonElement(legacy).jsonObject,
            "legacy_initial", JsonPrimitive("true"))) }
    }

    @Test fun restartCannotUseDeleteMergeDifferentIdentityOrAnExternalBirth() {
        val body = request("restart_request")
        val operation = body.operations.single()
        for (changed in listOf(operation.copy(action = "delete"), operation.copy(baseRevision = 1),
                operation.copy(entityUuid = head(2).roundUuid))) invalid { body.copy(operations = listOf(changed)) }
        invalid { body.copy(contexts = listOf(body.contexts.single().copy(head = head(0)))) }
        invalid { body.copy(contexts = listOf(body.contexts.single().copy(legacyInitial = true))) }
        invalid { body.copy(contexts = listOf(body.contexts.single().copy(affectedHeads = listOf(head(0))))) }
        invalid { body.copy(operations = listOf(operation.copy(entityType = "unregistered"))) }
        invalid { body.copy(operations = listOf(operation.copy(payload = JsonObject(operation.payload + ("owner_id" to JsonPrimitive("fake")))))) }
    }

    @Test fun metadataRequiresCompleteUniqueHistoryAndOriginalBirthsNotJustTheCurrentHead() {
        val original = metadata()
        assertEquals(original, Json.decodeFromJsonElement<ChallengeMetadata>(json.encodeToJsonElement(original)))
        invalid { ChallengeCheckpoint(head(1), listOf(record(1))) }
        invalid { original.copy(checkpoints = original.checkpoints + original.checkpoints) }
        invalid { original.copy(births = original.births + original.births) }
        invalid { original.copy(births = listOf(original.births.single().copy(head = head(2)))) }
        invalid { original.copy(births = listOf(original.births.single().copy(head = head(5)))) }
        invalid { original.births.single().copy(entityType = "plan_node") }
        val old = original.copy(births = listOf(original.births.single().copy(head = head(0))))
        assertEquals(head(0), old.births.single().head)
        assertEquals(head(1), old.checkpoints.single().head)
    }

    @Test fun successfulRestartMustProveOriginalIntentDeviceOperationAndRevision() {
        val body = request("restart_request")
        val operation = body.operations.single()
        val result = NextSyncOperationResult(operation.operationId, "challenge_round", operation.entityUuid, "applied", 1,
            entity = json.encodeToJsonElement(record(1)).jsonObject)
        val response = RoundSyncPushResponse(listOf(result), 1, metadata().checkpoints, emptyList())
        validateRoundResultBinding(body, response)
        invalid { response.copy(results = listOf(result.copy(revision = 2))) }
        invalid { response.copy(results = listOf(result.copy(operationId = head(0).activityUuid))) }
        invalid { validateRoundResultBinding(body.copy(deviceId = head(0).activityUuid), response) }
        val intent = Json.decodeFromJsonElement<ChallengeRestartIntent>(operation.payload)
        invalid { validateRoundResultBinding(body.copy(operations = listOf(operation.copy(payload =
            json.encodeToJsonElement(intent.copy(expectedPlanRevision = 4)).jsonObject))), response) }
        invalid { validateRoundResultBinding(body, response.copy(results = listOf(result, result))) }
    }

    @Test fun successfulFactRequiresItsOriginalHeadAndFrozenCountPolicy() {
        val body = request("fact_request")
        val operation = body.operations.single()
        val result = NextSyncOperationResult(operation.operationId, "activity_event", operation.entityUuid, "applied", 1,
            entity = operation.payload)
        val response = RoundSyncPushResponse(listOf(result), 1, metadata().checkpoints, metadata().births)
        validateRoundResultBinding(body, response)
        invalid { response.copy(checkpoints = emptyList(), births = emptyList()) }
        invalid { response.copy(births = emptyList()) }
        invalid { validateRoundResultBinding(body.copy(contexts = listOf(body.contexts.single().copy(head = head(0)))), response) }
        val policy = CountDayPolicy(4, true).toJson()
        val counted = operation.copy(payload = JsonObject(operation.payload + mapOf("event_type" to JsonPrimitive("count_delta"), "count_policy" to policy)))
        val countedResponse = response.copy(results = listOf(result.copy(entity = counted.payload)))
        validateRoundResultBinding(body.copy(operations = listOf(counted)), countedResponse)
        invalid { validateRoundResultBinding(body.copy(operations = listOf(counted)), response) }
        invalid { countedResponse.copy(results = listOf(result.copy(entity = JsonObject(counted.payload +
            ("count_policy" to CountDayPolicy(5, true).toJson()))))).let { validateRoundResultBinding(body.copy(operations = listOf(counted)), it) } }
        invalid { response.copy(results = listOf(result.copy(entity = JsonObject(operation.payload + ("count_policy" to policy))))) }
    }

    @Test fun changesBindImmutableCreationSourceAndRecurringBirthWithoutRollingBackMetadata() {
        val checkpoint = metadata().checkpoints.single()
        val change = SyncV2Change(4, "challenge_round", record(1).head.roundUuid, "upsert", 1,
            json.encodeToJsonElement(record(1)).jsonObject, "2026-10-08T00:00:00Z", record(1).sourceDeviceUuid)
        val response = RoundSyncPullResponse(listOf(change), 4, false, change.changedAt, 1, listOf(checkpoint), emptyList())
        invalid { response.copy(changes = listOf(change.copy(operation = "delete"))) }
        invalid { response.copy(changes = listOf(change.copy(revision = 2))) }
        invalid { response.copy(changes = listOf(change.copy(originDeviceId = head(0).activityUuid))) }
        val fact = request("fact_request").operations.single()
        val factChange = change.copy(entityType = "activity_event", entityUuid = fact.entityUuid, payload = fact.payload)
        invalid { response.copy(changes = listOf(factChange)) }
        val oldBirth = metadata().births.single().copy(head = head(0))
        assertEquals(head(1), response.copy(changes = listOf(factChange), births = listOf(oldBirth)).checkpoints.single().head)
    }

    @Test fun emptyResponsesStillRequireAValidExplicitProfileAndCompleteMetadata() {
        invalid { RoundTimerCommandBatchResponse(emptyList(), "2026-10-08T00:00:00Z", 0, emptyList(), emptyList()) }
        invalid { RoundActiveTimerResponse(null, "2026-10-08T00:00:00Z", 0, emptyList(), emptyList()) }
        invalid { RoundSyncPushResponse(emptyList(), 0, emptyList(), emptyList()) }
        assertEquals(emptyList<ChallengeBirth>(), RoundTimerCommandBatchResponse(emptyList(), "2026-10-08T00:00:00Z", 1,
            emptyList(), emptyList()).births)
    }

    private fun timerBody() = Json.decodeFromJsonElement<RoundTimerCommandBatchRequest>(wire.getValue("timer_request"))
    private fun timer(state: String = "running", completed: String? = null): TimerSessionResponse {
        val command = timerBody().commands.single()
        return TimerSessionResponse(command.sessionId, command.activityUuid!!, state, timerBody().deviceId, 1, 1, 2,
            command.occurredAt, command.occurredAt, timezone = command.timezone!!, isCountdown = true,
            targetSeconds = 60, maxDurationSeconds = 60, activeElapsedMs = 0, completedEventId = completed)
    }
    private fun timerResponse(session: TimerSessionResponse?) = RoundTimerCommandBatchResponse(
        listOf(TimerCommandResult(timerBody().commands.single().commandId, timerBody().commands.single().sessionId, "applied", session = session)),
        "2026-10-08T00:00:00Z", 1, metadata().checkpoints,
        listOf(ChallengeBirth("timer_session", timerBody().commands.single().sessionId, head(1))))

    @Test fun successfulTimerAcknowledgementsRequireOriginalBirthSessionAndStartPolicy() {
        val body = timerBody()
        val response = timerResponse(timer())
        validateRoundTimerBinding(body, response)
        invalid { validateRoundTimerBinding(body, timerResponse(null)) }
        invalid { validateRoundTimerBinding(body, timerResponse(timer().copy(targetSeconds = 61))) }
        invalid { validateRoundTimerBinding(body, timerResponse(timer().copy(isCountdown = false))) }
        invalid { validateRoundTimerBinding(body, timerResponse(timer().copy(maxDurationSeconds = 180))) }
        invalid { validateRoundTimerBinding(body, timerResponse(timer().copy(activityUuid = head(5).activityUuid))) }
        invalid { validateRoundTimerBinding(body.copy(contexts = listOf(body.contexts.single().copy(head = head(0)))), response) }
        invalid { validateRoundTimerBinding(body, response.copy(results = listOf(response.results.single().copy(errorCode = "CONTROL_LOST")))) }
        // A real conflict can report the server's different birth without claiming success.
        val conflict = response.copy(results = listOf(response.results.single().copy(status = "conflict", errorCode = "CHALLENGE_BINDING_CONFLICT")),
            births = listOf(response.births.single().copy(head = head(0))))
        validateRoundTimerBinding(body, conflict)
    }

    @Test fun completedFactMustInheritTheProvedSessionBirthNotCurrentOrMissingHead() {
        val session = timer("completed", timerBody().commands.single().sessionId)
        invalid { timerResponse(session) }
        val eventBirth = ChallengeBirth("activity_event", session.completedEventId!!, head(1))
        val completed = timerResponse(timer()).copy(results = listOf(timerResponse(timer()).results.single().copy(session = session)),
            births = timerResponse(timer()).births + eventBirth)
        assertEquals(head(1), completed.metadata().requireBirth("activity_event", session.completedEventId, session.activityUuid).head)
        invalid { completed.copy(births = listOf(completed.births.first(), eventBirth.copy(head = head(0)))) }
        invalid { RoundActiveTimerResponse(session, completed.serverTime, 1, completed.checkpoints, emptyList()) }
        assertEquals(session, RoundActiveTimerResponse(session, completed.serverTime, 1, completed.checkpoints, completed.births).session)
    }

    @Test fun malformedCountProofAndOnceSubstitutionCannotBeAcceptedAsRecurringFacts() {
        val body = request("fact_request")
        val operation = body.operations.single()
        val result = NextSyncOperationResult(operation.operationId, "activity_event", operation.entityUuid, "applied", 1, entity = operation.payload)
        val response = RoundSyncPushResponse(listOf(result), 1, metadata().checkpoints, metadata().births)
        for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive("policy"), JsonArray(emptyList()), buildJsonObject { put("target_value", 4) })) {
            invalid { response.copy(results = listOf(result.copy(entity = JsonObject(operation.payload + mapOf(
                "event_type" to JsonPrimitive("count_delta"), "count_policy" to value))))) }
        }
        // The original recurring result cannot be reinterpreted as an immutable once intent.
        val once = fixture("api").getValue("bootstrap").jsonObject.getValue("changes").jsonArray.last().jsonObject.getValue("payload").jsonObject
        val disguised = Json.parseToJsonElement(once.toString()
            .replace(once.getValue("public_id").jsonPrimitive.content, operation.entityUuid)
            .replace(once.getValue("activity_uuid").jsonPrimitive.content, operation.payload.getValue("activity_uuid").jsonPrimitive.content)).jsonObject
        invalid { response.copy(results = listOf(result.copy(entity = disguised))).let { validateRoundResultBinding(body, it) } }
    }
}
