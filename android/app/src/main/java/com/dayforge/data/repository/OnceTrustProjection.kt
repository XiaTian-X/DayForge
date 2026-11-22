package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.OneTimeState
import com.dayforge.domain.service.AccountSessionCoordinator
import kotlinx.serialization.json.jsonObject

/** Existing authenticated ONCE coverage only. Never prepares a root or grants action authority. */
internal class OnceTrustProjection(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    class Read(val habit: HabitEntity, val snapshot: OneTimeLocalSnapshot)
    suspend fun read(activityUuid: String, session: LocalDataSession,
        scope: RecurringDisplayReadScope? = null): Read? {
        check(database.inTransaction())
        if (scope != null) return scope.onceProjection(activityUuid, session)
        val access = tokens.localCoreWriteAccess() ?: return null
        if (access.session != session) return null
        return CountTrustKernel(database, tokens, sessions).withOpen(access) { context ->
            context?.let { readInContext(it, activityUuid, session) }
        }
    }

    /** Invoked only by the existing same-owner DisplayRead participant or this standalone
     * read's authenticated context. It never opens another root/maintenance scope.
     */
    internal suspend fun readInContext(context: CountTrustKernel.Context, activityUuid: String,
        session: LocalDataSession): Read? {
        check(database.inTransaction())
        if (context.access.session != session) return null
        val habit = database.habitDao().getHabitByUuid(activityUuid) ?: return null
        return context.index.withReadScope {
            if (!context.onceEligible(habit)) return@withReadScope null
            val row = context.checked(CountTrustMembership.key("habit.uuid", activityUuid), "habits",
                "uuid=?", arrayOf(activityUuid)).single()
            check(row.long("id") == habit.id) { "ONCE_TRUST_ACTIVITY_CHANGED" }
            val shadow = context.checked(CountTrustMembership.key("shadow.entity", "plan_node", activityUuid),
                "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf("plan_node", activityUuid)).singleOrNull()
            if (shadow?.bool("deleted") == true) throw OneTimeLocalException(OneTimeLocalException.Reason.ENTITY_DELETED)
            // Complete pending membership includes rejected predecessors. This remains O(P),
            // and never enumerates the retained confirmed-history H.
            context.checked(OnceTrustEvidence.pendingKey(activityUuid), "sync_outbox",
                "referenceUuid=? AND recordType IN ('completion','one_time_completion')", arrayOf(activityUuid))
            val snapshot = OnceTrustEvidence.decode(requireNotNull(context.index.get(
                OnceTrustEvidence.projectionKey(activityUuid))).jsonObject, activityUuid, session)
            check(snapshot.confirmed == OneTimeState(requireNotNull(habit.oneTimeConfirmedVersion),
                habit.oneTimeConfirmedHeadEventUuid, habit.oneTimeConfirmedCompletionEventUuid)) {
                "ONCE_TRUST_CONFIRMED_CHANGED"
            }
            snapshot.confirmed.headEventUuid?.let { uuid ->
                val head = context.checked(CountTrustMembership.key("completion.uuid", uuid), "completions",
                    "uuid=?", arrayOf(uuid)).single().completion()
                check(head.habitId == habit.id && head.habitUuid == activityUuid) { "ONCE_TRUST_HEAD_CHANGED" }
            }
            check(tokens.localCoreWriteAccess() == context.access) { "ONCE_TRUST_SESSION_CHANGED" }
            Read(habit, snapshot)
        }
    }
}
