@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class)

package com.dayforge.data.repository

import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.*
import com.dayforge.domain.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

/** NEW timer source only. The original start policy/frontier and command are not reinterpreted. */
@Serializable
internal data class NextRoundTimerIntent(
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("challenge_contract") val challengeContract: Int,
    val timer: NextTimerIntent,
    val context: ChallengeSourceContext,
    @SerialName("captured_device_id") val capturedDeviceId: String
) {
    init {
        require(challengeContract == 1 && isContractUuid(capturedDeviceId) && !context.legacyInitial)
        require(context.sourceUuid == timer.command.commandId && context.head != null && context.affectedHeads.isEmpty())
        require(timer.capturedDeviceId == null || timer.capturedDeviceId == capturedDeviceId)
        if (timer.command.commandType == "start") {
            require(timer.command.activityUuid == context.head.activityUuid && timer.command.startPolicy != null &&
                timer.capturedDeviceId == capturedDeviceId && timer.planQueueWatermark != null)
            timer.command.startPolicy.validate()
            require(timer.command.sequence == 1 && timer.command.expectedControlGeneration == 0 &&
                timer.command.expectedRevision == null && timer.command.activeElapsedMs == null &&
                timer.command.timezone in java.time.ZoneId.getAvailableZoneIds())
        } else {
            require(timer.command.sequence > 1 && timer.command.expectedControlGeneration > 0 &&
                timer.command.activityUuid == null && timer.command.timezone == null && timer.command.startPolicy == null)
        }
        RoundTimerCommandBatchRequest(1, capturedDeviceId, listOf(timer.command), listOf(context))
    }
}

internal suspend fun roundTimerIntent(body: String): NextRoundTimerIntent? =
    if (Json.parseToJsonElement(body).jsonObject.containsKey("challenge_contract"))
        decodeFrozenSyncRequest(body.toByteArray(Charsets.UTF_8), NextRoundTimerIntent.serializer()) else null

internal suspend fun validateNextTimerEnvelope(body: String, bytes: ByteArray, device: String): TimerCommandRequest {
    val round = roundTimerIntent(body)
    if (round == null) {
        val request = decodeFrozenSyncRequest(bytes, TimerCommandBatchRequest.serializer())
        require(request.deviceId == device && request.commands == listOf(decodeNextTimerIntent(body).command))
        return request.commands.single()
    }
    val request = decodeFrozenSyncRequest(bytes, RoundTimerCommandBatchRequest.serializer())
    require(round.capturedDeviceId == device && request.deviceId == device &&
        request.commands == listOf(round.timer.command) && request.contexts == listOf(round.context))
    return round.timer.command
}
