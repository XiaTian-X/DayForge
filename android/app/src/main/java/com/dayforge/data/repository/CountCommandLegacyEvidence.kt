package com.dayforge.data.repository

import android.database.Cursor
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*

/** Replays the immutable local receipt's input/source binding, not v4's mutable transport
 * projection. v4 may fold an unsent add/delete, reuse its oldest queue row, allocate a wire
 * revert on first send, ACK/remove the queue, or remove a fact later. None undoes an already
 * authenticated local T1. Missing queue/receipt never authorizes executing the command again.
 */
internal object CountCommandLegacyEvidence {
    fun verify(intent: CountCommandRecords.Intent, outcome: CountCommandOutcome) {
        require(CountCommandLegacyInput.isLegacy(intent)); outcome.requireIntent(intent)
        if (intent.action == CountCommandRecords.Action.LINKED_METRICS) {
            CountCommandLegacyMetricPlan.verifyHistorical(intent, outcome)
            return
        }
        val evidence = outcome.evidence
        require(evidence.profile == CountCommandEvidence.Profile.LEGACY_V4 && evidence.inputHash == intent.inputHash &&
            evidence.namespaceHash == intent.namespace.hash && outcome.origins.isEmpty())
        if (outcome.kind == CountCommandOutcome.Kind.NO_EFFECT) {
            require(evidence.records.isEmpty()); return
        }
        val input = intent.body().getValue("input").jsonObject
        val habit = CountCommandRecords.uuid(input, "habit_uuid")
        val added = intent.action == CountCommandRecords.Action.COUNT_ADD
        val role = if (added) CountCommandEvidence.Role.INSERTED_FACT else CountCommandEvidence.Role.DELETED_FACT
        val fact = evidence.records.single { it.role == role }
        require(fact.table == "completions" && fact.number("id") > 0 &&
            fact.number("habitId") == CountCommandRecords.integer(input, "habit_id"))
        require(fact.nullableText("oneTimeAction") == null && fact.nullableNumber("oneTimeExpectedVersion") == null &&
            fact.nullableText("oneTimeExpectedHeadEventUuid") == null && fact.nullableText("oneTimeRevertsEventUuid") == null)
        if (CountCommandLegacyWidgetInput.matches(intent)) {
            val claim = CountCommandLegacyWidgetInput.claim(input)
            require(fact.nullableText("habitUuid") == null || fact.nullableText("habitUuid") == habit)
            if (added) {
                val at = Instant.ofEpochMilli(requireNotNull(fact.nullableNumber("actualCompletedAt")))
                val zone = ZoneId.of(claim.timezone)
                require(fact.text("uuid") == CountCommandRecords.uuid(input, "reserved_fact_uuid") &&
                    fact.nullableText("habitUuid") == habit && fact.number("value") == 1L &&
                    at.atZone(zone).toLocalDate().toString() == claim.date && fact.text("recordedTimezone") == zone.id &&
                    fact.number("date") == at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() &&
                    fact.text("recordedLocalDate") == claim.date && fact.text("timeMetadataSource") == "captured")
            } else require(fact.text("uuid") == claim.completionUuid && fact.text("uuid") == CountCommandRecords.uuid(input, "target_fact_uuid"))
        } else if (added) {
            val at = Instant.parse(CountCommandRecords.text(input, "occurred_at")); val zone = ZoneId.of(CountCommandRecords.text(input, "timezone"))
            require(fact.text("uuid") == CountCommandRecords.uuid(input, "reserved_fact_uuid") && fact.nullableText("habitUuid") == habit &&
                fact.number("value") == CountCommandRecords.integer(input, "quantity") && fact.nullableNumber("actualCompletedAt") == at.toEpochMilli() &&
                fact.number("date") == at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() &&
                fact.text("recordedTimezone") == zone.id && fact.text("recordedLocalDate") == CountCommandRecords.text(input, "local_date") &&
                fact.text("timeMetadataSource") == "captured")
        } else {
            require(fact.text("uuid") == CountCommandRecords.uuid(input, "target_fact_uuid") &&
                nativeCompletion(fact) == input.getValue("target_native")) { "COMMAND_LEGACY_DELETE_EVIDENCE_CHANGED" }
        }
        val sources = evidence.records.filter { it.role == CountCommandEvidence.Role.ORIGINAL_SOURCE }
        require(sources.size in 1..(if (added) 2 else 1) && evidence.records.size == sources.size + 1 &&
            sources.map { it.text("operationId") }.distinct().size == sources.size)
        for (source in sources) {
            require(source.table == "sync_outbox" && source.number("id") > 0 &&
                com.dayforge.domain.model.isContractUuid(source.text("operationId")) && source.number("createdAt") % 1000L == 0L &&
                source.text("entityUuid") == source.text("wireEntityUuid") && source.number("attemptCount") == 0L)
            for (column in listOf("payloadJson", "baseRevision", "basePayloadJson", "attemptedAt", "lastError", "errorCode", "deadLetteredAt"))
                require(source.cell(column).type == Cursor.FIELD_TYPE_NULL)
        }
        val source = sources.single { it.text("recordType") == "completion" }
        require(source.text("entityUuid") == fact.text("uuid") && source.text("action") == if (added) "upsert" else "delete")
        require(source.nullableText("referenceUuid") == if (added) habit else fact.nullableText("habitUuid") ?: habit)
        require(outcome.primaryFactUuid == fact.text("uuid") && outcome.primaryRequestId == source.text("operationId"))
        sources.filter { it !== source }.forEach {
            require(added && it.text("recordType") == "habit" && it.text("entityUuid") == habit &&
                it.text("action") == "upsert" && it.text("referenceUuid") == "COUNTING")
        }
    }

    // Current v4 retry/fold/preparation/ACK identities are deliberately not reconstructed
    // from mutable queue rows here. The authenticated T1 evidence above is historical only;
    // original v4 send and original current display gates remain separate callers.

    private fun nativeCompletion(record: CountCommandEvidence.Record): JsonObject = buildJsonObject {
        record.body().getValue("cells").jsonObject.keys.forEach { name ->
            val cell = record.cell(name)
            put(name, when (cell.type) {
                Cursor.FIELD_TYPE_NULL -> JsonNull
                Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cell.value as Long)
                Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cell.value as String)
                else -> error("COMMAND_LEGACY_COMPLETION_NATIVE_TYPE")
            })
        }
    }
}
