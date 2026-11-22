package com.dayforge.data.repository

import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess

/** The existing original accepted-fact proof, extracted without network/authorization ownership.
 * Caller retains its original fresh authority, account lock and actual transaction.
 */
internal class NextAcceptedFactProofStore(private val database: HabitDatabase) {
    suspend fun read(access: LocalSyncAccess, id: String): Pair<SyncV2Operation, String> {
        check(database.inTransaction())
        val sql = database.openHelper.writableDatabase
        val dao = database.nextRequestDao()
        val args = arrayOf<Any>(NEXT_OPERATION, id)
        val originHash = requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", args))
        val receiptHash = NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", args)
            ?: rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
        val transmissionHash = requireNotNull(NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", args))
        val original = requireNotNull(dao.origin(NEXT_OPERATION, id))
        val sent = requireNotNull(dao.transmission(NEXT_OPERATION, id))
        require(original.kind == NEXT_OPERATION && original.requestId == id && original.protocol == 5 && original.queueId > 0 &&
            (original.serverInstanceId == null) == (original.syncEpoch == null))
        if (original.serverInstanceId != null && (original.serverInstanceId != access.session.serverInstanceId || original.syncEpoch != access.session.syncEpoch))
            rejectNextRequest(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED)
        NextOriginalRequestProofStore(database).journal(original, sent, access)
        val receipt = requireNotNull(dao.acceptance(NEXT_OPERATION, id))
        require(receipt.kind == NEXT_OPERATION && receipt.requestId == id && receipt.originHash == originHash &&
            receipt.transmissionHash == transmissionHash && receipt.resultHash == nextRequestHash(receipt.resultJson.toByteArray(Charsets.UTF_8)))
        val result = decodeFrozenSyncRequest(receipt.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
        val operation = decodeNextOperationIntent(original.intentJson)
        require(result.status == "applied" && operation.entityType in setOf("activity_event", "metric_observation") &&
            operation.payload["one_time"].let { it == null || it == kotlinx.serialization.json.JsonNull })
        NextOrdinaryResultMapper.validate(operation, result, requireNotNull(access.deviceId))
        roundOperationIntent(original.intentJson)?.let { round ->
            require(round.capturedDeviceId == access.deviceId && original.serverInstanceId == access.session.serverInstanceId &&
                original.syncEpoch == access.session.syncEpoch)
            val metadata = NextChallengeStore(database).activeInTransaction(access).second
            validateRoundResultBinding(decodeFrozenSyncRequest(sent.wireBytes, RoundSyncPushRequest.serializer()),
                RoundSyncPushResponse(listOf(result), 1, metadata.checkpoints, metadata.births))
        }
        require(NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(original.queueId)) == null &&
            database.syncOutboxDao().getByOperationId(id) == null)
        check(NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", args) == receiptHash)
        return operation to nextRequestHash(listOf(originHash, transmissionHash, receiptHash)
            .joinToString(";").toByteArray(Charsets.UTF_8))
    }

}
