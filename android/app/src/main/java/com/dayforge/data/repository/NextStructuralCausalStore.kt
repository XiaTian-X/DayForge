package com.dayforge.data.repository

import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.decodeSyncReply
import com.dayforge.data.api.InvalidFrozenSyncRequest
import com.dayforge.data.api.NextSyncReplyInvalid
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.isContractUuid
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Transaction-only D-015 coordinator. The caller owns the account lock and outer COMMIT. */
internal class NextStructuralCausalStore(private val database: HabitDatabase,
    private val memo: PureMemo = PureMemo()) {
    private val sql get() = database.openHelper.writableDatabase
    private val requests get() = database.nextRequestDao()
    private val causal get() = database.nextStructuralCausalDao()
    private val outbox get() = database.syncOutboxDao()
    // Database state belongs to one short Room transaction, never a process/network cache.
    private val capturedHeads = mutableMapOf<Pair<String, String>, NextStructuralDependencyEntity?>()
    internal data class ParsedEnvelope(val bytes: ByteArray, val value: NextSyncPushRequest)
    internal data class MergeKey(val before: String, val after: String, val submitted: String,
        val result: String, val replacementId: String)

    /** Pure values only, optionally shared by the sequential phases of ONE send/accept call.
     * Never stores live SQL rows/hashes, dependency/receipt proofs, credentials or authorization.
     * Concurrent requests own separate memos; exact current raw inputs are required for every hit.
     */
    internal class PureMemo {
        val parsedIntents = mutableMapOf<String, SyncV2Operation>()
        val parsedResults = mutableMapOf<String, NextSyncOperationResult>()
        val parsedSources = mutableMapOf<String, SyncOutboxEntity>()
        val parsedEnvelopes = mutableMapOf<String, ParsedEnvelope>()
        val mergedIntents = mutableMapOf<MergeKey, SyncV2Operation>()
        private var parsedBytes = 0
        private var parsedCount = 0
        fun retain(bytes: Int, save: () -> Unit) {
            if (parsedCount < 256 && bytes <= 2_097_152 - parsedBytes) {
                save(); parsedCount++; parsedBytes += bytes
            }
        }
    }
    private val parsedIntents get() = memo.parsedIntents
    private val parsedResults get() = memo.parsedResults
    private val parsedSources get() = memo.parsedSources
    private val parsedEnvelopes get() = memo.parsedEnvelopes
    private val mergedIntents get() = memo.mergedIntents
    private fun retainParsing(bytes: Int, save: () -> Unit) = memo.retain(bytes, save)

    /** Small bounded values stay on the existing Room transaction thread; the strict codec is unchanged. */
    private suspend fun <T> decode(bytes: ByteArray, serializer: KSerializer<T>): T {
        check(database.inTransaction())
        val caller = currentCoroutineContext()
        caller.ensureActive()
        if (bytes.size > 8192) return decodeFrozenSyncRequest(bytes, serializer)
        return try { decodeSyncReply(bytes, SYNC_REQUEST_LIMIT, serializer, { caller.ensureActive() }) }
        catch (_: NextSyncReplyInvalid) { throw InvalidFrozenSyncRequest() }
    }

    /** Validates a complete normalized structural entity, including root-operation receipt replays. */
    suspend fun validateStructuralResult(json: String): NextSyncOperationResult {
        currentCoroutineContext().ensureActive()
        return parsedResults[json] ?: json.toByteArray(Charsets.UTF_8).let { bytes ->
            decode(bytes, NextSyncOperationResult.serializer()).also { value ->
                // Pure validation of these exact bytes, never proof of an ACK or current authority.
                require(value.status == "applied" && value.revision != null && value.revision > 0 &&
                    value.entity != null && value.errorCode == null && value.message == null &&
                    value.baseEntity == null && value.localEntity == null && value.conflictingFields.isEmpty() &&
                    value.conflictKind == null && value.oneTimeConflict == null)
                when (value.entityType) {
                    "plan_node" -> NextStructureMapper.readPlan(value.entity, value.entityUuid, value.revision)
                    "metric" -> NextStructureMapper.readMetric(value.entity, value.entityUuid, value.revision)
                    "activity_metric_link" -> NextCommonFactMapper.validateLinkSnapshot(SyncV2Change(0,
                        value.entityType, value.entityUuid, "upsert", value.revision, value.entity,
                        requireNotNull(value.entity["updated_at"] as? JsonPrimitive).content))
                    else -> error("Unsupported structural receipt")
                }
                retainParsing(bytes.size) { parsedResults[json] = value }
            }
        }
    }

    private suspend fun sourceSnapshot(json: String): SyncOutboxEntity {
        currentCoroutineContext().ensureActive()
        return parsedSources[json] ?: json.toByteArray(Charsets.UTF_8).let { bytes ->
            decode(bytes, SyncOutboxEntity.serializer()).also { value ->
                require(encodeSyncRequest(SyncOutboxEntity.serializer(), value).contentEquals(bytes))
                retainParsing(bytes.size) { parsedSources[json] = value }
            }
        }
    }

    private suspend fun envelope(bytes: ByteArray, hash: String): NextSyncPushRequest {
        currentCoroutineContext().ensureActive()
        parsedEnvelopes[hash]?.let { if (it.bytes.contentEquals(bytes)) return it.value }
        return decode(bytes, NextSyncPushRequest.serializer()).also { value ->
            // Digest alone is never sufficient to reuse a parse. Own and compare the complete bytes.
            if (hash !in parsedEnvelopes) retainParsing(bytes.size) {
                parsedEnvelopes[hash] = ParsedEnvelope(bytes.copyOf(), value)
            }
        }
    }

    private data class ProofKey(val table: String, val where: String, val args: List<Any>)
    private class ReadOnlyProof {
        val hashes = mutableMapOf<ProofKey, String?>()
        val origins = mutableMapOf<String, NextRequestOriginEntity>()
        val dependencies = mutableMapOf<String, NextStructuralDependencyEntity>()
        val validatedDependencies = mutableSetOf<String>()
        val supersessions = mutableMapOf<String, NextStructuralSupersessionEntity>()
        val transmissions = mutableMapOf<String, NextTransmissionEntity>()
        val acceptances = mutableMapOf<String, NextAcceptanceEntity>()
        val sourcePresence = mutableMapOf<String, Boolean>()
        var rowBytes = 0
        var rowCount = 0
        fun retainRow(bytes: Int, save: () -> Unit) {
            if (rowCount < 256 && bytes <= 2_097_152 - rowBytes) {
                save(); rowCount++; rowBytes += bytes
            }
        }
    }
    private var proof: ReadOnlyProof? = null

    /** SELECT-only pass in one SQLite snapshot. Never retain proofs across any write/trigger. */
    private suspend fun <T> readOnlyProof(block: suspend () -> T): T {
        check(database.inTransaction() && proof == null)
        proof = ReadOnlyProof()
        return try { block() } finally { proof = null }
    }

    private suspend fun rowHash(table: String, where: String, args: Array<Any>): String? {
        currentCoroutineContext().ensureActive()
        val cache = proof?.hashes
        val key = ProofKey(table, where, args.toList())
        if (cache != null && cache.containsKey(key)) return cache[key]
        val value = NextRequestSql.rowHash(sql, table, where, args)
        if (cache != null && cache.size < 512) cache[key] = value
        return value
    }

    private suspend fun hash(table: String, column: String, id: String): String? =
        rowHash(table, "$column=?", arrayOf(id))
    private suspend fun requestHash(table: String, id: String): String? =
        rowHash(table, "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id))

    /** Performance hint only; the full existing ancestry/receipt/domain audit still follows. */
    private suspend fun warmAncestry(root: String) {
        val p = requireNotNull(proof)
        val ids = mutableListOf<String>()
        var current: String? = root
        // A small prefix bounds allocation. It never caps the subsequent 10,000-link audit.
        while (current != null && ids.size < 64) {
            currentCoroutineContext().ensureActive()
            require(isContractUuid(current) && current !in ids)
            if (hash("next_structural_dependencies", "operationId", current) == null) break
            val row = dependencyRow(current)
            require(row.operationId == current && row.kind == NEXT_OPERATION && row.logicalOrder > 0)
            ids.add(current)
            current = row.predecessorId
        }
        if (ids.size < 2) return

        suspend fun warm(table: String, column: String, keys: List<Any>, kind: String? = null): Boolean {
            val hashes = NextRequestSql.boundedRowHashes(sql, table, column, keys, kind) ?: return false
            hashes.forEach { (id, value) ->
                val key = if (kind == null) ProofKey(table, "$column=?", listOf(id))
                    else ProofKey(table, "kind=? AND requestId=?", listOf(kind, id))
                if (p.hashes.size < 512 || key in p.hashes) p.hashes[key] = value
            }
            return true
        }

        if (!warm("next_structural_supersessions", "originalId", ids)) return
        val supersessions = causal.supersessions(ids)
        require(supersessions.map { it.originalId }.distinct().size == supersessions.size)
        for (row in supersessions) {
            require(row.originalId in ids && row.kind == NEXT_OPERATION && isContractUuid(row.replacementId))
            p.retainRow(row.toString().toByteArray(Charsets.UTF_8).size) { p.supersessions[row.originalId] = row }
        }
        val requestIds = (ids + supersessions.map { it.replacementId }).distinct()
        if (!warm("next_request_origins", "requestId", requestIds, NEXT_OPERATION)) return
        val origins = requests.origins(NEXT_OPERATION, requestIds)
        require(origins.map { it.requestId }.distinct().size == origins.size)
        for (row in origins) {
            require(row.requestId in requestIds && row.kind == NEXT_OPERATION && row.queueId > 0)
            p.retainRow(row.toString().toByteArray(Charsets.UTF_8).size) { p.origins[row.requestId] = row }
        }
        // Absence proofs are as important as rows: originals must have no journal/ACK/source.
        if (warm("next_transmissions", "requestId", requestIds, NEXT_OPERATION)) {
            val rows = requests.transmissions(NEXT_OPERATION, requestIds)
            require(rows.map { it.requestId }.distinct().size == rows.size)
            for (row in rows) {
                require(row.requestId in requestIds && row.kind == NEXT_OPERATION)
                val bytes = row.wireBytes.size + 256 + listOf(row.kind, row.requestId, row.accountId,
                    row.serverInstanceId, row.syncEpoch, row.deviceId, row.wireHash).sumOf { it.toByteArray(Charsets.UTF_8).size }
                p.retainRow(bytes) { p.transmissions[row.requestId] = row }
            }
        }
        if (warm("next_acceptances", "requestId", requestIds, NEXT_OPERATION)) {
            val rows = requests.acceptances(NEXT_OPERATION, requestIds)
            require(rows.map { it.requestId }.distinct().size == rows.size)
            for (row in rows) {
                require(row.requestId in requestIds && row.kind == NEXT_OPERATION)
                p.retainRow(row.toString().toByteArray(Charsets.UTF_8).size) { p.acceptances[row.requestId] = row }
            }
        }
        warm("next_structural_dependencies", "operationId", requestIds)
        warm("next_structural_supersessions", "originalId", requestIds)
        val queueIds = origins.map { it.queueId }.distinct()
        if (queueIds.isNotEmpty()) warm("sync_outbox", "id", queueIds)
        // Existence by operation ID is separate from absence at the original queue ID.
        // Preserve that proof even when an unexpected row has a different physical identity.
        val present = mutableSetOf<String>()
        sql.query("SELECT operationId FROM sync_outbox WHERE operationId IN (${requestIds.joinToString(",") { "?" }})",
            requestIds.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                require(c.getType(0) == android.database.Cursor.FIELD_TYPE_STRING)
                val id = c.getString(0)
                require(id in requestIds && present.add(id))
            }
        }
        for (id in requestIds) p.retainRow(id.toByteArray(Charsets.UTF_8).size + 16) {
            p.sourcePresence[id] = id in present
        }
    }

    private suspend fun byOriginal(id: String): NextStructuralSupersessionEntity? {
        if (hash("next_structural_supersessions", "originalId", id) == null) return null
        proof?.supersessions?.get(id)?.let { return it }
        return requireNotNull(causal.supersession(id)).also { row ->
            require(row.originalId == id)
            proof?.let { p -> p.retainRow(row.toString().toByteArray(Charsets.UTF_8).size) { p.supersessions[id] = row } }
        }
    }

    private suspend fun byReplacement(id: String): NextStructuralSupersessionEntity? {
        if (hash("next_structural_supersessions", "replacementId", id) == null) return null
        return requireNotNull(causal.supersessionByReplacement(id)).also { require(it.replacementId == id) }
    }

    private fun hasSource(id: String): Boolean {
        proof?.sourcePresence?.get(id)?.let { return it }
        return sql.query("SELECT 1 FROM sync_outbox WHERE operationId=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }
    }

    /** Bound IDs and audit lengths/types before Room can materialize or coerce source fields. */
    private suspend fun entityIntents(type: String, uuid: String): List<SyncOutboxEntity> {
        val ids = sql.query("SELECT id FROM sync_outbox WHERE recordType=? AND entityUuid=? ORDER BY id LIMIT 10001", arrayOf(type, uuid)).use { c ->
            buildList { while (c.moveToNext()) {
                require(c.getType(0) == android.database.Cursor.FIELD_TYPE_INTEGER && c.getLong(0) > 0)
                add(c.getLong(0))
            } }
        }
        val watermark = NextRequestSql.watermark(sql, "sync_outbox")
        require(ids.size <= 10000 && ids.all { it <= watermark })
        val proofs = ids.associateWith { requireNotNull(rowHash("sync_outbox", "id=?", arrayOf(it))) }
        return outbox.getEntityIntents(type, uuid).also { rows ->
            require(rows.associate { it.id to NextRequestSql.sourceHash(it) } == proofs)
        }
    }

    private fun ordinary(operation: SyncV2Operation): Boolean = operation.action == "upsert" &&
        operation.entityType in setOf("plan_node", "metric", "activity_metric_link") &&
        (operation.payload["activity"] as? JsonObject)?.get("completion_policy") != JsonPrimitive("one_and_done")

    private suspend fun original(id: String): NextRequestOriginEntity {
        require(isContractUuid(id))
        if (requestHash("next_request_origins", id) == null)
            rejectNextRequest(NextRequestException.Reason.OLD_INTENT)
        val row = proof?.origins?.get(id) ?: requireNotNull(requests.origin(NEXT_OPERATION, id)).also { value ->
            proof?.let { p -> p.retainRow(value.toString().toByteArray(Charsets.UTF_8).size) { p.origins[id] = value } }
        }
        return row.also {
            require(it.kind == NEXT_OPERATION && it.requestId == id && it.protocol == 5 && it.queueId > 0 &&
                isContractUuid(it.accountId) && (it.serverInstanceId == null) == (it.syncEpoch == null))
            it.serverInstanceId?.let { value -> require(isContractUuid(value) && isContractUuid(requireNotNull(it.syncEpoch))) }
        }
    }
    private suspend fun intent(origin: NextRequestOriginEntity): SyncV2Operation {
        currentCoroutineContext().ensureActive()
        val value = parsedIntents[origin.intentJson] ?: run {
            val bytes = origin.intentJson.toByteArray(Charsets.UTF_8)
            decode(bytes, SyncV2Operation.serializer()).also {
                require(it.operationId == origin.requestId && ordinary(it))
                retainParsing(bytes.size) { parsedIntents[origin.intentJson] = it }
            }
        }
        require(value.operationId == origin.requestId && ordinary(value))
        return value
    }

    /** Audit only one immutable link; strict increasing order also prevents cycles. */
    private suspend fun dependency(id: String): NextStructuralDependencyEntity {
        requireNotNull(hash("next_structural_dependencies", "operationId", id))
        val row = dependencyRow(id)
        // A SELECT-only snapshot cannot change this link or its audited immediate predecessor.
        // Discard this set at the end of the pass, including failure/cancellation and before writes.
        if (proof?.validatedDependencies?.contains(id) == true) return row
        val origin = original(id)
        intent(origin)
        require(row.operationId == id && row.kind == NEXT_OPERATION && row.logicalOrder == origin.queueId &&
            row.originHash == requestHash("next_request_origins", id))
        row.capturedDeviceId?.let { require(isContractUuid(it)) }
        if (row.predecessorId == null) require(row.predecessorOriginHash == null && row.predecessorDependencyHash == null)
        else {
            val parentId = row.predecessorId
            require(isContractUuid(parentId) && parentId != id)
            require(row.predecessorOriginHash == requestHash("next_request_origins", parentId) &&
                row.predecessorDependencyHash == hash("next_structural_dependencies", "operationId", parentId))
            val parent = dependencyRow(parentId)
            val parentOrigin = original(parentId)
            require(parent.operationId == parentId && parent.kind == NEXT_OPERATION && parent.logicalOrder == parentOrigin.queueId &&
                parent.originHash == row.predecessorOriginHash && parent.logicalOrder < row.logicalOrder)
            val before = intent(parentOrigin); val after = intent(origin)
            require(before.entityType == after.entityType && before.entityUuid == after.entityUuid &&
                parentOrigin.accountId == origin.accountId)
            if (parent.capturedDeviceId != null && row.capturedDeviceId != null)
                require(parent.capturedDeviceId == row.capturedDeviceId)
            if (parentOrigin.serverInstanceId != null && origin.serverInstanceId != null)
                require(parentOrigin.serverInstanceId == origin.serverInstanceId && parentOrigin.syncEpoch == origin.syncEpoch)
        }
        proof?.validatedDependencies?.let { if (it.size < 256) it.add(id) }
        return row
    }

    /** Raw audit precedes the first DAO read; reuse is confined to the SELECT-only proof snapshot. */
    private suspend fun dependencyRow(id: String): NextStructuralDependencyEntity {
        requireNotNull(hash("next_structural_dependencies", "operationId", id))
        proof?.dependencies?.get(id)?.let { return it }
        return requireNotNull(causal.dependency(id)).also { row ->
            proof?.let { p -> p.retainRow(row.toString().toByteArray(Charsets.UTF_8).size) { p.dependencies[id] = row } }
        }
    }

    private fun fresh(row: SyncOutboxEntity) {
        require(row.id > 0 && isContractUuid(row.operationId) && isContractUuid(row.entityUuid) &&
            row.entityUuid == row.wireEntityUuid && row.action == "upsert" && row.recordType in setOf("habit", "metric", "link") &&
            row.payloadJson == null && row.baseRevision == null && row.basePayloadJson == null && row.attemptedAt == null &&
            row.attemptCount == 0 && row.lastError == null && row.errorCode == null && row.deadLetteredAt == null)
        row.referenceUuid?.let {
            // Existing habit triggers store the type here, not a parent/record UUID.
            require(if (row.recordType == "habit") it in HabitType.entries.map { type -> type.name } else isContractUuid(it))
        }
    }

    suspend fun auditCaptured(id: String, access: LocalCoreWriteAccess) {
        check(database.inTransaction())
        val dep = dependency(id)
        val origin = original(id)
        require(origin.accountId == access.session.authentication.userId && origin.serverInstanceId == access.session.serverInstanceId &&
            origin.syncEpoch == access.session.syncEpoch && dep.capturedDeviceId == access.capturedDeviceId)
    }

    private suspend fun logical(row: SyncOutboxEntity): NextStructuralDependencyEntity {
        val supersession = byReplacement(row.operationId)
        if (supersession == null) return dependency(row.operationId)
        requireNotNull(hash("next_structural_supersessions", "originalId", supersession.originalId))
        val dep = dependency(supersession.originalId)
        require(supersession.kind == NEXT_OPERATION && supersession.replacementId == row.operationId &&
            supersession.originalQueueId == dep.logicalOrder && supersession.replacementQueueId == row.id &&
            supersession.originalDependencyHash == hash("next_structural_dependencies", "operationId", dep.operationId) &&
            supersession.originalOriginHash == dep.originHash &&
            supersession.replacementOriginHash == requestHash("next_request_origins", row.operationId))
        return dep
    }

    /** Only explicit NEW writes get ancestry. A migrated/unknown predecessor is never adopted. */
    suspend fun capture(origin: NextRequestOriginEntity, operation: SyncV2Operation, access: LocalCoreWriteAccess) {
        check(database.inTransaction())
        if (!ordinary(operation)) return
        require(rowHash("sync_outbox", "id=?", arrayOf(origin.queueId)) == origin.sourceHash)
        val source = requireNotNull(outbox.getById(origin.queueId))
        fresh(source)
        require(NextRequestSql.sourceHash(source) == origin.sourceHash &&
            rowHash("sync_outbox", "id=?", arrayOf(source.id)) == origin.sourceHash)
        val key = source.recordType to source.entityUuid
        if (!capturedHeads.containsKey(key)) capturedHeads[key] = entityIntents(source.recordType, source.entityUuid)
            .filter { it.id < source.id }.map { logical(it) }.maxByOrNull { it.logicalOrder }
        val parent = capturedHeads[key]
        parent?.let {
            val before = original(it.operationId)
            require(before.accountId == origin.accountId &&
                (before.serverInstanceId == null || origin.serverInstanceId == null ||
                    before.serverInstanceId == origin.serverInstanceId && before.syncEpoch == origin.syncEpoch) &&
                (it.capturedDeviceId == null || access.capturedDeviceId == null || it.capturedDeviceId == access.capturedDeviceId))
        }
        val row = NextStructuralDependencyEntity(origin.requestId, origin.queueId,
            requireNotNull(requestHash("next_request_origins", origin.requestId)), parent?.operationId,
            parent?.originHash, parent?.let { requireNotNull(hash("next_structural_dependencies", "operationId", it.operationId)) },
            access.capturedDeviceId)
        causal.insertDependency(row)
        check(dependency(origin.requestId) == row)
        capturedHeads[key] = row
    }

    private fun validateContext(origin: NextRequestOriginEntity, dep: NextStructuralDependencyEntity?, access: LocalSyncAccess) {
        if (origin.accountId != access.session.authentication.userId ||
            origin.serverInstanceId != null && (origin.serverInstanceId != access.session.serverInstanceId || origin.syncEpoch != access.session.syncEpoch) ||
            dep?.capturedDeviceId != null && dep.capturedDeviceId != access.deviceId)
            rejectNextRequest(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED)
        if ("structure.write" !in access.capabilities) rejectNextRequest(NextRequestException.Reason.PERMISSION_DENIED)
    }

    private data class Accepted(val operation: SyncV2Operation, val result: NextSyncOperationResult,
        val hash: String, val intentJson: String, val resultJson: String)

    /** Exact immutable inputs only; all live SQL/context/receipt checks remain outside this memo. */
    private suspend fun merged(before: NextRequestOriginEntity, after: NextRequestOriginEntity,
        parent: Accepted, replacementId: String): SyncV2Operation {
        currentCoroutineContext().ensureActive()
        val key = MergeKey(before.intentJson, after.intentJson, parent.intentJson, parent.resultJson, replacementId)
        mergedIntents[key]?.let { return it }
        return NextStructuralRebase.merge(intent(before), intent(after), parent.result,
            replacementId, parent.operation).also { value ->
            val bytes = listOf(key.before, key.after, key.submitted, key.result, key.replacementId)
                .sumOf { it.toByteArray(Charsets.UTF_8).size }
            retainParsing(bytes) { mergedIntents[key] = value }
        }
    }

    /** Full receipt proof, not a mutable shadow or inferred absence. */
    private suspend fun accepted(id: String, access: LocalSyncAccess): Accepted {
        val origin = original(id)
        validateContext(origin, null, access)
        val originHash = requireNotNull(requestHash("next_request_origins", id))
        val receiptHash = requestHash("next_acceptances", id)
            ?: rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
        val transmissionHash = requireNotNull(requestHash("next_transmissions", id))
        val transmission = proof?.transmissions?.get(id) ?: requireNotNull(requests.transmission(NEXT_OPERATION, id))
        require(transmission.kind == NEXT_OPERATION && transmission.requestId == id && transmission.protocol == 5 &&
            transmission.queueId == origin.queueId && transmission.accountId == origin.accountId &&
            transmission.serverInstanceId == access.session.serverInstanceId && transmission.syncEpoch == access.session.syncEpoch &&
            transmission.deviceId == access.deviceId && transmission.wireHash == nextRequestHash(transmission.wireBytes))
        val envelope = envelope(transmission.wireBytes, transmission.wireHash)
        val operation = intent(origin)
        require(envelope.deviceId == transmission.deviceId && envelope.operations == listOf(operation))
        val receipt = proof?.acceptances?.get(id) ?: requireNotNull(requests.acceptance(NEXT_OPERATION, id))
        require(receipt.kind == NEXT_OPERATION && receipt.requestId == id && receipt.originHash == originHash &&
            receipt.transmissionHash == transmissionHash && receipt.resultHash == nextRequestHash(receipt.resultJson.toByteArray(Charsets.UTF_8)))
        val result = validateStructuralResult(receipt.resultJson)
        validateTaskResultBinding(operation, result)
        require(result.status == "applied" && result.revision != null && result.revision > 0 &&
            result.revision >= (operation.baseRevision ?: 0) && result.entity != null && result.errorCode == null && result.message == null &&
            result.baseEntity == null && result.localEntity == null && result.conflictingFields.isEmpty() &&
            result.conflictKind == null && result.oneTimeConflict == null)
        require(rowHash("sync_outbox", "id=?", arrayOf(origin.queueId)) == null && !hasSource(id))
        return Accepted(operation, result, receiptHash, origin.intentJson, receipt.resultJson)
    }

    private suspend fun actualParent(dep: NextStructuralDependencyEntity): String {
        val parent = requireNotNull(dep.predecessorId)
        val supersession = byOriginal(parent) ?: return parent
        requireNotNull(hash("next_structural_supersessions", "originalId", parent))
        require(supersession.originalId == parent && supersession.kind == NEXT_OPERATION && isContractUuid(supersession.replacementId))
        return supersession.replacementId
    }

    private suspend fun auditReplacement(row: NextStructuralSupersessionEntity, access: LocalSyncAccess,
        acceptedByChild: Accepted?): Accepted {
        require(row.kind == NEXT_OPERATION && row.originalId != row.replacementId && isContractUuid(row.replacementId) &&
            row.accountId == access.session.authentication.userId && row.serverInstanceId == access.session.serverInstanceId &&
            row.syncEpoch == access.session.syncEpoch && row.deviceId == access.deviceId)
        val dep = dependency(row.originalId)
        val origin = original(row.originalId)
        validateContext(origin, dep, access)
        require(row.originalOriginHash == dep.originHash && row.originalDependencyHash == hash("next_structural_dependencies", "operationId", row.originalId) &&
            row.originalQueueId == origin.queueId && row.originalSourceHash == origin.sourceHash)
        require(row.sourceSnapshotHash == nextRequestHash(row.sourceSnapshotJson.toByteArray(Charsets.UTF_8)))
        val source = sourceSnapshot(row.sourceSnapshotJson)
        fresh(source)
        require(source.id == origin.queueId && source.operationId == origin.requestId && NextRequestSql.sourceHash(source) == origin.sourceHash)
        require(rowHash("sync_outbox", "id=?", arrayOf(source.id)) == null && !hasSource(row.originalId) &&
            requestHash("next_transmissions", row.originalId) == null && requestHash("next_acceptances", row.originalId) == null)
        require(row.predecessorAcceptedRequestId == actualParent(dep))
        val parent = accepted(row.predecessorAcceptedRequestId, access)
        require(row.predecessorAcceptanceHash == parent.hash)
        val replacement = original(row.replacementId)
        validateContext(replacement, null, access)
        require(row.replacementOriginHash == requestHash("next_request_origins", row.replacementId) &&
            row.replacementQueueId == replacement.queueId && replacement.queueId > source.id &&
            replacement.serverInstanceId == row.serverInstanceId && replacement.syncEpoch == row.syncEpoch &&
            hash("next_structural_dependencies", "operationId", row.replacementId) == null && byOriginal(row.replacementId) == null)
        val desired = merged(original(requireNotNull(dep.predecessorId)), origin, parent, row.replacementId)
        require(intent(replacement) == desired)
        val newSource = source.copy(id = replacement.queueId, operationId = replacement.requestId)
        require(replacement.sourceHash == NextRequestSql.sourceHash(newSource))
        val receiptHash = requestHash("next_acceptances", replacement.requestId)
        if (receiptHash != null) {
            // The immediately visited child already needed this exact complete receipt. It is
            // still the same SELECT-only snapshot and access; do not audit it a second time.
            if (acceptedByChild == null) accepted(replacement.requestId, access)
            else require(acceptedByChild.operation.operationId == replacement.requestId && acceptedByChild.hash == receiptHash)
        } else require(acceptedByChild == null &&
            rowHash("sync_outbox", "id=?", arrayOf(replacement.queueId)) == replacement.sourceHash &&
            outbox.getById(replacement.queueId) == newSource)
        return parent
    }

    /** Iterative, bounded ancestry audit; never recursively parse a growing chain on the stack. */
    suspend fun resolve(id: String, access: LocalSyncAccess): String = readOnlyProof {
        require(isContractUuid(id))
        val fromOriginal = byOriginal(id)
        val fromReplacement = byReplacement(id)
        require(fromOriginal == null || fromReplacement == null)
        val actual = fromOriginal?.replacementId ?: id
        var current: String? = fromOriginal?.originalId ?: fromReplacement?.originalId ?: id
        warmAncestry(requireNotNull(current))
        val seen = mutableSetOf<String>()
        var acceptedByChild: Accepted? = null
        while (current != null) {
            require(seen.add(current) && seen.size <= 10000)
            val depHash = hash("next_structural_dependencies", "operationId", current)
            if (depHash == null) {
                require(current == id && fromOriginal == null && fromReplacement == null)
                break // Existing origin without NEW ancestry remains exact-original-only.
            }
            val dep = dependency(current)
            validateContext(original(current), dep, access)
            val supersession = byOriginal(current)
            if (supersession != null) {
                requireNotNull(hash("next_structural_supersessions", "originalId", current))
                acceptedByChild = auditReplacement(supersession, access, acceptedByChild)
            } else acceptedByChild = null
            current = dep.predecessorId
        }
        actual
    }

    internal suspend fun logicalOrder(queue: SyncOutboxEntity): Long = readOnlyProof {
        if (hash("next_structural_dependencies", "operationId", queue.operationId) != null || byReplacement(queue.operationId) != null)
            logical(queue).logicalOrder else queue.id
    }

    suspend fun requireHead(queue: SyncOutboxEntity) = readOnlyProof {
        val own = if (hash("next_structural_dependencies", "operationId", queue.operationId) != null || byReplacement(queue.operationId) != null)
            logical(queue).logicalOrder else queue.id
        val pending = entityIntents(queue.recordType, queue.entityUuid)
        for (other in pending.filter { it.id != queue.id }) {
            val order = if (hash("next_structural_dependencies", "operationId", other.operationId) != null || byReplacement(other.operationId) != null)
                logical(other).logicalOrder else other.id
            if (order < own) rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
        }
    }

    /** Called only after exact-v5 discovery. Unknown/sent originals are NEVER superseded. */
    suspend fun prepare(id: String, access: LocalSyncAccess): String {
        check(database.inTransaction())
        val actual = resolve(id, access)
        if (actual != id || byReplacement(id) != null) return actual
        if (requestHash("next_transmissions", id) != null || requestHash("next_acceptances", id) != null) return id
        val origin = original(id)
        val operation = decodeFrozenSyncRequest(origin.intentJson.toByteArray(Charsets.UTF_8), SyncV2Operation.serializer())
        if (!ordinary(operation)) return id
        require(rowHash("sync_outbox", "id=?", arrayOf(origin.queueId)) == origin.sourceHash)
        val source = requireNotNull(outbox.getById(origin.queueId))
        requireHead(source)
        if (hash("next_structural_dependencies", "operationId", id) == null) rejectNextRequest(NextRequestException.Reason.OLD_INTENT)
        val dep = dependency(id)
        validateContext(origin, dep, access)
        if (dep.predecessorId == null) return id
        fresh(source)
        require(rowHash("sync_outbox", "id=?", arrayOf(source.id)) == origin.sourceHash &&
            NextRequestSql.sourceHash(source) == origin.sourceHash)
        val parentId = actualParent(dep)
        val parent = accepted(parentId, access)
        val replacementId = UUID.randomUUID().toString()
        if (requests.origin(NEXT_OPERATION, replacementId) != null || requests.transmission(NEXT_OPERATION, replacementId) != null ||
            requests.acceptance(NEXT_OPERATION, replacementId) != null) rejectNextRequest(NextRequestException.Reason.REQUEST_ID_REUSED)
        val replacementOperation = merged(original(dep.predecessorId), origin, parent, replacementId)
        val intentBytes = encodeSyncRequest(SyncV2Operation.serializer(), replacementOperation)
        decodeFrozenSyncRequest(intentBytes, SyncV2Operation.serializer())
        val envelope = encodeSyncRequest(NextSyncPushRequest.serializer(), NextSyncPushRequest(requireNotNull(access.deviceId), listOf(replacementOperation)))
        decodeFrozenSyncRequest(envelope, NextSyncPushRequest.serializer())
        val before = NextRequestSql.sources(sql, "sync_outbox")
        val timers = NextRequestSql.sources(sql, "timer_command_outbox")
        val watermark = NextRequestSql.watermark(sql, "sync_outbox")
        val newSource = source.copy(id = 0, operationId = replacementId)
        val newQueueId = outbox.insert(newSource)
        require(newQueueId > watermark && newQueueId > source.id)
        val sourceHash = requireNotNull(rowHash("sync_outbox", "id=?", arrayOf(newQueueId)))
        require(outbox.getById(newQueueId) == newSource.copy(id = newQueueId) && sourceHash == NextRequestSql.sourceHash(newSource.copy(id = newQueueId)))
        val replacement = NextRequestOriginEntity(NEXT_OPERATION, replacementId, newQueueId, 5, origin.accountId,
            requireNotNull(access.session.serverInstanceId), requireNotNull(access.session.syncEpoch), sourceHash, intentBytes.toString(Charsets.UTF_8))
        requests.insertOrigin(replacement)
        val snapshot = encodeSyncRequest(SyncOutboxEntity.serializer(), source)
        val supersession = NextStructuralSupersessionEntity(id, replacementId, source.id, newQueueId, dep.originHash,
            requireNotNull(hash("next_structural_dependencies", "operationId", id)), origin.sourceHash, snapshot.toString(Charsets.UTF_8),
            nextRequestHash(snapshot), parentId, parent.hash, requireNotNull(requestHash("next_request_origins", replacementId)),
            origin.accountId, requireNotNull(access.session.serverInstanceId), requireNotNull(access.session.syncEpoch), requireNotNull(access.deviceId))
        causal.insertSupersession(supersession)
        outbox.deleteById(source.id)
        check(NextRequestSql.sources(sql, "sync_outbox") == before.minus(source.id).plus(newQueueId to sourceHash) &&
            NextRequestSql.sources(sql, "timer_command_outbox") == timers &&
            requestHash("next_request_origins", replacementId) == supersession.replacementOriginHash &&
            requests.origin(NEXT_OPERATION, replacementId) == replacement && byOriginal(id) == supersession)
        check(resolve(id, access) == replacementId)
        NextRequestSql.requireOutboxEnabled(sql)
        return replacementId
    }
}
