package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextAcceptanceEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.Instant
import kotlinx.serialization.json.*

/** Ordered v5 timer send/ACK/recovery boundary. Does not rewind local timers or create duration facts. */
internal class NextTimerRequestStore(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val requests: NextCoreRequestStore
) {
    private data class Receipt(val command: TimerCommandRequest, val result: TimerCommandResult, val proof: String,
        val transmissionProof: String)

    suspend fun sendAndAccept(access: LocalSyncAccess, id: String): NextOperationAcceptance? {
        require(isContractUuid(id))
        val saved = sessions.exclusive {
            authorize(access)
            database.withTransaction {
                if (hash("next_acceptances", id) == null) null else receipt(access, id)
            }
        }
        if (saved != null) return acceptResult(access, id, saved.result, saved.transmissionProof)
        // Order is proved in the first journal transaction. Frozen unknown results remain exact replay.
        val delivered = requests.sendCommand(access, id, ::requireHead) ?: return null
        return accept(delivered)
    }

    suspend fun accept(delivery: NextCoreDelivery<TimerCommandBatchResponse>): NextOperationAcceptance {
        require(delivery.result.results.size == 1)
        Instant.parse(delivery.result.serverTime)
        return acceptResult(delivery.access, delivery.requestId, delivery.result.results.single(), delivery.transmissionProof)
    }

    internal suspend fun send(access: LocalSyncAccess, id: String): NextCoreDelivery<TimerCommandBatchResponse>? =
        requests.sendCommand(access, id, ::requireHead)

    /** Actual queue drain for the later unified scheduler; a rejection does not silently delete an intent. */
    suspend fun pushPending(access: LocalSyncAccess): Int {
        var accepted = 0
        while (true) {
            val pending = sessions.exclusive {
                authorize(access)
                database.withTransaction {
                    NextRequestSql.sources(database.openHelper.writableDatabase, "timer_command_outbox")
                    database.timeLogDao().getPendingTimerCommands(100)
                }
            }
            if (pending.isEmpty()) return accepted
            require(accepted <= 10_000 - pending.size) { "Timer queue exceeds one synchronization pass" }
            for (command in pending) {
                if (sendAndAccept(access, command.commandId) == null) return accepted
                accepted++
            }
        }
    }

    private suspend fun acceptResult(access: LocalSyncAccess, id: String, result: TimerCommandResult,
        transmissionProof: String): NextOperationAcceptance = sessions.exclusive {
        authorize(access)
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            NextRequestSql.requireOutboxEnabled(sql)
            require(isContractUuid(id))
            val dao = database.nextRequestDao()
            val originHash = hash("next_request_origins", id) ?: rejectNextRequest(NextRequestException.Reason.OLD_INTENT)
            val transmissionHash = hash("next_transmissions", id) ?: rejectNextRequest(NextRequestException.Reason.OLD_INTENT)
            if (transmissionHash != transmissionProof) rejectNextRequest(NextRequestException.Reason.SOURCE_CHANGED)
            val origin = requireNotNull(dao.origin(NEXT_TIMER, id))
            val transmission = requireNotNull(dao.transmission(NEXT_TIMER, id))
            requests.validateTimerJournalInTransaction(origin, transmission, access)
            val command = decodeFrozenSyncRequest(transmission.wireBytes, TimerCommandBatchRequest.serializer()).commands.single()
            val timer = NextTimerResultMapper.validate(command, result, requireNotNull(access.deviceId))
            val normalized = result.copy(status = "applied")
            val previous = if (command.sequence == 1) null else findReceipt(access, command.sessionId, command.sequence - 1)
            previous?.let { NextTimerResultMapper.validateAfter(requireNotNull(it.result.session), command, timer) }
            if (hash("next_acceptances", id) != null) {
                if (receipt(access, id).result != normalized) rejectNextRequest(NextRequestException.Reason.RESULT_CHANGED)
                authorize(access)
                return@withTransaction NextOperationAcceptance.REPLAYED
            }
            require(requests.requireTimerOriginInTransaction(access, id) == origin)
            val queue = requireNotNull(database.timeLogDao().getTimerCommand(origin.queueId))
            requireHead(queue)
            val sources = NextRequestSql.sources(sql, "timer_command_outbox")
            val local = database.timeLogDao().getTimeLogByUuid(command.sessionId)
            val segments = database.timeLogDao().getTimerSegments(command.sessionId)
            val allocations = database.timeLogDao().getDayAllocations(command.sessionId)
            val bytes = encodeSyncRequest(TimerCommandResult.serializer(), normalized)
            decodeFrozenSyncRequest(bytes, TimerCommandResult.serializer())
            val accepted = NextAcceptanceEntity(NEXT_TIMER, id, originHash, transmissionHash,
                nextRequestHash(bytes), bytes.toString(Charsets.UTF_8))
            dao.insertAcceptance(accepted)
            check(hash("next_acceptances", id) != null && dao.acceptance(NEXT_TIMER, id) == accepted)
            check(NextRequestSql.rowHash(sql, "timer_command_outbox", "id=?", arrayOf(queue.id)) == origin.sourceHash)
            database.timeLogDao().deleteTimerCommand(queue.id)
            check(NextRequestSql.sources(sql, "timer_command_outbox") == sources - queue.id)
            check(database.timeLogDao().getTimeLogByUuid(command.sessionId) == local &&
                database.timeLogDao().getTimerSegments(command.sessionId) == segments &&
                database.timeLogDao().getDayAllocations(command.sessionId) == allocations)
            check(hash("next_request_origins", id) == originHash && hash("next_transmissions", id) == transmissionHash &&
                hash("next_acceptances", id) != null && dao.acceptance(NEXT_TIMER, id) == accepted)
            if (previous != null) check(findReceipt(access, command.sessionId, command.sequence - 1)?.proof == previous.proof)
            NextRequestSql.requireOutboxEnabled(sql)
            authorize(access)
            NextOperationAcceptance.COMMITTED
        }
    }

    /** Full completed fact proof inside its caller's authenticated business/cursor transaction. */
    internal suspend fun completionProofInTransaction(access: LocalSyncAccess, change: SyncV2Change): String? {
        check(database.inTransaction())
        authorize(access, control = false)
        require(change.entityType == "activity_event" && change.payload["event_type"] == JsonPrimitive("duration_session"))
        val terminal = findReceipt(access, change.entityUuid, null) ?: return null
        val timer = requireNotNull(terminal.result.session)
        if (terminal.command.commandType != "stop" || timer.state != "completed") return null
        val previous = findReceipt(access, timer.sessionId, terminal.command.sequence - 1)
        previous?.let { NextTimerResultMapper.validateAfter(requireNotNull(it.result.session), terminal.command, timer) }
        val body = change.payload
        require(timer.completedEventId == change.entityUuid && body["activity_uuid"] == JsonPrimitive(timer.activityUuid) &&
            Instant.parse(body.getValue("started_at").jsonPrimitive.content) == Instant.parse(timer.startedAt) &&
            Instant.parse(body.getValue("ended_at").jsonPrimitive.content) == Instant.parse(requireNotNull(timer.endedAt)) &&
            body["timezone"] == JsonPrimitive(timer.timezone) && body["duration_milliseconds"]?.jsonPrimitive?.longOrNull == timer.activeElapsedMs &&
            body["source_type"] == JsonPrimitive("app") && body["source_device_id"] == JsonPrimitive(timer.controllerDeviceId) &&
            body["external_event_id"] == JsonPrimitive(timer.sessionId) &&
            body.getValue("metadata").jsonObject["timer_session_id"] == JsonPrimitive(timer.sessionId))
        requireNoCommands(timer.sessionId)
        return terminal.proof + ":" + (previous?.proof ?: "no-local-predecessor")
    }

    private suspend fun receipt(access: LocalSyncAccess, id: String): Receipt {
        val dao = database.nextRequestDao()
        val originHash = requireNotNull(hash("next_request_origins", id))
        val transmissionHash = requireNotNull(hash("next_transmissions", id))
        val receiptHash = requireNotNull(hash("next_acceptances", id))
        val saved = requireNotNull(dao.acceptance(NEXT_TIMER, id))
        require(saved.kind == NEXT_TIMER && saved.requestId == id && saved.originHash == originHash &&
            saved.transmissionHash == transmissionHash && saved.resultHash == nextRequestHash(saved.resultJson.toByteArray(Charsets.UTF_8)))
        val origin = requireNotNull(dao.origin(NEXT_TIMER, id))
        val transmission = requireNotNull(dao.transmission(NEXT_TIMER, id))
        requests.validateTimerJournalInTransaction(origin, transmission, access)
        val command = decodeFrozenSyncRequest(transmission.wireBytes, TimerCommandBatchRequest.serializer()).commands.single()
        val result = decodeFrozenSyncRequest(saved.resultJson.toByteArray(Charsets.UTF_8), TimerCommandResult.serializer())
        require(result.status == "applied")
        NextTimerResultMapper.validate(command, result, requireNotNull(access.deviceId))
        require(NextRequestSql.rowHash(database.openHelper.writableDatabase, "timer_command_outbox", "id=?", arrayOf(origin.queueId)) == null &&
            NextRequestSql.rowHash(database.openHelper.writableDatabase, "timer_command_outbox", "commandId=?", arrayOf(id)) == null)
        return Receipt(command, result, "$originHash:$transmissionHash:$receiptHash", transmissionHash)
    }

    private suspend fun findReceipt(access: LocalSyncAccess, session: String, sequence: Int?): Receipt? {
        require(isContractUuid(session))
        val ids = database.openHelper.writableDatabase.query(
            "SELECT requestId FROM next_acceptances WHERE kind=? AND resultJson LIKE ? LIMIT 10001",
            arrayOf(NEXT_TIMER, "%$session%")).use { c -> buildList {
                while (c.moveToNext()) { require(c.getType(0) == Cursor.FIELD_TYPE_STRING); add(c.getString(0)) }
            } }
        require(ids.size <= 10_000)
        var found: Receipt? = null
        for (id in ids) {
            val candidate = receipt(access, id)
            if (candidate.command.sessionId == session && (sequence?.let { candidate.command.sequence == it } ?:
                (candidate.result.session?.state in setOf("completed", "cancelled")))) {
                require(found == null) // A second ACK for the same transition/terminal is divergence, not a winner.
                found = candidate
            }
        }
        return found
    }

    private suspend fun requireHead(command: TimerCommandEntity) {
        val sql = database.openHelper.writableDatabase
        NextRequestSql.sources(sql, "timer_command_outbox")
        val ids = sql.query("SELECT id FROM timer_command_outbox WHERE sessionUuid=? AND sequence<? LIMIT 10001",
            arrayOf<Any>(command.sessionUuid, command.sequence)).use { c -> buildList {
                while (c.moveToNext()) { require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER); add(c.getLong(0)) }
            } }
        require(ids.size <= 10_000)
        ids.forEach { NextRequestSql.rowHash(sql, "timer_command_outbox", "id=?", arrayOf(it)) }
        if (ids.isNotEmpty()) rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
    }

    private suspend fun requireNoCommands(session: String) {
        require(!database.openHelper.writableDatabase.query("SELECT 1 FROM timer_command_outbox WHERE sessionUuid=? LIMIT 1",
            arrayOf(session)).use { it.moveToFirst() })
    }

    private suspend fun hash(table: String, id: String) = NextRequestSql.rowHash(database.openHelper.writableDatabase,
        table, "kind=? AND requestId=?", arrayOf(NEXT_TIMER, id))

    private suspend fun authorize(access: LocalSyncAccess, control: Boolean = true) {
        if (tokens.syncAuthenticationSnapshot(access) == null) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
        if ((if (control) "timer.control" else "sync.read") !in access.capabilities)
            rejectNextRequest(NextRequestException.Reason.PERMISSION_DENIED)
    }
}
