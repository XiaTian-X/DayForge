package com.dayforge.domain.model

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** UUIDv5 namespace = canonical activity UUID, name = a fixed UTF-8 contract value. */
fun initialChallengeRoundUuid(activityUuid: String): String {
    require(isContractUuid(activityUuid))
    val namespace = UUID.fromString(activityUuid)
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array())
    val bytes = digest.digest("dayforge.challenge.initial.v1".toByteArray(Charsets.UTF_8)).copyOf(16)
    bytes[6] = (bytes[6].toInt() and 0x0f or 0x50).toByte()
    bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
    val buffer = ByteBuffer.wrap(bytes)
    return UUID(buffer.long, buffer.long).toString()
}

@Serializable
data class ChallengeRoundHead(
    @SerialName("activity_uuid") val activityUuid: String,
    @SerialName("round_uuid") val roundUuid: String,
    @Serializable(with = ContractIntegerSerializer::class) val generation: Int
) {
    init {
        require(isContractUuid(activityUuid) && isContractUuid(roundUuid) && generation >= 0)
        require((generation == 0) == (roundUuid == initialChallengeRoundUuid(activityUuid)))
    }
}

@Serializable
data class ChallengeRestartIntent(
    @SerialName("activity_uuid") val activityUuid: String,
    @SerialName("round_uuid") val roundUuid: String,
    @SerialName("expected_round_uuid") val expectedRoundUuid: String,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("expected_generation") val expectedGeneration: Int,
    @Serializable(with = ContractLongSerializer::class)
    @SerialName("expected_plan_revision") val expectedPlanRevision: Long
) {
    init {
        require(isContractUuid(roundUuid) && expectedPlanRevision > 0)
        ChallengeRoundHead(activityUuid, expectedRoundUuid, expectedGeneration)
        require(roundUuid != expectedRoundUuid && roundUuid != initialChallengeRoundUuid(activityUuid))
    }
}

@Serializable
data class ChallengeRoundRecord(
    val head: ChallengeRoundHead,
    @SerialName("source_device_uuid") val sourceDeviceUuid: String?,
    @SerialName("restart_operation_uuid") val restartOperationUuid: String?,
    @SerialName("restart_intent") val restartIntent: ChallengeRestartIntent?
) {
    init {
        require(sourceDeviceUuid == null || isContractUuid(sourceDeviceUuid))
        require(restartOperationUuid == null || isContractUuid(restartOperationUuid))
        if (head.generation == 0) {
            require(sourceDeviceUuid == null && restartOperationUuid == null && restartIntent == null)
        } else {
            require(sourceDeviceUuid != null && restartOperationUuid != null && restartIntent != null)
            require(restartIntent.activityUuid == head.activityUuid && restartIntent.roundUuid == head.roundUuid &&
                restartIntent.expectedGeneration.toLong() + 1 == head.generation.toLong())
        }
    }
}

class ChallengeTransitionException(val code: String) : IllegalArgumentException(code)

fun initialChallengeRoundHead(activityUuid: String) = ChallengeRoundHead(activityUuid, initialChallengeRoundUuid(activityUuid), 0)

/** Preflight, not authority: caller must authenticate, replay and persist a real SQL CAS atomically. */
fun advanceChallenge(
    current: ChallengeRoundHead,
    intent: ChallengeRestartIntent,
    actualPlanRevision: Long,
    hasUnfinishedTimer: Boolean = false,
    deleted: Boolean = false
): ChallengeRoundHead {
    fun reject(code: String): Nothing = throw ChallengeTransitionException(code)
    if (deleted) reject("ENTITY_DELETED")
    if (current.activityUuid != intent.activityUuid) reject("CHALLENGE_ACTIVITY_MISMATCH")
    if (current.generation != intent.expectedGeneration || current.roundUuid != intent.expectedRoundUuid)
        reject("CHALLENGE_STATE_CONFLICT")
    if (actualPlanRevision != intent.expectedPlanRevision) reject("CHALLENGE_PLAN_CHANGED")
    if (hasUnfinishedTimer) reject("CHALLENGE_TIMER_UNFINISHED")
    if (current.generation == Int.MAX_VALUE) reject("CHALLENGE_STATE_EXHAUSTED")
    if (actualPlanRevision == Long.MAX_VALUE) reject("CHALLENGE_PLAN_REVISION_EXHAUSTED")
    return ChallengeRoundHead(current.activityUuid, intent.roundUuid, current.generation + 1)
}

/** Only changes the head; cannot prove acceptance, ownership or old record consistency by itself. */
fun applyChallengeRecord(current: ChallengeRoundHead, incoming: ChallengeRoundRecord): ChallengeRoundHead {
    fun reject(code: String): Nothing = throw ChallengeTransitionException(code)
    val head = incoming.head
    if (head.activityUuid != current.activityUuid) reject("CHALLENGE_ACTIVITY_MISMATCH")
    if (head.generation < current.generation) return current
    if (head.generation == current.generation) {
        if (head != current) reject("CHALLENGE_STATE_DIVERGED")
        return current
    }
    if (head.generation.toLong() != current.generation.toLong() + 1) reject("CHALLENGE_CHAIN_INCOMPLETE")
    if (incoming.restartIntent?.expectedRoundUuid != current.roundUuid) reject("CHALLENGE_STATE_DIVERGED")
    return head
}

/** Complete, non-time-ordered bootstrap/archive chain. Caller owns account and durable proof checks. */
fun rebuildChallengeHistory(activityUuid: String, records: List<ChallengeRoundRecord>): ChallengeRoundHead {
    var current = initialChallengeRoundHead(activityUuid)
    if (records.any { it.head.activityUuid != activityUuid }) throw ChallengeTransitionException("CHALLENGE_ACTIVITY_MISMATCH")
    val ordered = records.sortedBy { it.head.generation }
    if (ordered.firstOrNull()?.head != current) throw ChallengeTransitionException("CHALLENGE_CHAIN_INCOMPLETE")
    val rounds = mutableSetOf<String>()
    val sources = mutableSetOf<Pair<String?, String?>>()
    ordered.forEachIndexed { generation, record ->
        val source = record.sourceDeviceUuid to record.restartOperationUuid
        if (record.head.roundUuid in rounds || (generation > 0 && source in sources))
            throw ChallengeTransitionException("CHALLENGE_HISTORY_INVALID")
        if (record.head.generation != generation) throw ChallengeTransitionException("CHALLENGE_CHAIN_INCOMPLETE")
        current = applyChallengeRecord(current, record)
        rounds.add(record.head.roundUuid)
        sources.add(source)
    }
    return current
}
