package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitDraft
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.PlanStructureMetadata
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Issued only by an explicit next-protocol workflow, never inferred from icon IDs or old rows. */
class ObjectCreationAuthority internal constructor(session: LocalDataSession,
    internal val rounds: NextRoundWriteScope? = null,
    creationClock: () -> Pair<Instant, ZoneId> = { Instant.now() to ZoneId.systemDefault() }
) : ObjectAppearanceAuthority(session) {
    // Opening a form/picker is not object creation. Freeze the original time at its first save,
    // then retain it on exact retry; a form left open across midnight must not gain an old anchor.
    private val creationTime by lazy(creationClock)
    internal val instant: Instant get() = creationTime.first
    internal val timezone: ZoneId get() = creationTime.second
    // Non-secret saved-draft binding. Reauthentication requires discarding/reopening the old draft,
    // rather than adopting another account's IDs, links or imported references.
    internal val scopeKey = listOf(session.authentication.userId, session.authentication.generation,
        session.serverInstanceId, session.syncEpoch).joinToString(":") +
        (rounds?.let { ":challenge:1:${it.access.deviceId}" } ?: "")
}

/** One account lock/transaction for a complete new object graph, including original v5 intent. */
@Singleton
class NextObjectCreator @Inject constructor(
    private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val icons: AccountIconController
) {
    private val producer = NextCoreLocalIntentStore(database, tokens, sessions)

    // Internal admission is reserved for coordinated v5 navigation. Merely injecting this service
    // does not change the current v4 protocol, register a device or classify existing test data.
    internal suspend fun capture(): ObjectCreationAuthority = sessions.exclusive {
        captureLocked()
    }

    /** Formal navigation: legacy stays legacy; partial/plain v5 is never silently adopted. */
    suspend fun captureForNavigation(): ObjectCreationAuthority? = sessions.exclusive {
        database.withTransaction {
            if (!NextProtocolAdmission.hasNextState(database)) return@withTransaction null
            val access = requireNotNull(tokens.localSyncAccess()) { "OBJECT_CREATE_SYNC_REQUIRED" }
            // Audit raw cursor types before any Room projection can coerce them.
            NextProtocolAdmission.requireRoundsOrEmpty(database, access)
            captureLocked().also { check(it.rounds != null) { "OBJECT_CREATE_CHALLENGE_SYNC_REQUIRED" } }
        }
    }

    private suspend fun captureLocked(): ObjectCreationAuthority {
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "OBJECT_CREATE_ACCESS_DENIED" }
        check(access.capabilities == null || "structure.write" in access.capabilities) { "OBJECT_CREATE_ACCESS_DENIED" }
        return database.withTransaction {
            val ticket = ObjectCreationAuthority(access.session, producer.captureDisplayedRoundsInTransaction())
            check(tokens.localCoreWriteAccess() == access) { "OBJECT_CREATE_SESSION_CHANGED" }
            ticket
        }
    }

    internal fun habit(draft: HabitDraft, parent: String?, ticket: ObjectCreationAuthority): HabitEntity {
        val goal = draft.habitType == HabitType.GOAL
        require(draft.appearance != null)
        require(if (goal) draft.completionPolicy == null else draft.completionPolicy in setOf("recurring", "one_and_done"))
        val once = draft.completionPolicy == "one_and_done"
        val time = ticket.instant.toEpochMilli()
        val metadata = PlanStructureMetadata(ticket.instant.toString(), 0,
            if (!goal && draft.schedule is HabitSchedule.Custom) ticket.instant.atZone(ticket.timezone).toLocalDate().toString() else null,
            null, if (goal) null else ticket.timezone.id,
            if (draft.habitType == HabitType.TIMER) "second" else null,
            draft.bestTime?.let { LocalTime.of((it / 60).toInt(), (it % 60).toInt()).toString() }, null)
        return HabitEntity(uuid = draft.id, name = draft.name, description = draft.description,
            habitType = draft.habitType, iconResId = 0, colorHex = draft.colorHex,
            schedule = draft.schedule, targetValue = draft.targetValue, isCountdown = draft.isCountdown,
            parentHabitId = parent, targetCycles = draft.targetCycles, failMode = draft.failMode,
            bestTime = draft.bestTime, createdAt = time, updatedAt = time, activityRateUpdatedAt = time,
            completionPolicy = draft.completionPolicy, oneTimeConfirmedVersion = if (once) 0 else null,
            appearance = draft.appearance, planMetadata = metadata).also { NextStructureMapper.writePlan(it) }
    }

    internal suspend fun <T> habits(rows: List<HabitEntity>, ticket: ObjectCreationAuthority,
        commit: suspend () -> T): T {
        for (row in rows) {
            NextStructureMapper.writePlan(row)
            icons.authorizeEditReference(ticket.session, requireNotNull(row.appearance).icon,
                IconReference.Role(if (row.completionPolicy == "one_and_done") "task.default" else "habit.default"),
                row.completionPolicy == "one_and_done")
        }
        return write(ticket, commit)
    }

    internal suspend fun <T> metric(row: MetricEntity, ticket: ObjectCreationAuthority,
        commit: suspend (MetricEntity) -> T): T {
        val time = ticket.instant.toEpochMilli()
        val saved = row.copy(createdAt = time, updatedAt = time, iconResId = 0)
        NextStructureMapper.writeMetric(saved)
        icons.authorizeEditReference(ticket.session, requireNotNull(saved.appearance).icon,
            IconReference.Role("metric.default"), false)
        return write(ticket) { commit(saved) }
    }

    private suspend fun <T> write(ticket: ObjectCreationAuthority, commit: suspend () -> T): T =
        ticket.rounds?.let { producer.writeRounds(it, commit) } ?: producer.write(ticket.session, commit)
}
