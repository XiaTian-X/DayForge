package com.dayforge.data.repository

import com.dayforge.data.api.SyncV2Api
import com.dayforge.data.api.dto.TimerCommandBatchRequest
import com.dayforge.data.api.dto.TimerCommandRequest
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.TimerCommandEntity
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Uploads the independent, ordered timer-command queue without rewriting history. */
@Singleton
class TimerSyncRepository @Inject constructor(
    private val api: SyncV2Api,
    private val timeLogDao: TimeLogDao
) {
    fun observeRejectedCommands(): Flow<List<TimerCommandEntity>> =
        timeLogDao.observeRejectedTimerCommands()

    suspend fun retryRejectedCommand(id: Long) {
        timeLogDao.retryRejectedTimerCommand(id, UUID.randomUUID().toString())
    }

    suspend fun retryAllRejectedCommands() {
        timeLogDao.getRejectedTimerCommands().forEach { retryRejectedCommand(it.id) }
    }

    /**
     * Explicitly abandon a rejected local timer intent and converge on the
     * authoritative server session. Active sessions controlled by this device
     * are cancelled first; sessions on another controller remain untouched.
     */
    suspend fun cancelRejectedCommandAndUseServer(id: Long, deviceId: String) {
        val rejected = timeLogDao.getRejectedTimerCommand(id)
            ?: throw IllegalStateException("计时同步问题已被处理")
        val status = api.timerStatus(rejected.sessionUuid, deviceId)
        val session = status.session
        when (session?.state) {
            null, "cancelled" -> timeLogDao.resolveRejectedTimerCommand(
                rejected.sessionUuid,
                removeLocalSession = true
            )
            "completed" -> timeLogDao.resolveRejectedTimerCommand(
                rejected.sessionUuid,
                removeLocalSession = false
            )
            "running", "paused" -> {
                if (session.controllerDeviceId != deviceId) {
                    // The authoritative timer remains on its current controller;
                    // only discard this device's stale local representation.
                    timeLogDao.resolveRejectedTimerCommand(
                        rejected.sessionUuid,
                        removeLocalSession = true
                    )
                    return
                }
                val occurredAt = maxOf(
                    Instant.parse(status.serverTime),
                    Instant.parse(session.stateChangedAt).plusMillis(1)
                )
                val cancel = TimerCommandRequest(
                    commandId = UUID.randomUUID().toString(),
                    sessionId = session.sessionId,
                    sequence = session.nextCommandSequence,
                    commandType = "cancel",
                    occurredAt = occurredAt.toString(),
                    expectedControlGeneration = session.controlGeneration,
                    expectedRevision = session.revision,
                    activeElapsedMs = session.activeElapsedMs.coerceAtMost(
                        session.maxDurationSeconds.toLong() * 1_000L
                    )
                )
                val result = api.pushTimerCommands(
                    TimerCommandBatchRequest(deviceId, listOf(cancel))
                ).results.singleOrNull()
                    ?: throw IllegalStateException("服务器未返回计时取消结果")
                check(result.commandId == cancel.commandId && result.sessionId == cancel.sessionId) {
                    "服务器返回了不匹配的计时取消结果"
                }
                check(result.status == "applied" || result.status == "already_applied") {
                    result.message ?: "服务器拒绝取消计时：${result.errorCode}"
                }
                timeLogDao.resolveRejectedTimerCommand(
                    rejected.sessionUuid,
                    removeLocalSession = true
                )
            }
            else -> throw IllegalStateException("未知服务器计时状态：${session.state}")
        }
    }

    suspend fun pushPending(deviceId: String) {
        var rounds = 0
        while (true) {
            check(++rounds <= MAX_ROUNDS) { "计时同步队列超过单次处理上限" }
            val rows = timeLogDao.getPendingTimerCommands(MAX_BATCH_SIZE)
            if (rows.isEmpty()) return
            val response = api.pushTimerCommands(
                TimerCommandBatchRequest(deviceId, rows.map(::toRequest))
            )
            val byId = rows.associateBy { it.commandId }
            val acknowledged = mutableSetOf<String>()
            var madeProgress = false
            var retryAfterPredecessor = false
            response.results.forEach { result ->
                val row = byId[result.commandId] ?: return@forEach
                acknowledged += result.commandId
                when (result.status) {
                    "applied", "already_applied" -> {
                        timeLogDao.deleteTimerCommand(row.id)
                        madeProgress = true
                    }
                    "conflict", "rejected" -> {
                        if (result.errorCode in TRANSIENT_COMMAND_ERRORS) {
                            timeLogDao.markTimerCommandAttempt(
                                row.id, result.errorCode, result.message
                            )
                            retryAfterPredecessor = true
                        } else {
                            timeLogDao.deadLetterTimerCommand(
                                row.id,
                                result.errorCode,
                                result.message,
                                System.currentTimeMillis()
                            )
                            throw TimerSyncRequiresAttentionException(
                                result.message ?: "计时命令被服务器拒绝：${result.errorCode}"
                            )
                        }
                    }
                    else -> throw IllegalStateException("未知计时同步结果：${result.status}")
                }
            }
            if (acknowledged.size != rows.size) {
                throw IllegalStateException("服务器未返回全部计时命令的处理结果")
            }
            if (retryAfterPredecessor && !madeProgress) return
        }
    }

    private fun toRequest(row: TimerCommandEntity) = TimerCommandRequest(
        commandId = row.commandId,
        sessionId = row.sessionUuid,
        sequence = row.sequence,
        commandType = row.commandType,
        occurredAt = Instant.ofEpochMilli(row.occurredAt).toString(),
        expectedControlGeneration = row.expectedControlGeneration,
        expectedRevision = row.expectedRevision,
        activityUuid = row.activityUuid,
        timezone = row.timezone,
        activeElapsedMs = row.activeElapsedMillis
    )

    private companion object {
        const val MAX_BATCH_SIZE = 100
        const val MAX_ROUNDS = 100
        val TRANSIENT_COMMAND_ERRORS = setOf("MISSING_PREDECESSOR", "OPERATION_IN_PROGRESS")
    }
}

class TimerSyncRequiresAttentionException(message: String) : IllegalStateException(message)
