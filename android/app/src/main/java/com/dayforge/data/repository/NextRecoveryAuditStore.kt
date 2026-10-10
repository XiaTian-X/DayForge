package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.domain.model.isContractUuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Validated local terminal group; NEVER a remote acceptance or permission to apply a projection. */
internal data class NextRecoveryTerminalProof(val recoveryId: String, val manifest: NextRecoveryAuditManifest,
    val evidenceHash: String)

/**
 * SELECT-only complete terminal reader. Caller owns authenticated account + Room transaction.
 * No retirement/confirmation writer is enabled. Membership or a matching digest alone is insufficient.
 */
internal class NextRecoveryAuditStore(private val database: HabitDatabase) {
    private val sql get() = database.openHelper.writableDatabase
    private val payloads get() = NextRecoveryPayloadStore(database)
    private val requestTables = listOf("next_request_origins", "next_transmissions", "next_acceptances", "next_rejections")

    internal suspend fun checkpoint(access: LocalSyncAccess): NextRecoveryAuditCheckpoint {
        check(database.inTransaction())
        val active = NextChallengeStore(database).activeInTransaction(access)
        return NextRecoveryAuditCheckpoint(access.session.authentication.userId, requireNotNull(access.session.serverInstanceId),
            requireNotNull(access.session.syncEpoch), requireNotNull(access.deviceId),
            requireNotNull(NextRequestSql.rowHash(sql, "next_sync_state", "1=1", emptyArray())),
            requireNotNull(NextRequestSql.rowHash(sql, "next_challenge_state", "1=1", emptyArray())), active.second)
    }

    /** Immutable rows of the actual graph; streamed per row, no closure-sized source bodies. */
    internal suspend fun graphRows(graph: NextRecoveryGraphArchive): List<NextRecoveryAuditRow> {
        check(database.inTransaction())
        val result = linkedMapOf<Pair<String, NextRecoveryKey>, NextRecoveryAuditRow>()
        for (node in graph.nodes) {
            currentCoroutineContext().ensureActive()
            for (table in requestTables + if (node.key.kind == NEXT_OPERATION)
                listOf("next_structural_dependencies", "next_structural_supersessions") else emptyList()) {
                rowHash(table, node.key)?.let { hash -> result[table to node.key] = NextRecoveryAuditRow(table, node.key, hash) }
            }
            require("next_request_origins" to node.key in result)
        }
        return result.values.toList()
    }

