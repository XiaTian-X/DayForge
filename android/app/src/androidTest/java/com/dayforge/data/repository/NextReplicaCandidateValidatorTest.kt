package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Private candidate Room is real Android SQLite; active physical Room/preferences stay untouched. */
@RunWith(AndroidJUnit4::class)
class NextReplicaCandidateValidatorTest : NextCoreRequestFixture() {
    private fun validator() = NextReplicaCandidateValidator(app, tokens, sessions, preferences)
    private fun canonical(kind: String, identity: String, body: JsonObject) = SyncV2Change(0, kind, identity, "upsert", 1,
        JsonObject(body + mapOf("public_id" to JsonPrimitive(identity), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun snapshot(changes: List<SyncV2Change> = listOf(
        canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
        canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
        canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))),
        once: List<OneTimeProjection> = emptyList(), extraBirths: List<ChallengeBirth> = emptyList()) =
        RoundSyncBootstrapResponse(changes, 20, time, once, 1, listOf(habit.uuid, timerHabit.uuid).map { activity ->
            val head = initialChallengeRoundHead(activity)
            ChallengeCheckpoint(head, listOf(ChallengeRoundRecord(head, null, null, null)))
        }, extraBirths)
    private suspend fun response(snapshot: RoundSyncBootstrapResponse = snapshot(), timer: TimerSessionResponse? = null) =
        ReplicaRecoveryPreviewResponse(1, 5, id(1), id(2), id(99),
            RecoveryDeviceProof(id(4), tokens.getOrCreateInstallationId(), "android", "interactive", null, null,
                false, false, 1, listOf("sync.read", "facts.append"), time), snapshot, timer)
    private suspend fun validate(response: ReplicaRecoveryPreviewResponse) =
        validator().validate(access(), response.device.installationId, response)
    private suspend fun unchanged(block: suspend () -> Unit) {
        val before = storage.snapshotClosedDatabase(); val prefs = dataStore.data.first(); val authority = access()
        block()
        assertArrayEquals(before, storage.snapshotClosedDatabase()); assertEquals(prefs, dataStore.data.first())
        assertEquals(authority, access()); assertSame(db, com.dayforge.data.local.HabitDatabaseProvider.getInstance(app))
    }

    @Test fun completeGraphValidatesWithoutReplacingActiveRoomOrPublishingNewRights() = runBlocking<Unit> {
        register(); val value = response()
        unchanged {
            val candidate = validate(value)
            assertFalse(candidate.activationAuthorized); assertFalse(candidate.hasUnfinishedTimer)
            assertEquals(id(3), candidate.original.session.syncEpoch); assertEquals(id(99), candidate.newEpoch)
            assertEquals(20L, candidate.cursor); assertEquals(5, candidate.records.size)
            assertEquals("Weight", candidate.records.getValue(ReplicaRecordKey("metric", metric.uuid)).jsonObject["name"]!!.jsonPrimitive.content)
            assertTrue(runCatching { (candidate.records as MutableMap<*, *>).clear() }.isFailure)
            assertFalse(candidate.records.toString().contains("synthetic-first"))
        }
    }

    @Test fun domainMalformedMetricAndMissingLinkEndpointAreRejectedWithNoActiveWrites() = runBlocking<Unit> {
        register()
        val malformed = snapshot().changes.map { if (it.entityType == "metric")
            it.copy(payload = JsonObject(it.payload + ("decimal_places" to JsonPrimitive(100)))) else it }
        val link = canonical("activity_metric_link", id(44), buildJsonObject {
            put("activity_uuid", habit.uuid); put("metric_uuid", id(45)); put("coefficient", "1.0")
            put("show_in_activity_detail", true); put("prompt_on_complete", false); put("is_active", true)
        })
        for (candidate in listOf(response(snapshot(malformed)), response(snapshot(snapshot().changes + link)))) {
            unchanged { rejected { validate(candidate) } }
        }
    }

    @Test fun completedOnceHistoryAndCheckpointArePreservedInFullContents() = runBlocking<Unit> {
        register()
        val once = habit.copy(uuid = id(50), name = "Once item", habitType = HabitType.CHECK_IN, targetValue = 1,
            schedule = HabitSchedule.Once(), targetCycles = null, failMode = FailMode.LOOSE,
            completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
            appearance = requireNotNull(habit.appearance).copy(icon = IconReference.Role("task.default")))
        val event = buildJsonObject {
            put("activity_uuid", once.uuid); put("event_type", "check_in"); put("value", "1.0")
            put("occurred_at", time); put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai")
            put("source_type", "app"); put("source_device_id", id(4)); put("note", "original note")
            put("received_at", time); put("metadata", buildJsonObject {})
            listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "external_event_id", "reverts_event_uuid").forEach { put(it, JsonNull) }
            put("one_time", buildJsonObject {
                put("event_uuid", id(51)); put("action", "complete"); put("expected_version", 0)
                put("expected_head_event_uuid", JsonNull); put("reverts_event_uuid", JsonNull)
            })
            put("one_time_state_after", buildJsonObject {
                put("version", 1); put("head_event_uuid", id(51)); put("completion_event_uuid", id(51))
            })
        }
        val value = response(snapshot(snapshot().changes + listOf(
            canonical("plan_node", once.uuid, NextStructureMapper.writePlan(once)), canonical("activity_event", id(51), event)),
            listOf(OneTimeProjection(once.uuid, OneTimeState(1, id(51), id(51))))))
        unchanged {
            val candidate = validate(value)
            assertEquals("original note", candidate.records.getValue(ReplicaRecordKey("activity_event", id(51))).jsonObject["note"]!!.jsonPrimitive.content)
            assertEquals(1, candidate.records.getValue(ReplicaRecordKey("one_time_checkpoint", once.uuid)).jsonObject["state"]!!.jsonObject["version"]!!.jsonPrimitive.int)
            assertNull(db.habitDao().getHabitByUuid(once.uuid))
        }
    }

