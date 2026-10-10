package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Immutable in-memory preview receipt, not saved credentials or a shortcut around current proofs. */
internal class NextRecoveryConfirmation internal constructor(val recoveryId: String,
    access: LocalSyncAccess, internal val root: NextRecoveryKey, internal val graphHash: String,
    selected: Set<NextRecoveryKey>, checkpoint: ByteArray, drafts: List<Triple<ByteArray, ByteArray, ByteArray>>) {
    internal val access = access.copy(capabilities = Collections.unmodifiableSet(access.capabilities.toSet()))
    internal val selected: Set<NextRecoveryKey> = Collections.unmodifiableSet(selected.toSet())
    private val checkpointBytes = checkpoint.copyOf()
    private val draftBytes = drafts.map { Triple(it.first.copyOf(), it.second.copyOf(), it.third.copyOf()) }
    internal suspend fun checkpoint() = NextRecoveryAuditCodec.decode<NextRecoveryAuditCheckpoint>(checkpointBytes,
        com.dayforge.data.api.SYNC_REQUEST_LIMIT)
    internal suspend fun drafts() = draftBytes.map {
        NextRecoveryProjectionDraft(
            NextRecoveryAuditCodec.decode<NextRecoveryProjectionSnapshot>(it.first, NEXT_RECOVERY_SOURCE_BYTES),
            NextRecoveryAuditCodec.decode<NextRecoveryProjectionSnapshot>(it.second, NEXT_RECOVERY_SOURCE_BYTES),
            NextRecoveryAuditCodec.decode<NextRecoveryProjectionBaseline>(it.third, NEXT_RECOVERY_SOURCE_BYTES))
    }
}

/**
 * D-020 transaction participant. Settings reaches this only through the shared sync coordinator.
 * Caller owns sync serialization, authenticated account lock and Room transaction; no HTTP here.
 */
