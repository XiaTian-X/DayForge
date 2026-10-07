package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject

/** An in-memory edit ticket, never a saved-state credential or a caller-selected owner. */
class ObjectEditAuthority internal constructor(
    internal val session: LocalDataSession,
    internal val uuid: String,
    internal val type: String,
    internal val original: JsonObject
)

data class ObjectEditSnapshot<T>(val value: T?, val authority: ObjectEditAuthority?)

/**
 * Formal typed editors use the existing v5 producer. Legacy editors remain on their original
 * transaction path until the coordinated switch. There is no protocol inference from an icon.
 */
@Singleton
class NextObjectEditor @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val icons: AccountIconController
) {
    private val producer = NextCoreLocalIntentStore(database, tokens, sessions)

    internal suspend fun habit(id: Long): ObjectEditSnapshot<HabitEntity> = sessions.exclusive {
        val access = tokens.localCoreWriteAccess()
        database.withTransaction {
            val row = database.habitDao().getHabitById(id)
            val ticket = row?.takeIf { it.appearance != null }?.let {
                ObjectEditAuthority(requireNotNull(access) { "OBJECT_EDIT_ACCESS_DENIED" }.session,
                    it.uuid, "plan_node", NextStructureMapper.writePlan(it))
            }
            check(tokens.localCoreWriteAccess() == access) { "OBJECT_EDIT_SESSION_CHANGED" }
            ObjectEditSnapshot(row, ticket)
        }
    }

    internal suspend fun metric(id: Long): ObjectEditSnapshot<MetricEntity> = sessions.exclusive {
        val access = tokens.localCoreWriteAccess()
        database.withTransaction {
            val row = database.metricDao().getMetricById(id)
            val ticket = row?.takeIf { it.appearance != null }?.let {
                ObjectEditAuthority(requireNotNull(access) { "OBJECT_EDIT_ACCESS_DENIED" }.session,
                    it.uuid, "metric", NextStructureMapper.writeMetric(it))
            }
            check(tokens.localCoreWriteAccess() == access) { "OBJECT_EDIT_SESSION_CHANGED" }
            ObjectEditSnapshot(row, ticket)
        }
    }

    internal suspend fun <T> editHabit(edited: HabitEntity, ticket: ObjectEditAuthority,
        commit: suspend (HabitEntity) -> T): T {
        require(ticket.type == "plan_node" && ticket.uuid == edited.uuid)
        val before = requireNotNull(database.habitDao().getHabitById(edited.id)) { "OBJECT_EDIT_NOT_FOUND" }
        require(edited.completionPolicy == before.completionPolicy) { "OBJECT_EDIT_POLICY_CHANGE_REQUIRES_HISTORY_CHECK" }
        authorize(ticket, requireNotNull(edited.appearance), requireNotNull(before.appearance),
            before.completionPolicy == "one_and_done")
        return producer.write(ticket.session) {
            val current = requireNotNull(database.habitDao().getHabitById(edited.id)) { "OBJECT_EDIT_NOT_FOUND" }
            check(current.uuid == ticket.uuid && NextStructureMapper.writePlan(current) == ticket.original) {
                "OBJECT_EDIT_CHANGED_RELOAD_REQUIRED"
            }
            val metadata = requireNotNull(current.planMetadata)
            val preferred = if (edited.bestTime == current.bestTime) metadata.preferredLocalTime else
                edited.bestTime?.let { LocalTime.of((it / 60).toInt(), (it % 60).toInt()).toString() }
            // Display-only editors never overwrite the authoritative projection or unseen planning fields.
            val saved = edited.copy(createdAt = current.createdAt, activityRate = current.activityRate,
                activityRateUpdatedAt = current.activityRateUpdatedAt,
                oneTimeConfirmedVersion = current.oneTimeConfirmedVersion,
                oneTimeConfirmedHeadEventUuid = current.oneTimeConfirmedHeadEventUuid,
                oneTimeConfirmedCompletionEventUuid = current.oneTimeConfirmedCompletionEventUuid,
                planMetadata = metadata.copy(preferredLocalTime = preferred), updatedAt = System.currentTimeMillis())
            NextStructureMapper.writePlan(saved)
            commit(saved)
        }
    }

    internal suspend fun <T> editMetric(edited: MetricEntity, ticket: ObjectEditAuthority,
        commit: suspend (MetricEntity) -> T): T {
        require(ticket.type == "metric" && ticket.uuid == edited.uuid)
        val before = requireNotNull(database.metricDao().getMetricById(edited.id)) { "OBJECT_EDIT_NOT_FOUND" }
        authorize(ticket, requireNotNull(edited.appearance), requireNotNull(before.appearance), false)
        return producer.write(ticket.session) {
            val current = requireNotNull(database.metricDao().getMetricById(edited.id)) { "OBJECT_EDIT_NOT_FOUND" }
            check(current.uuid == ticket.uuid && NextStructureMapper.writeMetric(current) == ticket.original) {
                "OBJECT_EDIT_CHANGED_RELOAD_REQUIRED"
            }
            val saved = edited.copy(createdAt = current.createdAt, updatedAt = System.currentTimeMillis())
            NextStructureMapper.writeMetric(saved)
            commit(saved)
        }
    }

    private suspend fun authorize(ticket: ObjectEditAuthority, appearance: ObjectAppearance,
        previous: ObjectAppearance, oneTime: Boolean) {
        // Immutable asset metadata is checked BEFORE entering the producer's non-reentrant account lock.
        // The producer rechecks the captured session and the transaction proves the original structure.
        icons.authorizeEditReference(ticket.session, appearance.icon, previous.icon, oneTime)
    }
}
