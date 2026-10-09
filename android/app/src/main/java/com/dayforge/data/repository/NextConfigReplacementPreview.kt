package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.model.HabitType
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Counts describe destructive replacement, not an estimate of server deletion or a write ticket. */
internal data class ConfigReplacementCounts(val goals: Long, val habits: Long, val items: Long,
    val metrics: Long, val links: Long, val checkAndCountRecords: Long, val timerSessions: Long,
    val metricRecords: Long)

internal enum class ConfigReplacementBlocker {
    ACTIVE_TIMER, PENDING_WORK, CONFLICT_OR_RECOVERY, COMPLETION_PROMPT,
    UNCONFIRMED_REPLICA, UNCONFIRMED_STRUCTURE, QUEUE_CAPACITY
}

internal class NextConfigReplacementPreview internal constructor(
    internal val original: NextConfigImportPreview,
    val counts: ConfigReplacementCounts,
    blockers: Set<ConfigReplacementBlocker>,
    internal val fingerprint: String
) {
    val blockers: Set<ConfigReplacementBlocker> = Collections.unmodifiableSet(blockers.toSet())
    val eligible: Boolean get() = blockers.isEmpty()
}

/** Read participant only. Caller owns the actual account lock and one Room snapshot. */
internal class NextConfigReplacementInspector(private val database: HabitDatabase) {
    private val sql get() = database.openHelper.writableDatabase