    suspend fun read(access: LocalSyncAccess, id: String): NextRecoveryTerminalProof {
        check(database.inTransaction()); currentCoroutineContext().ensureActive(); require(isContractUuid(id))
        NextRequestSql.requireOutboxEnabled(sql)
        val current = checkpoint(access)
        val headerHash = requireNotNull(NextRequestSql.rowHash(sql, "next_recovery_audits", "recoveryId=?", arrayOf(id)))
        val header = sql.query("SELECT * FROM next_recovery_audits WHERE recoveryId=?", arrayOf(id)).use { c ->
            require(c.moveToFirst())
            c.columnNames.associateWith { column -> c.getString(c.getColumnIndexOrThrow(column)) }
        }
        require(header.getValue("accountId") == current.accountId && header.getValue("serverInstanceId") == current.serverInstanceId &&
            header.getValue("syncEpoch") == current.syncEpoch && header.getValue("deviceId") == current.deviceId)
        val selectedCount = header.getValue("selectedCount").toInt().also { require(it in 1..10_000) }
        val root = NextRecoveryKey(header.getValue("rootKind"), header.getValue("rootRequestId"))
        val manifestReference = reference(id, "manifest", header.getValue("manifestHash"), 67_108_864)
        val manifest = NextRecoveryAuditCodec.decode<NextRecoveryAuditManifest>(payloads.read(id, manifestReference), 67_108_864)
        require(manifest.root == root && manifest.requests.size == selectedCount &&
            header.getValue("checkpointHash") == manifest.checkpoint.hash)
        val frozen = NextRecoveryAuditCodec.decode<NextRecoveryAuditCheckpoint>(payloads.read(id, manifest.checkpoint), SYNC_REQUEST_LIMIT)
        require(frozen.accountId == current.accountId && frozen.serverInstanceId == current.serverInstanceId &&
            frozen.syncEpoch == current.syncEpoch && frozen.deviceId == current.deviceId)
        // Later legitimate pulls/ACKs may extend a history; they cannot rewrite the old captured records.
        val currentRecords = current.metadata.checkpoints.flatMap { it.records }.associateBy { it.head.roundUuid }
        require(frozen.metadata.checkpoints.flatMap { it.records }.all { currentRecords[it.head.roundUuid] == it })
        require(frozen.metadata.births.all { it in current.metadata.births })
        val preview = manifest.graph.preview(root)
        require(preview.canConfirm && preview.graphHash == header.getValue("graphHash"))
        val selected = manifest.requests.associateBy { it.key }
        require(preview.affected.associate { it.key to it.state } == selected.mapValues { it.value.state })
        val identities = preview.affected.map { it.entityType to it.entityUuid }.toSet()
        require(manifest.projections.map { it.entityType to it.entityUuid }.toSet() == identities)
        val expectedKeys = setOf("manifest", "checkpoint") + manifest.requests.map { it.source.key } +
            manifest.projections.flatMap { listOf(it.before.key, it.after.key, it.baseline.key) }
        require(payloads.keys(id) == expectedKeys)
        validateRows(access, id, manifest)
        val members = memberKeys(id)
        require(members.toSet() == selected.keys && members.size == selected.size)
        val affected = preview.affected.associateBy { it.key }
        for (request in manifest.requests) validateSource(access, frozen.metadata, id, request, affected.getValue(request.key))
        for (projection in manifest.projections) {
            val before = NextRecoveryAuditCodec.decode<NextRecoveryProjectionSnapshot>(payloads.read(id, projection.before), NEXT_RECOVERY_SOURCE_BYTES)
            val after = NextRecoveryAuditCodec.decode<NextRecoveryProjectionSnapshot>(payloads.read(id, projection.after), NEXT_RECOVERY_SOURCE_BYTES)
            val baseline = NextRecoveryAuditCodec.decode<NextRecoveryProjectionBaseline>(payloads.read(id, projection.baseline), NEXT_RECOVERY_SOURCE_BYTES)
            require(before.entityType == projection.entityType && after.entityType == projection.entityType &&
                before.entityUuid == projection.entityUuid && after.entityUuid == projection.entityUuid)
            val origins = preview.affected.filter { it.entityType == projection.entityType && it.entityUuid == projection.entityUuid }
                .map { requireNotNull(database.nextRequestDao().origin(it.key.kind, it.key.requestId)) }
            NextRecoveryProjectionStore(database).validateDraft(NextRecoveryProjectionDraft(before, after, baseline), origins)
        }
        // A trigger/read participant cannot mutate the terminal while it is being inspected.
        check(NextRequestSql.rowHash(sql, "next_recovery_audits", "recoveryId=?", arrayOf(id)) == headerHash)
        return NextRecoveryTerminalProof(id, manifest, nextRequestHash((headerHash + manifestReference.hash).toByteArray(Charsets.UTF_8)))
    }

    /** Referenced membership always audits the WHOLE original group, not just this source. */
    suspend fun source(access: LocalSyncAccess, key: NextRecoveryKey): Pair<NextRecoveryTerminalProof, SyncOutboxEntity>? {
        check(database.inTransaction())
        val memberHash = NextRequestSql.rowHash(sql, "next_abandoned_requests", "kind=? AND requestId=?", arrayOf(key.kind, key.requestId)) ?: return null
        val id = sql.query("SELECT recoveryId FROM next_abandoned_requests WHERE kind=? AND requestId=?",
            arrayOf(key.kind, key.requestId)).use { require(it.moveToFirst()); it.getString(0) }
        val proof = read(access, id)
        val request = proof.manifest.requests.single { it.key == key }
        val source = NextRecoverySourceSnapshot.decode(payloads.read(id, request.source), request.sourceHash)
        check(NextRequestSql.rowHash(sql, "next_abandoned_requests", "kind=? AND requestId=?", arrayOf(key.kind, key.requestId)) == memberHash)
        return proof to source
    }

