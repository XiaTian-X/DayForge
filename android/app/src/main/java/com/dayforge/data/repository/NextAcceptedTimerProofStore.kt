package com.dayforge.data.repository

import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess

/** Existing complete accepted timer proof, now shared without a network/sender dependency.
 * Caller owns the authenticated transaction. Values grant no SQL, write or publication permit. */
internal class NextAcceptedTimerProofStore(private val database: HabitDatabase) {
    internal data class Receipt(val command: TimerCommandRequest, val result: TimerCommandResult, val proof: String,
        val transmissionProof: String, val challengeMetadata: ChallengeMetadata? = null)

    internal suspend fun read(access: LocalSyncAccess, id: String): Receipt {
        val dao = database.nextRequestDao()
        val originHash = requireNotNull(hash("next_request_origins", id))
        val transmissionHash = requireNotNull(hash("next_transmissions", id))
        val receiptHash = requireNotNull(hash("next_acceptances", id))
        val saved = requireNotNull(dao.acceptance(NEXT_TIMER, id))
        require(saved.kind == NEXT_TIMER && saved.requestId == id && saved.originHash == originHash &&
            saved.transmissionHash == transmissionHash && saved.resultHash == nextRequestHash(saved.resultJson.toByteArray(Charsets.UTF_8)))
        val origin = requireNotNull(dao.origin(NEXT_TIMER, id))
        val transmission = requireNotNull(dao.transmission(NEXT_TIMER, id))
        NextOriginalRequestProofStore(database).validateTimerJournalInTransaction(origin, transmission, access)
        val command = validateNextTimerEnvelope(origin.intentJson, transmission.wireBytes, requireNotNull(access.deviceId))
        val result = decodeFrozenSyncRequest(saved.resultJson.toByteArray(Charsets.UTF_8), TimerCommandResult.serializer())
        require(result.status == "applied")
        NextTimerResultMapper.validate(command, result, requireNotNull(access.deviceId))
        val metadata = if (roundTimerIntent(origin.intentJson) == null) null else {
            val actual = NextChallengeStore(database).activeInTransaction(access).second
            validateRoundTimerBinding(decodeFrozenSyncRequest(transmission.wireBytes, RoundTimerCommandBatchRequest.serializer()),
                RoundTimerCommandBatchResponse(listOf(result), requireNotNull(result.session).stateChangedAt, 1, actual.checkpoints, actual.births))
            actual
        }
        require(NextRequestSql.rowHash(database.openHelper.writableDatabase, "timer_command_outbox", "id=?", arrayOf(origin.queueId)) == null &&
            NextRequestSql.rowHash(database.openHelper.writableDatabase, "timer_command_outbox", "commandId=?", arrayOf(id)) == null)
        return Receipt(command, result, "$originHash:$transmissionHash:$receiptHash", transmissionHash, metadata)
    }

    internal suspend fun requireTerminal(access: LocalSyncAccess, id: String, session: String, sequence: Int): String {
        check(database.inTransaction())
        if (hash("next_acceptances", id) == null) rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
        val saved = read(access, id)
        require(saved.command.sessionId == session && saved.command.sequence == sequence &&
            saved.command.commandType in setOf("stop", "cancel") &&
            saved.result.session?.state == if (saved.command.commandType == "stop") "completed" else "cancelled")
        return saved.proof
    }

    private suspend fun hash(table: String, id: String) = NextRequestSql.rowHash(database.openHelper.writableDatabase,
        table, "kind=? AND requestId=?", arrayOf(NEXT_TIMER, id))
}
