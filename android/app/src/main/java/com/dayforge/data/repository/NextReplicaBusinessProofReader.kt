package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.strictAppearanceJson
import com.dayforge.data.local.*
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** A complete current projection index, with unconfirmed values preserved rather than adopted.
 * Historical shadows may legitimately outlive their current parents/projections. Materials and
 * the private archive/stage/fence remain separate requirements; this is never a switch ticket.
 */
internal class ReplicaBusinessProof internal constructor(val localFacts: ReplicaLocalFactProof,
    records: Map<ReplicaRecordKey, JsonObject>, unconfirmed: Set<ReplicaRecordKey>,
    projectionGaps: Set<ReplicaRecordKey>, val hasUnfinishedTimer: Boolean) {
    val records: Map<ReplicaRecordKey, JsonObject> = Collections.unmodifiableMap(records.toMap())
    val unconfirmed: Set<ReplicaRecordKey> = Collections.unmodifiableSet(unconfirmed.toSet())
    // Snapshot convergence can also remove cache rows while retaining old authority. Do not guess
    // whether a gap was that legitimate absence or loss; keep it explicit for the recovery review.
    val projectionGaps: Set<ReplicaRecordKey> = Collections.unmodifiableSet(projectionGaps.toSet())
    val hasProjectionReview: Boolean get() = unconfirmed.isNotEmpty() || projectionGaps.isNotEmpty()
    val activationAuthorized: Boolean get() = false
}

