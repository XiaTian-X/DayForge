package com.dayforge.data.repository

import com.dayforge.data.api.dto.TimerCommandRequest
import com.dayforge.data.api.dto.TimerCommandResult
import com.dayforge.data.api.dto.TimerSessionResponse
import com.dayforge.domain.model.isContractUuid
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Authoritative command proof, never a local timer transition or a manual duration write. */
internal object NextTimerResultMapper {
    fun validate(command: TimerCommandRequest, result: TimerCommandResult, device: String): TimerSessionResponse {
        require(listOf(command.commandId, command.sessionId, device).all(::isContractUuid) &&
            command.sequence in 1 until Int.MAX_VALUE && command.expectedControlGeneration >= 0 &&
            (command.expectedRevision == null || command.expectedRevision in 1 until Int.MAX_VALUE))
        require(result.commandId == command.commandId && result.sessionId == command.sessionId &&
            result.status in setOf("applied", "already_applied") && result.errorCode == null && result.message == null)
        val timer = requireNotNull(result.session)
        require(listOf(timer.sessionId, timer.activityUuid, timer.controllerDeviceId).all(::isContractUuid) &&
            timer.sessionId == command.sessionId && timer.controllerDeviceId == device &&
            timer.revision > 0 && timer.nextCommandSequence == command.sequence + 1 &&
            timer.targetSeconds >= 0 && timer.maxDurationSeconds in 1..86_400 &&
            timer.activeElapsedMs in 0..timer.maxDurationSeconds.toLong() * 1000)
        val start = instant(timer.startedAt)
        val changed = instant(timer.stateChangedAt)
        require(start <= changed && changed == instant(command.occurredAt))
        require(timer.timezone in ZoneId.getAvailableZoneIds())
        timer.lastHeartbeatAt?.let(::instant)
        val state = when (command.commandType) {
            "start", "resume" -> "running"
            "pause" -> "paused"
            "stop" -> "completed"
            "cancel" -> "cancelled"
            "takeover" -> timer.state.also { require(it in setOf("running", "paused")) }
            else -> error("Unknown timer command")
        }
        require(timer.state == state)
        val generation = when (command.commandType) {
            "start" -> {
                require(command.sequence == 1 && command.expectedControlGeneration == 0 && command.expectedRevision == null &&
                    command.activityUuid == timer.activityUuid && command.timezone == timer.timezone &&
                    command.activeElapsedMs == null && start == changed && timer.revision == 1 && timer.activeElapsedMs == 0L)
                command.startPolicy?.let { policy ->
                    policy.validate()
                    require(timer.targetSeconds == policy.targetSeconds && timer.isCountdown == policy.isCountdown &&
                        timer.maxDurationSeconds == policy.maxDurationSeconds)
                }
                1
            }
            "takeover" -> {
                require(command.expectedControlGeneration in 1 until Int.MAX_VALUE)
                command.expectedControlGeneration + 1
            }
            else -> command.expectedControlGeneration.also { require(it > 0) }
        }
        require(timer.controlGeneration == generation)
        if (command.expectedRevision != null) require(timer.revision == command.expectedRevision + 1)
        if (command.commandType != "start") require(command.activityUuid == null && command.timezone == null && command.startPolicy == null)
        command.activeElapsedMs?.let { require(it in 0..86_400_000 && command.commandType in setOf("pause", "stop", "cancel")) }
        if (command.commandType == "pause" && command.activeElapsedMs != null)
            require(timer.activeElapsedMs == minOf(command.activeElapsedMs, timer.maxDurationSeconds.toLong() * 1000))
        if (timer.state in setOf("completed", "cancelled")) require(timer.endedAt?.let(::instant) == changed)
        else require(timer.endedAt == null)
        if (timer.state == "completed") require(timer.completedEventId == timer.sessionId &&
            (timer.targetSeconds == 0 || timer.activeElapsedMs >= timer.targetSeconds.toLong() * 1000))
        else require(timer.completedEventId == null)
        return timer
    }

    /** Use a known earlier receipt, not today's editable habit or optimistic local session. */
    fun validateAfter(previous: TimerSessionResponse, command: TimerCommandRequest, current: TimerSessionResponse) {
        require(previous.sessionId == current.sessionId && previous.nextCommandSequence == command.sequence &&
            previous.revision < Int.MAX_VALUE && current.revision == previous.revision + 1 &&
            previous.controlGeneration == command.expectedControlGeneration && previous.state in setOf("running", "paused") &&
            previous.activityUuid == current.activityUuid && instant(previous.startedAt) == instant(current.startedAt) &&
            previous.timezone == current.timezone && previous.isCountdown == current.isCountdown &&
            previous.targetSeconds == current.targetSeconds && previous.maxDurationSeconds == current.maxDurationSeconds &&
            instant(previous.stateChangedAt) <= instant(current.stateChangedAt))
        if (command.commandType == "takeover") require(previous.controllerDeviceId != current.controllerDeviceId && current.state == previous.state)
        else require(previous.controllerDeviceId == current.controllerDeviceId)
        when (command.commandType) {
            "pause" -> require(previous.state == "running")
            "resume" -> require(previous.state == "paused")
            "stop", "cancel", "takeover" -> Unit
            else -> error("Start has no predecessor")
        }
        val elapsed = if (previous.state == "paused" || command.commandType == "resume") previous.activeElapsedMs else {
            val measured = if (command.commandType != "takeover" && command.activeElapsedMs != null) command.activeElapsedMs else
                Math.addExact(previous.activeElapsedMs, Duration.between(instant(previous.stateChangedAt), instant(command.occurredAt)).toMillis())
            require(measured >= previous.activeElapsedMs)
            if (command.commandType == "cancel") measured else minOf(measured, previous.maxDurationSeconds.toLong() * 1000)
        }
        require(current.activeElapsedMs == elapsed)
    }

    private fun instant(value: String): Instant = Instant.parse(value).also {
        require(it.atZone(ZoneId.of("UTC")).year in 1..9999)
    }
}
