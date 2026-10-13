package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.api.dto.ServerIdentityResponse
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class ReplicaRecoveryReview {
    BUSINESS_QUEUE, TIMER_QUEUE, UNKNOWN_RESULT, UNVERIFIED_SOURCE, REJECTION,
    CONFLICT, UNFINISHED_TIMER, METRIC_DRAFT, CONFIGURATION_JOURNAL, SNAPSHOT_CANDIDATE
}

/** A local point-in-time diagnosis, NOT a fresh authentication/snapshot, archive or switch ticket. */
internal data class ReplicaRecoveryAssessment(
    val original: LocalSyncAccess,
    val observedEpoch: String,
    val checkpoint: NextSyncStateEntity,
    val rowCounts: Map<String, Long>,
    val reviewCounts: Map<ReplicaRecoveryReview, Long>
) {
    val activationAuthorized: Boolean get() = false
    val remainingGates: Set<String> get() = setOf("new_epoch_authentication_and_snapshot",
        "complete_difference_preview", "material_scope_and_preferences", "verified_private_archive",
        "unresolved_work_and_full_proof_review", "explicit_confirmation_and_durable_transition")
}

internal class NextReplicaRecoveryRequired(val assessment: ReplicaRecoveryAssessment) :
    IllegalStateException("SYNC_REPLICA_RECOVERY_REQUIRED")

