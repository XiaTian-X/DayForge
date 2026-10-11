@file:kotlinx.serialization.UseSerializers(
    com.dayforge.domain.model.ContractStringSerializer::class,
    com.dayforge.domain.model.ContractLongSerializer::class
)

package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.decodeSyncReply
import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.TimerActionAuthority
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.StructuralEditGuard
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Credential-free v4 display identity. Missing identity never authorizes an ID-only restart. */
@Serializable
@ConsistentCopyVisibility
data class LegacyWidgetRestartClaim internal constructor(
    internal val accountId: String,
    internal val authenticationGeneration: String,
    internal val serverInstanceId: String?,
    internal val syncEpoch: String?,
    internal val deviceId: String?,
    val habitId: Long,
    val habitUuid: String,
    internal val plan: JsonObject
) {
    internal fun session() = LocalDataSession(AuthenticationSession(accountId, authenticationGeneration), serverInstanceId, syncEpoch)
    fun encode(): String = Json.encodeToString(this)
    companion object {
        fun decode(value: String): LegacyWidgetRestartClaim {
            require(value.length <= 65_536)
            return decodeSyncReply(value.toByteArray(Charsets.UTF_8), 65_536, serializer(), {}).also {
                require(it.habitId > 0 && listOf(it.accountId, it.authenticationGeneration, it.habitUuid).all(::isContractUuid))
                require((it.serverInstanceId == null) == (it.syncEpoch == null))
                listOfNotNull(it.serverInstanceId, it.syncEpoch, it.deviceId).forEach { id -> require(isContractUuid(id)) }
            }
        }
        internal fun displayed(habit: HabitEntity, access: LocalCoreWriteAccess) = LegacyWidgetRestartClaim(
            access.session.authentication.userId, access.session.authentication.generation,
            access.session.serverInstanceId, access.session.syncEpoch, access.capturedDeviceId,
            habit.id, habit.uuid, SyncV2Mapper.planNode(habit))
    }
}

/** Authenticate a rendered claim BEFORE displaying confirmation; do not recapture on confirmation. */
@Singleton
class WidgetRestartEntry @Inject internal constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val editor: NextObjectEditor,
    private val facts: WidgetFactReader,
    private val structuralGuard: StructuralEditGuard
) {
    internal suspend fun prepare(id: Long, fact: WidgetFactClaim?, timer: TimerActionAuthority?,
        legacy: LegacyWidgetRestartClaim?): HabitReactivationRequest = sessions.exclusive {
        require(listOfNotNull(fact, timer, legacy).size == 1) { "WIDGET_RESTART_CLAIM_REQUIRED" }
        database.withTransaction {
            val access = requireNotNull(tokens.localCoreWriteAccess()) { "WIDGET_RESTART_STALE_ACCOUNT" }
            val habit = requireNotNull(database.habitDao().getVisibleHabitById(id)) { "WIDGET_RESTART_NOT_FOUND" }
            require(habit.habitType != HabitType.GOAL && (habit.targetCycles ?: 0) > 0) { "SYNC_RESTART_ACTIVITY_REQUIRED" }
            require(database.timeLogDao().getAllTimeLogsForHabit(id).none { it.endTime == null }) { "CHALLENGE_TIMER_UNFINISHED" }
            val request = if (legacy != null) {
                require(habit.appearance == null && legacy.habitId == id && legacy.habitUuid == habit.uuid &&
                    legacy.session() == access.session && legacy.deviceId == access.capturedDeviceId &&
                    legacy.plan == SyncV2Mapper.planNode(habit)) { "WIDGET_RESTART_STALE_DISPLAY" }
                NextChallengeStore(database).requirePlainInTransaction()
                HabitReactivationRequest(habit, null, access)
            } else {
                require(habit.appearance != null && habit.completionPolicy == "recurring") { "SYNC_RESTART_PROFILE_REQUIRED" }
                fact?.let {
                    require(it.habitId == id && habit.habitType in setOf(HabitType.CHECK_IN, HabitType.COUNTING))
                    require(facts.requireInTransaction(it).habit == habit) { "WIDGET_RESTART_STALE_DISPLAY" }
                }
                val ticket = requireNotNull(editor.habitInTransaction(id).authority)
                val rounds = requireNotNull(ticket.rounds) { "SYNC_RESTART_PROFILE_REQUIRED" }
                timer?.let {
                    it.validate()
                    require(habit.habitType == HabitType.TIMER && it.session() == access.session &&
                        it.deviceId == access.capturedDeviceId && it.habitUuid == habit.uuid &&
                        it.sessionUuid == null && it.originalPlan == NextStructureMapper.writePlan(habit).toString() &&
                        it.challengeContract == 1) { "WIDGET_RESTART_STALE_DISPLAY" }
                    val metadata = Json.decodeFromString(ChallengeMetadata.serializer(), rounds.metadataJson)
                    val head = rounds.pendingRestarts[habit.uuid]?.head ?: metadata.checkpoints
                        .singleOrNull { point -> point.head.activityUuid == habit.uuid }?.head ?: rounds.pendingInitials[habit.uuid]?.head
                    require(head != null && it.challengeHead == head) { "WIDGET_RESTART_ROUND_CHANGED" }
                }
                HabitReactivationRequest(habit, ticket)
            }
            check(tokens.localCoreWriteAccess() == access) { "WIDGET_RESTART_STALE_ACCOUNT" }
            request
        }
    }

    internal suspend fun commitLegacy(habit: HabitEntity, captured: LocalCoreWriteAccess) {
        // Permission refresh may perform sync; never do it while owning the account mutex/Room.
        structuralGuard.requireAllowed()
        sessions.exclusive {
            database.withTransaction {
                check(tokens.localCoreWriteAccess() == captured) { "WIDGET_RESTART_STALE_ACCOUNT" }
                require(captured.capabilities == null || "structure.write" in captured.capabilities) { "SYNC_STRUCTURE_WRITE_DENIED" }
                check(database.habitDao().getVisibleHabitById(habit.id) == habit) { "WIDGET_RESTART_STALE_DISPLAY" }
                clearLegacyHabitHistoryInTransaction(database, habit)
                check(tokens.localCoreWriteAccess() == captured) { "WIDGET_RESTART_STALE_ACCOUNT" }
            }
        }
    }
}

internal suspend fun clearLegacyHabitHistoryInTransaction(database: HabitDatabase, habit: HabitEntity) {
    check(database.inTransaction())
    NextChallengeStore(database).requirePlainInTransaction()
    val current = requireNotNull(database.habitDao().getHabitById(habit.id))
    require(current.appearance == null) { "SYNC_RESTART_PROFILE_REQUIRED" }
    check(current.uuid == habit.uuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
    require(current.completionPolicy != "one_and_done") { "ONE_TIME_HISTORY_IS_IMMUTABLE" }
    require(database.timeLogDao().getAllTimeLogsForHabit(current.id).none { it.endTime == null }) { "CHALLENGE_TIMER_UNFINISHED" }
    database.completionDao().deleteByHabitId(current.id)
    database.timeLogDao().deleteByHabitId(current.id)
    database.habitDao().updateIsActive(current.id, true)
}
