@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class,
    com.dayforge.domain.model.ContractLongSerializer::class)

package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.appearance.AccountIconContext
import com.dayforge.data.appearance.ValidatedConfigBundle
import com.dayforge.data.appearance.strictAppearanceJson
import com.dayforge.data.export.ConfigImportIdentities
import com.dayforge.data.export.ConfigImportTarget
import com.dayforge.data.export.NextConfigImportPlan
import com.dayforge.data.local.HabitDatabase
import com.dayforge.domain.model.isContractUuid
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ConfigImportedSource(val queueId: Long, val sourceHash: String) {
    init { require(queueId > 0 && sourceHash.matches(Regex("[0-9a-f]{64}"))) }
}

/** Caller owns account lock/Room transaction. Frozen source must be supplied again on cold recovery. */
internal class NextConfigImportStore(private val database: HabitDatabase) {
    internal data class Entry(val plan: NextConfigImportPlan, val committed: Boolean,
        val sources: Map<String, ConfigImportedSource>)
    private val sql get() = database.openHelper.writableDatabase

    fun pending(context: AccountIconContext): String? {
        check(database.inTransaction())
        sql.query("SELECT COUNT(*),MAX(length(CAST(importId AS BLOB))+length(CAST(accountId AS BLOB))+" +
            "length(CAST(serverInstanceId AS BLOB))+length(CAST(syncEpoch AS BLOB))+length(CAST(deviceId AS BLOB))+" +
            "length(CAST(mode AS BLOB))+length(CAST(state AS BLOB))) FROM next_config_imports WHERE state!='local_committed'").use { c ->
            check(c.moveToFirst()); require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) in 0L..1L)
            if (c.getLong(0) == 0L) return null
            require(c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) in 1L..1024L)
        }
        return sql.query("SELECT importId,accountId,serverInstanceId,syncEpoch,deviceId,mode,state FROM next_config_imports " +
            "WHERE state!='local_committed'").use { c ->
            check(c.moveToFirst()); for (i in 0..6) require(c.getType(i) == Cursor.FIELD_TYPE_STRING)
            val id = c.getString(0); require(isContractUuid(id) && c.getString(5) == "empty_replica" && c.getString(6) == "prepared")
            check(ConfigImportTarget(c.getString(1), c.getString(2), c.getString(3), c.getString(4)) ==
                ConfigImportTarget.from(context)) { "CONFIG_IMPORT_TARGET_CHANGED" }
            require(!c.moveToNext()); id
        }
    }

    suspend fun read(context: AccountIconContext, importId: String, source: ValidatedConfigBundle): Entry {
        check(database.inTransaction()); require(isContractUuid(importId))
        val columns = listOf("importId", "accountId", "serverInstanceId", "syncEpoch", "deviceId", "archiveHash",
            "identitiesHash", "mode", "state", "receiptHash")
        // Bound every header field before asking Android's cursor to materialize it.
        sql.query("SELECT ${columns.joinToString(",") { "length(CAST($it AS BLOB))" }} FROM next_config_imports WHERE importId=?",
            arrayOf<Any>(importId)).use { c ->
            check(c.moveToFirst()) { "CONFIG_IMPORT_NOT_FOUND" }
            for (i in columns.indices) require(c.getType(i) == Cursor.FIELD_TYPE_NULL && i == 9 ||
                c.getType(i) == Cursor.FIELD_TYPE_INTEGER && c.getLong(i) in 1L..128L)
            require(!c.moveToNext())
        }
        val header = sql.query("SELECT ${columns.joinToString(",")} FROM next_config_imports WHERE importId=?",
            arrayOf<Any>(importId)).use { c ->
            check(c.moveToFirst())
            columns.indices.map { i ->
                require(c.getType(i) == Cursor.FIELD_TYPE_STRING || i == 9 && c.getType(i) == Cursor.FIELD_TYPE_NULL)
                if (c.isNull(i)) null else c.getString(i)
            }.also { require(!c.moveToNext()) }
        }
        val target = ConfigImportTarget(requireNotNull(header[1]), requireNotNull(header[2]),
            requireNotNull(header[3]), requireNotNull(header[4]))
        check(target == ConfigImportTarget.from(context)) { "CONFIG_IMPORT_TARGET_CHANGED" }
        require(header[0] == importId && header[7] == "empty_replica" && header[8] in setOf("prepared", "local_committed"))
        val identities = ConfigImportIdentities.decode(payload(importId, "identities", requireNotNull(header[6])))
        val plan = NextConfigImportPlan(source, identities)
        require(plan.importId == importId && identities.target == target && identities.archiveHash == header[5])
        plan.requireTarget(context)
        val committed = header[8] == "local_committed"
        val kinds = sql.query("SELECT DISTINCT payloadKind FROM next_config_import_payloads WHERE importId=?",
            arrayOf<Any>(importId)).use { c -> buildSet { while (c.moveToNext()) {
                require(c.getType(0) == Cursor.FIELD_TYPE_STRING); add(c.getString(0))
            } } }
        require(kinds == if (committed) setOf("identities", "receipt") else setOf("identities"))
        val sources: Map<String, ConfigImportedSource> = if (!committed) {
            require(header[9] == null); emptyMap()
        } else {
            val bytes = payload(importId, "receipt", requireNotNull(header[9]))
            Json.decodeFromString(strictAppearanceJson(bytes, LIMIT, {}) { error("CONFIG_IMPORT_RECEIPT_$it") })
        }
        if (committed) {
            require(sources.keys == plan.creationOperationIds.values.toSet() && sources.values.map { it.queueId }.distinct().size == sources.size)
            val expected = buildMap {
                plan.habits.forEach { put(it.uuid, "plan_node" to NextStructureMapper.writePlan(it)) }
                plan.metrics.forEach { put(it.uuid, "metric" to NextStructureMapper.writeMetric(it)) }
                plan.links(plan.habits.mapIndexed { i, row -> row.uuid to i.toLong() + 1 }.toMap(),
                    plan.metrics.mapIndexed { i, row -> row.uuid to i.toLong() + 1 }.toMap())
                    .forEach { put(it.uuid, "activity_metric_link" to SyncV2Mapper.link(it)) }
            }
            for ((id, proof) in sources) {
                currentCoroutineContext().ensureActive()
                requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)))
                val origin = requireNotNull(database.nextRequestDao().origin(NEXT_OPERATION, id))
                val original = decodeNextOperationIntent(origin.intentJson)
                require(origin.protocol == 5 && origin.queueId == proof.queueId && origin.sourceHash == proof.sourceHash &&
                    origin.accountId == target.accountId && origin.serverInstanceId == target.serverInstanceId &&
                    origin.syncEpoch == target.syncEpoch && original.operationId == id && original.action == "upsert" &&
                    plan.creationOperationIds[original.entityUuid] == id && original.baseRevision == null &&
                    expected[original.entityUuid] == (original.entityType to original.payload))
            }
        }
        return Entry(plan, committed, sources.toMap())
    }

    suspend fun prepare(plan: NextConfigImportPlan) {
        check(database.inTransaction())
        requireNoOtherPending()
        val ids = plan.identities; val target = ids.target; val bytes = ids.encode().toByteArray(Charsets.UTF_8)
        sql.execSQL("INSERT INTO next_config_imports(importId,accountId,serverInstanceId,syncEpoch,deviceId,archiveHash," +
            "identitiesHash,mode,state,receiptHash) VALUES(?,?,?,?,?,?,?,'empty_replica','prepared',NULL)",
            arrayOf<Any>(plan.importId, target.accountId, target.serverInstanceId, target.syncEpoch, target.deviceId,
                ids.archiveHash, nextRequestHash(bytes)))
        writePayload(plan.importId, "identities", bytes)
    }

    /** Must run inside the original producer callback, before it seals all newly created origins. */
    suspend fun committed(plan: NextConfigImportPlan, sources: Map<String, ConfigImportedSource>) {
        check(database.inTransaction())
        require(sources.keys == plan.creationOperationIds.values.toSet())
        val bytes = Json.encodeToString(sources).toByteArray(Charsets.UTF_8)
        require(bytes.size <= LIMIT)
        writePayload(plan.importId, "receipt", bytes)
        sql.compileStatement("UPDATE next_config_imports SET state='local_committed',receiptHash=? WHERE importId=? AND state='prepared'").use {
            it.bindString(1, nextRequestHash(bytes)); it.bindString(2, plan.importId)
            check(it.executeUpdateDelete() == 1) { "CONFIG_IMPORT_STATE_CHANGED" }
        }
    }

    fun requireNoOtherPending(ownId: String? = null) {
        check(database.inTransaction())
        sql.query("SELECT importId,state FROM next_config_imports WHERE state!='local_committed' ORDER BY importId").use { c ->
            while (c.moveToNext()) {
                require(c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getType(1) == Cursor.FIELD_TYPE_STRING)
                check(ownId != null && c.getString(0) == ownId && c.getString(1) == "prepared") { "CONFIG_IMPORT_PENDING" }
            }
        }
        sql.query("SELECT 1 FROM next_config_import_payloads p LEFT JOIN next_config_imports j ON j.importId=p.importId " +
            "WHERE j.importId IS NULL LIMIT 1").use { require(!it.moveToFirst()) }
    }

    private suspend fun writePayload(importId: String, kind: String, bytes: ByteArray) {
        require(bytes.size in 1..LIMIT)
        var start = 0; var part = 0
        while (start < bytes.size) {
            currentCoroutineContext().ensureActive()
            val end = minOf(start + CHUNK, bytes.size)
            sql.execSQL("INSERT INTO next_config_import_payloads(importId,payloadKind,part,bytes) VALUES(?,?,?,?)",
                arrayOf<Any>(importId, kind, part++, bytes.copyOfRange(start, end)))
            start = end
        }
    }

    private suspend fun payload(importId: String, kind: String, hash: String): ByteArray {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        val args = arrayOf<Any>(importId, kind)
        val lengths = sql.query("SELECT part,length(bytes),typeof(bytes) FROM next_config_import_payloads " +
            "WHERE importId=? AND payloadKind=? ORDER BY part", args).use { c -> buildList {
                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    require(size < LIMIT / CHUNK && c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) == size.toLong() &&
                        c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) in 1L..CHUNK.toLong() && c.getString(2) == "blob")
                    add(c.getInt(1))
                }
            } }
        require(lengths.isNotEmpty() && lengths.dropLast(1).all { it == CHUNK })
        val bytes = ByteArrayOutputStream(lengths.sum()).use { output ->
            sql.query("SELECT bytes FROM next_config_import_payloads WHERE importId=? AND payloadKind=? ORDER BY part", args).use { c ->
                var part = 0
                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    require(c.getType(0) == Cursor.FIELD_TYPE_BLOB)
                    val value = c.getBlob(0); require(value.size == lengths[part++]); output.write(value)
                }
                require(part == lengths.size)
            }
            output.toByteArray()
        }
        require(nextRequestHash(bytes) == hash) { "CONFIG_IMPORT_JOURNAL_CHANGED" }
        return bytes
    }

    private companion object { const val LIMIT = 2_097_152; const val CHUNK = 65_536 }
}