    private suspend fun validateRows(access: LocalSyncAccess, id: String, manifest: NextRecoveryAuditManifest) {
        val rows = manifest.rows.associateBy { it.table to it.key }
        require(rows.values.all { rowHash(it.table, it.key) == it.hash }) { "RECOVERY_IMMUTABLE_PROOF_CHANGED" }
        val keys = manifest.graph.nodes.map { it.key }.toSet()
        require(rows.values.all { it.key in keys } && keys.all { "next_request_origins" to it in rows })
        val aliases = manifest.graph.aliases.associate { it.logical to it.actual }
        for (node in manifest.graph.nodes.filter { it.category == NextRecoveryCategory.STRUCTURE }) {
            val key = node.key
            val origin = requireNotNull(database.nextRequestDao().origin(key.kind, key.requestId))
            val round = requireNotNull(roundOperationIntent(origin.intentJson))
            require(origin.kind == key.kind && origin.requestId == key.requestId && origin.protocol == 5 && origin.queueId > 0 &&
                origin.accountId == access.session.authentication.userId && origin.serverInstanceId == access.session.serverInstanceId &&
                origin.syncEpoch == access.session.syncEpoch && round.capturedDeviceId == access.deviceId)
            val operation = round.operation
            require(operation.operationId == key.requestId)
            require(operation.entityType == node.entityType && operation.entityUuid == node.entityUuid)
            if (node.state == NextRecoveryRequestState.ACCEPTED) {
                val accepted = NextStructuralCausalStore(database).recoveryAcceptedOperationProof(key.requestId, access)
                require(accepted.first == operation && accepted.second == rows.getValue("next_acceptances" to key).hash)
            }
            val depHash = rowHash("next_structural_dependencies", key)
            require(rows["next_structural_dependencies" to key]?.hash == depHash)
            val dependency = database.nextStructuralCausalDao().dependency(key.requestId)
            if (dependency != null) {
                require(dependency.kind == key.kind && dependency.logicalOrder == origin.queueId &&
                    dependency.originHash == rows.getValue("next_request_origins" to key).hash)
                dependency.predecessorId?.let { parent ->
                    val predecessor = NextRecoveryKey(NEXT_OPERATION, parent)
                    require(dependency.predecessorOriginHash == rows.getValue("next_request_origins" to predecessor).hash &&
                        dependency.predecessorDependencyHash == rows.getValue("next_structural_dependencies" to predecessor).hash)
                    require(manifest.graph.dependencies.any { it.predecessor == predecessor &&
                        it.successor == (aliases[key] ?: key) && it.reason == NextRecoveryDependencyReason.STRUCTURAL_PREDECESSOR })
                }
            } else require(key in aliases.values || operation.entityType == "metric" && operation.action == "delete")
            val replacement = database.nextStructuralCausalDao().supersession(key.requestId)
            require(rows["next_structural_supersessions" to key]?.hash == rowHash("next_structural_supersessions", key))
            if (replacement != null) {
                require(aliases[key] == NextRecoveryKey(NEXT_OPERATION, replacement.replacementId) &&
                    replacement.originalOriginHash == rows.getValue("next_request_origins" to key).hash &&
                    replacement.replacementOriginHash == rows.getValue("next_request_origins" to aliases.getValue(key)).hash &&
                    replacement.originalDependencyHash == depHash && replacement.originalQueueId == origin.queueId)
                manifest.requests.singleOrNull { it.key == aliases.getValue(key) }?.let { request ->
                    val source = NextRecoverySourceSnapshot.decode(payloads.read(id, request.source), request.sourceHash)
                    NextStructuralCausalStore(database).recoveryRetiredReplacementProof(key.requestId, access, source)
                }
            } else require(key !in aliases)
        }
    }


