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
        val sources: Map<String, ConfigImportedSource>, val network: ConfigNetworkPlan? = null)
    internal data class NetworkEntry(val plan: ConfigNetworkPlan, val receipt: ConfigNetworkReceipt,
        val accepted: Map<String, String>?, val proof: String, val sourceProof: String)
    private val sql get() = database.openHelper.writableDatabase

    fun pending(context: AccountIconContext): String? {
        check(database.inTransaction())
        sql.query("SELECT COUNT(*),MAX(length(CAST(importId AS BLOB))+length(CAST(accountId AS BLOB))+" +
            "length(CAST(serverInstanceId AS BLOB))+length(CAST(syncEpoch AS BLOB))+length(CAST(deviceId AS BLOB))+" +
            "length(CAST(mode AS BLOB))+length(CAST(state AS BLOB))) FROM next_config_imports WHERE state NOT IN ('local_committed','operations_accepted')").use { c ->
            check(c.moveToFirst()); require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) in 0L..1L)
            if (c.getLong(0) == 0L) return null
            require(c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) in 1L..1024L)
        }
        return sql.query("SELECT importId,accountId,serverInstanceId,syncEpoch,deviceId,mode,state FROM next_config_imports " +
            "WHERE state NOT IN ('local_committed','operations_accepted')").use { c ->
            check(c.moveToFirst()); for (i in 0..6) require(c.getType(i) == Cursor.FIELD_TYPE_STRING)
            val id = c.getString(0); require(isContractUuid(id) && c.getString(5) in setOf("empty_replica", "replacement") && c.getString(6) == "prepared")
            check(ConfigImportTarget(c.getString(1), c.getString(2), c.getString(3), c.getString(4)) ==
                ConfigImportTarget.from(context)) { "CONFIG_IMPORT_TARGET_CHANGED" }
            require(!c.moveToNext()); id
        }
    }

    suspend fun read(context: AccountIconContext, importId: String, source: ValidatedConfigBundle): Entry {
        check(database.inTransaction()); require(isContractUuid(importId))
        val header = header(importId)
        val target = ConfigImportTarget(requireNotNull(header[1]), requireNotNull(header[2]),
            requireNotNull(header[3]), requireNotNull(header[4]))
        check(target == ConfigImportTarget.from(context)) { "CONFIG_IMPORT_TARGET_CHANGED" }
        require(header[0] == importId && header[7] in setOf("empty_replica", "replacement"))
        val identities = ConfigImportIdentities.decode(payload(importId, "identities", requireNotNull(header[6])))
        val plan = NextConfigImportPlan(source, identities)
        require(plan.importId == importId && identities.target == target && identities.archiveHash == header[5])
        plan.requireTarget(context)
        if (header[7] == "replacement") {
            if (header[8] == "prepared") {
                require(kinds(importId) == setOf("identities", "network"))
                val network = ConfigNetworkPlan.decode(payload(importId, "network", requireNotNull(header[9])))
                network.requirePlan(plan)
                return Entry(plan, false, emptyMap(), network)
            }
            val network = network(importId)
            network.plan.requirePlan(plan)
            return Entry(plan, true, network.receipt.sources, network.plan)
        }
        require(header[8] in setOf("prepared", "local_committed"))
        val committed = header[8] == "local_committed"
        require(kinds(importId) == if (committed) setOf("identities", "receipt") else setOf("identities"))
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

    private fun header(importId: String): List<String?> {
        require(isContractUuid(importId))
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
        return sql.query("SELECT ${columns.joinToString(",")} FROM next_config_imports WHERE importId=?",
            arrayOf<Any>(importId)).use { c ->
            check(c.moveToFirst())
            columns.indices.map { i ->
                require(c.getType(i) == Cursor.FIELD_TYPE_STRING || i == 9 && c.getType(i) == Cursor.FIELD_TYPE_NULL)
                if (c.isNull(i)) null else c.getString(i)
            }.also { require(!c.moveToNext()) }
        }
    }

    private fun kinds(importId: String): Set<String> =
        sql.query("SELECT DISTINCT payloadKind FROM next_config_import_payloads WHERE importId=?",
            arrayOf<Any>(importId)).use { c -> buildSet { while (c.moveToNext()) {
                require(c.getType(0) == Cursor.FIELD_TYPE_STRING); add(c.getString(0))
            } } }

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

    suspend fun prepareReplacement(plan: NextConfigImportPlan, network: ConfigNetworkPlan) {
        check(database.inTransaction()); requireNoOtherPending(); requireNoActiveReplacement()
        network.requirePlan(plan)
        val bytes = plan.identities.encode().toByteArray(Charsets.UTF_8)
        val frozen = network.encode(); val target = plan.identities.target
        sql.execSQL("INSERT INTO next_config_imports(importId,accountId,serverInstanceId,syncEpoch,deviceId,archiveHash," +
            "identitiesHash,mode,state,receiptHash) VALUES(?,?,?,?,?,?,?,'replacement','prepared',?)",
            arrayOf<Any>(plan.importId, target.accountId, target.serverInstanceId, target.syncEpoch, target.deviceId,
                plan.identities.archiveHash, nextRequestHash(bytes), nextRequestHash(frozen)))
        writePayload(plan.importId, "identities", bytes); writePayload(plan.importId, "network", frozen)
    }

    suspend fun committedReplacement(network: ConfigNetworkPlan, sources: Map<String, ConfigImportedSource>) {
        check(database.inTransaction())
        require(sources.keys == network.steps.map { it.operationId }.toSet() &&
            sources.values.map { it.queueId }.distinct().size == sources.size)
        val bytes = Json.encodeToString(ConfigNetworkReceipt(nextRequestHash(network.encode()), sources)).toByteArray(Charsets.UTF_8)
        writePayload(network.importId, "receipt", bytes)
        sql.compileStatement("UPDATE next_config_imports SET state='local_committed',receiptHash=? " +
            "WHERE importId=? AND mode='replacement' AND state='prepared'").use {
            it.bindString(1, nextRequestHash(bytes)); it.bindString(2, network.importId)
            check(it.executeUpdateDelete() == 1) { "CONFIG_IMPORT_STATE_CHANGED" }
        }
    }

    /** Source-only audit also works after ACK; never infers acceptance from absent outbox rows. */
    suspend fun network(importId: String): NetworkEntry {
        check(database.inTransaction())
        val head = header(importId)
        require(head[7] == "replacement" && head[8] in setOf("local_committed", "operations_accepted"))
        val complete = head[8] == "operations_accepted"
        require(kinds(importId) == if (complete) setOf("identities", "network", "receipt", "acceptances")
            else setOf("identities", "network", "receipt"))
        val receipt = Json.decodeFromString<ConfigNetworkReceipt>(strictAppearanceJson(
            payload(importId, "receipt", requireNotNull(head[9])), LIMIT, {}) { error("CONFIG_IMPORT_RECEIPT_$it") })
        val network = ConfigNetworkPlan.decode(payload(importId, "network", receipt.networkHash))
        val identities = ConfigImportIdentities.decode(payload(importId, "identities", requireNotNull(head[6])))
        require(network.importId == importId && identities.ids["import"] == importId && identities.target == network.target &&
            identities.target == ConfigImportTarget(requireNotNull(head[1]), requireNotNull(head[2]),
                requireNotNull(head[3]), requireNotNull(head[4])) && identities.archiveHash == head[5])
        require(identities.ids.values.all(::isContractUuid) && identities.ids.values.distinct().size == identities.ids.size &&
            identities.ids.filterKeys { it.startsWith("operation:") }.values.toSet() ==
            network.steps.filter { it.action == "upsert" }.map { it.operationId }.toSet())
        require(receipt.sources.keys == network.steps.map { it.operationId }.toSet() &&
            receipt.sources.values.map { it.queueId }.distinct().size == receipt.sources.size)
        val origins = mutableListOf<String>()
        for (step in network.steps) {
            currentCoroutineContext().ensureActive()
            origins += requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, step.operationId)))
            val origin = requireNotNull(database.nextRequestDao().origin(NEXT_OPERATION, step.operationId))
            val saved = receipt.sources.getValue(step.operationId)
            require(origin.protocol == 5 && origin.queueId == saved.queueId && origin.sourceHash == saved.sourceHash &&
                origin.accountId == network.target.accountId && origin.serverInstanceId == network.target.serverInstanceId &&
                origin.syncEpoch == network.target.syncEpoch)
            step.requireOperation(decodeNextOperationIntent(origin.intentJson))
        }
        val accepted: Map<String, String>? = if (!complete) {
            require(receipt.acceptanceHash == null); null
        } else {
            Json.decodeFromString<Map<String, String>>(strictAppearanceJson(
                payload(importId, "acceptances", requireNotNull(receipt.acceptanceHash)), LIMIT, {}) {
                error("CONFIG_IMPORT_ACCEPTANCES_$it")
            }).also { values ->
                require(values.keys == receipt.sources.keys)
                for ((id, hash) in values) require(hash.matches(Regex("[0-9a-f]{64}")) &&
                    NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)) == hash)
            }
        }
        // header() has already bounded and checked EVERY SQLite field type before coercion.
        // This is its own journal proof, not an extension of the request SQL table allow-list.
        return NetworkEntry(network, receipt, accepted, nextRequestHash(Json.encodeToString(head).toByteArray(Charsets.UTF_8)),
            nextRequestHash((Json.encodeToString(head.take(8)) + origins.joinToString(";")).toByteArray(Charsets.UTF_8)))
    }

    fun requireNoActiveReplacement() {
        check(database.inTransaction())
        sql.query("SELECT 1 FROM next_config_imports WHERE mode='replacement' AND state='local_committed' LIMIT 1").use {
            check(!it.moveToFirst()) { "CONFIG_IMPORT_SYNC_PENDING" }
        }
    }

    /** Only one live group; finished journals remain recoverable without loading all old graphs. */
    suspend fun activeNetwork(): NetworkEntry? {
        check(database.inTransaction())
        // A forged terminal marker without its atomic receipt is not permission to bypass phases.
        sql.query("SELECT 1 FROM next_config_imports j WHERE mode NOT IN ('empty_replica','replacement') OR " +
            "state NOT IN ('prepared','local_committed','operations_accepted') OR " +
            "(state='operations_accepted' AND (mode!='replacement' OR " +
            "NOT EXISTS(SELECT 1 FROM next_config_import_payloads p WHERE p.importId=j.importId AND p.payloadKind='acceptances'))) LIMIT 1").use {
            require(!it.moveToFirst()) { "CONFIG_IMPORT_STATE_CHANGED" }
        }
        val ids = sql.query("SELECT importId,length(CAST(importId AS BLOB)) FROM next_config_imports " +
            "WHERE mode='replacement' AND state='local_committed' LIMIT 2").use { c -> buildList {
            while (c.moveToNext()) {
                require(c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) == 36L && c.getType(0) == Cursor.FIELD_TYPE_STRING)
                add(c.getString(0))
            }
        } }
        require(ids.size <= 1) { "CONFIG_IMPORT_STATE_CHANGED" }
        return ids.singleOrNull()?.let { network(it) }
    }

    suspend fun accepted(entry: NetworkEntry, hashes: Map<String, String>) {
        check(database.inTransaction()); require(entry.accepted == null && hashes.keys == entry.receipt.sources.keys)
        require(network(entry.plan.importId) == entry) { "CONFIG_IMPORT_JOURNAL_CHANGED" }
        for ((id, hash) in hashes) require(NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?",
            arrayOf(NEXT_OPERATION, id)) == hash)
        val accepted = Json.encodeToString(hashes).toByteArray(Charsets.UTF_8)
        writePayload(entry.plan.importId, "acceptances", accepted)
        val receipt = Json.encodeToString(entry.receipt.copy(acceptanceHash = nextRequestHash(accepted))).toByteArray(Charsets.UTF_8)
        sql.execSQL("DELETE FROM next_config_import_payloads WHERE importId=? AND payloadKind='receipt'", arrayOf(entry.plan.importId))
        writePayload(entry.plan.importId, "receipt", receipt)
        sql.compileStatement("UPDATE next_config_imports SET state='operations_accepted',receiptHash=? " +
            "WHERE importId=? AND mode='replacement' AND state='local_committed'").use {
            it.bindString(1, nextRequestHash(receipt)); it.bindString(2, entry.plan.importId)
            check(it.executeUpdateDelete() == 1)
        }
        val saved = network(entry.plan.importId)
        require(saved.plan == entry.plan && saved.receipt == entry.receipt.copy(acceptanceHash = nextRequestHash(accepted)) &&
            saved.accepted == hashes && saved.sourceProof == entry.sourceProof)
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
        sql.query("SELECT importId,state FROM next_config_imports WHERE state NOT IN ('local_committed','operations_accepted') ORDER BY importId").use { c ->
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