    suspend fun capture(original: NextConfigImportPreview, preparedImportId: String? = null): NextConfigReplacementPreview {
        check(database.inTransaction())
        NextRequestSql.requireOutboxEnabled(sql)
        val habits = database.habitDao().getAllHabitsOnce()
        val metrics = database.metricDao().getAllMetricsOnce()
        val links = database.habitMetricLinkDao().getAllLinksOnce()
        // Invalid/legacy objects are never silently omitted from a destructive preview.
        val habitByUuid = habits.associateBy { it.uuid }
        val metricByUuid = metrics.associateBy { it.uuid }
        val linkByUuid = links.associateBy { it.uuid }
        require(habitByUuid.size == habits.size && metricByUuid.size == metrics.size &&
            links.map { it.uuid }.distinct().size == links.size) { "CONFIG_REPLACEMENT_INVALID_GRAPH" }
        habits.forEach { row ->
            NextStructureMapper.writePlan(row)
            row.parentHabitId?.let { parent -> require(row.habitType != HabitType.GOAL &&
                habitByUuid[parent]?.let { it.habitType == HabitType.GOAL && it.parentHabitId == null } == true) {
                "CONFIG_REPLACEMENT_INVALID_GRAPH"
            } }
        }
        metrics.forEach { NextStructureMapper.writeMetric(it) }
        links.forEach { row ->
            require(habitByUuid[row.habitUuid]?.let { it.id == row.habitId && it.habitType != HabitType.GOAL } == true &&
                metricByUuid[row.metricUuid]?.id == row.metricId) { "CONFIG_REPLACEMENT_INVALID_GRAPH" }
            com.dayforge.data.api.dto.validateNextSyncOperation(com.dayforge.data.api.dto.SyncV2Operation(
                row.uuid, "activity_metric_link", row.uuid, "upsert", null, SyncV2Mapper.link(row)))
        }
        val blockers = buildSet {
            if (exists("timelogs", "endTime IS NULL")) add(ConfigReplacementBlocker.ACTIVE_TIMER)
            if (exists("sync_outbox") || exists("timer_command_outbox")) add(ConfigReplacementBlocker.PENDING_WORK)
            val unresolvedRejection = sql.query("SELECT 1 FROM next_rejections r LEFT JOIN next_acceptances a " +
                "ON a.kind=r.kind AND a.requestId=r.requestId WHERE a.requestId IS NULL LIMIT 1").use { it.moveToFirst() }
            if (exists("sync_conflicts", "status!='resolved'") || unresolvedRejection || exists("next_recovery_state"))
                add(ConfigReplacementBlocker.CONFLICT_OR_RECOVERY)
            if (exists("completion_metric_prompts", "state='pending'")) add(ConfigReplacementBlocker.COMPLETION_PROMPT)
            if (!exists("next_sync_state")) add(ConfigReplacementBlocker.UNCONFIRMED_REPLICA)
            val objects = habits.map { "plan_node" to it.uuid } + metrics.map { "metric" to it.uuid } +
                links.map { "activity_metric_link" to it.uuid }
            for ((type, uuid) in objects) {
                currentCoroutineContext().ensureActive()
                NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf(type, uuid))
                val shadow = database.syncOutboxDao().getState(type, uuid)
                if (shadow == null || shadow.deleted) add(ConfigReplacementBlocker.UNCONFIRMED_STRUCTURE)
                else {
                    require(shadow.revision > 0 && shadow.payloadJson != null &&
                        shadow.payloadHash == syncPayloadHash(shadow.payloadJson)) { "CONFIG_REPLACEMENT_INVALID_SHADOW" }
                    val payload = Json.parseToJsonElement(shadow.payloadJson).jsonObject
                    val expected: JsonObject
                    val accepted: JsonObject
                    when (type) {
                        "plan_node" -> {
                            val row = habitByUuid.getValue(uuid)
                            expected = NextStructureMapper.writePlan(row)
                            accepted = NextStructureMapper.writePlan(NextStructureMapper.readPlan(payload, uuid, shadow.revision, row))
                        }
                        "metric" -> {
                            val row = metricByUuid.getValue(uuid)
                            expected = NextStructureMapper.writeMetric(row)
                            accepted = NextStructureMapper.writeMetric(NextStructureMapper.readMetric(payload, uuid, shadow.revision, row))
                        }
                        else -> {
                            val row = linkByUuid.getValue(uuid)
                            expected = SyncV2Mapper.link(row)
                            val change = SyncV2Change(0, type, uuid, "upsert", shadow.revision, payload,
                                java.time.Instant.ofEpochMilli(row.updatedAt).toString())
                            accepted = SyncV2Mapper.link(NextCommonFactMapper.link(change,
                                habitByUuid.getValue(row.habitUuid), metricByUuid.getValue(row.metricUuid), row))
                        }
                    }
                    if (accepted != expected) add(ConfigReplacementBlocker.UNCONFIRMED_STRUCTURE)
                }
            }
            val incoming = original.source.manifest.let { it.nodes.size.toLong() + it.metrics.size + it.links.size }
            // Admission to the existing complete producer audit, not a new product object limit.
            if (!fitsQueue(habits.size.toLong() + metrics.size + links.size, incoming))
                add(ConfigReplacementBlocker.QUEUE_CAPACITY)
        }
        val counts = ConfigReplacementCounts(habits.count { it.habitType == HabitType.GOAL }.toLong(),
            habits.count { it.habitType != HabitType.GOAL && it.completionPolicy != "one_and_done" }.toLong(),
            habits.count { it.completionPolicy == "one_and_done" }.toLong(), metrics.size.toLong(), links.size.toLong(),
            count("completions"), count("timelogs"), count("metric_logs"))
        return NextConfigReplacementPreview(original, counts, blockers, fingerprint(preparedImportId))
    }

    private fun exists(table: String, where: String = "1=1") =
        sql.query("SELECT 1 FROM $table WHERE $where LIMIT 1").use { it.moveToFirst() }

    private fun count(table: String) = sql.query("SELECT COUNT(*) FROM $table").use {
        check(it.moveToFirst()); require(it.getType(0) == Cursor.FIELD_TYPE_INTEGER); it.getLong(0)
    }

    /** Hash actual SQLite values/types, including facts and retained journals; never Room coercions.
     * Pages keep arbitrary history out of a single CursorWindow. This proof is only in memory.
     */
    private suspend fun fingerprint(preparedImportId: String?): String {
        val caller = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        fun bytes(value: ByteArray) {
            digest.update(ByteBuffer.allocate(4).putInt(value.size).array()); digest.update(value)
        }
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND " +
            "name NOT IN ('android_metadata','room_master_table') ORDER BY name").use { c -> buildList {
            while (c.moveToNext()) { require(c.getType(0) == Cursor.FIELD_TYPE_STRING); add(c.getString(0)) }
        } }
        for (table in tables) {
            caller.ensureActive()
            require(table.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")))
            bytes(table.toByteArray(Charsets.UTF_8))
            val columns = sql.query("PRAGMA table_info(`$table`)").use { c -> buildList {
                while (c.moveToNext()) add(c.getString(1))
            } }
            require(columns.isNotEmpty() && columns.all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) })
            val length = columns.joinToString("+") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
            // Only this independently validated prepared journal was added after confirmation.
            // Do not exclude other imports, old receipts, facts or any business value.
            val excluded = preparedImportId != null && table in setOf("next_config_imports", "next_config_import_payloads")
            var last: Long? = null
            while (true) {
                caller.ensureActive()
                val boundary = last
                // Bound individual damaged rows BEFORE Android materializes TEXT/BLOB.
                val predicates = buildList {
                    if (boundary != null) add("rowid>?")
                    if (excluded) add("importId!=?")
                }
                val arguments = buildList<Any> {
                    if (boundary != null) add(boundary)
                    if (excluded) add(requireNotNull(preparedImportId))
                }.toTypedArray()
                val where = if (predicates.isEmpty()) "" else "WHERE ${predicates.joinToString(" AND ")} "
                val ids = sql.query("SELECT rowid,($length) FROM `$table` $where ORDER BY rowid LIMIT 128",
                    arguments).use { c -> buildList {
                    var bytes = 0L
                    while (c.moveToNext()) {
                        caller.ensureActive()
                        require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER &&
                            (lastOrNull() ?: boundary)?.let { c.getLong(0) > it } != false &&
                            c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) in 0L..ROW_LIMIT) {
                            "CONFIG_REPLACEMENT_INVALID_STORAGE"
                        }
                        val size = c.getLong(1)
                        if (isNotEmpty() && bytes + size > ROW_LIMIT) break
                        bytes += size; add(c.getLong(0))
                    }
                } }
                if (ids.isEmpty()) break
                sql.query("SELECT * FROM `$table` WHERE ${(predicates + "rowid<=?").joinToString(" AND ")} ORDER BY rowid",
                    arguments + ids.last()).use { c ->
                    require(c.columnCount == columns.size)
                    var rows = 0
                    while (c.moveToNext()) {
                        caller.ensureActive(); rows++
                        digest.update(1.toByte())
                        for (i in columns.indices) {
                            caller.ensureActive(); bytes(columns[i].toByteArray(Charsets.UTF_8))
                            val type = c.getType(i); digest.update(type.toByte())
                            when (type) {
                                Cursor.FIELD_TYPE_NULL -> Unit
                                Cursor.FIELD_TYPE_INTEGER -> bytes(ByteBuffer.allocate(8).putLong(c.getLong(i)).array())
                                Cursor.FIELD_TYPE_FLOAT -> bytes(ByteBuffer.allocate(8).putLong(c.getDouble(i).toRawBits()).array())
                                Cursor.FIELD_TYPE_STRING -> bytes(c.getString(i).toByteArray(Charsets.UTF_8))
                                Cursor.FIELD_TYPE_BLOB -> bytes(c.getBlob(i))
                                else -> error("CONFIG_REPLACEMENT_INVALID_STORAGE")
                            }
                        }
                    }
                    require(rows == ids.size)
                }
                last = ids.last()
            }
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ROW_LIMIT = com.dayforge.data.api.SYNC_REQUEST_LIMIT + 4096L
        internal fun fitsQueue(deletions: Long, creations: Long): Boolean {
            require(deletions >= 0 && creations >= 0)
            return creations <= 10_000 && deletions <= 10_000 - creations
        }
    }
}
