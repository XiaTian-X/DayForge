package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Complete original ONCE audit. Only this full retained-fact proof can issue current-state
 * coverage; warm reads never call it to validate their own cached answer. No authority is saved.
 */
internal class OnceTrustEvidence private constructor(val habit: HabitEntity, val snapshot: OneTimeLocalSnapshot) {
    companion object {
        const val COVERAGE_KEY = "once:source-coverage"
        const val COVERAGE = "retained-once-facts-independent-cas/v1"
        const val HABITS_KEY = "once:qualified-habits"
        fun eligibleKey(uuid: String) = "once:eligible:$uuid"
        fun projectionKey(uuid: String) = "once:projection:$uuid"
        fun pendingKey(uuid: String) = CountTrustMembership.key("once.pending", uuid)
        private val json = Json { encodeDefaults = true }

        /** Freeze before original business writes. TEMP journals have their own sqlite_sequence;
         * only MAIN and the retained AUTOINCREMENT high-water mark predict these row IDs.
         * An unavailable prediction only disables optional maintenance, never the original write.
         */
        fun nextId(database: HabitDatabase, table: String): Long? {
            check(database.inTransaction())
            require(table in setOf("completions", "metric_logs", "sync_outbox"))
            return try {
                val sql = database.openHelper.writableDatabase
                val sequence = sql.query("SELECT seq FROM main.sqlite_sequence WHERE name=? LIMIT 2", arrayOf(table)).use { cursor ->
                    if (!cursor.moveToFirst()) 0L else {
                        check(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER)
                        val value = cursor.getLong(0)
                        check(value >= 0 && !cursor.moveToNext())
                        value
                    }
                }
                val maximum = sql.query("SELECT MAX(id) FROM main.`$table`").use { cursor ->
                    check(cursor.moveToFirst())
                    val value = if (cursor.isNull(0)) 0L else {
                        check(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER)
                        cursor.getLong(0)
                    }
                    check(!cursor.moveToNext())
                    value
                }
                Math.addExact(maxOf(sequence, maximum), 1L)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }

        suspend fun audit(database: HabitDatabase, activityUuid: String, session: LocalDataSession): OnceTrustEvidence = try {
            check(database.inTransaction())
            val habits = database.habitDao()
            val facts = database.completionDao()
            val outbox = database.syncOutboxDao()
            auditOriginal(activityUuid, session, habits, facts, outbox)
        } catch (_: IllegalArgumentException) {
            reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
        }

        private suspend fun auditOriginal(activityUuid: String, session: LocalDataSession,
            habits: com.dayforge.data.local.dao.HabitDao, facts: com.dayforge.data.local.dao.CompletionDao,
            outbox: com.dayforge.data.local.dao.SyncOutboxDao): OnceTrustEvidence {
            if (outbox.getState("plan_node", activityUuid)?.deleted == true) reject(OneTimeLocalException.Reason.ENTITY_DELETED)
            val habit = habits.getHabitByUuid(activityUuid) ?: reject(OneTimeLocalException.Reason.ACTIVITY_NOT_FOUND)
            if (habit.completionPolicy != "one_and_done" || habit.oneTimeConfirmedVersion == null) {
                reject(OneTimeLocalException.Reason.UNINITIALIZED)
            }
            if (habit.schedule !is com.dayforge.data.model.HabitSchedule.Once ||
                habit.habitType != HabitType.CHECK_IN || habit.targetValue != 1 || habit.isCountdown ||
                habit.failMode != FailMode.LOOSE || habit.targetCycles != null || habit.bestTime != null
            ) reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
            val confirmed = OneTimeState(habit.oneTimeConfirmedVersion,
                habit.oneTimeConfirmedHeadEventUuid, habit.oneTimeConfirmedCompletionEventUuid)
            val records = facts.getByHabitOnce(habit.id)
            if (records.map { it.uuid }.distinct().size != records.size) reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
            val byUuid = records.associateBy { it.uuid }
            if (confirmed.version > 0) {
                val head = byUuid[confirmed.headEventUuid] ?: reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
                val headIntent = intent(head)
                val before = OneTimeState(headIntent.expectedVersion, headIntent.expectedHeadEventUuid,
                    headIntent.expectedHeadEventUuid.takeIf { headIntent.expectedVersion % 2 == 1 })
                if (advanceOneTime(before, headIntent) != confirmed) reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
            }
            val rows = outbox.getActivityIntents(activityUuid)
            val pending = rows.map { row ->
                val record = byUuid[row.entityUuid] ?: reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
                if (!matches(row, record, payload(record))) reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
                PendingOneTimeIntent(row.operationId, intent(record))
            }
            val pendingEventIds = pending.map { it.intent.eventUuid }.toSet()
            records.forEach { record ->
                if (record.habitUuid != activityUuid || record.value != if (record.oneTimeAction == "complete") 1 else 0) {
                    reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
                }
                val recordIntent = intent(record)
                // Older confirmed facts can be absent during incremental pull, but every fact
                // that is present must still prove a legal transition of its own.
                advanceOneTime(OneTimeState(recordIntent.expectedVersion, recordIntent.expectedHeadEventUuid,
                    recordIntent.expectedHeadEventUuid.takeIf { recordIntent.expectedVersion % 2 == 1 }), recordIntent)
                val instant = record.actualCompletedAt?.let(Instant::ofEpochMilli)
                    ?: reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
                if (record.recordedTimezone !in ZoneId.getAvailableZoneIds()) reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
                val captured = instant.atZone(ZoneId.of(record.recordedTimezone))
                if (instant.atZone(ZoneId.of("UTC")).year !in 1..9999 || captured.year !in 1..9999 ||
                    captured.toLocalDate().toString() != record.recordedLocalDate
                ) reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
                if (recordIntent.expectedVersion >= confirmed.version && record.uuid !in pendingEventIds) {
                    reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)
                }
            }
            val rejected = rows.firstOrNull { it.deadLetteredAt != null }?.operationId
            return OnceTrustEvidence(habit, OneTimeLocalSnapshot(activityUuid, session, confirmed,
                projectPendingOneTime(confirmed, pending, rejected)))
        }

        fun encode(snapshot: OneTimeLocalSnapshot): JsonObject = buildJsonObject {
            put("activity", snapshot.activityUuid)
            put("confirmed", json.encodeToJsonElement(OneTimeState.serializer(), snapshot.confirmed))
            put("queue", json.encodeToJsonElement(OneTimeQueueView.serializer(), snapshot.queue))
        }
        fun decode(value: JsonObject, activityUuid: String, session: LocalDataSession): OneTimeLocalSnapshot {
            check(value.keys == setOf("activity", "confirmed", "queue") && value.getValue("activity") == JsonPrimitive(activityUuid)) {
                "ONCE_TRUST_PROJECTION_CHANGED"
            }
            val confirmed = json.decodeFromJsonElement(OneTimeState.serializer(), value.getValue("confirmed"))
            val queue = json.decodeFromJsonElement(OneTimeQueueView.serializer(), value.getValue("queue"))
            return OneTimeLocalSnapshot(activityUuid, session, confirmed, queue)
        }
        suspend fun memberships(row: CountTrustRows.Row): Map<String, String> {
            val result = linkedMapOf<String, String>()
            fun add(kind: String, vararg parts: String) { result[CountTrustMembership.key(kind, *parts)] = row.name }
            when (row.table.name) {
                "sync_outbox" -> if (row.text("recordType") == "one_time_completion")
                    row.nullableText("referenceUuid")?.let { add("once.pending", it) }
                "local_fact_submissions" -> {
                    add("once.submission", row.text("operationId"))
                    add("once.submission.entity", row.text("entityType"), row.text("entityUuid"))
                }
                "one_time_transmissions" -> add("once.transmission", row.text("operationId"))
                "completion_metric_prompts" -> add("once.prompt", row.text("eventUuid"))
                "next_transmissions" -> add("once.nextTransmission", row.text("kind"), row.text("requestId"))
                "next_acceptances" -> add("once.acceptance", row.text("kind"), row.text("requestId"))
            }
            return Collections.unmodifiableMap(result)
        }

        /** Fixed private issuers supply every declared native column; no SQL or reflection. */
        fun native(rows: CountTrustRows, table: String, fields: Map<String, Any?>): CountTrustRows.Row {
            val layout = rows.table(table)
            check(fields.keys == layout.columns.toSet()) { "ONCE_TRUST_NATIVE_COLUMNS_CHANGED" }
            return CountTrustRows.Row(layout, layout.columns.map { column -> cell(fields[column]) })
        }
        fun replace(row: CountTrustRows.Row, fields: Map<String, Any?>): CountTrustRows.Row {
            check(row.table.columns.containsAll(fields.keys)) { "ONCE_TRUST_NATIVE_COLUMNS_CHANGED" }
            return CountTrustRows.Row(row.table, row.table.columns.map { if (it in fields) cell(fields[it]) else row.cell(it) })
        }
        fun completionRow(rows: CountTrustRows, value: CompletionEntity) = native(rows, "completions", mapOf(
            "id" to value.id, "habitId" to value.habitId, "date" to value.date, "value" to value.value,
            "actualCompletedAt" to value.actualCompletedAt, "uuid" to value.uuid, "habitUuid" to value.habitUuid,
            "createdAt" to value.createdAt, "recordedTimezone" to value.recordedTimezone,
            "recordedLocalDate" to value.recordedLocalDate, "timeMetadataSource" to value.timeMetadataSource,
            "oneTimeAction" to value.oneTimeAction, "oneTimeExpectedVersion" to value.oneTimeExpectedVersion,
            "oneTimeExpectedHeadEventUuid" to value.oneTimeExpectedHeadEventUuid, "oneTimeRevertsEventUuid" to value.oneTimeRevertsEventUuid))
        fun outboxRow(rows: CountTrustRows, value: SyncOutboxEntity) = native(rows, "sync_outbox", mapOf(
            "id" to value.id, "operationId" to value.operationId, "recordType" to value.recordType, "entityUuid" to value.entityUuid,
            "wireEntityUuid" to value.wireEntityUuid, "action" to value.action, "referenceUuid" to value.referenceUuid,
            "payloadJson" to value.payloadJson, "baseRevision" to value.baseRevision, "basePayloadJson" to value.basePayloadJson,
            "attemptedAt" to value.attemptedAt, "attemptCount" to value.attemptCount, "lastError" to value.lastError,
            "errorCode" to value.errorCode, "deadLetteredAt" to value.deadLetteredAt, "createdAt" to value.createdAt))
        fun submissionRow(rows: CountTrustRows, value: com.dayforge.data.local.entity.LocalFactSubmissionEntity) =
            native(rows, "local_fact_submissions", mapOf("operationId" to value.operationId, "entityType" to value.entityType,
                "entityUuid" to value.entityUuid, "referenceUuid" to value.referenceUuid, "payloadJson" to value.payloadJson))
        fun originRow(rows: CountTrustRows, value: com.dayforge.data.local.entity.NextRequestOriginEntity) =
            native(rows, "next_request_origins", mapOf("kind" to value.kind, "requestId" to value.requestId,
                "queueId" to value.queueId, "protocol" to value.protocol, "accountId" to value.accountId,
                "serverInstanceId" to value.serverInstanceId, "syncEpoch" to value.syncEpoch,
                "sourceHash" to value.sourceHash, "intentJson" to value.intentJson))
        fun promptRow(rows: CountTrustRows, value: com.dayforge.data.local.entity.CompletionMetricPromptEntity) =
            native(rows, "completion_metric_prompts", mapOf("eventUuid" to value.eventUuid, "activityUuid" to value.activityUuid,
                "state" to value.state, "revision" to value.revision, "entriesJson" to value.entriesJson,
                "recordedAtMillis" to value.recordedAtMillis, "timezone" to value.timezone))
        private fun cell(value: Any?): CountTrustJournal.Cell = when (value) {
            null -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_NULL, null)
            is String -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_STRING, value)
            is Boolean -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, if (value) 1L else 0L)
            is Int -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value.toLong())
            is Long -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value)
            is Double -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_FLOAT, value.toRawBits())
            is ByteArray -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_BLOB, value.toList())
            else -> error("ONCE_TRUST_NATIVE_TYPE_CHANGED")
        }
        private fun intent(record: CompletionEntity) = OneTimeIntent(record.uuid,
            record.oneTimeAction ?: reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE),
            record.oneTimeExpectedVersion ?: reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE),
            record.oneTimeExpectedHeadEventUuid, record.oneTimeRevertsEventUuid)
        private fun payload(record: CompletionEntity): String = buildJsonObject {
            put("activity_uuid", record.habitUuid)
            put("event_type", if (record.oneTimeAction == "complete") "check_in" else "revert")
            put("occurred_at", Instant.ofEpochMilli(record.actualCompletedAt
                ?: reject(OneTimeLocalException.Reason.INVALID_LOCAL_STATE)).toString())
            put("local_date", record.recordedLocalDate); put("timezone", record.recordedTimezone)
            put("source_type", "app")
            if (record.oneTimeAction == "undo") put("reverts_event_uuid", record.oneTimeRevertsEventUuid)
            put("one_time", json.parseToJsonElement(json.encodeToString(intent(record))))
        }.toString()
        private fun matches(row: SyncOutboxEntity, record: CompletionEntity, payload: String) =
            row.recordType == "one_time_completion" && row.entityUuid == record.uuid && row.wireEntityUuid == record.uuid &&
                row.referenceUuid == record.habitUuid && row.action == "upsert" && row.baseRevision == null &&
                row.basePayloadJson == null && row.payloadJson == payload
        private fun reject(reason: OneTimeLocalException.Reason): Nothing = throw OneTimeLocalException(reason)
    }
}

/** Original ONCE writer/send/ACK/prompt/page owners predict exact raw events. Inner mergers
 * may contribute effects, but only their original outer transaction may finish the root.
 */
internal sealed interface OnceTrustUpdate {
    val context: CountTrustKernel.Context
    val effects: List<CountTrustAck.Effect>
    val metadata: ChallengeMetadata?
    val affectedHabitUuids: Set<String>
    suspend fun verifyFinal(database: HabitDatabase)
}
