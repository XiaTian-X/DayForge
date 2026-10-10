package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import com.dayforge.data.local.entity.NextRequestOriginEntity
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Frozen before/after rows only. Not an outbox producer, server shadow or write permission. */
internal data class NextRecoveryProjectionDraft(val before: NextRecoveryProjectionSnapshot,
    val after: NextRecoveryProjectionSnapshot, val baseline: NextRecoveryProjectionBaseline)

/** Planning and exact projection application; caller owns the complete recovery transaction. */
internal class NextRecoveryProjectionStore(private val database: HabitDatabase) {
    private val sql get() = database.openHelper.writableDatabase
    private fun table(type: String) = when (type) {
        "plan_node" -> "habits"; "metric" -> "metrics"; "activity_metric_link" -> "habit_metric_links"
        else -> error("RECOVERY_PROJECTION_TYPE_INVALID")
    }

    suspend fun capture(type: String, uuid: String): NextRecoveryProjectionSnapshot {
        check(database.inTransaction()); require(isContractUuid(uuid)); currentCoroutineContext().ensureActive()
        val table = table(type)
        val lengths = columns(type).keys.joinToString("+") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
        sql.query("SELECT ($lengths) FROM $table WHERE uuid=?", arrayOf(uuid)).use {
            if (!it.moveToFirst()) return NextRecoveryProjectionSnapshot(type, uuid, null, null, JsonObject(emptyMap()))
            require(it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) <= NEXT_RECOVERY_SOURCE_BYTES && !it.moveToNext())
        }
        val local = sql.query("SELECT * FROM $table WHERE uuid=?", arrayOf(uuid)).use { c ->
            require(c.moveToFirst())
            val value = buildJsonObject {
                for (i in 0 until c.columnCount) {
                    currentCoroutineContext().ensureActive()
                    put(c.getColumnName(i), when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i).also { require(it.isFinite()) })
                        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(c.getString(i))
                        else -> error("RECOVERY_PROJECTION_SQL_TYPE_INVALID")
                    })
                }
            }
            require(!c.moveToNext()); value
        }
        validateLocal(type, local)
        return NextRecoveryProjectionSnapshot(type, uuid, local.long("id"), body(type, local), local).also(::validate)
    }

    fun validatePair(before: NextRecoveryProjectionSnapshot, after: NextRecoveryProjectionSnapshot) {
        check(database.inTransaction())
        validate(before); validate(after)
        require(before.entityType == after.entityType && before.entityUuid == after.entityUuid)
        if (before.localId != null && after.localId != null) {
            require(before.localId == after.localId)
            val preserved = when (before.entityType) {
                "plan_node" -> listOf("createdAt", "iconResId", "colorHex", "activityRate", "activityRateUpdatedAt",
                    "oneTimeConfirmedVersion", "oneTimeConfirmedHeadEventUuid", "oneTimeConfirmedCompletionEventUuid")
                "metric" -> listOf("createdAt", "iconResId", "colorHex")
                else -> listOf("createdAt", "habitId", "habitUuid", "metricId", "metricUuid")
            }
            require(preserved.all { before.local[it] == after.local[it] }) { "RECOVERY_PROJECTION_LOCAL_IDENTITY_CHANGED" }
            if (before.entityType == "plan_node") require((habit(before.local).habitType == HabitType.GOAL) == (habit(after.local).habitType == HabitType.GOAL))
        }
    }

    /** Frozen cold-read validation never rewrites or requires the current projection to stay old. */
    suspend fun validateDraft(draft: NextRecoveryProjectionDraft, origins: List<NextRequestOriginEntity>) {
        check(database.inTransaction())
        val (before, after, baseline) = draft
        validatePair(before, after)
        require(baseline.entityType == before.entityType && baseline.entityUuid == before.entityUuid && origins.isNotEmpty())
        val operations = origins.map { requireNotNull(roundOperationIntent(it.intentJson)).operation }
        require(operations.all { it.entityType == before.entityType && it.entityUuid == before.entityUuid })
        if (baseline.rowHash == null) {
            val first = operations[origins.indices.minBy { origins[it].queueId }]
            require(first.action == "upsert" && first.baseRevision == null) { "RECOVERY_PROJECTION_BASE_MISSING" }
            require(after.localId == null)
        } else if (baseline.deleted == true) {
            val payload = requireNotNull(baseline.canonical)
            if (before.entityType == "activity_metric_link") NextCommonFactMapper.validateLinkSnapshot(
                com.dayforge.data.api.dto.SyncV2Change(0, before.entityType, before.entityUuid, "delete",
                    requireNotNull(baseline.revision), payload, payload.getValue("updated_at").jsonPrimitive.content))
            else NextStructureMapper.validateTombstone(payload, before.entityType, before.entityUuid, requireNotNull(baseline.revision))
            require(after.localId == null)
        } else {
            require(before.localId != null && after.localId == before.localId)
            require(after.local == restoredLocal(before, baseline)) { "RECOVERY_PROJECTION_NOT_FROM_BASELINE" }
        }
    }

    private fun restoredLocal(before: NextRecoveryProjectionSnapshot, baseline: NextRecoveryProjectionBaseline): JsonObject {
        val payload = requireNotNull(baseline.canonical); val revision = requireNotNull(baseline.revision)
        return when (before.entityType) {
            "plan_node" -> planLocal(NextStructureMapper.readPlan(payload, before.entityUuid, revision, habit(before.local)))
            "metric" -> metricLocal(NextStructureMapper.readMetric(payload, before.entityUuid, revision, metric(before.local)))
            else -> {
                NextCommonFactMapper.validateLinkSnapshot(com.dayforge.data.api.dto.SyncV2Change(0, before.entityType,
                    before.entityUuid, "upsert", revision, payload, payload.getValue("updated_at").jsonPrimitive.content))
                val old = link(before.local)
                require(payload.getValue("activity_uuid") == JsonPrimitive(old.habitUuid) &&
                    payload.getValue("metric_uuid") == JsonPrimitive(old.metricUuid))
                require(Instant.parse(payload.getValue("created_at").jsonPrimitive.content).toEpochMilli() == old.createdAt)
                linkLocal(old.copy(coefficient = payload.getValue("coefficient").jsonPrimitive.double,
                    showInHabitDetail = payload.getValue("show_in_activity_detail").jsonPrimitive.boolean,
                    promptOnComplete = payload.getValue("prompt_on_complete").jsonPrimitive.boolean,
                    isActive = payload.getValue("is_active").jsonPrimitive.boolean,
                    updatedAt = Instant.parse(payload.getValue("updated_at").jsonPrimitive.content).toEpochMilli()))
            }
        }
    }

    /** Uses a full validated current shadow, never a rejection's message or current-error payload. */
    suspend fun plan(preview: NextRecoveryDependencyPreview): List<NextRecoveryProjectionDraft> {
        check(database.inTransaction()); require(preview.canConfirm)
        require(preview.archive.preview(preview.root).graphHash == preview.graphHash)
        val selected = preview.affected.map { it.key }.toSet()
        val queue = database.syncOutboxDao().getAll() + database.syncOutboxDao().getDeadLetters()
        val affected = preview.affected.groupBy { it.entityType to it.entityUuid }
        val result = mutableListOf<NextRecoveryProjectionDraft>()
        for ((identity, nodes) in affected) {
            val (type, uuid) = identity
            currentCoroutineContext().ensureActive()
            require(queue.none {
                it.entityUuid == uuid && NextRecoveryKey(NEXT_OPERATION, it.operationId) !in selected
            }) { "RECOVERY_PROJECTION_UNSELECTED_WORK" }
            val before = capture(type, uuid)
            val hash = NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf(type, uuid))
            val shadow = database.syncOutboxDao().getState(type, uuid)
            val origins = nodes.map { requireNotNull(database.nextRequestDao().origin(it.key.kind, it.key.requestId)) }
            val baseline = NextRecoveryProjectionBaseline(type, uuid, hash, shadow?.revision, shadow?.deleted,
                shadow?.payloadJson, shadow?.payloadHash, shadow?.updatedAt)
            val after = if (shadow == null) {
                require(hash == null)
                val first = origins.minBy { it.queueId }
                val operation = requireNotNull(roundOperationIntent(first.intentJson)).operation
                require(operation.action == "upsert" && operation.baseRevision == null) { "RECOVERY_PROJECTION_BASE_MISSING" }
                requireNoHistory(type, uuid, before)
                NextRecoveryProjectionSnapshot(type, uuid, null, null, JsonObject(emptyMap()))
            } else {
                require(hash != null && shadow.revision > 0 && shadow.payloadJson != null && shadow.payloadHash == syncPayloadHash(shadow.payloadJson))
                val payload = Json.parseToJsonElement(shadow.payloadJson).jsonObject
                if (shadow.deleted) {
                    if (type == "activity_metric_link") NextCommonFactMapper.validateLinkSnapshot(
                        com.dayforge.data.api.dto.SyncV2Change(0, type, uuid, "delete", shadow.revision, payload, payload.getValue("updated_at").jsonPrimitive.content))
                    else NextStructureMapper.validateTombstone(payload, type, uuid, shadow.revision)
                    requireNoHistory(type, uuid, before)
                    NextRecoveryProjectionSnapshot(type, uuid, null, null, JsonObject(emptyMap()))
                } else {
                    // A legacy physical delete has no saved local identity. Do not invent a new ID.
                    require(before.localId != null) { "RECOVERY_PROJECTION_RETAINED_ROW_REQUIRED" }
                    val local = restoredLocal(before, baseline)
                    if (type == "plan_node" && hasHistory(habit(before.local))) require(habit(before.local).let {
                        it.habitType == habit(local).habitType && it.completionPolicy == habit(local).completionPolicy
                    }) { "RECOVERY_PROJECTION_HISTORY_POLICY_CHANGED" }
                    NextRecoveryProjectionSnapshot(type, uuid, before.localId, body(type, local), local)
                }
            }
            val draft = NextRecoveryProjectionDraft(before, after, baseline)
            validateDraft(draft, origins); result += draft
        }
        validateFinal(result)
        return result
    }

    private suspend fun validateFinal(drafts: List<NextRecoveryProjectionDraft>) {
        val changes = drafts.associateBy { it.after.entityType to it.after.entityUuid }
        val plans = database.habitDao().getAllHabitsOnce().mapNotNull { old ->
            changes["plan_node" to old.uuid]?.after?.let { if (it.localId == null) null else habit(it.local) }
                ?: old.takeIf { "plan_node" to old.uuid !in changes }
        }
        val metrics = database.metricDao().getAllMetricsOnce().mapNotNull { old ->
            changes["metric" to old.uuid]?.after?.let { if (it.localId == null) null else metric(it.local) }
                ?: old.takeIf { "metric" to old.uuid !in changes }
        }
        require(plans.map { it.name }.distinct().size == plans.size) { "RECOVERY_PROJECTION_NAME_CONFLICT" }
        // Pending deletes release visible metric names without changing raw retained rows.
        // Restoring a selected metric must not silently introduce a visible duplicate.
        val visibleMetrics = metrics.filter {
            "metric" to it.uuid in changes || !database.metricDao().hasPendingNextDeletion(it.uuid)
        }
        require(visibleMetrics.map { it.name }.distinct().size == visibleMetrics.size) { "RECOVERY_PROJECTION_NAME_CONFLICT" }
        val parents = plans.associateBy { it.uuid }; val metricIds = metrics.associateBy { it.uuid }
        require(plans.all { it.parentHabitId == null || parents[it.parentHabitId]?.habitType == HabitType.GOAL }) { "RECOVERY_PROJECTION_PARENT_INVALID" }
        // A valid parent kind alone does not prevent self-parenting or cycles among goals.
        val settled = mutableSetOf<String>()
        for (plan in plans) {
            val path = mutableSetOf<String>(); var next: String? = plan.uuid
            while (next != null && next !in settled) {
                require(path.add(next)) { "RECOVERY_PROJECTION_PARENT_CYCLE" }
                next = requireNotNull(parents[next]).parentHabitId
            }
            settled += path
        }
        val links = database.habitMetricLinkDao().getAllLinksOnce().mapNotNull { old ->
            changes["activity_metric_link" to old.uuid]?.after?.let { if (it.localId == null) null else link(it.local) }
                ?: old.takeIf { "activity_metric_link" to old.uuid !in changes }
        }
        require(links.all { parents[it.habitUuid]?.let { p -> p.id == it.habitId && p.habitType != HabitType.GOAL } == true &&
            metricIds[it.metricUuid]?.id == it.metricId }) { "RECOVERY_PROJECTION_LINK_INVALID" }
        require(links.map { it.habitId to it.metricId }.distinct().size == links.size)
    }

    /** No producers, IDs, facts, shadows or notifications. Any failure must roll back the caller's transaction. */
    internal suspend fun apply(drafts: List<NextRecoveryProjectionDraft>) {
        check(database.inTransaction()); NextRequestSql.requireOutboxEnabled(sql)
        require(drafts.isNotEmpty() && drafts.map { it.before.entityType to it.before.entityUuid }.distinct().size == drafts.size)
        for (draft in drafts) {
            validatePair(draft.before, draft.after)
            require(capture(draft.before.entityType, draft.before.entityUuid) == draft.before) { "RECOVERY_PROJECTION_CHANGED" }
            require(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?",
                arrayOf(draft.baseline.entityType, draft.baseline.entityUuid)) == draft.baseline.rowHash) { "RECOVERY_BASELINE_CHANGED" }
            if (draft.after.localId == null) requireNoHistory(draft.before.entityType, draft.before.entityUuid, draft.before)
        }
        validateFinal(drafts)
        val before = allRows()
        val expected = before.toMutableMap()
        for (draft in drafts) {
            val key = draft.after.entityType to draft.after.entityUuid
            if (draft.after.localId == null) expected.remove(key) else expected[key] = draft.after
        }
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        // Explicitly selected links first: FK cascades must not erase unselected relationships.
        for (draft in drafts.filter { it.after.localId == null }.sortedBy { if (it.before.entityType == "activity_metric_link") 0 else 1 }) {
            currentCoroutineContext().ensureActive()
            draft.before.localId?.let { id ->
                sql.execSQL("DELETE FROM ${table(draft.before.entityType)} WHERE id=? AND uuid=?", arrayOf<Any>(id, draft.before.entityUuid))
            }
        }
        // The final names were checked together. Vacate selected names inside this suppressed,
        // invisible transaction so legitimate name permutations do not trip the SQLite unique index.
        val occupied = (before.values + drafts.map { it.after }).mapNotNull { it.local["name"]?.jsonPrimitive?.content }.toMutableSet()
        for (draft in drafts.filter { it.after.entityType == "plan_node" && it.after.localId != null }) {
            var temporary = "__dayforge_recovery_${draft.after.entityUuid}"
            while (!occupied.add(temporary)) temporary += "_"
            sql.execSQL("UPDATE habits SET name=? WHERE id=? AND uuid=?", arrayOf<Any>(temporary, requireNotNull(draft.after.localId), draft.after.entityUuid))
        }
        for (draft in drafts.filter { it.after.localId != null }) {
            currentCoroutineContext().ensureActive()
            val cells = draft.after.local.entries.toList()
            val values = cells.map { (_, value) -> when {
                value == JsonNull -> null
                value.jsonPrimitive.isString -> value.jsonPrimitive.content
                value.jsonPrimitive.longOrNull != null -> value.jsonPrimitive.long
                else -> value.jsonPrimitive.double
            } }
            sql.execSQL("UPDATE ${table(draft.after.entityType)} SET ${cells.joinToString(",") { "`${it.key}`=?" }} WHERE id=? AND uuid=?",
                (values + listOf<Any>(requireNotNull(draft.after.localId), draft.after.entityUuid)).toTypedArray<Any?>())
        }
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        NextRequestSql.requireOutboxEnabled(sql)
        require(allRows() == expected) { "RECOVERY_PROJECTION_WRITE_CHANGED" }
    }

    private suspend fun allRows(): Map<Pair<String, String>, NextRecoveryProjectionSnapshot> {
        val result = linkedMapOf<Pair<String, String>, NextRecoveryProjectionSnapshot>()
        for (type in listOf("plan_node", "metric", "activity_metric_link")) {
            val uuids = sql.query("SELECT uuid FROM ${table(type)} ORDER BY id").use { c -> buildList {
                while (c.moveToNext()) { require(c.getType(0) == Cursor.FIELD_TYPE_STRING); add(c.getString(0)) }
            } }
            for (uuid in uuids) require(result.put(type to uuid, capture(type, uuid)) == null)
        }
        return result
    }

    private suspend fun requireNoHistory(type: String, uuid: String, before: NextRecoveryProjectionSnapshot) {
        if (type == "plan_node" && before.localId != null) require(!hasHistory(habit(before.local))) { "RECOVERY_PROJECTION_FACTS_PROTECTED" }
        if (type == "metric" && before.localId != null) require(database.metricLogDao().getAllLogsForMetric(before.localId).isEmpty()) {
            "RECOVERY_PROJECTION_FACTS_PROTECTED"
        }
        // Accepted remote facts may have only a shadow; absence of a local row is not absence of history.
        for (kind in listOf("activity_event", "metric_observation")) for (shadow in database.syncOutboxDao().getStatesForType(kind)) {
            val body = requireNotNull(shadow.payloadJson).let { Json.parseToJsonElement(it).jsonObject }
            val reference = body[if (kind == "activity_event") "activity_uuid" else "metric_uuid"]
            require(reference is JsonPrimitive && reference.isString && isContractUuid(reference.content))
            require(type != (if (kind == "activity_event") "plan_node" else "metric") || reference.content != uuid) { "RECOVERY_PROJECTION_FACTS_PROTECTED" }
        }
    }

    private suspend fun hasHistory(h: HabitEntity): Boolean {
        if ((h.oneTimeConfirmedVersion ?: 0) > 0 || database.completionDao().getByHabitOnce(h.id).isNotEmpty() ||
            database.timeLogDao().getAllTimeLogsForHabit(h.id).isNotEmpty() || database.completionFollowUpDao().hasActivityHistory(h.uuid) ||
            database.syncOutboxDao().getActivityIntents(h.uuid).isNotEmpty() ||
            sql.query("SELECT 1 FROM count_days WHERE habitId=? LIMIT 1", arrayOf(h.id)).use { it.moveToFirst() }) return true
        val timers = database.timeLogDao()
        for (command in timers.getPendingTimerCommands(Int.MAX_VALUE) + timers.getRejectedTimerCommands()) {
            val activity = command.activityUuid ?: timers.getTimeLogByUuid(command.sessionUuid)?.let {
                database.habitDao().getHabitById(it.habitId)?.uuid
            } ?: error("RECOVERY_PROJECTION_TIMER_ORIGIN_MISSING")
            if (activity == h.uuid) return true
        }
        return database.syncOutboxDao().getStatesForType("activity_event").any { shadow ->
            val payload = Json.parseToJsonElement(requireNotNull(shadow.payloadJson)).jsonObject
            val activity = payload["activity_uuid"]
            require(activity is JsonPrimitive && activity.isString && isContractUuid(activity.content))
            activity.content == h.uuid
        }
    }

    private fun validate(snapshot: NextRecoveryProjectionSnapshot) {
        if (snapshot.localId == null) return
        validateLocal(snapshot.entityType, snapshot.local)
        require(snapshot.local.long("id") == snapshot.localId && snapshot.local.text("uuid") == snapshot.entityUuid &&
            body(snapshot.entityType, snapshot.local) == snapshot.body)
    }

    private fun columns(type: String): Map<String, String> = sql.query("PRAGMA table_info(${table(type)})").use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(1), c.getString(2)) }
    }
    private fun validateLocal(type: String, row: JsonObject) {
        val columns = columns(type); require(row.keys == columns.keys)
        for ((name, value) in row) if (value != JsonNull) {
            val scalar = requireNotNull(value as? JsonPrimitive)
            when (columns.getValue(name)) {
                "INTEGER" -> require(!scalar.isString && scalar.longOrNull != null)
                "REAL" -> require(!scalar.isString && scalar.doubleOrNull?.isFinite() == true)
                "TEXT" -> require(scalar.isString)
                else -> error("RECOVERY_PROJECTION_COLUMN_INVALID")
            }
        }
    }
    private fun body(type: String, row: JsonObject): JsonObject = when (type) {
        "plan_node" -> NextStructureMapper.writePlan(habit(row))
        "metric" -> NextStructureMapper.writeMetric(metric(row))
        else -> link(row).let { l -> buildJsonObject {
            put("activity_uuid", l.habitUuid); put("metric_uuid", l.metricUuid); put("coefficient", l.coefficient)
            put("show_in_activity_detail", l.showInHabitDetail); put("prompt_on_complete", l.promptOnComplete); put("is_active", l.isActive)
        }.also(NextCommonFactMapper::validateLinkWrite) }
    }

    private fun JsonObject.text(name: String): String = requireNotNull(getValue(name) as? JsonPrimitive).also { require(it.isString) }.content
    private fun JsonObject.nullText(name: String) = if (getValue(name) == JsonNull) null else text(name)
    private fun JsonObject.long(name: String): Long = requireNotNull(getValue(name) as? JsonPrimitive).also { require(!it.isString) }.long
    private fun JsonObject.nullLong(name: String) = if (getValue(name) == JsonNull) null else long(name)
    private fun JsonObject.int(name: String) = long(name).also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }.toInt()
    private fun JsonObject.nullInt(name: String) = if (getValue(name) == JsonNull) null else int(name)
    private fun JsonObject.bool(name: String) = long(name).also { require(it in 0..1) } == 1L
    private fun JsonObject.nullBool(name: String) = if (getValue(name) == JsonNull) null else bool(name)
    private fun JsonObject.double(name: String) = getValue(name).jsonPrimitive.also { require(!it.isString) }.double.also { require(it.isFinite()) }
    private fun JsonObject.nullDouble(name: String) = if (getValue(name) == JsonNull) null else double(name)
    private fun habit(r: JsonObject) = HabitEntity(id = r.long("id"), name = r.text("name"), description = r.text("description"),
        habitType = HabitType.valueOf(r.text("habitType")), iconResId = r.int("iconResId"), colorHex = r.text("colorHex"),
        schedule = Json.decodeFromString<HabitSchedule>(r.text("schedule")), targetValue = r.int("targetValue"), isCountdown = r.bool("isCountdown"),
        isActive = r.bool("isActive"), uuid = r.text("uuid"), parentHabitId = r.nullText("parentHabitId"), targetCycles = r.nullInt("targetCycles"),
        failMode = FailMode.valueOf(r.text("failMode")), goalSuccess = r.nullBool("goalSuccess"), activityRate = r.int("activityRate"),
        activityRateUpdatedAt = r.long("activityRateUpdatedAt"), bestTime = r.nullLong("bestTime"), createdAt = r.long("createdAt"), updatedAt = r.long("updatedAt"),
        completionPolicy = r.nullText("completionPolicy"), oneTimeConfirmedVersion = r.nullInt("oneTimeConfirmedVersion"),
        oneTimeConfirmedHeadEventUuid = r.nullText("oneTimeConfirmedHeadEventUuid"), oneTimeConfirmedCompletionEventUuid = r.nullText("oneTimeConfirmedCompletionEventUuid"),
        appearance = r.nullText("appearance")?.let { Json.decodeFromString<ObjectAppearance>(it) },
        planMetadata = r.nullText("planMetadata")?.let { Json.decodeFromString<PlanStructureMetadata>(it) })
    private fun metric(r: JsonObject) = MetricEntity(id = r.long("id"), name = r.text("name"), description = r.text("description"), unit = r.text("unit"),
        decimalPlaces = r.int("decimalPlaces"), aggregationType = r.text("aggregationType"), targetDirection = r.nullText("targetDirection"),
        targetValue = r.nullDouble("targetValue"), targetValueUpper = r.nullDouble("targetValueUpper"), iconResId = r.int("iconResId"), colorHex = r.text("colorHex"),
        isActive = r.bool("isActive"), uuid = r.text("uuid"), createdAt = r.long("createdAt"), updatedAt = r.long("updatedAt"),
        appearance = r.nullText("appearance")?.let { Json.decodeFromString<ObjectAppearance>(it) })
    private fun link(r: JsonObject) = HabitMetricLinkEntity(id = r.long("id"), habitId = r.long("habitId"), habitUuid = r.text("habitUuid"),
        metricId = r.long("metricId"), metricUuid = r.text("metricUuid"), coefficient = r.double("coefficient"), showInHabitDetail = r.bool("showInHabitDetail"),
        promptOnComplete = r.bool("promptOnComplete"), isActive = r.bool("isActive"), uuid = r.text("uuid"), createdAt = r.long("createdAt"), updatedAt = r.long("updatedAt"))

    private fun planLocal(h: HabitEntity) = buildJsonObject {
        put("id", h.id); put("name", h.name); put("description", h.description); put("habitType", h.habitType.name); put("iconResId", h.iconResId)
        put("colorHex", h.colorHex); put("schedule", Json.encodeToString(h.schedule)); put("targetValue", h.targetValue); put("isCountdown", if (h.isCountdown) 1 else 0)
        put("isActive", if (h.isActive) 1 else 0); put("uuid", h.uuid); put("parentHabitId", h.parentHabitId?.let(::JsonPrimitive) ?: JsonNull)
        put("targetCycles", h.targetCycles?.let(::JsonPrimitive) ?: JsonNull); put("failMode", h.failMode.name); put("goalSuccess", h.goalSuccess?.let { JsonPrimitive(if (it) 1 else 0) } ?: JsonNull)
        put("activityRate", h.activityRate); put("activityRateUpdatedAt", h.activityRateUpdatedAt); put("bestTime", h.bestTime?.let(::JsonPrimitive) ?: JsonNull)
        put("createdAt", h.createdAt); put("updatedAt", h.updatedAt); put("completionPolicy", h.completionPolicy?.let(::JsonPrimitive) ?: JsonNull)
        put("oneTimeConfirmedVersion", h.oneTimeConfirmedVersion?.let(::JsonPrimitive) ?: JsonNull)
        put("oneTimeConfirmedHeadEventUuid", h.oneTimeConfirmedHeadEventUuid?.let(::JsonPrimitive) ?: JsonNull)
        put("oneTimeConfirmedCompletionEventUuid", h.oneTimeConfirmedCompletionEventUuid?.let(::JsonPrimitive) ?: JsonNull)
        put("appearance", h.appearance?.let { JsonPrimitive(Json.encodeToString(it)) } ?: JsonNull)
        put("planMetadata", h.planMetadata?.let { JsonPrimitive(Json.encodeToString(it)) } ?: JsonNull)
    }
    private fun metricLocal(m: MetricEntity) = buildJsonObject {
        put("id", m.id); put("name", m.name); put("description", m.description); put("unit", m.unit); put("decimalPlaces", m.decimalPlaces)
        put("aggregationType", m.aggregationType); put("targetDirection", m.targetDirection?.let(::JsonPrimitive) ?: JsonNull)
        put("targetValue", m.targetValue?.let(::JsonPrimitive) ?: JsonNull); put("targetValueUpper", m.targetValueUpper?.let(::JsonPrimitive) ?: JsonNull)
        put("iconResId", m.iconResId); put("colorHex", m.colorHex); put("isActive", if (m.isActive) 1 else 0); put("uuid", m.uuid)
        put("createdAt", m.createdAt); put("updatedAt", m.updatedAt); put("appearance", m.appearance?.let { JsonPrimitive(Json.encodeToString(it)) } ?: JsonNull)
    }
    private fun linkLocal(l: HabitMetricLinkEntity) = buildJsonObject {
        put("id", l.id); put("habitId", l.habitId); put("habitUuid", l.habitUuid); put("metricId", l.metricId); put("metricUuid", l.metricUuid)
        put("coefficient", l.coefficient); put("showInHabitDetail", if (l.showInHabitDetail) 1 else 0); put("promptOnComplete", if (l.promptOnComplete) 1 else 0)
        put("isActive", if (l.isActive) 1 else 0); put("uuid", l.uuid); put("createdAt", l.createdAt); put("updatedAt", l.updatedAt)
    }
}