    private suspend fun validateSource(access: LocalSyncAccess, metadata: ChallengeMetadata, id: String,
        request: NextRecoveryAuditRequest, node: NextRecoveryNode) {
        currentCoroutineContext().ensureActive()
        val key = request.key
        require(node.category == NextRecoveryCategory.STRUCTURE && node.protections.isEmpty())
        val args = arrayOf<Any>(key.kind, key.requestId)
        requireNotNull(NextRequestSql.rowHash(sql, "next_abandoned_requests", "kind=? AND requestId=?", args))
        sql.query("SELECT recoveryId,disposition,sourceHash,sourceSnapshotHash,originHash,transmissionHash,rejectionHash " +
            "FROM next_abandoned_requests WHERE kind=? AND requestId=?", args).use { c ->
            require(c.moveToFirst() && c.getString(0) == id && c.getString(1) == request.state.name &&
                c.getString(2) == request.sourceHash && c.getString(3) == request.source.hash && c.getString(4) == request.originHash &&
                c.getString(5) == request.transmissionHash && c.getString(6) == request.rejectionHash)
        }
        val source = NextRecoverySourceSnapshot.decode(payloads.read(id, request.source), request.sourceHash)
        val origin = requireNotNull(database.nextRequestDao().origin(key.kind, key.requestId))
        require(source.id == origin.queueId && source.operationId == key.requestId && origin.kind == key.kind &&
            origin.protocol == 5 && origin.sourceHash == request.sourceHash && rowHash("next_request_origins", key) == request.originHash &&
            origin.accountId == access.session.authentication.userId && origin.serverInstanceId == access.session.serverInstanceId &&
            origin.syncEpoch == access.session.syncEpoch)
        val round = requireNotNull(roundOperationIntent(origin.intentJson)); val operation = round.operation
        require(round.capturedDeviceId == access.deviceId && operation.operationId == key.requestId &&
            round.restartFrontier == null && operation.entityType == node.entityType && operation.entityUuid == node.entityUuid &&
            source.entityUuid == operation.entityUuid && source.wireEntityUuid == operation.entityUuid && source.action == operation.action &&
            source.recordType == when (operation.entityType) { "plan_node" -> "habit"; "metric" -> "metric"; "activity_metric_link" -> "link"; else -> error("Not a structure") })
        require(NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(source.id)) == null &&
            NextRequestSql.rowHash(sql, "sync_outbox", "operationId=?", arrayOf(source.operationId)) == null)
        require(rowHash("next_acceptances", key) == null && NextRequestSql.rowHash(sql, "one_time_transmissions", "operationId=?", arrayOf(key.requestId)) == null)
        require(rowHash("next_transmissions", key) == request.transmissionHash && rowHash("next_rejections", key) == request.rejectionHash)
        if (request.state == NextRecoveryRequestState.NEVER_SENT) {
            require(source.payloadJson == null && source.baseRevision == null && source.basePayloadJson == null &&
                source.attemptedAt == null && source.attemptCount == 0 && source.lastError == null && source.errorCode == null && source.deadLetteredAt == null)
        } else validateRejection(access, metadata, origin, request)
    }

    /** Separate retired evidence path: does not weaken liveOrigin()/journal() or create an ACK. */
    private suspend fun validateRejection(access: LocalSyncAccess, metadata: ChallengeMetadata,
        origin: NextRequestOriginEntity, request: NextRecoveryAuditRequest) {
        val key = request.key
        val sent = requireNotNull(database.nextRequestDao().transmission(key.kind, key.requestId))
        require(sent.kind == key.kind && sent.requestId == key.requestId && sent.queueId == origin.queueId && sent.protocol == 5 &&
            sent.accountId == origin.accountId && sent.serverInstanceId == origin.serverInstanceId && sent.syncEpoch == origin.syncEpoch &&
            sent.deviceId == access.deviceId && nextRequestHash(sent.wireBytes) == sent.wireHash)
        val operation = validateNextOperationEnvelope(origin.intentJson, sent.wireBytes, requireNotNull(access.deviceId))
        val failed = requireNotNull(database.nextSyncStateDao().rejection(key.kind, key.requestId))
        require(failed.kind == key.kind && failed.requestId == key.requestId &&
            failed.originHash == request.originHash && failed.transmissionHash == request.transmissionHash &&
            failed.resultHash == nextRequestHash(failed.resultJson.toByteArray(Charsets.UTF_8)))
        val result = decodeFrozenSyncRequest(failed.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
        validateTaskResultBinding(operation, result); requirePermanentProblem(result.status, result.errorCode)
        validateRoundResultBinding(decodeFrozenSyncRequest(sent.wireBytes, RoundSyncPushRequest.serializer()),
            RoundSyncPushResponse(listOf(result), 1, metadata.checkpoints, metadata.births))
    }

    private suspend fun rowHash(table: String, key: NextRecoveryKey): String? = when (table) {
        "next_structural_dependencies" -> NextRequestSql.rowHash(sql, table, "operationId=?", arrayOf(key.requestId))
        "next_structural_supersessions" -> NextRequestSql.rowHash(sql, table, "originalId=?", arrayOf(key.requestId))
        else -> { require(table in requestTables); NextRequestSql.rowHash(sql, table, "kind=? AND requestId=?", arrayOf(key.kind, key.requestId)) }
    }

    private fun memberKeys(id: String): List<NextRecoveryKey> = sql.query("SELECT kind,requestId FROM next_abandoned_requests " +
        "WHERE recoveryId=? ORDER BY kind,requestId LIMIT 10001", arrayOf(id)).use { c -> buildList {
        while (c.moveToNext()) { require(c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getType(1) == Cursor.FIELD_TYPE_STRING); add(NextRecoveryKey(c.getString(0), c.getString(1))) }
        require(size <= 10_000)
    } }

    private fun reference(id: String, key: String, hash: String, limit: Int): NextRecoveryPayloadReference {
        require(recoveryHash(hash))
        val size = sql.query("SELECT SUM(length(bytes)) FROM next_recovery_audit_payloads WHERE recoveryId=? AND payloadKey=?",
            arrayOf(id, key)).use { require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER); it.getLong(0) }
        require(size in 1L..limit.toLong())
        return NextRecoveryPayloadReference(key, size.toInt(), hash)
    }
}