    @Test fun unfinishedServerTimerIsReportedNotMaterializedAsCompletionOrPermission() = runBlocking<Unit> {
        register()
        val timer = TimerSessionResponse(id(60), timerHabit.uuid, "paused", id(61), 1, 2, 3,
            time, time, null, "Asia/Shanghai", false, 1, 180, 500, time, null)
        val value = response(snapshot(extraBirths = listOf(ChallengeBirth("timer_session", id(60), initialChallengeRoundHead(timerHabit.uuid)))), timer)
        unchanged {
            val candidate = validate(value)
            assertTrue(candidate.hasUnfinishedTimer); assertFalse(candidate.activationAuthorized)
            assertEquals("paused", candidate.records.getValue(ReplicaRecordKey("active_timer", id(60))).jsonObject["state"]!!.jsonPrimitive.content)
            assertTrue(db.timeLogDao().getAllTimeLogsForHabit(timerHabit.id).isEmpty())
        }
        val corrupt = value.copy(activeTimer = timer.copy(activeElapsedMs = 180_001))
        unchanged { rejected { validate(corrupt) } }
    }

    @Test fun callerMutationAfterDtoConstructionStillGetsCompleteDomainValidation() = runBlocking<Unit> {
        register()
        val changes = snapshot().changes.toMutableList()
        val value = response(snapshot(changes))
        val metricIndex = changes.indexOfFirst { it.entityType == "metric" }
        val metricChange = changes[metricIndex]
        changes[metricIndex] = metricChange.copy(payload = JsonObject(metricChange.payload + ("unit" to JsonPrimitive(""))))
        unchanged { rejected { validate(value) } }
    }

    @Test fun staleAuthenticationOtherAccountServerDeviceAndInstallationCannotValidateCandidate() = runBlocking<Unit> {
        register(); val original = access(); val value = response()
        for (wrong in listOf(value.copy(accountId = id(80)), value.copy(serverInstanceId = id(81)),
            value.copy(syncEpoch = id(3)), value.copy(device = value.device.copy(deviceId = id(82))),
            value.copy(device = value.device.copy(installationId = id(83))))) {
            unchanged { rejected { validate(wrong) } }
        }
        tokens.saveLoginSession("synthetic-fresh", "synthetic-refresh", "member", id(1), false)
        unchanged { rejected { validator().validate(original, value.device.installationId, value) } }
    }
}
