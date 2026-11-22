package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.domain.model.isContractUuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual standalone plain T0/T1, immutable wire bytes and cold T2. No mocked producer,
 * extra normalization, timing threshold or formal signed-zero coverage is implied. */
@RunWith(AndroidJUnit4::class)
class IndependentMetricWireEnvelopeTest : NextObjectEditorFixture() {
    private fun cold() { storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons) }

    @Test(timeout = 150_000) fun bothZeroSignsAndEscapedNoteKeepOriginalWireOrderAcrossColdDelivery() = runBlocking<Unit> {
        register()
        val note = "quote=\"value\" backslash=\\ newline=\n 指标/🙂"
        val expectedKeys = listOf("metric_uuid", "value", "unit", "occurred_at", "local_date", "timezone", "note", "source_type")
        val seenFacts = linkedSetOf<String>()
        val seenCommands = linkedSetOf<String>()
        for ((index, requested) in listOf(-0.0, 0.0).withIndex()) {
            val repository = editingMetrics()
            val selected = repository.getMetricForEditing(metric.id)
            val attempt = repository.beginIndependentValue(metric.id, requested, note, millis, selected.authority, metric.uuid)
            val frozenIntent = requireNotNull(attempt.intent)
            val input = IndependentMetricInput.input(frozenIntent)
            val requestedBits = requested.toRawBits().toULong().toString(16).padStart(16, '0')
            assertEquals(requestedBits, CountCommandRecords.text(input, "value_bits"))
            assertEquals(false, input.getValue("round_profile").jsonPrimitive.boolean)
            val reserved = CountCommandRecords.uuid(input, "reserved_fact_uuid")
            val frozenLog = MetricLogEntity(metricId = metric.id, uuid = reserved,
                date = CountCommandRecords.integer(input, "recorded_at"), value = requested, unit = metric.unit,
                note = note, recordedTimezone = CountCommandRecords.text(input, "timezone"))
            // Freeze all payload fields and their original mapper order before real T1.
            val frozenPayload = SyncV2Mapper.metricObservation(frozenLog, metric.uuid)
            assertEquals(expectedKeys, frozenPayload.keys.toList())
            val oldWatermark = db.syncOutboxDao().getAll().maxOfOrNull { it.id } ?: 0L

            val saved = repository.executeIndependentValue(attempt)
            val reference = requireNotNull(saved.reference)
            assertTrue(seenFacts.add(saved.factUuid)); assertTrue(seenCommands.add(reference.native().commandId))
            val stored = requireNotNull(db.metricLogDao().getById(saved.factId))
            assertEquals(reserved, stored.uuid); assertEquals(metric.id, stored.metricId)
            assertEquals(frozenLog.date, stored.date); assertEquals(frozenLog.unit, stored.unit)
            assertEquals(frozenLog.note, stored.note); assertEquals(frozenLog.recordedTimezone, stored.recordedTimezone)
            assertEquals(frozenLog.recordedLocalDate, stored.recordedLocalDate)
            assertEquals(0.0, stored.value, 0.0)
            assertTrue(stored.value.toRawBits() == requested.toRawBits() ||
                requested.toRawBits() == Long.MIN_VALUE && stored.value == 0.0)
            assertEquals(stored.value.toRawBits(), saved.value.toRawBits())

            val allocated = db.syncOutboxDao().getAll().single { it.entityUuid == reserved }
            assertTrue(allocated.id > oldWatermark); assertTrue(isContractUuid(allocated.operationId))
            assertEquals(0L, allocated.createdAt % 1000L)
            val expectedSource = SyncOutboxEntity(id = allocated.id, operationId = allocated.operationId,
                recordType = "metric_log", entityUuid = reserved, wireEntityUuid = reserved,
                action = "upsert", referenceUuid = metric.uuid, createdAt = allocated.createdAt)
            assertEquals(expectedSource, allocated)
            // The native REAL zero is the sole permitted observed payload value. All other
            // fields remain the owned pre-write input; no actual origin supplies expected text.
            val expectedPayload = buildJsonObject {
                for ((key, value) in frozenPayload) put(key, if (key == "value") JsonPrimitive(stored.value) else value)
            }
            assertEquals(expectedKeys, expectedPayload.keys.toList())
            val expectedOperation = SyncV2Operation(allocated.operationId, "metric_observation", reserved,
                "upsert", null, expectedPayload)
            val expectedWire = encodeSyncRequest(SyncV2Operation.serializer(), expectedOperation).toString(Charsets.UTF_8)
            val expectedOrigin = NextRequestOriginEntity(NEXT_OPERATION, allocated.operationId, allocated.id, 5,
                frozenIntent.namespace.account, frozenIntent.namespace.server, frozenIntent.namespace.epoch,
                NextRequestSql.sourceHash(expectedSource), expectedWire)
            val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, allocated.operationId))
            assertEquals(expectedOrigin, origin)
            assertEquals(expectedKeys, Json.parseToJsonElement(origin.intentJson).jsonObject.getValue("payload").jsonObject.keys.toList())
            assertNull(roundOperationIntent(origin.intentJson))
            val before = CountCommandStore(db, tokens, sessions).resolve(reference.native()).historical
            val outcome = requireNotNull(before.outcome)
            assertEquals(CountCommandEvidence.Profile.METRIC_PLAIN_V5, outcome.evidence.profile)
            val resultBytes = outcome.text()
            assertEquals(frozenIntent.text(), before.intent.text())
            assertEquals(index + 1, count("metric_logs")); assertEquals(index + 1, count("sync_outbox"))

            cold()
            val recovered = editingMetrics().resolveIndependentValue(reference) as IndependentMetricResolution.Saved
            assertEquals(saved.factUuid, recovered.value.factUuid)
            assertEquals(stored.value.toRawBits(), recovered.value.value.toRawBits())
            assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, allocated.operationId))
            editingMetrics().acknowledgeIndependentValue(recovered.value) { true }
            editingMetrics().acknowledgeIndependentValue(recovered.value) { true }
            val delivered = CountCommandStore(db, tokens, sessions).resolve(reference.native()).historical
            assertEquals(CountCommandRecords.Delivery.DELIVERED, delivered.status.delivery)
            assertEquals(resultBytes, delivered.outcome!!.text())
            assertEquals(frozenIntent.text(), delivered.intent.text())
            assertEquals(requestedBits, CountCommandRecords.text(IndependentMetricInput.input(delivered.intent), "value_bits"))
            assertEquals(index + 1, count("metric_logs")); assertEquals(index + 1, count("sync_outbox"))
            assertEquals(index + 1, count("next_request_origins")); assertEquals(index + 1, count("count_command_results"))
        }
        assertEquals(2, seenFacts.size); assertEquals(2, seenCommands.size)
    }
}