internal class NextRecoveryConfirmationStore(private val database: HabitDatabase,
    private val reader: NextRecoveryDependencyReader) {
    private val sql get() = database.openHelper.writableDatabase
    private val audit get() = NextRecoveryAuditStore(database)
    private val payloads get() = NextRecoveryPayloadStore(database)
    private val projections get() = NextRecoveryProjectionStore(database)

    suspend fun prepare(access: LocalSyncAccess, preview: NextRecoveryDependencyPreview,
        selected: Set<NextRecoveryKey>): NextRecoveryConfirmation {
        requireAuthority(access)
        val current = reader.readInTransaction(access, preview.root)
        require(current.canConfirm && current.graphHash == preview.graphHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
        require(selected == current.affected.map { it.key }.toSet()) { "SYNC_RECOVERY_WHOLE_SELECTION_REQUIRED" }
        val drafts = projections.plan(current)
        return NextRecoveryConfirmation(UUID.randomUUID().toString(), access, current.root, current.graphHash, selected,
            NextRecoveryAuditCodec.encode(audit.checkpoint(access)), drafts.map { Triple(
                NextRecoveryAuditCodec.encode(it.before), NextRecoveryAuditCodec.encode(it.after), NextRecoveryAuditCodec.encode(it.baseline)) })
    }

    suspend fun confirm(access: LocalSyncAccess, ticket: NextRecoveryConfirmation): NextRecoveryTerminalProof {
        requireAuthority(access); require(access == ticket.access) { "SYNC_RECOVERY_ACCESS_CHANGED" }
        // Lost result/cold retry: validate the complete existing receipt BEFORE asking for retired
        // queues or old projections. Never overwrite an edit made after the committed confirmation.
        if (NextRequestSql.rowHash(sql, "next_recovery_audits", "recoveryId=?", arrayOf(ticket.recoveryId)) != null) {
            return audit.read(access, ticket.recoveryId).also { validateTicket(it, ticket) }
        }
        val current = reader.readInTransaction(access, ticket.root)
        require(current.canConfirm && current.graphHash == ticket.graphHash &&
            current.affected.map { it.key }.toSet() == ticket.selected) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
        val checkpoint = audit.checkpoint(access)
        require(checkpoint == ticket.checkpoint()) { "SYNC_RECOVERY_CHECKPOINT_CHANGED" }
        val drafts = projections.plan(current)
        require(drafts == ticket.drafts()) { "SYNC_RECOVERY_PROJECTION_CHANGED" }
        val beforeSources = NextRequestSql.sources(sql, "sync_outbox")
        val preserved = NextRecoveryPreservationStore(database).snapshot(ticket.recoveryId)
        val rows = audit.graphRows(current.archive)
        val hashes = rows.associate { (it.table to it.key) to it.hash }
        val checkpointBytes = NextRecoveryAuditCodec.encode(checkpoint)
        val checkpointHash = nextRequestHash(checkpointBytes)
        // Provisional header is observable only inside this transaction. The complete reader below
        // must validate the final header, manifest and exact membership before COMMIT.
        sql.execSQL("INSERT INTO next_recovery_audits VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>(ticket.recoveryId,
            checkpoint.accountId, checkpoint.serverInstanceId, checkpoint.syncEpoch, checkpoint.deviceId,
            current.root.kind, current.root.requestId, current.graphHash, checkpointHash, "0".repeat(64), ticket.selected.size))
        val checkpointRef = payloads.write(ticket.recoveryId, "checkpoint", checkpointBytes)
        val retiredIds = linkedSetOf<Long>()
        val requests = current.affected.map { node ->
            currentCoroutineContext().ensureActive()
            require(node.key.kind == NEXT_OPERATION)
            val origin = requireNotNull(database.nextRequestDao().origin(node.key.kind, node.key.requestId))
            val source = requireNotNull(database.syncOutboxDao().getById(origin.queueId))
            require(source.operationId == node.key.requestId && beforeSources[source.id] == origin.sourceHash && retiredIds.add(source.id))
            val reference = payloads.write(ticket.recoveryId, "source:${node.key.kind}:${node.key.requestId}",
                NextRecoverySourceSnapshot.encode(source, origin.sourceHash))
            NextRecoveryAuditRequest(node.key, node.state, reference, origin.sourceHash,
                hashes.getValue("next_request_origins" to node.key), hashes["next_transmissions" to node.key], hashes["next_rejections" to node.key])
        }
        val projectionRefs = drafts.map { draft ->
            val key = "${draft.before.entityType}:${draft.before.entityUuid}"
            NextRecoveryAuditProjection(draft.before.entityType, draft.before.entityUuid,
                payloads.write(ticket.recoveryId, "projection_before:$key", NextRecoveryAuditCodec.encode(draft.before)),
                payloads.write(ticket.recoveryId, "projection_after:$key", NextRecoveryAuditCodec.encode(draft.after)),
                payloads.write(ticket.recoveryId, "projection_base:$key", NextRecoveryAuditCodec.encode(draft.baseline)))
        }
        val manifest = NextRecoveryAuditManifest(1, current.root, current.archive, checkpointRef, requests, rows, projectionRefs)
        val manifestRef = payloads.write(ticket.recoveryId, "manifest", NextRecoveryAuditCodec.encode(manifest))
        sql.execSQL("UPDATE next_recovery_audits SET manifestHash=? WHERE recoveryId=?", arrayOf<Any>(manifestRef.hash, ticket.recoveryId))
        for (request in requests) {
            currentCoroutineContext().ensureActive()
            sql.execSQL("INSERT INTO next_abandoned_requests VALUES(?,?,?,?,?,?,?,?,?)", arrayOf(request.key.kind, request.key.requestId,
                ticket.recoveryId, request.state.name, request.sourceHash, request.source.hash, request.originHash,
                request.transmissionHash, request.rejectionHash))
            val origin = requireNotNull(database.nextRequestDao().origin(request.key.kind, request.key.requestId))
            require(NextRequestSql.rowHash(sql, "sync_outbox", "id=? AND operationId=?",
                arrayOf<Any>(origin.queueId, request.key.requestId)) == request.sourceHash) { "SYNC_RECOVERY_SOURCE_CHANGED" }
            sql.execSQL("DELETE FROM sync_outbox WHERE id=? AND operationId=?", arrayOf<Any>(origin.queueId, request.key.requestId))
        }
        projections.apply(drafts)
        require(NextRequestSql.sources(sql, "sync_outbox") == beforeSources.filterKeys { it !in retiredIds }) { "SYNC_RECOVERY_QUEUE_CHANGED" }
        require(NextRecoveryPreservationStore(database).snapshot(ticket.recoveryId) == preserved) { "SYNC_RECOVERY_PROTECTED_STATE_CHANGED" }
        val result = audit.read(access, ticket.recoveryId)
        validateTicket(result, ticket)
        currentCoroutineContext().ensureActive()
        return result
    }

    private suspend fun requireAuthority(access: LocalSyncAccess) {
        check(database.inTransaction()); currentCoroutineContext().ensureActive()
        require(access.deviceId != null && "structure.write" in access.capabilities) { "SYNC_RECOVERY_WRITE_PERMISSION_REQUIRED" }
        NextRequestSql.requireOutboxEnabled(sql)
        NextProtocolAdmission.requireRoundsOrEmpty(database, access)
    }

    private suspend fun validateTicket(proof: NextRecoveryTerminalProof, ticket: NextRecoveryConfirmation) {
        val manifest = proof.manifest
        require(proof.recoveryId == ticket.recoveryId && manifest.root == ticket.root &&
            manifest.graph.preview(ticket.root).graphHash == ticket.graphHash && manifest.requests.map { it.key }.toSet() == ticket.selected)
        require(NextRecoveryAuditCodec.decode<NextRecoveryAuditCheckpoint>(payloads.read(ticket.recoveryId, manifest.checkpoint),
            com.dayforge.data.api.SYNC_REQUEST_LIMIT) == ticket.checkpoint())
        val expected = ticket.drafts().associateBy { it.before.entityType to it.before.entityUuid }
        require(manifest.projections.size == expected.size)
        for (projection in manifest.projections) {
            val draft = expected.getValue(projection.entityType to projection.entityUuid)
            require(NextRecoveryAuditCodec.decode<NextRecoveryProjectionSnapshot>(payloads.read(ticket.recoveryId, projection.before), NEXT_RECOVERY_SOURCE_BYTES) == draft.before &&
                NextRecoveryAuditCodec.decode<NextRecoveryProjectionSnapshot>(payloads.read(ticket.recoveryId, projection.after), NEXT_RECOVERY_SOURCE_BYTES) == draft.after &&
                NextRecoveryAuditCodec.decode<NextRecoveryProjectionBaseline>(payloads.read(ticket.recoveryId, projection.baseline), NEXT_RECOVERY_SOURCE_BYTES) == draft.baseline)
        }
    }
}
