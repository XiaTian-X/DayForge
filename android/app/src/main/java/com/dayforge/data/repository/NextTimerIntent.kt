package com.dayforge.data.repository

import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.TimerCommandRequest
import com.dayforge.data.api.dto.TimerCommandResult
import com.dayforge.data.api.dto.TimerStartPolicy
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.isContractUuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Private birth metadata. Only [command] is transmitted; local ancestry never becomes a server owner. */
@Serializable
internal data class NextTimerIntent(
    val command: TimerCommandRequest,
    val planPredecessorId: String? = null,
    val planQueueWatermark: Long? = null,
    val capturedDeviceId: String? = null
)

internal suspend fun decodeNextTimerIntent(json: String): NextTimerIntent {
    roundTimerIntent(json)?.let { return it.timer }
    val bytes = json.toByteArray(Charsets.UTF_8)
    // Both formats are strictly decoded. This reads old proof; it never adopts old queue rows.
    return if (Json.parseToJsonElement(json).jsonObject.containsKey("command"))
        decodeFrozenSyncRequest(bytes, NextTimerIntent.serializer())
    else NextTimerIntent(decodeFrozenSyncRequest(bytes, TimerCommandRequest.serializer()))
}

internal fun timerPolicy(habit: HabitEntity): TimerStartPolicy {
    require(habit.habitType == com.dayforge.data.model.HabitType.TIMER)
    val target = Math.multiplyExact(habit.targetValue, 60)
    return TimerStartPolicy(target, habit.isCountdown, when {
        target <= 0 -> 86_400
        habit.isCountdown -> target
        else -> minOf(target.toLong() * 3, 86_400L).toInt()
    }).validate()
}

/** Existing immutable start origin survives ACK and cancel, unlike the disposable local timer row. */
internal class NextTimerPolicyStore(private val database: HabitDatabase) {
    suspend fun capture(command: TimerCommandRequest, access: LocalCoreWriteAccess): NextTimerIntent {
        check(database.inTransaction())
        val habit = requireNotNull(database.habitDao().getHabitByUuid(requireNotNull(command.activityUuid)))
        if (habit.appearance == null) return NextTimerIntent(command) // Explicit foundation/legacy path only.
        require(habit.completionPolicy == "recurring" && habit.isActive)
        val policy = timerPolicy(habit)
        val causal = NextStructuralCausalStore(database)
        val pending = database.syncOutboxDao().getEntityIntents("habit", habit.uuid)
        val predecessor = pending.maxByOrNull { causal.logicalOrder(it) }
        predecessor?.let {
            require(it.action == "upsert")
            val origin = requireNotNull(database.nextRequestDao().origin(NEXT_OPERATION, it.operationId))
            require(origin.protocol == 5 && origin.accountId == access.session.authentication.userId &&
                origin.sourceHash == NextRequestSql.sourceHash(it))
            require(origin.serverInstanceId == null || origin.serverInstanceId == access.session.serverInstanceId &&
                origin.syncEpoch == access.session.syncEpoch)
        }
        return NextTimerIntent(command.copy(startPolicy = policy), predecessor?.operationId,
            NextRequestSql.watermark(database.openHelper.writableDatabase, "sync_outbox"), access.capturedDeviceId)
    }

