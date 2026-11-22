package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.ApprovedBusinessSchema
import com.dayforge.data.local.ReplicaBusinessReferenceSchema
import com.dayforge.data.local.ReplicaSchemaSql
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.NextReplicaFenceSql
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit new-recovery preparation only. Discard optional derived cache before capturing a
 * NEW preview; never mutate an active stage, receipt, business record or command recovery row.
 * This does not repair an invalid control/schema or confer a new root. Read-only proof readers
 * must never call this lifecycle themselves.
 */
internal class CountTrustRecoveryPreparation(private val database: HabitDatabase,
    private val tokens: TokenManager, private val sessions: AccountSessionCoordinator) {
    /** Same original pre-network refusal boundary, without binding a preview or changing
     * optional contents/owner qualification. This Unit result is never a reusable proof.
     */
    suspend fun preflight(expected: LocalSyncAccess) = checked(expected) { _, input ->
        check(!input.present || input.generation < Long.MAX_VALUE) { "COUNT_TRUST_CONTROL_INVALID" }
    }

    suspend fun discardCache(expected: LocalSyncAccess,
        preview: com.dayforge.data.local.CountTrustRecoveryPreview? = null): Boolean = checked(expected) { owner, input ->
        val sql = database.openHelper.writableDatabase
        // The entire original inspection is freshly repeated in this actual cleanup transaction.
        preview?.let { owner.bindRecoveryPreview(it, input.storageId) }
        // Preserve the original direct-cleanup refusal order: invalid preview before overflow.
        check(!input.present || input.generation < Long.MAX_VALUE) { "COUNT_TRUST_CONTROL_INVALID" }
        val discarded = if (!input.present) false else {
            sql.execSQL("UPDATE count_trust_control SET generation=generation+1,dirty=1 WHERE id=1")
            tables.forEach { sql.execSQL("DELETE FROM `$it`") }
            CountTrustSchema.requireControl(sql)
            sql.query("SELECT generation,dirty FROM main.count_trust_control WHERE id=1").use {
                check(it.moveToFirst() && it.getLong(0) == input.generation + 1 && it.getLong(1) == 1L && !it.moveToNext()) {
                    "COUNT_TRUST_RECOVERY_UNEXPECTED_WRITE"
                }
            }
            true
        }
        // From actual cleanup until preview disposal/stage handoff, ordinary reads may use
        // the original validator but must never repopulate the optional cache.
        owner.retireFastPathForRecovery()
        discarded
    }

    private val tables = listOf("count_trust_root", "count_trust_nodes", "count_trust_routes")
    private class CleanupInput(val present: Boolean, val generation: Long, val storageId: String)

    /** The private input exists only within one owned transaction; never returned or cached. */
    private suspend fun <T> checked(expected: LocalSyncAccess,
        action: suspend (CountTrustRuntime, CleanupInput) -> T): T = withContext(Dispatchers.IO) {
        // Pure completed Room metadata only; never capture source data/authority outside its lock.
        val reference = CountTrustRuntime.forDatabase(database)?.let { ReplicaBusinessReferenceSchema.read(it.context) }
        sessions.exclusive { database.withTransaction {
            suspend fun authorize() {
                check(tokens.isCurrentSyncAuthority(expected)) { "SYNC_ACCOUNT_CHANGED" }
                check(tokens.activeReplicaStage() == null) { "SYNC_REPLICA_STAGE_PENDING" }
            }
            authorize()
            val presentation = ReplicaPresentationRepository(database, tokens, sessions)
            val fence = presentation.readFenceInTransaction()
            check(fence == null || fence.mode == NextReplicaFenceEntity.CLOSED) { "SYNC_REPLICA_SWITCH_FENCED" }
            val owner = requireNotNull(CountTrustRuntime.forDatabase(database)) { "COUNT_TRUST_CONTROLLED_DATABASE_REQUIRED" }
            val result = owner.maintenance(database) {
                val sql = database.openHelper.writableDatabase
                CountTrustSchema.validate(sql)
                // Deleting optional rows must not execute an unreviewed trigger that changes
                // business data. Only the existing exact local fence guards may own them.
                fun normalized(text: String): List<String> = ReplicaSchemaSql.tokens(text).let {
                    if (it.size >= 5 && it.take(1) == listOf("word:create") &&
                        it.subList(2, 5) == listOf("word:if", "word:not", "word:exists")) it.take(2) + it.drop(5) else it
                }
                val currentDefinitions = NextReplicaFenceSql.businessAndLocalDefinitions(sql.version)
                val expectedTriggers = NextReplicaFenceSql.localTrustDefinitions().keys.associateWith {
                    normalized(currentDefinitions.getValue(it))
                }
                val actualTriggers = sql.query("SELECT CASE WHEN length(CAST(name AS BLOB))<=256 THEN name ELSE NULL END," +
                    "CASE WHEN length(CAST(sql AS BLOB))<=16384 THEN sql ELSE NULL END FROM main.sqlite_master " +
                    "WHERE type='trigger' AND tbl_name IN (${CountTrustSchema.LOCAL_TABLES.joinToString(",") { "?" }}) LIMIT ${expectedTriggers.size + 1}",
                    CountTrustSchema.LOCAL_TABLES.toTypedArray()).use { cursor -> buildMap {
                    while (cursor.moveToNext()) {
                        check(!cursor.isNull(0) && !cursor.isNull(1)) { "COUNT_TRUST_RECOVERY_SCHEMA_CHANGED" }
                        put(cursor.getString(0), normalized(cursor.getString(1)))
                    }
                } }
                check(actualTriggers == expectedTriggers) { "COUNT_TRUST_RECOVERY_SCHEMA_CHANGED" }
                ApprovedBusinessSchema.requireCleanupInTransaction(sql, requireNotNull(reference))
                val present = tables.any { table -> sql.query("SELECT 1 FROM main.`$table` LIMIT 1").use { it.moveToFirst() } }
                val input = sql.query("SELECT generation,storageId FROM main.count_trust_control WHERE id=1").use {
                    check(it.moveToFirst())
                    CleanupInput(present, it.getLong(0), it.getString(1))
                }
                action(owner, input)
            }
            check(presentation.readFenceInTransaction() == fence) { "SYNC_REPLICA_FENCE_CHANGED" }
            authorize()
            result
        } }
    }
}
