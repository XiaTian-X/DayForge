package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The real formal SyncManager route owns transport, ACK and PULL; no direct acceptance shortcut. */
@RunWith(AndroidJUnit4::class)
class IndependentMetricFormalChainTest : CountCommandFormalUndoFixture() {
    override fun additionalStructuralReply(request: MaterialSocketServer.Input,
        wire: RoundSyncPushRequest): MaterialSocketServer.Reply {
        val operation = wire.operations.single()
        assertEquals("metric_observation", operation.entityType)
        assertEquals(operation.operationId, wire.contexts.single().sourceUuid)
        assertNull(wire.contexts.single().head) // standalone metric never fabricates a habit/round parent
        val result = events[operation.operationId]?.copy(status = "already_applied") ?: run {
            val received = Instant.now().toString()
            val response = successReply(request) { value -> JsonObject(value + mapOf(
                "created_at" to JsonPrimitive(received), "updated_at" to JsonPrimitive(received),
                "received_at" to JsonPrimitive(received))) }
            json.decodeFromString<NextSyncPushResponse>(response.bytes.toString(Charsets.UTF_8)).results.single().also {
                events[operation.operationId] = it
                changes += SyncV2Change(++cursor, operation.entityType, operation.entityUuid, "upsert", 1,
                    requireNotNull(it.entity), received, id(4))
            }
        }
        val meta = metadata()
        return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(listOf(result), 1,
            meta.checkpoints, meta.births)).toByteArray())
    }

    @Test(timeout = 150_000) fun standaloneFormalValueHasNoHabitBirthAndSurvivesRealAckPullColdDelivery() = runBlocking<Unit> {
        openRounds()
        val original = editingMetrics().getMetricForEditing(metric.id)
        val beforeBirths = count("next_challenge_births")
        val attempt = editingMetrics().beginIndependentValue(metric.id, 16.75, "formal standalone", millis,
            original.authority, metric.uuid)
        val saved = editingMetrics().executeIndependentValue(attempt)
        val reference = requireNotNull(saved.reference)
        val queued = db.syncOutboxDao().getAll().single()
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, queued.operationId))
        val wire = json.decodeFromString<NextRoundOperationIntent>(origin.intentJson)
        assertEquals(saved.factUuid, wire.operation.entityUuid); assertNull(wire.context.head)
        assertEquals("metric_observation", wire.operation.entityType)
        assertEquals(CountCommandEvidence.Profile.METRIC_ROUND_V5, row(reference.native()).outcome!!.evidence.profile)
        val outcome = row(reference.native()).outcome!!.text()
        syncAll()
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, queued.operationId))
        assertEquals(beforeBirths, count("next_challenge_births"))
        assertEquals(1, count("metric_logs"))
        val accepted = requireNotNull(db.metricLogDao().getById(saved.factId))
        assertEquals(saved.factUuid, accepted.uuid); assertEquals(saved.value, accepted.value, 0.0)
        syncAll()
        assertEquals(1, server.requests.count { it.path.endsWith("/rounds/push") })
        assertEquals(1, count("metric_logs"))
        reopen()
        val resolved = editingMetrics().resolveIndependentValue(reference) as IndependentMetricResolution.Saved
        assertEquals(saved.factUuid, resolved.value.factUuid)
        assertEquals(outcome, row(reference.native()).outcome!!.text())
        editingMetrics().acknowledgeIndependentValue(resolved.value) { true }
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(reference.native()).status.delivery)
        assertEquals(beforeBirths, count("next_challenge_births"))
    }
}