    suspend fun starts(activityUuid: String? = null, sessionUuid: String? = null): List<Pair<com.dayforge.data.local.entity.NextRequestOriginEntity, NextTimerIntent>> {
        check(database.inTransaction())
        require((activityUuid == null) != (sessionUuid == null))
        val uuid = requireNotNull(activityUuid ?: sessionUuid)
        require(isContractUuid(uuid))
        val sql = database.openHelper.writableDatabase
        // Bound the relevant activity/session, not the entire account's retained command history.
        // The actual queue identity also selects damaged birth metadata so it cannot evade a start barrier.
        val column = if (activityUuid != null) "activityUuid" else "sessionUuid"
        val ids = sql.query("SELECT requestId FROM next_request_origins WHERE kind=? AND " +
            "(intentJson LIKE ? OR requestId IN (SELECT commandId FROM timer_command_outbox WHERE $column=?)) LIMIT 10001",
            arrayOf(NEXT_TIMER, "%$uuid%", uuid))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        require(ids.size <= 10_000)
        return ids.mapNotNull { id ->
            val origin = requireNotNull(database.nextRequestDao().origin(NEXT_TIMER, id))
            requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_TIMER, id)))
            val intent = decodeNextTimerIntent(origin.intentJson)
            val command = intent.command
            require(command.commandId == id && isContractUuid(command.sessionId))
            database.timeLogDao().getTimerCommand(origin.queueId)?.let { queued ->
                require(NextRequestSql.rowHash(sql, "timer_command_outbox", "id=?", arrayOf(origin.queueId)) == origin.sourceHash)
                require(timerRequest(queued) == command.copy(startPolicy = null))
            }
            if (command.commandType != "start" || command.startPolicy == null) null else {
                command.startPolicy.validate()
                require(intent.planQueueWatermark != null && intent.planQueueWatermark >= 0 &&
                    (intent.planPredecessorId == null || isContractUuid(intent.planPredecessorId)) &&
                    (intent.capturedDeviceId == null || isContractUuid(intent.capturedDeviceId)))
                origin to intent
            }
        }
    }

    suspend fun policy(access: LocalCoreWriteAccess, sessionUuid: String): TimerStartPolicy {
        require(isContractUuid(sessionUuid))
        val session = access.session
        val matches = starts(sessionUuid = sessionUuid).filter { (origin, intent) ->
            intent.command.sessionId == sessionUuid && origin.accountId == session.authentication.userId &&
                (origin.serverInstanceId == null || origin.serverInstanceId == session.serverInstanceId && origin.syncEpoch == session.syncEpoch)
        }
        require(matches.size == 1) { "TIMER_START_POLICY_PROOF_REQUIRED" }
        val (origin, intent) = matches.single()
        require(origin.protocol == 5 && origin.queueId > 0 &&
            (origin.serverInstanceId == null) == (origin.syncEpoch == null) &&
            (intent.capturedDeviceId == null || intent.capturedDeviceId == access.capturedDeviceId))
        val sql = database.openHelper.writableDatabase
        val dao = database.nextRequestDao()
        val args = arrayOf<Any>(NEXT_TIMER, origin.requestId)
        val originHash = requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", args))
        val transmissionHash = NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", args)
        val acceptanceHash = NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", args)
        if (transmissionHash != null) {
            val sent = requireNotNull(dao.transmission(NEXT_TIMER, origin.requestId))
            require(sent.kind == NEXT_TIMER && sent.requestId == origin.requestId && sent.protocol == 5 &&
                sent.queueId == origin.queueId && sent.accountId == origin.accountId &&
                sent.serverInstanceId == session.serverInstanceId && sent.syncEpoch == session.syncEpoch &&
                sent.deviceId == access.capturedDeviceId && sent.wireHash == nextRequestHash(sent.wireBytes))
            require(validateNextTimerEnvelope(origin.intentJson, sent.wireBytes, sent.deviceId) == intent.command)
        }
        if (acceptanceHash == null) {
            require(NextRequestSql.rowHash(sql, "timer_command_outbox", "id=?", arrayOf(origin.queueId)) == origin.sourceHash)
            val queued = requireNotNull(database.timeLogDao().getTimerCommand(origin.queueId))
            require(timerRequest(queued) == intent.command.copy(startPolicy = null))
        } else {
            requireNotNull(transmissionHash)
            val accepted = requireNotNull(dao.acceptance(NEXT_TIMER, origin.requestId))
            require(accepted.kind == NEXT_TIMER && accepted.requestId == origin.requestId &&
                accepted.originHash == originHash && accepted.transmissionHash == transmissionHash &&
                accepted.resultHash == nextRequestHash(accepted.resultJson.toByteArray(Charsets.UTF_8)))
            val result = decodeFrozenSyncRequest(accepted.resultJson.toByteArray(Charsets.UTF_8), TimerCommandResult.serializer())
            require(result.status == "applied")
            NextTimerResultMapper.validate(intent.command, result, requireNotNull(access.capturedDeviceId))
            require(NextRequestSql.rowHash(sql, "timer_command_outbox", "id=?", arrayOf(origin.queueId)) == null &&
                NextRequestSql.rowHash(sql, "timer_command_outbox", "commandId=?", arrayOf(origin.requestId)) == null)
        }
        return requireNotNull(intent.command.startPolicy)
    }
}
