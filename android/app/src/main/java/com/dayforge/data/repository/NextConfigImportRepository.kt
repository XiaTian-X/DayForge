package com.dayforge.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.dayforge.data.appearance.AccountIconContext
import com.dayforge.data.appearance.ValidatedConfigBundle
import com.dayforge.data.export.ConfigImportTarget
import com.dayforge.data.export.NextConfigImportPlan
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.widget.WidgetRefreshScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class NextConfigImportPreview internal constructor(internal val context: AccountIconContext,
    internal val source: ValidatedConfigBundle)

/** Local commit only: individual network operations and material transfers still need real ACKs. */
internal data class NextConfigImportReceipt(val importId: String, val nodes: Int, val metrics: Int, val links: Int)

/**
 * Durable empty-replica import. It deliberately refuses nonempty replacement until deletion/ACK
 * coordination is connected. No clearAllTables, URI reread, cross-database transaction or theme apply.
 * Formal settings still use the old path; injecting this service cannot activate protocol v5.
 */
@Singleton
internal class NextConfigImportRepository @Inject constructor(
    private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val icons: AccountIconController,
    private val creator: NextObjectCreator, @param:ApplicationContext private val appContext: Context
) {
    private val journal get() = NextConfigImportStore(database)

    suspend fun preview(source: ValidatedConfigBundle): NextConfigImportPreview = withContext(Dispatchers.IO) {
        val context = icons.capture()
        guarded(context) { requireEmptyReplica(); journal.requireNoOtherPending() }
        NextConfigImportPreview(context, source)
    }

    /** Destructive counts/readiness only; does not enable replacement or grant a write ticket. */
    suspend fun previewReplacement(source: ValidatedConfigBundle): NextConfigReplacementPreview = withContext(Dispatchers.IO) {
        val context = icons.capture()
        guarded(context) {
            journal.requireNoOtherPending()
            NextConfigReplacementInspector(database).capture(NextConfigImportPreview(context, source))
        }
    }

    /** UI must discard/reopen a changed preview; an old source URI is never read again here. */
    suspend fun recheckReplacement(preview: NextConfigReplacementPreview) = withContext(Dispatchers.IO) {
        guarded(preview.original.context) {
            journal.requireNoOtherPending()
            val current = NextConfigReplacementInspector(database).capture(preview.original)
            check(current.fingerprint == preview.fingerprint && current.counts == preview.counts &&
                current.blockers == preview.blockers) { "CONFIG_REPLACEMENT_PREVIEW_CHANGED" }
            check(current.eligible) { "CONFIG_REPLACEMENT_NOT_READY" }
        }
    }

    /** Confirmation allocates once and commits the original mapping before any material file work. */
    suspend fun confirm(preview: NextConfigImportPreview): String = withContext(Dispatchers.IO) {
        guarded(preview.context) {
            requireEmptyReplica(); journal.requireNoOtherPending()
            val plan = NextConfigImportPlan(preview.source, NextConfigImportPlan.allocate(preview.source,
                ConfigImportTarget.from(preview.context), Instant.now()))
            journal.prepare(plan)
            plan.importId
        }
    }

    /** A cold-start recovery notice, not authority or proof that its original file is still present. */
    suspend fun pendingImportId(): String? = withContext(Dispatchers.IO) {
        val context = icons.capture()
        guarded(context) { journal.pending(context) }
    }

    /** Cold recovery explicitly resupplies the same validated frozen file; never adopts new bytes. */
    suspend fun resume(importId: String, source: ValidatedConfigBundle): NextConfigImportReceipt = withContext(Dispatchers.IO) {
        require(isContractUuid(importId))
        val context = icons.capture()
        val entry = guarded(context) {
            journal.read(context, importId, source).also {
                if (!it.committed) { requireEmptyReplica(); journal.requireNoOtherPending(importId) }
            }
        }
        val plan = entry.plan
        if (entry.committed) return@withContext receipt(plan)
        // The business lock/transaction does not span actual private-file or other database I/O.
        if (plan.iconPack != null) icons.installConfiguration(context, plan)
        val ticket = creator.capture()
        check(ticket.session == context.access.session) { "CONFIG_IMPORT_SESSION_CHANGED" }
        for (row in plan.metrics) icons.authorizeEditReference(ticket.session, requireNotNull(row.appearance).icon,
            IconReference.Role("metric.default"), false)
        val result = creator.habits(plan.habits, ticket) {
            requireAccess(context)
            val original = journal.read(context, importId, source)
            require(original.plan.identities == plan.identities) { "CONFIG_IMPORT_JOURNAL_CHANGED" }
            if (!original.committed) {
                requireEmptyReplica(); journal.requireNoOtherPending(importId)
                val metricIds = plan.metrics.associate { it.uuid to database.metricDao().insert(it) }
                val habitIds = plan.habits.associate { it.uuid to database.habitDao().insert(it) }
                for (link in plan.links(habitIds, metricIds)) database.habitMetricLinkDao().insert(link)
                val sql = database.openHelper.writableDatabase
                val fresh = database.syncOutboxDao().getAll()
                require(fresh.size == plan.creationOperationIds.size &&
                    fresh.map { it.entityUuid }.toSet() == plan.creationOperationIds.keys &&
                    fresh.all { it.action == "upsert" }) { "CONFIG_IMPORT_CREATED_WORK_CHANGED" }
                val sources = fresh.associate { row ->
                    val operationId = plan.creationOperationIds.getValue(row.entityUuid)
                    sql.execSQL("UPDATE sync_outbox SET operationId=? WHERE id=?", arrayOf<Any>(operationId, row.id))
                    val saved = requireNotNull(database.syncOutboxDao().getById(row.id))
                    operationId to ConfigImportedSource(saved.id, NextRequestSql.sourceHash(saved))
                }
                // This receipt, business rows and original outbox/origins commit or roll back together.
                journal.committed(plan, sources)
            }
            requireAccess(context)
            receipt(plan) to !original.committed
        }
        if (result.second) WidgetRefreshScheduler.request(appContext)
        result.first
    }

    /** Explicitly abandon only a prepared local import. Already declared material is retained. */
    suspend fun cancelPrepared(importId: String, source: ValidatedConfigBundle) = withContext(Dispatchers.IO) {
        val context = icons.capture()
        guarded(context) {
            val entry = journal.read(context, importId, source)
            check(!entry.committed) { "CONFIG_IMPORT_ALREADY_COMMITTED" }
            val sql = database.openHelper.writableDatabase
            sql.execSQL("DELETE FROM next_config_import_payloads WHERE importId=?", arrayOf<Any>(importId))
            sql.execSQL("DELETE FROM next_config_imports WHERE importId=?", arrayOf<Any>(importId))
        }
    }

    private suspend fun <T> guarded(context: AccountIconContext, block: suspend () -> T): T = sessions.exclusive {
        requireAccess(context)
        database.withTransaction {
            val result = block(); requireAccess(context); result
        }
    }

    private suspend fun requireAccess(context: AccountIconContext) {
        check(tokens.localIconAccess() == context.access) { "CONFIG_IMPORT_SESSION_CHANGED" }
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "CONFIG_IMPORT_ACCESS_DENIED" }
        check(access.session == context.access.session && access.capturedDeviceId == context.access.deviceId &&
            context.access.canDeclare && access.capabilities?.contains("structure.write") == true) { "CONFIG_IMPORT_ACCESS_DENIED" }
        val sync = requireNotNull(tokens.localSyncAccess()) { "CONFIG_IMPORT_ACCESS_DENIED" }
        check(sync.session == access.session && sync.deviceId == context.access.deviceId &&
            sync.capabilityRevision == context.access.capabilityRevision && tokens.syncAuthenticationSnapshot(sync) != null) {
            "CONFIG_IMPORT_SESSION_CHANGED"
        }
        val sql = database.openHelper.writableDatabase
        // Called both before and inside Room; no business coercion or partial checkpoint adoption.
        if (database.inTransaction()) {
            NextRequestSql.rowHash(sql, "next_sync_state", "1=1", emptyArray())
            val cursor = database.nextSyncStateDao().rows().let { require(it.size <= 1); it.singleOrNull() }
            if (cursor != null) check(cursor.accountId == context.namespace.accountId &&
                cursor.serverInstanceId == context.namespace.serverInstanceId && cursor.syncEpoch == context.namespace.syncEpoch &&
                cursor.deviceId == context.access.deviceId) { "CONFIG_IMPORT_REPLICA_CHANGED" }
            NextChallengeStore(database).readInTransaction(sync, cursor)
        }
    }

    private fun requireEmptyReplica() {
        check(database.inTransaction())
        val sql = database.openHelper.writableDatabase
        NextRequestSql.requireOutboxEnabled(sql)
        // Any facts, retained work, conflicts, staged recovery or old shadow must not be discarded.
        for (table in listOf("habits", "metrics", "habit_metric_links", "completions", "timelogs", "metric_logs",
            "count_days", "timer_segments", "timelog_day_allocations", "sync_outbox", "timer_command_outbox",
            "sync_entity_state", "sync_conflicts", "local_fact_submissions", "completion_metric_prompts",
            "one_time_transmissions", "next_recovery_state", "next_rejections", "next_request_origins",
            "next_transmissions", "next_acceptances", "next_structural_dependencies", "next_structural_supersessions",
            "next_restart_materializations", "next_restart_plan_proofs", "next_challenge_births")) {
            sql.query("SELECT 1 FROM $table LIMIT 1").use {
                check(!it.moveToFirst()) { "CONFIG_IMPORT_REPLACEMENT_NOT_READY" }
            }
        }
    }

    private fun receipt(plan: NextConfigImportPlan) = NextConfigImportReceipt(plan.importId, plan.habits.size,
        plan.metrics.size, plan.creationOperationIds.size - plan.habits.size - plan.metrics.size)
}