/** SELECT-only old-scope inventory. Discovery never changes persisted replica/requests/authority. */
internal class NextReplicaRecoveryReader(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    suspend fun read(expected: LocalSyncAccess, identity: ServerIdentityResponse): ReplicaRecoveryAssessment = sessions.exclusive {
        database.withTransaction { readInTransaction(expected, identity) }
    }

    /** Caller owns the account lock and Room snapshot; not a public activation entry. */
    internal suspend fun readInTransaction(expected: LocalSyncAccess, identity: ServerIdentityResponse): ReplicaRecoveryAssessment {
        check(database.inTransaction())
        require(identity.protocolVersion == 5 && isContractUuid(identity.serverInstanceId) && isContractUuid(identity.syncEpoch))
        Instant.parse(identity.serverTime)
        require(identity.serverInstanceId == expected.session.serverInstanceId && identity.syncEpoch != expected.session.syncEpoch) {
            "SYNC_REPLICA_RECOVERY_SCOPE_INVALID"
        }
        check(tokens.isCurrentSyncAuthority(expected)) {
            "SYNC_ACCOUNT_CHANGED"
        }
        return run {
            val sql = database.openHelper.writableDatabase
            require(sql.version == 18) { "SYNC_REPLICA_RECOVERY_SCHEMA_REVIEW_REQUIRED" }
            val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c ->
                buildSet { while (c.moveToNext()) {
                    require(c.getType(0) == Cursor.FIELD_TYPE_STRING)
                    val name = c.getString(0)
                    if (!name.startsWith("sqlite_") && name !in setOf("android_metadata", "room_master_table")) add(name)
                } }
            }
            require(tables == TABLES) { "SYNC_REPLICA_RECOVERY_SCHEMA_REVIEW_REQUIRED" }
            NextProtocolAdmission.requireRoundsOrEmpty(database, expected)
            val checkpoint = NextChallengeStore(database).activeInTransaction(expected)
            // Full existing retirement proof first: a missing source is not implicitly abandoned.
            NextRecoveryAuditStore(database).validatedMembers(expected)
            NextRequestSql.requireOutboxEnabled(sql)
            NextRequestSql.sources(sql, "sync_outbox")
            NextRequestSql.sources(sql, "timer_command_outbox")
            fun count(query: String): Long = sql.query(query).use { c ->
                require(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER)
                c.getLong(0).also { require(it >= 0 && !c.moveToNext()) }
            }
            val rows = TABLES.sorted().associateWith { table ->
                currentCoroutineContext().ensureActive()
                count("SELECT COUNT(*) FROM $table")
            }
            val reviews = linkedMapOf<ReplicaRecoveryReview, Long>()
            fun review(kind: ReplicaRecoveryReview, total: Long) { if (total > 0) reviews[kind] = total }
            review(ReplicaRecoveryReview.BUSINESS_QUEUE, rows.getValue("sync_outbox"))
            review(ReplicaRecoveryReview.TIMER_QUEUE, rows.getValue("timer_command_outbox"))
            review(ReplicaRecoveryReview.UNKNOWN_RESULT, count("""SELECT COUNT(*) FROM next_transmissions t
                WHERE NOT EXISTS(SELECT 1 FROM next_acceptances a WHERE a.kind=t.kind AND a.requestId=t.requestId)
                AND NOT EXISTS(SELECT 1 FROM next_rejections r WHERE r.kind=t.kind AND r.requestId=t.requestId)
                AND NOT EXISTS(SELECT 1 FROM next_abandoned_requests x WHERE x.kind=t.kind AND x.requestId=t.requestId)"""))
            review(ReplicaRecoveryReview.UNVERIFIED_SOURCE, count("""SELECT COUNT(*) FROM next_request_origins o
                WHERE NOT EXISTS(SELECT 1 FROM next_acceptances a WHERE a.kind=o.kind AND a.requestId=o.requestId)
                AND NOT EXISTS(SELECT 1 FROM next_rejections r WHERE r.kind=o.kind AND r.requestId=o.requestId)
                AND NOT EXISTS(SELECT 1 FROM next_abandoned_requests x WHERE x.kind=o.kind AND x.requestId=o.requestId)
                AND NOT EXISTS(SELECT 1 FROM sync_outbox q WHERE o.kind='sync_operation' AND q.operationId=o.requestId)
                AND NOT EXISTS(SELECT 1 FROM timer_command_outbox q WHERE o.kind='timer_command' AND q.commandId=o.requestId)"""))
            review(ReplicaRecoveryReview.REJECTION, count("""SELECT COUNT(*) FROM next_rejections r WHERE
                NOT EXISTS(SELECT 1 FROM next_abandoned_requests x WHERE x.kind=r.kind AND x.requestId=r.requestId)"""))
            review(ReplicaRecoveryReview.CONFLICT, count("SELECT COUNT(*) FROM sync_conflicts WHERE status='unresolved'"))
            review(ReplicaRecoveryReview.UNFINISHED_TIMER, count("SELECT COUNT(*) FROM timelogs WHERE endTime IS NULL"))
            review(ReplicaRecoveryReview.METRIC_DRAFT, count("SELECT COUNT(*) FROM completion_metric_prompts WHERE state='pending'"))
            // Finished import receipts still need full dependency review during the NEXT preview.
            // Counting them is not a claim that every receipt is an unsent operation.
            review(ReplicaRecoveryReview.CONFIGURATION_JOURNAL, rows.getValue("next_config_imports"))
            review(ReplicaRecoveryReview.SNAPSHOT_CANDIDATE,
                count("SELECT COUNT(*) FROM next_recovery_state WHERE phase='awaiting_snapshot'"))
            check(NextChallengeStore(database).activeInTransaction(expected) == checkpoint &&
                tokens.isCurrentSyncAuthority(expected)) {
                "SYNC_ACCOUNT_CHANGED"
            }
            ReplicaRecoveryAssessment(expected.copy(capabilities = expected.capabilities.toSet()), identity.syncEpoch,
                checkpoint.first, rows.toMap(), reviews.toMap())
        }
    }

    companion object {
        // Exhaustive current Room inventory. Unknown future collections require explicit review.
        internal val TABLES = setOf("habits", "completions", "count_days", "timelogs", "metrics", "metric_logs",
            "habit_metric_links", "sync_outbox", "sync_entity_state", "sync_conflicts", "sync_control",
            "timer_command_outbox", "timer_segments", "timelog_day_allocations", "local_fact_submissions",
            "completion_metric_prompts", "one_time_transmissions", "next_recovery_state", "next_request_origins",
            "next_transmissions", "next_acceptances", "next_structural_dependencies", "next_structural_supersessions",
            "next_sync_state", "next_rejections", "next_challenge_rounds", "next_challenge_births", "next_challenge_state",
            "next_restart_materializations", "next_restart_plan_proofs", "next_config_imports", "next_config_import_payloads",
            "next_recovery_audits", "next_abandoned_requests", "next_recovery_audit_payloads", "next_replica_fence")
    }
}
