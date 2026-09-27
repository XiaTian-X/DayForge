package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.CompletionMetricPromptEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.LocalFactSubmissionEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.util.NumericInputUtils
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class CompletionMetricDraft(
    val linkUuid: String,
    val metricUuid: String,
    val metricName: String,
    val unit: String,
    val decimalPlaces: Int,
    val observationUuid: String,
    val operationId: String,
    val input: String = "",
    val note: String = ""
)

internal data class CompletionMetricPrompt(
    val row: CompletionMetricPromptEntity,
    val entries: List<CompletionMetricDraft>,
    val session: LocalDataSession
)

internal data class CompletionMetricInput(val value: String, val note: String = "")

/** Local receipt only; a missing/removed outbox never causes another observation to be inserted. */
internal data class CompletionMetricSaved(val observationUuids: List<String>, val alreadyStored: Boolean)

internal class CompletionMetricPromptException(val reason: Reason) : IllegalStateException(reason.name) {
    enum class Reason { MISSING, STALE_DRAFT, CLOSED, INVALID_INPUT, TARGET_MISSING, METRIC_CHANGED, CORRUPT }
}

/** Durable one-time completion prompts. UI/v5 activation is a separate coordinated step. */
internal class CompletionMetricPromptStore(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> String = { ZoneId.systemDefault().id }
) {
    private val dao = database.completionFollowUpDao()
    private val outbox = database.syncOutboxDao()

    suspend fun pending(): List<CompletionMetricPrompt> = sessions.exclusive {
        val session = access(null, false)
        database.withTransaction { dao.pendingPrompts().map { decode(it, session) } }
    }

    suspend fun read(eventUuid: String): CompletionMetricPrompt = sessions.exclusive {
        val session = access(null, false)
        database.withTransaction { load(eventUuid, session) }
    }

    /** Persist even incomplete numeric text. Validation belongs to submit, not keystroke storage. */
    suspend fun updateDraft(
        snapshot: CompletionMetricPrompt,
        inputs: Map<String, CompletionMetricInput>
    ): CompletionMetricPrompt = sessions.exclusive {
        access(snapshot.session, true)
        database.withTransaction {
            val current = editable(snapshot)
            if (!current.entries.map { it.metricUuid }.containsAll(inputs.keys)) fail(CompletionMetricPromptException.Reason.INVALID_INPUT)
            val entries = current.entries.map { entry -> inputs[entry.metricUuid]?.let {
                entry.copy(input = it.value, note = it.note)
            } ?: entry }
            replaceDraft(current, entries)
        }
    }

    /** Explicitly acknowledge changed units/precision before a later submission; retain input. */
    suspend fun refreshMetadata(snapshot: CompletionMetricPrompt): CompletionMetricPrompt = sessions.exclusive {
        access(snapshot.session, true)
        database.withTransaction {
            val current = editable(snapshot)
            replaceDraft(current, current.entries.map { entry ->
                val metric = target(current, entry, checkUnit = false)
                entry.copy(metricName = metric.name, unit = metric.unit, decimalPlaces = metric.decimalPlaces)
            })
        }
    }

    suspend fun dismiss(snapshot: CompletionMetricPrompt) = sessions.exclusive {
        access(snapshot.session, true)
        database.withTransaction {
            val current = load(snapshot.row.eventUuid, snapshot.session)
            if (current.row.state == "dismissed" && current.row.revision == snapshot.row.revision) return@withTransaction
            if (current.row.revision != snapshot.row.revision) fail(CompletionMetricPromptException.Reason.STALE_DRAFT)
            if (current.row.state != "pending") fail(CompletionMetricPromptException.Reason.CLOSED)
            check(dao.updatePrompt(current.row.copy(state = "dismissed")) == 1)
        }
    }

    suspend fun submit(snapshot: CompletionMetricPrompt): CompletionMetricSaved = sessions.exclusive {
        access(snapshot.session, true)
        // Freeze the observation instant separately, so a later fact/outbox failure keeps it
        // together with the user's already-persisted input and original identities.
        val prepared = database.withTransaction {
            val current = load(snapshot.row.eventUuid, snapshot.session)
            if (current.row.revision != snapshot.row.revision) fail(CompletionMetricPromptException.Reason.STALE_DRAFT)
            if (current.row.state == "dismissed") fail(CompletionMetricPromptException.Reason.CLOSED)
            selected(current)
            if (current.row.state == "saved") return@withTransaction current
            current.entries.filter { it.input.isNotBlank() }.forEach { target(current, it) }
            if (current.row.recordedAtMillis != null) current else {
                val instant = now()
                val timezone = zone()
                requireValidTime(instant, timezone)
                val frozen = current.row.copy(recordedAtMillis = instant, timezone = timezone)
                check(dao.updatePrompt(frozen) == 1)
                decode(frozen, current.session)
            }
        }
        database.withTransaction {
            val current = load(prepared.row.eventUuid, prepared.session)
            if (current.row.copy(state = prepared.row.state) != prepared.row) fail(CompletionMetricPromptException.Reason.STALE_DRAFT)
            if (current.row.state == "dismissed") fail(CompletionMetricPromptException.Reason.CLOSED)
            val entries = selected(current)
            if (current.row.state == "saved") {
                entries.forEach { entry ->
                    val payload = SyncV2Mapper.metricObservation(log(current, entry, 0), entry.metricUuid).toString()
                    if (dao.submission(entry.operationId) != receipt(entry, payload)) fail(CompletionMetricPromptException.Reason.CORRUPT)
                }
                return@withTransaction CompletionMetricSaved(entries.map { it.observationUuid }, true)
            }
            val rows = entries.map { entry ->
                val metric = target(current, entry)
                if (database.metricLogDao().getLogByUuid(entry.observationUuid) != null ||
                    outbox.getState("metric_observation", entry.observationUuid) != null ||
                    outbox.getByOperationId(entry.operationId) != null || dao.submission(entry.operationId) != null ||
                    dao.submissionForEntity("metric_observation", entry.observationUuid) != null
                ) fail(CompletionMetricPromptException.Reason.CORRUPT)
                val log = log(current, entry, metric.id)
                val payload = SyncV2Mapper.metricObservation(log, metric.uuid)
                Triple(entry, log, payload.toString())
            }
            val sql = database.openHelper.writableDatabase
            val enabled = sql.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use {
                it.moveToFirst() && it.getInt(0) == 0
            }
            if (!enabled) fail(CompletionMetricPromptException.Reason.CORRUPT)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            rows.forEach { (_, log, _) -> database.metricLogDao().insertForSync(log) }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            rows.forEach { (entry, _, payload) ->
                outbox.insert(SyncOutboxEntity(operationId = entry.operationId, recordType = "metric_log",
                    entityUuid = entry.observationUuid, wireEntityUuid = entry.observationUuid,
                    action = "upsert", referenceUuid = entry.metricUuid, payloadJson = payload,
                    createdAt = requireNotNull(current.row.recordedAtMillis)))
                dao.insertSubmission(receipt(entry, payload))
            }
            check(dao.updatePrompt(current.row.copy(state = "saved")) == 1)
            CompletionMetricSaved(entries.map { it.observationUuid }, false)
        }
    }

    private suspend fun access(expected: LocalDataSession?, write: Boolean): LocalDataSession {
        val access = tokens.localFactAccess()
        if (access == null || (expected != null && expected != access.session)) {
            throw OneTimeLocalException(OneTimeLocalException.Reason.STALE_SESSION)
        }
        if (write && !access.canAppend) throw OneTimeLocalException(OneTimeLocalException.Reason.FACTS_DENIED)
        return access.session
    }

    private suspend fun load(eventUuid: String, session: LocalDataSession): CompletionMetricPrompt {
        require(isContractUuid(eventUuid))
        return decode(dao.prompt(eventUuid) ?: fail(CompletionMetricPromptException.Reason.MISSING), session)
    }

    private suspend fun editable(snapshot: CompletionMetricPrompt): CompletionMetricPrompt {
        val current = load(snapshot.row.eventUuid, snapshot.session)
        if (current.row.revision != snapshot.row.revision) fail(CompletionMetricPromptException.Reason.STALE_DRAFT)
        if (current.row.state != "pending") fail(CompletionMetricPromptException.Reason.CLOSED)
        if (current.row.revision == Long.MAX_VALUE) fail(CompletionMetricPromptException.Reason.CORRUPT)
        return current
    }

    private suspend fun replaceDraft(current: CompletionMetricPrompt, entries: List<CompletionMetricDraft>): CompletionMetricPrompt {
        val updated = current.row.copy(revision = current.row.revision + 1, entriesJson = json.encodeToString(entries),
            recordedAtMillis = null, timezone = null)
        check(dao.updatePrompt(updated) == 1)
        return decode(updated, current.session)
    }

    private fun decode(row: CompletionMetricPromptEntity, session: LocalDataSession): CompletionMetricPrompt = try {
        require(isContractUuid(row.eventUuid) && isContractUuid(row.activityUuid))
        require(row.state in setOf("pending", "saved", "dismissed") && row.revision >= 0)
        require((row.recordedAtMillis == null) == (row.timezone == null))
        if (row.recordedAtMillis != null) requireValidTime(row.recordedAtMillis, requireNotNull(row.timezone))
        if (row.state == "saved") require(row.recordedAtMillis != null)
        val entries = json.decodeFromString<List<CompletionMetricDraft>>(row.entriesJson)
        require(entries.isNotEmpty())
        require(entries.map { it.metricUuid }.distinct().size == entries.size)
        require(entries.map { it.operationId }.distinct().size == entries.size)
        require(entries.map { it.observationUuid }.distinct().size == entries.size)
        entries.forEach { require(listOf(it.linkUuid, it.metricUuid, it.operationId, it.observationUuid).all(::isContractUuid)) }
        CompletionMetricPrompt(row, entries, session)
    } catch (_: IllegalArgumentException) { fail(CompletionMetricPromptException.Reason.CORRUPT) }

    private fun selected(prompt: CompletionMetricPrompt): List<CompletionMetricDraft> {
        val entries = prompt.entries.filter { it.input.isNotBlank() }
        if (entries.isEmpty() || entries.any {
                NumericInputUtils.parseFiniteDouble(it.input) == null ||
                    it.note.codePointCount(0, it.note.length) > 1000 ||
                    it.unit.codePointCount(0, it.unit.length) !in 1..50
            }) {
            fail(CompletionMetricPromptException.Reason.INVALID_INPUT)
        }
        return entries
    }

    private suspend fun target(prompt: CompletionMetricPrompt, entry: CompletionMetricDraft, checkUnit: Boolean = true): MetricEntity {
        val habit = database.habitDao().getHabitByUuid(prompt.row.activityUuid)
        val completion = database.completionDao().getCompletionByUuid(prompt.row.eventUuid)
        val metric = database.metricDao().getMetricByUuid(entry.metricUuid)
        val link = database.habitMetricLinkDao().getLinkByUuid(entry.linkUuid)
        if (habit == null || completion?.habitId != habit.id || completion.habitUuid != habit.uuid ||
            completion.oneTimeAction != "complete" || metric == null || link == null || !link.isActive ||
            link.habitId != habit.id || link.metricId != metric.id || link.habitUuid != habit.uuid || link.metricUuid != metric.uuid ||
            outbox.getState("plan_node", habit.uuid)?.deleted == true ||
            outbox.getState("metric", metric.uuid)?.deleted == true || outbox.getState("activity_metric_link", link.uuid)?.deleted == true
        ) fail(CompletionMetricPromptException.Reason.TARGET_MISSING)
        if (checkUnit && (metric.unit != entry.unit || metric.decimalPlaces != entry.decimalPlaces)) {
            fail(CompletionMetricPromptException.Reason.METRIC_CHANGED)
        }
        return metric
    }

    private fun log(prompt: CompletionMetricPrompt, entry: CompletionMetricDraft, metricId: Long): MetricLogEntity =
        MetricLogEntity(metricId = metricId, uuid = entry.observationUuid,
            date = requireNotNull(prompt.row.recordedAtMillis), value = requireNotNull(NumericInputUtils.parseFiniteDouble(entry.input)),
            unit = entry.unit, note = entry.note, recordedTimezone = requireNotNull(prompt.row.timezone),
            createdAt = requireNotNull(prompt.row.recordedAtMillis), updatedAt = requireNotNull(prompt.row.recordedAtMillis))

    private fun receipt(entry: CompletionMetricDraft, payload: String) = LocalFactSubmissionEntity(
        entry.operationId, "metric_observation", entry.observationUuid, entry.metricUuid, payload)

    private fun fail(reason: CompletionMetricPromptException.Reason): Nothing = throw CompletionMetricPromptException(reason)

    companion object {
        private val json = Json { encodeDefaults = true }

        private fun requireValidTime(millis: Long, timezone: String) {
            require(timezone in ZoneId.getAvailableZoneIds())
            require(Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).year in 1..9999)
            require(Instant.ofEpochMilli(millis).atZone(ZoneId.of(timezone)).year in 1..9999)
        }

        /** Called only inside the completion transaction, after its fact/outbox were inserted. */
        suspend fun createInTransaction(database: HabitDatabase, habit: HabitEntity, eventUuid: String) {
            check(database.inTransaction())
            val entries = database.habitMetricLinkDao().getAllLinksForHabit(habit.id)
                .filter { it.isActive && it.promptOnComplete }.map { link ->
                    val metric = checkNotNull(database.metricDao().getMetricById(link.metricId))
                    check(link.habitUuid == habit.uuid && link.metricUuid == metric.uuid)
                    check(listOf(link.uuid, metric.uuid).all(::isContractUuid))
                    CompletionMetricDraft(link.uuid, metric.uuid, metric.name, metric.unit, metric.decimalPlaces,
                        UUID.randomUUID().toString(), UUID.randomUUID().toString())
                }
            if (entries.isNotEmpty()) database.completionFollowUpDao().insertPrompt(CompletionMetricPromptEntity(
                eventUuid = eventUuid, activityUuid = habit.uuid, entriesJson = json.encodeToString(entries)))
        }
    }
}