internal class NextReplicaBusinessProofReader(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val preferences: PreferencesManager, private val http: NextSyncHttp) {
    suspend fun read(localFacts: ReplicaLocalFactProof): ReplicaBusinessProof = withContext(Dispatchers.IO) {
        val requests = localFacts.configuration.requests
        ReplicaFactRecoveryReadScope.withSnapshot(database, tokens, sessions, requests.original) { factScope ->
            val storage = NextReplicaStorageProof(database).readInTransaction()
            require(storage.hash == requests.storageHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
            val tables = storage.contents.getValue("tables").jsonObject
            val businessTables = listOf("habits", "metrics", "habit_metric_links", "completions", "timelogs", "metric_logs")
            // Audit the raw representation BEFORE Room's Int/Boolean and JSON conversions.
            for (table in businessTables + listOf("timer_segments", "timelog_day_allocations")) {
                for (raw in tables.getValue(table).jsonArray) auditRaw(table, raw.jsonObject)
            }
            val shadows = linkedMapOf<ReplicaRecordKey, SyncV2Change>()
            NextAuthorityShadowReader(database).readInTransaction { shadows[ReplicaRecordKey(it.entityType, it.entityUuid)] = it }
            val habits = database.habitDao().getAllHabitsOnce()
            val metrics = database.metricDao().getAllMetricsOnce()
            require(habits.map { it.uuid }.distinct().size == habits.size && metrics.map { it.uuid }.distinct().size == metrics.size)
            val parents = habits.associateBy { it.uuid }
            val habitsById = habits.associateBy { it.id }
            val metricsById = metrics.associateBy { it.id }
            val records = linkedMapOf<ReplicaRecordKey, JsonObject>()
            val unconfirmed = linkedSetOf<ReplicaRecordKey>()
            val submissions = localFacts.submissions.associateBy { it.entityType to it.entityUuid }
            require(submissions.size == localFacts.submissions.size)
            val currentRaw = businessTables.associateWith { table ->
                tables.getValue(table).jsonArray.map { it.jsonObject }.associateBy { raw ->
                    raw.getValue("uuid").jsonObject.getValue("value").jsonPrimitive.content.also { require(isContractUuid(it)) }
                }.also { require(it.size == tables.getValue(table).jsonArray.size) { "SYNC_RECOVERY_PROJECTION_DUPLICATE" } }
            }
            fun record(table: String, type: String, uuid: String, confirmed: Boolean) {
                val key = ReplicaRecordKey(type, uuid)
                require(records.put(key, currentRaw.getValue(table).getValue(uuid)) == null) { "SYNC_RECOVERY_PROJECTION_DUPLICATE" }
                if (!confirmed) unconfirmed += key
            }
            fun authority(type: String, uuid: String) = shadows[ReplicaRecordKey(type, uuid)]?.takeIf { it.operation == "upsert" }
            val once = OneTimeLocalIntentStore(database, tokens, sessions, preferences)
            val core = NextCoreRequestStore(database, tokens, sessions, http)
            val onceFacts = OneTimeAcceptedEventStore(database, tokens, sessions, once,
                timerRequests = NextTimerRequestStore(database, tokens, sessions, core))
            var unfinished = false
            val today = LocalDate.of(2000, 1, 1) // Recovery selects no calendar window; every retained row is audited.
            for (habit in habits) {
                currentCoroutineContext().ensureActive()
                NextStructureMapper.writePlan(habit)
                require(habit.activityRate in 0..100)
                habit.parentHabitId?.let { parent ->
                    val goal = requireNotNull(parents[parent]) { "SYNC_RECOVERY_PARENT_INVALID" }
                    require(habit.habitType != HabitType.GOAL && goal.habitType == HabitType.GOAL && goal.parentHabitId == null &&
                        shadows[ReplicaRecordKey("plan_node", parent)]?.operation != "delete") { "SYNC_RECOVERY_PARENT_INVALID" }
                }
                val shadow = authority("plan_node", habit.uuid)
                record("habits", "plan_node", habit.uuid, shadow != null &&
                    NextStructureMapper.readPlan(shadow.payload, habit.uuid, shadow.revision, habit) == habit)
                val completions = database.completionDao().getCompletionsByHabit(habit.id).first()
                if (habit.completionPolicy == "one_and_done") {
                    once.readInTransaction(habit.uuid, requests.original.session)
                    for (fact in completions) {
                        validateDate(fact.actualCompletedAt, fact.recordedTimezone, fact.recordedLocalDate, fact.timeMetadataSource)
                        require(fact.date == LocalDate.parse(fact.recordedLocalDate).atStartOfDay(ZoneId.of(fact.recordedTimezone)).toInstant().toEpochMilli()) {
                            "SYNC_RECOVERY_FACT_PROJECTION_INVALID"
                        }
                        val accepted = authority("activity_event", fact.uuid)
                        if (accepted != null) {
                            val incoming = onceFacts.completion(OneTimeServerFact(accepted.payload, accepted.revision), habit)
                            require(fact.copy(id = 0, createdAt = incoming.createdAt, timeMetadataSource = "server", date = incoming.date) == incoming) {
                                "SYNC_RECOVERY_FACT_PROJECTION_INVALID"
                            }
                        } else {
                            val submission = submissions["activity_event" to fact.uuid]
                            require(submission != null && once.payload(fact) == submission.payloadJson) { "SYNC_RECOVERY_FACT_PROJECTION_INVALID" }
                        }
                        record("completions", "activity_event", fact.uuid, accepted != null)
                    }
                    require(database.timeLogDao().getAllTimeLogsForHabit(habit.id).isEmpty() && database.countDayDao().forHabit(habit.id).isEmpty())
                } else if (habit.habitType != HabitType.GOAL) {
                    val evidence = RecurringCompletionEvidenceReader(database, tokens, sessions).recoveryInTransaction(habit, today, factScope)
                    require(evidence.completions.size == completions.size)
                    for (fact in evidence.completions) {
                        val accepted = authority("activity_event", fact.uuid)
                        // A known quantity with an unknown historical count rule is still a review,
                        // never an excuse to adopt today's editable target/countdown configuration.
                        val unknownRule = accepted?.payload?.let { body ->
                            body["event_type"] in setOf(JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot")) &&
                                NextCommonFactMapper.countPolicy(body) == null
                        } == true
                        record("completions", "activity_event", fact.uuid, accepted != null && !unknownRule)
                    }
                    val timers = TimerHistoryReader(database, tokens, sessions).recoveryInTransaction(habit, today, factScope)
                    for (log in timers.logs) record("timelogs", "activity_event", log.uuid,
                        authority("activity_event", log.uuid) != null)
                    unfinished = unfinished || timers.hasUnfinishedTimer
                } else require(completions.isEmpty() && database.timeLogDao().getAllTimeLogsForHabit(habit.id).isEmpty() &&
                    database.countDayDao().forHabit(habit.id).isEmpty()) { "SYNC_RECOVERY_GOAL_FACT_INVALID" }
            }
            for (metric in metrics) {
                currentCoroutineContext().ensureActive()
                NextStructureMapper.writeMetric(metric)
                val shadow = authority("metric", metric.uuid)
                record("metrics", "metric", metric.uuid, shadow != null &&
                    NextStructureMapper.readMetric(shadow.payload, metric.uuid, shadow.revision, metric) == metric)
                for (log in database.metricLogDao().getAllLogsForMetric(metric.id)) {
                    validateDate(log.date, log.recordedTimezone, log.recordedLocalDate, log.timeMetadataSource)
                    val body = SyncV2Mapper.metricObservation(log, metric.uuid)
                    validateNextSyncOperation(SyncV2Operation(log.uuid, "metric_observation", log.uuid, "upsert", payload = body))
                    val accepted = authority("metric_observation", log.uuid)
                    record("metric_logs", "metric_observation", log.uuid, accepted != null &&
                        NextCommonFactMapper.observation(accepted, metric, log) == log)
                }
            }
            for (link in database.habitMetricLinkDao().getAllLinksOnce()) {
                currentCoroutineContext().ensureActive()
                val habit = requireNotNull(habitsById[link.habitId])
                val metric = requireNotNull(metricsById[link.metricId])
                require(habit.habitType != HabitType.GOAL && habit.uuid == link.habitUuid && metric.uuid == link.metricUuid)
                NextCommonFactMapper.validateLinkWrite(SyncV2Mapper.link(link))
                val accepted = authority("activity_metric_link", link.uuid)
                record("habit_metric_links", "activity_metric_link", link.uuid, accepted != null &&
                    NextCommonFactMapper.link(accepted, habit, metric, link) == link)
            }
            // No hidden row may disappear because a DAO selected only active/current-round records.
            require(records.size == businessTables.sumOf { currentRaw.getValue(it).size }) { "SYNC_RECOVERY_PROJECTION_INCOMPLETE" }
            val reverts = shadows.values.filter { it.operation == "upsert" && it.entityType == "activity_event" &&
                it.payload["one_time"].let { onceBody -> onceBody == null || onceBody == JsonNull } &&
                it.payload["event_type"] == JsonPrimitive("revert") }.mapTo(hashSetOf()) {
                    it.payload.getValue("activity_uuid").jsonPrimitive.content to it.payload.getValue("reverts_event_uuid").jsonPrimitive.content
                }
            val gaps = linkedSetOf<ReplicaRecordKey>()
            fun deleted(type: String, id: String) = shadows[ReplicaRecordKey(type, id)]?.operation == "delete"
            for ((key, shadow) in shadows) {
                currentCoroutineContext().ensureActive()
                if (shadow.operation == "delete" || key in records) continue
                val body = shadow.payload
                val provenAbsence = when (key.kind) {
                    "plan_node", "metric" -> false
                    "activity_metric_link" -> deleted("plan_node", body.getValue("activity_uuid").jsonPrimitive.content) ||
                        deleted("metric", body.getValue("metric_uuid").jsonPrimitive.content)
                    "metric_observation" -> deleted("metric", body.getValue("metric_uuid").jsonPrimitive.content)
                    "activity_event" -> {
                        val activity = body.getValue("activity_uuid").jsonPrimitive.content
                        val ordinary = body["one_time"].let { it == null || it == JsonNull }
                        deleted("plan_node", activity) || ordinary && (body["event_type"] == JsonPrimitive("revert") || activity to key.identity in reverts)
                    }
                    else -> error("SYNC_RECOVERY_PROJECTION_KIND_INVALID")
                }
                if (!provenAbsence) gaps += key
            }
            require(NextReplicaStorageProof(database).readInTransaction().hash == requests.storageHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
            ReplicaBusinessProof(localFacts, records, unconfirmed, gaps, unfinished)
        }
    }

    private suspend fun auditRaw(table: String, row: JsonObject) {
        val caller = currentCoroutineContext()
        for ((name, raw) in row) {
            caller.ensureActive()
            val typed = raw.jsonObject
            val type = typed.getValue("sqlite_type").jsonPrimitive.int
            if (type == Cursor.FIELD_TYPE_NULL) continue // Schema proof checked actual nullability.
            val value = typed.getValue("value").jsonPrimitive
            if (name in intColumns || table == "habits" && name == "targetValue" || table == "completions" && name == "value")
                require(type == Cursor.FIELD_TYPE_INTEGER && value.long in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "SYNC_RECOVERY_PROJECTION_INTEGER_INVALID"
            }
            if (name in booleanColumns) require(type == Cursor.FIELD_TYPE_INTEGER && value.long in 0..1) { "SYNC_RECOVERY_PROJECTION_BOOLEAN_INVALID" }
            if (name == "id") require(type == Cursor.FIELD_TYPE_INTEGER && value.long > 0) { "SYNC_RECOVERY_PROJECTION_ID_INVALID" }
            if (name in jsonColumns) {
                require(type == Cursor.FIELD_TYPE_STRING)
                val text = strictAppearanceJson(value.content.toByteArray(), com.dayforge.data.api.SYNC_REQUEST_LIMIT,
                    { caller.ensureActive() }) { error("SYNC_RECOVERY_PROJECTION_$it") }
                val body = Json.parseToJsonElement(text).jsonObject
                // JSON integer decoding also must not wrap a Long into an apparently valid Int.
                if (name == "schedule") {
                    for (key in listOf("dayOfMonth", "frequencyDays")) body[key]?.let { require(it is JsonPrimitive && !it.isString && it.intOrNull != null) }
                    body["daysOfWeek"]?.jsonArray?.forEach { require(it is JsonPrimitive && !it.isString && it.intOrNull != null) }
                }
                if (name == "planMetadata") body["sortOrder"]?.let { require(com.dayforge.domain.model.contractLongOrNull(it) != null) }
            }
        }
    }

    private fun validateDate(millis: Long?, timezone: String, date: String, source: String) {
        require(millis != null && timezone in ZoneId.getAvailableZoneIds() && source in timeSources)
        val instant = Instant.ofEpochMilli(millis)
        val local = instant.atZone(ZoneId.of(timezone))
        require(instant.atZone(ZoneId.of("UTC")).year in 1..9999 && local.year in 1..9999 && local.toLocalDate().toString() == date)
    }

    private val intColumns = setOf("iconResId", "targetCycles", "activityRate", "oneTimeConfirmedVersion", "decimalPlaces",
        "oneTimeExpectedVersion", "durationSeconds", "timerNextCommandSequence", "timerControlGeneration", "timerBootCount", "sequence")
    private val booleanColumns = setOf("isCountdown", "isActive", "goalSuccess", "showInHabitDetail", "promptOnComplete", "isPaused")
    private val jsonColumns = setOf("schedule", "appearance", "planMetadata")
    private val timeSources = setOf("captured", "server", "legacy_sync", "legacy_device_fallback")
}
