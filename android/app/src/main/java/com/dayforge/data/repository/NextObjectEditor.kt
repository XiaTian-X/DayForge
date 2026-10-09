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
abstract class ObjectAppearanceAuthority internal constructor(internal val session: LocalDataSession)

class ObjectEditAuthority internal constructor(
    session: LocalDataSession,
    internal val uuid: String,
    internal val type: String,
    internal val original: JsonObject,
    internal val rounds: NextRoundWriteScope? = null,
    internal val goalChildUuids: List<String>? = null
) : ObjectAppearanceAuthority(session)

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
        database.withTransaction { habitInTransaction(id) }
    }

    /** Read participant only: caller owns the account lock and Room snapshot. */
    internal suspend fun habitInTransaction(id: Long): ObjectEditSnapshot<HabitEntity> {
        check(database.inTransaction()) { "OBJECT_EDIT_TRANSACTION_REQUIRED" }
        val access = tokens.localCoreWriteAccess()
        val row = database.habitDao().getVisibleHabitById(id)
        val ticket = row?.takeIf { it.appearance != null }?.let {
            val rounds = producer.captureDisplayedRoundsInTransaction()
            ObjectEditAuthority(requireNotNull(access) { "OBJECT_EDIT_ACCESS_DENIED" }.session,
                it.uuid, "plan_node", NextStructureMapper.writePlan(it), rounds,
                if (rounds != null && it.habitType == com.dayforge.data.model.HabitType.GOAL)
                    database.habitDao().getChildrenByParentUuidOnce(it.uuid).map { child -> child.uuid }.sorted() else null)
        }
        check(tokens.localCoreWriteAccess() == access) { "OBJECT_EDIT_SESSION_CHANGED" }
        return ObjectEditSnapshot(row, ticket)
    }

    internal suspend fun metric(id: Long): ObjectEditSnapshot<MetricEntity> = sessions.exclusive {
        val access = tokens.localCoreWriteAccess()
        database.withTransaction {
            val row = database.metricDao().getMetricById(id)
            val ticket = row?.takeIf { it.appearance != null }?.let {
                ObjectEditAuthority(requireNotNull(access) { "OBJECT_EDIT_ACCESS_DENIED" }.session,
                    it.uuid, "metric", NextStructureMapper.writeMetric(it), producer.captureDisplayedRoundsInTransaction())
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
        return write(ticket) {
            NextPlanDeletionStore(database).requireWritable(ticket.uuid)
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
        return write(ticket) {
            val current = requireNotNull(database.metricDao().getMetricById(edited.id)) { "OBJECT_EDIT_NOT_FOUND" }
            check(current.uuid == ticket.uuid && NextStructureMapper.writeMetric(current) == ticket.original) {
                "OBJECT_EDIT_CHANGED_RELOAD_REQUIRED"
            }
            val saved = edited.copy(createdAt = current.createdAt, updatedAt = System.currentTimeMillis())
            NextStructureMapper.writeMetric(saved)
            commit(saved)
        }
    }

    /** Existing-object business mutations preserve appearance; no file work or nested account lock. */
    internal suspend fun <T> mutateHabit(expected: HabitEntity, authority: ObjectEditAuthority? = null,
        commit: suspend (HabitEntity) -> T): T {
        val ticket = authority ?: requireNotNull(habit(expected.id).authority) { "OBJECT_WRITE_TICKET_REQUIRED" }
        check(ticket.type == "plan_node" && ticket.uuid == expected.uuid &&
            ticket.original == NextStructureMapper.writePlan(expected)) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        return write(ticket) {
            NextPlanDeletionStore(database).requireWritable(ticket.uuid)
            val current = requireNotNull(database.habitDao().getHabitById(expected.id)) { "OBJECT_WRITE_NOT_FOUND" }
            check(current.uuid == ticket.uuid && NextStructureMapper.writePlan(current) == ticket.original) {
                "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED"
            }
            commit(current)
        }
    }

    /** Retain pending facts, release names and freeze explicit child policy in one original transaction. */
    internal suspend fun deleteHabit(expected: HabitEntity, childPolicy: String?, authority: ObjectEditAuthority? = null): List<HabitEntity> {
        val ticket = authority ?: requireNotNull(habit(expected.id).authority) { "OBJECT_WRITE_TICKET_REQUIRED" }
        check(ticket.type == "plan_node" && ticket.uuid == expected.uuid &&
            ticket.original == NextStructureMapper.writePlan(expected)) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        return write(ticket) {
            val current = requireNotNull(database.habitDao().getHabitById(expected.id)) { "OBJECT_WRITE_NOT_FOUND" }
            check(current.uuid == ticket.uuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
            if (database.habitDao().hasPendingNextDeletion(current.uuid)) {
                val existing = database.syncOutboxDao().getEntityIntents("habit", current.uuid).single { it.action == "delete" }
                check(NextPlanDeletionStore.payload(existing)?.get("child_policy")?.let { it.toString().trim('"') } == childPolicy)
                return@write emptyList() // Exact same deletion is already durable; do not append another.
            }
            check(current.uuid == ticket.uuid && NextStructureMapper.writePlan(current) == ticket.original) {
                "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED"
            }
            val children = database.habitDao().getChildrenByParentUuidOnce(current.uuid)
            if (ticket.rounds != null && current.habitType == com.dayforge.data.model.HabitType.GOAL)
                check(ticket.goalChildUuids == children.map { it.uuid }.sorted()) { "OBJECT_DELETE_CHILDREN_CHANGED_RELOAD_REQUIRED" }
            require(current.habitType == com.dayforge.data.model.HabitType.GOAL || children.isEmpty())
            require(children.all { it.appearance != null && it.habitType != com.dayforge.data.model.HabitType.GOAL }) {
                "OBJECT_WRITE_MIXED_PROTOCOL_OR_INVALID_PARENT"
            }
            val deletion = NextPlanDeletionStore(database)
            if (childPolicy == "cascade_children") children.forEach {
                if (!database.habitDao().hasPendingNextDeletion(it.uuid)) deletion.stage(it, null)
            }
            else children.forEach {
                if (!database.habitDao().hasPendingNextDeletion(it.uuid))
                    database.habitDao().updateParentHabitId(it.id, null)
            }
            deletion.stage(current, childPolicy, children.map { it.uuid })
            if (childPolicy == "cascade_children") listOf(current) + children else listOf(current)
        }
    }

    /** Batch observations/links share one owner and transaction, including all their original intents. */
    internal suspend fun <T> mutateMetrics(expected: List<MetricEntity>, authority: ObjectEditAuthority? = null,
        commit: suspend () -> T): T {
        require(expected.isNotEmpty() && expected.all { it.appearance != null }) { "OBJECT_WRITE_MIXED_PROTOCOL" }
        val first = expected.first()
        val ticket = authority ?: requireNotNull(metric(first.id).authority) { "OBJECT_WRITE_TICKET_REQUIRED" }
        check(ticket.type == "metric" && ticket.uuid == first.uuid &&
            ticket.original == NextStructureMapper.writeMetric(first)) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        return write(ticket) {
            expected.forEach { row ->
                val current = requireNotNull(database.metricDao().getMetricById(row.id)) { "OBJECT_WRITE_NOT_FOUND" }
                check(current.uuid == row.uuid && NextStructureMapper.writeMetric(current) == NextStructureMapper.writeMetric(row)) {
                    "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED"
                }
            }
            commit()
        }
    }

    private suspend fun <T> write(ticket: ObjectEditAuthority, commit: suspend () -> T): T =
        ticket.rounds?.let { producer.writeRounds(it, commit) } ?: producer.write(ticket.session, commit)

    private suspend fun authorize(ticket: ObjectEditAuthority, appearance: ObjectAppearance,
        previous: ObjectAppearance, oneTime: Boolean) {
        // Immutable asset metadata is checked BEFORE entering the producer's non-reentrant account lock.
        // The producer rechecks the captured session and the transaction proves the original structure.
        icons.authorizeEditReference(ticket.session, appearance.icon, previous.icon, oneTime)
    }
}
